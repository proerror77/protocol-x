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

from .actions import execute_task_action
from .arrival_desk import ArrivalDesk, register_arrival_desk
from .bindings import (can_read_task, event_and_definition, load_definitions, ref_of, require_task, resolve, task_rows)
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
from .flight_dispatch import ENGINE
from .foundry_bridge import FoundryBridge, FoundryBridgeSettings, register_foundry_bridge
from .pickup_scenario import register_pickup_scenario
from .pickup_watch import PickupWatch, register_pickup_watch
from .pilot import PilotSettings, pilot_lifespan, register_ptt
from .qrf import register_qrf
from .ptt_agent import visible_messages
from .rns_envelopes import EnvelopeProcessor, register_rns_envelopes
from .rns_gateway import RnsGateway, RnsGatewaySettings, register_rns_gateway, rns_gateway_lifespan
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
        {"id": "external", "label": "外部信息", "status": "configured_unverified", "detail": "航班页读取香港机场到港，接待卡提供 Google Maps 路线与 Uber 跳转。"}
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
    foundry_settings = FoundryBridgeSettings.from_env()
    foundry_bridge = FoundryBridge(foundry_settings) if foundry_settings else None
    reception = ArrivalDesk(store, definitions, pickup, field, foundry_bridge=foundry_bridge)
    from .bindings import candidates, project_task

    channel_wakeups = ChannelWakeups((store.path, voice_archive.path if voice_archive else None))
    rns_settings = RnsGatewaySettings.from_env()
    rns_gateway = None

    @asynccontextmanager
    async def lifespan(app):
        async with pilot_lifespan(settings)(app), pickup.running(bool(settings.tokens)), changes.running(), channel_wakeups.running(), rns_gateway_lifespan(rns_gateway):
            yield

    app = FastAPI(title="Operations Runtime", version="0.5.0", lifespan=lifespan)
    app.state.store = store
    app.state.reception = reception
    app.state.definitions = definitions
    app.state.pilot_settings = settings
    app.state.field = field
    app.state.voice_archive = voice_archive
    app.state.channel_wakeups = channel_wakeups
    app.state.foundry_bridge = foundry_bridge
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
    rns_processor = EnvelopeProcessor(
        store, definitions, field,
        arrival_desk=reception, tak_transport=transport, voice_archive=voice_archive,
    )
    app.state.rns_processor = rns_processor
    register_rns_envelopes(app, identity, rns_processor)
    if rns_settings is not None:
        rns_gateway = RnsGateway(rns_settings, store, rns_processor, tak_settings=tak)
        app.state.rns_gateway = rns_gateway
        register_rns_gateway(app, identity, rns_gateway)
    register_foundry_bridge(
        app,
        store,
        channel_wakeups,
        foundry_bridge,
        tak_transport=transport,
        rns_gateway=rns_gateway,
    )
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
        active_bridge = app.state.foundry_bridge
        if active_bridge is not None and active_bridge.settings.mode == "primary":
            active_bridge.forward_task_action(event_id, body, user)
        with store.connect(write=True) as conn:
            receipt, deduplicated = execute_task_action(
                conn,
                event_id,
                definitions,
                body,
                user,
                now_fn=now,
                new_id_fn=new_id,
                enrich_conflict=False,
            )
        if active_bridge is not None and active_bridge.settings.mode == "shadow" and not deduplicated:
            active_bridge.forward_task_action(event_id, body, user)
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
