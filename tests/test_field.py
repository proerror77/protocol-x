import base64
import hashlib
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models import ActionInput
from app.store import dump

EVENT = "/api/field/events/golf-demo"
DISPATCH = {"Authorization": "Bearer " + "D" * 40}


def ok(response):
    assert response.status_code < 300, response.text
    return response.json()


@pytest.fixture()
def field(tmp_path, monkeypatch):
    monkeypatch.setenv("OPS_PILOT_DISPATCHER_TOKEN", "D" * 40)
    monkeypatch.setenv("OPS_PILOT_STAFF_TOKEN", "S" * 40)
    monkeypatch.setenv("OPS_LIVEKIT_SERVER_URL", "http://127.0.0.1:1")
    monkeypatch.setenv("OPS_LIVEKIT_CLIENT_URL", "ws://127.0.0.1:1")
    monkeypatch.setenv("OPS_LIVEKIT_API_KEY", "local-field-test")
    monkeypatch.setenv("OPS_LIVEKIT_API_SECRET", "example-secret" * 3)
    monkeypatch.setenv("OPS_PTT_EVENT_ID", "golf-demo")
    monkeypatch.setenv("OPS_PTT_VOICE_DB", str(tmp_path / "voice.db"))
    app = create_app(tmp_path / "field.db")
    with TestClient(app) as client:
        yield client, app.state.voice_archive


def group(client, name):
    return ok(client.post(EVENT + "/channels", headers=DISPATCH, json={"name": name}))["id"]


def create_person(client, group_id, name):
    return ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": name, "callsign": name, "channel_ids": [group_id],
    }))


def bind_person(client, invitation):
    credentials = {"code": invitation["binding_code"]}
    preview = ok(client.post("/api/field/invitation", json=credentials))
    assert preview["identity"]["actor"] == invitation["actor"]
    bound = ok(client.post("/api/field/bind", json=credentials))
    assert client.post("/api/field/bind", json=credentials).status_code == 404
    return {"Authorization": "Bearer " + bound["session_token"]}


def test_binding_identity_and_revoked_radio_credentials(field):
    client, _ = field
    channel = group(client, "接驳")
    person = create_person(client, channel, "林晨")
    first = bind_person(client, person)
    bootstrap = ok(client.get("/api/bootstrap", headers=first))
    assert bootstrap["identity"]["name"] == "林晨"
    assert "device" not in bootstrap["identity"]
    assert [event["id"] for event in bootstrap["events"]] == ["golf-demo"]
    assert client.get("/api/events/conference-demo/workspace", headers=first).status_code == 403
    assert client.post("/api/events/golf-demo/ptt/join", headers=first).status_code == 403
    join = ok(client.post(EVENT + f"/channels/{channel}/ptt/join", headers=first))
    media_header = {"Authorization": "Bearer " + join["token"]}
    assert client.get("/internal/ptt/admission", headers=media_header).status_code == 204
    updated = ok(client.post(EVENT + f"/people/{person['actor']}/invite", headers=DISPATCH))
    second = bind_person(client, dict(updated, actor=person["actor"]))
    assert client.get("/api/bootstrap", headers=first).status_code == 401
    assert client.get("/internal/ptt/admission", headers=media_header).status_code == 403
    assert ok(client.get("/api/bootstrap", headers=second))["identity"]["actor"] == person["actor"]


def test_suspended_account_is_hidden_from_group_members_and_kept_in_management(field):
    client, _ = field
    channel = group(client, "机场演练")
    active = create_person(client, channel, "现场队员")
    staff = bind_person(client, active)
    test_account = create_person(client, channel, "验收账号")
    actors = {active["actor"], test_account["actor"]}
    directory_url = EVENT + "/directory"
    account_url = EVENT + f"/people/{test_account['actor']}/account-status"

    def roster(headers):
        directory = ok(client.get(directory_url, headers=headers))
        members = next(c["members"] for c in directory["channels"] if c["id"] == channel)
        return {person["actor"] for person in members}, directory["people"]

    assert roster(staff)[0] == actors
    ok(client.post(account_url, headers=DISPATCH, json={"suspended": True}))
    assert roster(staff)[0] == {active["actor"]}
    members, people = roster(DISPATCH)
    assert members == {active["actor"]}
    assert next(person["login_status"] for person in people if person["actor"] == test_account["actor"]) == "suspended"
    ok(client.post(account_url, headers=DISPATCH, json={"suspended": False}))
    assert roster(staff)[0] == actors


def test_private_history_and_channel_membership(field):
    client, archive = field
    group_a = group(client, "入口")
    group_b = group(client, "会合")
    a = create_person(client, group_a, "甲")
    b = create_person(client, group_a, "乙")
    outsider = create_person(client, group_b, "丙")
    a_token, b_token, outsider_token = [bind_person(client, person) for person in (a, b, outsider)]
    direct = ok(client.post(EVENT + "/direct", headers=a_token, json={"person_id": b["actor"]}))
    thread = EVENT + f"/channels/{direct['id']}"
    assert client.post(thread + "/ptt/join", headers=outsider_token).status_code == 404
    assert client.post(thread + "/ptt/join", headers=b_token).status_code == 409
    assert client.post(thread + "/video/join", headers=b_token).status_code == 404
    assert client.get(thread + "/history", headers=DISPATCH).status_code == 403
    assert ok(client.get(EVENT + "/inbox", headers=b_token))["calls"][0]["channel_id"] == direct["id"]
    for number in range(38):
        sid = "TR_" + str(number)
        assert archive.start(direct["id"], sid, a["actor"], "甲")
        archive.finish(direct["id"], sid, b"audio", 500, False)
    page = ok(client.get(thread + "/history", headers=b_token))
    older = ok(client.get(thread + "/history", headers=b_token, params={"before": page["next_before"]}))
    assert len(page["items"]) == 30 and len(older["items"]) == 8
    assert not set(v["track_sid"] for v in page["items"]) & set(v["track_sid"] for v in older["items"])
    assert client.get(thread + "/voice/TR_0/audio", headers=outsider_token).status_code == 404
    assert client.get(thread + "/voice/TR_0/audio", headers=b_token).content == b"audio"
    assert client.post(EVENT + f"/channels/{group_b}/ptt/join", headers=b_token).status_code == 404
    ok(client.post(EVENT + f"/channels/{group_b}/members", headers=DISPATCH, json={"person_id": b["actor"]}))
    assert ok(client.post(EVENT + f"/channels/{group_b}/ptt/join", headers=b_token))["room"] == "px-" + group_b


def test_channel_changes_updates_one_voice_row_and_keeps_receipts_scoped(field, monkeypatch):
    from app import field_media, ptt_agent

    client, archive = field
    channel = group(client, "机场演练")
    other_channel = group(client, "备用组")
    person = create_person(client, channel, "接机员")
    headers = bind_person(client, person)
    outsider = bind_person(client, create_person(client, other_channel, "其他人员"))
    changes = EVENT + f"/channels/{channel}/changes"
    store = client.app.state.store

    def read(cursor=None, audio=True):
        return ok(client.get(changes, headers=headers, params={"cursor": cursor or "", "audio": str(audio)}))

    assert client.get(changes, headers=outsider).status_code == 404
    first = read()
    assert first["reset"] and first["items"] == []
    assert read(first["cursor"]) == {"cursor": first["cursor"], "changed": False}
    assert archive.start(channel, "TR_current", person["actor"], "接机员")
    assert archive.start(channel, "TR_silent", person["actor"], "接机员")
    archive.finish(channel, "TR_silent", None, 0, False, silent=True)
    assert not read(first["cursor"])["changed"]

    def no_projection(*_args):
        pytest.fail("An audio-only change should not recalculate dispatch state")

    with monkeypatch.context() as scoped:
        scoped.setattr(field_media, "channel_updates", no_projection)
        archive.finish(channel, "TR_current", b"test", 500, False)
        pending = read(first["cursor"])
        assert [(v["track_sid"], v["status"]) for v in pending["items"]] == [("TR_current", "pending")]
        archive.complete(channel, "TR_current", "ready", "香港三号门关闭", "synthetic-review")
        reviewed = read(pending["cursor"])
        assert [(v["track_sid"], v["text"]) for v in reviewed["items"]] == [("TR_current", "香港三号门关闭")]
        assert read(reviewed["cursor"]) == {"cursor": reviewed["cursor"], "changed": False}

    ptt_agent.record_incident(store.path, client.app.state.field.definitions, channel, {
        "actor": person["actor"], "track_sid": "TR_current", "text": "香港三号门关闭",
    })
    incident = read(reviewed["cursor"])
    item = incident["incident_tracks"]["TR_current"]
    assert not item["acknowledged"] and "items" not in incident
    ack = "/api/events/golf-demo/qrf/reports/" + item["id"] + "/acknowledge"
    ok(client.post(ack, headers=DISPATCH))
    received = read(incident["cursor"])
    assert received["incident_tracks"]["TR_current"]["acknowledged"]
    assert not read(received["cursor"])["changed"]
    background = read(audio=False)
    assert not read(background["cursor"], audio=False)["changed"]

    with archive.connect(write=True) as conn:
        conn.execute("DELETE FROM voice_transmissions WHERE track_sid=?", ("TR_current",))
    removed = read(received["cursor"])
    assert removed["items"] == [] and removed["removed"] == ["TR_current"]
    with archive.connect(write=True) as conn:
        conn.execute("UPDATE voice_feed_entries SET changed_at=? WHERE scope=?", ("1970-01-01T00:00:00+00:00", channel))
    archive.prune(channel)
    assert read(received["cursor"])["voice_reset"]
    assert not read(removed["cursor"])["changed"]


def test_personal_dispatch_delivery_decline_and_photo_retries(field):
    client, _ = field
    channel = group(client, "现场")
    receiver = create_person(client, channel, "现场员")
    other = create_person(client, channel, "另一个人")
    coordinator = ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": "调度甲", "callsign": "调度甲", "role": "dispatcher", "channel_ids": [channel],
    }))
    staff, other_token, dispatch = [bind_person(client, person) for person in (receiver, other, coordinator)]
    body = {"person_id": receiver["actor"], "request_id": str(uuid4()), "title": "西门医疗协助",
            "location": "西门入口", "contact": "调度", "due": "14:30"}
    created = ok(client.post(EVENT + "/dispatch", headers=dispatch, json=body))
    assert ok(client.post(EVENT + "/dispatch", headers=dispatch, json=body))["id"] == created["id"]
    assert client.post(EVENT + "/dispatch", headers=dispatch, json=dict(body, title="另一个任务")).status_code == 409
    task_id = created["target_ref"]["id"]
    tasks = ok(client.get(EVENT + "/inbox", headers=staff))["tasks"]
    assert len(tasks) == 1 and tasks[0]["owner"] == receiver["actor"]
    assert not ok(client.get(EVENT + "/inbox", headers=other_token))["tasks"]
    receipt = ok(client.post(EVENT + "/received", headers=staff, json={"task_id": task_id}))
    assert ok(client.post(EVENT + "/received", headers=staff, json={"task_id": task_id})) == receipt
    work = "/api/events/golf-demo/"
    task = next(task for task in ok(client.get(work + "workspace", headers=dispatch))["tasks"]
                if task["ref"]["id"] == task_id)
    assert task["field_response"]["received_at"] == receipt["received_at"]
    body = {"action_type": "decline_response", "target_ref": task["ref"], "expected_version": task["version"],
            "idempotency_key": str(uuid4())}
    assert client.post(work + "actions", headers=dispatch, json=body).status_code == 409
    declined = ok(client.post(work + "actions", headers=staff, json=body))
    assert declined["speaker"] == "现场员"
    identity = client.app.state.field.authenticate(staff["Authorization"])
    old_fingerprint = hashlib.sha256(dump({
        "identity": identity, "request": ActionInput.model_validate(body).model_dump(exclude_none=True),
    }).encode()).hexdigest()
    with client.app.state.store.connect(write=True) as conn:
        conn.execute("UPDATE receipts SET fingerprint=? WHERE id=?", (old_fingerprint, declined["id"]))
    assert ok(client.post(work + "actions", headers=staff, json=body)) == declined
    assert client.post(work + "actions", headers=staff,
                       json=dict(body, expected_version=task["version"] + 1)).status_code == 409
    photo = bytes.fromhex("ffd8ffe000020001ffd9")
    payload = {"id": str(uuid4()), "jpeg": base64.b64encode(photo).decode()}
    url = EVENT + f"/tasks/{task_id}/photos"
    assert client.post(url, headers=other_token, json=payload).status_code == 404
    uploaded = ok(client.post(url, headers=staff, json=payload))
    assert ok(client.post(url, headers=staff, json=payload)) == uploaded
    assert len(ok(client.get(url, headers=dispatch))["items"]) == 1
    assert client.get(url + "/" + payload["id"], headers=dispatch).content == photo
    assert client.get(url + "/" + payload["id"], headers=other_token).status_code == 404


def test_qrf_pickup_draft_needs_confirmation_and_dispatch_ack(field, monkeypatch):
    from app import qrf

    client, _ = field
    channel = group(client, "QRF 接机")
    operator = create_person(client, channel, "接机一组")
    staff = bind_person(client, operator)
    outsider = bind_person(client, create_person(client, channel, "接机二组"))
    dispatch = ok(client.post(EVENT + "/dispatch", headers=DISPATCH, json={
        "request_id": str(uuid4()), "person_id": operator["actor"],
        "title": "接机演练", "location": "机场 3 号门",
    }))
    ref = dispatch["target_ref"]
    workspace = "/api/events/golf-demo/workspace"
    base = "/api/events/golf-demo"

    def task():
        return next(item for item in ok(client.get(workspace, headers=staff))["tasks"] if item["ref"] == ref)

    def step(label):
        current = task()
        action = next(a for a in current["allowed_actions"] if a["label"] == label)
        return ok(client.post(base + "/actions", headers=staff, json={
            "action_type": action["id"], "target_ref": ref, "expected_version": current["version"],
            "idempotency_key": str(uuid4()),
            "parameters": {"progress_index": action["progress_index"]} if "progress_index" in action else {},
        }))

    step("确认接单")
    step("确认出发")
    arrival = step("确认到场")
    assert arrival["result"]["progress_label"] == "到场"
    version = task()["version"]
    monkeypatch.setattr(qrf, "transcribe", lambda audio: "我到了，没找到客人")
    monkeypatch.setattr(qrf, "compose_report", lambda sentence: "已到集合点，尚未会合。")
    input_body = {"task": ref, "audio": "synthetic"}
    assert client.post(base + "/qrf/drafts", headers=outsider, json=input_body).status_code == 403
    draft = ok(client.post(base + "/qrf/drafts", headers=staff, json=input_body))
    assert draft["transcript"] == "我到了，没找到客人"
    assert not ok(client.get(workspace, headers=DISPATCH))["messages"]
    confirmation = base + "/qrf/drafts/" + draft["id"] + "/confirm"
    assert client.post(confirmation, headers=outsider, json={"text": draft["text"]}).status_code == 404
    reviewed = {"text": "已到 3 号门，尚未会合。"}
    report = ok(client.post(confirmation, headers=staff, json=reviewed))
    assert ok(client.post(confirmation, headers=staff, json=reviewed)) == report
    assert client.post(confirmation, headers=staff, json={"text": "其他内容"}).status_code == 409
    assert report["subject_ref"] == ref
    assert ok(client.get(workspace, headers=DISPATCH))["messages"] == [report]
    ack = base + "/qrf/reports/" + report["id"] + "/acknowledge"
    assert client.post(ack, headers=staff).status_code == 403
    receipt = ok(client.post(ack, headers=DISPATCH))
    assert ok(client.post(ack, headers=DISPATCH)) == receipt
    assert receipt["result"]["message_id"] == report["id"]
    assert receipt in ok(client.get(workspace, headers=staff))["receipts"]
    assert receipt not in ok(client.get(workspace, headers=outsider))["receipts"]
    assert task()["version"] == version
    assert task()["progress"][-1] == {"label": "交接", "status": "current"}


def test_assistance_retry_and_dispatcher_readback(field):
    client, _ = field
    channel = group(client, "会场求助")
    operator = create_person(client, channel, "现场员工")
    staff = bind_person(client, operator)
    coordinator = ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": "调度员", "callsign": "调度1", "role": "dispatcher", "channel_ids": [channel],
    }))
    dispatcher = bind_person(client, coordinator)
    ref = ok(client.post(EVENT + "/dispatch", headers=dispatcher, json={
        "request_id": str(uuid4()), "person_id": operator["actor"],
        "title": "入口引导", "location": "西门",
    }))["target_ref"]
    url = "/api/events/golf-demo/qrf/assistance"
    body = {"task": ref, "request_id": str(uuid4()), "note": "需要协助引导来宾"}
    signal = ok(client.post(url, headers=staff, json=body))
    assert ok(client.post(url, headers=staff, json=body)) == signal
    assert client.post(url, headers=staff, json=dict(body, note="其他")).status_code == 409
    assert client.post(url, headers=dispatcher, json=body).status_code == 403
    assert ok(client.get(EVENT + "/inbox", headers=dispatcher))["assistance"] == [signal]
    ack = "/api/events/golf-demo/qrf/reports/" + signal["id"] + "/acknowledge"
    receipt = ok(client.post(ack, headers=dispatcher))
    assert ok(client.post(ack, headers=dispatcher)) == receipt
    assert not ok(client.get(EVENT + "/inbox", headers=dispatcher))["assistance"]
    assert receipt in ok(client.get("/api/events/golf-demo/workspace", headers=staff))["receipts"]


def test_call_requires_recipient_acceptance_and_is_revoked_on_end(field):
    client, _ = field
    channel = group(client, "服务组")
    source = create_person(client, channel, "现场甲")
    destination = create_person(client, channel, "现场乙")
    others = create_person(client, channel, "旁观")
    sender, receiver, outsider = (bind_person(client, person) for person in (source, destination, others))
    direct = ok(client.post(EVENT + "/direct", headers=sender, json={"person_id": destination["actor"]}))["id"]
    offers = EVENT + f"/channels/{direct}/conferences"
    request = {"mode": "audio", "request_id": str(uuid4())}
    call = ok(client.post(offers, headers=sender, json=request))
    assert call == ok(client.post(offers, headers=sender, json=request))
    calls = EVENT + f"/conferences/{call['id']}"
    assert ok(client.get(EVENT + "/inbox", headers=receiver))["conferences"][0]["id"] == call["id"]
    assert client.post(calls + "/join", headers=sender).status_code == 409
    assert client.post(calls + "/decision", headers=sender, json={"action": "accept"}).status_code == 403
    assert client.get(calls, headers=outsider).status_code == 404
    assert ok(client.post(calls + "/decision", headers=receiver, json={"action": "accept"}))["state"] == "accepted"
    peer = ok(client.post(calls + "/join", headers=receiver))
    owner = ok(client.post(calls + "/join", headers=sender))
    assert owner["room"] == peer["room"] and owner["room"].startswith("px-call-")
    listener = {"Authorization": "Bearer " + peer["token"]}
    assert client.get("/internal/ptt/admission", headers=listener).status_code == 204
    assert client.post(calls + "/publish", headers=sender, json={"session_id": peer["session_id"]}).status_code == 403
    assert ok(client.post(calls + "/decision", headers=sender, json={"action": "end"}))["state"] == "ended"
    assert client.get("/internal/ptt/admission", headers=listener).status_code == 403


def test_server_channel_voice_policy_and_audit(field):
    client, _ = field
    channel = group(client, "行动语音")
    coordinator = ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": "现场调度", "callsign": "现场调度", "role": "dispatcher", "channel_ids": [channel],
    }))
    dispatcher = bind_person(client, coordinator)
    staff = bind_person(client, create_person(client, channel, "现场一线"))
    root = EVENT + f"/channels/{channel}"
    url = root + "/voice/settings"
    joined = ok(client.post(root + "/ptt/join", headers=staff))
    media = {"Authorization": "Bearer " + joined["token"]}
    assert client.get("/internal/ptt/admission", headers=media).status_code == 204
    closed = {"mode": "operation", "enabled": False, "request_id": str(uuid4())}
    assert client.post(url, headers=staff, json=closed).status_code == 403
    assert ok(client.post(url, headers=dispatcher, json=closed)) == {"mode": "operation", "enabled": False}
    assert client.post(root + "/ptt/join", headers=staff).status_code == 409
    assert client.get("/internal/ptt/admission", headers=media).status_code == 403
    assert ok(client.get(EVENT + "/directory", headers=staff))["channels"][0]["voice"] == {"mode": "operation", "enabled": False}
    assert client.get(root + "/history", headers=staff).status_code == 200
    state = {"mode": "always", "enabled": True, "request_id": str(uuid4())}
    ok(client.post(url, headers=dispatcher, json=state))
    assert client.get("/internal/ptt/admission", headers=media).status_code == 403
    assert ok(client.post(root + "/ptt/join", headers=staff))["session_id"] != joined["session_id"]
    ok(client.post(url, headers=dispatcher, json=closed))
    assert ok(client.get(EVENT + "/directory", headers=staff))["channels"][0]["voice"]["enabled"]
    assert client.get(root + "/voice/logs", headers=staff).status_code == 403
    logs = ok(client.get(root + "/voice/logs", headers=dispatcher))["items"]
    assert [item["action"] for item in logs] == ["join_authorized", "voice_policy", "voice_policy", "join_authorized"]
    assert [item["speaker"] for item in logs] == ["现场一线", "现场调度", "现场调度", "现场一线"]


def test_team_inheritance_individual_override_and_revoked_channel_token(field):
    client, _ = field
    source, target, hidden = [group(client, n) for n in ("接机组", "香港调度", "隔离频道")]
    admin = bind_person(client, ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": "中控", "callsign": "CTRL", "role": "dispatcher", "channel_ids": [source, target],
    })))
    person = create_person(client, source, "接机员")
    operator = bind_person(client, person)
    other = create_person(client, hidden, "酒店组成员")
    view = EVENT + f"/channels/{target}"
    assert client.get(view + "/history", headers=operator).status_code == 404
    grant = {"source_id": source, "enabled": True}
    assert client.post(view + "/access/team", headers=operator, json=grant).status_code == 403
    assert client.post(view + "/access/team", headers=admin, json={"source_id": hidden, "enabled": True}).status_code == 404
    assert client.post(view + "/access/team", headers=admin, json=grant).status_code == 200
    assert client.get(view + "/history", headers=operator).status_code == 200
    new = bind_person(client, create_person(client, source, "新接机员"))
    assert {c["id"] for c in ok(client.get(EVENT + "/directory", headers=new))["channels"]} == {source, target}
    token = ok(client.post(view + "/ptt/join", headers=operator))["token"]
    body = {"person_id": person["actor"], "access": "deny"}
    ok(client.post(view + "/access/individual", headers=admin, json=body))
    assert client.get(view + "/history", headers=operator).status_code == 404
    assert client.get("/internal/ptt/admission", headers={"Authorization": "Bearer " + token}).status_code == 403
    ok(client.post(view + "/access/individual", headers=admin, json={**body, "access": "inherit"}))
    assert client.get(view + "/history", headers=operator).status_code == 200
    assert client.get("/internal/ptt/admission", headers={"Authorization": "Bearer " + token}).status_code == 403
    ok(client.post(view + "/access/individual", headers=admin, json={"person_id": other["actor"], "access": "allow"}))
    assert other["actor"] in ok(client.get(view + "/access", headers=admin))["direct"]
    assert ok(client.get(view + "/access", headers=admin))["sources"] == [source]
    assert client.get(EVENT + f"/channels/{hidden}/access", headers=admin).status_code == 404


def test_private_call_invitation_is_atomic_and_restricted_to_shared_group(field):
    client, _ = field
    channel = group(client, "共同频道")
    hidden = group(client, "另一小组")
    source = create_person(client, channel, "甲")
    dest = create_person(client, channel, "乙")
    outside = create_person(client, hidden, "丙")
    sender, recipient = [bind_person(client, person) for person in (source, dest)]
    assert ok(client.get(EVENT + "/directory", headers=sender))["private_calls_enabled"]
    post = EVENT + "/private-calls"
    body = {"person_id": dest["actor"], "mode": "audio", "request_id": str(uuid4())}
    assert client.post(post, headers=sender, json={**body, "person_id": outside["actor"]}).status_code == 404
    call = ok(client.post(post, headers=sender, json=body))
    assert ok(client.post(post, headers=sender, json=body)) == call
    assert ok(client.get(EVENT + "/inbox", headers=recipient))["calls"] == []
    incoming = ok(client.get(post + "/incoming", headers=recipient))["call"]
    assert incoming["name"] == "甲" and incoming["id"] == call["id"]
    calls = EVENT + f"/conferences/{call['id']}"
    assert client.post(calls + "/join", headers=sender).status_code == 409
    assert ok(client.post(calls + "/decision", headers=recipient, json={"action": "decline"}))["state"] == "declined"
    assert ok(client.get(post + "/incoming", headers=recipient))["call"] is None
    assert ok(client.get(calls, headers=sender))["state"] == "declined"


def test_nearby_follows_channel_membership_and_rotates_on_removal(field):
    import hashlib
    import json
    from datetime import datetime, timezone

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    client, _ = field
    alpha = group(client, "接驳频道")
    beta = group(client, "签到频道")
    a = create_person(client, alpha, "甲")
    b = create_person(client, alpha, "乙")
    c = create_person(client, beta, "丙")
    ta, tb, tc = [bind_person(client, person) for person in (a, b, c)]
    manager = ok(client.post(EVENT + "/people", headers=DISPATCH, json={
        "name": "调度", "callsign": "调度", "role": "dispatcher", "channel_ids": [alpha, beta],
    }))
    td = bind_person(client, manager)
    route = EVENT + "/nearby"

    def public():
        key = ec.generate_private_key(ec.SECP256R1()).public_key()
        return base64.b64encode(key.public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )).decode()

    def provision(token, key):
        response = client.post(route, headers=token, json={"public_key": key})
        assert response.headers["Cache-Control"] == "private, no-store"
        data = ok(response)
        return data["authority"], {entry["channel"]: entry for entry in data["channels"]}

    ka, kb, kc = public(), public(), public()
    assert client.post(route, headers=DISPATCH, json={"public_key": ka}).status_code == 403
    assert client.post(route, headers=ta, json={"public_key": "invalid"}).status_code == 422
    authority, a_leases = provision(ta, ka)
    _, b_leases = provision(tb, kb)
    _, c_leases = provision(tc, kc)
    assert set(a_leases) == set(b_leases) == {alpha}
    assert a_leases[alpha]["voice_enabled"] is True
    assert set(c_leases) == {beta}
    original = a_leases[alpha]["secret"]
    assert b_leases[alpha]["secret"] == original != c_leases[beta]["secret"]
    raw, signature = map(base64.b64decode, a_leases[alpha]["certificate"].split("."))
    authority_key = serialization.load_der_public_key(base64.b64decode(authority))
    authority_key.verify(signature, raw, ec.ECDSA(hashes.SHA256()))
    claim = json.loads(raw)
    assert claim["actor"] == a["actor"] and claim["name"] == "甲" and claim["key"] == ka
    assert claim["channel"] == alpha
    assert claim["generation"] == hashlib.sha256(original.encode()).hexdigest()
    assert 0 < claim["expires"] - datetime.now(timezone.utc).timestamp() <= 12 * 60 * 60

    access = EVENT + f"/channels/{alpha}/access/individual"
    ok(client.post(access, headers=td, json={"person_id": c["actor"], "access": "allow"}))
    assert provision(tc, kc)[1][alpha]["secret"] == original
    assert provision(ta, ka)[1][alpha]["secret"] == original
    ok(client.post(access, headers=td, json={"person_id": c["actor"], "access": "deny"}))
    assert set(provision(tc, kc)[1]) == {beta}
    rotated = provision(ta, ka)[1][alpha]["secret"]
    assert rotated != original and provision(tb, kb)[1][alpha]["secret"] == rotated

    ok(client.post(EVENT + f"/people/{b['actor']}/revoke", headers=td))
    assert client.post(route, headers=tb, json={"public_key": kb}).status_code == 401
    assert provision(ta, ka)[1][alpha]["secret"] != rotated


def test_nearby_agreement_directory_is_channel_and_device_scoped(field):
    import json

    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    client, _ = field
    alpha = group(client, "附近一组")
    beta = group(client, "附近二组")
    a = create_person(client, alpha, "甲")
    b = create_person(client, alpha, "乙")
    c = create_person(client, beta, "丙")
    ta, tb, tc = [bind_person(client, person) for person in (a, b, c)]
    route = EVENT + "/nearby"

    def key(curve):
        public = ec.generate_private_key(curve).public_key()
        return base64.b64encode(public.public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def provision(token, payload):
        response = ok(client.post(route, headers=token, json=payload))
        credential, = response["channels"]
        return response["authority"], credential

    sign = key(ec.SECP256R1())
    agreement = key(ec.SECP256R1())
    payload = {"public_key": sign, "agreement_key": agreement}
    assert client.post(route, headers=ta, json={**payload, "agreement_key": sign}).status_code == 422
    assert client.post(route, headers=ta, json={**payload, "agreement_key": key(ec.SECP384R1())}).status_code == 422

    _, first = provision(ta, payload)
    assert first["recipients"] == []
    _, outsider = provision(tc, {"public_key": key(ec.SECP256R1()), "agreement_key": key(ec.SECP256R1())})
    assert outsider["channel"] == beta and outsider["recipients"] == []

    bkey = key(ec.SECP256R1())
    bsign = key(ec.SECP256R1())
    _, receiver = provision(tb, {"public_key": bsign, "agreement_key": bkey})
    assert receiver["recipients"] == [{"actor": a["actor"], "name": "甲", "key": agreement}]
    issuer, sender = provision(ta, payload)
    assert sender["channel"] == alpha and sender["recipients"] == [{"actor": b["actor"], "name": "乙", "key": bkey}]
    raw, signature = map(base64.b64decode, sender["certificate"].split("."))
    serialization.load_der_public_key(base64.b64decode(issuer)).verify(signature, raw, ec.ECDSA(hashes.SHA256()))
    assert json.loads(raw)["agreement_key"] == agreement
    _, legacy = provision(ta, {"public_key": sign})
    assert "recipients" not in legacy
    assert "agreement_key" not in json.loads(base64.b64decode(legacy["certificate"].split(".")[0]))

    ok(client.post("/api/field/logout", headers=tb))
    assert client.post(route, headers=tb, json={"public_key": bsign, "agreement_key": bkey}).status_code == 401
    _, after = provision(ta, payload)
    assert after["recipients"] == [] and after["secret"] != sender["secret"]
