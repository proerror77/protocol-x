import io
import json
import unittest
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.flights import register_flights
from app.main import create_app
from app.travel_actions import GOOGLE_KEY_FILE, GOOGLE_ROUTES, travel_links


EVENT = "golf-hkg-arrivals-demo"
DAY = "2026-11-24"
ARRIVAL = {"flights": ["CX 0841", "AA 0123"], "status": "estimated", "estimated": "17:45"}


class FlightFeed:
    def get(self, day=None):
        if day and day != DAY:
            raise ValueError("Outside window")
        return {"provider": "HKIA", "state": "current", "date": DAY, "airport": "HKG", "arrivals": [ARRIVAL]}


class TravelActionsTests(unittest.TestCase):
    def test_published_flight_query_preserves_code_share_identity_and_freshness(self):
        app = FastAPI()
        register_flights(app, lambda: {}, FlightFeed())
        client = TestClient(app)
        path = f"/api/events/{EVENT}/flight-arrivals/"
        response = client.get(path + "aa123", params={"service_date": DAY})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["cache-control"], "private, no-store")
        self.assertEqual(response.json(), {"provider": "HKIA", "state": "current", "date": DAY, "airport": "HKG",
                                          "requested_flight": "AA123", "flight": ARRIVAL})
        self.assertEqual(client.get(path + "cx841").json()["flight"], ARRIVAL)
        self.assertIsNone(client.get(path + "CX999").json()["flight"])
        self.assertEqual(client.get(path + "CX841", params={"service_date": "2000-01-01"}).status_code, 422)
        self.assertEqual(client.get("/api/events/conference-demo/flight-arrivals/CX841").status_code, 404)

    def test_reception_card_links_and_role_scoped_route_estimate(self):
        today = date.today()
        tomorrow = date.fromordinal(today.toordinal() + 1)
        pickup = {"latitude": 22.3089, "longitude": 113.9185}
        dropoff = {"latitude": 22.2848, "longitude": 114.158}
        data = {
            "expected_version": 0, "players": [{"id": str(uuid4()), "name": "合成球员"}],
            "flight": "CX841", "flight_date": today.isoformat(), "driver": "demo-staff",
            "greeter": "demo-staff", "vehicle_id": "arrival-van-1", "synthetic": True,
            "meeting_point": "T1 接机层 + A", "meeting_coordinates": pickup,
            "hotel": {"name": "合成酒店", "address": "香港中环 & 酒店地址", "coordinates": dropoff,
                      "check_in": today.isoformat(), "check_out": tomorrow.isoformat()},
        }
        legacy = {**data, "meeting_coordinates": None, "hotel": {**data["hotel"], "coordinates": None}}
        self.assertEqual(travel_links(legacy)["uber"], "https://m.uber.com/")
        self.assertFalse(travel_links(legacy)["uber_prefilled"])
        with TemporaryDirectory() as temporary:
            secret = "synthetic-key-for-tests"
            key_file = Path(temporary) / "google-key"
            key_file.write_text(secret)
            app = create_app(Path(temporary) / "ops.db")
            client = TestClient(app)
            prefix = f"/api/events/{EVENT}"
            plan_id = str(uuid4())
            path = prefix + "/arrival-plans/" + plan_id
            estimate = path + "/route-estimate"
            with patch.dict("os.environ", {GOOGLE_KEY_FILE: str(key_file)}):
                saved = client.put(path, json=data)
                self.assertEqual(saved.status_code, 200, saved.text)
                workspace = client.get(prefix + "/workspace", headers={"X-Demo-Role": "staff"})
                self.assertEqual(workspace.status_code, 200, workspace.text)
                card = next(row for row in workspace.json()["arrival_desk"]["cards"] if row["id"] == plan_id)
                links = card["travel_links"]
                directions = parse_qs(urlsplit(links["google_directions"]).query)
                self.assertEqual(directions["origin"], ["22.3089,113.9185"])
                self.assertEqual(directions["destination"], ["22.2848,114.158"])
                self.assertEqual(parse_qs(urlsplit(links["google_hotel"]).query)["query"],
                                 ["合成酒店 香港中环 & 酒店地址"])
                uber = parse_qs(urlsplit(links["uber"]).query)
                self.assertTrue(links["uber_prefilled"])
                self.assertEqual(json.loads(uber["pickup"][0]), {**pickup, "addressLine1": "香港國際機場，T1 接机层 + A"})
                self.assertEqual(json.loads(uber["drop[0]"][0]), {**dropoff, "addressLine1": data["hotel"]["address"]})
                self.assertNotIn("合成球员", str(links))
                with patch("app.travel_actions.build_opener") as opener:
                    opener.return_value.open.return_value = io.BytesIO(
                        b'{"routes":[{"distanceMeters":35829,"duration":"2314.2s"}]}')
                    self.assertEqual(client.post(estimate, params={"version": 2}).status_code, 409)
                    self.assertEqual(client.post(estimate.replace(plan_id, str(uuid4())), params={"version": 1}).status_code, 404)
                    opener.return_value.open.assert_not_called()
                    response = client.post(estimate, params={"version": 1}, headers={"X-Demo-Role": "staff"})
                    self.assertEqual(response.status_code, 200, response.text)
                    self.assertEqual(response.headers["cache-control"], "private, no-store")
                    self.assertEqual(response.json()["duration_seconds"], 2315)
                    self.assertEqual(response.json()["distance_meters"], 35829)
                    self.assertNotIn(secret, response.text)
                    request = opener.return_value.open.call_args.args[0]
                    self.assertEqual(request.full_url, GOOGLE_ROUTES)
                    self.assertEqual(request.get_header("X-goog-api-key"), secret)
                    self.assertEqual(json.loads(request.data), {
                        "origin": {"location": {"latLng": pickup}}, "destination": {"location": {"latLng": dropoff}},
                        "travelMode": "DRIVE", "regionCode": "HK",
                    })
                    current = client.get(prefix + "/workspace").json()["arrival_desk"]["cards"][0]
                    self.assertEqual(current["version"], 1)
                    self.assertEqual(current["hotel"]["transfer_minutes"], 45)
            with patch.dict("os.environ", {GOOGLE_KEY_FILE: ""}):
                self.assertEqual(client.post(estimate, params={"version": 1}).status_code, 503)
