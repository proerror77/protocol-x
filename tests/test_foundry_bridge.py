"""Tests for Foundry Edge Bridge (Strangler Fig adapter to MDN Foundry Gateway)."""
import uuid

from fastapi.testclient import TestClient

from app.arrival_desk import StageInput
from app.foundry_bridge import FoundryBridge, FoundryBridgeSettings
from app.main import create_app
from app.models import ActionInput, ActionParameters, ObjectRef


def _make_client(tmp_path, monkeypatch, *, foundry_url=None, mode="shadow", secret=None):
    monkeypatch.delenv("OPS_FOUNDRY_GATEWAY_URL", raising=False)
    monkeypatch.delenv("OPS_FOUNDRY_MODE", raising=False)
    monkeypatch.delenv("OPS_FOUNDRY_BRIDGE_SECRET", raising=False)
    if foundry_url:
        monkeypatch.setenv("OPS_FOUNDRY_GATEWAY_URL", foundry_url)
    if mode:
        monkeypatch.setenv("OPS_FOUNDRY_MODE", mode)
    if secret:
        monkeypatch.setenv("OPS_FOUNDRY_BRIDGE_SECRET", secret)
    app = create_app(tmp_path / "ops.db")
    return app, TestClient(app)


def test_foundry_status_disabled_by_default(tmp_path, monkeypatch):
    _, client = _make_client(tmp_path, monkeypatch)
    res = client.get("/api/foundry/status")
    assert res.status_code == 200
    assert res.json() == {
        "enabled": False,
        "mode": None,
        "ontology_id": None,
        "gateway_configured": False,
    }


def test_task_action_mapping_golf_and_conference():
    golf_ack = ActionInput(
        action_type="acknowledge",
        target_ref=ObjectRef(ontology_id="golf.v1", object_type="TransferLeg", id="transfer-001"),
        expected_version=1,
        idempotency_key=str(uuid.uuid4()),
    )
    mapped = FoundryBridge.map_task_action("golf-demo", golf_ack)
    assert mapped == {
        "action": "AcceptTransfer",
        "parameters": {"transfer": "transfer-001", "expectedRevision": 1},
    }

    golf_replace = ActionInput(
        action_type="replace_resource",
        target_ref=ObjectRef(ontology_id="golf.v1", object_type="TransferLeg", id="transfer-001"),
        expected_version=2,
        idempotency_key=str(uuid.uuid4()),
        parameters=ActionParameters(
            replacement_ref=ObjectRef(ontology_id="golf.v1", object_type="Vehicle", id="car-03")
        ),
    )
    mapped_replace = FoundryBridge.map_task_action("golf-demo", golf_replace)
    assert mapped_replace["action"] == "AssignVehicle"
    assert mapped_replace["parameters"]["vehicle"] == "car-03"
    assert len(mapped_replace["parameters"]["allocations"]) == 1
    assert mapped_replace["parameters"]["allocations"][0]["resourceId"] == "car-03"

    conf_ack = ActionInput(
        action_type="acknowledge",
        target_ref=ObjectRef(ontology_id="conference.v1", object_type="Session", id="session-001"),
        expected_version=1,
        idempotency_key=str(uuid.uuid4()),
    )
    assert FoundryBridge.map_task_action("conference-demo", conf_ack) == {
        "action": "AcceptSessionStewardship",
        "parameters": {"session": "session-001", "expectedRevision": 1},
    }


def test_arrival_stage_and_observation_mapping():
    stage = StageInput(expected_version=3, stage="hotel_handoff")
    assert FoundryBridge.map_arrival_stage("plan-1", stage) == {
        "action": "AdvanceReceptionStage",
        "parameters": {"plan": "plan-1", "expectedRevision": 3, "targetStage": "hotelHandoff"},
    }

    flight_obs = FoundryBridge.map_flight_observation(
        "golf-hkg-arrivals-demo",
        {
            "id": "HKG:2026-11-28:CX881",
            "flight_number": "CX881",
            "service_date": "2026-11-28",
            "status": "at_gate",
            "source_time": "2026-11-28T09:00:00Z",
        },
    )
    assert flight_obs["action"] == "RecordFlightObservation"
    assert flight_obs["parameters"]["flightStatus"] == "atGate"

    tak_obs = FoundryBridge.map_tak_observation(
        "golf-demo",
        {
            "id": "takobs-001",
            "cot_uid": "ANDROID-01",
            "cot_type": "a-f-G-U-C",
            "category": "ops_ptt",
            "lat": 22.308,
            "lon": 113.918,
            "observed_at": "2026-11-28T09:05:00Z",
        },
    )
    assert tak_obs["action"] == "RecordTakObservation"
    assert tak_obs["parameters"]["category"] == "opsPtt"


def test_shadow_mode_mirrors_action_without_breaking_local_commit(tmp_path, monkeypatch):
    app, client = _make_client(
        tmp_path,
        monkeypatch,
        foundry_url="http://127.0.0.1:18787",
        mode="shadow",
        secret="bridge-test-secret",
    )
    calls = []

    def fake_caller(url, payload, headers, timeout_s):
        calls.append((url, payload, headers))
        return 200, {"ok": True}

    app.state.foundry_bridge = FoundryBridge(app.state.foundry_bridge.settings, http_caller=fake_caller)

    res = client.post(
        "/api/events/golf-demo/actions",
        headers={"X-Demo-Role": "staff"},
        json={
            "action_type": "acknowledge",
            "target_ref": {"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": "transfer-001"},
            "expected_version": 1,
            "idempotency_key": str(uuid.uuid4()),
        },
    )
    assert res.status_code == 200
    assert len(calls) == 1
    assert calls[0][0].endswith("/api/v1/ontologies/TocOperations/actions/AcceptTransfer/apply")
    assert calls[0][1]["parameters"] == {"transfer": "transfer-001", "expectedRevision": 1}


def test_primary_mode_propagates_foundry_409_before_local_commit(tmp_path, monkeypatch):
    app, client = _make_client(
        tmp_path,
        monkeypatch,
        foundry_url="http://127.0.0.1:18787",
        mode="primary",
        secret="bridge-test-secret",
    )

    def conflict_caller(url, payload, headers, timeout_s):
        return 409, {"detail": "Foundry revision conflict"}

    app.state.foundry_bridge = FoundryBridge(app.state.foundry_bridge.settings, http_caller=conflict_caller)

    res = client.post(
        "/api/events/golf-demo/actions",
        headers={"X-Demo-Role": "staff"},
        json={
            "action_type": "acknowledge",
            "target_ref": {"ontology_id": "golf.v1", "object_type": "TransferLeg", "id": "transfer-001"},
            "expected_version": 1,
            "idempotency_key": str(uuid.uuid4()),
        },
    )
    assert res.status_code == 409
    assert "Foundry revision conflict" in res.json()["detail"]


def test_foundry_outbox_effect_callbacks_require_secret(tmp_path, monkeypatch):
    _, client = _make_client(
        tmp_path,
        monkeypatch,
        foundry_url="http://127.0.0.1:18787",
        mode="shadow",
        secret="bridge-test-secret",
    )
    payload = {"eventId": "golf-demo", "objectType": "TransferLeg", "objectId": "transfer-001", "action": "AcceptTransfer"}

    # Missing token -> 401
    assert client.post("/internal/foundry-effects/channel-feed", json=payload).status_code == 401
    # Wrong token -> 403
    assert client.post(
        "/internal/foundry-effects/channel-feed",
        headers={"Authorization": "Bearer wrong-secret"},
        json=payload,
    ).status_code == 403
    # Valid token -> 200
    ok = client.post(
        "/internal/foundry-effects/channel-feed",
        headers={"Authorization": "Bearer bridge-test-secret"},
        json=payload,
    )
    assert ok.status_code == 200
    assert ok.json()["delivered"] is True

    tak_ok = client.post(
        "/internal/foundry-effects/tak-cot",
        headers={"Authorization": "Bearer bridge-test-secret"},
        json={"eventId": "golf-demo", "planId": "plan-1", "stage": "boarded"},
    )
    assert tak_ok.status_code == 200
    assert tak_ok.json()["stage"] == "boarded"

    rns_ok = client.post(
        "/internal/foundry-effects/rns-envelope",
        headers={"Authorization": "Bearer bridge-test-secret"},
        json={"eventId": "golf-demo", "kind": "task", "transferId": "transfer-001", "vehicleId": "car-03"},
    )
    assert rns_ok.status_code == 200
    assert rns_ok.json()["vehicle_id"] == "car-03"
