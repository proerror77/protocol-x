"""Unified RNS/TOC envelope ingress, 3-tier deduplication, and causal reconciliation."""
import base64
import binascii
import hashlib
import json
from datetime import datetime, timezone
from typing import Any, Literal
from uuid import UUID, uuid4

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519
from fastapi import Depends, HTTPException, Response
from pydantic import ConfigDict, Field, UUID4

from .actions import execute_task_action
from .arrival_desk import StageInput
from .bindings import can_read_task, event_and_definition, project_task, ref_of, resolve
from .channel_attachments import MEDIA_TYPES, POSTER_BYTES, VIDEO_MS, mp4_duration, valid_jpeg
from .channel_feed import bump_feed
from .field import stamp
from .field_tasks import ArrivalDispatchInput, DispatchInput
from .models import ActionInput, ActionParameters, MessageInput, ObjectRef, StrictModel
from .ptt_agent import field_recipients, record_incident_in_conn, write_instruction
from .store import dump
from .tak import record_delivery


class EnvelopeTrace(StrictModel):
    hop_count: int = Field(default=0, ge=0, le=32)
    origin_radio: Literal["HTTPS", "WIFI_AWARE", "BLE", "RNS_IP", "LIVEKIT", "TAK_COT"] = "HTTPS"
    rns_packet_hash: str | None = Field(default=None, max_length=128)
    gateway_actor: str | None = Field(default=None, max_length=128)


class EnvelopeAuth(StrictModel):
    key_id: str = Field(default="", max_length=128)
    public_key: str = Field(default="", max_length=256)
    certificate: str = Field(default="", max_length=2048)
    signature: str = Field(default="", max_length=512)
    signature_alg: Literal["ecdsa_p256", "ed25519_rns", "lxmf_verified"] = "ecdsa_p256"


class EnvelopeV1(StrictModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    schema_version: Literal["protocol-x.rns.envelope/v1"] = Field(
        default="protocol-x.rns.envelope/v1", alias="schema"
    )
    envelope_id: str = Field(min_length=8, max_length=128)
    kind: Literal["event", "task", "message", "attachment", "location", "delivery_receipt"]
    event_id: str = Field(min_length=1, max_length=120)
    channel_id: str | None = Field(default=None, max_length=120)
    actor: str = Field(min_length=1, max_length=128)
    device_id: str = Field(min_length=1, max_length=128)
    created_at: str = Field(min_length=1, max_length=64)
    ttl_ms: int = Field(default=86_400_000, ge=1, le=7 * 86_400_000)
    priority: int = Field(default=1, ge=0, le=3)
    idempotency_key: str = Field(min_length=8, max_length=128)
    fingerprint: str = Field(min_length=16, max_length=128)
    trace: EnvelopeTrace = Field(default_factory=EnvelopeTrace)
    auth: EnvelopeAuth | None = None
    payload: dict[str, Any]


class EnvelopeBatchInput(StrictModel):
    envelopes: list[EnvelopeV1] = Field(min_length=1, max_length=64)


def install_rns_envelope_schema(conn):
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS rns_envelopes (
            event_id TEXT NOT NULL,
            envelope_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            actor TEXT NOT NULL,
            device_id TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            status TEXT NOT NULL,
            response_json TEXT NOT NULL,
            trace_json TEXT NOT NULL,
            created_at TEXT NOT NULL,
            PRIMARY KEY(event_id, envelope_id)
        );
        CREATE INDEX IF NOT EXISTS rns_envelopes_idem
            ON rns_envelopes(event_id, idempotency_key);
        CREATE TABLE IF NOT EXISTS field_channel_messages (
            event_id TEXT NOT NULL,
            channel_id TEXT NOT NULL,
            id TEXT NOT NULL,
            actor TEXT NOT NULL,
            speaker TEXT NOT NULL,
            created_at TEXT NOT NULL,
            scope TEXT NOT NULL,
            text TEXT NOT NULL,
            recipient TEXT,
            sealed_payload TEXT,
            assistance INTEGER NOT NULL DEFAULT 0,
            voice_track_sid TEXT,
            fingerprint TEXT NOT NULL,
            PRIMARY KEY(event_id, channel_id, id)
        );
        CREATE INDEX IF NOT EXISTS field_channel_messages_page
            ON field_channel_messages(event_id, channel_id, created_at DESC, id DESC);
        CREATE TABLE IF NOT EXISTS rns_locations (
            event_id TEXT NOT NULL,
            envelope_id TEXT NOT NULL,
            actor TEXT NOT NULL,
            location_kind TEXT NOT NULL,
            title TEXT NOT NULL,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            observed_at TEXT NOT NULL,
            data TEXT NOT NULL,
            PRIMARY KEY(event_id, envelope_id)
        );
        CREATE TABLE IF NOT EXISTS rns_mesh_receipts (
            event_id TEXT NOT NULL,
            target_envelope_id TEXT NOT NULL,
            actor TEXT NOT NULL,
            receipt_kind TEXT NOT NULL,
            via_radio TEXT NOT NULL,
            recorded_at TEXT NOT NULL,
            PRIMARY KEY(event_id, target_envelope_id, actor, receipt_kind)
        );
    """)


def canonical_envelope_signing_bytes(envelope: EnvelopeV1) -> bytes:
    """Deterministic byte representation signed by offline/mesh originators."""
    material = {
        "schema": envelope.schema_version,
        "envelope_id": envelope.envelope_id,
        "kind": envelope.kind,
        "event_id": envelope.event_id,
        "channel_id": envelope.channel_id,
        "actor": envelope.actor,
        "device_id": envelope.device_id,
        "created_at": envelope.created_at,
        "idempotency_key": envelope.idempotency_key,
        "fingerprint": envelope.fingerprint,
        "payload": envelope.payload,
    }
    return dump(material).encode("utf-8")


def _verify_nearby_certificate(conn, certificate: str, expected_actor: str, expected_channel: str | None) -> dict:
    row = conn.execute("SELECT private_key FROM field_nearby_authority WHERE id='v1'").fetchone()
    if row is None:
        raise HTTPException(401, "附近通信证书签发根尚未初始化")
    authority = serialization.load_der_private_key(bytes(row["private_key"]), password=None)
    parts = certificate.split(".", 1)
    if len(parts) != 2:
        raise HTTPException(401, "设备能力证书格式无效")
    try:
        raw_claims = base64.b64decode(parts[0], validate=True)
        sig = base64.b64decode(parts[1], validate=True)
        authority.public_key().verify(sig, raw_claims, ec.ECDSA(hashes.SHA256()))
        claims = json.loads(raw_claims.decode("utf-8"))
    except (ValueError, binascii.Error, InvalidSignature) as error:
        raise HTTPException(401, "设备能力证书验签失败") from error
    if claims.get("actor") != expected_actor:
        raise HTTPException(403, "设备能力证书身份与信封发送方不一致")
    if expected_channel and claims.get("channel") != expected_channel:
        raise HTTPException(403, "设备能力证书频道与信封频道不一致")
    now_ts = int(datetime.now(timezone.utc).timestamp())
    if int(claims.get("expires", 0)) <= now_ts:
        raise HTTPException(401, "设备能力证书已过期")
    return claims


def _verify_envelope_signature(auth: EnvelopeAuth, envelope: EnvelopeV1, claims: dict | None, rns_pub_b64: str | None):
    if not auth.signature:
        raise HTTPException(401, "缺少信封签名")
    signing_bytes = canonical_envelope_signing_bytes(envelope)
    try:
        sig_bytes = base64.b64decode(auth.signature, validate=True)
    except (ValueError, binascii.Error) as error:
        raise HTTPException(401, "信封签名编码无效") from error

    if auth.signature_alg == "ecdsa_p256":
        key_b64 = (claims or {}).get("key") or auth.public_key
        if not key_b64:
            raise HTTPException(401, "缺少 P-256 验签公钥")
        try:
            pub = serialization.load_der_public_key(base64.b64decode(key_b64, validate=True))
            if not isinstance(pub, ec.EllipticCurvePublicKey):
                raise ValueError
            pub.verify(sig_bytes, signing_bytes, ec.ECDSA(hashes.SHA256()))
        except (ValueError, binascii.Error, InvalidSignature) as error:
            raise HTTPException(401, "ECDSA 信封验签失败") from error
        return

    if auth.signature_alg == "ed25519_rns":
        key_b64 = (claims or {}).get("rns_ed25519_pub") or rns_pub_b64 or auth.public_key
        if not key_b64:
            raise HTTPException(401, "缺少 RNS Ed25519 验签公钥")
        try:
            pub = ed25519.Ed25519PublicKey.from_public_bytes(base64.b64decode(key_b64, validate=True))
            pub.verify(sig_bytes, signing_bytes)
        except (ValueError, binascii.Error, InvalidSignature) as error:
            raise HTTPException(401, "RNS Ed25519 信封验签失败") from error
        return


def _as_uuid4_str(value: str, label="编号") -> str:
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise HTTPException(422, f"无效的{label}") from error
    if parsed.version != 4 or str(parsed) != value.lower():
        raise HTTPException(422, f"{label}必须为小写 UUIDv4")
    return str(parsed)


class EnvelopeProcessor:
    def __init__(self, store, definitions, field, *, arrival_desk=None, tak_transport=None, voice_archive=None):
        self.store = store
        self.definitions = definitions
        self.field = field
        self.arrival_desk = arrival_desk
        self.tak_transport = tak_transport
        self.voice_archive = voice_archive
        with store.connect(write=True) as conn:
            install_rns_envelope_schema(conn)

    def _resolve_originator(self, conn, envelope: EnvelopeV1, caller_user: dict, *, allow_gateway_relay: bool):
        is_direct_self = "device" in caller_user and caller_user["actor"] == envelope.actor
        is_demo_self = "device" not in caller_user and caller_user["actor"] == envelope.actor
        if is_direct_self or is_demo_self:
            if envelope.auth and envelope.auth.signature:
                claims = None
                if envelope.auth.certificate:
                    claims = _verify_nearby_certificate(conn, envelope.auth.certificate, envelope.actor, envelope.channel_id)
                _verify_envelope_signature(envelope.auth, envelope, claims, None)
            return caller_user

        if not (allow_gateway_relay or caller_user.get("role") == "dispatcher" or caller_user.get("gateway")):
            raise HTTPException(403, "无权代传其他成员的通信信封")

        if not self.field.enabled:
            raise HTTPException(403, "未启用人员绑定，无法代传信封")

        person = conn.execute(
            "SELECT * FROM field_people WHERE id=? AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=id)",
            (envelope.actor,),
        ).fetchone()
        if person is None:
            raise HTTPException(403, "信封发送方不存在或已停用")

        rns_row = conn.execute("""
            SELECT r.*, d.expires_at AS device_expires, d.revoked_at AS device_revoked
            FROM field_rns_devices r JOIN field_devices d ON d.token_hash=r.token_hash
            WHERE r.person_id=? AND r.revoked_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>?
            ORDER BY r.bound_at DESC LIMIT 1
            """, (envelope.actor, stamp())).fetchone()

        if caller_user.get("lxmf_verified"):
            if rns_row is None or (envelope.device_id and rns_row["dest_hash"] != envelope.device_id):
                raise HTTPException(401, "RNS 终端绑定已失效或被撤销")
            return {**self.field.person(person), "device": rns_row["token_hash"]}

        if envelope.auth is None:
            raise HTTPException(401, "跨节点代传信封必须携带发送方认证与签名")
        claims = None
        if envelope.auth.certificate:
            claims = _verify_nearby_certificate(conn, envelope.auth.certificate, envelope.actor, envelope.channel_id)
        elif rns_row is None:
            raise HTTPException(401, "缺少有效设备证书或 RNS 绑定")
        _verify_envelope_signature(
            envelope.auth, envelope, claims, rns_row["ed25519_pub"] if rns_row else None
        )
        device_hash = rns_row["token_hash"] if rns_row else "relay-" + envelope.device_id
        return {**self.field.person(person), "device": device_hash}

    def ingest(self, envelope: EnvelopeV1, caller_user: dict, *, allow_gateway_relay: bool = False) -> dict:
        event_id = envelope.event_id
        # Handle arrival_stage outside outer lock if needed, after dedup/auth precheck.
        with self.store.connect(write=True) as conn:
            _, definition = event_and_definition(conn, event_id, self.definitions)
            existing = conn.execute(
                "SELECT fingerprint, response_json FROM rns_envelopes WHERE event_id=? AND envelope_id=?",
                (event_id, envelope.envelope_id),
            ).fetchone()
            if existing is not None:
                if existing["fingerprint"] != envelope.fingerprint:
                    raise HTTPException(409, "信封编号已用于不同内容")
                return {**json.loads(existing["response_json"]), "deduplicated": True}

            originator = self._resolve_originator(
                conn, envelope, caller_user, allow_gateway_relay=allow_gateway_relay
            )
            if self.field.enabled and "device" in originator and not self.field.event_access(conn, event_id, originator["actor"]):
                raise HTTPException(403, "发送方未分配到此活动")

            if envelope.kind == "task" and envelope.payload.get("op") == "confirm_arrival_stage":
                # ArrivalDesk.confirm manages its own store transaction.
                result = None
            elif envelope.kind in ("location", "message") or (
                envelope.kind == "event" and envelope.payload.get("event_kind") == "ptt_mic_lifecycle"
            ):
                # Run DB persistence inside this txn, then release lock before TakTransport/VoiceArchive I/O.
                result = self._apply_in_txn(conn, definition, envelope, originator)
                tak_pending = result.pop("_tak_pending", None)
                voice_pending = result.pop("_voice_pending", None)
                if tak_pending is None and voice_pending is None:
                    record = {
                        "envelope_id": envelope.envelope_id,
                        "event_id": event_id,
                        "kind": envelope.kind,
                        "status": "committed",
                        "idempotency_key": envelope.idempotency_key,
                        "deduplicated": False,
                        "result": result,
                    }
                    conn.execute(
                        "INSERT INTO rns_envelopes VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        (
                            event_id,
                            envelope.envelope_id,
                            envelope.kind,
                            originator["actor"],
                            envelope.device_id,
                            envelope.idempotency_key,
                            envelope.fingerprint,
                            "committed",
                            dump(record),
                            dump(envelope.trace.model_dump(mode="json")),
                            stamp(),
                        ),
                    )
                    return record
            else:
                result = self._apply_in_txn(conn, definition, envelope, originator)
                record = {
                    "envelope_id": envelope.envelope_id,
                    "event_id": event_id,
                    "kind": envelope.kind,
                    "status": "committed",
                    "idempotency_key": envelope.idempotency_key,
                    "deduplicated": False,
                    "result": result,
                }
                conn.execute(
                    "INSERT INTO rns_envelopes VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        event_id,
                        envelope.envelope_id,
                        envelope.kind,
                        originator["actor"],
                        envelope.device_id,
                        envelope.idempotency_key,
                        envelope.fingerprint,
                        "committed",
                        dump(record),
                        dump(envelope.trace.model_dump(mode="json")),
                        stamp(),
                    ),
                )
                return record

        if envelope.kind in ("location", "event", "message"):
            if voice_pending is not None and self.voice_archive is not None:
                vp = voice_pending
                self.voice_archive.start(vp["channel_id"], vp["track_sid"], vp["actor"], vp["speaker"], review=False)
                self.voice_archive.finish(vp["channel_id"], vp["track_sid"], vp["audio_bytes"], vp["duration_ms"], False)
                if vp["text"]:
                    self.voice_archive.complete(vp["channel_id"], vp["track_sid"], "ready", vp["text"], "rns_voice_note")
            if tak_pending is not None:
                tak_result = record_delivery(
                    self.store,
                    self.tak_transport,
                    event_id,
                    tak_pending["kind"],
                    tak_pending["operation_id"],
                    tak_pending["content"],
                    originator["actor"],
                )
                result["tak"] = tak_result
            record = {
                "envelope_id": envelope.envelope_id,
                "event_id": event_id,
                "kind": envelope.kind,
                "status": "committed",
                "idempotency_key": envelope.idempotency_key,
                "deduplicated": False,
                "result": result,
            }
            with self.store.connect(write=True) as conn:
                conn.execute(
                    "INSERT OR IGNORE INTO rns_envelopes VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        event_id,
                        envelope.envelope_id,
                        envelope.kind,
                        originator["actor"],
                        envelope.device_id,
                        envelope.idempotency_key,
                        envelope.fingerprint,
                        "committed",
                        dump(record),
                        dump(envelope.trace.model_dump(mode="json")),
                        stamp(),
                    ),
                )
            return record

        # Arrival stage branch (uses ArrivalDesk.confirm's own transaction + receipt idempotency).
        if self.arrival_desk is None:
            raise HTTPException(503, "接待服务尚未启用")
        params = envelope.payload.get("parameters") or {}
        plan_id = (envelope.payload.get("target_ref") or {}).get("id") or envelope.payload.get("plan_id")
        if not plan_id:
            raise HTTPException(422, "接待阶段确认缺少任务编号")
        stage_input = StageInput(
            expected_version=envelope.payload["expected_version"],
            stage=params.get("stage") or envelope.payload.get("stage"),
            idempotency_key=params.get("idempotency_key") or envelope.idempotency_key,
        )
        stage_result = self.arrival_desk.confirm(event_id, plan_id, stage_input, originator)
        record = {
            "envelope_id": envelope.envelope_id,
            "event_id": event_id,
            "kind": envelope.kind,
            "status": "committed",
            "idempotency_key": envelope.idempotency_key,
            "deduplicated": False,
            "result": stage_result,
        }
        with self.store.connect(write=True) as conn:
            conn.execute(
                "INSERT OR IGNORE INTO rns_envelopes VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    event_id,
                    envelope.envelope_id,
                    envelope.kind,
                    originator["actor"],
                    envelope.device_id,
                    envelope.idempotency_key,
                    envelope.fingerprint,
                    "committed",
                    dump(record),
                    dump(envelope.trace.model_dump(mode="json")),
                    stamp(),
                ),
            )
        return record

    def ingest_batch(self, envelopes: list[EnvelopeV1], caller_user: dict, *, allow_gateway_relay: bool = False) -> dict:
        ordered = sorted(
            enumerate(envelopes),
            key=lambda item: (
                item[1].payload.get("client_seq", item[0]) if isinstance(item[1].payload, dict) else item[0],
                item[1].created_at,
                item[0],
            ),
        )
        results = []
        for _, env in ordered:
            try:
                res = self.ingest(env, caller_user, allow_gateway_relay=allow_gateway_relay)
                results.append({"envelope_id": env.envelope_id, "http_status": 200, **res})
            except HTTPException as exc:
                results.append({
                    "envelope_id": env.envelope_id,
                    "event_id": env.event_id,
                    "kind": env.kind,
                    "status": "conflict" if exc.status_code == 409 else "rejected",
                    "http_status": exc.status_code,
                    "detail": exc.detail,
                })
        return {"items": results}

    def _apply_in_txn(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        kind = envelope.kind
        if kind == "task":
            return self._apply_task(conn, definition, envelope, user)
        if kind == "message":
            return self._apply_message(conn, definition, envelope, user)
        if kind == "attachment":
            return self._apply_attachment(conn, definition, envelope, user)
        if kind == "location":
            return self._apply_location(conn, envelope, user)
        if kind == "delivery_receipt":
            return self._apply_receipt(conn, definition, envelope, user)
        if kind == "event":
            return self._apply_event(conn, definition, envelope, user)
        raise HTTPException(422, "未知的信封类型")

    def _apply_task(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        op = p.get("op", "action")
        event_id = envelope.event_id
        if op == "action":
            params_dict = dict(p.get("parameters") or {})
            action_input = ActionInput(
                action_type=p["action_type"],
                target_ref=ObjectRef(**p["target_ref"]),
                expected_version=p["expected_version"],
                idempotency_key=p.get("idempotency_key") or envelope.idempotency_key,
                parameters=ActionParameters(**params_dict),
                proposal_id=p.get("proposal_id"),
            )
            receipt, dedup = execute_task_action(
                conn,
                event_id,
                self.definitions,
                action_input,
                user,
                now_fn=stamp,
                new_id_fn=lambda prefix: f"{prefix}-{uuid4().hex}",
                enrich_conflict=True,
            )
            return {"receipt": receipt, "deduplicated_by_key": dedup}

        if op == "dispatch":
            self.field.require_dispatcher(user)
            params = p.get("parameters") or {}
            body = DispatchInput(
                person_id=params["person_id"],
                request_id=params.get("request_id") or _as_uuid4_str(envelope.idempotency_key, "request_id"),
                title=params["title"],
                location=params["location"],
                contact=params.get("contact", ""),
                due=params.get("due", ""),
            )
            key = "field-dispatch:" + str(body.request_id)
            fp = hashlib.sha256(dump([event_id, user["actor"], body.model_dump(mode="json")]).encode()).hexdigest()
            prev = conn.execute(
                "SELECT fingerprint,data FROM receipts WHERE event_id=? AND idempotency_key=?",
                (event_id, key),
            ).fetchone()
            if prev:
                if prev["fingerprint"] != fp:
                    raise HTTPException(409, "此请求编号已经用于其他派单")
                return {"receipt": json.loads(prev["data"]), "deduplicated_by_key": True}
            person = conn.execute(
                "SELECT * FROM field_people WHERE id=? AND role='staff'", (body.person_id,)
            ).fetchone()
            if person is None or not self.field.event_access(conn, event_id, person["id"]):
                raise HTTPException(422, "请选择此活动中的工作人员")
            binding = definition["binding"]
            at = stamp()
            task_id = "field-" + uuid4().hex
            ref = {"id": task_id, "ontology_id": definition["ontology_id"], "object_type": binding["task_type"]}
            data = {
                binding["title"]: body.title, binding["description"]: body.location,
                binding["owner"]: person["id"], binding["subject"]: person["name"],
                binding["status"]: binding["initial_status"], binding["resource"]: None,
                binding["required_capacity"]: 0,
                binding["progress"]: [
                    {"label": label, "status": "current" if idx == 0 else "pending"}
                    for idx, label in enumerate(binding.get("field_response_milestones", ["出发", "到场", "交接"]))
                ],
                "field_response": {
                    "dispatched_at": at, "location": body.location,
                    "contact": body.contact.strip(), "due": body.due.strip(),
                },
            }
            conn.execute(
                "INSERT INTO objects VALUES (?,?,?,?,?,?)",
                (event_id, ref["ontology_id"], ref["object_type"], ref["id"], 1, dump(data)),
            )
            receipt = {
                "id": "receipt-" + uuid4().hex, "action_type": "dispatch_task", "target_ref": ref,
                "status": "committed", "actor": user["actor"], "speaker": user.get("name"),
                "created_at": at, "idempotency_key": key, "proposal_id": None,
                "result": {"owner_label": person["name"], "object_version": 1, "status_label": "待接单"},
            }
            conn.execute(
                "INSERT INTO receipts (id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
                (receipt["id"], event_id, key, fp, user["actor"], dump(receipt)),
            )
            return {"receipt": receipt, "deduplicated_by_key": False}

        if op == "dispatch_arrival":
            self.field.require_dispatcher(user)
            params = p.get("parameters") or {}
            task_id = (p.get("target_ref") or {}).get("id") or params["task_id"]
            body = ArrivalDispatchInput(
                request_id=params.get("request_id") or _as_uuid4_str(envelope.idempotency_key, "request_id"),
                task_id=task_id,
                expected_version=p["expected_version"],
            )
            key = "field-arrival-dispatch:" + str(body.request_id)
            fp = hashlib.sha256(dump([event_id, user["actor"], body.model_dump(mode="json")]).encode()).hexdigest()
            prev = conn.execute(
                "SELECT fingerprint,data FROM receipts WHERE event_id=? AND idempotency_key=?",
                (event_id, key),
            ).fetchone()
            if prev:
                if prev["fingerprint"] != fp:
                    raise HTTPException(409, "此请求编号已经用于其他派单")
                return {"receipt": json.loads(prev["data"]), "deduplicated_by_key": True}
            row = resolve(conn, event_id, {
                "ontology_id": definition["ontology_id"],
                "object_type": definition["binding"]["task_type"],
                "id": str(body.task_id),
            })
            if not can_read_task(row, definition, user):
                raise HTTPException(404, "找不到可访问的任务")
            data = json.loads(row["data"])
            recipients = field_recipients(row, definition)
            if (
                not data.get("arrival_managed")
                or data.get("arrival_dispatched_at")
                or row["version"] != body.expected_version
                or data[definition["binding"]["progress"]][0]["status"] == "done"
            ):
                raise HTTPException(409, "接机任务已发布或状态有变化，请刷新")
            if not recipients or any(not self.field.event_access(conn, event_id, a) for a in recipients):
                raise HTTPException(422, "请先为司机和接机员分配活动频道")
            at = stamp()
            data["arrival_dispatched_at"] = at
            conn.execute(
                "UPDATE objects SET version=version+1,data=? WHERE event_id=? AND ontology_id=? AND object_type=? AND id=?",
                (dump(data), event_id, row["ontology_id"], row["object_type"], row["id"]),
            )
            receipt = {
                "id": "receipt-" + uuid4().hex, "action_type": "dispatch_arrival_task",
                "target_ref": ref_of(row), "status": "committed", "actor": user["actor"],
                "created_at": at, "idempotency_key": key, "proposal_id": None,
                "result": {"recipients": recipients, "object_version": row["version"] + 1},
            }
            conn.execute(
                "INSERT INTO receipts (id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
                (receipt["id"], event_id, key, fp, user["actor"], dump(receipt)),
            )
            return {"receipt": receipt, "deduplicated_by_key": False}

        raise HTTPException(422, "未知的任务操作")

    def _apply_message(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        scope = p.get("scope", "channel_group")
        event_id = envelope.event_id
        text = (p.get("text") or "").strip()

        if scope == "task_report":
            msg_input = MessageInput(
                text=text,
                subject_ref=ObjectRef(**p["subject_ref"]) if p.get("subject_ref") else None,
            )
            ref = msg_input.subject_ref.model_dump() if msg_input.subject_ref else None
            if ref is not None:
                row = resolve(conn, event_id, ref)
                if row["ontology_id"] != definition["ontology_id"] or row["object_type"] != definition["binding"]["task_type"]:
                    raise HTTPException(422, "目标不支持 WorkItem 能力")
                if not can_read_task(row, definition, user):
                    raise HTTPException(403, "只能汇报本人负责的任务")
            idem_scope = dump([event_id, user["actor"], envelope.idempotency_key])
            msg_id = "msg-" + hashlib.sha256(idem_scope.encode()).hexdigest()
            prev = conn.execute("SELECT data FROM messages WHERE id=? AND event_id=?", (msg_id, event_id)).fetchone()
            if prev:
                stored = json.loads(prev["data"])
                if stored["text"] != msg_input.text or stored["subject_ref"] != ref:
                    raise HTTPException(409, "汇报幂等键已经用于其他内容")
                return {"message": stored, "deduplicated_by_key": True}
            msg = {
                "id": msg_id, "author": user["actor"], "text": msg_input.text,
                "created_at": stamp(), "source": "text", "subject_ref": ref,
            }
            conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (msg_id, event_id, dump(msg)))
            return {"message": msg, "deduplicated_by_key": False}

        if scope == "qrf_assistance":
            ref_raw = p.get("subject_ref")
            if not ref_raw:
                raise HTTPException(422, "求助汇报必须关联任务")
            ref = ObjectRef(**ref_raw).model_dump()
            row = resolve(conn, event_id, ref)
            data = json.loads(row["data"])
            if not data.get("field_response") or data[definition["binding"]["owner"]] != user["actor"]:
                raise HTTPException(403, "只有任务负责人可以上报该派遣任务")
            req_id = _as_uuid4_str(p.get("request_id") or envelope.idempotency_key, "request_id")
            key = f"assist-{req_id}"
            note = (p.get("note") or text).strip()
            msg_text = "请求调度协助" + (" · " + note if note else "")
            prev = conn.execute("SELECT data FROM messages WHERE id=? AND event_id=?", (key, event_id)).fetchone()
            if prev:
                stored = json.loads(prev["data"])
                if stored["author"] != user["actor"] or stored["subject_ref"] != ref or stored["text"] != msg_text:
                    raise HTTPException(409, "请求编号已用于其他内容")
                return {"message": stored, "deduplicated_by_key": True}
            msg = {
                "id": key, "author": user["actor"], "speaker": user.get("name"),
                "text": msg_text, "created_at": stamp(), "source": "qrf_exception",
                "request_kind": "assistance", "subject_ref": ref,
            }
            conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (key, event_id, dump(msg)))
            return {"message": msg, "deduplicated_by_key": False}

        if scope == "qrf_reviewed":
            ref_raw = p.get("subject_ref")
            if not ref_raw or not text:
                raise HTTPException(422, "回报必须关联任务且不能为空")
            ref = ObjectRef(**ref_raw).model_dump()
            row = resolve(conn, event_id, ref)
            data = json.loads(row["data"])
            if (
                user["role"] != "staff"
                or "device" not in user
                or not can_read_task(row, definition, user)
                or not (data.get("field_response") or data.get("arrival_managed"))
            ):
                raise HTTPException(403, "只有该任务分配的现场人员可以提交")
            req_id = _as_uuid4_str(p.get("request_id") or envelope.idempotency_key, "request_id")
            msg_id = f"qrf-reviewed-{req_id}"
            prev = conn.execute("SELECT data FROM messages WHERE id=? AND event_id=?", (msg_id, event_id)).fetchone()
            if prev:
                stored = json.loads(prev["data"])
                if stored["author"] != user["actor"] or stored["text"] != text or stored["subject_ref"] != ref:
                    raise HTTPException(409, "回报编号已经用于其他内容")
                return {"message": stored, "deduplicated_by_key": True}
            msg = {
                "id": msg_id, "author": user["actor"], "speaker": user.get("name"),
                "text": text[:1000], "created_at": stamp(), "source": "qrf_exception", "subject_ref": ref,
            }
            conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (msg_id, event_id, dump(msg)))
            return {"message": msg, "deduplicated_by_key": False}

        if scope == "dispatch_instruction":
            self.field.require_dispatcher(user)
            if not envelope.channel_id or not p.get("incident_id") or not text:
                raise HTTPException(422, "调度指示缺少频道、异常编号或正文")
            channel = self.field.authorized_channel(conn, event_id, envelope.channel_id, user["actor"])
            if channel["kind"] != "group":
                raise HTTPException(403, "只有工作群组可下达频道指示")
            inc_row = conn.execute(
                "SELECT data FROM messages WHERE id=? AND event_id=?", (p["incident_id"], event_id)
            ).fetchone()
            incident = json.loads(inc_row["data"]) if inc_row else None
            if not incident or incident.get("voice_channel_id") != envelope.channel_id or incident.get("request_kind") != "ptt_incident":
                raise HTTPException(404, "找不到频道异常")
            if incident["subject_ref"] is None:
                raise HTTPException(409, "请先确认异常关联的任务")
            task = resolve(conn, event_id, incident["subject_ref"])
            if not can_read_task(task, definition, user):
                raise HTTPException(403, "当前账号无权调度此任务")
            req_id = _as_uuid4_str(p.get("request_id") or envelope.idempotency_key, "request_id")
            key = "instruction-" + req_id
            task_version = int(p["task_version"])
            prev_row = conn.execute("SELECT data FROM messages WHERE id=? AND event_id=?", (key, event_id)).fetchone()
            if prev_row:
                prev = json.loads(prev_row["data"])
                if (
                    prev["author"] != user["actor"]
                    or prev["incident_id"] != incident["id"]
                    or prev["text"] != text
                    or prev["task_version"] != task_version
                ):
                    raise HTTPException(409, "这个请求编号已用于其他指示")
                return {"message": prev, "deduplicated_by_key": True}
            if task["version"] != task_version:
                raise HTTPException(409, "任务分派已更新，请重新核对后发送")
            msg = write_instruction(conn, event_id, incident, task, definition, user, key, text)
            return {"message": msg, "deduplicated_by_key": False}

        if scope in ("channel_group", "channel_direct", "voice_note"):
            if not envelope.channel_id:
                raise HTTPException(422, "频道消息必须指定 channel_id")
            self.field.authorized_channel(conn, event_id, envelope.channel_id, user["actor"])
            sealed = p.get("sealed_payload")
            if sealed and not p.get("recipient"):
                raise HTTPException(422, "加密定向消息必须指定 recipient")
            if not text and not sealed and not p.get("voice_ref"):
                raise HTTPException(422, "频道消息不能为空")
            msg_id = envelope.envelope_id
            fp = hashlib.sha256(dump([
                user["actor"], envelope.channel_id, scope, text, p.get("recipient"), sealed, p.get("voice_ref"),
            ]).encode()).hexdigest()
            prev = conn.execute(
                "SELECT * FROM field_channel_messages WHERE event_id=? AND channel_id=? AND id=?",
                (event_id, envelope.channel_id, msg_id),
            ).fetchone()
            if prev:
                if prev["fingerprint"] != fp:
                    raise HTTPException(409, "频道消息编号已用于其他内容")
                return {"channel_message": dict(prev), "deduplicated_by_key": True}
            track_sid = None
            voice_pending = None
            voice_ref = p.get("voice_ref") or {}
            if scope == "voice_note":
                track_sid = voice_ref.get("track_sid") or f"rns-{envelope.envelope_id}"
                audio_b64 = voice_ref.get("audio_b64")
                if self.voice_archive is not None and audio_b64:
                    try:
                        audio_bytes = base64.b64decode(audio_b64, validate=True)
                    except (ValueError, binascii.Error) as error:
                        raise HTTPException(422, "语音留言音频无效") from error
                    voice_pending = {
                        "channel_id": envelope.channel_id,
                        "track_sid": track_sid,
                        "actor": user["actor"],
                        "speaker": user.get("name", user["actor"]),
                        "audio_bytes": audio_bytes,
                        "duration_ms": int(voice_ref.get("duration_ms") or 0),
                        "text": text,
                    }
            created_at = stamp()
            conn.execute(
                """INSERT INTO field_channel_messages
                   (event_id, channel_id, id, actor, speaker, created_at, scope, text,
                    recipient, sealed_payload, assistance, voice_track_sid, fingerprint)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    event_id, envelope.channel_id, msg_id, user["actor"], user.get("name", user["actor"]),
                    created_at, scope, text[:2000], p.get("recipient"), sealed,
                    int(bool(p.get("assistance"))), track_sid, fp,
                ),
            )
            bump_feed(conn, envelope.channel_id, "ops")
            return {
                "channel_message": {
                    "id": msg_id, "event_id": event_id, "channel_id": envelope.channel_id,
                    "actor": user["actor"], "speaker": user.get("name", user["actor"]),
                    "created_at": created_at, "scope": scope, "text": text[:2000],
                    "recipient": p.get("recipient"), "assistance": bool(p.get("assistance")),
                    "voice_track_sid": track_sid,
                },
                "deduplicated_by_key": False,
                **({"_voice_pending": voice_pending} if voice_pending else {}),
            }

        raise HTTPException(422, "未知的消息作用域")

    def _apply_attachment(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        target_kind = p.get("target_kind")
        event_id = envelope.event_id
        att_id = _as_uuid4_str(str(p.get("attachment_id") or envelope.idempotency_key), "attachment_id")

        if target_kind == "task_photo":
            task_id = p.get("task_id")
            if not task_id:
                raise HTTPException(422, "任务照片缺少 task_id")
            row = resolve(conn, event_id, {
                "ontology_id": definition["ontology_id"],
                "object_type": definition["binding"]["task_type"],
                "id": task_id,
            })
            if not can_read_task(row, definition, user):
                raise HTTPException(404, "找不到可访问的任务")
            jpeg_b64 = p.get("content_b64") or p.get("jpeg")
            if not jpeg_b64:
                raise HTTPException(422, "缺少照片内容")
            try:
                photo = base64.b64decode(jpeg_b64, validate=True)
            except (ValueError, binascii.Error) as error:
                raise HTTPException(422, "无效的照片") from error
            if len(photo) > 1_000_000 or not photo.startswith(b"\xff\xd8\xff") or not photo.endswith(b"\xff\xd9"):
                raise HTTPException(422, "请选择 1 MB 内的 JPEG 照片")
            fp = hashlib.sha256(jpeg_b64.encode()).hexdigest()
            existing = conn.execute("SELECT * FROM field_photos WHERE id=?", (att_id,)).fetchone()
            if existing:
                if (existing["event_id"], existing["task_id"], existing["actor"], existing["fingerprint"]) != (
                    event_id, task_id, user["actor"], fp
                ):
                    raise HTTPException(409, "照片编号已使用")
                return {
                    "photo": {k: existing[k] for k in ("id", "actor", "speaker", "sent_at")},
                    "deduplicated_by_key": True,
                }
            at = stamp()
            conn.execute(
                "INSERT INTO field_photos VALUES (?,?,?,?,?,?,?,?)",
                (att_id, event_id, task_id, user["actor"], user.get("name", user["actor"]), at, fp, photo),
            )
            return {
                "photo": {"id": att_id, "actor": user["actor"], "speaker": user.get("name", user["actor"]), "sent_at": at},
                "deduplicated_by_key": False,
            }

        if target_kind in ("channel_photo", "channel_video"):
            if not envelope.channel_id:
                raise HTTPException(422, "频道附件必须指定 channel_id")
            channel = self.field.authorized_channel(conn, event_id, envelope.channel_id, user["actor"])
            if channel["kind"] != "group":
                raise HTTPException(404, "工作群组才有频道附件")
            media_type = p.get("media_type")
            if media_type not in MEDIA_TYPES:
                raise HTTPException(415, "请选择 JPEG 照片或 MP4 短影片")
            caption = (p.get("caption") or "").strip()
            if len(caption) > 500 or "\x00" in caption:
                raise HTTPException(422, "附件说明最多 500 个字")
            try:
                poster = base64.b64decode(p["poster_b64"], validate=True)
                content = base64.b64decode(p["content_b64"], validate=True)
            except (KeyError, ValueError, binascii.Error) as error:
                raise HTTPException(422, "附件编码无效") from error
            if not (1 <= len(poster) <= POSTER_BYTES) or not valid_jpeg(poster, 480):
                raise HTTPException(422, "附件封面无法读取")
            if len(content) > MEDIA_TYPES[media_type]:
                raise HTTPException(413, "附件超过大小限制")
            duration = None
            if media_type == "image/jpeg":
                if not valid_jpeg(content, 1600):
                    raise HTTPException(422, "照片无法读取")
            else:
                duration = mp4_duration(content)
                if duration is None or not 0 < duration <= VIDEO_MS:
                    raise HTTPException(422, "请选择不超过 30 秒的 MP4 短影片")
            checksum = f"{len(poster)}:{hashlib.sha256(poster + content).hexdigest()}"
            prev = conn.execute(
                "SELECT id,actor,speaker,created_at,caption,media_type,duration_ms,fingerprint "
                "FROM field_channel_attachments WHERE event_id=? AND channel_id=? AND id=?",
                (event_id, envelope.channel_id, att_id),
            ).fetchone()
            if prev is not None:
                if (prev["actor"], prev["media_type"], prev["caption"], prev["fingerprint"]) != (
                    user["actor"], media_type, caption, checksum
                ):
                    raise HTTPException(409, "这个附件编号已使用")
                item = dict(prev)
                del item["fingerprint"]
                return {"attachment": item, "deduplicated_by_key": True}
            item = {
                "id": att_id, "actor": user["actor"], "speaker": user.get("name", user["actor"]),
                "created_at": stamp(), "caption": caption, "media_type": media_type, "duration_ms": duration,
            }
            conn.execute(
                """INSERT INTO field_channel_attachments
                   (event_id,channel_id,id,actor,speaker,created_at,caption,media_type,duration_ms,
                    fingerprint,poster,content) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                (event_id, envelope.channel_id, *item.values(), checksum, poster, content),
            )
            bump_feed(conn, envelope.channel_id, "ops")
            return {"attachment": item, "deduplicated_by_key": False}

        raise HTTPException(422, "未知的附件类型")

    def _apply_location(self, conn, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        loc_kind = p.get("location_kind", "point_marker")
        title = (p.get("title") or user.get("callsign") or user.get("name") or user["actor"]).strip()[:80]
        lat = float(p["latitude"])
        lon = float(p["longitude"])
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise HTTPException(422, "坐标超出有效范围")
        observed_at = p.get("observed_at") or envelope.created_at
        conn.execute(
            "INSERT OR IGNORE INTO rns_locations VALUES (?,?,?,?,?,?,?,?,?)",
            (envelope.event_id, envelope.envelope_id, user["actor"], loc_kind, title, lat, lon, observed_at, dump(p)),
        )
        tak_pending = None
        if (
            self.tak_transport is not None
            and self.tak_transport.settings.event_id == envelope.event_id
            and p.get("project_to_tak", True)
            and loc_kind in ("point_marker", "peer_pli")
        ):
            if loc_kind == "point_marker":
                tak_pending = {
                    "kind": "point",
                    "operation_id": envelope.idempotency_key,
                    "content": {"title": title, "latitude": lat, "longitude": lon},
                }
            else:
                tak_pending = {
                    "kind": "peer_pli",
                    "operation_id": user["actor"],
                    "content": {
                        "callsign": title,
                        "latitude": lat,
                        "longitude": lon,
                        "cot_type": p.get("cot_type") or "a-f-G-U-C",
                        "remarks": f"RNS PLI · {observed_at}",
                    },
                }
        return {
            "location": {"title": title, "latitude": lat, "longitude": lon, "kind": loc_kind},
            **({"_tak_pending": tak_pending} if tak_pending else {}),
        }

    def _apply_receipt(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        receipt_kind = p.get("receipt_kind")
        event_id = envelope.event_id

        if receipt_kind == "task_delivered":
            task_id = (p.get("target_ref") or {}).get("id") or p.get("task_id")
            if not task_id:
                raise HTTPException(422, "送达确认缺少 task_id")
            row = resolve(conn, event_id, {
                "ontology_id": definition["ontology_id"],
                "object_type": definition["binding"]["task_type"],
                "id": task_id,
            })
            data = json.loads(row["data"])
            if not (
                (data.get("field_response") and data[definition["binding"]["owner"]] == user["actor"])
                or (data.get("arrival_dispatched_at") and user["actor"] in field_recipients(row, definition))
            ):
                raise HTTPException(403, "只有负责人终端能确认送达")
            conn.execute(
                "INSERT OR IGNORE INTO field_task_delivery VALUES (?,?,?,?)",
                (event_id, task_id, user["actor"], stamp()),
            )
            row_del = conn.execute(
                "SELECT received_at FROM field_task_delivery WHERE event_id=? AND task_id=? AND person_id=?",
                (event_id, task_id, user["actor"]),
            ).fetchone()
            return {"received_at": row_del["received_at"]}

        if receipt_kind == "instruction_received":
            msg_id = p.get("target_message_id")
            row_msg = conn.execute(
                "SELECT data FROM messages WHERE id=? AND event_id=?", (msg_id, event_id)
            ).fetchone()
            msg = json.loads(row_msg["data"]) if row_msg else None
            if not msg or msg.get("source") != "dispatch_instruction" or user["actor"] not in msg.get("recipients", []):
                raise HTTPException(404, "找不到给你的指示")
            key = f"dispatch-receive:{msg_id}:{user['actor']}"
            prev = conn.execute(
                "SELECT data FROM receipts WHERE event_id=? AND idempotency_key=?", (event_id, key)
            ).fetchone()
            if prev:
                return {"receipt": json.loads(prev["data"]), "deduplicated_by_key": True}
            receipt = {
                "id": f"receipt-{uuid4().hex}", "action_type": "receive_dispatch_instruction",
                "target_ref": msg["subject_ref"], "status": "committed", "actor": user["actor"],
                "created_at": stamp(), "idempotency_key": key, "proposal_id": None,
                "result": {"message_id": msg_id},
            }
            conn.execute(
                "INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES(?,?,?,?,?,?)",
                (receipt["id"], event_id, key, msg_id, user["actor"], dump(receipt)),
            )
            return {"receipt": receipt, "deduplicated_by_key": False}

        if receipt_kind == "qrf_acknowledged":
            if user["role"] != "dispatcher":
                raise HTTPException(403, "只有中台可以确认收到异常")
            report_id = p.get("target_message_id")
            row_msg = conn.execute(
                "SELECT data FROM messages WHERE id=? AND event_id=?", (report_id, event_id)
            ).fetchone()
            msg = json.loads(row_msg["data"]) if row_msg else {}
            if msg.get("source") != "qrf_exception":
                raise HTTPException(404, "找不到异常回报")
            key = f"qrf-ack:{report_id}"
            prev = conn.execute(
                "SELECT data FROM receipts WHERE event_id=? AND idempotency_key=?", (event_id, key)
            ).fetchone()
            if prev:
                return {"receipt": json.loads(prev["data"]), "deduplicated_by_key": True}
            target = msg.get("subject_ref") or {
                "ontology_id": definition["ontology_id"], "object_type": "ChannelIncident", "id": report_id,
            }
            receipt = {
                "id": f"receipt-{uuid4().hex}", "action_type": "ack_qrf_exception",
                "target_ref": target, "status": "committed", "actor": user["actor"],
                "speaker": user.get("name"), "created_at": stamp(),
                "idempotency_key": key, "proposal_id": None, "result": {"message_id": report_id},
            }
            conn.execute(
                "INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES(?,?,?,?,?,?)",
                (receipt["id"], event_id, key, report_id, user["actor"], dump(receipt)),
            )
            return {"receipt": receipt, "deduplicated_by_key": False}

        if receipt_kind in ("mesh_delivered", "mesh_read"):
            target_env = p.get("target_envelope_id") or envelope.idempotency_key
            via = p.get("via_radio") or envelope.trace.origin_radio
            at = stamp()
            conn.execute(
                "INSERT OR IGNORE INTO rns_mesh_receipts VALUES (?,?,?,?,?,?)",
                (event_id, target_env, user["actor"], receipt_kind, via, at),
            )
            return {"target_envelope_id": target_env, "receipt_kind": receipt_kind, "recorded_at": at}

        raise HTTPException(422, "未知的回执类型")

    def _apply_event(self, conn, definition: dict, envelope: EnvelopeV1, user: dict) -> dict:
        p = envelope.payload
        ev_kind = p.get("event_kind")
        if ev_kind == "ptt_incident":
            if not envelope.channel_id:
                raise HTTPException(422, "ptt_incident 缺少 channel_id")
            attrs = p.get("attributes") or {}
            track_sid = attrs.get("track_sid") or f"rns-{envelope.envelope_id}"
            text = (attrs.get("text") or "").strip()
            msg_id = record_incident_in_conn(
                conn,
                self.definitions,
                envelope.channel_id,
                {"actor": user["actor"], "track_sid": track_sid, "text": text, "review_engine": attrs.get("review_engine")},
            )
            return {"track_sid": track_sid, "message_id": msg_id}
        if ev_kind == "ptt_mic_lifecycle":
            attrs = p.get("attributes") or {}
            op_id = attrs.get("livekit_event_id") or envelope.idempotency_key
            if self.tak_transport is not None and self.tak_transport.settings.event_id == envelope.event_id:
                phase = attrs.get("phase", "started")
                label = "开启" if phase == "started" else "关闭"
                content = {
                    "text": f"PTT 麦克风音轨{label} · {user.get('name', user['actor'])}",
                    "event": {
                        "phase": phase, "actor": user["actor"],
                        "session_id": str(attrs.get("session_id") or ""),
                        "track_sid": str(attrs.get("track_sid") or ""),
                        "room": str(attrs.get("room") or envelope.channel_id or ""),
                        "livekit_event_id": str(op_id),
                        "observed_at": str(attrs.get("observed_at") or envelope.created_at),
                    },
                }
                return {
                    "recorded": True,
                    "_tak_pending": {"kind": "ptt", "operation_id": str(op_id), "content": content},
                }
            return {"recorded": True}
        return {"recorded": True, "event_kind": ev_kind}


def register_rns_envelopes(app, identity, processor: EnvelopeProcessor):
    @app.post("/api/rns/v1/envelopes")
    def submit_envelope(body: EnvelopeV1, response: Response, user=Depends(identity)):
        response.headers["Cache-Control"] = "private, no-store"
        return processor.ingest(body, user, allow_gateway_relay=user.get("role") == "dispatcher")

    @app.post("/api/rns/v1/envelopes/batch")
    def submit_envelope_batch(body: EnvelopeBatchInput, response: Response, user=Depends(identity)):
        response.headers["Cache-Control"] = "private, no-store"
        return processor.ingest_batch(body.envelopes, user, allow_gateway_relay=user.get("role") == "dispatcher")
