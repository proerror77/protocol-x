"""Projection and action bindings shared by independent domain schemas."""
import json
from pathlib import Path
from fastapi import HTTPException


def load_definitions(directory):
    definitions = {}
    for path in sorted(Path(directory).glob("*.json")):
        definition = json.loads(path.read_text(encoding="utf-8"))
        definitions[definition["ontology_id"]] = definition
    return definitions


def ref_of(row):
    return {name: row[name] for name in ("ontology_id", "object_type", "id")}


def resolve(conn, event_id, ref):
    row = conn.execute("SELECT * FROM objects WHERE event_id=? AND ontology_id=? AND object_type=? AND id=?", (event_id, ref["ontology_id"], ref["object_type"], ref["id"])).fetchone()
    if row is None:
        raise HTTPException(404, "对象不属于当前活动或不存在")
    return row


def event_and_definition(conn, event_id, definitions):
    row = conn.execute("SELECT * FROM events WHERE id=?", (event_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "活动不存在")
    return dict(row), definitions[row["ontology_id"]]


def task_rows(conn, event_id, definition):
    return conn.execute("SELECT * FROM objects WHERE event_id=? AND ontology_id=? AND object_type=? ORDER BY id", (event_id, definition["ontology_id"], definition["binding"]["task_type"])).fetchall()


def require_task(row, definition):
    if row["ontology_id"] != definition["ontology_id"] or row["object_type"] != definition["binding"]["task_type"]:
        raise HTTPException(422, "目标不支持 WorkItem 能力")


def can_read_task(row, definition, identity):
    data = json.loads(row["data"])
    if data.get("arrival_managed") and not data["arrival_synthetic"] and "device" not in identity:
        return False
    return identity["role"] == "dispatcher" or identity["actor"] in (
        data[definition["binding"]["owner"]], data.get("arrival_greeter"))


def current_progress(progress):
    return next((index for index, step in enumerate(progress) if step["status"] == "current"), None)


def allowed_actions(row, definition, identity):
    binding = definition["binding"]
    data = json.loads(row["data"])
    actions = []
    field_response = data.get("field_response")
    owner = data[binding["owner"]] == identity["actor"]
    if can_read_task(row, definition, identity) and (owner or (not field_response and not data.get("arrival_managed"))):
        if data[binding["status"]] == binding["initial_status"]:
            actions.append({"id": "acknowledge", "label": binding["acknowledge_label"]})
            if field_response:
                actions.append({"id": "decline_response", "label": "无法响应"})
        elif data[binding["status"]] == binding["acknowledged_status"] and not data.get("arrival_managed"):
            progress = data[binding["progress"]]
            index = current_progress(progress)
            if index is not None:
                actions.append({"id": "confirm_progress", "label": f"确认{progress[index]['label']}", "progress_index": index})
    in_transfer = data.get("arrival_managed") and (
        "boarded" in data.get("arrival_started_stages", ()) or data[binding["progress"]][1]["status"] == "done")
    if identity["role"] == "dispatcher" and not in_transfer:
        actions.append({"id": "replace_resource", "label": binding["replace_label"]})
        if binding.get("pickup_time"):
            actions.append({"id": "reschedule_pickup", "label": "调整接机时间"})
    return actions


def candidates(conn, event_id, row, definition):
    binding = definition["binding"]
    data = json.loads(row["data"])
    current = data[binding["resource"]]
    # Conservative whole-event exclusive reservation for this prototype.
    # Production must replace this with time-window and capacity reservation logic.
    reserved = {json.loads(t["data"])[binding["resource"]] for t in task_rows(conn, event_id, definition) if t["id"] != row["id"]}
    resources = conn.execute("SELECT * FROM objects WHERE event_id=? AND ontology_id=? AND object_type=? ORDER BY id", (event_id, definition["ontology_id"], binding["resource_type"])).fetchall()
    result = []
    for resource in resources:
        payload = json.loads(resource["data"])
        if resource["id"] == current or resource["id"] in reserved:
            continue
        if payload[binding["resource_ready"]] != binding["ready_value"]:
            continue
        if payload[binding["resource_capacity"]] < data[binding["required_capacity"]]:
            continue
        result.append({"ref": ref_of(resource), "label": payload[binding["resource_label"]]})
    return result


def project_task(conn, event_id, row, definition, identity):
    binding = definition["binding"]
    data = json.loads(row["data"])
    resource_ref = {"ontology_id": row["ontology_id"], "object_type": binding["resource_type"], "id": data[binding["resource"]]}
    resource = json.loads(resolve(conn, event_id, resource_ref)["data"]) if resource_ref["id"] else None
    status = data[binding["status"]]
    response = data.get("field_response")
    if response:
        delivered = conn.execute("""
            SELECT received_at FROM field_task_delivery WHERE event_id=? AND task_id=? AND person_id=?
            """, (event_id, row["id"], data[binding["owner"]])).fetchone()
        response = {**response, "received_at": delivered["received_at"] if delivered else None}
    return {
        "ref": ref_of(row), "title": data[binding["title"]], "description": data[binding["description"]],
        "status": status, "status_label": "无法响应" if status == "declined" else binding["status_labels"].get(status, status),
        **({"field_response": response} if response else {}),
        **({"arrival_dispatched_at": data["arrival_dispatched_at"]} if data.get("arrival_dispatched_at") else {}),
        "owner": data[binding["owner"]], "subject_label": data[binding["subject"]],
        "pickup_at": data.get(binding["pickup_time"]) if binding.get("pickup_time") else None,
        "resource_ref": resource_ref if resource else None,
        "resource_label": resource[binding["resource_label"]] if resource else "", "version": row["version"],
        "progress": data[binding["progress"]], "allowed_actions": allowed_actions(row, definition, identity),
        "replacement_options": candidates(conn, event_id, row, definition) if identity["role"] == "dispatcher" else []
    }
