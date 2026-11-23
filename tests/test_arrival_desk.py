import base64
from datetime import datetime
from uuid import uuid4

from fastapi.testclient import TestClient

from app.ptt_agent import choose_task, incident_kind, incident_statuses, record_incident
from app.arrival_desk import hotel_risk, reception_window
from app.flights import AIRPORT_TZ
from app.main import create_app

EVENT = "golf-hkg-arrivals-demo"
ROOT = f"/api/events/{EVENT}"
DISPATCH = {"Authorization": "Bearer " + "D" * 40}
DEMO_STAFF = {"Authorization": "Bearer " + "S" * 40}
PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3YQAAAAASUVORK5CYII="


def ok(response):
    assert response.status_code < 300, response.text
    if response.status_code != 204:
        return response.json()


def test_personal_reception_from_hkia_to_check_in(tmp_path, monkeypatch):
    monkeypatch.setenv("OPS_PILOT_DISPATCHER_TOKEN", "D" * 40)
    monkeypatch.setenv("OPS_PILOT_STAFF_TOKEN", "S" * 40)
    app = create_app(tmp_path / "arrival.db")
    client = TestClient(app)
    field = f"/api/field/events/{EVENT}"
    channel = ok(client.post(field + "/channels", headers=DISPATCH, json={"name": "接待组"}))["id"]

    def member(name, role="staff"):
        person = ok(client.post(field + "/people", headers=DISPATCH, json={
            "name": name, "callsign": name, "role": role, "channel_ids": [channel],
        }))
        bound = ok(client.post("/api/field/bind", json={"code": person["binding_code"]}))
        return person["actor"], {"Authorization": "Bearer " + bound["session_token"]}

    _, dispatcher = member("测试调度员", "dispatcher")
    greeter_id, greeter = member("测试接机员")
    driver_id, driver = member("测试司机")
    _, unassigned = member("本活动其他人员")
    task_id = str(uuid4())
    player_id = str(uuid4())
    day = datetime.now(AIRPORT_TZ).date()
    next_day = day.fromordinal(day.toordinal() + 1)
    endpoint = ROOT + "/arrival-plans/" + task_id
    photo = endpoint + f"/players/{player_id}/photo"
    body = {
        "expected_version": 0, "players": [{"id": player_id, "name": "模拟球员"}],
        "flight": "CX 001", "flight_date": day.isoformat(), "driver": driver_id, "greeter": greeter_id,
        "vehicle_id": "arrival-van-1", "meeting_point": "一号接待点",
        "hotel": {
            "name": "演练酒店", "address": "香港演练地址", "check_in": day.isoformat(), "check_out": next_day.isoformat(),
            "latest_check_in": "18:00", "transfer_minutes": 45,
        },
    }
    assert client.put(endpoint, json=body, headers=DISPATCH).status_code == 403
    assert ok(client.put(endpoint, json=body, headers=dispatcher))["version"] == 1
    assert client.put(endpoint, json=body, headers=dispatcher).status_code == 409
    assert client.put(ROOT + "/arrival-plans/" + str(uuid4()), json=body, headers=dispatcher).status_code == 409
    assert client.put(photo, json={"data": PNG}, headers=greeter).status_code == 403
    assert client.put(photo, json={"data": "xxxx"}, headers=dispatcher).status_code == 422
    ok(client.put(photo, json={"data": PNG}, headers=dispatcher))
    image = client.get(photo, headers=driver)
    assert image.content == base64.b64decode(PNG)
    assert image.headers["cache-control"] == "private, no-store"
    for identity in (unassigned, DEMO_STAFF, DISPATCH, {}):
        assert client.get(photo, headers=identity).status_code in {401, 404}
        workspace = client.get(ROOT + "/workspace", headers=identity)
        if workspace.status_code == 200:
            assert workspace.json()["arrival_desk"]["cards"] == []
            assert workspace.json()["tasks"] == []

    def observe(at, **change):
        app.state.reception.pickup.observe({
            "date": day.isoformat(), "state": "current", "source_updated_at": f"{day}T{at}+08:00",
            "arrivals": [{"flights": ["CX 001"], "scheduled": "17:00", "estimated": "17:35", "actual": None,
                         "status": "estimated", "arrivals_hall": "A", **change}],
        })

    observe("17:01:00")
    observe("17:02:00")
    observe("17:36:00", estimated=None, actual="17:36", status="landed")
    observe("17:43:00", estimated=None, actual="17:43", status="at_gate", baggage_belt="7")
    inbox = "/api/arrival-flight-notices"
    for identity in (greeter, driver):
        response = client.get(inbox, headers=identity)
        payload = ok(response)
        assert response.headers["cache-control"] == "private, no-store"
        assert [item["kind"] for item in payload["items"]] == ["delay", "landed", "at_gate"]
        assert {item["task_id"] for item in payload["items"]} == {task_id}
        after = payload["next_after"]
        assert ok(client.get(f"{inbox}?after={after}", headers=identity))["items"] == []
        assert all("players" not in item for item in payload["items"])
    assert ok(client.get(inbox, headers=unassigned))["items"] == []
    assert client.get(inbox, headers=DISPATCH).status_code == 403
    assert client.get(inbox, headers=DEMO_STAFF).status_code == 403
    assert client.get(inbox + "/stream", headers=DISPATCH).status_code == 403
    assert client.get(inbox + "/stream", headers=DEMO_STAFF).status_code == 403
    reviewed = {"task": {"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": task_id},
                "request_id": str(uuid4()), "text": "会合点入口关闭，演练回报"}
    report_url = ROOT + "/qrf/reports"
    assert client.post(report_url, json=reviewed, headers=unassigned).status_code == 403
    written = ok(client.post(report_url, json=reviewed, headers=greeter))
    assert written["source"] == "qrf_exception" and written["text"] == reviewed["text"]
    assert ok(client.post(report_url, json=reviewed, headers=greeter))["id"] == written["id"]
    assert client.post(report_url, json={**reviewed, "text": "另一条说法"}, headers=greeter).status_code == 409
    ack = ok(client.post(f"{report_url}/{written['id']}/acknowledge", headers=dispatcher))
    assert ack["result"]["message_id"] == written["id"]
    assert incident_kind("没有车辆故障。") is None
    assert incident_kind("入口关闭？") is None
    assert incident_kind("架車開唔到，請支援。") == "vehicle"
    channel_message = {"track_sid": "TR_Test123", "actor": driver_id, "text": "车辆故障无法启动，请协助。"}
    state = app.state
    with state.store.connect() as conn:
        definition = state.definitions["golf.v1"]
        assert choose_task(conn, EVENT, definition, driver_id, "航班 ZH999 延误") is None
        assert choose_task(conn, EVENT, definition, driver_id, "CX 001 车辆故障")["id"] == task_id
    record_incident(state.store.path, state.definitions, channel, channel_message)
    record_incident(state.store.path, state.definitions, channel, channel_message)
    with state.store.connect() as conn:
        incidents = incident_statuses(conn, EVENT, channel)
        assert len(incidents) == 1 and incidents["TR_Test123"]["task_id"] == task_id
        proposals = state.store.records(conn, "proposals", EVENT)
        assert len([p for p in proposals if p["engine"] == "ptt_incident"]) == 1
        assert all(p["status"] == "proposed" for p in proposals if p["engine"] == "ptt_incident")
    assert not any(m["id"] == incidents["TR_Test123"]["id"]
                   for m in ok(client.get(ROOT + "/workspace", headers=DEMO_STAFF))["messages"])
    dispatch_url = f"/api/field/events/{EVENT}/channels/{channel}"
    dispatch_view = ok(client.get(dispatch_url + "/dispatch", headers=dispatcher))
    suggestion = dispatch_view["incidents"][0]
    assert suggestion["id"] == incidents["TR_Test123"]["id"]
    assert suggestion["vehicle_change"]["action_type"] == "replace_resource"
    assert "测试司机" in suggestion["recipients"]
    public_agent = ok(client.get(dispatch_url + "/dispatch", headers=greeter))
    assert public_agent["incidents"] == []
    assert public_agent["agent_activity"] == [{
        "id": suggestion["id"], "created_at": suggestion["created_at"], "label": "车辆故障", "dispatched": False,
    }]
    assert "一号接待点" == suggestion["meeting_point"]
    instruction = {"text": "先确认球员位置，随后在频道回报。",
                   "task_version": suggestion["version"], "request_id": str(uuid4())}
    send = dispatch_url + f"/incidents/{suggestion['id']}/dispatch"
    assert client.post(send, headers=driver, json=instruction).status_code == 403
    assert client.post(send, headers=dispatcher, json={**instruction, "task_version": 99}).status_code == 409
    given = ok(client.post(send, headers=dispatcher, json=instruction))
    assert ok(client.post(send, headers=dispatcher, json=instruction))["id"] == given["id"]
    assert not ok(client.get(dispatch_url + "/dispatch", headers=dispatcher))["incidents"]
    assert ok(client.get(dispatch_url + "/dispatch", headers=unassigned))["instructions"] == []
    assert ok(client.get(dispatch_url + "/dispatch", headers=greeter))["instructions"][0]["id"] == given["id"]
    without_audio = ok(client.get(dispatch_url + "/history", headers=greeter))
    assert not without_audio["enabled"] and without_audio["items"] == []
    assert without_audio["instructions"][0]["id"] == given["id"]
    received_url = f"/api/field/events/{EVENT}/dispatch/{given['id']}/receive"
    assert client.post(received_url, headers=unassigned).status_code == 404
    received = ok(client.post(received_url, headers=driver))
    assert ok(client.post(received_url, headers=driver))["id"] == received["id"]
    assert ok(client.get(dispatch_url + "/dispatch", headers=driver))["instructions"][0]["received"]
    assert ok(client.get(dispatch_url + "/dispatch", headers=dispatcher))["instructions"][0]["received_count"] == 1




    assert client.post(ROOT + "/pickup-flight", headers=dispatcher, json={
        "task_ref": {"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": task_id},
        "date": day.isoformat(), "flight": "CX 001",
    }).status_code in {409, 503}

    def card(headers):
        return ok(client.get(ROOT + "/workspace", headers=headers))["arrival_desk"]["cards"][0]

    arrival = card(greeter)
    assert arrival["observation"]["status"] == "at_gate"
    assert arrival["observation"]["baggage_belt"] == "7"
    assert arrival["window"]["from"].endswith("18:18:00+08:00")
    assert arrival["window"]["to"].endswith("18:48:00+08:00")
    assert arrival["hotel_deadline"]["certain"]
    kinds = [row["kind"] for row in arrival["history"]]
    assert kinds.count("delay") == 1
    assert kinds.count("hotel_late") == 1
    assert {"landed", "at_gate", "plan_created", "photo_updated"}.issubset(kinds)
    assert arrival["next_stage"] == "met"
    assert card(driver)["next_stage"] is None

    def stage(headers, version, name):
        return client.post(endpoint + "/stages", headers=headers, json={"expected_version": version, "stage": name})

    assert stage(driver, 1, "met").status_code == 409
    assert stage(greeter, 1, "boarded").status_code == 409
    assert ok(stage(greeter, 1, "met"))["action"] == "ConfirmAirportMeet"
    assert stage(driver, 1, "boarded").status_code == 409
    ok(stage(driver, 2, "boarded"))
    ok(stage(driver, 3, "hotel_handoff"))
    assert stage(driver, 4, "checked_in").status_code == 409
    assert card(driver)["next_stage"] is None
    # A reservation may be confirmed after handoff without changing the hotel's identity.
    current = card(dispatcher)
    body.update(expected_version=4, expected_task_version=current["task"]["version"])
    body["hotel"]["confirmed"] = True
    ok(client.put(endpoint, json=body, headers=dispatcher))
    ok(stage(driver, 5, "checked_in"))
    assert all(step["status"] == "done" and step["confirmed_at"] for step in card(driver)["task"]["progress"])
    assert "hotel_confirmed" in [row["kind"] for row in card(driver)["history"]]
    assert client.get("/arrivals").status_code == 200


def test_reception_estimate_rolls_after_midnight_and_handles_cancellation():
    rules = {"taxi_minutes": 10, "exit_min_minutes": 35, "exit_max_minutes": 65, "fresh_minutes": 10}
    flight = {"scheduled": "23:55", "actual": "00:08", "status": "landed", "source_time": "2026-11-23T00:11:00+08:00"}
    result = reception_window("2026-11-22", flight, rules)
    assert result["from"] == "2026-11-23T00:53:00+08:00"
    assert result["to"] == "2026-11-23T01:23:00+08:00"
    assert hotel_risk({"latest_check_in": None}, result) is None
    assert reception_window("2026-11-22", {**flight, "status": "cancelled"}, rules) is None
