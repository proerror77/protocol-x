"""Player reception plans, role-scoped handoffs and airport-derived readiness."""
import asyncio
import base64
import binascii
import json
import re
from datetime import date, datetime, time, timedelta, timezone
from typing import Annotated, Literal

from fastapi import Depends, HTTPException, Query, Request, Response
from fastapi.responses import StreamingResponse
from pydantic import AwareDatetime, Field, StringConstraints, UUID4, model_validator
from starlette.concurrency import run_in_threadpool

from .bindings import event_and_definition, project_task, ref_of, resolve, task_rows
from .flights import AIRPORT_TZ, HKG_EVENTS
from .models import StrictModel
from .pickup_watch import arrival_time, flight_number
from .store import dump
from .travel_actions import HongKongPoint, estimate_transfer, travel_links

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)]
Actor = Annotated[str, StringConstraints(pattern=r"^(demo-staff|person-[a-f0-9]{32})$")]
FLIGHT_NOTICE_LABELS = {"delay": "航班延误", "landed": "航班已落地", "at_gate": "飞机已到达机位"}
ARRIVAL_NOTICE_SCOPE = "arrival-flight-notices"
HISTORY = {
    "plan_created": "安排接待", "plan_updated": "更新接待安排", "photo_updated": "更新球员照片",
    "hotel_confirmed": "确认酒店预订", "hotel_pending": "酒店改为待确认",
    "hotel_late": "Automate 提醒协调酒店留房",
    "delay": "航班预计延误",
    "scheduled": "机场发布计划", "estimated": "机场更新预计到达", "delayed": "机场通报延误",
    "landed": "航班落地", "at_gate": "飞机到达机位", "cancelled": "航班取消",
    "reschedule_pickup": "调度员调整接机时间", "replace_resource": "调度员调整车辆",
    "scenario_approved": "调度员确认接机方案",
    "proposal": "Agent 提出调度建议", "acknowledge": "司机确认接单",
}


class PlayerInput(StrictModel):
    id: UUID4
    name: Name


class HotelInput(StrictModel):
    name: Name
    address: Name
    coordinates: HongKongPoint | None = None
    check_in: date
    check_out: date
    confirmed: bool = False
    latest_check_in: time | None = None
    transfer_minutes: int = Field(default=45, ge=5, le=240)
    instruction: str = Field(default="", max_length=240)

    @model_validator(mode="after")
    def dates(self):
        if self.check_out <= self.check_in or (self.latest_check_in and self.latest_check_in.tzinfo):
            raise ValueError("退房日期须晚于入住日期，最晚入住时间请填写香港本地时间")
        return self


class PlanInput(StrictModel):
    expected_version: int = Field(ge=0)
    expected_task_version: int = Field(default=0, ge=0)
    players: list[PlayerInput] = Field(min_length=1, max_length=12)
    flight_date: date
    flight: str = Field(min_length=3, max_length=16)
    pickup_at: AwareDatetime | None = None
    driver: Actor
    greeter: Actor
    vehicle_id: Name
    meeting_point: Name
    meeting_coordinates: HongKongPoint | None = None
    hotel: HotelInput
    notes: str = Field(default="", max_length=300)
    synthetic: bool = False

    @model_validator(mode="after")
    def unique_players(self):
        if len({player.id for player in self.players}) != len(self.players):
            raise ValueError("同一球员只能添加一次")
        return self


class StageInput(StrictModel):
    expected_version: int = Field(ge=1)
    stage: Literal["met", "boarded", "hotel_handoff", "checked_in"]


class PhotoInput(StrictModel):
    data: str | None = Field(default=None, max_length=700000)


def iso_now():
    return datetime.now(timezone.utc).isoformat()


def reception_window(day, observation, rules):
    if not observation or observation["status"] in {"cancelled", "delayed", "other"}:
        return None
    status = observation["status"]
    if status in {"at_gate", "landed"}:
        anchor = arrival_time(day, observation["actual"], observation.get("scheduled"))
    elif status == "estimated":
        value = observation.get("estimated_at")
        anchor = datetime.fromisoformat(value) if value else None
    else:
        anchor = arrival_time(day, observation["scheduled"])
    if anchor is None:
        return None
    taxi = 0 if status == "at_gate" else rules["taxi_minutes"]
    return {
        "from": (anchor + timedelta(minutes=taxi + rules["exit_min_minutes"])).isoformat(),
        "to": (anchor + timedelta(minutes=taxi + rules["exit_max_minutes"])).isoformat(),
        "basis": status, "source_time": observation["source_time"],
        "valid_until": (datetime.fromisoformat(observation["source_time"])
                        + timedelta(minutes=rules["fresh_minutes"])).isoformat()
        if status in {"scheduled", "estimated"} else None,
    }


def hotel_risk(stay, window):
    if not stay["latest_check_in"] or not window:
        return None
    deadline = datetime.combine(date.fromisoformat(stay["check_in"]),
                                time.fromisoformat(stay["latest_check_in"]), AIRPORT_TZ)
    transfer = timedelta(minutes=stay["transfer_minutes"])
    earliest = datetime.fromisoformat(window["from"]) + transfer
    latest = datetime.fromisoformat(window["to"]) + transfer
    if latest <= deadline:
        return None
    return {"deadline": deadline.isoformat(), "earliest": earliest.isoformat(),
            "latest": latest.isoformat(), "certain": earliest > deadline}


def history(conn, event_id, task_id, kind, actor, detail, occurred_at=None):
    conn.execute("""
        INSERT INTO arrival_history(event_id,task_id,kind,actor,occurred_at,recorded_at,detail)
        VALUES (?,?,?,?,?,?,?) ON CONFLICT(event_id,task_id,kind,occurred_at) DO NOTHING
    """, (event_id, task_id, kind, actor, occurred_at or iso_now(), iso_now(), detail))


class ArrivalDesk:
    def __init__(self, store, definitions, pickup, field):
        self.store = store
        self.definitions = definitions
        self.pickup = pickup
        self.field = field
        pickup.on_observation = self.record_observation
        with store.connect(write=True) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS arrival_plans (
                    event_id TEXT NOT NULL, task_id TEXT NOT NULL, version INTEGER NOT NULL, data TEXT NOT NULL,
                    PRIMARY KEY(event_id,task_id)
                );
                CREATE TABLE IF NOT EXISTS arrival_photos (
                    event_id TEXT NOT NULL, task_id TEXT NOT NULL, player_id TEXT NOT NULL,
                    revision TEXT NOT NULL, mime TEXT NOT NULL, content BLOB NOT NULL,
                    PRIMARY KEY(event_id,task_id,player_id),
                    FOREIGN KEY(event_id,task_id) REFERENCES arrival_plans(event_id,task_id) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS arrival_history (
                    event_id TEXT NOT NULL, task_id TEXT NOT NULL, kind TEXT NOT NULL, actor TEXT NOT NULL,
                    occurred_at TEXT NOT NULL, recorded_at TEXT NOT NULL, detail TEXT NOT NULL,
                    PRIMARY KEY(event_id,task_id,kind,occurred_at),
                    FOREIGN KEY(event_id,task_id) REFERENCES arrival_plans(event_id,task_id) ON DELETE CASCADE
                );
            """)
            sequence = ("BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY" if conn.postgres
                        else "INTEGER PRIMARY KEY AUTOINCREMENT")
            conn.executescript(f"""
                CREATE TABLE IF NOT EXISTS arrival_flight_notices (
                    seq {sequence}, event_id TEXT NOT NULL, task_id TEXT NOT NULL,
                    flight TEXT NOT NULL, flight_date TEXT NOT NULL, kind TEXT NOT NULL,
                    source_at TEXT NOT NULL, recorded_at TEXT NOT NULL,
                    UNIQUE(event_id,task_id,flight,flight_date,kind),
                    FOREIGN KEY(event_id,task_id) REFERENCES arrival_plans(event_id,task_id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS arrival_flight_notices_recorded
                    ON arrival_flight_notices(recorded_at);
            """)
            if conn.postgres:
                conn.execute("""
                    CREATE OR REPLACE FUNCTION ops_signal_arrival_notice() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM pg_notify('ops_channel_changed', 'arrival-flight-notices');
                        RETURN NEW;
                    END
                    $$
                """)
                conn.execute("DROP TRIGGER IF EXISTS ops_arrival_notice ON arrival_flight_notices")
                conn.execute("""
                    CREATE TRIGGER ops_arrival_notice AFTER INSERT ON arrival_flight_notices
                    FOR EACH ROW EXECUTE FUNCTION ops_signal_arrival_notice()
                """)

    def record_observation(self, conn, link, previous, observation):
        task_id = json.loads(link["task_ref"])["id"]
        event_id = link["event_id"]
        record = conn.execute("SELECT data FROM arrival_plans WHERE event_id=? AND task_id=?",
                              (event_id, task_id)).fetchone()
        if not record:
            return
        fields = ("status", "estimated", "actual", "arrivals_hall", "baggage_belt")
        if previous and all(previous.get(key) == observation.get(key) for key in fields):
            return
        when = observation.get("actual") or observation.get("estimated") or observation.get("scheduled")
        detail = " · ".join(filter(None, [link["flight"], when]))
        kind = "delay" if observation["delay_minutes"] else observation["status"]
        if observation["delay_minutes"]:
            detail += f" · 延误 {observation['delay_minutes']} 分钟"
        history(conn, event_id, task_id, kind, "HKIA", detail, observation["source_time"])
        notice = "delay" if kind == "delayed" else kind
        if notice in FLIGHT_NOTICE_LABELS:
            conn.execute("""
                INSERT INTO arrival_flight_notices
                    (event_id,task_id,flight,flight_date,kind,source_at,recorded_at)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT(event_id,task_id,flight,flight_date,kind) DO NOTHING
            """, (event_id, task_id, link["flight"], link["day"], notice, observation["source_time"], iso_now()))
        plan = json.loads(record["data"])
        rules = self.scope(conn, event_id)["arrival_rules"]
        risk = hotel_risk(plan["hotel"], reception_window(link["day"], observation, rules))
        previous_risk = hotel_risk(plan["hotel"], reception_window(link["day"], previous, rules))
        if risk and (not previous_risk or risk["certain"] != previous_risk["certain"]):
            deadline = datetime.fromisoformat(risk["deadline"])
            history(conn, event_id, task_id, "hotel_late", "Automate",
                    f"最晚入住 {deadline:%m-%d %H:%M} HKT", observation["source_time"])

    def notices(self, user, after):
        if not self.field.enabled or "device" not in user or user["role"] != "staff":
            raise HTTPException(403, "请先绑定个人现场账号")
        actor = user["actor"]
        window = timedelta(hours=12) if after else timedelta(minutes=10)
        cutoff = (datetime.now(timezone.utc) - window).isoformat()
        with self.store.connect() as conn:
            driver, greeter, flight, day = (
                ("o.data::jsonb->>'assigned_driver'", "p.data::jsonb->>'greeter'",
                 "p.data::jsonb->>'flight'", "p.data::jsonb->>'flight_date'") if conn.postgres else
                ("json_extract(o.data,'$.assigned_driver')", "json_extract(p.data,'$.greeter')",
                 "json_extract(p.data,'$.flight')", "json_extract(p.data,'$.flight_date')")
            )
            rows = conn.execute(f"""
                SELECT n.seq,n.event_id,n.task_id,n.flight,n.kind,n.source_at
                FROM arrival_flight_notices n
                JOIN arrival_plans p ON p.event_id=n.event_id AND p.task_id=n.task_id
                JOIN objects o ON o.event_id=n.event_id AND o.id=n.task_id
                    AND o.ontology_id='golf.v1' AND o.object_type='TransferLeg'
                WHERE n.seq>? AND n.recorded_at>=? AND n.flight=({flight}) AND n.flight_date=({day})
                  AND (({driver})=? OR ({greeter})=?)
                  AND EXISTS (
                      SELECT 1 FROM field_members m JOIN field_channels c ON c.id=m.channel_id
                      WHERE m.person_id=? AND c.event_id=n.event_id AND c.kind='group'
                  )
                ORDER BY n.seq LIMIT 51
            """, (after, cutoff, actor, actor, actor)).fetchall()
        items = [{**dict(row), "label": FLIGHT_NOTICE_LABELS[row["kind"]]} for row in rows[:50]]
        return {"items": items, "next_after": items[-1]["seq"] if items else after, "has_more": len(rows) > 50}

    def scope(self, conn, event_id):
        if event_id not in HKG_EVENTS:
            raise HTTPException(404, "此活动未启用香港接待")
        return event_and_definition(conn, event_id, self.definitions)[1]

    @staticmethod
    def access(plan, task, user):
        if user["role"] == "dispatcher":
            return plan["synthetic"] or "device" in user
        if not plan["synthetic"] and "device" not in user:
            return False
        return user["actor"] in (plan["greeter"], task["assigned_driver"])

    def load(self, conn, event_id, task_id, user):
        definition = self.scope(conn, event_id)
        record = conn.execute("SELECT * FROM arrival_plans WHERE event_id=? AND task_id=?",
                              (event_id, task_id)).fetchone()
        if record is None:
            raise HTTPException(404, "接待安排不存在")
        row = resolve(conn, event_id, {"id": task_id, "ontology_id": definition["ontology_id"],
                                      "object_type": definition["binding"]["task_type"]})
        plan, task = json.loads(record["data"]), json.loads(row["data"])
        if not self.access(plan, task, user):
            raise HTTPException(404, "接待安排不存在")
        return record, plan, row, task

    def people(self, conn, event_id):
        people = [{"actor": "demo-staff", "name": "演练工作人员"}]
        if self.field.enabled:
            people.extend(dict(row) for row in conn.execute("""
                SELECT DISTINCT p.id AS actor,p.name FROM field_people p
                JOIN field_members m ON p.id=m.person_id JOIN field_channels c ON c.id=m.channel_id
                WHERE c.event_id=? AND c.kind='group' AND p.role='staff' ORDER BY p.name,p.id
            """, (event_id,)))
        return people

    @staticmethod
    def resources(conn, event_id, definition):
        vehicles = conn.execute("""
            SELECT * FROM objects WHERE event_id=? AND ontology_id=? AND object_type='Vehicle' ORDER BY id
        """, (event_id, definition["ontology_id"]))
        return [{"id": row["id"], "name": payload["callsign"], "seats": payload["seats"],
                 "available": payload["readiness"] == "available",
                 **({"rehearsal_pool": payload["rehearsal_pool"]} if "rehearsal_pool" in payload else {})}
                for row in vehicles for payload in [json.loads(row["data"])]]

    def save(self, event_id, task_id, body, user):
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可以安排接待")
        if not body.synthetic and ("device" not in user or "demo-staff" in (body.driver, body.greeter)):
            raise HTTPException(403, "真实球员资料须由个人绑定的调度员和工作人员处理")
        number = flight_number(body.flight)
        if not re.fullmatch(r"[A-Z0-9]{2}\d{1,4}[A-Z]?", number):
            raise HTTPException(422, "请填写机场公布的主航班号")
        day = body.flight_date.isoformat()
        data = body.model_dump(mode="json", exclude={"expected_version", "expected_task_version", "driver", "vehicle_id", "pickup_at"})
        data["flight"] = number
        if abs((body.hotel.check_in - body.flight_date).days) > 1 or body.hotel.check_out <= body.flight_date:
            raise HTTPException(422, "入住日期须在航班服务日期前后一天内，退房日期须晚于航班服务日期")
        if body.pickup_at and abs((body.pickup_at.astimezone(AIRPORT_TZ).date() - body.flight_date).days) > 1:
            raise HTTPException(422, "接机时间须在航班服务日期前后一天内")
        with self.store.connect(write=True) as conn:
            definition = self.scope(conn, event_id)
            ref = {"ontology_id": definition["ontology_id"], "object_type": "TransferLeg", "id": task_id}
            old = conn.execute("SELECT version FROM arrival_plans WHERE event_id=? AND task_id=?",
                               (event_id, task_id)).fetchone()
            if body.expected_version != (old["version"] if old else 0):
                raise HTTPException(409, "安排已变化，请刷新后检查")
            existing = self.load(conn, event_id, task_id, user) if old else None
            if existing and existing[2]["version"] != body.expected_task_version:
                raise HTTPException(409, "接机任务已变化，请关闭并打开最新安排")
            task = existing[3] if existing else {}
            if old and existing[1]["synthetic"] != body.synthetic:
                raise HTTPException(409, "已有接待的资料类型不能变更")
            if old and any(step["status"] == "done" for step in task["service_facts"]):
                previous = existing[1]
                if (data["players"] != previous["players"] or data["greeter"] != previous["greeter"]
                    or data["flight"] != previous["flight"] or data["flight_date"] != previous["flight_date"]
                    or body.driver != task["assigned_driver"]):
                    raise HTTPException(409, "现场已经确认会合，请保留人员和航班历史")
                if task["service_facts"][1]["status"] == "done" and (
                    body.vehicle_id != task["vehicle_id"] or data["meeting_point"] != previous["meeting_point"]
                    or data.get("meeting_coordinates") != previous.get("meeting_coordinates")
                ):
                    raise HTTPException(409, "已登车，请保留车辆和会合地点记录")
                if task["service_facts"][2]["status"] == "done" and any(
                    data["hotel"].get(key) != previous["hotel"].get(key)
                    for key in ("name", "address", "coordinates", "check_in", "check_out")
                ):
                    raise HTTPException(409, "已完成酒店交接，请保留已交接酒店和住宿日期")
                if task["service_facts"][3]["status"] == "done" and not body.hotel.confirmed:
                    raise HTTPException(409, "已确认入住，请保留酒店确认")
            if not {body.driver, body.greeter}.issubset({p["actor"] for p in self.people(conn, event_id)}):
                raise HTTPException(422, "请选择本活动内已登记的工作人员")
            if not old or task["vehicle_id"] != body.vehicle_id or len(body.players) != task["passengers"]:
                vehicles = {v["id"]: v for v in self.resources(conn, event_id, definition)}
                reserved = {json.loads(row["data"])["vehicle_id"]
                            for row in task_rows(conn, event_id, definition) if row["id"] != task_id}
                vehicle = vehicles.get(body.vehicle_id)
                if (not vehicle or not vehicle["available"] or body.vehicle_id in reserved
                    or vehicle["seats"] < len(body.players)):
                    raise HTTPException(409, "车辆不可用、已被分配或座位不足")
            names = "、".join(player.name for player in body.players)
            stages = [{"label": stage["label"], "status": "current" if index == 0 else "pending"}
                      for index, stage in enumerate(definition["arrival_process"]["stages"])]
            same_driver = task.get("assigned_driver") == body.driver
            task.update({
                "arrival_managed": True, "arrival_synthetic": body.synthetic,
                "arrival_greeter": body.greeter, "title": "接机 · " + (names if body.synthetic else "球员接待"),
                "route": "香港国际机场 → 酒店", "player_name": names if body.synthetic else "球员接待",
                "assigned_driver": body.driver, "vehicle_id": body.vehicle_id, "passengers": len(body.players),
                "pickup_at": body.pickup_at.isoformat() if body.pickup_at else None,
                "dispatch_state": task["dispatch_state"] if same_driver else "assigned",
                "service_facts": task.get("service_facts", stages),
            })
            if old:
                conn.execute("UPDATE objects SET version=version+1,data=? WHERE event_id=? AND ontology_id=? AND object_type='TransferLeg' AND id=?",
                             (dump(task), event_id, ref["ontology_id"], task_id))
            else:
                conn.execute("INSERT INTO objects VALUES (?,?,?,?,1,?)",
                             (event_id, ref["ontology_id"], "TransferLeg", task_id, dump(task)))
            version = body.expected_version + 1
            conn.execute("""
                INSERT INTO arrival_plans VALUES (?,?,?,?) ON CONFLICT(event_id,task_id)
                DO UPDATE SET version=excluded.version,data=excluded.data
            """, (event_id, task_id, version, dump(data)))
            conn.execute("""
                INSERT INTO pickup_flight_links(event_id,task_ref,dispatcher_id,day,flight) VALUES (?,?,?,?,?)
                ON CONFLICT(event_id,task_ref) DO UPDATE SET dispatcher_id=excluded.dispatcher_id,
                    day=excluded.day,flight=excluded.flight,last_source=NULL,observation=NULL
                WHERE pickup_flight_links.day<>excluded.day OR pickup_flight_links.flight<>excluded.flight
            """, (event_id, dump(ref), user["actor"], day, number))
            player_ids = {str(player.id) for player in body.players}
            for photo in conn.execute("SELECT player_id FROM arrival_photos WHERE event_id=? AND task_id=?", (event_id, task_id)):
                if photo["player_id"] not in player_ids:
                    conn.execute("DELETE FROM arrival_photos WHERE event_id=? AND task_id=? AND player_id=?",
                                 (event_id, task_id, photo["player_id"]))
            history(conn, event_id, task_id, "plan_updated" if old else "plan_created", user["actor"],
                    f"{number} · {day}")
            previously_confirmed = existing[1]["hotel"]["confirmed"] if existing else False
            if body.hotel.confirmed != previously_confirmed:
                history(conn, event_id, task_id, "hotel_confirmed" if body.hotel.confirmed else "hotel_pending",
                        user["actor"], "")
        return {"id": task_id, "version": version}

    @staticmethod
    def allowed_stage(plan, task, user, stages):
        step = next((stage for stage, entry in zip(stages, task["service_facts"])
                     if entry["status"] == "current"), None)
        if step is None or (step.get("requires_hotel_confirmation") and not plan["hotel"]["confirmed"]):
            return None
        responsible = plan["greeter"] if step["responsible"] == "greeter" else task["assigned_driver"]
        return step["id"] if user["role"] == "dispatcher" or responsible == user["actor"] else None

    def confirm(self, event_id, task_id, body, user):
        with self.store.connect(write=True) as conn:
            record, plan, row, task = self.load(conn, event_id, task_id, user)
            stages = self.scope(conn, event_id)["arrival_process"]["stages"]
            if body.expected_version != record["version"] or self.allowed_stage(plan, task, user, stages) != body.stage:
                raise HTTPException(409, "请刷新后确认本人负责的当前节点")
            # Hotel confirmation and physical handoffs are separate facts.
            progress = task["service_facts"]
            index, stage = next((index, stage) for index, stage in enumerate(stages) if stage["id"] == body.stage)
            at = iso_now()
            progress[index].update(status="done", confirmed_at=at)
            if index + 1 < len(progress):
                progress[index + 1]["status"] = "current"
            conn.execute("UPDATE objects SET data=?,version=version+1 WHERE event_id=? AND ontology_id=? AND object_type='TransferLeg' AND id=?",
                         (dump(task), event_id, row["ontology_id"], task_id))
            conn.execute("UPDATE arrival_plans SET version=version+1 WHERE event_id=? AND task_id=?", (event_id, task_id))
            history(conn, event_id, task_id, body.stage, user["actor"], "", at)
        return {"version": record["version"] + 1, "stage": body.stage, "confirmed_at": at, "action": stage["action"]}

    def photo(self, event_id, task_id, player_id, user, upload=None):
        with self.store.connect(write=upload is not None) as conn:
            _, plan, _, _ = self.load(conn, event_id, task_id, user)
            if not any(player["id"] == player_id for player in plan["players"]):
                raise HTTPException(404, "球员不存在")
            scope = (event_id, task_id, player_id)
            cache = {"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"}
            if upload is None:
                row = conn.execute("SELECT mime,content FROM arrival_photos WHERE event_id=? AND task_id=? AND player_id=?", scope).fetchone()
                if row is None:
                    raise HTTPException(404, "尚未提供球员照片")
                return Response(bytes(row["content"]), media_type=row["mime"], headers=cache)
            if user["role"] != "dispatcher":
                raise HTTPException(403, "只有调度员可以更新照片")
            if upload.data is None:
                conn.execute("DELETE FROM arrival_photos WHERE event_id=? AND task_id=? AND player_id=?", scope)
            else:
                try:
                    content = base64.b64decode(upload.data, validate=True)
                except (binascii.Error, ValueError):
                    raise HTTPException(422, "照片格式无效") from None
                mime = None
                if content.startswith(b"\x89PNG\r\n\x1a\n") and content.endswith(b"IEND\xaeB`\x82"):
                    mime = "image/png"
                elif content.startswith(b"\xff\xd8\xff") and content.endswith(b"\xff\xd9"):
                    mime = "image/jpeg"
                if not mime or len(content) > 512000:
                    raise HTTPException(422, "照片须为 500 KB 以内的 JPEG 或 PNG")
                conn.execute("""
                    INSERT INTO arrival_photos VALUES (?,?,?,?,?,?) ON CONFLICT(event_id,task_id,player_id)
                    DO UPDATE SET revision=excluded.revision,mime=excluded.mime,content=excluded.content
                """, (*scope, iso_now(), mime, content))
            history(conn, event_id, task_id, "photo_updated", user["actor"], "")
            return Response(status_code=204, headers=cache)

    def project(self, conn, event_id, definition, user, receipts, proposals):
        if event_id not in HKG_EVENTS:
            return None
        people = self.people(conn, event_id)
        names = {p["actor"]: p["name"] for p in people}
        process = definition["arrival_process"]
        stages = process["stages"]
        labels = {**HISTORY, **{stage["id"]: stage["label"] for stage in stages}}
        cards = []
        for record in conn.execute("""
            SELECT p.*,o.data AS task,o.version AS task_version FROM arrival_plans p
            JOIN objects o ON o.event_id=p.event_id AND o.id=p.task_id AND o.object_type='TransferLeg'
                AND o.ontology_id=?
            WHERE p.event_id=? ORDER BY p.task_id
        """, (definition["ontology_id"], event_id)):
            plan, task = json.loads(record["data"]), json.loads(record["task"])
            if not self.access(plan, task, user):
                continue
            task_id = record["task_id"]
            object_row = {"event_id": event_id, "id": task_id, "ontology_id": definition["ontology_id"],
                          "object_type": "TransferLeg", "version": record["task_version"], "data": record["task"]}
            item = project_task(conn, event_id, object_row, definition, user)
            flight_link = conn.execute("""
                SELECT observation,dispatcher_id FROM pickup_flight_links WHERE event_id=? AND task_ref=?
            """, (event_id, dump(item["ref"]))).fetchone()
            scope = (event_id, task_id)
            stored = [dict(entry) for entry in conn.execute("""
                SELECT kind,actor,occurred_at,detail FROM arrival_history WHERE event_id=? AND task_id=?
                ORDER BY occurred_at DESC LIMIT 30
            """, scope)]
            for receipt in receipts:
                if receipt["target_ref"] == item["ref"] and receipt["action_type"] in HISTORY:
                    stored.append({"kind": receipt["action_type"], "actor": receipt["actor"],
                                   "occurred_at": receipt["created_at"], "detail": ""})
            for proposal in proposals:
                if proposal["target_ref"] == item["ref"] and proposal["engine"] == "flight_dispatch":
                    stored.append({"kind": "proposal", "actor": "Agent",
                                   "occurred_at": proposal["created_at"], "detail": proposal["title"]})
            photos = {row["player_id"]: row["revision"] for row in conn.execute(
                "SELECT player_id,revision FROM arrival_photos WHERE event_id=? AND task_id=?", scope)}
            observation = json.loads(flight_link["observation"]) if flight_link and flight_link["observation"] else None
            window = reception_window(plan["flight_date"], observation, definition["arrival_rules"])
            hotel_deadline = hotel_risk(plan["hotel"], window)
            cards.append({**plan, "id": task_id, "version": record["version"], "task": item,
                          "driver_name": names.get(item["owner"], item["owner"]), "greeter_name": names.get(plan["greeter"], plan["greeter"]),
                          "dispatcher": flight_link["dispatcher_id"] if flight_link else None,
                          "observation": observation, "window": window, "hotel_deadline": hotel_deadline,
                          "travel_links": travel_links(plan),
                          "photos": photos, "next_stage": self.allowed_stage(plan, task, user, stages),
                          "history": [{**entry, "actor": names.get(entry["actor"], entry["actor"]),
                                       "label": labels.get(entry["kind"], "机场更新")}
                                      for entry in sorted(stored, key=lambda entry: datetime.fromisoformat(entry["occurred_at"]),
                                                          reverse=True)[:25]]})
        dispatcher = user["role"] == "dispatcher"
        return {"process": process, "cards": cards, "people": people if dispatcher else [],
                "vehicles": self.resources(conn, event_id, definition) if dispatcher else []}


def register_arrival_desk(app, identity, desk):
    prefix = "/api/events/{event_id}/arrival-plans/{plan_id}"

    @app.get("/api/arrival-flight-notices")
    def flight_notices(response: Response, after: int = Query(default=0, ge=0, le=9223372036854775807),
                       user=Depends(identity)):
        response.headers["Cache-Control"] = "private, no-store"
        return desk.notices(user, after)

    @app.get("/api/arrival-flight-notices/stream")
    async def flight_notice_stream(request: Request,
                                   after: int = Query(default=0, ge=0, le=9223372036854775807),
                                   user=Depends(identity)):
        if not desk.field.enabled or "device" not in user or user["role"] != "staff":
            raise HTTPException(403, "请先绑定个人现场账号")
        wakeups = app.state.channel_wakeups
        header = request.headers.get("Authorization")

        async def events():
            cursor = after
            with wakeups.watch(ARRIVAL_NOTICE_SCOPE) as wake:
                while not await request.is_disconnected():
                    wake.clear()
                    try:
                        active = await run_in_threadpool(desk.field.authenticate, header)
                        if active is None or active["actor"] != user["actor"] or active["role"] != "staff":
                            return
                        batch = await run_in_threadpool(desk.notices, active, cursor)
                    except HTTPException as error:
                        if error.status_code in (401, 403):
                            return
                        raise
                    for item in batch["items"]:
                        cursor = item["seq"]
                        yield f"id: {cursor}\ndata: {dump(item)}\n\n"
                    if batch["has_more"]:
                        continue
                    try:
                        await asyncio.wait_for(wake.wait(), 20 if wakeups.paths else 2)
                    except TimeoutError:
                        yield ": heartbeat\n\n"

        return StreamingResponse(events(), media_type="text/event-stream", headers={
            "Cache-Control": "private, no-store, no-transform",
            "X-Accel-Buffering": "no",
        })

    @app.put(prefix)
    def save(event_id: str, plan_id: UUID4, body: PlanInput, user=Depends(identity)):
        return desk.save(event_id, str(plan_id), body, user)

    @app.post(prefix + "/route-estimate")
    def route_estimate(event_id: str, plan_id: UUID4, response: Response,
                       version: int = Query(ge=1), user=Depends(identity)):
        with desk.store.connect() as conn:
            record, plan, _, _ = desk.load(conn, event_id, str(plan_id), user)
            if record["version"] != version:
                raise HTTPException(409, "接待安排已更新，请刷新后再查路线。")
        response.headers["Cache-Control"] = "private, no-store"
        return estimate_transfer(plan)

    @app.post(prefix + "/stages")
    def confirm(event_id: str, plan_id: UUID4, body: StageInput, user=Depends(identity)):
        return desk.confirm(event_id, str(plan_id), body, user)

    @app.get(prefix + "/players/{player_id}/photo")
    def download(event_id: str, plan_id: UUID4, player_id: UUID4, user=Depends(identity)):
        return desk.photo(event_id, str(plan_id), str(player_id), user)

    @app.put(prefix + "/players/{player_id}/photo")
    def photo(event_id: str, plan_id: UUID4, player_id: UUID4, body: PhotoInput, user=Depends(identity)):
        return desk.photo(event_id, str(plan_id), str(player_id), user, body)
