from datetime import datetime, timedelta
from pathlib import Path
import tempfile
import unittest

from fastapi.testclient import TestClient

from app.flights import AIRPORT_TZ
from app.main import create_app
from app.pickup_watch import PickupFlight, PickupWatch


EVENT = "golf-hkg-dispatch-demo"
ACTOR = {"role": "dispatcher", "actor": "demo-dispatcher"}


class FlightDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.app = create_app(Path(self.temp.name) / "runtime.sqlite")
        self.client = TestClient(self.app)
        self.now = datetime.now(AIRPORT_TZ).replace(second=0, microsecond=0)
        self.day = self.now.date().isoformat()
        self.eta = self.now + timedelta(minutes=20)
        self.snapshot = {
            "date": self.day, "state": "current", "source_updated_at": self.now.isoformat(),
            "arrivals": [{
                "flights": ["CX 100"], "status": "estimated", "actual": None,
                "scheduled": (self.now - timedelta(minutes=20)).strftime("%H:%M (%d/%m/%Y)"),
                "estimated": self.eta.strftime("%H:%M (%d/%m/%Y)"),
            }],
        }
        class Feed:
            def get(_self):
                return self.snapshot

        self.watch = PickupWatch(self.app.state.store, Feed(), self.app.state.definitions)

    def tearDown(self):
        self.client.close()
        self.temp.cleanup()

    def workspace(self):
        response = self.client.get(f"/api/events/{EVENT}/workspace")
        self.assertEqual(response.status_code, 200)
        return response.json()

    def bind(self, task_id):
        ref = {"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": task_id}
        self.watch.bind(EVENT, ACTOR, PickupFlight(task_ref=ref, date=self.day, flight="CX 100"))

    def proposal(self, name):
        return next(p for p in self.workspace()["proposals"]
                    if p["action_type"] == name and p["status"] == "proposed")

    def action(self, proposal, headers=None):
        return self.client.post(f"/api/events/{EVENT}/actions", headers=headers, json={
            "proposal_id": proposal["id"], "target_ref": proposal["target_ref"],
            "expected_version": proposal["expected_version"], "action_type": proposal["action_type"],
            "parameters": proposal["parameters"], "idempotency_key": "confirm-" + proposal["id"],
        })

    def test_reschedule_requires_authorized_confirmation_and_is_idempotent(self):
        self.bind("dispatch-time-001")
        p = self.proposal("reschedule_pickup")
        before = next(t for t in self.workspace()["tasks"] if t["ref"]["id"] == "dispatch-time-001")
        self.assertIsNone(before["pickup_at"])
        self.assertEqual(self.action(p, {"X-Demo-Role": "staff"}).status_code, 403)
        first = self.action(p)
        self.assertEqual(first.status_code, 200, first.text)
        self.assertEqual(self.action(p).json(), first.json())
        result = first.json()["result"]
        self.assertEqual(result["pickup_at"], (self.eta + timedelta(minutes=30)).isoformat())
        self.assertTrue(result["service_progress_unchanged"])
        after = next(t for t in self.workspace()["tasks"] if t["ref"]["id"] == "dispatch-time-001")
        self.assertEqual((after["owner"], after["resource_ref"], after["progress"]),
                         (before["owner"], before["resource_ref"], before["progress"]))

    def test_changed_eta_expires_proposal_and_swap_rechecks_resource(self):
        self.bind("dispatch-vehicle-001")
        swap = self.proposal("replace_resource")
        with self.app.state.store.connect(write=True) as conn:
            conn.execute("UPDATE objects SET data=json_set(data,'$.readiness','unavailable') WHERE event_id=? AND id='demo-car-spare'",
                         (EVENT,))
        self.assertEqual(self.action(swap).status_code, 409)
        old = self.proposal("reschedule_pickup")
        self.snapshot["source_updated_at"] = (self.now + timedelta(seconds=1)).isoformat()
        self.snapshot["arrivals"][0]["estimated"] = (self.eta + timedelta(minutes=10)).strftime("%H:%M (%d/%m/%Y)")
        self.watch.observe(self.snapshot)
        self.assertEqual(self.action(old).status_code, 409)


if __name__ == "__main__":
    unittest.main()
