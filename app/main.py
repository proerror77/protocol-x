"""Operations runtime with optional authenticated pilot media."""
import hashlib
import json
import os
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import UUID4

from .arrival_desk import ArrivalDesk, register_arrival_desk
from .bindings import (allowed_actions, can_read_task, candidates, current_progress, event_and_definition,
                       load_definitions, project_task, ref_of, require_task, resolve, task_rows)
from .models import ActionInput, MessageInput, ProposalInput
from .field import Field
from .field_email import EnrollmentMail, register_field_email
from .field_passkeys import register_field_passkeys
from .field_media import register_field_media
from .channel_attachments import register_channel_attachments
from .field_nearby import register_field_nearby
from .field_conference import register_conferences
from .field_tasks import register_field_tasks
from .flights import ArrivalFeed, register_flights
from .flight_dispatch import ENGINE, check_confirmation
from .pickup_scenario import register_pickup_scenario
from .pickup_watch import PickupWatch, register_pickup_watch
from .pilot import PilotSettings, pilot_lifespan, register_ptt
from .qrf import register_qrf
from .ptt_agent import notify_vehicle_change, visible_messages
from .store import Store, dump
from .database import is_postgres
from .channel_feed import ChannelWakeups
from .tak import TakSettings, register_tak, tak_projection
from .tak_ptt import register_tak_ptt
from .voice_archive import VoiceArchive, register_voice_archive
from .voice_model_assets import register_voice_model_assets
from .workspace_changes import WorkspaceChanges

ROOT = Path(__file__).resolve().parents[1]


def now():
    return datetime.now(timezone.utc).isoformat()


def new_id(prefix):
    return f"{prefix}-{uuid.uuid4().hex}"


def identity(request: Request, x_demo_role: str = Header(default="dispatcher"), x_demo_actor: str | None = Header(default=None)):
    settings = request.app.state.pilot_settings
    if settings.tokens:
        header = request.headers.get("Authorization")
        field = request.app.state.field
        user = field.authenticate(header) or settings.authenticate(header)
        if field.email_enrollment and "device" not in user and user["role"] == "staff":
            raise HTTPException(401, "请使用 Passkey 登录")
        event_id = request.path_params.get("event_id")
        if event_id is not None:
            field.require_event(event_id, user)
            prefix = f"/api/events/{event_id}/"
            path = request.url.path
            if ("device" in user and path.startswith(prefix)
                    and path[len(prefix):].partition("/")[0] in {"voice", "ptt", "video"}):
                raise HTTPException(403, "此试点频道仅支持现有频道访问码")
        return user
    pairs = {"dispatcher": "demo-dispatcher", "staff": "demo-staff"}
    actor = x_demo_actor if x_demo_actor is not None else pairs.get(x_demo_role)
    if x_demo_role not in pairs or actor != pairs[x_demo_role]:
        raise HTTPException(403, "本机演示只支持已定义的角色与身份组合")
    return {"role": x_demo_role, "actor": actor}


def integrations(settings, tak, voice_archive=None):
    return [
        {"id": "runtime", "label": "业务运行时 / " + ("PostgreSQL" if os.getenv("OPS_DATABASE_URL") else "SQLite"), "status": "local_ready", "detail": "Ontology、任务与行动回执已持久化。"},
        {"id": "livekit", "label": "LiveKit / PTT",
         "status": "configured_unverified" if settings.media else "not_connected",
         "detail": "在 Android 工作人员端加入通话频道。实际音频须双机验证。" if settings.media else "尚未配置试点通话频道。"},
        {"id": "ai", "label": "AI / 语音转写",
         "status": "configured_unverified" if voice_archive else "not_connected",
         "detail": "通话原音和转写在活动记录内查看。" if voice_archive else "尚未配置语音转写。"},
        {"id": "tak", "label": "OpenTAKServer / CoT",
         "status": "configured_unverified" if tak else "not_connected",
         "detail": "共享回执经 OpenTAKServer 回读确认。" if tak else "尚未配置 OpenTAKServer 接收端。"},
        {"id": "external", "label": "外部信息", "status": "configured_unverified", "detail": "香港客运到港可在航班页查看，路线与天气另行接入。"}
    ]


def create_app(db_path=None):
    definitions = load_definitions(ROOT / "ontologies")
    database_url = os.getenv("OPS_DATABASE_URL", "").strip()
    if database_url and not is_postgres(database_url):
        raise ValueError("OPS_DATABASE_URL must be a PostgreSQL URL.")
    store = Store(db_path or database_url or os.environ.get("OPS_DB_PATH", ROOT / "data" / "ops.db"), definitions)
    settings = PilotSettings.from_env()
    enrollment_mail = EnrollmentMail.from_env()
    field = Field(store, definitions, bool(settings.tokens), email_enrollment=enrollment_mail is not None)
    tak = TakSettings.from_env()
    activities = {event["id"] for definition in definitions.values() for event in definition["events"]}
    if settings.media and settings.media.event_id not in activities:
        raise ValueError("OPS_PTT_EVENT_ID must be a defined activity.")
    if tak and (tak.event_id not in activities or not settings.tokens):
        raise ValueError("OpenTAKServer requires a defined activity and authenticated pilot access codes.")
    if tak and tak.ptt_events and (settings.media is None or settings.media.event_id != tak.event_id):
        raise ValueError("TAK PTT events require LiveKit and OpenTAKServer on the same pilot activity.")
    voice_url = os.getenv("OPS_PTT_VOICE_DATABASE_URL", "").strip()
    voice_path = os.getenv("OPS_PTT_VOICE_DB", "").strip()
    if voice_url and not is_postgres(voice_url):
        raise ValueError("OPS_PTT_VOICE_DATABASE_URL must be a PostgreSQL URL.")
    if database_url and voice_path and not voice_url:
        raise ValueError("Set OPS_PTT_VOICE_DATABASE_URL for a deployed PostgreSQL voice archive.")
    voice_path = voice_url or voice_path
    if voice_path and settings.media is None:
        raise ValueError("PTT voice archive requires an authenticated LiveKit pilot.")
    voice_archive = VoiceArchive(voice_path) if voice_path else None
    arrivals = ArrivalFeed()
    pickup = PickupWatch(store, arrivals, definitions)
    reception = ArrivalDesk(store, definitions, pickup, field)
    channel_wakeups = ChannelWakeups((store.path, voice_archive.path if voice_archive else None))

    @asynccontextmanager
    async def lifespan(app):
        async with pilot_lifespan(settings)(app), pickup.running(bool(settings.tokens)), changes.running(), channel_wakeups.running():
            yield

    app = FastAPI(title="Operations Runtime", version="0.5.0", lifespan=lifespan)
    app.state.store = store
    app.state.reception = reception
    app.state.definitions = definitions
    app.state.pilot_settings = settings
    app.state.field = field
    app.state.voice_archive = voice_archive
    app.state.channel_wakeups = channel_wakeups
    field.register(app, identity)
    register_field_nearby(app, field, identity)
    register_field_passkeys(app, field)
    register_field_email(app, field, identity, enrollment_mail)
    register_field_tasks(app, field, identity)
    register_qrf(app, store, definitions, identity)
    register_flights(app, identity, arrivals)
    register_pickup_watch(app, identity, pickup, definitions)
    register_arrival_desk(app, identity, reception)
    register_pickup_scenario(app, reception, field, identity)
    register_ptt(app, identity, settings)
    register_voice_model_assets(app, identity)
    register_field_media(app, field, identity, settings)
    register_channel_attachments(app, field, identity)
    register_conferences(app, field, identity, settings)
    if voice_archive:
        register_voice_archive(app, voice_archive, settings.media.event_id, identity)
    transport = register_tak(app, identity, store, tak)
    if tak and tak.ptt_events:
        register_tak_ptt(app, store, transport, settings.media)
    changes = WorkspaceChanges(store, field.enabled)
    changes.register(app, identity, activities)
    mode = "pilot" if settings.tokens else "local_demo"

    @app.get("/healthz")
    def health():
        with store.connect() as conn:
            conn.execute("SELECT 1").fetchone()
        return {"status": "ok", "mode": mode}

    @app.get("/api/bootstrap")
    def bootstrap(user=Depends(identity)):
        with store.connect() as conn:
            events = [dict(row) for row in conn.execute("SELECT * FROM events ORDER BY id DESC")
                      if "device" not in user or field.event_access(conn, row["id"], user["actor"])]
            passkey_session = conn.execute("""SELECT expires_at FROM field_devices WHERE token_hash=?
                                           AND auth_method='passkey'""", (user["device"],)).fetchone() if "device" in user else None
        return {"mode": mode, "events": events, "roles": ["dispatcher", "staff"],
                "identity": {k: v for k, v in user.items() if k != "device"}, "field_binding_enabled": field.enabled,
                "email_enrollment": field.email_enrollment,
                "passkey_session_expires_at": passkey_session["expires_at"] if passkey_session else None,
                "ptt_event_id": settings.media.event_id if settings.media else None,
                "private_calls_enabled": bool(settings.media and field.enabled),
                "video_enabled": settings.video_enabled, "voice_archive_enabled": voice_archive is not None,
                "integrations": integrations(settings, tak, voice_archive)}

    @app.get("/api/events/{event_id}/workspace")
    def workspace(event_id: str, response: Response, user=Depends(identity)):
        response.headers["Cache-Control"] = "private, no-store"
        with store.connect() as conn:
            event, definition = event_and_definition(conn, event_id, definitions)
            rows = [r for r in task_rows(conn, event_id, definition) if can_read_task(r, definition, user)]
            tasks = [project_task(conn, event_id, row, definition, user) for row in rows]
            visible_refs = {dump(t["ref"]) for t in tasks}
            messages = visible_messages(conn, store.records(conn, "messages", event_id), user)
            receipts = store.records(conn, "receipts", event_id)
            proposals = [p for p in store.records(conn, "proposals", event_id)
                         if p["engine"] != ENGINE or p["dispatcher_id"] == user["actor"]]
            if user["role"] == "staff" or "device" not in user:
                messages = [m for m in messages if m["subject_ref"] is None or dump(m["subject_ref"]) in visible_refs]
                message_ids = {message["id"] for message in messages}
                receipts = [r for r in receipts if dump(r["target_ref"]) in visible_refs or
                            (r["action_type"] == "ack_qrf_exception" and r["result"]["message_id"] in message_ids)]
                proposals = [p for p in proposals if dump(p["target_ref"]) in visible_refs]
            if user["role"] == "staff":
                proposals = []
            open_tasks = sum(not task["progress"] or any(step["status"] not in {"done", "confirmed", "completed"}
                                                       for step in task["progress"]) for task in tasks)
            return {
                "event": event,
                "ontology": {"id": definition["ontology_id"], "label": definition["label"], "schema_version": definition["schema_version"], "binding_version": definition["binding_version"], "relationships": definition.get("relationships", [])},
                "tasks": tasks, "messages": messages, "proposals": proposals, "receipts": receipts,
                "pickup_watch": pickup.project(conn, event_id, user),
                "arrival_desk": reception.project(conn, event_id, definition, user, receipts, proposals),
                "summary": {"open_tasks": open_tasks, "pending_proposals": sum(p["status"] == "proposed" for p in proposals), "messages": len(messages)},
                "voice_messages": voice_archive.recent(event_id)
                if voice_archive and event_id == settings.media.event_id and "device" not in user else [],
                "integrations": integrations(settings, tak if tak and tak.event_id == event_id else None, voice_archive),
                "tak": tak_projection(conn, tak, event_id, user)
            }

    @app.post("/api/events/{event_id}/messages", status_code=201)
    def post_message(event_id: str, body: MessageInput, user=Depends(identity),
                     idempotency_key: UUID4 | None = Header(default=None, alias="Idempotency-Key")):
        with store.connect(write=True) as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            ref = body.subject_ref.model_dump() if body.subject_ref else None
            if ref is not None:
                row = resolve(conn, event_id, ref)
                require_task(row, definition)
                if not can_read_task(row, definition, user):
                    raise HTTPException(403, "只能汇报本人负责的任务")
            message_id = new_id("msg")
            if idempotency_key:
                scope = dump([event_id, user["actor"], str(idempotency_key)])
                message_id = "msg-" + hashlib.sha256(scope.encode()).hexdigest()
                previous = conn.execute(
                    "SELECT data FROM messages WHERE id=? AND event_id=?", (message_id, event_id)
                ).fetchone()
                if previous:
                    message = json.loads(previous["data"])
                    if message["text"] != body.text or message["subject_ref"] != ref:
                        raise HTTPException(409, "汇报幂等键已经用于其他内容")
                    return message
            message = {"id": message_id, "author": user["actor"], "text": body.text, "created_at": now(), "source": "text", "subject_ref": ref}
            conn.execute("INSERT INTO messages(id,event_id,data) VALUES (?,?,?)", (message["id"], event_id, dump(message)))
        return message

    @app.post("/api/events/{event_id}/proposals", status_code=201)
    def propose(event_id: str, body: ProposalInput, user=Depends(identity)):
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可生成行动建议")
        with store.connect(write=True) as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            existing = conn.execute("SELECT data FROM proposals WHERE event_id=? AND message_id=?", (event_id, body.message_id)).fetchone()
            if existing:
                return json.loads(existing["data"])
            stored_message = conn.execute("SELECT data FROM messages WHERE event_id=? AND id=?", (event_id, body.message_id)).fetchone()
            if stored_message is None:
                raise HTTPException(404, "消息不属于当前活动或不存在")
            message = json.loads(stored_message["data"])
            if message["subject_ref"] is None:
                raise HTTPException(422, "消息必须明确关联一项任务；不会猜测对象")
            if not any(word in message["text"].lower() for word in ("故障", "延误", "不可用", "无法使用", "unavailable", "broken", "delayed")):
                raise HTTPException(422, "规则演示仅识别故障、延误或不可用汇报；没有生成建议")
            row = resolve(conn, event_id, message["subject_ref"])
            require_task(row, definition)
            if not can_read_task(row, definition, user):
                raise HTTPException(403, "此身份无权访问此任务")
            options = candidates(conn, event_id, row, definition)
            if not options:
                raise HTTPException(409, "没有满足容量和预留条件的备用资源")
            option = options[0]
            proposal = {
                "id": new_id("proposal"), "title": f"建议{definition['binding']['replace_label']}：{option['label']}",
                "reason": "确定性规则根据关联任务与异常关键词提出候选；汇报尚未核实，必须由调度员确认。",
                "engine": "deterministic_demo", "message_id": message["id"], "evidence_quote": message["text"],
                "status": "proposed", "target_ref": ref_of(row), "expected_version": row["version"],
                "action_type": "replace_resource", "parameters": {"replacement_ref": option["ref"]}, "created_at": now()
            }
            conn.execute("INSERT INTO proposals(id,event_id,message_id,data) VALUES (?,?,?,?)", (proposal["id"], event_id, message["id"], dump(proposal)))
        return proposal

    @app.post("/api/events/{event_id}/actions")
    def action(event_id: str, body: ActionInput, user=Depends(identity)):
        if body.action_type in {"replace_resource", "reschedule_pickup"} and user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可更改接机安排")
        action_request = body.model_dump(exclude_none=True)
        owner = {"actor": user["actor"], "role": user["role"]}
        fingerprint = hashlib.sha256(dump({"request": action_request, "identity": owner}).encode()).hexdigest()
        with store.connect(write=True) as conn:
            _, definition = event_and_definition(conn, event_id, definitions)
            existing = conn.execute("SELECT * FROM receipts WHERE event_id=? AND idempotency_key=?", (event_id, body.idempotency_key)).fetchone()
            if existing:
                legacy = hashlib.sha256(dump({"request": action_request, "identity": user}).encode()).hexdigest()
                if existing["fingerprint"] not in (fingerprint, legacy):
                    raise HTTPException(409, "幂等键已用于不同身份或不同请求")
                return json.loads(existing["data"])
            ref = body.target_ref.model_dump()
            row = resolve(conn, event_id, ref)
            require_task(row, definition)
            if not can_read_task(row, definition, user):
                raise HTTPException(403, "只能操作本人负责的任务")
            if row["version"] != body.expected_version:
                raise HTTPException(409, "对象版本已变化，请刷新任务后重新确认")
            if body.action_type not in {a["id"] for a in allowed_actions(row, definition, user)}:
                raise HTTPException(409, "当前状态不允许此行动")
            proposal = None
            if body.proposal_id:
                stored_proposal = conn.execute("SELECT data FROM proposals WHERE event_id=? AND id=?", (event_id, body.proposal_id)).fetchone()
                if stored_proposal is None:
                    raise HTTPException(404, "建议不属于当前活动或不存在")
                proposal = json.loads(stored_proposal["data"])
                params = body.parameters.model_dump(exclude_none=True)
                if (proposal["status"] != "proposed" or proposal["target_ref"] != ref or
                    proposal["action_type"] != body.action_type or proposal["parameters"] != params or
                    proposal["expected_version"] != body.expected_version):
                    raise HTTPException(409, "执行请求与待确认建议不一致或建议已经过期")
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
                    raise HTTPException(409, "候选资源不可用、已被预留、容量不足或类型不匹配")
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
                    raise HTTPException(409, "只能确认当前现场节点，请刷新任务")
                progress[index]["status"] = "done"
                following = next((step for step in progress[index + 1:] if step["status"] == "pending"), None)
                if following is not None:
                    following["status"] = "current"
                progress_result = {"progress_index": index, "progress_label": progress[index]["label"]}
                domain_action = binding["domain_confirm_progress_action"]
                note = "操作员已确认此现场节点，回执记录确认身份和系统写入时间。"
            conn.execute("UPDATE objects SET data=?, version=version+1 WHERE event_id=? AND ontology_id=? AND object_type=? AND id=? AND version=?",
                         (dump(data), event_id, ref["ontology_id"], ref["object_type"], ref["id"], body.expected_version))
            updated = resolve(conn, event_id, ref)
            projected = project_task(conn, event_id, updated, definition, user)
            receipt = {
                "id": new_id("receipt"), "action_type": body.action_type, "target_ref": ref,
                "status": "committed", "actor": user["actor"], "created_at": now(),
                **({"speaker": user["name"]} if "name" in user else {}),
                "idempotency_key": body.idempotency_key, "proposal_id": body.proposal_id,
                "result": {"object_version": updated["version"], "domain_action": domain_action,
                           "resource_label": projected["resource_label"], "status_label": projected["status_label"],
                           "service_progress_unchanged": not progress_result, "note": note, **progress_result}
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
                conn.execute("UPDATE proposals SET data=? WHERE id=? AND event_id=?", (dump(proposal), proposal["id"], event_id))
            conn.execute("INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES (?,?,?,?,?,?)", (receipt["id"], event_id, body.idempotency_key, fingerprint, user["actor"], dump(receipt)))
        return receipt

    web = ROOT / "web"
    if web.is_dir():
        app.mount("/static", StaticFiles(directory=web), name="static")

    @app.get("/arrivals", include_in_schema=False)
    @app.get("/", include_in_schema=False)
    def index():
        path = web / "index.html"
        if not path.is_file():
            raise HTTPException(503, "Web 界面尚未就绪；API 文档见 /docs")
        return FileResponse(path)

    return app


app = create_app()
