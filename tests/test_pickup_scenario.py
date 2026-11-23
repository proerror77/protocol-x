import json
from datetime import datetime, timedelta
from uuid import uuid4

from fastapi.testclient import TestClient

from app.flights import AIRPORT_TZ
from app.main import create_app
from app.ptt_agent import record_incident
from app.store import dump
from test_arrival_desk import DISPATCH, EVENT, ROOT, ok


def test_compare_dispatch_and_revalidate_pickup_scenario(tmp_path, monkeypatch):
    monkeypatch.setenv("OPS_PILOT_DISPATCHER_TOKEN", "D" * 40)
    monkeypatch.setenv("OPS_PILOT_STAFF_TOKEN", "S" * 40)
    frozen = datetime(2026, 11, 23, 14, 0, tzinfo=AIRPORT_TZ)
    monkeypatch.setattr("app.pickup_scenario.now_at_airport", lambda: frozen)
    app = create_app(tmp_path / "scenario.db")
    client = TestClient(app)
    field = f"/api/field/events/{EVENT}"
    channel = ok(client.post(field + "/channels", headers=DISPATCH, json={"name": "演练频道"}))["id"]

    def member(name, role="staff"):
        body = {"name": name, "callsign": name, "role": role, "channel_ids": [channel]}
        person = ok(client.post(field + "/people", headers=DISPATCH, json=body))
        device = ok(client.post("/api/field/bind", json={"code": person["binding_code"]}))
        return person["actor"], {"Authorization": "Bearer " + device["session_token"]}

    dispatcher_id, dispatcher = member("推演调度", "dispatcher")
    driver_id, driver = member("司机")
    greeter_id, greeter = member("接机员")
    _, other = member("活动其他人员")
    task_id = str(uuid4())
    body = {
        "expected_version": 0, "players": [{"id": str(uuid4()), "name": "演练球员"}],
        "flight": "CX 001", "flight_date": "2026-11-23", "driver": driver_id, "greeter": greeter_id,
        "vehicle_id": "arrival-van-1", "meeting_point": "接待点 A", "synthetic": True,
        "hotel": {"name": "演练酒店", "address": "演练地址", "check_in": "2026-11-23",
                  "check_out": "2026-11-24", "transfer_minutes": 25},
    }
    ok(client.put(f"{ROOT}/arrival-plans/{task_id}", headers=dispatcher, json=body))

    release = {"task_id": task_id, "expected_version": 1, "request_id": str(uuid4())}
    assert client.post(field + "/dispatch/arrival", headers=driver, json=release).status_code == 403
    dispatched = ok(client.post(field + "/dispatch/arrival", headers=dispatcher, json=release))
    assert dispatched == ok(client.post(field + "/dispatch/arrival", headers=dispatcher, json=release))
    for assigned in (greeter, driver):
        assert [t["ref"]["id"] for t in ok(client.get(field + "/inbox", headers=assigned))["tasks"]] == [task_id]
        ok(client.post(field + "/received", headers=assigned, json={"task_id": task_id}))
    assert ok(client.get(field + "/inbox", headers=other))["tasks"] == []

    store = app.state.store
    source = frozen.isoformat()
    snapshot = {"state": "current", "date": "2026-11-23", "source_updated_at": source,
                "arrivals": [{"flights": ["CX 001"], "status": "at_gate", "scheduled": "14:00", "actual": "14:00"}]}
    with store.connect(write=True) as conn:
        original = conn.execute("SELECT data FROM objects WHERE event_id=? AND id=?", (EVENT, task_id)).fetchone()
        task = json.loads(original["data"])
        followup = {**task, "vehicle_id": None, "title": "后续任务", "arrival_managed": False,
                    "pickup_at": (frozen + timedelta(minutes=130)).isoformat()}
        conn.execute("INSERT INTO objects VALUES (?,?,?,?,1,?)",
                     (EVENT, "golf.v1", "TransferLeg", "followup", dump(followup)))
        conn.execute("INSERT INTO objects VALUES (?,?,?,?,1,?)",
                     (EVENT, "golf.v1", "Vehicle", "spare-test", dump({
                         "callsign": "演练备用车", "seats": 8, "readiness": "available",
                     })))
    app.state.reception.pickup.observe(snapshot)
    record_incident(store.path, app.state.definitions, channel,
                    {"track_sid": "TR_Scenario", "actor": greeter_id, "text": "CX 001 车坏了，请协助。"})
    channel_url = f"{field}/channels/{channel}"
    incident = ok(client.get(channel_url + "/dispatch", headers=dispatcher))["incidents"][0]
    assert incident["pickup_scenario"] is True
    url = f"{channel_url}/incidents/{incident['id']}/scenario"
    form = {"alternate_point": "接待点 B"}
    assert client.post(url, headers=greeter, json=form).status_code == 403
    preview = ok(client.post(url, headers=dispatcher, json=form))
    wait, reserve, move = preview["options"]
    assert [item["id"] for item in preview["options"]] == ["wait", "reserve", "move"]
    assert wait["meeting_from"] == "2026-11-23T14:35:00+08:00"
    assert reserve["vehicle_minutes"] == wait["vehicle_minutes"] - 8
    assert reserve["vehicle"] != wait["vehicle"]
    assert move["meeting_to"] == "2026-11-23T15:17:00+08:00"
    assert move["impacts"][0]["title"] == "后续任务"
    assert move["impacts"][0]["shared"] == ["司机"]
    assert move["blocked"]
    assert not ok(client.get(channel_url + "/dispatch", headers=driver))["instructions"]
    approval_url = f"{url}/{preview['id']}/approve"
    approval = {"option": "reserve", "request_id": str(uuid4())}
    assert client.post(approval_url, headers=dispatcher, json={**approval, "option": "move"}).status_code == 409
    with store.connect(write=True) as conn:
        conn.execute("UPDATE objects SET version=version+1 WHERE event_id=? AND id=?", (EVENT, task_id))
    assert client.post(approval_url, headers=dispatcher, json=approval).status_code == 409
    fresh = ok(client.post(url, headers=dispatcher, json=form))
    assert fresh["snapshot"] != preview["snapshot"]
    approved_url = f"{url}/{fresh['id']}/approve"
    receipt = ok(client.post(approved_url, headers=dispatcher, json=approval))
    assert receipt["actor"] == dispatcher_id
    assert ok(client.post(approved_url, headers=dispatcher, json=approval)) == receipt
    assert client.post(url, headers=dispatcher, json=form).status_code == 409
    instruction = ok(client.get(channel_url + "/dispatch", headers=greeter))["instructions"][0]
    assert instruction["recipients"] == sorted([driver_id, greeter_id])
    assert instruction["id"] == receipt["result"]["instruction_id"]
    assert ok(client.get(channel_url + "/dispatch", headers=other))["instructions"] == []
    receive = field + "/dispatch/" + instruction["id"] + "/receive"
    ok(client.post(receive, headers=greeter))
    assert ok(client.get(channel_url + "/dispatch", headers=dispatcher))["instructions"][0]["received_count"] == 1
    card = next(c for c in ok(client.get(ROOT + "/workspace", headers=greeter))["arrival_desk"]["cards"] if c["id"] == task_id)
    assert card["task"]["resource_label"] == reserve["vehicle"]
    assert card["task"]["pickup_at"] == reserve["meeting_from"]
