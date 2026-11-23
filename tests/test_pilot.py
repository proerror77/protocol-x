import asyncio
import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import create_app
from app.pilot import listener_grant_allowed
from app.ptt import FloorBusy, LeaseExpired, PushToTalkFloor


class TestMedia:
    def __init__(self, publishers=()):
        self.publishers = set(publishers)
        self.fail_revoke = False
        self.fail_grant_after_write = False

    async def clear_publishers(self):
        self.publishers.clear()

    async def set_publish_permission(self, actor, allowed):
        await asyncio.sleep(0)
        if allowed:
            self.publishers.add(actor)
            if self.fail_grant_after_write:
                raise OSError("simulated SFU timeout after applying the change")
        else:
            if self.fail_revoke:
                raise OSError("simulated failed revocation")
            self.publishers.discard(actor)


class FloorTests(unittest.IsolatedAsyncioTestCase):
    async def test_competing_clients_and_stale_release(self):
        media = TestMedia(("previous-process",))
        floor = PushToTalkFloor(media)
        results = await asyncio.gather(
            floor.claim("staff", "press-a"), floor.claim("dispatcher", "press-b"), return_exceptions=True,
        )
        self.assertIsInstance(results[0], int)
        self.assertIsInstance(results[1], FloorBusy)
        self.assertEqual(media.publishers, {"staff"})
        self.assertGreater(await floor.claim("staff", "press-a"), 0)
        self.assertEqual(await floor.release("staff", "previous-press"), 0)
        await floor.release("dispatcher", "press-a")
        self.assertEqual(media.publishers, {"staff"})
        self.assertEqual(await floor.release("staff", "press-a"), 0)
        self.assertEqual(await floor.release("staff", "press-a"), 0)
        await floor.claim("dispatcher", "press-b")
        self.assertEqual(media.publishers, {"dispatcher"})

    async def test_expired_lease_stays_exclusive_until_confirmed_revoke(self):
        clock = [100.0]
        media = TestMedia()
        floor = PushToTalkFloor(media, lease_seconds=8, clock=lambda: clock[0])
        await floor.claim("staff", "press-a")
        clock[0] = 107.0
        self.assertEqual(await floor.renew("staff", "press-a"), 8000)
        clock[0] = 115.0
        with self.assertRaises(LeaseExpired):
            await floor.renew("staff", "press-a")
        media.fail_revoke = True
        with self.assertRaises(OSError):
            await floor.claim("dispatcher", "press-b")
        self.assertEqual(media.publishers, {"staff"})
        media.fail_revoke = False
        await floor.reap()
        await floor.claim("dispatcher", "press-b")
        self.assertEqual(media.publishers, {"dispatcher"})
        await floor.close()
        self.assertEqual(media.publishers, set())

    async def test_uncertain_grant_is_revoked_before_retry(self):
        media = TestMedia()
        floor = PushToTalkFloor(media)
        media.fail_grant_after_write = True
        with self.assertRaises(OSError):
            await floor.claim("staff", "press-a")
        self.assertEqual(media.publishers, {"staff"})
        media.fail_grant_after_write = False
        await floor.claim("dispatcher", "press-b")
        self.assertEqual(media.publishers, {"dispatcher"})


class PilotTests(unittest.TestCase):
    def setUp(self):
        scratch = TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.db = Path(scratch.name) / "pilot.db"
        self.dispatcher_token = uuid4().hex
        self.staff_token = uuid4().hex
        settings = patch.dict(os.environ, {
            "OPS_PILOT_DISPATCHER_TOKEN": self.dispatcher_token,
            "OPS_PILOT_STAFF_TOKEN": self.staff_token,
            "OPS_LIVEKIT_SERVER_URL": "",
            "OPS_LIVEKIT_CLIENT_URL": "",
            "OPS_LIVEKIT_API_KEY": "",
            "OPS_LIVEKIT_API_SECRET": "",
        })
        settings.start()
        self.addCleanup(settings.stop)
        self.client = TestClient(create_app(self.db))
        self.staff = {"Authorization": f"Bearer {self.staff_token}"}
        self.dispatcher = {"Authorization": f"Bearer {self.dispatcher_token}"}
        self.workspace = "/api/events/golf-demo/workspace"

    def test_pilot_resolves_identity_server_side_and_scopes_tasks(self):
        self.assertEqual(self.client.get(self.workspace, headers={"X-Demo-Role": "dispatcher"}).status_code, 401)
        self.assertEqual(self.client.get(self.workspace, headers={"Authorization": "Bearer invalid"}).status_code, 401)
        forged = dict(self.staff, **{"X-Demo-Role": "dispatcher", "X-Demo-Actor": "demo-dispatcher"})
        self.assertEqual(self.client.get("/api/bootstrap", headers=forged).json()["identity"]["role"], "staff")
        self.assertEqual(len(self.client.get(self.workspace, headers=forged).json()["tasks"]), 1)
        self.assertEqual(len(self.client.get(self.workspace, headers=self.dispatcher).json()["tasks"]), 2)
        self.assertEqual(self.client.post("/api/events/golf-demo/ptt/join", headers=forged).status_code, 404)

    def test_phone_report_retries_keep_the_original_message(self):
        task = self.client.get(self.workspace, headers=self.staff).json()["tasks"][0]
        params = {"text": "车辆故障，请求替换", "subject_ref": task["ref"]}
        headers = dict(self.staff, **{"Idempotency-Key": str(uuid4())})
        path = "/api/events/golf-demo/messages"
        first = self.client.post(path, json=params, headers=headers)
        same = self.client.post(path, json=params, headers=headers)
        changed = self.client.post(path, json=dict(params, text="不同的汇报"), headers=headers)
        self.assertEqual(first.status_code, 201)
        self.assertEqual(same.json(), first.json())
        self.assertEqual(changed.status_code, 409)
        updated = self.client.get(self.workspace, headers=self.dispatcher).json()
        self.assertEqual(len(updated["messages"]), 1)
        current_task = next(t for t in updated["tasks"] if t["ref"] == task["ref"])
        self.assertEqual(current_task["version"], task["version"])
        self.assertEqual(current_task["progress"], task["progress"])

    def test_only_listener_tokens_can_join_signaling(self):
        read_only = dict(room="ops-golf-demo", room_join=True, can_subscribe=True,
                         can_publish=False, can_publish_data=False)
        self.assertTrue(listener_grant_allowed(SimpleNamespace(**read_only), "ops-golf-demo"))
        for change in ({"can_publish": True}, {"can_publish_data": True},
                       {"room": "another-room"}, {"room_join": False}):
            self.assertFalse(listener_grant_allowed(SimpleNamespace(**(read_only | change)), "ops-golf-demo"))

    def test_android_workflow_completes_in_both_activity_bindings(self):
        for event, domain_action in (("golf-demo", "ConfirmTransferMilestone"),
                                     ("conference-demo", "ConfirmSessionMilestone")):
            with self.subTest(event=event):
                workspace = f"/api/events/{event}/workspace"
                action_path = f"/api/events/{event}/actions"
                staff_task = self.client.get(workspace, headers=self.staff).json()["tasks"][0]
                initial_progress = staff_task["progress"]
                payload = {"target_ref": staff_task["ref"], "expected_version": staff_task["version"],
                           "idempotency_key": uuid4().hex, "action_type": "confirm_progress",
                           "parameters": {"progress_index": 1}}
                self.assertEqual(self.client.post(action_path, json=payload, headers=self.staff).status_code, 409)
                ack = dict(payload, action_type="acknowledge", parameters={})
                self.assertEqual(self.client.post(action_path, json=ack, headers=self.staff).status_code, 200)

                report = self.client.post(f"/api/events/{event}/messages", headers=self.staff,
                                          json={"text": "原资源故障，暂不可用", "subject_ref": staff_task["ref"]})
                self.assertEqual(report.status_code, 201)
                proposal = self.client.post(f"/api/events/{event}/proposals", headers=self.dispatcher,
                                            json={"message_id": report.json()["id"]})
                self.assertEqual(proposal.status_code, 201, proposal.text)
                replacement = {key: proposal.json()[key] for key in
                               ("action_type", "target_ref", "expected_version", "parameters")}
                replacement.update(proposal_id=proposal.json()["id"], idempotency_key=uuid4().hex)
                self.assertEqual(self.client.post(action_path, json=replacement, headers=self.dispatcher).status_code, 200)
                staff_task = self.client.get(workspace, headers=self.staff).json()["tasks"][0]
                self.assertEqual(staff_task["progress"], initial_progress)
                self.assertNotEqual(staff_task["version"], ack["expected_version"])
                self.assertNotEqual(staff_task["resource_label"], "02 号接驳车" if event == "golf-demo" else "会议室 A")

                dispatcher_tasks = self.client.get(workspace, headers=self.dispatcher).json()["tasks"]
                other = next(task for task in dispatcher_tasks if task["ref"] != staff_task["ref"])
                foreign = dict(payload, target_ref=other["ref"], expected_version=other["version"], idempotency_key=uuid4().hex)
                self.assertEqual(self.client.post(action_path, json=foreign, headers=self.staff).status_code, 403)
                jump = dict(payload, expected_version=staff_task["version"], idempotency_key=uuid4().hex,
                            parameters={"progress_index": 2})
                self.assertEqual(self.client.post(action_path, json=jump, headers=self.staff).status_code, 409)

                for index in range(1, len(initial_progress)):
                    button = next(a for a in staff_task["allowed_actions"] if a["id"] == "confirm_progress")
                    self.assertEqual(button["progress_index"], index)
                    request = dict(payload, idempotency_key=uuid4().hex, expected_version=staff_task["version"],
                                   parameters={"progress_index": index})
                    first = self.client.post(action_path, json=request, headers=self.staff)
                    replay = self.client.post(action_path, json=request, headers=self.staff)
                    self.assertEqual(first.status_code, 200, first.text)
                    self.assertEqual(first.json(), replay.json())
                    self.assertEqual(self.client.post(action_path, json=dict(request, idempotency_key=uuid4().hex),
                                                      headers=self.staff).status_code, 409)
                    result = first.json()["result"]
                    self.assertEqual(result["domain_action"], domain_action)
                    self.assertEqual(result["progress_label"], initial_progress[index]["label"])
                    self.assertFalse(result["service_progress_unchanged"])
                    staff_task = self.client.get(workspace, headers=self.staff).json()["tasks"][0]

                after = self.client.get(workspace, headers=self.dispatcher).json()
                self.assertEqual(len(after["receipts"]), len(initial_progress) + 1)
                self.assertEqual(staff_task["allowed_actions"], [])
                self.assertTrue(all(step["status"] == "done" for step in staff_task["progress"]))
                self.assertEqual(self.client.get(workspace, headers=self.staff).json()["summary"]["open_tasks"], 0)


if __name__ == "__main__":
    unittest.main()
