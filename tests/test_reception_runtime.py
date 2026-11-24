from contextlib import contextmanager
from datetime import datetime, timedelta
import json
import os
import socket
import time
from threading import Thread
from uuid import uuid4

import httpx
import uvicorn
from fastapi.testclient import TestClient

from app.flights import AIRPORT_TZ
from app.main import create_app
from test_arrival_desk import DISPATCH, EVENT, ROOT, ok


class AirportReplay:
    def __init__(self, day):
        self.day = day
        self.set("10:05:00", "landed", "at_gate")

    def set(self, source_time, one, two):
        self.snapshot = {
            "date": self.day, "state": "current", "source_updated_at": f"{self.day}T{source_time}+08:00",
            "arrivals": [
                {"flights": [f"QX {number}"], "status": status, "scheduled": "10:00",
                 "actual": actual, "estimated": None, "arrivals_hall": hall}
                for number, status, actual, hall in [(701, one, "10:03", "A"), (702, two, "10:04", "B")]
            ],
        }

    def get(self, day=None):
        if day is not None and day != self.day:
            raise AssertionError(f"Unexpected fixture date {day}")
        return self.snapshot


@contextmanager
def live_server(app):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="warning", timeout_graceful_shutdown=1))
        thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 5
            while not server.started:
                assert thread.is_alive() and time.monotonic() < deadline, "Scenario server did not start"
                time.sleep(0.01)
            yield f"http://127.0.0.1:{port}"
        finally:
            server.should_exit = True
            thread.join(5)
            assert not thread.is_alive()


def test_two_flights_three_crews_and_one_missing_player(tmp_path, monkeypatch):
    monkeypatch.setenv("OPS_PILOT_DISPATCHER_TOKEN", "D" * 40)
    monkeypatch.setenv("OPS_PILOT_STAFF_TOKEN", "S" * 40)
    app = create_app(os.getenv("PROTOCOL_X_SCENARIO_DSN") or tmp_path / "reception.db")
    client = TestClient(app)
    field = f"/api/field/events/{EVENT}"
    channel = ok(client.post(field + "/channels", headers=DISPATCH, json={"name": "到港演练"}))["id"]

    def member(name, role="staff"):
        person = ok(client.post(field + "/people", headers=DISPATCH, json={
            "name": name, "callsign": name, "role": role, "channel_ids": [channel],
        }))
        bound = ok(client.post("/api/field/bind", json={"code": person["binding_code"]}))
        return person["actor"], {"Authorization": "Bearer " + bound["session_token"]}

    center = member("值班中台", "dispatcher")
    greeter = member("航班一接机员")
    second_greeter = member("航班二接机员")
    drivers = [member(f"{label}车司机") for label in "ABC"]
    bystander = member("未指派人员")
    day = datetime.now(AIRPORT_TZ).date()
    airport = AirportReplay(day.isoformat())
    pickup = app.state.reception.pickup
    pickup.feed = airport

    def make_plan(flight, number, names, greeter, driver):
        task_id = str(uuid4())
        players = [{"id": str(uuid4()), "name": name} for name in names]
        path = ROOT + "/arrival-plans/" + task_id
        ok(client.put(path, headers=center[1], json={
            "expected_version": 0, "players": players, "flight": flight, "flight_date": day.isoformat(),
            "vehicle_id": f"arrival-van-{number}", "driver": driver[0], "greeter": greeter[0],
            "meeting_point": f"{number}号会合点", "hotel": {
                "name": "演练酒店", "address": "香港", "check_in": day.isoformat(),
                "check_out": (day + timedelta(days=1)).isoformat(), "confirmed": True,
            },
        }))
        return task_id, players, path

    a = make_plan("QX701", 1, ["球员甲", "球员乙", "球员丙"], greeter, drivers[0])
    b = make_plan("QX701", 2, ["球员丁"], greeter, drivers[1])
    c = make_plan("QX702", 3, ["球员戊"], second_greeter, drivers[2])
    event_path = ROOT + "/arrival-events"
    inbox = "/api/arrival-flight-notices"
    assert ok(client.get(event_path, headers=center[1]))["items"] == []

    # The lifespan starts the actual airport worker. Only its upstream airport feed is replaced.
    with live_server(app) as address, httpx.Client(base_url=address, timeout=8) as http:
        with http.stream("GET", inbox + "/stream", headers=greeter[1]) as stream:
            assert stream.status_code == 200, stream.read()
            messages = (json.loads(line[6:]) for line in stream.iter_lines() if line.startswith("data: "))
            first, second = next(messages), next(messages)
        assert {first["task_id"], second["task_id"]} == {a[0], b[0]}
        assert (first["announce"], second["announce"]) == (True, False)
        assert first["runtime_event_seq"] == second["runtime_event_seq"]
        payload = ok(http.get(event_path, headers=center[1]))
        arrival_events = {row["flight"]: row for row in payload["items"]}
        one, two = arrival_events["QX701"], arrival_events["QX702"]
        assert {(row["type"], row["source"], row["rule"]) for row in arrival_events.values()} == {
            ("FlightArrived", "HKIA", "NotifyFlightArrival")
        }
        assert one["status"] == "landed" and two["status"] == "at_gate"
        assert len(arrival_events) == 2

        # Duplicate feed and the later at-gate transition do not create a second actionable alert.
        pickup.poll()
        airport.set("10:09:00", "at_gate", "at_gate")
        pickup.poll()
        assert ok(http.get(event_path, params={"after": payload["next_after"]}, headers=center[1]))["items"] == []
        updates = ok(http.get(inbox, params={"after": second["seq"]}, headers=greeter[1]))["items"]
        assert len(updates) == 2 and all(item["kind"] == "at_gate" and not item["announce"] for item in updates)
        assert ok(http.get(inbox, headers=bystander[1]))["items"] == []
        assert http.get(event_path, headers=greeter[1]).status_code == 403

        for person, event in [(greeter, one), (drivers[0], one), (drivers[1], one),
                              (second_greeter, two), (drivers[2], two)]:
            notices = ok(http.get(inbox, headers=person[1]))["items"]
            assert len([item for item in notices if item["announce"] and item["runtime_event_seq"] == event["seq"]]) == 1
            assert {item["runtime_event_seq"] for item in notices} == {event["seq"]}

        def receipt(person, event, status):
            return http.post(f"{event_path}/{event['seq']}/receipt", headers=person[1], json={"status": status})

        assert receipt(bystander, one, "acknowledged").status_code == 404
        assert receipt(drivers[2], one, "received").status_code == 404
        assert receipt(center, one, "acknowledged").status_code == 403
        delivered = ok(receipt(greeter, one, "received"))
        assert delivered["received_at"] and delivered["acknowledged_at"] is None
        acknowledged = ok(receipt(greeter, one, "acknowledged"))
        assert acknowledged["received_at"] == delivered["received_at"]
        assert ok(receipt(greeter, one, "acknowledged")) == acknowledged
        ok(receipt(drivers[0], one, "received"))
        ok(receipt(drivers[1], one, "acknowledged"))
        ok(receipt(second_greeter, two, "acknowledged"))
        # Driver C has been offline. Reconnect from prior cursor and read only the assigned flight.
        with http.stream("GET", inbox + "/stream?after=0", headers=drivers[2][1]) as stream:
            restored = next(json.loads(line[6:]) for line in stream.iter_lines() if line.startswith("data: "))
        assert restored["runtime_event_seq"] == two["seq"] and restored["announce"]
        ok(receipt(drivers[2], two, "received"))

        def stage(plan, person, version, label, players, key=None):
            return http.post(plan[2] + "/stages", headers=person[1], json={
                "expected_version": version, "stage": label, "player_ids": [p["id"] for p in players],
                "request_id": key or str(uuid4()),
            })

        progressed = a[1][:2]
        missing = a[1][2]
        assert http.post(a[2] + "/stages", headers=greeter[1], json={
            "expected_version": 1, "stage": "met",
        }).status_code == 422
        assert stage(a, drivers[0], 1, "boarded", progressed).status_code == 409
        key = str(uuid4())
        met = ok(stage(a, greeter, 1, "met", progressed, key))
        assert ok(stage(a, greeter, 1, "met", progressed, key)) == met
        assert stage(a, greeter, 1, "met", [missing], key).status_code == 409
        exception = ok(http.post(a[2] + "/player-exceptions", headers=greeter[1], json={
            "expected_version": 2, "player_id": missing["id"], "detail": "未到 A 区会合点",
        }))
        assert exception["exception"]["status"] == "open"
        assert stage(a, drivers[0], 3, "boarded", a[1]).status_code == 409
        ok(stage(a, drivers[0], 3, "boarded", progressed))
        for plan, airport_staff, driver in [(b, greeter, drivers[1]), (c, second_greeter, drivers[2])]:
            version = ok(stage(plan, airport_staff, 1, "met", plan[1]))["version"]
            for step in ("boarded", "hotel_handoff", "checked_in"):
                version = ok(stage(plan, driver, version, step, plan[1]))["version"]

        desk = ok(http.get(ROOT + "/workspace", headers=center[1]))["arrival_desk"]
        cards = {card["id"]: card for card in desk["cards"]}
        assert set(cards) == {a[0], b[0], c[0]}
        view = cards[a[0]]
        assert view["observation"]["status"] == "at_gate"
        assert [p["state"] for p in view["player_receptions"]] == ["boarded", "boarded", "waiting_meet"]
        assert view["player_receptions"][2]["exception"]["status"] == "open"
        assert not any(action["id"] == "replace_resource" for action in view["task"]["allowed_actions"])
        for plan in (b, c):
            assert cards[plan[0]]["player_receptions"][0]["state"] == "checked_in"
        receipts = {item["actor"]: item for item in view["arrival_notice"]["recipients"]}
        assert receipts[greeter[0]]["acknowledged_at"]
        assert receipts[drivers[0][0]]["received_at"] and not receipts[drivers[0][0]]["acknowledged_at"]
        assert ok(http.get(ROOT + "/workspace", headers=bystander[1]))["arrival_desk"]["cards"] == []
        print("2 FlightArrived events, 3 crews, exactly 1 pickup exception. Driver A is delivered but unacknowledged.")
