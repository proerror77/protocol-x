import base64
import hashlib
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519
from fastapi.testclient import TestClient

from app.main import create_app
from app.rns_envelopes import EnvelopeV1, canonical_envelope_signing_bytes
from app.store import dump


DISPATCH = {"Authorization": "Bearer " + "D" * 40}
DEMO_STAFF = {"Authorization": "Bearer " + "S" * 40}


def _sign_ecdsa(priv: ec.EllipticCurvePrivateKey, env_dict: dict) -> str:
    env = EnvelopeV1.model_validate(env_dict)
    sig = priv.sign(canonical_envelope_signing_bytes(env), ec.ECDSA(hashes.SHA256()))
    return base64.b64encode(sig).decode("ascii")


def _sign_ed25519(priv: ed25519.Ed25519PrivateKey, env_dict: dict) -> str:
    env = EnvelopeV1.model_validate(env_dict)
    sig = priv.sign(canonical_envelope_signing_bytes(env))
    return base64.b64encode(sig).decode("ascii")


def _fp(event_id: str, kind: str, actor: str, idem: str, payload: dict) -> str:
    return hashlib.sha256(dump([event_id, kind, actor, idem, payload]).encode()).hexdigest()


class RnsEnvelopesSyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "env.db"
        self.voice_db = Path(self.tmp.name) / "voice.db"
        self.env = patch.dict(os.environ, {
            "OPS_PILOT_DISPATCHER_TOKEN": "D" * 40,
            "OPS_PILOT_STAFF_TOKEN": "S" * 40,
            "OPS_LIVEKIT_SERVER_URL": "http://127.0.0.1:1",
            "OPS_LIVEKIT_CLIENT_URL": "ws://127.0.0.1:1",
            "OPS_LIVEKIT_API_KEY": "local-test-key",
            "OPS_LIVEKIT_API_SECRET": "s" * 40,
            "OPS_PTT_EVENT_ID": "golf-demo",
            "OPS_PTT_VOICE_DB": str(self.voice_db),
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.client = TestClient(create_app(self.db))
        self.addCleanup(self.client.close)

    def _bind_staff_with_keys(self, event_id: str, name: str):
        ch = self.client.post(f"/api/field/events/{event_id}/channels", headers=DISPATCH, json={"name": f"组-{name}"}).json()["id"]
        person = self.client.post(f"/api/field/events/{event_id}/people", headers=DISPATCH, json={
            "name": name, "callsign": name, "role": "staff", "channel_ids": [ch],
        }).json()
        token = self.client.post("/api/field/bind", json={"code": person["binding_code"]}).json()["session_token"]
        hdr = {"Authorization": "Bearer " + token}

        p256_priv = ec.generate_private_key(ec.SECP256R1())
        p256_pub_b64 = base64.b64encode(
            p256_priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        ).decode("ascii")
        ed_priv = ed25519.Ed25519PrivateKey.generate()
        ed_pub_b64 = base64.b64encode(
            ed_priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        ).decode("ascii")
        dest_hash = hashlib.sha256(b"dest|" + person["actor"].encode()).hexdigest()[:32]
        nearby = self.client.post(f"/api/field/events/{event_id}/nearby", headers=hdr, json={
            "public_key": p256_pub_b64,
            "rns_identity": {"dest_hash": dest_hash, "ed25519_pub": ed_pub_b64},
        }).json()["channels"][0]
        return person["actor"], hdr, ch, p256_priv, ed_priv, dest_hash, nearby["certificate"]

    def test_stage_input_idempotency_key_prevents_duplicate_arrival_stage_advance(self):
        event = "golf-hkg-arrivals-demo"
        ch = self.client.post(f"/api/field/events/{event}/channels", headers=DISPATCH, json={"name": "接待"}).json()["id"]
        disp_p = self.client.post(f"/api/field/events/{event}/people", headers=DISPATCH, json={
            "name": "调度", "callsign": "调度", "role": "dispatcher", "channel_ids": [ch],
        }).json()
        disp_hdr = {"Authorization": "Bearer " + self.client.post("/api/field/bind", json={"code": disp_p["binding_code"]}).json()["session_token"]}
        greeter = self.client.post(f"/api/field/events/{event}/people", headers=DISPATCH, json={
            "name": "接机员", "callsign": "接机员", "role": "staff", "channel_ids": [ch],
        }).json()
        greeter_hdr = {"Authorization": "Bearer " + self.client.post("/api/field/bind", json={"code": greeter["binding_code"]}).json()["session_token"]}
        driver = self.client.post(f"/api/field/events/{event}/people", headers=DISPATCH, json={
            "name": "司机", "callsign": "司机", "role": "staff", "channel_ids": [ch],
        }).json()

        task_id = str(uuid4())
        today = datetime.now(timezone.utc).date()
        next_day = today.fromordinal(today.toordinal() + 1)
        plan_url = f"/api/events/{event}/arrival-plans/{task_id}"
        res = self.client.put(plan_url, headers=disp_hdr, json={
            "expected_version": 0,
            "players": [{"id": str(uuid4()), "name": "Test Player"}],
            "flight": "CX 101",
            "flight_date": today.isoformat(),
            "driver": driver["actor"],
            "greeter": greeter["actor"],
            "vehicle_id": "arrival-van-1",
            "meeting_point": "A厅1号门",
            "hotel": {
                "name": "Test Hotel", "address": "HK", "check_in": today.isoformat(), "check_out": next_day.isoformat(),
            },
            "synthetic": True,
        })
        self.assertEqual(res.status_code, 200, res.text)

        idem = str(uuid4())
        first = self.client.post(plan_url + "/stages", headers=greeter_hdr, json={
            "expected_version": 1, "stage": "met", "idempotency_key": idem,
        })
        self.assertEqual(first.status_code, 200, first.text)
        # Replay with same idempotency_key after version is already 2 -> returns cached 200 result!
        replay = self.client.post(plan_url + "/stages", headers=greeter_hdr, json={
            "expected_version": 1, "stage": "met", "idempotency_key": idem,
        })
        self.assertEqual(replay.status_code, 200, replay.text)
        self.assertEqual(replay.json(), first.json())
        # Same idempotency_key with different stage -> 409 conflict
        conflict = self.client.post(plan_url + "/stages", headers=greeter_hdr, json={
            "expected_version": 1, "stage": "boarded", "idempotency_key": idem,
        })
        self.assertEqual(conflict.status_code, 409)

    def test_cross_transport_dedup_gateway_relay_and_causal_batch_reconciliation(self):
        actor, hdr, ch, p256_priv, ed_priv, dest_hash, cert = self._bind_staff_with_keys("golf-demo", "现场甲")
        _, outsider_hdr, _, _, _, _, _ = self._bind_staff_with_keys("golf-demo", "现场乙")

        # Dispatch a personal field task to actor
        req_id = str(uuid4())
        dispatched = self.client.post("/api/field/events/golf-demo/dispatch", headers=DISPATCH, json={
            "person_id": actor, "request_id": req_id, "title": "送球包", "location": "1号 tee",
        })
        self.assertEqual(dispatched.status_code, 201, dispatched.text)
        task_ref = dispatched.json()["target_ref"]

        # 1. Cross-transport dedup: execute acknowledge via HTTP /actions, then replay via /api/rns/v1/envelopes
        ack_idem = str(uuid4())
        http_ack = self.client.post("/api/events/golf-demo/actions", headers=hdr, json={
            "action_type": "acknowledge", "target_ref": task_ref, "expected_version": 1, "idempotency_key": ack_idem,
        })
        self.assertEqual(http_ack.status_code, 200, http_ack.text)

        env_payload_ack = {
            "op": "action", "action_type": "acknowledge", "target_ref": task_ref, "expected_version": 1, "parameters": {},
        }
        env_ack = {
            "schema": "protocol-x.rns.envelope/v1",
            "envelope_id": str(uuid4()),
            "kind": "task",
            "event_id": "golf-demo",
            "channel_id": ch,
            "actor": actor,
            "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "idempotency_key": ack_idem,
            "fingerprint": _fp("golf-demo", "task", actor, ack_idem, env_payload_ack),
            "trace": {"hop_count": 2, "origin_radio": "WIFI_AWARE", "gateway_actor": "demo-dispatcher"},
            "payload": env_payload_ack,
        }
        env_ack["auth"] = {
            "key_id": dest_hash, "certificate": cert, "signature_alg": "ecdsa_p256",
            "signature": _sign_ecdsa(p256_priv, env_ack),
        }

        # Unauthorized outsider relay -> 403
        self.assertEqual(self.client.post("/api/rns/v1/envelopes", headers=outsider_hdr, json=env_ack).status_code, 403)

        # Gateway/dispatcher relay with valid signed certificate -> 200 & deduplicated_by_key=True!
        relay_res = self.client.post("/api/rns/v1/envelopes", headers=DISPATCH, json=env_ack)
        self.assertEqual(relay_res.status_code, 200, relay_res.text)
        self.assertTrue(relay_res.json()["result"]["deduplicated_by_key"])
        self.assertEqual(relay_res.json()["result"]["receipt"]["id"], http_ack.json()["id"])

        # Same envelope_id replayed again -> deduplicated=True
        replay_env = self.client.post("/api/rns/v1/envelopes", headers=DISPATCH, json=env_ack)
        self.assertEqual(replay_env.status_code, 200)
        self.assertTrue(replay_env.json()["deduplicated"])

        # Same envelope_id with different fingerprint -> 409
        bad_fp = {**env_ack, "fingerprint": "f" * 64}
        self.assertEqual(self.client.post("/api/rns/v1/envelopes", headers=DISPATCH, json=bad_fp).status_code, 409)

        # 2. Causal offline batch replay: confirm_progress[0] (v2) then confirm_progress[1] (v3), sent out of order in batch!
        step0_idem = str(uuid4())
        step1_idem = str(uuid4())
        p0 = {
            "op": "action", "action_type": "confirm_progress", "target_ref": task_ref,
            "expected_version": 2, "parameters": {"progress_index": 0}, "client_seq": 1,
        }
        p1 = {
            "op": "action", "action_type": "confirm_progress", "target_ref": task_ref,
            "expected_version": 3, "parameters": {"progress_index": 1}, "client_seq": 2,
        }
        e0 = {
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": str(uuid4()), "kind": "task",
            "event_id": "golf-demo", "channel_id": ch, "actor": actor, "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": step0_idem,
            "fingerprint": _fp("golf-demo", "task", actor, step0_idem, p0), "payload": p0,
        }
        e1 = {
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": str(uuid4()), "kind": "task",
            "event_id": "golf-demo", "channel_id": ch, "actor": actor, "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": step1_idem,
            "fingerprint": _fp("golf-demo", "task", actor, step1_idem, p1), "payload": p1,
        }
        e0["auth"] = {"key_id": dest_hash, "certificate": cert, "signature_alg": "ed25519_rns", "signature": _sign_ed25519(ed_priv, e0)}
        e1["auth"] = {"key_id": dest_hash, "certificate": cert, "signature_alg": "ed25519_rns", "signature": _sign_ed25519(ed_priv, e1)}

        # Submit e1 BEFORE e0 in batch -> causal sorter orders by client_seq so both commit cleanly!
        batch = self.client.post("/api/rns/v1/envelopes/batch", headers=DISPATCH, json={"envelopes": [e1, e0]})
        self.assertEqual(batch.status_code, 200, batch.text)
        items = {item["envelope_id"]: item for item in batch.json()["items"]}
        self.assertEqual(items[e0["envelope_id"]]["http_status"], 200)
        self.assertEqual(items[e1["envelope_id"]]["http_status"], 200)

        # 3. Stale offline version -> structured 409 reconciliation details
        stale_idem = str(uuid4())
        p_stale = {
            "op": "action", "action_type": "confirm_progress", "target_ref": task_ref,
            "expected_version": 2, "parameters": {"progress_index": 2},
        }
        e_stale = {
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": str(uuid4()), "kind": "task",
            "event_id": "golf-demo", "channel_id": ch, "actor": actor, "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": stale_idem,
            "fingerprint": _fp("golf-demo", "task", actor, stale_idem, p_stale), "payload": p_stale,
        }
        conflict = self.client.post("/api/rns/v1/envelopes", headers=hdr, json=e_stale)
        self.assertEqual(conflict.status_code, 409)
        detail = conflict.json()["detail"]
        self.assertEqual(detail["current_version"], 4)
        self.assertEqual(detail["allowed_actions"][0]["progress_index"], 2)

    def test_channel_message_voice_note_photo_and_conference_ontology_invariance(self):
        actor, hdr, ch, _, _, dest_hash, _ = self._bind_staff_with_keys("golf-demo", "现场语音")

        # Voice note + channel photo via envelopes bump channel feed
        before = self.client.get(f"/api/field/events/golf-demo/channels/{ch}/changes", headers=hdr).json()["cursor"]
        vn_id = str(uuid4())
        vn_res = self.client.post("/api/rns/v1/envelopes", headers=hdr, json={
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": vn_id, "kind": "message",
            "event_id": "golf-demo", "channel_id": ch, "actor": actor, "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": vn_id,
            "fingerprint": "a" * 64,
            "payload": {
                "scope": "voice_note", "text": "2号门正常通行",
                "voice_ref": {"audio_b64": base64.b64encode(b"RIFFdemo").decode(), "duration_ms": 1200},
            },
        })
        self.assertEqual(vn_res.status_code, 200, vn_res.text)

        jpeg = b"\xff\xd8\xff\xc0\x00\x0b\x08\x00\x02\x00\x03\x01\x01\x11\x00\xff\xd9"
        att_id = str(uuid4())
        att_res = self.client.post("/api/rns/v1/envelopes", headers=hdr, json={
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": str(uuid4()), "kind": "attachment",
            "event_id": "golf-demo", "channel_id": ch, "actor": actor, "device_id": dest_hash,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": att_id,
            "fingerprint": "b" * 64,
            "payload": {
                "attachment_id": att_id, "target_kind": "channel_photo", "media_type": "image/jpeg",
                "caption": "现场照片", "poster_b64": base64.b64encode(jpeg).decode(),
                "content_b64": base64.b64encode(jpeg).decode(),
            },
        })
        self.assertEqual(att_res.status_code, 200, att_res.text)

        delta = self.client.get(f"/api/field/events/golf-demo/channels/{ch}/changes", headers=hdr, params={"cursor": before}).json()
        self.assertTrue(delta["changed"])
        self.assertEqual(delta["channel_messages"][0]["text"], "2号门正常通行")
        self.assertEqual(delta["attachments"][0]["id"], att_id)

        # Conference ontology invariance: same envelope schema dispatches & acknowledges a Session task
        conf_actor, conf_hdr, conf_ch, _, _, conf_dest, _ = self._bind_staff_with_keys("conference-demo", "会务甲")
        conf_dispatch = self.client.post("/api/field/events/conference-demo/dispatch", headers=DISPATCH, json={
            "person_id": conf_actor, "request_id": str(uuid4()), "title": "主会场检查", "location": "A厅",
        }).json()
        self.assertEqual(conf_dispatch["target_ref"]["object_type"], "Session")
        conf_ack = self.client.post("/api/rns/v1/envelopes", headers=conf_hdr, json={
            "schema": "protocol-x.rns.envelope/v1", "envelope_id": str(uuid4()), "kind": "task",
            "event_id": "conference-demo", "channel_id": conf_ch, "actor": conf_actor, "device_id": conf_dest,
            "created_at": datetime.now(timezone.utc).isoformat(), "idempotency_key": str(uuid4()),
            "fingerprint": "c" * 64,
            "payload": {
                "op": "action", "action_type": "acknowledge",
                "target_ref": conf_dispatch["target_ref"], "expected_version": 1, "parameters": {},
            },
        })
        self.assertEqual(conf_ack.status_code, 200, conf_ack.text)
        self.assertEqual(conf_ack.json()["result"]["receipt"]["result"]["domain_action"], "AcceptSessionStewardship")


if __name__ == "__main__":
    unittest.main()
