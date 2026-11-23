"""Compare and release a dispatcher-approved plan for an airport pickup."""
import json
from datetime import datetime, timedelta
from typing import Literal
from uuid import uuid4

from fastapi import Depends, HTTPException
from pydantic import Field, UUID4

from .arrival_desk import Name, history, reception_window
from .bindings import can_read_task, ref_of, task_rows
from .field import digest
from .flights import AIRPORT_TZ
from .models import StrictModel
from .ptt_agent import field_recipients, message_for, write_instruction
from .store import dump

MINUTE = timedelta(minutes=1)
VALID_FOR = timedelta(minutes=3)
OPTIONS = {"wait": "原地等候", "reserve": "换备用车", "move": "改会合点"}


class Preview(StrictModel):
    alternate_point: Name


class Approval(StrictModel):
    option: Literal["wait", "reserve", "move"]
    request_id: UUID4


def now_at_airport():
    return datetime.now(AIRPORT_TZ).replace(microsecond=0)


def local_time(value):
    return datetime.fromisoformat(value).astimezone(AIRPORT_TZ)


def pickup_state(conn, desk, event, incident, user, evaluated_at):
    ref = incident["subject_ref"]
    if not ref:
        raise HTTPException(409, "请先关联接机任务")
    record, plan, row, task = desk.load(conn, event, ref["id"], user)
    definition = desk.scope(conn, event)
    if ref_of(row) != ref or not can_read_task(row, definition, user):
        raise HTTPException(404, "接机任务不可访问")
    if task["dispatch_state"] == "declined" or task["service_facts"][0]["status"] == "done":
        raise HTTPException(409, "接机人员已拒单或已完成会合，请刷新任务")
    link = conn.execute("SELECT observation FROM pickup_flight_links WHERE event_id=? AND task_ref=?",
                        (event, dump(ref))).fetchone()
    observation = json.loads(link["observation"]) if link and link["observation"] else None
    window = reception_window(plan["flight_date"], observation, definition["arrival_rules"])
    if not window or (window["valid_until"] and local_time(window["valid_until"]) <= evaluated_at):
        raise HTTPException(409, "机场暂未提供可用于推演的有效到达时间")
    if evaluated_at > local_time(window["to"]) + 45 * MINUTE:
        raise HTTPException(409, "机场推算的出厅时段已过，请先核对球员当前位置")
    crews = field_recipients(row, definition)
    for actor in crews:
        if not desk.field.event_access(conn, event, actor):
            raise HTTPException(409, "司机或接机员尚未加入此活动")
    reservations = []
    for other in task_rows(conn, event, definition):
        data = json.loads(other["data"])
        if other["id"] == row["id"] or data.get("dispatch_state") == "declined":
            continue
        if not data.get("vehicle_id") and data.get("assigned_driver") != task["assigned_driver"]:
            continue
        if all(step["status"] == "done" for step in data["service_facts"]):
            continue
        other_plan = conn.execute("SELECT data FROM arrival_plans WHERE event_id=? AND task_id=?",
                                  (event, other["id"])).fetchone() if data.get("arrival_managed") else None
        hotel = json.loads(other_plan["data"])["hotel"] if other_plan else {}
        # An approved occupancy end remains applicable when pickup details are unchanged.
        approved = data.get("pickup_scenario", {})
        pickup = data.get("pickup_at")
        start = local_time(pickup) if pickup else None
        busy_until = None
        if start:
            if (approved.get("pickup_at"), approved.get("vehicle_id"), approved.get("driver"), approved.get("transfer_minutes")) == (
                    pickup, data.get("vehicle_id"), data["assigned_driver"], hotel.get("transfer_minutes")):
                start, busy_until = local_time(approved["from"]), local_time(approved["to"])
            else:
                busy_until = start + (hotel.get("transfer_minutes", 45) + 25) * MINUTE
                start -= 10 * MINUTE
        if busy_until and busy_until <= evaluated_at:
            continue
        reservations.append({"id": other["id"], "title": data["title"], "vehicle_id": data.get("vehicle_id"),
                             "driver": data["assigned_driver"], "from": start.isoformat() if start else None,
                             "to": busy_until.isoformat() if busy_until else None})
    vehicles = desk.resources(conn, event, definition)
    original = next((vehicle for vehicle in vehicles if vehicle["id"] == task["vehicle_id"]), {})
    rehearsal_pool = original.get("rehearsal_pool")
    vehicles = [vehicle for vehicle in vehicles if vehicle.get("rehearsal_pool") == rehearsal_pool]
    source = {key: value for key, value in observation.items() if key != "source_time"}
    people = {p["actor"]: p["name"] for p in desk.people(conn, event)}
    driver_name = people.get(task["assigned_driver"], "当班司机")
    state = {"task_version": row["version"], "plan_version": record["version"], "flight": plan["flight"],
             "flight_date": plan["flight_date"], "observation": source, "task": task, "plan": plan,
             "vehicles": vehicles, "reservations": reservations, "recipients": crews, "driver_name": driver_name,
             "arrival_rules": definition["arrival_rules"]}
    return row, definition, state, observation, window


def options_for(state, window, incident, alternate, at):
    task, plan = state["task"], state["plan"]
    original = next((v for v in state["vehicles"] if v["id"] == task["vehicle_id"]), None)
    fault = incident["kind"] == "vehicle"
    exit_from, exit_to = local_time(window["from"]), local_time(window["to"])
    duration = plan["hotel"]["transfer_minutes"] + 25
    driver = task["assigned_driver"]

    def option(key, vehicle, ready_from, ready_to, walk_from=0, walk_to=0):
        earliest = max(exit_from + walk_from * MINUTE, at + ready_from * MINUTE)
        latest = max(exit_to + walk_to * MINUTE, at + ready_to * MINUTE, earliest)
        start = at + 8 * MINUTE if key == "reserve" else at
        end = latest + duration * MINUTE
        label = vehicle["name"] if vehicle else None
        vehicle_id = vehicle["id"] if vehicle else None
        blocked = None if vehicle and vehicle["available"] and vehicle["seats"] >= task["passengers"] else "车辆不可用或座位不足"
        if key == "move" and alternate.casefold() == plan["meeting_point"].casefold():
            blocked = "请选择不同且可通行的会合点"
        impacts = []
        for booking in state["reservations"]:
            shared = []
            if vehicle_id and booking["vehicle_id"] == vehicle_id:
                shared.append("车辆")
            if booking["driver"] == driver:
                shared.append("司机")
            if not shared:
                continue
            overlap = None
            if booking["from"]:
                delta = min(end, local_time(booking["to"])) - max(start, local_time(booking["from"]))
                if delta <= timedelta():
                    continue
                overlap = max(1, int(delta.total_seconds() / 60))
            impacts.append({"task_id": booking["id"], "title": booking["title"], "shared": shared,
                            "overlap_minutes": overlap, "starts_at": booking["from"]})
        if impacts:
            blocked = blocked or "有后续任务冲突或时间待核对"
        return {"id": key, "label": OPTIONS[key], "vehicle_id": vehicle_id, "vehicle": label,
                "meeting_point": alternate if key == "move" else plan["meeting_point"],
                "meeting_from": earliest.isoformat(), "meeting_to": latest.isoformat(),
                "vehicle_from": start.isoformat() if vehicle else None, "vehicle_to": end.isoformat() if vehicle else None,
                "vehicle_minutes": int((end - start).total_seconds() / 60) if vehicle else None,
                "impacts": impacts, "blocked": blocked,
                "condition": "原车须由司机确认可用；恢复前仅等候或引导到会合点。" if fault and key != "reserve" else None}

    wait = option("wait", original, 20 if fault else 0, 30 if fault else 0)
    spare = [option("reserve", vehicle, 12, 18) for vehicle in state["vehicles"]
             if vehicle["id"] != task["vehicle_id"] and vehicle["available"] and vehicle["seats"] >= task["passengers"]]
    reserve = min(spare, key=lambda item: (bool(item["blocked"]), len(item["impacts"]), item["vehicle_id"])) if spare else {
        **option("reserve", None, 12, 18), "blocked": "暂无可用且座位充足的备用车"}
    move = option("move", original, 20 if fault else 0, 30 if fault else 0, 8, 12)
    return [wait, reserve, move]


def register_pickup_scenario(app, desk, field, identity):
    store = desk.store
    with store.connect(write=True) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS pickup_scenarios (
            id TEXT PRIMARY KEY, event_id TEXT NOT NULL, incident_id TEXT NOT NULL, actor TEXT NOT NULL,
            fingerprint TEXT NOT NULL, data TEXT NOT NULL, receipt TEXT, request_id TEXT
        )""")

    def access(conn, event, channel_id, incident_id, user):
        field.require_dispatcher(user)
        actor = field.require_person(user)
        channel = field.authorized_channel(conn, event, channel_id, actor)
        incident = message_for(conn, event, incident_id)
        if (channel["kind"] != "group" or not incident or incident.get("request_kind") != "ptt_incident"
                or incident["voice_channel_id"] != channel_id):
            raise HTTPException(404, "找不到可访问的频道异常")
        return incident

    def pending(conn, event, incident):
        if any(message.get("incident_id") == incident["id"] and message.get("source") == "dispatch_instruction"
               for message in store.records(conn, "messages", event)):
            raise HTTPException(409, "此异常已经下达指示")

    path = "/api/field/events/{event}/channels/{channel_id}/incidents/{incident_id}/scenario"

    @app.post(path, status_code=201)
    def preview(event: str, channel_id: str, incident_id: str, body: Preview, user=Depends(identity)):
        with store.connect(write=True) as conn:
            incident = access(conn, event, channel_id, incident_id, user)
            pending(conn, event, incident)
            at = now_at_airport()
            _, _, state, observation, window = pickup_state(conn, desk, event, incident, user, at)
            fingerprint = digest(dump(state))
            result = {"id": str(uuid4()), "flight": state["flight"], "flight_status": observation["status"],
                      "source_time": observation["source_time"], "at": at.isoformat(),
                      "expires_at": (at + VALID_FOR).isoformat(), "snapshot": fingerprint,
                      "driver": state["driver_name"], "options": options_for(state, window, incident, body.alternate_point, at),
                      "assumptions": ["香港机场数据结合出厅时间区间；时间为估算。",
                                      "备用车 12–18 分钟到位，改点增加 8–12 分钟步行。",
                                      "车辆故障时原车按 20–30 分钟复核，须现场确认可用。",
                                      "车辆占用包含酒店车程、登车和周转共额外 25 分钟。"]}
            conn.execute("""INSERT INTO pickup_scenarios(id,event_id,incident_id,actor,fingerprint,data)
                            VALUES (?,?,?,?,?,?)""", (result["id"], event, incident_id, user["actor"], fingerprint, dump(result)))
            return result

    @app.post(path + "/{run_id}/approve", status_code=201)
    def approve(event: str, channel_id: str, incident_id: str, run_id: UUID4, body: Approval, user=Depends(identity)):
        with store.connect(write=True) as conn:
            incident = access(conn, event, channel_id, incident_id, user)
            run = conn.execute("""SELECT * FROM pickup_scenarios WHERE id=? AND event_id=? AND incident_id=? AND actor=?""",
                               (str(run_id), event, incident_id, user["actor"])).fetchone()
            if not run:
                raise HTTPException(404, "找不到可确认的推演")
            if run["receipt"]:
                receipt = json.loads(run["receipt"])
                if run["request_id"] != str(body.request_id) or receipt["result"]["option"] != body.option:
                    raise HTTPException(409, "此推演已下达")
                return receipt
            pending(conn, event, incident)
            previewed = json.loads(run["data"])
            approved_at = now_at_airport()
            if local_time(previewed["expires_at"]) <= approved_at:
                raise HTTPException(409, "推演已过期，请重新比较")
            row, definition, state, _, _ = pickup_state(conn, desk, event, incident, user, approved_at)
            if digest(dump(state)) != run["fingerprint"]:
                raise HTTPException(409, "航班、任务或车辆状态已变化，请重新比较")
            option = next(item for item in previewed["options"] if item["id"] == body.option)
            if option["blocked"]:
                raise HTTPException(409, option["blocked"])
            if local_time(option["meeting_to"]) < approved_at:
                raise HTTPException(409, "方案的会合时段已过，请重新比较")
            task, plan = state["task"], state["plan"]
            task.update({"vehicle_id": option["vehicle_id"], "pickup_at": option["meeting_from"]})
            task["pickup_scenario"] = {"pickup_at": task["pickup_at"], "vehicle_id": task["vehicle_id"],
                                       "driver": task["assigned_driver"], "transfer_minutes": plan["hotel"]["transfer_minutes"],
                                       "from": option["vehicle_from"], "to": option["vehicle_to"]}
            if body.option == "move":
                plan["meeting_point"] = option["meeting_point"]
                conn.execute("UPDATE arrival_plans SET version=version+1,data=? WHERE event_id=? AND task_id=?",
                             (dump(plan), event, row["id"]))
            version = row["version"] + 1
            conn.execute("""UPDATE objects SET version=?,data=? WHERE event_id=? AND ontology_id=?
                         AND object_type=? AND id=?""", (version, dump(task), event, row["ontology_id"], row["object_type"], row["id"]))
            meeting = f'{local_time(option["meeting_from"]):%H:%M}–{local_time(option["meeting_to"]):%H:%M}'
            text = f'{state["flight"]} · {option["label"]}。会合点 {option["meeting_point"]}，预计 {meeting}。司机 {state["driver_name"]}，车辆 {option["vehicle"]}。'
            if option["condition"]:
                text += option["condition"]
            text += "请确认收到，有变动请在频道回报。"
            key = "scenario-" + str(run_id)
            updated = {**dict(row), "version": version, "data": dump(task)}
            instruction = write_instruction(conn, event, incident, updated, definition, user, "instruction-" + key, text)
            history(conn, event, row["id"], "scenario_approved", user["actor"], f'{option["label"]} · {meeting}')
            receipt = {"id": "receipt-" + uuid4().hex, "action_type": "approve_pickup_scenario", "actor": user["actor"],
                       "proposal_id": None, "target_ref": ref_of(row), "status": "committed", "idempotency_key": key,
                       "created_at": approved_at.isoformat(),
                       "result": {"run_id": str(run_id), "option": body.option, "object_version": version,
                                  "instruction_id": instruction["id"], "snapshot": run["fingerprint"]}}
            conn.execute("INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
                         (receipt["id"], event, key, run["fingerprint"], user["actor"], dump(receipt)))
            conn.execute("UPDATE pickup_scenarios SET receipt=?,request_id=? WHERE id=?",
                         (dump(receipt), str(body.request_id), str(run_id)))
            return receipt
