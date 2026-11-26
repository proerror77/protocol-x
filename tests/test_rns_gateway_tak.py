import hashlib
import json
import os
import socket
import tempfile
import threading
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4
from xml.etree.ElementTree import fromstring

import LXMF
import RNS
from fastapi.testclient import TestClient

from app.field_nearby import derive_channel_ifac
from app.main import create_app
from app.rns_gateway import ENVELOPE_CUSTOM_TYPE, FIELD_CUSTOM_DATA, FIELD_CUSTOM_TYPE, RnsGateway, RnsGatewaySettings
from app.store import dump
from app.tak import MARKER_TYPE, PEER_PLI_TYPE, TakSettings


DISPATCH = {"Authorization": "Bearer " + "D" * 40}


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class TakStub:
    def __init__(self, settings):
        self.settings = settings
        self.events = []

    def deliver(self, event):
        self.events.append(event)
        return "received"

    def read_back(self, event):
        return any(sent.get("uid") == event.get("uid") for sent in self.events)


class RnsGatewayTakTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "gw.db"
        self.env = patch.dict(os.environ, {
            "OPS_PILOT_DISPATCHER_TOKEN": "D" * 40,
            "OPS_PILOT_STAFF_TOKEN": "S" * 40,
            "OPS_TAK_EVENT_ID": "golf-demo",
            "OPS_TAK_COT_HOST": "tak.example",
            "OPS_TAK_COT_PORT": "8089",
            "OPS_TAK_MARTI_URL": "https://tak.example:8443",
            "OPS_TAK_SENDER_UID": "ops-golf",
            "OPS_TAK_RECIPIENT_UID": "tak-dispatch",
            "OPS_TAK_CA_FILE": "/test/ca.pem",
            "OPS_TAK_CERT_FILE": "/test/client.pem",
            "OPS_TAK_KEY_FILE": "/test/client.key",
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.tak_stub = TakStub(TakSettings.from_env())
        self.tak_patch = patch("app.tak.TakTransport", return_value=self.tak_stub)
        self.tak_patch.start()
        self.addCleanup(self.tak_patch.stop)
        self.client = TestClient(create_app(self.db))
        self.addCleanup(self.client.close)

    def _provision_field_actor(self, name: str, dest_hash_hex: str):
        ch = self.client.post("/api/field/events/golf-demo/channels", headers=DISPATCH, json={"name": "RNS桥接组"}).json()["id"]
        person = self.client.post("/api/field/events/golf-demo/people", headers=DISPATCH, json={
            "name": name, "callsign": name, "role": "staff", "channel_ids": [ch],
        }).json()
        token = self.client.post("/api/field/bind", json={"code": person["binding_code"]}).json()["session_token"]
        hdr = {"Authorization": "Bearer " + token}
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        p256 = base64_p256(ec.generate_private_key(ec.SECP256R1()).public_key())
        nearby = self.client.post("/api/field/events/golf-demo/nearby", headers=hdr, json={
            "public_key": p256,
            "rns_identity": {"dest_hash": dest_hash_hex},
        }).json()["channels"][0]
        return person["actor"], hdr, ch, nearby

    def test_real_rns_lxmf_gateway_with_ifac_tak_projection_and_downward_dispatch(self):
        # 1. Start TOC RnsGateway with derived IFAC on a private localhost port
        port = _free_port()
        ifac = derive_channel_ifac("golf-demo", "group-demo", "shared-ifac-secret")
        gw_settings = RnsGatewaySettings(
            storage_dir=Path(self.tmp.name) / "gw-rns",
            listen_host="127.0.0.1",
            listen_port=port,
            ifac_network_name=ifac["network_name"],
            ifac_passphrase=ifac["passphrase"],
            enable_transport=True,
        )
        gateway = RnsGateway(
            gw_settings,
            self.client.app.state.store,
            self.client.app.state.rns_processor,
            tak_settings=self.tak_stub.settings,
        )
        gateway.start()
        self.addCleanup(gateway.stop)

        # Verify IFAC rejection behavior & real LXMF bidirectional roundtrip using an in-process RNS/LXMF peer?
        # Note: official Python RNS uses a single process-level Reticulum instance, so we spawn a subprocess peer
        # (just like RNS-03 POC) for a genuine separate-process TCP+IFAC Reticulum node!
        import subprocess
        import sys

        peer_script = Path(self.tmp.name) / "peer.py"
        peer_id_path = Path(self.tmp.name) / "peer.id"
        peer_identity = RNS.Identity()
        peer_identity.to_file(str(peer_id_path))
        peer_dest_hex = RNS.Destination.hash_from_name_and_identity("lxmf.delivery", peer_identity.hash).hex()

        actor, _, ch, _ = self._provision_field_actor("电台手", peer_dest_hex)
        dispatch_res = self.client.post("/api/field/events/golf-demo/dispatch", headers=DISPATCH, json={
            "person_id": actor, "request_id": str(uuid4()), "title": "RNS现场测试", "location": "T1出口",
        }).json()

        env_loc = {
            "schema": "protocol-x.rns.envelope/v1",
            "envelope_id": str(uuid4()),
            "kind": "location",
            "event_id": "golf-demo",
            "channel_id": ch,
            "actor": actor,
            "device_id": peer_dest_hex,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "idempotency_key": str(uuid4()),
            "fingerprint": "d" * 64,
            "payload": {
                "location_kind": "point_marker",
                "title": "球车故障点",
                "latitude": 22.308,
                "longitude": 113.918,
                "project_to_tak": True,
            },
        }
        env_ack = {
            "schema": "protocol-x.rns.envelope/v1",
            "envelope_id": str(uuid4()),
            "kind": "task",
            "event_id": "golf-demo",
            "channel_id": ch,
            "actor": actor,
            "device_id": peer_dest_hex,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "idempotency_key": str(uuid4()),
            "fingerprint": "e" * 64,
            "payload": {
                "op": "action",
                "action_type": "acknowledge",
                "target_ref": dispatch_res["target_ref"],
                "expected_version": 1,
                "parameters": {},
            },
        }

        peer_script.write_text(f"""
import json, os, sys, threading, time
import LXMF, RNS

cfg_dir = {json.dumps(str(Path(self.tmp.name) / "peer-rns"))}
lx_dir = {json.dumps(str(Path(self.tmp.name) / "peer-lxmf"))}
os.makedirs(cfg_dir, exist_ok=True)
os.makedirs(lx_dir, exist_ok=True)
with open(os.path.join(cfg_dir, "config"), "w") as fh:
    fh.write('''[reticulum]
  enable_transport = No
  share_instance = No
[logging]
  loglevel = 2
[interfaces]
  [[GW]]
    type = TCPClientInterface
    enabled = Yes
    target_host = 127.0.0.1
    target_port = {port}
    network_name = {ifac["network_name"]}
    passphrase = {ifac["passphrase"]}
''')

RNS.Reticulum(configdir=cfg_dir)
RNS.Transport.STAMP_DESTINATIONS = False
RNS.Transport.STAMP_LRS = False
RNS.Transport.STAMP_PRS = False
ident = RNS.Identity.from_file({json.dumps(str(peer_id_path))})
router = LXMF.LXMRouter(storagepath=lx_dir, propagation_cost=0)
delivery = router.register_delivery_identity(ident, display_name="field-peer", stamp_cost=0)
received = []
done = threading.Event()
def on_msg(m):
    received.append(m.content_as_string())
    if len(received) >= 3:
        done.set()
router.register_delivery_callback(on_msg)

gw_dest_bytes = bytes.fromhex({json.dumps(gateway.lxmf_destination_hash_hex)})
deadline = time.monotonic() + 15.0
sent = False
while time.monotonic() < deadline:
    router.announce(delivery.hash)
    gw_id = RNS.Identity.recall(gw_dest_bytes)
    if gw_id is not None and not sent:
        gw_out = RNS.Destination(gw_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        for env in (json.loads({json.dumps(json.dumps(env_loc))}), json.loads({json.dumps(json.dumps(env_ack))})):
            raw = json.dumps(env, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
            m = LXMF.LXMessage(gw_out, delivery, raw.decode("utf-8"), title="Env",
                               fields={{0xFB: b"protocol-x.rns.envelope/v1", 0xFC: raw}},
                               desired_method=LXMF.LXMessage.DIRECT)
            router.handle_outbound(m)
        sent = True
    if done.is_set():
        break
    time.sleep(0.1)
print(json.dumps({{"count": len(received), "items": received}}))
sys.exit(0 if len(received) >= 2 else 1)
""", encoding="utf-8")

        # Also start a rogue peer with WRONG IFAC passphrase and verify it NEVER reaches gateway
        rogue_script = Path(self.tmp.name) / "rogue.py"
        rogue_script.write_text(f"""
import os, sys, time
import LXMF, RNS
cfg_dir = {json.dumps(str(Path(self.tmp.name) / "rogue-rns"))}
os.makedirs(cfg_dir, exist_ok=True)
with open(os.path.join(cfg_dir, "config"), "w") as fh:
    fh.write('''[reticulum]
  enable_transport = No
  share_instance = No
[interfaces]
  [[Rogue]]
    type = TCPClientInterface
    enabled = Yes
    target_host = 127.0.0.1
    target_port = {port}
    network_name = {ifac["network_name"]}
    passphrase = WRONG-PASSPHRASE
''')
RNS.Reticulum(configdir=cfg_dir)
gw_dest_bytes = bytes.fromhex({json.dumps(gateway.lxmf_destination_hash_hex)})
RNS.Transport.request_path(gw_dest_bytes)
time.sleep(1.5)
print("RECALLED=" + str(RNS.Identity.recall(gw_dest_bytes) is not None))
""", encoding="utf-8")

        rogue_out = subprocess.check_output([sys.executable, str(rogue_script)], text=True, timeout=10)
        self.assertIn("RECALLED=False", rogue_out)

        proc = subprocess.Popen([sys.executable, str(peer_script)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            # Wait until gateway ingests both inbound envelopes + sends receipts, then push downward TAK CoT!
            deadline = time.monotonic() + 12.0
            while time.monotonic() < deadline and len(gateway.inbound_events) < 2:
                gateway.router.announce(gateway.delivery_destination.hash)
                time.sleep(0.1)
            if len(gateway.inbound_events) < 2:
                proc.terminate()
                out, err = proc.communicate(timeout=5)
                self.fail(f"Inbound events={gateway.inbound_events}; peer out={out}; peer err={err}")
            self.assertEqual(len(gateway.inbound_events), 2)

            # Verify TAK CoT projection landed (OpsPoint- marker)
            self.assertEqual(len(self.tak_stub.events), 1)
            self.assertEqual(self.tak_stub.events[0].get("type"), MARKER_TYPE)
            self.assertEqual(self.tak_stub.events[0].find("detail/contact").get("callsign"), "球车故障点")

            # Verify task acknowledge bumped version 1 -> 2
            ws = self.client.get("/api/events/golf-demo/workspace", headers=DISPATCH).json()
            task = next(t for t in ws["tasks"] if t["ref"]["id"] == dispatch_res["target_ref"]["id"])
            self.assertEqual(task["version"], 2)
            self.assertEqual(task["status"], "acknowledged")

            # Test downward TAK CoT ingestion -> echo suppression + RNS dispatch to field peer!
            echo_xml = (
                f'<event version="2.0" uid="{self.tak_stub.events[0].get("uid")}" type="b-m-p-s-m" '
                f'how="h-g-i-g-o" time="2026-11-26T12:00:00.000Z" start="2026-11-26T12:00:00.000Z" '
                f'stale="2026-11-26T12:30:00.000Z"><point lat="22.3" lon="113.9" hae="9999999" ce="9999999" le="9999999"/>'
                f'<detail><contact callsign="echo"/></detail></event>'
            ).encode()
            self.assertEqual(gateway.ingest_tak_and_dispatch("golf-demo", echo_xml, [actor])["status"], "ignored")

            atak_xml = (
                '<event version="2.0" uid="ATAK-PEER-001" type="b-t-f" how="h-g-i-g-o" '
                'time="2026-11-26T12:01:00.000Z" start="2026-11-26T12:01:00.000Z" stale="2026-11-26T12:31:00.000Z">'
                '<point lat="22.31" lon="113.92" hae="9999999" ce="9999999" le="9999999"/>'
                '<detail><contact callsign="ATAK-CMD"/><remarks>二号门临时改道</remarks></detail></event>'
            ).encode("utf-8")
            tak_down = gateway.ingest_tak_and_dispatch("golf-demo", atak_xml, [actor])
            self.assertEqual(tak_down["status"], "ingested")
            self.assertEqual(len(tak_down["dispatches"]), 1)
            # Re-ingesting identical ATAK CoT deduplicates cleanly!
            self.assertEqual(gateway.ingest_tak_and_dispatch("golf-demo", atak_xml, [actor])["status"], "deduplicated")

            stdout, stderr = proc.communicate(timeout=15)
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=5)
        self.assertEqual(proc.returncode, 0, f"stderr={stderr}\nstdout={stdout}")
        peer_result = json.loads(stdout)
        self.assertGreaterEqual(peer_result["count"], 3)
        self.assertTrue(any("二号门临时改道" in item for item in peer_result["items"]))

        # Verify revoked device is rejected by gateway
        self.client.post(f"/api/field/events/golf-demo/people/{actor}/revoke", headers=DISPATCH)
        self.assertIsNone(gateway._lookup_active_rns_device(peer_dest_hex))


def base64_p256(pub) -> str:
    import base64
    from cryptography.hazmat.primitives import serialization

    return base64.b64encode(
        pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    ).decode("ascii")


if __name__ == "__main__":
    unittest.main()
