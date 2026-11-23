import unittest

from app.flights import correlation_window, normalize_arrivals


class ArrivalProjectionTests(unittest.TestCase):
    def test_estimates_and_actual_airport_states_remain_distinct(self):
        day = "2026-11-22"
        payload = [{
            "date": day,
            "arrival": True,
            "cargo": False,
            "lastUpdatedTime": day + "T17:00:00+08:00",
            "list": [
                {"flight": [{"no": "EX 100"}], "status": "Est at 17:29", "time": "17:15"},
                {"flight": [{"no": "EX 101"}], "status": "Landed 16:53", "time": "17:00"},
                {"flight": [{"no": "EX 102"}], "status": "At gate 00:10 (23/11/2026)", "time": "23:58"},
                {"flight": [{"no": "EX 103"}], "status": "Cancelled"},
            ],
        }]
        estimate, landed, gate, cancelled = normalize_arrivals(payload, day)["arrivals"]
        self.assertEqual((estimate["status"], estimate["estimated"], estimate["actual"]), ("estimated", "17:29", None))
        self.assertEqual((landed["status"], landed["actual"]), ("landed", "16:53"))
        self.assertEqual((gate["status"], gate["actual"]), ("at_gate", "00:10 (23/11/2026)"))
        self.assertEqual(cancelled["status"], "cancelled")

    def test_only_primary_flight_with_unique_callsign_can_link(self):
        day = "2026-11-22"
        primary = {"no": "CX 841", "airline": "CPA"}
        payload = [{
            "date": day, "arrival": True, "cargo": False, "lastUpdatedTime": day + "T17:00:00+08:00",
            "list": [
                {"flight": [primary, {"no": "AA 123", "airline": "AAL"}], "time": "17:55"},
                {"flight": [{"no": "CX 0949A", "airline": "CPA"}], "status": "Landed 17:12", "time": "17:30"},
                {"flight": [{"no": "CX 0949A", "airline": "CPA"}], "time": "20:30"},
            ],
        }]
        first, second, third = normalize_arrivals(payload, day)["arrivals"]
        self.assertEqual(first["callsign"], "CPA841")
        self.assertEqual(first["flights"], ["CX 841", "AA 123"])
        self.assertIsNone(second["callsign"])
        self.assertIsNone(third["callsign"])
        midnight = correlation_window(day, "00:10 (23/11/2026)", 45)
        self.assertEqual(midnight[1] - midnight[0], 90 * 60)
        self.assertIsNone(correlation_window(day, "not-a-time", 45))


if __name__ == "__main__":
    unittest.main()
