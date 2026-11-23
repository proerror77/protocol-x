"""Dispatch work and attach idempotent, access-controlled photo reports."""
import base64
import binascii
import json
from uuid import UUID, uuid4

from fastapi import Depends, HTTPException
from fastapi.responses import Response
from pydantic import UUID4, Field, field_validator

from .bindings import can_read_task, event_and_definition, project_task
from .field import DirectInput, clean, digest, expiry, stamp
from .models import StrictModel
from .ptt_agent import field_recipients
from .store import dump


class DispatchInput(DirectInput):
    request_id: UUID4
    title: str = Field(min_length=1, max_length=80)
    location: str = Field(min_length=1, max_length=120)
    contact: str = Field(default="", max_length=80)
    due: str = Field(default="", max_length=80)
    _clean = field_validator("title", "location")(clean)


class ArrivalDispatchInput(StrictModel):
    request_id: UUID4
    task_id: UUID4
    expected_version: int = Field(ge=1)


class DeliveryInput(StrictModel):
    task_id: str = Field(max_length=42)

    @field_validator("task_id")
    @classmethod
    def dispatch_task_id(cls, value):
        if value.startswith("field-") and len(value) == 38 and all(c in "0123456789abcdef" for c in value[6:]):
            return value
        task_id = UUID(value)
        if str(task_id) != value or task_id.version != 4:
            raise ValueError("无效的任务编号")
        return value


class PhotoInput(StrictModel):
    id: UUID4
    jpeg: str = Field(min_length=4, max_length=1_400_000)


def register_field_tasks(app, field, identity):
    store, definitions = field.store, field.definitions
    with store.connect() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS field_photos (
                id TEXT PRIMARY KEY, event_id TEXT NOT NULL, task_id TEXT NOT NULL, actor TEXT NOT NULL,
                speaker TEXT NOT NULL, sent_at TEXT NOT NULL, fingerprint TEXT NOT NULL, photo BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS field_task_delivery (
                event_id TEXT NOT NULL, task_id TEXT NOT NULL, person_id TEXT NOT NULL, received_at TEXT NOT NULL,
                PRIMARY KEY(event_id,task_id,person_id)
            );
            CREATE INDEX IF NOT EXISTS field_photo_task ON field_photos(event_id,task_id,sent_at);
        """)

    def get_task(conn, event_id, task_id, user):
        _, definition = event_and_definition(conn, event_id, definitions)
        row = conn.execute("SELECT * FROM objects WHERE event_id=? AND ontology_id=? AND object_type=? AND id=?",
                           (event_id, definition["ontology_id"], definition["binding"]["task_type"], task_id)).fetchone()
        if row is None or not can_read_task(row, definition, user):
            raise HTTPException(404, "找不到可访问的任务")
        return row, definition

    def photo_summary(row):
        return {k: row[k] for k in ("id", "actor", "speaker", "sent_at")}

    @app.post("/api/field/events/{event_id}/dispatch", status_code=201)
    def dispatch(event_id: str, body: DispatchInput, user=Depends(identity)):
        field.require_dispatcher(user)
        field.require_event(event_id, user)
        key = "field-dispatch:" + str(body.request_id)
        fingerprint = digest(dump([event_id, user["actor"], body.model_dump(mode="json")]))
        with store.connect(write=True) as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            previous = conn.execute("SELECT fingerprint,data FROM receipts WHERE event_id=? AND idempotency_key=?",
                                    (event_id, key)).fetchone()
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise HTTPException(409, "此请求编号已经用于其他派单")
                return json.loads(previous["data"])
            person = conn.execute("SELECT * FROM field_people WHERE id=? AND role='staff'", (body.person_id,)).fetchone()
            if person is None or not field.event_access(conn, event_id, person["id"]):
                raise HTTPException(422, "请选择此活动中的工作人员")
            binding = definition["binding"]
            at = stamp()
            task_id = "field-" + uuid4().hex
            ref = {"id": task_id, "ontology_id": definition["ontology_id"], "object_type": binding["task_type"]}
            data = {
                binding["title"]: body.title, binding["description"]: body.location,
                binding["owner"]: person["id"], binding["subject"]: person["name"],
                binding["status"]: binding["initial_status"], binding["resource"]: None,
                binding["required_capacity"]: 0,
                binding["progress"]: [{"label": label, "status": "current" if index == 0 else "pending"}
                                      for index, label in enumerate(binding.get("field_response_milestones", ["出发", "到场", "交接"]))],
                "field_response": {"dispatched_at": at, "location": body.location,
                                   "contact": body.contact.strip(), "due": body.due.strip()},
            }
            conn.execute("INSERT INTO objects VALUES (?,?,?,?,?,?)",
                         (event_id, ref["ontology_id"], ref["object_type"], ref["id"], 1, dump(data)))
            receipt = {"id": "receipt-" + uuid4().hex, "action_type": "dispatch_task", "target_ref": ref,
                       "status": "committed", "actor": user["actor"], "speaker": user.get("name"),
                       "created_at": at, "idempotency_key": key, "proposal_id": None,
                       "result": {"owner_label": person["name"], "object_version": 1, "status_label": "待接单"}}
            conn.execute("INSERT INTO receipts (id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
                         (receipt["id"], event_id, key, fingerprint, user["actor"], dump(receipt)))
            return receipt

    @app.post("/api/field/events/{event_id}/dispatch/arrival", status_code=201)
    def release_arrival(event_id: str, body: ArrivalDispatchInput, user=Depends(identity)):
        field.require_dispatcher(user)
        field.require_event(event_id, user)
        key = "field-arrival-dispatch:" + str(body.request_id)
        fingerprint = digest(dump([event_id, user["actor"], body.model_dump(mode="json")]))
        with store.connect(write=True) as conn:
            previous = conn.execute("SELECT fingerprint,data FROM receipts WHERE event_id=? AND idempotency_key=?",
                                    (event_id, key)).fetchone()
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise HTTPException(409, "此请求编号已经用于其他派单")
                return json.loads(previous["data"])
            row, definition = get_task(conn, event_id, str(body.task_id), user)
            data = json.loads(row["data"])
            recipients = field_recipients(row, definition)
            if (not data.get("arrival_managed") or data.get("arrival_dispatched_at") or row["version"] != body.expected_version
                    or data[definition["binding"]["progress"]][0]["status"] == "done"):
                raise HTTPException(409, "接机任务已发布或状态有变化，请刷新")
            if not recipients or any(not field.event_access(conn, event_id, actor) for actor in recipients):
                raise HTTPException(422, "请先为司机和接机员分配活动频道")
            at = stamp()
            data["arrival_dispatched_at"] = at
            conn.execute("""UPDATE objects SET version=version+1,data=? WHERE event_id=? AND ontology_id=?
                         AND object_type=? AND id=?""", (dump(data), event_id, row["ontology_id"], row["object_type"], row["id"]))
            receipt = {"id": "receipt-" + uuid4().hex, "action_type": "dispatch_arrival_task",
                       "target_ref": {name: row[name] for name in ("id", "ontology_id", "object_type")},
                       "status": "committed", "actor": user["actor"], "created_at": at,
                       "idempotency_key": key, "proposal_id": None,
                       "result": {"recipients": recipients, "object_version": row["version"] + 1}}
            conn.execute("INSERT INTO receipts (id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)",
                         (receipt["id"], event_id, key, fingerprint, user["actor"], dump(receipt)))
            return receipt

    @app.get("/api/field/events/{event_id}/inbox")
    def inbox(event_id: str, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect() as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            calls = conn.execute("""
                SELECT f.id,f.channel_id,f.created_at,p.name,p.callsign FROM field_calls f
                JOIN field_people p ON p.id=f.source
                WHERE f.event_id=? AND f.recipient=? AND f.created_at>?
                ORDER BY f.created_at,f.id LIMIT 32
                """, (event_id, actor, expiry(-5))).fetchall()
            binding = definition["binding"]
            rows = conn.execute("SELECT * FROM objects WHERE event_id=? AND object_type=?",
                                (event_id, binding["task_type"])).fetchall()
            tasks = []
            for row in rows:
                data = json.loads(row["data"])
                if (data.get("field_response") and data[binding["owner"]] == actor
                        and data[binding["status"]] == binding["initial_status"]):
                    tasks.append(project_task(conn, event_id, row, definition, user))
                elif (data.get("arrival_dispatched_at") and data[binding["progress"]][0]["status"] != "done"
                      and can_read_task(row, definition, user) and actor in field_recipients(row, definition)):
                    tasks.append(project_task(conn, event_id, row, definition, user))
            assistance = []
            if user["role"] == "dispatcher":
                for message in reversed(store.records(conn, "messages", event_id)):
                    if message.get("request_kind") != "assistance":
                        continue
                    if conn.execute("SELECT 1 FROM receipts WHERE event_id=? AND idempotency_key=?",
                                    (event_id, f"qrf-ack:{message['id']}")).fetchone():
                        continue
                    assistance.append(message)
                    if len(assistance) == 32:
                        break
            conferences = conn.execute("""
                SELECT c.id,c.channel_id,c.mode,p.name FROM field_conferences c
                JOIN field_people p ON p.id=c.caller
                WHERE c.event_id=? AND c.recipient=? AND c.state='ringing' AND c.expires_at>?
                ORDER BY c.created_at DESC LIMIT 8
                """, (event_id, actor, stamp())).fetchall() if getattr(app.state, "field_conferences_enabled", False) else []
            return {"tasks": tasks, "calls": [dict(row) for row in calls], "assistance": assistance,
                    "conferences": [dict(row) for row in conferences]}

    @app.post("/api/field/events/{event_id}/received")
    def received(event_id: str, body: DeliveryInput, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect(write=True) as conn:
            row, definition = get_task(conn, event_id, body.task_id, user)
            data = json.loads(row["data"])
            if not ((data.get("field_response") and data[definition["binding"]["owner"]] == actor)
                    or (data.get("arrival_dispatched_at") and actor in field_recipients(row, definition))):
                raise HTTPException(403, "只有负责人终端能确认送达")
            conn.execute("INSERT OR IGNORE INTO field_task_delivery VALUES (?,?,?,?)",
                         (event_id, row["id"], actor, stamp()))
            received_at = conn.execute("""
                SELECT received_at FROM field_task_delivery WHERE event_id=? AND task_id=? AND person_id=?
                """, (event_id, row["id"], actor)).fetchone()["received_at"]
        return {"received_at": received_at}

    @app.post("/api/field/events/{event_id}/tasks/{task_id}/photos", status_code=201)
    def upload(event_id: str, task_id: str, body: PhotoInput, user=Depends(identity)):
        actor = field.require_person(user)
        try:
            photo = base64.b64decode(body.jpeg, validate=True)
        except (ValueError, binascii.Error):
            raise HTTPException(422, "无效的照片") from None
        if len(photo) > 1_000_000 or not photo.startswith(b"\xff\xd8\xff") or not photo.endswith(b"\xff\xd9"):
            raise HTTPException(422, "请选择 1 MB 内的 JPEG 照片")
        fingerprint = digest(body.jpeg)
        key = str(body.id)
        with store.connect(write=True) as conn:
            get_task(conn, event_id, task_id, user)
            existing = conn.execute("SELECT * FROM field_photos WHERE id=?", (key,)).fetchone()
            if existing:
                if (existing["event_id"], existing["task_id"], existing["actor"], existing["fingerprint"]) != (
                        event_id, task_id, actor, fingerprint):
                    raise HTTPException(409, "照片编号已使用")
                return photo_summary(existing)
            at = stamp()
            conn.execute("INSERT INTO field_photos VALUES (?,?,?,?,?,?,?,?)",
                         (key, event_id, task_id, actor, user["name"], at, fingerprint, photo))
            return {"id": key, "actor": actor, "speaker": user["name"], "sent_at": at}

    @app.get("/api/field/events/{event_id}/tasks/{task_id}/photos")
    def photos(event_id: str, task_id: str, user=Depends(identity)):
        field.require_person(user)
        with store.connect() as conn:
            get_task(conn, event_id, task_id, user)
            rows = conn.execute("""SELECT id,actor,speaker,sent_at FROM field_photos
                                WHERE event_id=? AND task_id=? ORDER BY sent_at DESC,id DESC""",
                                (event_id, task_id)).fetchall()
            return {"items": [photo_summary(row) for row in rows]}

    @app.get("/api/field/events/{event_id}/tasks/{task_id}/photos/{photo_id}")
    def download(event_id: str, task_id: str, photo_id: UUID4, user=Depends(identity)):
        field.require_person(user)
        with store.connect() as conn:
            get_task(conn, event_id, task_id, user)
            row = conn.execute("SELECT photo FROM field_photos WHERE id=? AND event_id=? AND task_id=?",
                               (str(photo_id), event_id, task_id)).fetchone()
            if row is None:
                raise HTTPException(404, "照片不存在")
            return Response(row["photo"], media_type="image/jpeg", headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
