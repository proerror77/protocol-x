"""Build and revalidate permissioned pickup proposals from airport observations."""

from datetime import datetime, timedelta, timezone
import hashlib
import json

from fastapi import HTTPException

from .bindings import candidates, resolve
from .store import dump


ENGINE = "flight_dispatch"
PICKUP_BUFFER = timedelta(minutes=30)
OBSERVATION_MAX_AGE = timedelta(minutes=10)


def available_options(conn, event_id, task, definition, observation, now):
    if not observation or not observation["delay_minutes"]:
        return []
    source = datetime.fromisoformat(observation["source_time"])
    if now - source > OBSERVATION_MAX_AGE or not observation.get("estimated_at"):
        return []
    binding = definition["binding"]
    time_key = binding.get("pickup_time")
    if not time_key:
        return []
    data = json.loads(task["data"])
    if all(item["status"] in {"done", "confirmed", "completed"} for item in data[binding["progress"]]):
        return []
    rules = definition.get("arrival_rules") if data.get("arrival_managed") else None
    if rules and data[binding["progress"]][0]["status"] == "done":
        return []
    buffer = rules["taxi_minutes"] + rules["exit_min_minutes"] if rules else 30
    eta = datetime.fromisoformat(observation["estimated_at"])
    pickup = eta + (timedelta(minutes=buffer) if rules else PICKUP_BUFFER)
    if pickup <= now:
        return []
    options = []
    if not data.get(time_key) or pickup > datetime.fromisoformat(data[time_key]):
        options.append(("reschedule_pickup", {"pickup_at": pickup.isoformat()},
                        f"调整接机时间至 {pickup:%H:%M}",
                        f"预计到达时间后留出 {buffer} 分钟，供下机、入境和取行李。"))
    ref = {"ontology_id": task["ontology_id"], "object_type": binding["resource_type"],
           "id": data[binding["resource"]]}
    vehicle = json.loads(resolve(conn, event_id, ref)["data"])
    if vehicle[binding["resource_ready"]] != binding["ready_value"]:
        replacement = next(iter(candidates(conn, event_id, task, definition)), None)
        if replacement:
            options.append(("replace_resource", {"replacement_ref": replacement["ref"]},
                            f"换用{replacement['label']}",
                            "现车在活动中标记为不可用。候选车辆可用、容量充足且未被其他任务占用。"))
    return options


def reconcile(conn, link, observation, definition):
    ref = json.loads(link["task_ref"])
    event_id = link["event_id"]
    task = resolve(conn, event_id, ref)
    evidence = {"airport": "HKG", "day": link["day"], "flight": link["flight"]}
    now = datetime.now(timezone.utc)
    options = available_options(conn, event_id, task, definition, observation, now)
    desired = {(action, dump(params)): (action, params, title, reason) for action, params, title, reason in options}
    engine = "data::jsonb->>'engine'" if conn.postgres else "json_extract(data,'$.engine')"
    status = "data::jsonb->>'status'" if conn.postgres else "json_extract(data,'$.status')"
    target = "data::jsonb #>> '{target_ref,id}'" if conn.postgres else "json_extract(data,'$.target_ref.id')"
    existing = conn.execute(f"""
        SELECT id, data FROM proposals
        WHERE event_id=? AND ({engine})=? AND ({status})='proposed' AND ({target})=?
    """, (event_id, ENGINE, ref["id"])).fetchall()
    for row in existing:
        proposal = json.loads(row["data"])
        option = (proposal["action_type"], dump(proposal["parameters"]))
        if (option in desired and proposal["expected_version"] == task["version"]
                and proposal["flight_ref"] == evidence and proposal["dispatcher_id"] == link["dispatcher_id"]
                and proposal["estimated_at"] == observation.get("estimated_at")):
            desired.pop(option)
        else:
            proposal["status"] = "expired"
            conn.execute("UPDATE proposals SET data=? WHERE event_id=? AND id=?", (dump(proposal), event_id, row["id"]))
    for action, params, title, reason in desired.values():
        material = dump([event_id, ref, evidence, observation["source_time"], task["version"], action, params])
        key = hashlib.sha256(material.encode()).hexdigest()
        proposal = {
            "id": f"flight-proposal-{key[:32]}", "engine": ENGINE, "message_id": f"airport-{key}",
            "title": title, "reason": reason, "status": "proposed", "target_ref": ref,
            "expected_version": task["version"], "action_type": action, "parameters": params,
            "created_at": now.isoformat(), "flight_ref": evidence, "dispatcher_id": link["dispatcher_id"],
            "source_time": observation["source_time"], "estimated_at": observation["estimated_at"],
            "evidence_quote": (f"香港机场 {link['flight']}｜计划 {observation['scheduled']}，"
                               f"预计 {observation['estimated']}，晚于计划 {observation['delay_minutes']} 分钟。"),
        }
        conn.execute("INSERT INTO proposals(id,event_id,message_id,data) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                     (proposal["id"], event_id, proposal["message_id"], dump(proposal)))


def check_confirmation(conn, event_id, row, definition, proposal, user):
    if proposal["dispatcher_id"] != user["actor"] or user["role"] != "dispatcher":
        raise HTTPException(403, "只有跟进该接机任务的调度员可确认")
    link = conn.execute("SELECT * FROM pickup_flight_links WHERE event_id=? AND task_ref=?",
                        (event_id, dump(proposal["target_ref"]))).fetchone()
    if not link or not link["observation"] or link["dispatcher_id"] != user["actor"]:
        raise HTTPException(409, "航班关联已变化，请刷新建议")
    if (link["flight"], link["day"]) != (proposal["flight_ref"]["flight"], proposal["flight_ref"]["day"]):
        raise HTTPException(409, "航班关联已变化，请刷新建议")
    observation = json.loads(link["observation"])
    expected = (proposal["action_type"], proposal["parameters"])
    options = available_options(conn, event_id, row, definition, observation, datetime.now(timezone.utc))
    if observation.get("estimated_at") != proposal["estimated_at"] or not any(
        (action, params) == expected for action, params, _, _ in options
    ):
        raise HTTPException(409, "机场观测或资源可用性已变化，请刷新建议")
