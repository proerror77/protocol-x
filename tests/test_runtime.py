import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from fastapi.testclient import TestClient
from app.main import create_app


STAFF = {"X-Demo-Role": "staff", "X-Demo-Actor": "demo-staff"}


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name) / "test.db"
        self.client = TestClient(create_app(self.db))

    def tearDown(self):
        self.client.close()
        self.temp.cleanup()

    def workspace(self, event="golf-demo", headers=None):
        response = self.client.get(f"/api/events/{event}/workspace", headers=headers or {})
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def report_and_proposal(self, event="golf-demo"):
        task = self.workspace(event)["tasks"][0]
        report = self.client.post(f"/api/events/{event}/messages", json={"text": "现场报告：当前资源故障，暂时不可用。", "subject_ref": task["ref"]}, headers=STAFF)
        self.assertEqual(report.status_code, 201, report.text)
        response = self.client.post(f"/api/events/{event}/proposals", json={"message_id": report.json()["id"]})
        self.assertEqual(response.status_code, 201, response.text)
        return task, report.json(), response.json()

    def request_for(self, proposal, key="test-action-0001"):
        return {"action_type": proposal["action_type"], "target_ref": proposal["target_ref"], "expected_version": proposal["expected_version"], "parameters": proposal["parameters"], "proposal_id": proposal["id"], "idempotency_key": key}

    def test_reports_and_proposals_do_not_execute(self):
        task, message, proposal = self.report_and_proposal()
        workspace = self.workspace()
        self.assertEqual(workspace["tasks"][0], task)
        self.assertEqual(workspace["receipts"], [])
        self.assertEqual(proposal["engine"], "deterministic_demo")
        self.assertEqual(proposal["evidence_quote"], message["text"])
        again = self.client.post("/api/events/golf-demo/proposals", json={"message_id": message["id"]})
        self.assertEqual(again.json()["id"], proposal["id"])
        self.assertEqual(len(self.workspace()["proposals"]), 1)

    def test_same_app_contract_works_for_two_ontologies(self):
        for event, task_type, domain_action in [("golf-demo", "TransferLeg", "AssignVehicle"), ("conference-demo", "Session", "RebookSessionRoom")]:
            with self.subTest(event=event):
                task, _, proposal = self.report_and_proposal(event)
                self.assertEqual(task["ref"]["object_type"], task_type)
                response = self.client.post(f"/api/events/{event}/actions", json=self.request_for(proposal))
                self.assertEqual(response.status_code, 200, response.text)
                receipt = response.json()
                after = self.workspace(event)["tasks"][0]
                self.assertEqual(receipt["result"]["domain_action"], domain_action)
                self.assertEqual(after["version"], task["version"] + 1)
                self.assertNotEqual(after["resource_label"], task["resource_label"])
                self.assertEqual(after["progress"], task["progress"])
                self.assertEqual(after["status"], task["status"])

    def test_action_idempotency_and_payload_mismatch(self):
        _, _, proposal = self.report_and_proposal()
        request = self.request_for(proposal)
        first = self.client.post("/api/events/golf-demo/actions", json=request)
        second = self.client.post("/api/events/golf-demo/actions", json=request)
        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(len(self.workspace()["receipts"]), 1)
        request["expected_version"] += 1
        self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=request).status_code, 409)

    def test_stale_action_rejected_without_partial_write(self):
        task, _, proposal = self.report_and_proposal()
        acknowledge = {"action_type": "acknowledge", "target_ref": task["ref"], "expected_version": task["version"], "idempotency_key": "ack-first-001"}
        self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=acknowledge, headers=STAFF).status_code, 200)
        response = self.client.post("/api/events/golf-demo/actions", json=self.request_for(proposal))
        self.assertEqual(response.status_code, 409)
        after = self.workspace()
        self.assertEqual(after["tasks"][0]["resource_label"], task["resource_label"])
        self.assertEqual(after["tasks"][0]["progress"], task["progress"])
        self.assertEqual(after["proposals"][0]["status"], "proposed")
        self.assertEqual(len(after["receipts"]), 1)

    def test_roles_and_assigned_task_scope_enforced_server_side(self):
        workspace = self.workspace()
        self.assertEqual(len(self.workspace(headers=STAFF)["tasks"]), 1)
        other = workspace["tasks"][1]
        request = {"action_type": "acknowledge", "target_ref": other["ref"], "expected_version": other["version"], "idempotency_key": "forbidden-0001"}
        self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=request, headers=STAFF).status_code, 403)
        self.assertEqual(self.client.post("/api/events/golf-demo/messages", json={"text":"汇报", "subject_ref":other["ref"]}, headers=STAFF).status_code, 403)
        _, _, proposal = self.report_and_proposal()
        self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=self.request_for(proposal), headers=STAFF).status_code, 403)
        self.assertEqual(self.client.get("/api/bootstrap", headers={"X-Demo-Role":"dispatcher","X-Demo-Actor":"demo-staff"}).status_code, 403)

    def test_cross_event_references_and_evidence_rejected(self):
        _, message, proposal = self.report_and_proposal()
        self.assertEqual(self.client.post("/api/events/conference-demo/messages", json={"text":"故障", "subject_ref":proposal["target_ref"]}).status_code, 404)
        self.assertEqual(self.client.post("/api/events/conference-demo/proposals", json={"message_id":message["id"]}).status_code, 404)
        self.assertEqual(self.client.post("/api/events/conference-demo/actions", json=self.request_for(proposal)).status_code, 404)

    def test_resource_reservations_and_capacity_checked(self):
        task = self.workspace()["tasks"][0]
        options = task["replacement_options"]
        self.assertEqual([item["ref"]["id"] for item in options], ["car-03"])
        for resource in ("car-05", "car-small", "car-02"):
            with self.subTest(resource=resource):
                body = {"action_type":"replace_resource", "target_ref":task["ref"], "expected_version":task["version"], "idempotency_key":f"capacity-{resource}", "parameters":{"replacement_ref":{"ontology_id":"golf.v1","object_type":"Vehicle","id":resource}}}
                self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=body).status_code, 409)
        self.assertEqual(self.workspace()["tasks"][0]["version"], 1)
        self.assertEqual(self.workspace()["receipts"], [])

    def test_proposal_payload_must_match_and_needs_subject(self):
        _, _, proposal = self.report_and_proposal()
        body = self.request_for(proposal)
        body["parameters"]["replacement_ref"]["id"] = "car-05"
        self.assertEqual(self.client.post("/api/events/golf-demo/actions", json=body).status_code, 409)
        message = self.client.post("/api/events/golf-demo/messages", json={"text":"有故障"}).json()
        self.assertEqual(self.client.post("/api/events/golf-demo/proposals", json={"message_id":message["id"]}).status_code, 422)

    def test_data_persists_across_new_app_instance(self):
        _, _, proposal = self.report_and_proposal()
        self.client.post("/api/events/golf-demo/actions", json=self.request_for(proposal))
        with TestClient(create_app(self.db)) as client:
            after = client.get("/api/events/golf-demo/workspace").json()
            self.assertEqual(len(after["messages"]), 1)
            self.assertEqual(len(after["receipts"]), 1)
            self.assertEqual(after["tasks"][0]["version"], 2)

    def test_competing_actions_cannot_double_reserve_resource(self):
        tasks = self.workspace()["tasks"]
        requests = [
            {"action_type":"replace_resource", "target_ref":task["ref"], "expected_version":task["version"],
             "idempotency_key":f"race-resource-{index}",
             "parameters":{"replacement_ref":{"ontology_id":"golf.v1","object_type":"Vehicle","id":"car-03"}}}
            for index, task in enumerate(tasks)
        ]
        def submit(body):
            with TestClient(create_app(self.db)) as client:
                return client.post("/api/events/golf-demo/actions", json=body).status_code
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(submit, requests))
        self.assertEqual(sorted(statuses), [200, 409])
        after = self.workspace()
        self.assertEqual(len(after["receipts"]), 1)
        self.assertEqual(sum(task["resource_label"] == "03 号备用车" for task in after["tasks"]), 1)


if __name__ == "__main__":
    unittest.main()
