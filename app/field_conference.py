"""Explicitly accepted one-to-one calls on a separate LiveKit room."""
from typing import Literal
from uuid import uuid4

from fastapi import Depends, HTTPException
from fastapi.responses import JSONResponse
from pydantic import Field, UUID4

from .field import digest, expiry, stamp
from .field_media import lookup_session, write_voice_log
from .models import StrictModel
from .store import dump


class Offer(StrictModel):
    request_id: UUID4
    mode: Literal["audio", "video"]


class PersonOffer(Offer):
    person_id: str = Field(pattern=r"^person-[a-f0-9]{32}$")


class Decision(StrictModel):
    action: Literal["accept", "decline", "end"]


class Publish(StrictModel):
    session_id: UUID4


def register_conferences(app, field, identity, settings):
    app.state.field_conferences_enabled = bool(field.enabled and settings.media)
    store = field.store
    if field.enabled:
        with store.connect(write=True) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS field_conferences (
                    id TEXT PRIMARY KEY, event_id TEXT NOT NULL, channel_id TEXT NOT NULL,
                    wire_room TEXT NOT NULL UNIQUE, caller TEXT NOT NULL, recipient TEXT NOT NULL,
                    mode TEXT NOT NULL CHECK(mode IN ('audio','video')), state TEXT NOT NULL,
                    created_at TEXT NOT NULL, expires_at TEXT NOT NULL, request_id TEXT NOT NULL,
                    UNIQUE(caller, request_id)
                );
                CREATE INDEX IF NOT EXISTS field_conference_inbox ON field_conferences(event_id,recipient,state);
            """)

    def find_call(conn, event_id, call_id, actor):
        row = conn.execute("""
            SELECT * FROM field_conferences WHERE id=? AND event_id=? AND (caller=? OR recipient=?)
            """, (call_id, event_id, actor, actor)).fetchone()
        if row is None:
            raise HTTPException(404, "找不到此通话")
        return row

    def projection(row, actor):
        return {"id": row["id"], "channel_id": row["channel_id"], "mode": row["mode"],
                "state": row["state"] if row["expires_at"] > stamp() else "ended",
                "incoming": row["recipient"] == actor}

    def shared_group(conn, event_id, actor, peer):
        return conn.execute("""
            SELECT 1 FROM field_channels c JOIN field_members a ON a.channel_id=c.id AND a.person_id=?
            JOIN field_members b ON b.channel_id=c.id AND b.person_id=?
            WHERE c.event_id=? AND c.kind='group' AND NOT EXISTS
                (SELECT 1 FROM field_suspensions s WHERE s.person_id IN (?,?)) LIMIT 1
            """, (actor, peer, event_id, actor, peer)).fetchone() is not None

    def ensure_media(mode):
        if not settings.media or (mode == "video" and not settings.video_enabled):
            raise HTTPException(404, "此类通话暂未启用")

    def make_offer(conn, event_id, channel_id, actor, peer, body):
        if not shared_group(conn, event_id, actor, peer):
            raise HTTPException(404, "找不到可私呼的同组成员")
        previous = conn.execute("SELECT * FROM field_conferences WHERE caller=? AND request_id=?",
                                (actor, str(body.request_id))).fetchone()
        if previous:
            if (previous["event_id"], previous["channel_id"], previous["mode"]) != (event_id, channel_id, body.mode):
                raise HTTPException(409, "此请求已用于其他通话")
            return projection(previous, actor)
        busy = conn.execute("""
            SELECT 1 FROM field_conferences WHERE state IN ('ringing','accepted') AND expires_at>?
            AND (caller IN (?,?) OR recipient IN (?,?)) LIMIT 1
            """, (stamp(), actor, peer, actor, peer)).fetchone()
        if busy:
            raise HTTPException(409, "对方正在等待或进行另一通通话")
        call_id = uuid4().hex
        conn.execute("INSERT INTO field_conferences VALUES (?,?,?,?,?,?,?,?,?,?,?)", (
            call_id, event_id, channel_id, "px-call-" + call_id, actor, peer, body.mode,
            "ringing", stamp(), expiry(0.75), str(body.request_id)))
        write_voice_log(conn, channel_id, actor, "private_ringing", "private-" + call_id + "-ringing", {"peer": peer})
        return projection(find_call(conn, event_id, call_id, actor), actor)

    @app.post("/api/field/events/{event_id}/private-calls", status_code=201)
    def call_person(event_id: str, body: PersonOffer, user=Depends(identity)):
        actor = field.require_person(user)
        ensure_media(body.mode)
        peer = body.person_id
        if actor == peer:
            raise HTTPException(422, "请选择其他成员")
        pair = sorted((actor, peer))
        channel_id = "direct-" + digest(dump([event_id, pair]))[:32]
        with store.connect(write=True) as conn:
            if not shared_group(conn, event_id, actor, peer):
                raise HTTPException(404, "找不到可私呼的同组成员")
            conn.execute("INSERT OR IGNORE INTO field_channels VALUES (?,?,?,?,?)",
                         (channel_id, event_id, "私呼", "direct", stamp()))
            conn.executemany("INSERT OR IGNORE INTO field_members VALUES (?,?)",
                             [(channel_id, person) for person in pair])
            return make_offer(conn, event_id, channel_id, actor, peer, body)

    @app.get("/api/field/events/{event_id}/private-calls/incoming")
    def incoming(event_id: str, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect() as conn:
            row = conn.execute("""
                SELECT c.*,p.name,p.callsign FROM field_conferences c JOIN field_people p ON p.id=c.caller
                WHERE c.event_id=? AND c.recipient=? AND c.state='ringing' AND c.expires_at>?
                ORDER BY c.created_at DESC LIMIT 1
                """, (event_id, actor, stamp())).fetchone()
            payload = None
            if row and shared_group(conn, event_id, actor, row["caller"]):
                payload = {**projection(row, actor), "name": row["name"], "callsign": row["callsign"]}
        return JSONResponse({"call": payload}, headers={"Cache-Control": "private, no-store"})

    @app.post("/api/field/events/{event_id}/channels/{channel_id}/conferences", status_code=201)
    def offer(event_id: str, channel_id: str, body: Offer, user=Depends(identity)):
        actor = field.require_person(user)
        ensure_media(body.mode)
        with store.connect(write=True) as conn:
            channel = field.authorized_channel(conn, event_id, channel_id, actor)
            if channel["kind"] != "direct":
                raise HTTPException(422, "请在一对一会话中拨打")
            peers = conn.execute("SELECT person_id FROM field_members WHERE channel_id=? AND person_id<>?",
                                 (channel_id, actor)).fetchall()
            if len(peers) != 1:
                raise HTTPException(409, "此会话没有唯一的通话对象")
            return make_offer(conn, event_id, channel_id, actor, peers[0]["person_id"], body)

    @app.get("/api/field/events/{event_id}/channels/{channel_id}/conferences")
    def open_call(event_id: str, channel_id: str, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect() as conn:
            field.authorized_channel(conn, event_id, channel_id, actor)
            row = conn.execute("""
                SELECT * FROM field_conferences WHERE channel_id=? AND (caller=? OR recipient=?)
                AND state IN ('ringing','accepted') AND expires_at>?
                ORDER BY created_at DESC LIMIT 1
                """, (channel_id, actor, actor, stamp())).fetchone()
        return {"call": projection(row, actor) if row else None}

    @app.get("/api/field/events/{event_id}/conferences/{call_id}")
    def status(event_id: str, call_id: str, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect() as conn:
            return projection(find_call(conn, event_id, call_id, actor), actor)

    @app.post("/api/field/events/{event_id}/conferences/{call_id}/decision")
    def decide(event_id: str, call_id: str, body: Decision, user=Depends(identity)):
        actor = field.require_person(user)
        with store.connect(write=True) as conn:
            call = find_call(conn, event_id, call_id, actor)
            if body.action in ("accept", "decline") and actor != call["recipient"]:
                raise HTTPException(403, "仅接听方可接听或拒绝")
            if call["expires_at"] <= stamp():
                return projection(call, actor)
            state = call["state"]
            if body.action == "accept" and state == "ringing":
                if not shared_group(conn, event_id, call["caller"], call["recipient"]):
                    raise HTTPException(403, "对方已不在共同工作组")
                conn.execute("UPDATE field_conferences SET state='accepted',expires_at=? WHERE id=?",
                             (expiry(120), call_id))
                state = "accepted"
            elif body.action == "decline" and state == "ringing":
                state = "declined"
            elif body.action == "end" and state in ("ringing", "accepted"):
                state = "ended"
            if state != call["state"]:
                conn.execute("UPDATE field_conferences SET state=? WHERE id=?", (state, call_id))
                write_voice_log(conn, call["channel_id"], actor, "private_" + state,
                                f"private-{call_id}-{state}", {})
            return projection(find_call(conn, event_id, call_id, actor), actor)

    def accepted(conn, event_id, call_id, user):
        if not settings.media:
            raise HTTPException(404, "通话媒体暂未启用")
        actor = field.require_person(user)
        call = find_call(conn, event_id, call_id, actor)
        if call["state"] != "accepted" or call["expires_at"] <= stamp():
            raise HTTPException(409, "等待对方接听或通话已经结束")
        if not shared_group(conn, event_id, call["caller"], call["recipient"]):
            raise HTTPException(403, "对方已不在共同工作组")
        return call

    @app.post("/api/field/events/{event_id}/conferences/{call_id}/join")
    async def join(event_id: str, call_id: str, user=Depends(identity)):
        with store.connect(write=True) as conn:
            call = accepted(conn, event_id, call_id, user)
            media, _ = app.state.media_channels["ptt"]
            joined = media.join(user["actor"], user["callsign"], user["role"], room=call["wire_room"])
            conn.execute("INSERT INTO field_radio_sessions VALUES (?,?,?,?,?,NULL)",
                         (joined["session_id"], call["channel_id"], call["wire_room"], user["device"], expiry(10)))
        return JSONResponse(joined, headers={"Cache-Control": "no-store"})

    @app.post("/api/field/events/{event_id}/conferences/{call_id}/publish")
    async def authorize(event_id: str, call_id: str, body: Publish, user=Depends(identity)):
        with store.connect() as conn:
            call = accepted(conn, event_id, call_id, user)
            session = lookup_session(conn, str(body.session_id))
            if (session is None or session["wire_room"] != call["wire_room"]
                    or session["device_hash"] != user["device"] or session["revoked_at"]):
                raise HTTPException(403, "通话会话已失效")
        media, _ = app.state.media_channels["ptt"]
        sources = [media.api.TrackSource.MICROPHONE]
        if call["mode"] == "video":
            sources.append(media.api.TrackSource.CAMERA)
        try:
            await media.client.room.update_participant(media.api.UpdateParticipantRequest(
                room=call["wire_room"], identity=f"{user['actor']}-{body.session_id}",
                permission=media.api.ParticipantPermission(can_subscribe=True, can_publish=True,
                    can_publish_data=False, can_update_metadata=False, can_publish_sources=sources),
            ))
        except media.errors:
            raise HTTPException(503, "通话媒体暂不可用") from None
        return {"status": "publish_granted"}
