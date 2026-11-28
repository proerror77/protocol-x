"""Turn qualifying, attributed group-channel transcripts into auditable work items."""
import hashlib
import json
import re

from .reception_progress import started as reception_started
from .bindings import can_read_task, candidates, event_and_definition, ref_of, resolve, task_rows
from fastapi import HTTPException
from .database import connect
from .field import stamp
from .store import Store, dump

LABELS = {"vehicle": "车辆故障", "handoff": "会合异常", "delay": "到达延误", "assistance": "请求调度协助"}
RULES = (
    ("vehicle", r"車.{0,6}(故障|坏了|壞了|抛锚|拋錨|无法启动|無法啟動|開唔到|开不了|不可用)|"
                r"车.{0,6}(故障|坏了|壞了|抛锚|无法启动|开不了|不可用)|\bvehicle (broke down|won.t start)\b"),
    ("handoff", r"(入口|[门門閘]|会合点|會合點).{0,5}(关闭|關閉|封閉|封闭|关了|關咗)|"
                r"(找不到|搵唔到|没接到|沒接到|未找到).{0,8}(球员|球員|乘客|客人|接机员|接機員)|\bgate .{0,12}closed\b"),
    ("delay", r"(航[班機]|航机|车辆|車輛|司机|司機).{0,14}(延误|延誤|晚点|晚點|未到|还没到|還沒到|仲未到)|"
              r"\b(flight|driver).{0,25}\b(delayed|late|hasn.t arrived)\b"),
    ("assistance", r"(请|請|麻烦|麻煩|请求|請求|需要).{0,8}(协助|協助|支援|帮忙|幫忙)|"
                   r"\b(need|please send) (help|assistance|support)\b"),
)
NEGATED = re.compile(r"(没有|沒有|并无|並無|并未|並未|未发现|未發現|没有发生|沒有發生|无须|無須|无需|無需|不用|唔使|"
                     r"如果|假设|假設|是否|有没有|有沒有|\bno\b|\bnot\b|\bif\b|\bwithout\b)", re.I)
QUESTION = re.compile(r"([吗嗎么嗎]\s*$|[？?]|有没有|有沒有|是否|係咪|會唔會|会不会)", re.I)


def incident_kind(text):
    for clause in re.split(r"(?<=[，。？！；,!?;\n])", text):
        if QUESTION.search(clause) or NEGATED.search(clause):
            continue
        for kind, pattern in RULES:
            if re.search(pattern, clause, re.I):
                return kind
    return None


def normalized(value):
    return "".join(c.upper() for c in value if c.isalnum())


FLIGHT_CODE = re.compile(r"(?<![A-Z0-9])([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z])\s*([0-9]{2,4})(?![A-Z0-9])", re.I)
ARRIVAL_HALL = re.compile(r"(?:到港[厅廳]|接[機机]大[堂廳厅]|arrival\s*hall)\s*([AB])", re.I)
DOOR = re.compile(r"([0-9]{1,2}|[一二三四五六七八九十])\s*(?:[号號]\s*)?[门門閘]", re.I)


def flight_codes(text):
    return {normalized(match[0]) for match in FLIGHT_CODE.finditer(text)}


def place_tags(text):
    chinese_numbers = dict(zip("一二三四五六七八九十", map(str, range(1, 11))))
    halls = {"到港厅 " + match[1].upper() for match in ARRIVAL_HALL.finditer(text)}
    return halls | {chinese_numbers.get(match[1], match[1]) + "号门" for match in DOOR.finditer(text)}


def current_plan(conn, event_id, task_id, data):
    if not data.get("arrival_managed"):
        return {}
    plan = conn.execute("SELECT data FROM arrival_plans WHERE event_id=? AND task_id=?", (event_id, task_id)).fetchone()
    return json.loads(plan["data"]) if plan else {}


def choose_task(conn, event_id, definition, actor, text):
    selected = []
    binding = definition["binding"]
    user = {"role": "staff", "actor": actor, "device": True}
    for row in task_rows(conn, event_id, definition):
        if not can_read_task(row, definition, user):
            continue
        task = json.loads(row["data"])
        if task[binding["status"]] == "declined" or (
            task[binding["progress"]] and all(step["status"] in {"confirmed", "completed", "done"}
                                              for step in task[binding["progress"]])
        ):
            continue
        selected.append((row, task))
    spoken_flights = flight_codes(text)
    spoken_places = place_tags(text)
    candidates_with_context = []
    phrase = normalized(text)
    for row, task in selected:
        plan = current_plan(conn, event_id, row["id"], task)
        task_flights = flight_codes(plan.get("flight", "") + " " + task[binding["title"]])
        location = plan.get("meeting_point") or task.get("field_response", {}).get("location", "")
        places = place_tags(location)
        identifiers = [row["id"], task[binding["title"]], location, plan.get("flight", "")]
        matched = any(len(v) >= 3 and v in phrase for v in map(normalized, identifiers))
        candidates_with_context.append((row, task_flights, places, matched))
    if spoken_flights:
        candidates_with_context = [item for item in candidates_with_context if spoken_flights & item[1]]
        if not candidates_with_context:
            return None
    if len(candidates_with_context) == 1:
        row, _flights, places, _matched = candidates_with_context[0]
        if not spoken_flights and spoken_places and places and not (spoken_places & places):
            return None
        return row
    by_place = [item[0] for item in candidates_with_context if spoken_places & item[2]]
    if len(by_place) == 1:
        return by_place[0]
    by_id = [item[0] for item in candidates_with_context if item[3]]
    return by_id[0] if len(by_id) == 1 else None


def record_incident_in_conn(conn, definitions, channel_id, item):
    kind = incident_kind(item["text"])
    if not kind:
        return None
    actor = item["actor"]
    member = conn.execute("""
        SELECT c.event_id,p.name FROM field_channels c
        JOIN field_members m ON m.channel_id=c.id JOIN field_people p ON p.id=m.person_id
        WHERE c.id=? AND c.kind='group' AND p.id=? AND p.role='staff'
    """, (channel_id, actor)).fetchone()
    if not member:
        return None
    event_id = member["event_id"]
    _, definition = event_and_definition(conn, event_id, definitions)
    track = item["track_sid"]
    key = hashlib.sha256(dump([channel_id, track]).encode()).hexdigest()
    message_id = "ptt-" + key[:32]
    if conn.execute("SELECT 1 FROM messages WHERE id=?", (message_id,)).fetchone():
        return message_id
    task = choose_task(conn, event_id, definition, actor, item["text"])
    ref = ref_of(task) if task else None
    message = {"id": message_id, "created_at": stamp(), "author": actor, "speaker": member["name"],
               "source": "qrf_exception", "request_kind": "ptt_incident", "kind": kind, "incident_label": LABELS[kind],
               "text": item["text"][:2000], "subject_ref": ref,
               "voice_channel_id": channel_id, "voice_track_sid": track,
               "review_engine": dict(item).get("review_engine"),
               "flight_mentions": sorted(flight_codes(item["text"])), "place_mentions": sorted(place_tags(item["text"]))}
    conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (message_id, event_id, dump(message)))
    if kind == "vehicle" and task and definition["binding"]["resource_type"] == "Vehicle":
        option = next(iter(candidates(conn, event_id, task, definition)), None)
        if option:
            proposal = {"id": "ptt-proposal-" + key[:32], "message_id": message_id,
                        "engine": "ptt_incident", "title": f"建议换用{option['label']}",
                        "reason": "频道发言报告车辆故障。候选车辆通过当前可用性、容量和预留检查，等待调度员确认。",
                        "evidence_quote": message["text"], "status": "proposed", "target_ref": ref,
                        "expected_version": task["version"], "action_type": "replace_resource",
                        "parameters": {"replacement_ref": option["ref"]}, "created_at": message["created_at"]}
            conn.execute("INSERT INTO proposals(id,event_id,message_id,data) VALUES(?,?,?,?)",
                         (proposal["id"], event_id, message_id, dump(proposal)))
    return message_id


def record_incident(path, definitions, channel_id, item):
    if not incident_kind(item["text"]):
        return
    with connect(path, write=True) as conn:
        record_incident_in_conn(conn, definitions, channel_id, item)


def channel_records(conn, event_id, channel_id):
    if conn.postgres:
        scope = "(m.data::jsonb->>'voice_channel_id')"
        target = "(r.data::jsonb->'result'->>'message_id')"
        kind = "(r.data::jsonb->>'action_type')"
        order = "record_seq"
    else:
        scope = "json_extract(m.data,'$.voice_channel_id')"
        target = "json_extract(r.data,'$.result.message_id')"
        kind = "json_extract(r.data,'$.action_type')"
        order = "rowid"
    messages = conn.execute(f"""SELECT m.data FROM messages m WHERE m.event_id=? AND {scope}=?
                               ORDER BY m.{order}""", (event_id,channel_id))
    receipts = conn.execute(f"""SELECT r.data FROM messages m JOIN receipts r
                               ON r.event_id=m.event_id AND {target}=m.id
                               WHERE m.event_id=? AND {scope}=?
                                  AND {kind} IN ('ack_qrf_exception','receive_dispatch_instruction')""",
                           (event_id,channel_id))
    return ([json.loads(row["data"]) for row in messages], [json.loads(row["data"]) for row in receipts])


def incident_statuses(conn, event_id, channel_id, records=None):
    messages, receipts = channel_records(conn, event_id, channel_id) if records is None else records
    pending = {m["id"]: m for m in reversed(messages)
               if m.get("voice_channel_id") == channel_id and m.get("request_kind") == "ptt_incident"}
    dispatched = {m["incident_id"] for m in messages if m.get("source") == "dispatch_instruction"}
    if not pending:
        return {}
    acknowledged = {r["result"]["message_id"] for r in receipts
                    if r["action_type"] == "ack_qrf_exception"}
    return {m["voice_track_sid"]: {"id": m["id"], "label": m["incident_label"],
             "task_id": m["subject_ref"]["id"] if m["subject_ref"] else None,
             "created_at": m["created_at"], "acknowledged": m["id"] in acknowledged,
             "dispatched": m["id"] in dispatched} for m in pending.values()}


def visible_messages(conn, messages, user):
    if user["role"] == "dispatcher":
        return messages
    received = {m["channel_id"] for m in conn.execute("SELECT channel_id FROM field_members WHERE person_id=?",
                                                     (user["actor"],))} if "device" in user else set()
    return [m for m in messages if (user["actor"] in m.get("recipients", []) if m.get("source") == "dispatch_instruction"
             else not m.get("voice_channel_id") or m["voice_channel_id"] in received)]


def message_for(conn, event_id, message_id):
    row = conn.execute("SELECT data FROM messages WHERE id=? AND event_id=?", (message_id, event_id)).fetchone()
    return json.loads(row["data"]) if row else None


def field_recipients(task, definition):
    binding = definition["binding"]
    data = json.loads(task["data"])
    return sorted({actor for actor in [data.get(binding["owner"]), data.get("arrival_greeter")] if actor})


def recommendation(conn, event_id, definitions, incident, user):
    if incident["subject_ref"] is None:
        return {"next": "请在频道确认航班号或会合点，再给对应人员下达指示。",
                "review_note": "发言暂未与当前任务唯一匹配"}
    _, definition = event_and_definition(conn, event_id, definitions)
    task = resolve(conn, event_id, incident["subject_ref"])
    if not can_read_task(task, definition, user):
        return None
    binding = definition["binding"]
    data = json.loads(task["data"])
    plan = current_plan(conn, event_id, task["id"], data)
    meeting = plan.get("meeting_point") or data.get("field_response", {}).get("location")
    resource_id = data.get(binding["resource"])
    vehicle = None
    if resource_id and binding["resource_type"] == "Vehicle":
        vehicle = resolve(conn, event_id, {"ontology_id": task["ontology_id"], "object_type": "Vehicle", "id": resource_id})
    assignment = json.loads(vehicle["data"])[binding["resource_label"]] if vehicle else None
    observation = None
    if plan:
        link = conn.execute("SELECT observation FROM pickup_flight_links WHERE event_id=? AND task_ref=?",
                            (event_id, dump(incident["subject_ref"]))).fetchone()
        observation = json.loads(link["observation"]) if link and link["observation"] else None
    recipients = field_recipients(task, definition)
    people = {row["id"]: row["name"] for row in conn.execute("SELECT id,name FROM field_people")}
    status = {"estimated": "预计到达", "landed": "已落地", "at_gate": "已到机位", "scheduled": "计划到达",
              "cancelled": "已取消", "delayed": "机场通报延误"}
    flight_status = None
    if observation:
        when = observation.get("actual") or observation.get("estimated") or observation.get("scheduled")
        label = status.get(observation["status"], "待核对")
        flight_status = f"{label} {when}" if when else label
    hints = {
        "handoff": "请接机员和司机核对当前会合点的通行情况，在频道回报实际可用的会合点。",
        "delay": "请接机员和司机核对航班及现场进度，在频道确认新的预计会合时间。",
        "vehicle": "请司机确认车辆状态和球员所在位置，在频道回报。等待调度确认备用车后再转移。",
        "assistance": "请接机员和司机在频道回报当前位置、球员是否已会合，以及最需要的协助。",
    }
    details = {"task_title": data[binding["title"]], "status": binding["status_labels"].get(data[binding["status"]], data[binding["status"]]),
               "vehicle": assignment, "meeting_point": meeting, "flight": plan.get("flight"), "flight_state": flight_status,
               "airport_updated_at": observation.get("source_time") if observation else None,
               "recipients": [people.get(a, a) for a in recipients], "version": task["version"],
               "pickup_scenario": bool(data.get("arrival_managed") and not reception_started(data, "met", 0)),
               "next": hints[incident["kind"]], "dispatchable": bool(recipients)}
    heard_places = set(incident.get("place_mentions", []))
    scheduled_places = place_tags(meeting or "")
    if heard_places and scheduled_places and not heard_places & scheduled_places:
        details["review_note"] = "发言地点 " + "、".join(sorted(heard_places)) + " 与任务会合点不同，请核对现场。"
    if plan.get("flight") and normalized(plan["flight"]) in incident.get("flight_mentions", []):
        if "review_note" not in details:
            details["review_note"] = "发言航班已与接机任务核对。"
    if incident["kind"] == "vehicle":
        row = conn.execute("SELECT data FROM proposals WHERE event_id=? AND message_id=?",
                           (event_id, incident["id"])).fetchone()
        proposed = json.loads(row["data"]) if row else None
        if (proposed and proposed["status"] == "proposed" and proposed["expected_version"] == task["version"] and
            proposed["parameters"]["replacement_ref"] in [c["ref"] for c in candidates(conn, event_id, task, definition)]):
            details["vehicle_change"] = proposed
    return details


def write_instruction(conn, event_id, incident, task, definition, user, key, instruction):
    recipients = field_recipients(task, definition)
    if not recipients:
        raise HTTPException(409, "任务没有已指派的现场人员")
    message = {"id": key, "author": user["actor"], "speaker": user.get("name"), "source": "dispatch_instruction",
               "text": instruction, "created_at": stamp(), "subject_ref": ref_of(task),
               "voice_channel_id": incident["voice_channel_id"], "incident_id": incident["id"],
               "recipients": recipients, "task_version": task["version"]}
    conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (key, event_id, dump(message)))
    return message


def notify_vehicle_change(conn, event_id, definition, task, user, proposal, receipt):
    incident = message_for(conn, event_id, proposal["message_id"])
    if incident and incident.get("request_kind") == "ptt_incident":
        return write_instruction(conn, event_id, incident, task, definition, user, "instruction-" + receipt["id"],
                                 "调度已确认换车。请使用 " + receipt["result"]["resource_label"] + "，在频道确认交接。")
    return None


def channel_updates(conn, event_id, channel_id, definitions, user, records=None):
    messages, activity_receipts = channel_records(conn, event_id, channel_id) if records is None else records
    activity_instructions = [m for m in messages if m.get("source") == "dispatch_instruction"]
    instructions = [m for m in activity_instructions if m["voice_channel_id"] == channel_id
                    and (user["role"] == "dispatcher" or user["actor"] in m["recipients"])]
    receipts = [r for r in activity_receipts if r["action_type"] == "receive_dispatch_instruction"]
    delivered = {(r["actor"], r["result"]["message_id"]) for r in receipts}
    delivered_count = {m["id"]: sum((a, m["id"]) in delivered for a in m["recipients"]) for m in instructions}
    instructions = [{**m, "received": (user["actor"], m["id"]) in delivered,
                     "received_count": delivered_count[m["id"]]} for m in instructions[-32:]]
    sent = {m["incident_id"] for m in activity_instructions}
    next_steps = []
    if user["role"] == "dispatcher":
        for m in reversed(messages):
            if m.get("request_kind") != "ptt_incident" or m.get("voice_channel_id") != channel_id:
                continue
            if m["id"] in sent:
                continue
            suggestion = recommendation(conn, event_id, definitions, m, user)
            if suggestion:
                next_steps.append({"id": m["id"], "label": m["incident_label"], "text": m["text"],
                                   "track_sid": m["voice_track_sid"], "created_at": m["created_at"], **suggestion})
            if len(next_steps) == 8:
                break
    activity = [{"id": m["id"], "created_at": m["created_at"], "label": m["incident_label"],
                 "dispatched": m["id"] in sent}
                for m in messages if m.get("request_kind") == "ptt_incident" and m.get("voice_channel_id") == channel_id]
    has_channel_msgs = (
        True if conn.postgres else
        conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='field_channel_messages'").fetchone() is not None
    )
    channel_messages = []
    if has_channel_msgs:
        rows = conn.execute("""
            SELECT id, event_id, channel_id, actor, speaker, created_at, scope, text,
                   recipient, sealed_payload, assistance, voice_track_sid
            FROM field_channel_messages
            WHERE event_id=? AND channel_id=?
              AND (recipient IS NULL OR recipient='' OR recipient=? OR actor=?)
            ORDER BY created_at DESC, id DESC LIMIT 50
            """, (event_id, channel_id, user["actor"], user["actor"])).fetchall()
        channel_messages = [{**dict(r), "assistance": bool(r["assistance"])} for r in reversed(rows)]
    return {"incidents": next_steps, "instructions": instructions, "agent_activity": activity[-24:],
            "channel_messages": channel_messages}
