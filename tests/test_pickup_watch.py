from pathlib import Path
import tempfile
import unittest

from app.bindings import load_definitions
from app.pickup_watch import PickupFlight, PickupWatch, delay_minutes
from app.store import Store


class PickupWatchTests(unittest.TestCase):
    def test_actual_status_events_are_scoped_deduplicated_and_do_not_change_task(self):
        day = "2026-11-22"
        flight = {"flights": ["CX 100"], "scheduled": "17:00", "estimated": "17:20",
                  "actual": None, "status": "estimated"}

        def snap(time, **change):
            return {"date": day, "source_updated_at": f"{day}T{time}+08:00",
                    "state": "current", "arrivals": [{**flight, **change}]}

        class Feed:
            def get(self):
                return snap("17:01:00")

        actor = {"role": "dispatcher", "actor": "demo-dispatcher"}
        with tempfile.TemporaryDirectory() as directory:
            definitions = load_definitions(Path.cwd() / "ontologies")
            store = Store(Path(directory) / "test.db", definitions)
            watch = PickupWatch(store, Feed())
            form = PickupFlight(date=day, flight="CX 100",
                                task_ref={"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": "transfer-001"})
            with store.connect() as conn:
                original = conn.execute("SELECT version,data FROM objects WHERE id='transfer-001'").fetchone()
            watch.bind("golf-demo", actor, form)
            watch.bind("golf-demo", actor, form)
            watch.observe(snap("17:02:00", estimated="17:23"))
            watch.observe(snap("17:24:00", status="landed", estimated=None, actual="17:24"))
            watch.observe(snap("17:25:00", status="landed", estimated=None, actual="17:24"))
            watch.observe(snap("17:01:30", estimated="18:00"))
            with store.connect() as conn:
                result = watch.project(conn, "golf-demo", actor)
                self.assertEqual([row["kind"] for row in result["reminders"]], ["landed", "delay"])
                self.assertFalse(result["reminders"][0]["initial_observation"])
                self.assertEqual(result["reminders"][0]["actual"], "17:24")
                self.assertEqual(watch.project(conn, "golf-demo", {"role": "dispatcher", "actor": "other"})["reminders"], [])
                self.assertIsNone(watch.project(conn, "golf-demo", {"role": "staff", "actor": "demo-staff"}))
                current = conn.execute("SELECT version,data FROM objects WHERE id='transfer-001'").fetchone()
                self.assertEqual(tuple(current), tuple(original))

    def test_clock_only_estimate_delayed_after_midnight(self):
        self.assertEqual(delay_minutes("2026-11-22", {
            "status": "estimated", "scheduled": "23:55", "estimated": "00:12",
        }), 17)


if __name__ == "__main__":
    unittest.main()
