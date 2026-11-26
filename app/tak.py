"""Send explicitly shared Ops information to OpenTAKServer over TLS CoT."""
import hashlib
import http.client
import json
import logging
import os
import re
import socket
import ssl
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from threading import Lock
from urllib.parse import quote, urlsplit
from uuid import NAMESPACE_URL, uuid4, uuid5
from xml.etree.ElementTree import Element, ParseError, SubElement, fromstring, tostring

from fastapi import Depends, HTTPException, Request, Response
from pydantic import UUID4, Field

from .models import StrictModel
from .store import dump

LOG = logging.getLogger(__name__)
NO_POSITION = 9999999
CHAT_TYPE = "b-t-f"
MARKER_TYPE = "b-m-p-s-m"
PTT_TYPE = "t-x-ops-ptt"
PEER_PLI_TYPE = "a-f-G-U-C"
TASK_MARKER_TYPE = "b-m-p-s-m"
ID_PATTERN = re.compile(r"[A-Za-z0-9_.:-]{1,80}\Z")
INVALID_XML = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\ud800-\udfff\ufffe\uffff]")


@dataclass(frozen=True)
class TakSettings:
    event_id: str
    cot_host: str
    cot_port: int
    readback_host: str
    readback_port: int
    sender_uid: str
    recipient_uid: str
    ca_file: str
    cert_file: str
    key_file: str
    report_point: tuple[float, float] | None
    ptt_events: bool = False

    @classmethod
    def from_env(cls):
        fields = ("EVENT_ID", "COT_HOST", "MARTI_URL", "SENDER_UID", "RECIPIENT_UID",
                  "CA_FILE", "CERT_FILE", "KEY_FILE")
        values = {key: os.getenv("OPS_TAK_" + key, "").strip() for key in fields}
        port = os.getenv("OPS_TAK_COT_PORT", "")
        reference = [os.getenv("OPS_TAK_REPORT_" + name, "").strip() for name in ("LATITUDE", "LONGITUDE")]
        ptt = os.getenv("OPS_TAK_PTT_EVENTS", "false").strip().lower()
        if ptt not in {"true", "false"}:
            raise ValueError("OPS_TAK_PTT_EVENTS must be true or false.")
        if not any(values.values()) and not port and not any(reference) and ptt == "false":
            return None
        if not all(values.values()):
            raise ValueError("Configure the activity, CoT host, Marti URL, UIDs and client TLS files for TAK.")
        sender, recipient = values["SENDER_UID"], values["RECIPIENT_UID"]
        if (not ID_PATTERN.fullmatch(sender) or not ID_PATTERN.fullmatch(recipient)
                or sender == recipient):
            raise ValueError("Set two distinct TAK UIDs using at most 80 supported ASCII characters.")
        marti = urlsplit(values["MARTI_URL"])
        if (marti.scheme != "https" or not marti.hostname or marti.username is not None
                or marti.path not in ("", "/") or marti.query or marti.fragment):
            raise ValueError("OPS_TAK_MARTI_URL must be an HTTPS origin.")
        cot_port = int(port or "8089")
        if not 1 <= cot_port <= 65535:
            raise ValueError("OPS_TAK_COT_PORT must be a valid TCP port.")
        report_point = None
        if any(reference):
            if not all(reference):
                raise ValueError("Set both coordinates of the TAK activity reference point.")
            latitude, longitude = map(float, reference)
            if not (-90 <= latitude <= 90 and -180 <= longitude <= 180) or (latitude, longitude) == (0, 0):
                raise ValueError("Set a valid nonzero activity reference point for GeoChat.")
            report_point = (latitude, longitude)
        return cls(values["EVENT_ID"], values["COT_HOST"], cot_port, marti.hostname, marti.port or 443,
                   sender, recipient, values["CA_FILE"], values["CERT_FILE"], values["KEY_FILE"],
                   report_point, ptt_events=ptt == "true")

    def destination(self):
        return [self.cot_host, self.cot_port, self.readback_host, self.readback_port,
                self.sender_uid, self.recipient_uid]


class ShareReport(StrictModel):
    excerpt: str | None = Field(default=None, max_length=255)


class SharePoint(StrictModel):
    request_id: UUID4
    title: str = Field(min_length=1, max_length=80)
    latitude: float = Field(ge=-90, le=90, allow_inf_nan=False, strict=True)
    longitude: float = Field(ge=-180, le=180, allow_inf_nan=False, strict=True)


def cot_time(value):
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def cot_event(uid, event_type, timestamp, latitude=NO_POSITION, longitude=NO_POSITION,
              *, how="h-g-i-g-o", stale_minutes=30):
    stamp = cot_time(timestamp)
    event = Element("event", {"version": "2.0", "uid": uid, "type": event_type, "how": how,
                              "time": stamp, "start": stamp,
                              "stale": cot_time(timestamp + timedelta(minutes=stale_minutes))})
    SubElement(event, "point", {"lat": str(latitude), "lon": str(longitude),
                                "hae": str(NO_POSITION), "ce": str(NO_POSITION), "le": str(NO_POSITION)})
    return event


def cot_payload(settings, uid, kind, content, timestamp):
    if kind == "ptt":
        event = cot_event(uid, PTT_TYPE, timestamp)
        detail = SubElement(event, "detail")
        SubElement(SubElement(detail, "marti"), "dest", {"uid": settings.recipient_uid})
        SubElement(detail, "ops_ptt", content["event"])
        SubElement(detail, "remarks").text = content["text"]
        return event

    if kind == "peer_pli":
        event = cot_event(
            uid, content.get("cot_type") or PEER_PLI_TYPE, timestamp,
            content["latitude"], content["longitude"], how="m-g", stale_minutes=15,
        )
        detail = SubElement(event, "detail")
        SubElement(SubElement(detail, "marti"), "dest", {"uid": settings.recipient_uid})
        SubElement(detail, "contact", {"callsign": content["callsign"]})
        if content.get("remarks"):
            SubElement(detail, "remarks").text = content["remarks"]
        return event

    if kind == "task_marker":
        event = cot_event(
            uid, content.get("cot_type") or TASK_MARKER_TYPE, timestamp,
            content["latitude"], content["longitude"],
        )
        detail = SubElement(event, "detail")
        SubElement(SubElement(detail, "marti"), "dest", {"uid": settings.recipient_uid})
        SubElement(detail, "contact", {"callsign": content["title"]})
        if content.get("remarks"):
            SubElement(detail, "remarks").text = content["remarks"]
        return event

    is_report = kind == "report"
    coordinates = (content.get("reference_point", (0, 0)) if is_report
                   else (content["latitude"], content["longitude"]))
    event = cot_event(uid, CHAT_TYPE if is_report else MARKER_TYPE, timestamp, *coordinates)
    detail = SubElement(event, "detail")
    SubElement(SubElement(detail, "marti"), "dest", {"uid": settings.recipient_uid})
    if is_report:
        chat = SubElement(detail, "__chat", {"id": settings.recipient_uid,
                                             "chatroom": settings.recipient_uid,
                                             "senderCallsign": settings.sender_uid,
                                             "messageId": uid.rsplit(".", 1)[-1]})
        SubElement(chat, "chatgrp", {"id": settings.recipient_uid, "uid0": settings.sender_uid,
                                    "uid1": settings.recipient_uid})
        SubElement(detail, "link", {"uid": settings.sender_uid, "type": "a-f-G-U-C", "relation": "p-p"})
        if "reference_point" in content:
            SubElement(detail, "precisionlocation", {"geolocationsrc": "activity-reference"})
        SubElement(detail, "remarks", {"source": f"BAO.F.ATAK.{settings.sender_uid}",
                                       "to": settings.recipient_uid, "time": cot_time(timestamp)}).text = (
            content["text"].replace("\r\n", "\n").replace("\r", "\n"))
    else:
        SubElement(detail, "contact", {"callsign": content["title"]})
    return event


class TakTransport:
    def __init__(self, settings):
        self.settings = settings
        self.context = ssl.create_default_context(cafile=settings.ca_file)
        self.context.load_cert_chain(settings.cert_file, settings.key_file)
        self.send_lock = Lock()

    def read_back(self, expected):
        config = self.settings
        connection = http.client.HTTPSConnection(config.readback_host, config.readback_port,
                                                 context=self.context, timeout=2)
        uid = expected.get("uid")
        try:
            connection.request("GET", "/Marti/api/cot/xml/" + quote(uid, safe=""))
            reply = connection.getresponse()
            if reply.status == 404:
                return False
            if reply.status != 200:
                raise OSError(f"TAK readback HTTP {reply.status}")
            data = reply.read(16_385)
            if len(data) > 16_384 or b"<!DOCTYPE" in data.upper():
                raise ValueError("Unexpected CoT response")
            record = fromstring(data)
        finally:
            connection.close()
        destination = record.find("detail/marti/dest")
        if (record.tag != "event" or record.get("uid") != uid or record.get("type") != expected.get("type")
                or destination is None or destination.get("uid") != config.recipient_uid
                or record.findtext("detail/remarks") != expected.findtext("detail/remarks")):
            raise ValueError("OpenTAKServer returned a different event")
        actual_point, expected_point = record.find("point"), expected.find("point")
        if actual_point is None or any(float(actual_point.get(key, "nan")) != float(expected_point.get(key))
                                       for key in ("lat", "lon")):
            raise ValueError("OpenTAKServer returned different coordinates")
        if expected.get("type") == PTT_TYPE:
            actual_detail, expected_detail = record.find("detail/ops_ptt"), expected.find("detail/ops_ptt")
            if actual_detail is None or actual_detail.attrib != expected_detail.attrib:
                raise ValueError("OpenTAKServer returned a different PTT event")
        if expected.get("type") == MARKER_TYPE:
            actual_contact, expected_contact = record.find("detail/contact"), expected.find("detail/contact")
            if actual_contact is None or actual_contact.get("callsign") != expected_contact.get("callsign"):
                raise ValueError("OpenTAKServer returned a different point")
        if expected.get("type") == PEER_PLI_TYPE:
            actual_contact, expected_contact = record.find("detail/contact"), expected.find("detail/contact")
            if actual_contact is None or actual_contact.get("callsign") != expected_contact.get("callsign"):
                raise ValueError("OpenTAKServer returned a different peer PLI")
        return True

    def deliver(self, event):
        """Return retryable only when no CoT payload has been written."""
        # OTS uses a device UID for one TLS session. Share that identity serially.
        with self.send_lock:
            return self._deliver(event)

    def _deliver(self, event):
        config = self.settings
        identity = cot_event(config.sender_uid, "a-f-G-U-C", datetime.now(timezone.utc))
        detail = SubElement(identity, "detail")
        SubElement(detail, "contact", {"callsign": config.sender_uid})
        SubElement(detail, "takv", {"platform": "Ops", "device": "Ops adapter"})
        SubElement(SubElement(detail, "marti"), "dest", {"uid": config.recipient_uid})
        payload = b"\n".join(tostring(node, encoding="us-ascii") for node in (identity, event))
        try:
            with socket.create_connection((config.cot_host, config.cot_port), timeout=3) as raw:
                client = self.context.wrap_socket(raw, server_hostname=config.cot_host)
        except OSError as error:
            LOG.warning("OpenTAKServer TLS connection failed: %s", type(error).__name__)
            return "retryable"

        with client:
            try:
                client.sendall(payload)
                deadline = time.monotonic() + 4
                while time.monotonic() < deadline:
                    if self.read_back(event):
                        return "received"
                    time.sleep(0.25)
            except (OSError, http.client.HTTPException, ParseError, ValueError) as error:
                LOG.warning("OpenTAKServer delivery could not be confirmed: %s", type(error).__name__)
        return "awaiting_readback"


def tak_status(row):
    return {"cot_uid": row["cot_uid"], "status": row["status"], "recipient_uid": row["recipient_uid"],
            "received_at": row["received_at"]}


def tak_projection(conn, tak, event_id, user):
    if tak is None or tak.event_id != event_id or user["role"] != "dispatcher":
        return None
    rows = conn.execute("SELECT * FROM tak_deliveries WHERE event_id=? AND kind='report'", (event_id,))
    return {"recipient_uid": tak.recipient_uid, "report_reference_point": tak.report_point,
            "reports": {row["operation_id"]: {**tak_status(row), "excerpt": json.loads(row["payload"])["text"]}
                        for row in rows}}


def record_delivery(store, transport, event_id, kind, operation_id, content, actor):
    config = transport.settings
    fingerprint = hashlib.sha256(dump([config.destination(), content]).encode()).hexdigest()
    key = (event_id, kind, operation_id)
    with store.connect(write=True) as conn:
        row = conn.execute("""SELECT * FROM tak_deliveries
                              WHERE event_id=? AND kind=? AND operation_id=?""", key).fetchone()
        should_send = row is None or row["status"] == "retryable"
        if row is None:
            uid = uuid4().hex
            if kind == "report":
                uid = f"GeoChat.{config.sender_uid}.{config.recipient_uid}.{uid}"
            elif kind == "peer_pli":
                uid = f"OpsPeer-{event_id}-{operation_id}"
            elif kind == "task_marker":
                uid = f"OpsTask-{event_id}-{operation_id}"
            else:
                uid = ("OpsPtt-" if kind == "ptt" else "OpsPoint-") + uid
            created_at = cot_time(datetime.now(timezone.utc))
            conn.execute("""INSERT INTO tak_deliveries
                            (event_id, kind, operation_id, cot_uid, fingerprint, payload, status,
                             recipient_uid, actor, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'awaiting_readback', ?, ?, ?)""",
                         (*key, uid, fingerprint, dump(content), config.recipient_uid, actor, created_at))
        else:
            if row["fingerprint"] != fingerprint:
                raise HTTPException(409, "这条 TAK 共享请求已关联其他内容或接收端")
            if row["status"] == "received":
                return tak_status(row)
            uid, created_at = row["cot_uid"], row["created_at"]
            if should_send:
                conn.execute("""UPDATE tak_deliveries SET status='awaiting_readback'
                                WHERE event_id=? AND kind=? AND operation_id=?""", key)

    event = cot_payload(config, uid, kind, content, datetime.fromisoformat(created_at))
    if should_send:
        status = transport.deliver(event)
    else:
        try:
            status = "received" if transport.read_back(event) else "awaiting_readback"
        except (OSError, http.client.HTTPException, ParseError, ValueError):
            status = "awaiting_readback"

    received_at = cot_time(datetime.now(timezone.utc)) if status == "received" else None
    with store.connect(write=True) as conn:
        if should_send or received_at:
            conn.execute("""UPDATE tak_deliveries SET status=?, received_at=?
                            WHERE event_id=? AND kind=? AND operation_id=? AND status<>'received'""",
                         (status, received_at, *key))
        return tak_status(conn.execute("""SELECT * FROM tak_deliveries
                                          WHERE event_id=? AND kind=? AND operation_id=?""", key).fetchone())


def install_tak_inbound_schema(conn):
    conn.execute("""
        CREATE TABLE IF NOT EXISTS tak_inbound_events (
            event_id TEXT NOT NULL,
            cot_uid TEXT NOT NULL,
            cot_time TEXT NOT NULL,
            envelope_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            data TEXT NOT NULL,
            received_at TEXT NOT NULL,
            PRIMARY KEY(event_id, cot_uid, cot_time)
        )
    """)


def is_echo_cot(conn, sender_uid: str | None, cot_uid: str) -> str | None:
    if sender_uid and (cot_uid == sender_uid or cot_uid.startswith(f"GeoChat.{sender_uid}.")):
        return "self_originated"
    if cot_uid.startswith(("OpsPoint-", "OpsPtt-", "OpsPeer-", "OpsTask-")):
        return "ops_prefix_echo"
    if conn.execute("SELECT 1 FROM tak_deliveries WHERE cot_uid=?", (cot_uid,)).fetchone():
        return "known_outbound_uid"
    return None


def ingest_inbound_cot(store, event_id: str, xml_bytes: bytes, *, sender_uid: str | None = None):
    """Parse an inbound ATAK CoT event, suppress echoes, and store deterministic evidence."""
    if len(xml_bytes) > 16_384 or b"<!DOCTYPE" in xml_bytes.upper():
        raise HTTPException(422, "无效的 CoT XML")
    try:
        record = fromstring(xml_bytes)
    except ParseError as error:
        raise HTTPException(422, "CoT XML 解析失败") from error
    if record.tag != "event":
        raise HTTPException(422, "CoT 根节点必须为 event")
    cot_uid = (record.get("uid") or "").strip()
    cot_type = (record.get("type") or "").strip()
    cot_time_str = (record.get("time") or record.get("start") or "").strip()
    if not cot_uid or not cot_type or not cot_time_str:
        raise HTTPException(422, "CoT 缺少 uid、type 或 time")

    point = record.find("point")
    if point is None:
        raise HTTPException(422, "CoT 缺少 point 节点")
    try:
        lat = float(point.get("lat", ""))
        lon = float(point.get("lon", ""))
    except ValueError as error:
        raise HTTPException(422, "CoT 坐标无效") from error

    remarks = (record.findtext("detail/remarks") or "").strip()
    contact = record.find("detail/contact")
    callsign = (contact.get("callsign") if contact is not None else "") or ""
    if INVALID_XML.search(remarks) or INVALID_XML.search(callsign):
        raise HTTPException(422, "CoT 文本包含非法字符")

    envelope_id = str(uuid5(NAMESPACE_URL, f"tak:{cot_uid}:{cot_time_str}"))
    now_iso = cot_time(datetime.now(timezone.utc))
    with store.connect(write=True) as conn:
        install_tak_inbound_schema(conn)
        echo_reason = is_echo_cot(conn, sender_uid, cot_uid)
        if echo_reason:
            return {"status": "ignored", "reason": echo_reason, "cot_uid": cot_uid}

        existing = conn.execute(
            "SELECT envelope_id, kind, data, received_at FROM tak_inbound_events WHERE event_id=? AND cot_uid=? AND cot_time=?",
            (event_id, cot_uid, cot_time_str),
        ).fetchone()
        if existing:
            return {
                "status": "deduplicated",
                "envelope_id": existing["envelope_id"],
                "cot_uid": cot_uid,
                "kind": existing["kind"],
                "data": json.loads(existing["data"]),
                "received_at": existing["received_at"],
            }

        is_chat = cot_type == CHAT_TYPE
        kind = "message" if is_chat else "location"
        payload = {
            "cot_uid": cot_uid,
            "cot_type": cot_type,
            "cot_time": cot_time_str,
            "stale": record.get("stale"),
            "callsign": callsign,
            "remarks": remarks,
            "latitude": lat,
            "longitude": lon,
        }
        conn.execute(
            "INSERT INTO tak_inbound_events VALUES (?,?,?,?,?,?,?)",
            (event_id, cot_uid, cot_time_str, envelope_id, kind, dump(payload), now_iso),
        )
        if is_chat and remarks:
            msg_id = "tak-" + hashlib.sha256(f"{event_id}:{cot_uid}:{cot_time_str}".encode()).hexdigest()[:32]
            message = {
                "id": msg_id,
                "author": f"tak:{callsign or cot_uid}",
                "speaker": callsign or cot_uid,
                "text": remarks[:4000],
                "created_at": now_iso,
                "source": "tak_cot",
                "subject_ref": None,
                "cot_uid": cot_uid,
            }
            conn.execute("INSERT OR IGNORE INTO messages(id,event_id,data) VALUES (?,?,?)",
                         (msg_id, event_id, dump(message)))
        return {
            "status": "ingested",
            "envelope_id": envelope_id,
            "cot_uid": cot_uid,
            "kind": kind,
            "data": payload,
            "received_at": now_iso,
        }


def register_tak(app, identity, store, settings):
    with store.connect(write=True) as conn:
        install_tak_inbound_schema(conn)
    transport = TakTransport(settings) if settings else None

    def require_tak(event_id, user):
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可以共享到 OpenTAKServer")
        if settings is None or settings.event_id != event_id:
            raise HTTPException(404, "当前活动没有配置 OpenTAKServer")

    def share(response, event_id, kind, operation_id, content, user):
        result = record_delivery(store, transport, event_id, kind, operation_id, content, user["actor"])
        response.status_code = {"received": 200, "awaiting_readback": 202, "retryable": 503}[result["status"]]
        if result["status"] == "retryable":
            result["detail"] = "未能建立到 OpenTAKServer 的连接，可以重试同一条发布"
        return result

    @app.post("/api/events/{event_id}/tak/reports/{message_id}")
    def share_report(event_id: str, message_id: str, body: ShareReport, response: Response, user=Depends(identity)):
        require_tak(event_id, user)
        with store.connect() as conn:
            row = conn.execute("SELECT data FROM messages WHERE event_id=? AND id=?", (event_id, message_id)).fetchone()
            saved = conn.execute("""SELECT payload FROM tak_deliveries
                                 WHERE event_id=? AND kind='report' AND operation_id=?""",
                                 (event_id, message_id)).fetchone()
        if row is None:
            raise HTTPException(404, "当前活动找不到这条汇报")
        original = json.loads(row["data"])["text"]
        previous = json.loads(saved["payload"]) if saved else {"text": original}
        text = (previous["text"] if body.excerpt is None else body.excerpt).strip()
        if not text or len(text) > 255 or text not in original or INVALID_XML.search(text):
            raise HTTPException(422, "选择汇报中一段可共享的原文，最多 255 字")
        content = {"text": text}
        reference = previous.get("reference_point") if saved else settings.report_point
        if reference:
            content["reference_point"] = reference
        return share(response, event_id, "report", message_id, content, user)

    @app.post("/api/events/{event_id}/tak/points")
    def share_point(event_id: str, body: SharePoint, response: Response, user=Depends(identity)):
        require_tak(event_id, user)
        title = body.title.strip()
        if not title or INVALID_XML.search(title):
            raise HTTPException(422, "请输入有效的点位名称")
        content = {"title": title, "latitude": body.latitude, "longitude": body.longitude}
        return share(response, event_id, "point", str(body.request_id), content, user)

    @app.post("/api/events/{event_id}/tak/inbound")
    async def receive_inbound_cot(event_id: str, request: Request, user=Depends(identity)):
        require_tak(event_id, user)
        xml_bytes = await request.body()
        return ingest_inbound_cot(store, event_id, xml_bytes, sender_uid=settings.sender_uid)

    @app.get("/api/events/{event_id}/tak/deliveries")
    def list_deliveries(event_id: str, user=Depends(identity)):
        require_tak(event_id, user)
        with store.connect() as conn:
            rows = conn.execute(
                "SELECT event_id, kind, operation_id, cot_uid, status, recipient_uid, actor, created_at, received_at "
                "FROM tak_deliveries WHERE event_id=? ORDER BY created_at DESC LIMIT 100",
                (event_id,),
            ).fetchall()
        return {"items": [dict(row) for row in rows]}

    return transport

