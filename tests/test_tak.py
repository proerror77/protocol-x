from base64 import b64encode
from hashlib import sha256
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from time import time
import unittest
from unittest.mock import patch
from uuid import uuid4
from xml.etree.ElementTree import fromstring, tostring

from fastapi.testclient import TestClient

from app.main import create_app
from app.tak import CHAT_TYPE, MARKER_TYPE, PTT_TYPE, TakSettings, TakTransport


class TakStub:
    def __init__(self, settings):
        self.settings = settings
        self.events = []
        self.connected = True
        self.visible = True
        self.lookups = []

    def deliver(self, event):
        if not self.connected:
            return "retryable"
        self.events.append(event)
        return "received" if self.visible else "awaiting_readback"

    def read_back(self, event):
        self.lookups.append(event)
        return self.visible and any(sent.get("uid") == event.get("uid") for sent in self.events)


class TakTests(unittest.TestCase):
    def setUp(self):
        scratch = TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.db = Path(scratch.name) / "ops.db"
        self.event = "/api/events/golf-demo"
        self.staff = {"Authorization": "Bearer " + uuid4().hex}
        self.dispatcher = {"Authorization": "Bearer " + uuid4().hex}
        config = patch.dict("os.environ", {
            "OPS_PILOT_STAFF_TOKEN": self.staff["Authorization"].split()[1],
            "OPS_PILOT_DISPATCHER_TOKEN": self.dispatcher["Authorization"].split()[1],
            "OPS_LIVEKIT_SERVER_URL": "",
            "OPS_LIVEKIT_CLIENT_URL": "",
            "OPS_LIVEKIT_API_KEY": "",
            "OPS_LIVEKIT_API_SECRET": "",
            "OPS_TAK_EVENT_ID": "golf-demo",
            "OPS_TAK_COT_HOST": "tak.example",
            "OPS_TAK_COT_PORT": "8089",
            "OPS_TAK_MARTI_URL": "https://tak.example:8443",
            "OPS_TAK_SENDER_UID": "ops-golf",
            "OPS_TAK_RECIPIENT_UID": "tak-dispatch",
            "OPS_TAK_CA_FILE": "/test/ca.pem",
            "OPS_TAK_CERT_FILE": "/test/client.pem",
            "OPS_TAK_KEY_FILE": "/test/client.key",
            "OPS_TAK_REPORT_LATITUDE": "",
            "OPS_TAK_REPORT_LONGITUDE": "",
            "OPS_TAK_PTT_EVENTS": "false",
        })
        config.start()
        self.addCleanup(config.stop)
        self.transport = TakStub(TakSettings.from_env())
        transport = patch("app.tak.TakTransport", return_value=self.transport)
        transport.start()
        self.addCleanup(transport.stop)
        self.client = TestClient(create_app(self.db))

    def report(self, event=None, text="演练车辆故障 & <待调度>"):
        event = event or self.event
        work = self.client.get(event + "/workspace", headers=self.staff).json()
        task = work["tasks"][0]
        result = self.client.post(event + "/messages", headers=self.staff,
                                  json={"text": text, "subject_ref": task["ref"]})
        self.assertEqual(result.status_code, 201)
        self.assertEqual(self.transport.events, [])
        return event + "/tak/reports/" + result.json()["id"]

    def test_dispatcher_shares_only_within_configured_activity(self):
        golf = self.report()
        conference = self.report("/api/events/conference-demo")
        self.assertEqual(self.client.post(golf, json={}).status_code, 401)
        self.assertEqual(self.client.post(golf, headers=self.staff, json={}).status_code, 403)
        self.assertEqual(self.client.post(conference, headers=self.dispatcher, json={}).status_code, 404)
        foreign = self.event + "/tak/reports/" + conference.rsplit("/", 1)[1]
        self.assertEqual(self.client.post(foreign, headers=self.dispatcher, json={}).status_code, 404)
        staff_workspace = self.client.get(self.event + "/workspace", headers=self.staff).json()
        self.assertIsNone(staff_workspace["tak"])

    def test_confirmed_report_contains_explicit_destination_and_original_excerpt(self):
        path = self.report(text="私有前文。获准共享 & <演练>。私有后文。")
        text = "获准共享 & <演练>"
        response = self.client.post(path, headers=self.dispatcher, json={"excerpt": text})
        self.assertEqual(response.status_code, 200)
        receipt = response.json()
        self.assertEqual(receipt["status"], "received")
        self.assertTrue(receipt["received_at"])
        event = fromstring(tostring(self.transport.events[0], encoding="us-ascii"))
        self.assertEqual(event.get("uid"), receipt["cot_uid"])
        self.assertEqual(event.get("type"), CHAT_TYPE)
        self.assertEqual(event.findtext("detail/remarks"), text)
        self.assertEqual(event.find("detail/marti/dest").get("uid"), "tak-dispatch")
        self.assertEqual(event.find("point").get("lat"), "0")
        self.assertEqual(event.find("point").get("ce"), "9999999")
        restarted = TestClient(create_app(self.db))
        self.assertEqual(restarted.post(path, headers=self.dispatcher, json={}).json(), receipt)
        self.assertEqual(len(self.transport.events), 1)
        self.assertEqual(restarted.post(path, headers=self.dispatcher,
                                        json={"excerpt": "私有前文"}).status_code, 409)

    def test_waiting_receipt_rechecks_authorized_payload_without_resending(self):
        path = self.report()
        self.transport.visible = False
        waiting = self.client.post(path, headers=self.dispatcher, json={"excerpt": "车辆故障"})
        self.assertEqual(waiting.status_code, 202)
        self.assertIsNone(waiting.json()["received_at"])
        self.assertEqual(len(self.transport.events), 1)
        self.transport.visible = True
        found = self.client.post(path, headers=self.dispatcher, json={})
        self.assertEqual(found.status_code, 200)
        self.assertEqual(found.json()["cot_uid"], waiting.json()["cot_uid"])
        self.assertEqual(len(self.transport.events), 1)
        self.assertEqual(self.transport.lookups[-1].findtext("detail/remarks"), "车辆故障")

    def test_geo_chat_reference_is_kept_when_configuration_changes(self):
        path = self.report(text="商定会合点\r\n确认到达")
        with patch.dict("os.environ", {"OPS_TAK_REPORT_LATITUDE": "31.2", "OPS_TAK_REPORT_LONGITUDE": "121.4"}):
            self.transport.settings = TakSettings.from_env()
            configured = TestClient(create_app(self.db))
            self.transport.visible = False
            waiting = configured.post(path, headers=self.dispatcher, json={})
        self.assertEqual(waiting.status_code, 202)
        event = self.transport.events[-1]
        self.assertEqual(event.find("point").get("lat"), "31.2")
        self.assertEqual(event.find("detail/precisionlocation").get("geolocationsrc"), "activity-reference")
        self.assertEqual(event.findtext("detail/remarks"), "商定会合点\n确认到达")
        self.transport.settings = TakSettings.from_env()
        self.transport.visible = True
        restarted = TestClient(create_app(self.db))
        self.assertEqual(restarted.post(path, headers=self.dispatcher, json={}).status_code, 200)
        self.assertEqual(self.transport.lookups[-1].find("point").get("lat"), "31.2")
        self.assertEqual(len(self.transport.events), 1)

    def test_retry_after_failed_connection_keeps_uid(self):
        path = self.report()
        self.transport.connected = False
        failed = self.client.post(path, headers=self.dispatcher, json={})
        self.assertEqual(failed.status_code, 503)
        self.assertEqual(failed.json()["status"], "retryable")
        self.assertEqual(self.transport.events, [])
        self.transport.connected = True
        retried = self.client.post(path, headers=self.dispatcher, json={})
        self.assertEqual(retried.status_code, 200)
        self.assertEqual(retried.json()["cot_uid"], failed.json()["cot_uid"])

    def test_point_is_validated_and_idempotent(self):
        path = self.event + "/tak/points"
        key = str(uuid4())
        body = {"request_id": key, "title": "模拟会合点", "latitude": 31.2, "longitude": 121.4}
        self.assertEqual(self.client.post(path, headers=self.dispatcher,
                                          json={**body, "latitude": 91}).status_code, 422)
        first = self.client.post(path, headers=self.dispatcher, json=body)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.json(), self.client.post(path, headers=self.dispatcher, json=body).json())
        self.assertEqual(self.client.post(path, headers=self.dispatcher,
                                          json={**body, "title": "另一地点"}).status_code, 409)
        event = self.transport.events[0]
        self.assertEqual(event.get("type"), MARKER_TYPE)
        self.assertEqual(event.find("point").get("lon"), "121.4")
        self.assertEqual(event.find("detail/contact").get("callsign"), "模拟会合点")

    def test_tls_readback_rejects_same_uid_with_different_text(self):
        response = self.client.post(self.report(), headers=self.dispatcher, json={})
        self.assertEqual(response.status_code, 200)
        event = self.transport.events[0]
        different = fromstring(tostring(event))
        different.find("detail/remarks").text = "另一段文字"
        transport = object.__new__(TakTransport)
        transport.settings = self.transport.settings
        transport.context = None
        with patch("app.tak.http.client.HTTPSConnection") as connection:
            reply = connection.return_value.getresponse.return_value
            reply.status = 200
            reply.read.return_value = tostring(different)
            with self.assertRaises(ValueError):
                transport.read_back(event)
            reply.read.return_value = tostring(event)
            self.assertTrue(transport.read_back(event))

    def ptt_client(self):
        with patch.dict("os.environ", {
            "OPS_TAK_PTT_EVENTS": "true",
            "OPS_LIVEKIT_SERVER_URL": "http://localhost:7880",
            "OPS_LIVEKIT_CLIENT_URL": "ws://127.0.0.1:7880",
            "OPS_LIVEKIT_API_KEY": "test-livekit-key",
            "OPS_LIVEKIT_API_SECRET": "s" * 64,
            "OPS_PTT_ROOM": "ops-golf-demo",
            "OPS_PTT_EVENT_ID": "golf-demo",
        }):
            self.transport.settings = TakSettings.from_env()
            return TestClient(create_app(self.db))

    @staticmethod
    def webhook(client, event, signed=True, tampered=False):
        from livekit import api

        body = json.dumps(event)
        headers = {"Content-Type": "application/webhook+json"}
        if signed:
            digest = b64encode(sha256(body.encode()).digest()).decode()
            headers["Authorization"] = (api.AccessToken("test-livekit-key", "s" * 64)
                                        .with_sha256(digest).to_jwt())
        if tampered:
            body += " "
        return client.post("/internal/ptt/livekit-events", content=body, headers=headers)

    def test_only_signed_ops_microphone_events_in_the_configured_room_reach_tak(self):
        client = self.ptt_client()
        timestamp = int(time())
        event = {"event": "track_published", "id": "EV_mic_start", "createdAt": str(timestamp),
                 "room": {"name": "ops-golf-demo"},
                 "participant": {"identity": f"demo-staff-{uuid4()}"},
                 "track": {"sid": "TR_ops_mic", "type": "AUDIO", "source": "MICROPHONE"}}
        self.assertEqual(self.webhook(client, event, signed=False).status_code, 401)
        self.assertEqual(self.webhook(client, event, tampered=True).status_code, 401)
        self.assertEqual(self.webhook(client, {**event, "room": {"name": "other-room"}}).status_code, 204)
        self.assertEqual(self.webhook(client, {**event, "participant": {"identity": "tak-listener"}}).status_code, 204)
        camera = {**event["track"], "type": "VIDEO", "source": "CAMERA"}
        self.assertEqual(self.webhook(client, {**event, "track": camera}).status_code, 204)
        self.assertEqual(len(self.transport.events), 0)
        self.assertEqual(self.webhook(client, event).status_code, 204)
        self.assertEqual(self.webhook(client, event).status_code, 204)
        closed = {**event, "event": "track_unpublished", "id": "EV_mic_end", "createdAt": str(timestamp + 1)}
        self.assertEqual(self.webhook(client, closed).status_code, 204)
        self.assertEqual(len(self.transport.events), 2)
        for expected_phase, cot in zip(("started", "ended"), self.transport.events):
            self.assertEqual(cot.get("type"), PTT_TYPE)
            self.assertEqual(cot.find("detail/ops_ptt").get("phase"), expected_phase)
            self.assertEqual(cot.find("detail/ops_ptt").get("track_sid"), "TR_ops_mic")
            self.assertEqual(cot.find("detail/marti/dest").get("uid"), "tak-dispatch")
            self.assertEqual(cot.find("point").get("lat"), "9999999")

    def test_webhook_retries_readback_without_republishing_a_ptt_event(self):
        client = self.ptt_client()
        event = {"event": "track_published", "id": "EV_wait", "createdAt": str(int(time())),
                 "room": {"name": "ops-golf-demo"},
                 "participant": {"identity": f"demo-dispatcher-{uuid4()}"},
                 "track": {"sid": "TR_wait", "type": "AUDIO", "source": "MICROPHONE"}}
        self.transport.visible = False
        self.assertEqual(self.webhook(client, event).status_code, 503)
        self.assertEqual(len(self.transport.events), 1)
        self.transport.visible = True
        self.assertEqual(self.webhook(client, event).status_code, 204)
        self.assertEqual(len(self.transport.events), 1)
        self.assertEqual(self.transport.lookups[-1].get("uid"), self.transport.events[0].get("uid"))

