"""Membership-checked media entry points for named channels and private PTT."""
import asyncio
import json
from typing import Literal

from fastapi import Depends, HTTPException, Query, Request
from starlette.concurrency import run_in_threadpool
from pydantic import Field, UUID4
from fastapi.responses import JSONResponse

from .channel_attachments import attachment_page
from .channel_feed import feed_version
from .field import Field as FieldDirectory, expiry, stamp
from uuid import uuid4
from .pilot import PressInput, listener_grant_allowed
from .ptt import FloorBusy, LeaseExpired
from .ptt_agent import channel_records, channel_updates, incident_statuses, message_for, write_instruction
from .bindings import can_read_task, event_and_definition, resolve
from .models import StrictModel
from .store import dump
from .voice_archive import audio_response


class DispatchInstruction(StrictModel):
    text: str = Field(min_length=1, max_length=500)
    task_version: int = Field(ge=1)
    request_id: UUID4


class VoicePolicyInput(StrictModel):
    mode: Literal["always", "operation"]
    enabled: bool
    request_id: UUID4


CALL_PAIR_ACTIVE = """EXISTS (
    SELECT 1 FROM field_channels g JOIN field_members a ON a.channel_id=g.id AND a.person_id=c.caller
    JOIN field_members b ON b.channel_id=g.id AND b.person_id=c.recipient
    WHERE g.event_id=c.event_id AND g.kind='group' AND NOT EXISTS (
        SELECT 1 FROM field_suspensions x WHERE x.person_id IN (c.caller,c.recipient)
    )
)"""


def write_voice_log(conn, channel, actor, action, key, detail):
    conn.execute("INSERT OR IGNORE INTO field_voice_log VALUES (?,?,?,?,?,?)",
                 (key, channel, actor, action, stamp(), dump(detail)))


def lookup_session(conn, session_id):
    return conn.execute("""
        SELECT s.*,d.person_id,d.revoked_at,d.expires_at AS device_expires
        FROM field_radio_sessions s JOIN field_devices d ON d.token_hash=s.device_hash
        WHERE s.id=? AND s.removed_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM field_voice_disconnects x WHERE x.session_id=s.id)
        """, (session_id,)).fetchone()


def admit_field_listener(app, media, token):
    if not token:
        return False
    authorization, _, jwt = token.partition(" ")
    if authorization.lower() != "bearer":
        return False
    try:
        claim = media.verifier.verify(jwt)
        if not claim.identity.startswith("person-"):
            return False
        with app.state.store.connect() as conn:
            session = lookup_session(conn, claim.identity[-36:])
            if session is None:
                return False
            now = stamp()
            channel_id = session["channel_id"]
            if session["wire_room"] == "px-" + channel_id:
                channel = conn.execute("SELECT kind FROM field_channels WHERE id=?", (channel_id,)).fetchone()
                if (channel is None or channel["kind"] != "group"
                        or not FieldDirectory.voice_policy(conn, channel_id)["enabled"]):
                    return False
            if session["wire_room"].startswith("px-call-") and not getattr(app.state, "field_conferences_enabled", False):
                return False
            if session["wire_room"].startswith("px-call-") and not conn.execute(f"""
                    SELECT 1 FROM field_conferences c WHERE wire_room=? AND state='accepted' AND expires_at>?
                    AND {CALL_PAIR_ACTIVE}
                    """, (session["wire_room"], now)).fetchone():
                return False
            return (not session["revoked_at"] and session["device_expires"] > now and session["expires_at"] > now
                    and claim.identity == f"{session['person_id']}-{session['id']}"
                    and listener_grant_allowed(claim.video, session["wire_room"]))
    except (media.jwt_error, ValueError, TypeError, AttributeError):
        return False


async def remove_expired_sessions(app, media):
    store = app.state.store
    supports_calls = getattr(app.state, "field_conferences_enabled", False)
    expired_calls = f"""OR (SUBSTR(s.wire_room,1,8)='px-call-' AND NOT EXISTS (
        SELECT 1 FROM field_conferences c WHERE c.wire_room=s.wire_room
        AND c.state='accepted' AND c.expires_at>? AND {CALL_PAIR_ACTIVE}
    ))""" if supports_calls else ""
    at = stamp()
    with store.connect() as conn:
        rows = conn.execute(f"""
            SELECT s.id,s.wire_room,d.person_id FROM field_radio_sessions s
            JOIN field_devices d ON d.token_hash=s.device_hash
            WHERE s.removed_at IS NULL AND (d.revoked_at IS NOT NULL OR d.expires_at<=? {expired_calls}
                OR EXISTS (SELECT 1 FROM field_voice_disconnects x WHERE x.session_id=s.id)
                OR (s.wire_room='px-' || s.channel_id AND EXISTS (
                    SELECT 1 FROM field_channels c WHERE c.id=s.channel_id AND c.kind='direct'
                ))
                OR (s.wire_room='px-' || s.channel_id AND EXISTS (
                    SELECT 1 FROM field_channel_voice v WHERE v.channel_id=s.channel_id AND v.enabled=0
                )))
            ORDER BY s.id LIMIT 32
            """, (at, at) if supports_calls else (at,)).fetchall()
    for row in rows:
        try:
            await media.client.room.remove_participant(media.api.RoomParticipantIdentity(
                room=row["wire_room"], identity=f"{row['person_id']}-{row['id']}",
            ))
        except media.api.ServerError as error:
            if error.code != "not_found":
                continue
        except media.errors:
            continue
        with store.connect(write=True) as conn:
            conn.execute("UPDATE field_radio_sessions SET removed_at=? WHERE id=?", (stamp(), row["id"]))
            conn.execute("DELETE FROM field_voice_disconnects WHERE session_id=?", (row["id"],))


def register_field_media(app, field, identity, settings):
    store = field.store

    def member(event_id, channel_id, user):
        actor = field.require_person(user)
        with store.connect() as conn:
            return field.authorized_channel(conn, event_id, channel_id, actor)

    def require_voice(conn, channel_id):
        if not field.voice_policy(conn, channel_id)["enabled"]:
            raise HTTPException(409, "此工作频道的语音尚未开启")

    def managed_group(conn, event_id, channel_id, user):
        field.require_dispatcher(user)
        actor = field.require_person(user)
        channel = field.authorized_channel(conn, event_id, channel_id, actor)
        if channel["kind"] != "group":
            raise HTTPException(404, "仅工作群组可配置语音")
        return channel

    @app.post("/api/field/events/{event_id}/channels/{channel_id}/voice/settings")
    def set_voice(event_id: str, channel_id: str, body: VoicePolicyInput, user=Depends(identity)):
        if body.mode == "always" and not body.enabled:
            raise HTTPException(422, "常开模式必须启用语音")
        state = {"mode": body.mode, "enabled": body.enabled}
        key = "voice-policy-" + str(body.request_id)
        with store.connect(write=True) as conn:
            managed_group(conn, event_id, channel_id, user)
            prior = conn.execute("SELECT channel_id,actor,detail FROM field_voice_log WHERE id=?", (key,)).fetchone()
            if prior:
                if prior["actor"] != user["actor"] or prior["channel_id"] != channel_id or json.loads(prior["detail"])["to"] != state:
                    raise HTTPException(409, "操作编号已被使用")
                return state
            previous = field.voice_policy(conn, channel_id)
            conn.execute("""INSERT INTO field_channel_voice VALUES (?,?,?) ON CONFLICT(channel_id)
                            DO UPDATE SET mode=excluded.mode,enabled=excluded.enabled""",
                         (channel_id, body.mode, int(body.enabled)))
            if not body.enabled:
                conn.execute("""INSERT INTO field_voice_disconnects(session_id)
                                SELECT id FROM field_radio_sessions WHERE channel_id=? AND wire_room=? AND removed_at IS NULL
                                ON CONFLICT(session_id) DO NOTHING""", (channel_id, "px-" + channel_id))
            write_voice_log(conn, channel_id, user["actor"], "voice_policy", key, {"from": previous, "to": state})
        return state

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/voice/logs")
    def voice_logs(event_id: str, channel_id: str, before: str = "", user=Depends(identity)):
        if len(before) > 180 or (before and "|" not in before):
            raise HTTPException(422, "无效的历史记录位置")
        time, _, ident = before.partition("|")
        with store.connect() as conn:
            managed_group(conn, event_id, channel_id, user)
            rows = conn.execute("""
                SELECT l.*,p.name AS speaker FROM field_voice_log l JOIN field_people p ON p.id=l.actor
                WHERE l.channel_id=? AND (?='' OR l.recorded_at<? OR (l.recorded_at=? AND l.id<?))
                ORDER BY l.recorded_at DESC,l.id DESC LIMIT 61
                """, (channel_id, before, time, time, ident)).fetchall()
        items = [{**dict(row), "detail": json.loads(row["detail"])} for row in rows[:60]]
        cursor = items[-1]["recorded_at"] + "|" + items[-1]["id"] if len(rows) > 60 else None
        return JSONResponse({"items": items, "next_before": cursor}, headers={"Cache-Control": "private, no-store"})

    def resolve_media(event_id, channel_id, kind, user):
        channel = member(event_id, channel_id, user)
        if not settings.media or kind not in ("ptt", "video"):
            raise HTTPException(404, "媒体未启用")
        if kind == "ptt":
            if channel["kind"] == "direct":
                raise HTTPException(409, "请用新版私呼，与对方接通后再通话")
            with store.connect() as conn:
                require_voice(conn, channel_id)
        if kind == "video" and (not settings.video_enabled or channel["kind"] != "group"):
            raise HTTPException(404, "此频道没有现场画面")
        room_name = "px-" + channel_id + ("-video" if kind == "video" else "")
        return room_name, app.state.open_field_media(room_name, event_id, kind)

    @app.post("/api/field/events/{event_id}/channels/{channel_id}/{kind}/join")
    async def join(event_id: str, channel_id: str, kind: str, user=Depends(identity)):
        wire, (media, _) = resolve_media(event_id, channel_id, kind, user)
        joined = media.join(user["actor"], user["callsign"], user["role"])
        with store.connect(write=True) as conn:
            if kind == "ptt":
                require_voice(conn, channel_id)
                write_voice_log(conn, channel_id, user["actor"], "join_authorized",
                                "voice-join-" + joined["session_id"], {})
            conn.execute("INSERT INTO field_radio_sessions VALUES (?,?,?,?,?,NULL)",
                         (joined["session_id"], channel_id, wire, user["device"], expiry(10)))
        return JSONResponse(joined, headers={"Cache-Control": "no-store"})

    @app.post("/api/field/events/{event_id}/channels/{channel_id}/{kind}/floor/{operation}")
    async def floor(event_id: str, channel_id: str, kind: str, operation: str, body: PressInput, user=Depends(identity)):
        if operation not in {"claim", "renew", "release"}:
            raise HTTPException(404, "未知的频道动作")
        wire, (media, controller) = resolve_media(event_id, channel_id, kind, user)
        with store.connect() as conn:
            session = lookup_session(conn, str(body.session_id))
        if (session is None or session["wire_room"] != wire or session["device_hash"] != user["device"]
                or session["revoked_at"] or session["device_expires"] <= stamp()):
            raise HTTPException(403, "设备会话已失效，请重新加入")
        participant = f"{user['actor']}-{body.session_id}"
        try:
            remaining = await getattr(controller, operation)(participant, str(body.press_id))
        except FloorBusy:
            raise HTTPException(409, "频道正忙，请稍后重试") from None
        except LeaseExpired:
            raise HTTPException(409, "发布授权已失效，请重新加入") from None
        except media.errors:
            raise HTTPException(503, "频道暂不可用") from None
        if kind == "ptt" and operation != "renew":
            with store.connect(write=True) as conn:
                write_voice_log(conn, channel_id, user["actor"], "ptt_" + operation,
                                f"voice-{body.session_id}-{body.press_id}-{operation}", {})
        return {"lease_ms": remaining}

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/changes")
    async def channel_changes(
        event_id: str,
        channel_id: str,
        request: Request,
        cursor: str = Query(default="", max_length=43, pattern=r"^(?:[0-9]{1,19}:[0-9]{1,19})?$"),
        wait: int = Query(default=0, ge=0, le=18),
        audio: bool = True,
        user=Depends(identity),
    ):
        since = tuple(map(int, cursor.split(":"))) if cursor else None
        actor = field.require_person(user)
        archive = app.state.voice_archive if audio else None
        wakeups = app.state.channel_wakeups

        def read():
            with store.connect() as conn:
                current_user = field.authenticate(request.headers.get("Authorization"), conn)
                if not current_user or current_user["actor"] != actor:
                    raise HTTPException(401, "设备绑定已失效")
                channel = field.authorized_channel(conn, event_id, channel_id, actor)
                ops = feed_version(conn, channel_id, "ops")
                voice = feed_version(conn, channel_id, "voice") if archive and archive.path == store.path else None
                if voice is None:
                    if archive:
                        with archive.connect() as audio_conn:
                            voice = feed_version(audio_conn, channel_id, "voice")
                    else:
                        voice = 0
                reset = since is None or since[0] > ops or since[1] > voice
                data = {"cursor": f"{ops}:{voice}", "changed": reset or since != (ops, voice)}
                if not data["changed"]:
                    return data
                if reset or since[0] != ops:
                    records = channel_records(conn, event_id, channel_id)
                    data.update(channel_updates(conn, event_id, channel_id, field.definitions, current_user, records))
                    data["incident_tracks"] = incident_statuses(conn, event_id, channel_id, records)
                    if channel["kind"] == "group":
                        data.update(attachment_page(conn, event_id, channel_id))
            if reset:
                items, older = archive.page(channel_id) if archive else ([], None)
                data.update({"reset": True, "enabled": archive is not None, "items": items, "next_before": older})
            elif archive and since[1] != voice:
                revision, items, removed = archive.feed(channel_id, since[1])
                data["cursor"] = f"{ops}:{revision}"
                if items is None:
                    items, older = archive.page(channel_id)
                    data.update({"voice_reset": True, "items": items, "next_before": older})
                else:
                    data.update({"items": items, "removed": removed})
            return data

        with wakeups.watch(channel_id) as signal:
            data = await run_in_threadpool(read)
            if not data["changed"] and wait:
                deadline = asyncio.get_running_loop().time() + (wait if wakeups.paths else min(wait, 2))
                while not data["changed"]:
                    remaining = deadline - asyncio.get_running_loop().time()
                    if remaining <= 0 or await request.is_disconnected():
                        break
                    try:
                        await asyncio.wait_for(signal.wait(), remaining)
                    except TimeoutError:
                        data = await run_in_threadpool(read)
                        break
                    signal.clear()
                    data = await run_in_threadpool(read)
        return JSONResponse(data, headers={"Cache-Control": "private, no-store"})

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/history")
    def history(event_id: str, channel_id: str, before: str = "", user=Depends(identity)):
        member(event_id, channel_id, user)
        archive = app.state.voice_archive
        items, next_before = archive.page(channel_id, before) if archive else ([], None)
        with store.connect() as conn:
            records = channel_records(conn, event_id, channel_id)
            incidents = incident_statuses(conn, event_id, channel_id, records)
            updates = channel_updates(conn, event_id, channel_id, field.definitions, user, records)
        for item in items:
            item["incident"] = incidents.get(item["track_sid"])
        return {"items": items, "next_before": next_before, "enabled": archive is not None, **updates}

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/incidents")
    def incidents(event_id: str, channel_id: str, user=Depends(identity)):
        member(event_id, channel_id, user)
        if user["role"] != "dispatcher":
            raise HTTPException(403, "仅调度员接收频道异常提醒")
        with store.connect() as conn:
            items = [incident for incident in incident_statuses(conn, event_id, channel_id).values()
                     if not incident["acknowledged"] and not incident["dispatched"] and incident["created_at"] > expiry(-20)]
        return {"items": items[:32]}

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/dispatch")
    def dispatch_updates(event_id: str, channel_id: str, user=Depends(identity)):
        member(event_id, channel_id, user)
        with store.connect() as conn:
            return channel_updates(conn, event_id, channel_id, field.definitions, user)

    @app.post("/api/field/events/{event_id}/channels/{channel_id}/incidents/{incident_id}/dispatch", status_code=201)
    def dispatch(event_id: str, channel_id: str, incident_id: str, body: DispatchInstruction, user=Depends(identity)):
        member(event_id, channel_id, user)
        if user["role"] != "dispatcher":
            raise HTTPException(403, "只有调度员可以发送指示")
        text = body.text.strip()
        if not text:
            raise HTTPException(422, "请填写要发给现场的指示")
        key = "instruction-" + str(body.request_id)
        with store.connect(write=True) as conn:
            channel = field.authorized_channel(conn, event_id, channel_id, user["actor"])
            if channel["kind"] != "group":
                raise HTTPException(403, "只有工作群组可下达频道指示")
            incident = message_for(conn, event_id, incident_id)
            if not incident or incident.get("voice_channel_id") != channel_id or incident.get("request_kind") != "ptt_incident":
                raise HTTPException(404, "找不到频道异常")
            if incident["subject_ref"] is None:
                raise HTTPException(409, "请先确认异常关联的任务")
            _, definition = event_and_definition(conn, event_id, field.definitions)
            task = resolve(conn, event_id, incident["subject_ref"])
            if not can_read_task(task, definition, user):
                raise HTTPException(403, "当前账号无权调度此任务")
            previous = message_for(conn, event_id, key)
            if previous:
                if (previous["author"] != user["actor"] or previous["incident_id"] != incident_id
                    or previous["text"] != text or previous["task_version"] != body.task_version):
                    raise HTTPException(409, "这个请求编号已用于其他指示")
                return previous
            if task["version"] != body.task_version:
                raise HTTPException(409, "任务分派已更新，请重新核对后发送")
            return write_instruction(conn, event_id, incident, task, definition, user, key, text)

    @app.post("/api/field/events/{event_id}/dispatch/{message_id}/receive")
    def receive_instruction(event_id: str, message_id: str, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect(write=True) as conn:
            message = message_for(conn, event_id, message_id)
            if not message or message["source"] != "dispatch_instruction" or actor not in message["recipients"]:
                raise HTTPException(404, "找不到给你的指示")
            key = "dispatch-receive:" + message_id + ":" + actor
            previous = conn.execute("SELECT data FROM receipts WHERE event_id=? AND idempotency_key=?",
                                    (event_id, key)).fetchone()
            if previous:
                return json.loads(previous["data"])
            receipt = {"id": "receipt-" + uuid4().hex, "action_type": "receive_dispatch_instruction",
                       "target_ref": message["subject_ref"], "status": "committed", "actor": actor,
                       "created_at": stamp(), "idempotency_key": key, "proposal_id": None,
                       "result": {"message_id": message_id}}
            conn.execute("INSERT INTO receipts(id,event_id,idempotency_key,fingerprint,actor,data) VALUES(?,?,?,?,?,?)",
                         (receipt["id"], event_id, key, message_id, actor, dump(receipt)))
            return receipt

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/voice/{track_sid}/audio")
    def audio(event_id: str, channel_id: str, track_sid: str, request: Request, user=Depends(identity)):
        member(event_id, channel_id, user)
        archive = app.state.voice_archive
        payload = archive.audio(channel_id, track_sid) if archive else None
        if payload is None:
            raise HTTPException(404, "原音暂不可用")
        return audio_response(payload, request.headers.get("range"))
