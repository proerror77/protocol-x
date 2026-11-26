"""Authenticated Python RNS + LXMF gateway bridging field mesh <-> TOC <-> TAK CoT."""
import base64
import hashlib
import json
import logging
import os
import tempfile
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal
from uuid import uuid4

from fastapi import Depends, HTTPException, Response
from pydantic import Field

from .field import stamp
from .field_nearby import derive_channel_ifac
from .models import StrictModel
from .rns_envelopes import EnvelopeProcessor, EnvelopeTrace, EnvelopeV1
from .store import dump
from .tak import ingest_inbound_cot

LOG = logging.getLogger(__name__)

FIELD_FILE_ATTACHMENTS = 0x05
FIELD_CUSTOM_TYPE = 0xFB
FIELD_CUSTOM_DATA = 0xFC
ENVELOPE_CUSTOM_TYPE = b"protocol-x.rns.envelope/v1"


@dataclass(frozen=True)
class RnsGatewaySettings:
    storage_dir: Path
    listen_host: str = "127.0.0.1"
    listen_port: int = 4242
    connect_peers: tuple[tuple[str, int], ...] = ()
    ifac_network_name: str | None = None
    ifac_passphrase: str | None = None
    enable_transport: bool = False
    enable_propagation: bool = False
    display_name: str = "protocol-x-toc-gateway"

    @classmethod
    def from_env(cls):
        enabled = os.getenv("OPS_RNS_ENABLED", "false").strip().lower() == "true"
        if not enabled:
            return None
        storage = Path(os.getenv("OPS_RNS_STORAGE_DIR", tempfile.gettempdir()) or tempfile.gettempdir()) / "px-rns-gw"
        host = os.getenv("OPS_RNS_LISTEN_HOST", "127.0.0.1").strip() or "127.0.0.1"
        port = int(os.getenv("OPS_RNS_LISTEN_PORT", "4242"))
        peers = []
        raw_peers = os.getenv("OPS_RNS_CONNECT_PEERS", "").strip()
        for entry in raw_peers.split(","):
            entry = entry.strip()
            if not entry:
                continue
            p_host, _, p_port = entry.rpartition(":")
            if p_host and p_port.isdigit():
                peers.append((p_host, int(p_port)))
        netname = os.getenv("OPS_RNS_IFAC_NETNAME", "").strip() or None
        passphrase = os.getenv("OPS_RNS_IFAC_PASSPHRASE", "").strip() or None
        transport = os.getenv("OPS_RNS_ENABLE_TRANSPORT", "false").strip().lower() == "true"
        prop = os.getenv("OPS_RNS_ENABLE_PROPAGATION", "false").strip().lower() == "true"
        display = os.getenv("OPS_RNS_DISPLAY_NAME", "protocol-x-toc-gateway").strip() or "protocol-x-toc-gateway"
        return cls(
            storage_dir=storage,
            listen_host=host,
            listen_port=port,
            connect_peers=tuple(peers),
            ifac_network_name=netname,
            ifac_passphrase=passphrase,
            enable_transport=transport,
            enable_propagation=prop,
            display_name=display,
        )


class GatewayDispatchInput(StrictModel):
    event_id: str = Field(min_length=1, max_length=120)
    channel_id: str | None = Field(default=None, max_length=120)
    kind: Literal["event", "task", "message", "location", "delivery_receipt"] = "message"
    recipient_actors: list[str] = Field(min_length=1, max_length=64)
    title: str = Field(default="TOC Dispatch", min_length=1, max_length=120)
    idempotency_key: str = Field(min_length=8, max_length=128)
    payload: dict[str, Any]
    desired_method: Literal["direct", "opportunistic", "propagated"] = "direct"


def install_rns_gateway_schema(conn):
    conn.execute("""
        CREATE TABLE IF NOT EXISTS rns_outbound_deliveries (
            event_id TEXT NOT NULL,
            envelope_id TEXT NOT NULL,
            dest_hash TEXT NOT NULL,
            actor TEXT NOT NULL,
            lxmf_message_hash TEXT,
            status TEXT NOT NULL,
            detail TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            ack_received_at TEXT,
            PRIMARY KEY(event_id, envelope_id, dest_hash)
        )
    """)


def _decode_field_text(value) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def decode_lxmf_to_envelope(message, source_hex: str, resolved_actor: str, *, gateway_actor: str = "toc-gateway") -> EnvelopeV1:
    """Decode an inbound LXMF message (EnvelopeV1 JSON or RnsEnvelopeCodec) into EnvelopeV1."""
    fields = getattr(message, "fields", None) or {}
    custom_type = fields.get(FIELD_CUSTOM_TYPE)
    custom_data = fields.get(FIELD_CUSTOM_DATA)
    raw_json = None

    if custom_type in (ENVELOPE_CUSTOM_TYPE, "protocol-x.rns.envelope/v1") and custom_data:
        raw_json = json.loads(_decode_field_text(custom_data))
    else:
        content = message.content_as_string() or ""
        if content.lstrip().startswith("{"):
            parsed = json.loads(content)
            if parsed.get("schema") == "protocol-x.rns.envelope/v1" or "envelope_id" in parsed:
                raw_json = parsed

    if raw_json is not None:
        trace = dict(raw_json.get("trace") or {})
        trace.setdefault("origin_radio", "RNS_IP")
        trace["rns_packet_hash"] = message.hash.hex() if getattr(message, "hash", None) else trace.get("rns_packet_hash")
        trace["gateway_actor"] = gateway_actor
        raw_json["trace"] = trace
        return EnvelopeV1.model_validate(raw_json)

    # Compatibility mapping for Android RnsEnvelopeCodec compact title/content + attachments.
    title_str = message.title_as_string() or "{}"
    meta = json.loads(title_str)
    content = message.content_as_string() or ""
    envelope_id = meta.get("id") or str(uuid4())
    idem_key = meta.get("idem") or envelope_id
    event_id = meta["event"]
    channel_id = meta.get("channel") or None
    kind_name = meta.get("kind", "CHANNEL_TEXT")
    recipient = meta.get("recipient") or ""
    assistance = bool(meta.get("assist", False))
    attachments = fields.get(FIELD_FILE_ATTACHMENTS) or []

    kind_map = {
        "CHANNEL_TEXT": ("message", "channel_group"),
        "PRIVATE_ENCRYPTED": ("message", "channel_direct"),
        "ASSISTANCE_ALERT": ("message", "qrf_assistance"),
        "VOICE_NOTE": ("message", "voice_note"),
        "CONTROL_ACK": ("delivery_receipt", "mesh_read"),
        "DELIVERY_RECEIPT": ("delivery_receipt", "mesh_delivered"),
    }
    if kind_name == "ATTACHMENT_MEDIA" and attachments:
        att_name = _decode_field_text(attachments[0][0])
        att_bytes = bytes(attachments[0][1])
        is_video = att_name.endswith(".mp4")
        payload = {
            "attachment_id": envelope_id,
            "target_kind": "channel_video" if is_video else "channel_photo",
            "media_type": "video/mp4" if is_video else "image/jpeg",
            "caption": content[:500],
            "content_b64": base64.b64encode(att_bytes).decode("ascii"),
            "poster_b64": base64.b64encode(att_bytes).decode("ascii"),
        }
        env_kind = "attachment"
    elif kind_name == "TASK_ACTION":
        env_kind = "task"
        payload = json.loads(content) if content.lstrip().startswith("{") else {}
    else:
        env_kind, scope = kind_map.get(kind_name, ("message", "channel_group"))
        if env_kind == "delivery_receipt":
            target_id = content.split(":", 1)[1] if ":" in content else envelope_id
            payload = {"receipt_kind": scope, "target_envelope_id": target_id, "via_radio": "WIFI"}
        else:
            payload = {
                "scope": scope,
                "text": content,
                "recipient": recipient or None,
                "assistance": assistance,
            }
            if scope == "voice_note" and attachments:
                payload["voice_ref"] = {
                    "audio_b64": base64.b64encode(bytes(attachments[0][1])).decode("ascii"),
                    "duration_ms": 1000,
                }

    fp = hashlib.sha256(dump([event_id, resolved_actor, idem_key, payload]).encode()).hexdigest()
    return EnvelopeV1(
        envelope_id=envelope_id,
        kind=env_kind,
        event_id=event_id,
        channel_id=channel_id,
        actor=resolved_actor,
        device_id=source_hex,
        created_at=stamp(),
        idempotency_key=idem_key,
        fingerprint=fp,
        trace=EnvelopeTrace(
            origin_radio="RNS_IP",
            rns_packet_hash=message.hash.hex() if getattr(message, "hash", None) else None,
            gateway_actor=gateway_actor,
        ),
        payload=payload,
    )


class RnsGateway:
    """Reticulum + LXMF Gateway running on private IFAC-isolated interfaces."""

    def __init__(self, settings: RnsGatewaySettings, store, processor: EnvelopeProcessor, *, tak_settings=None):
        self.settings = settings
        self.store = store
        self.processor = processor
        self.tak_settings = tak_settings
        self.running = False
        self.reticulum = None
        self.router = None
        self.identity = None
        self.delivery_destination = None
        self.inbound_events: list[dict] = []
        with store.connect(write=True) as conn:
            install_rns_gateway_schema(conn)

    @property
    def identity_hash_hex(self) -> str:
        return self.identity.hash.hex() if self.identity else ""

    @property
    def lxmf_destination_hash_hex(self) -> str:
        return self.delivery_destination.hash.hex() if self.delivery_destination else ""

    def ifac_for_channel(self, event_id: str, channel_id: str) -> dict | None:
        with self.store.connect() as conn:
            row = conn.execute(
                "SELECT secret FROM field_nearby_channels WHERE channel_id=?", (channel_id,)
            ).fetchone()
        if row is None:
            return None
        return derive_channel_ifac(event_id, channel_id, row["secret"])

    def _build_rns_config(self, rns_dir: Path) -> None:
        lines = [
            "[reticulum]",
            f"  enable_transport = {'Yes' if self.settings.enable_transport else 'No'}",
            "  share_instance = No",
            "",
            "[logging]",
            "  loglevel = 2",
            "",
            "[interfaces]",
        ]
        ifac_lines = []
        if self.settings.ifac_network_name:
            ifac_lines.append(f"    network_name = {self.settings.ifac_network_name}")
        if self.settings.ifac_passphrase:
            ifac_lines.append(f"    passphrase = {self.settings.ifac_passphrase}")

        if self.settings.listen_port > 0:
            lines.extend([
                "  [[ProtocolX Gateway TCP Server]]",
                "    type = TCPServerInterface",
                "    enabled = Yes",
                f"    listen_ip = {self.settings.listen_host}",
                f"    listen_port = {self.settings.listen_port}",
                *ifac_lines,
            ])
        for idx, (peer_host, peer_port) in enumerate(self.settings.connect_peers):
            lines.extend([
                f"  [[ProtocolX Gateway TCP Peer {idx}]]",
                "    type = TCPClientInterface",
                "    enabled = Yes",
                f"    target_host = {peer_host}",
                f"    target_port = {peer_port}",
                *ifac_lines,
            ])
        (rns_dir / "config").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def start(self):
        if self.running:
            return
        import LXMF
        import RNS

        base = self.settings.storage_dir
        rns_dir = base / "rns"
        lxmf_dir = base / "lxmf"
        rns_dir.mkdir(parents=True, exist_ok=True)
        lxmf_dir.mkdir(parents=True, exist_ok=True)
        self._build_rns_config(rns_dir)

        self.reticulum = RNS.Reticulum(configdir=str(rns_dir))
        RNS.Transport.STAMP_DESTINATIONS = False
        RNS.Transport.STAMP_LRS = False
        RNS.Transport.STAMP_PRS = False

        id_file = base / "gateway.id"
        if id_file.is_file():
            self.identity = RNS.Identity.from_file(str(id_file))
        if self.identity is None:
            self.identity = RNS.Identity()
            self.identity.to_file(str(id_file))

        self.router = LXMF.LXMRouter(storagepath=str(lxmf_dir), propagation_cost=0)
        self.delivery_destination = self.router.register_delivery_identity(
            self.identity, display_name=self.settings.display_name, stamp_cost=0
        )
        if self.settings.enable_propagation:
            self.router.set_message_storage_limit(megabytes=20.0)
            self.router.enable_propagation()
        self.router.register_delivery_callback(self._on_lxmf_delivery)
        self.router.announce(self.delivery_destination.hash)
        self.running = True

    def stop(self):
        if not self.running:
            return
        self.running = False
        with suppress(Exception):
            if self.router is not None:
                self.router.exit_handler()
        with suppress(Exception):
            import RNS

            RNS.Reticulum.exit_handler()
        self.router = None
        self.reticulum = None

    def _lookup_active_rns_device(self, dest_hash_hex: str) -> dict | None:
        with self.store.connect() as conn:
            row = conn.execute("""
                SELECT r.token_hash, r.person_id, r.dest_hash, p.name, p.callsign, p.role
                FROM field_rns_devices r
                JOIN field_devices d ON d.token_hash=r.token_hash
                JOIN field_people p ON p.id=r.person_id
                WHERE r.dest_hash=? AND r.revoked_at IS NULL
                  AND d.revoked_at IS NULL AND d.expires_at>?
                  AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=p.id)
                ORDER BY r.bound_at DESC LIMIT 1
                """, (dest_hash_hex, stamp())).fetchone()
            return dict(row) if row else None

    def _on_lxmf_delivery(self, message) -> None:
        try:
            self.handle_inbound_lxmf(message)
        except Exception:
            LOG.exception("Failed handling inbound LXMF message")

    def handle_inbound_lxmf(self, message) -> dict:
        if not getattr(message, "signature_validated", False):
            return {"status": "rejected", "reason": "invalid_lxmf_signature"}
        source_hash = getattr(message, "source_hash", None)
        source_hex = source_hash.hex() if source_hash else ""
        if not source_hex:
            return {"status": "rejected", "reason": "missing_source_hash"}

        device = self._lookup_active_rns_device(source_hex)
        if device is None:
            return {"status": "rejected", "reason": "revoked_or_unknown_device", "source": source_hex}

        envelope = decode_lxmf_to_envelope(
            message,
            source_hex,
            device["person_id"],
            gateway_actor=self.lxmf_destination_hash_hex or "toc-gateway",
        )
        if envelope.actor != device["person_id"]:
            return {"status": "rejected", "reason": "actor_mismatch"}

        caller = {
            "actor": device["person_id"],
            "name": device["name"],
            "callsign": device["callsign"],
            "role": device["role"],
            "device": device["token_hash"],
            "lxmf_verified": True,
            "gateway": True,
        }
        try:
            result = self.processor.ingest(envelope, caller, allow_gateway_relay=True)
            receipt_status = "committed"
            receipt_detail = result
        except HTTPException as exc:
            receipt_status = "conflict" if exc.status_code == 409 else "rejected"
            receipt_detail = {"http_status": exc.status_code, "detail": exc.detail}

        receipt_env = self._send_receipt_to_peer(source_hex, envelope, receipt_status, receipt_detail)
        outcome = {"status": receipt_status, "envelope_id": envelope.envelope_id, "detail": receipt_detail, "receipt": receipt_env}
        self.inbound_events.append(outcome)
        return outcome

    def _send_receipt_to_peer(self, dest_hash_hex: str, orig: EnvelopeV1, status: str, detail: dict) -> dict | None:
        receipt_kind = "tak_readback" if (orig.kind == "location" and isinstance(detail, dict) and "tak" in detail.get("result", {})) else "action_committed"
        receipt_payload = {
            "receipt_kind": receipt_kind,
            "target_envelope_id": orig.envelope_id,
            "via_radio": "RNS_IP",
            "status": status,
            "detail": detail,
        }
        env_id = str(uuid4())
        fp = hashlib.sha256(dump([orig.event_id, orig.envelope_id, status, detail]).encode()).hexdigest()
        receipt_env = {
            "schema": "protocol-x.rns.envelope/v1",
            "envelope_id": env_id,
            "kind": "delivery_receipt",
            "event_id": orig.event_id,
            "channel_id": orig.channel_id,
            "actor": "toc-gateway",
            "device_id": self.lxmf_destination_hash_hex or "toc-gateway",
            "created_at": stamp(),
            "ttl_ms": 86_400_000,
            "priority": 3,
            "idempotency_key": f"gw-rcpt-{orig.envelope_id}",
            "fingerprint": fp,
            "trace": {"hop_count": 0, "origin_radio": "RNS_IP", "gateway_actor": "toc-gateway"},
            "payload": receipt_payload,
        }
        if self.running and self.router is not None:
            self._queue_lxmf_message(dest_hash_hex, "TOC Receipt", receipt_env, desired_method="direct")
        return receipt_env

    def _queue_lxmf_message(self, dest_hash_hex: str, title: str, envelope_dict: dict, *, desired_method: str = "direct") -> str | None:
        import LXMF
        import RNS

        dest_bytes = bytes.fromhex(dest_hash_hex)
        peer_id = RNS.Identity.recall(dest_bytes)
        if peer_id is None:
            RNS.Transport.request_path(dest_bytes)
            return None
        dest = RNS.Destination(peer_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        method_map = {
            "direct": LXMF.LXMessage.DIRECT,
            "opportunistic": LXMF.LXMessage.OPPORTUNISTIC,
            "propagated": LXMF.LXMessage.PROPAGATED,
        }
        packed_json = dump(envelope_dict).encode("utf-8")
        msg = LXMF.LXMessage(
            dest,
            self.delivery_destination,
            packed_json.decode("utf-8"),
            title=title,
            fields={FIELD_CUSTOM_TYPE: ENVELOPE_CUSTOM_TYPE, FIELD_CUSTOM_DATA: packed_json},
            desired_method=method_map.get(desired_method, LXMF.LXMessage.DIRECT),
        )
        event_id = envelope_dict["event_id"]
        envelope_id = envelope_dict["envelope_id"]

        def on_delivered(lxm):
            with self.store.connect(write=True) as conn:
                conn.execute(
                    "UPDATE rns_outbound_deliveries SET status='delivered', updated_at=?, ack_received_at=? "
                    "WHERE event_id=? AND envelope_id=? AND dest_hash=?",
                    (stamp(), stamp(), event_id, envelope_id, dest_hash_hex),
                )

        def on_failed(lxm):
            with self.store.connect(write=True) as conn:
                conn.execute(
                    "UPDATE rns_outbound_deliveries SET status='failed', updated_at=? "
                    "WHERE event_id=? AND envelope_id=? AND dest_hash=?",
                    (stamp(), event_id, envelope_id, dest_hash_hex),
                )

        msg.register_delivery_callback(on_delivered)
        msg.register_failed_callback(on_failed)
        self.router.handle_outbound(msg)
        return msg.hash.hex() if getattr(msg, "hash", None) else None

    def dispatch_to_field(
        self,
        event_id: str,
        *,
        kind: str,
        payload: dict,
        recipient_actors: list[str],
        idempotency_key: str,
        channel_id: str | None = None,
        title: str = "TOC Dispatch",
        desired_method: str = "direct",
    ) -> list[dict]:
        """Dispatch a downward TOC/TAK message/task to active field RNS destinations."""
        now = stamp()
        with self.store.connect() as conn:
            placeholders = ",".join("?" for _ in recipient_actors)
            rows = conn.execute(f"""
                SELECT DISTINCT r.person_id, r.dest_hash
                FROM field_rns_devices r
                JOIN field_devices d ON d.token_hash=r.token_hash
                JOIN field_members m ON m.person_id=r.person_id
                JOIN field_channels c ON c.id=m.channel_id
                WHERE c.event_id=? AND c.kind='group'
                  AND r.person_id IN ({placeholders})
                  AND r.revoked_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>?
                  AND NOT EXISTS (SELECT 1 FROM field_suspensions s WHERE s.person_id=r.person_id)
                """, (event_id, *recipient_actors, now)).fetchall()

        deliveries = []
        for row in rows:
            actor = row["person_id"]
            dest_hash = row["dest_hash"]
            envelope_id = str(uuid4())
            fp = hashlib.sha256(dump([event_id, actor, idempotency_key, payload]).encode()).hexdigest()
            env_dict = {
                "schema": "protocol-x.rns.envelope/v1",
                "envelope_id": envelope_id,
                "kind": kind,
                "event_id": event_id,
                "channel_id": channel_id,
                "actor": "toc-gateway",
                "device_id": self.lxmf_destination_hash_hex or "toc-gateway",
                "created_at": now,
                "ttl_ms": 86_400_000,
                "priority": 2,
                "idempotency_key": f"{idempotency_key}:{actor}",
                "fingerprint": fp,
                "trace": {"hop_count": 0, "origin_radio": "RNS_IP", "gateway_actor": "toc-gateway"},
                "payload": payload,
            }
            msg_hash = None
            status = "queued"
            if self.running and self.router is not None:
                msg_hash = self._queue_lxmf_message(dest_hash, title, env_dict, desired_method=desired_method)
                status = "dispatched" if msg_hash else "path_pending"
            with self.store.connect(write=True) as conn:
                conn.execute(
                    "INSERT OR IGNORE INTO rns_outbound_deliveries VALUES (?,?,?,?,?,?,?,?,?,NULL)",
                    (event_id, envelope_id, dest_hash, actor, msg_hash, status, dump(env_dict), now, now),
                )
            deliveries.append({
                "envelope_id": envelope_id,
                "actor": actor,
                "dest_hash": dest_hash,
                "lxmf_message_hash": msg_hash,
                "status": status,
            })
        return deliveries

    def ingest_tak_and_dispatch(self, event_id: str, xml_bytes: bytes, recipient_actors: list[str]) -> dict:
        """Ingest inbound ATAK CoT XML (with echo suppression) and relay downward over RNS."""
        sender_uid = self.tak_settings.sender_uid if self.tak_settings else None
        ingested = ingest_inbound_cot(self.store, event_id, xml_bytes, sender_uid=sender_uid)
        if ingested["status"] != "ingested":
            return ingested
        data = ingested["data"]
        if ingested["kind"] == "message":
            kind = "message"
            payload = {"scope": "channel_group", "text": data["remarks"], "cot_uid": data["cot_uid"]}
        else:
            kind = "location"
            payload = {
                "location_kind": "point_marker",
                "title": data["callsign"] or data["cot_uid"],
                "latitude": data["latitude"],
                "longitude": data["longitude"],
                "cot_uid": data["cot_uid"],
                "project_to_tak": False,
            }
        dispatches = self.dispatch_to_field(
            event_id,
            kind=kind,
            payload=payload,
            recipient_actors=recipient_actors,
            idempotency_key=f"tak-down:{ingested['envelope_id']}",
            title="TAK Update",
        )
        return {**ingested, "dispatches": dispatches}


@asynccontextmanager
async def rns_gateway_lifespan(gateway: RnsGateway | None):
    if gateway is None:
        yield
        return
    gateway.start()
    try:
        yield
    finally:
        gateway.stop()


def register_rns_gateway(app, identity, gateway: RnsGateway):
    @app.get("/api/rns/v1/gateway/status")
    def gateway_status(user=Depends(identity)):
        return {
            "running": gateway.running,
            "identity_hash": gateway.identity_hash_hex,
            "lxmf_destination_hash": gateway.lxmf_destination_hash_hex,
            "ifac_enabled": bool(gateway.settings.ifac_network_name or gateway.settings.ifac_passphrase),
        }

    @app.post("/api/rns/v1/gateway/dispatch")
    def gateway_dispatch(body: GatewayDispatchInput, response: Response, user=Depends(identity)):
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可以通过 RNS 网关下发消息")
        response.headers["Cache-Control"] = "private, no-store"
        items = gateway.dispatch_to_field(
            body.event_id,
            kind=body.kind,
            payload=body.payload,
            recipient_actors=body.recipient_actors,
            idempotency_key=body.idempotency_key,
            channel_id=body.channel_id,
            title=body.title,
            desired_method=body.desired_method,
        )
        return {"items": items}
