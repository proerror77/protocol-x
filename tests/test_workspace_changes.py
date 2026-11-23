from concurrent.futures import ThreadPoolExecutor
from time import monotonic

from fastapi.testclient import TestClient

from app.main import create_app


def test_workspace_subscription_wakes_on_committed_activity_change(tmp_path, monkeypatch):
    monkeypatch.delenv("OPS_PILOT_DISPATCHER_TOKEN", raising=False)
    monkeypatch.delenv("OPS_PILOT_STAFF_TOKEN", raising=False)
    workspace = "/api/events/golf-demo/workspace"
    changes = workspace + "/changes"
    with TestClient(create_app(tmp_path / "workspace.db")) as client, ThreadPoolExecutor(1) as executor:
        observed = client.get(changes)
        assert observed.status_code == 200
        assert observed.headers["cache-control"] == "private, no-store"
        assert client.get(changes, headers={"X-Demo-Role": "unknown"}).status_code == 403
        revision = observed.json()["revision"]
        pending = executor.submit(client.get, changes, params={"after": revision})
        start = monotonic()
        report = client.post("/api/events/golf-demo/messages", json={"text": "接机安排已确认"})
        assert report.status_code == 201, report.text
        update = pending.result(timeout=3)
        assert update.status_code == 200
        assert update.json()["revision"] != revision
        assert monotonic() - start < 3
        assert report.json()["id"] in {item["id"] for item in client.get(workspace).json()["messages"]}
