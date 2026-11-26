"""Shared transactional task action execution for HTTP and RNS envelope ingress."""
import hashlib
import json

from fastapi import HTTPException

from .bindings import allowed_actions, can_read_task, candidates, current_progress, event_and_definition, project_task, resolve
from .flight_dispatch import ENGINE, check_confirmation
from .models import ActionInput
from .ptt_agent import notify_vehicle_change
from .store import dump


def execute_task_action(
    conn,
    event_id: str,
    definitions: dict,
    body: ActionInput,
    user: dict,
    *,
    now_fn,
    new_id_fn,
    enrich_conflict: bool = False,
) -> tuple[dict, bool]:
    """Execute or idempotently replay a task ActionInput inside an active write transaction.

    Returns `(receipt, deduplicated)`.
    """
    if body.action_type in {"replace_resource", "reschedule_pickup"} and user["role"] != "dispatcher":
        raise HTTPException(403, "只有调度员可更改接机安排")
    action_request = body.model_dump(exclude_none=True)
    owner = {"actor": user["actor"], "role": user["role"]}
    fingerprint = hashlib.sha256(dump({"request": action_request, "identity": owner}).encode()).hexdigest()

    _, definition = event_and_definition(conn, event_id, definitions)

    existing = conn.execute(
        "SELECT * FROM receipts WHERE event_id=? AND idempotency_key=?",
        (event_id, body.idempotency_key),
    ).fetchone()
    if existing:
        legacy = hashlib.sha256(dump({"request": action_request, "identity": user}).encode()).hexdigest()
        if existing["fingerprint"] not in (fingerprint, legacy):
            raise HTTPException(409, "幂等键已用于不同身份或不同请求")
        return json.loads(existing["data"]), True

    ref = body.target_ref.model_dump()
    row = resolve(conn, event_id, ref)
    if row["ontology_id"] != definition["ontology_id"] or row["object_type"] != definition["binding"]["task_type"]:
        raise HTTPException(422, "目标不支持 WorkItem 能力")
    if not can_read_task(row, definition, user):
        raise HTTPException(403, "只能操作本人负责的任务")

    def conflict_detail(detail: str):
        if not enrich_conflict:
            raise HTTPException(409, detail)
        projected = project_task(conn, event_id, row, definition, user)
        raise HTTPException(409, {
            "message": detail,
            "current_version": row["version"],
            "current_status": projected["status"],
            "current_progress": projected["progress"],
            "allowed_actions": projected["allowed_actions"],
        })

    if row["version"] != body.expected_version:
        conflict_detail("对象版本已变化，请刷新任务后重新确认")
    if body.action_type not in {a["id"] for a in allowed_actions(row, definition, user)}:
        conflict_detail("当前状态不允许此行动")

    proposal = None
    if body.proposal_id:
        stored_proposal = conn.execute(
            "SELECT data FROM proposals WHERE event_id=? AND id=?", (event_id, body.proposal_id)
        ).fetchone()
        if stored_proposal is None:
            raise HTTPException(404, "建议不属于当前活动或不存在")
        proposal = json.loads(stored_proposal["data"])
        params = body.parameters.model_dump(exclude_none=True)
        if (
            proposal["status"] != "proposed"
            or proposal["target_ref"] != ref
            or proposal["action_type"] != body.action_type
            or proposal["parameters"] != params
            or proposal["expected_version"] != body.expected_version
        ):
            conflict_detail("执行请求与待确认建议不一致或建议已经过期")
        if proposal["engine"] == ENGINE:
            check_confirmation(conn, event_id, row, definition, proposal, user)

    data = json.loads(row["data"])
    binding = definition["binding"]
    progress_result = {}
    if body.action_type != "confirm_progress" and body.parameters.progress_index is not None:
        raise HTTPException(422, "该行动不接受现场节点参数")
    if body.action_type != "reschedule_pickup" and body.parameters.pickup_at is not None:
        raise HTTPException(422, "该行动不接受接机时间")
    if body.action_type == "acknowledge":
        if body.parameters.replacement_ref is not None:
            raise HTTPException(422, "接单行动不接受资源替换参数")
        data[binding["status"]] = binding["acknowledged_status"]
        domain_action = binding["domain_acknowledge_action"]
        note = "仅已确认接单；不代表登车、交接、入住或活动完成。"
    elif body.action_type == "decline_response":
        if body.parameters.replacement_ref is not None:
            raise HTTPException(422, "不能在拒绝时更改资源")
        data[binding["status"]] = "declined"
        domain_action = "DeclineResponse"
        note = "工作人员已标记无法响应。"
    elif body.action_type == "replace_resource":
        if body.parameters.replacement_ref is None:
            raise HTTPException(422, "资源替换必须指定 replacement_ref")
        replacement = body.parameters.replacement_ref.model_dump()
        resolve(conn, event_id, replacement)
        if replacement not in [c["ref"] for c in candidates(conn, event_id, row, definition)]:
            conflict_detail("候选资源不可用、已被预留、容量不足或类型不匹配")
        data[binding["resource"]] = replacement["id"]
        domain_action = binding["domain_replace_action"]
        note = "资源指派已写入；实际人员转移、交接与服务进度保持原记录。"
    elif body.action_type == "reschedule_pickup":
        if not proposal or proposal["engine"] != ENGINE or body.parameters.replacement_ref is not None:
            raise HTTPException(422, "调整接机时间需要确认对应的航班建议")
        data[binding["pickup_time"]] = body.parameters.pickup_at
        domain_action = binding["domain_reschedule_action"]
        note = "接机时间已写入任务，司机、车辆和交接进度保持原记录。"
    else:
        if body.parameters.replacement_ref is not None or body.parameters.progress_index is None:
            raise HTTPException(422, "确认现场节点必须且只能指定 progress_index")
        progress = data[binding["progress"]]
        index = current_progress(progress)
        if index is None or body.parameters.progress_index != index:
            conflict_detail("只能确认当前现场节点，请刷新任务")
        progress[index]["status"] = "done"
        following = next((step for step in progress[index + 1:] if step["status"] == "pending"), None)
        if following is not None:
            following["status"] = "current"
        progress_result = {"progress_index": index, "progress_label": progress[index]["label"]}
        domain_action = binding["domain_confirm_progress_action"]
        note = "操作员已确认此现场节点，回执记录确认身份和系统写入时间。"

    conn.execute(
        "UPDATE objects SET data=?, version=version+1 WHERE event_id=? AND ontology_id=? AND object_type=? AND id=? AND version=?",
        (dump(data), event_id, ref["ontology_id"], ref["object_type"], ref["id"], body.expected_version),
    )
    updated = resolve(conn, event_id, ref)
    projected = project_task(conn, event_id, updated, definition, user)
    receipt = {
        "id": new_id_fn("receipt"),
        "action_type": body.action_type,
        "target_ref": ref,
        "status": "committed",
        "actor": user["actor"],
        "created_at": now_fn(),
        **({"speaker": user["name"]} if "name" in user else {}),
        "idempotency_key": body.idempotency_key,
        "proposal_id": body.proposal_id,
        "result": {
            "object_version": updated["version"],
            "domain_action": domain_action,
            "resource_label": projected["resource_label"],
            "status_label": projected["status_label"],
            "service_progress_unchanged": not progress_result,
            "note": note,
            **progress_result,
        },
    }
    if proposal:
        if proposal["engine"] == ENGINE:
            receipt["result"]["flight_ref"] = proposal["flight_ref"]
            receipt["result"]["source_time"] = proposal["source_time"]
        if body.action_type == "reschedule_pickup":
            receipt["result"]["pickup_at"] = projected["pickup_at"]
        if proposal["engine"] == "ptt_incident":
            instruction = notify_vehicle_change(conn, event_id, definition, updated, user, proposal, receipt)
            if instruction:
                receipt["result"]["instruction_id"] = instruction["id"]
        proposal["status"] = "executed"
        conn.execute(
            "UPDATE proposals SET data=? WHERE id=? AND event_id=?",
            (dump(proposal), proposal["id"], event_id),
        )
    conn.execute(
        "INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
        (receipt["id"], event_id, body.idempotency_key, fingerprint, user["actor"], dump(receipt)),
    )
    return receipt, False
