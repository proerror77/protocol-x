"""Authenticated access and LiveKit integration for the synthetic-data pilot."""
import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass, replace
from datetime import timedelta
from urllib.parse import urlparse
from uuid import uuid4

from fastapi import Depends, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from pydantic import UUID4, Field, field_validator

from .models import StrictModel
from .ptt import FloorBusy, LeaseExpired, PushToTalkFloor

LOG = logging.getLogger(__name__)


def speaker_name(value):
    clean = value.strip()
    return clean if clean.isprintable() and len(clean) <= 48 else ""


@dataclass(frozen=True)
class MediaSettings:
    server_url: str
    client_url: str
    key: str
    secret: str
    event_id: str
    room: str


@dataclass(frozen=True)
class PilotSettings:
    tokens: dict
    media: MediaSettings | None
    video_enabled: bool = False

    @classmethod
    def from_env(cls):
        dispatcher = os.getenv("OPS_PILOT_DISPATCHER_TOKEN", "")
        staff = os.getenv("OPS_PILOT_STAFF_TOKEN", "")
        tokens = {}
        if dispatcher or staff:
            if dispatcher == staff or any(len(value) < 32 or not value.isascii() or not value.isalnum()
                                          for value in (dispatcher, staff)):
                raise ValueError("Configure two distinct, alphanumeric pilot tokens of at least 32 characters.")
            tokens = {"dispatcher": dispatcher, "staff": staff}
        media_fields = [os.getenv(key, "") for key in (
            "OPS_LIVEKIT_SERVER_URL", "OPS_LIVEKIT_CLIENT_URL", "OPS_LIVEKIT_API_KEY", "OPS_LIVEKIT_API_SECRET"
        )]
        if not any(media_fields):
            return cls(tokens, None)
        if not tokens or not all(media_fields):
            raise ValueError("LiveKit requires both pilot tokens and all four OPS_LIVEKIT_* settings.")
        server, client, key, secret = media_fields
        server_address = urlparse(server)
        if not server_address.hostname or server_address.scheme not in {"http", "https", "ws", "wss"}:
            raise ValueError("Set the LiveKit server URL to an HTTP(S) or WS(S) endpoint.")
        client_address = urlparse(client)
        if not client_address.hostname or client_address.scheme not in {"ws", "wss"}:
            raise ValueError("Set the LiveKit client URL to wss:// or a local ws:// endpoint.")
        if client_address.scheme == "ws" and client_address.hostname not in {"127.0.0.1", "localhost", "10.0.2.2"}:
            raise ValueError("Remote LiveKit clients require wss://.")
        event_id = os.getenv("OPS_PTT_EVENT_ID", "golf-demo")
        room = os.getenv("OPS_PTT_ROOM", f"ops-{event_id}")
        video = os.getenv("OPS_VIDEO_ENABLED", "false").lower()
        if video not in {"true", "false"}:
            raise ValueError("OPS_VIDEO_ENABLED must be true or false.")
        return cls(tokens, MediaSettings(server, client, key, secret, event_id, room), video == "true")

    def authenticate(self, authorization):
        header = authorization or ""
        scheme, _, token = header.partition(" ")
        if scheme.lower() == "bearer" and token.isascii():
            for role, expected in self.tokens.items():
                if hmac.compare_digest(expected, token):
                    # Preserve the starter's seeded staff assignment and actor IDs.
                    return {"role": role, "actor": f"demo-{role}"}
        raise HTTPException(401, "需要有效的试点访问码", headers={"WWW-Authenticate": "Bearer"})


class PressInput(StrictModel):
    session_id: UUID4
    press_id: UUID4


class JoinInput(StrictModel):
    callsign: str = Field(default="", max_length=32)

    @field_validator("callsign")
    @classmethod
    def check_callsign(cls, value):
        if value.strip() and not speaker_name(value):
            raise ValueError("对讲呼号只能包含可显示的字符")
        return value.strip()


def listener_grant_allowed(video, room):
    return (
        video is not None and video.room == room and video.room_join is True
        and video.can_subscribe is True and video.can_publish is False and video.can_publish_data is False
    )


class LiveKitPilot:
    def __init__(self, settings, api, aiohttp, jwt_error, publish_source=None):
        self.settings = settings
        self.api = api
        self.errors = (api.ServerError, aiohttp.ClientError, TimeoutError, OSError)
        self.jwt_error = jwt_error
        self.publish_source = api.TrackSource.MICROPHONE if publish_source is None else publish_source
        self.verifier = api.TokenVerifier(settings.key, settings.secret, leeway=timedelta(0))
        self.client = api.LiveKitAPI(
            url=settings.server_url, api_key=settings.key, api_secret=settings.secret,
            timeout=aiohttp.ClientTimeout(total=2),
        )

    def join(self, actor, callsign="", role=None, room=None):
        session_id = uuid4()
        room = room or self.settings.room
        role = "调度员" if actor == "demo-dispatcher" or role == "dispatcher" else "工作人员"
        name = f"{callsign} · {role}" if callsign and callsign != role else role
        token = (
            self.api.AccessToken(self.settings.key, self.settings.secret)
            .with_identity(f"{actor}-{session_id}")
            .with_name(name)
            .with_ttl(timedelta(minutes=10))
            .with_grants(self.api.VideoGrants(
                room_join=True, room=room, can_subscribe=True,
                can_publish=False, can_publish_data=False, can_update_own_metadata=False,
            ))
            .to_jwt()
        )
        return {"session_id": str(session_id), "url": self.settings.client_url,
                "room": room, "token": token}

    def allow_signal(self, authorization):
        scheme, _, token = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token:
            return False
        try:
            grant = self.verifier.verify(token).video
        except (self.jwt_error, ValueError, TypeError, AttributeError):
            return False
        return listener_grant_allowed(grant, self.settings.room)

    async def clear_publishers(self):
        try:
            result = await self.client.room.list_participants(
                self.api.ListParticipantsRequest(room=self.settings.room)
            )
        except self.api.ServerError as exc:
            if exc.code == "not_found":
                return
            raise
        for participant in result.participants:
            if participant.permission.can_publish:
                await self.set_publish_permission(participant.identity, False)

    async def set_publish_permission(self, participant_id, allowed):
        try:
            await self.client.room.update_participant(self.api.UpdateParticipantRequest(
                room=self.settings.room, identity=participant_id,
                permission=self.api.ParticipantPermission(
                    can_subscribe=True, can_publish=allowed, can_publish_data=False,
                    can_update_metadata=False,
                    can_publish_sources=[self.publish_source] if allowed else [],
                ),
            ))
        except self.api.ServerError as exc:
            if exc.code == "not_found":
                if not allowed:
                    return
                raise LeaseExpired from exc
            raise


def pilot_lifespan(settings):
    @asynccontextmanager
    async def lifespan(app):
        if settings.media is None:
            yield
            return
        import aiohttp
        from jwt import PyJWTError
        from livekit import api

        voice = LiveKitPilot(settings.media, api, aiohttp, PyJWTError)
        channels = {"ptt": (voice, PushToTalkFloor(voice))}
        if settings.video_enabled:
            video_settings = replace(settings.media, room=f"{settings.media.room}-video")
            video = LiveKitPilot(video_settings, api, aiohttp, PyJWTError, api.TrackSource.CAMERA)
            channels["video"] = (video, PushToTalkFloor(video))
        app.state.media_channels = channels

        async def monitor(media, floor):
            failed = False
            while True:
                try:
                    await floor.reap()
                    failed = False
                except media.errors as exc:
                    if not failed:
                        LOG.warning("LiveKit floor maintenance failed in %s: %s", media.settings.room, type(exc).__name__)
                    failed = True
                await asyncio.sleep(0.5)

        from .field_media import remove_expired_sessions
        field_channels = {}
        tasks = [asyncio.create_task(monitor(media, floor)) for media, floor in channels.values()]

        def open_field_media(room_name, event_id, kind):
            if room_name not in field_channels:
                scoped = replace(settings.media, event_id=event_id, room=room_name)
                source = api.TrackSource.CAMERA if kind == "video" else api.TrackSource.MICROPHONE
                media = LiveKitPilot(scoped, api, aiohttp, PyJWTError, source)
                floor = PushToTalkFloor(media)
                field_channels[room_name] = media, floor
                tasks.append(asyncio.create_task(monitor(media, floor)))
            return field_channels[room_name]

        async def revoke_disconnected_devices():
            while True:
                if app.state.field.enabled:
                    await remove_expired_sessions(app, voice)
                await asyncio.sleep(2)

        app.state.open_field_media = open_field_media
        tasks.append(asyncio.create_task(revoke_disconnected_devices()))
        try:
            yield
        finally:
            for task in tasks:
                task.cancel()
            for task in tasks:
                with suppress(asyncio.CancelledError):
                    await task
            for media, floor in [*channels.values(), *field_channels.values()]:
                try:
                    await floor.close()
                except media.errors:
                    LOG.warning("Could not revoke the active lease in %s during shutdown.", media.settings.room)
                finally:
                    await media.client.aclose()
    return lifespan


def register_ptt(app, identity, settings):
    def require_room(event_id, channel):
        if settings.media is None or settings.media.event_id != event_id:
            raise HTTPException(404, "此活动没有配置频道")
        room = app.state.media_channels.get(channel)
        if room is None:
            raise HTTPException(404, "此频道尚未启用")
        return room

    @app.get("/internal/ptt/admission", include_in_schema=False)
    async def admit_listener(request: Request):
        if settings.media is None:
            raise HTTPException(404)
        token = request.headers.get("Authorization")
        from .field_media import admit_field_listener
        if not any(media.allow_signal(token) for media, _ in app.state.media_channels.values()):
            legacy_voice, _ = app.state.media_channels["ptt"]
            if not admit_field_listener(app, legacy_voice, token):
                raise HTTPException(403, "请使用新的收听令牌加入频道")
        return Response(status_code=204)

    @app.post("/api/events/{event_id}/ptt/join")
    async def join_voice(event_id: str, body: JoinInput | None = None, user=Depends(identity)):
        if "device" in user:
            raise HTTPException(403, "绑定人员请使用分配的频道")
        media, _ = require_room(event_id, "ptt")
        return JSONResponse(media.join(user["actor"], body.callsign if body else ""), headers={"Cache-Control": "no-store"})

    @app.post("/api/events/{event_id}/video/join")
    async def join_video(event_id: str, body: JoinInput | None = None, user=Depends(identity)):
        if "device" in user:
            raise HTTPException(403, "绑定人员请使用分配的频道")
        media, _ = require_room(event_id, "video")
        return JSONResponse(media.join(user["actor"], body.callsign if body else ""), headers={"Cache-Control": "no-store"})

    @app.post("/api/events/{event_id}/ptt/floor/{operation}")
    async def change_voice_floor(event_id: str, operation: str, body: PressInput, user=Depends(identity)):
        if "device" in user:
            raise HTTPException(403, "绑定人员请使用分配的频道")
        return await change_floor(event_id, "ptt", operation, body, user)

    @app.post("/api/events/{event_id}/video/floor/{operation}")
    async def change_video_floor(event_id: str, operation: str, body: PressInput, user=Depends(identity)):
        if "device" in user:
            raise HTTPException(403, "绑定人员请使用分配的频道")
        return await change_floor(event_id, "video", operation, body, user)

    async def change_floor(event_id, channel, operation, body, user):
        if operation not in {"claim", "renew", "release"}:
            raise HTTPException(404, "未知的频道动作")
        media, floor = require_room(event_id, channel)
        participant = f"{user['actor']}-{body.session_id}"
        try:
            remaining = await getattr(floor, operation)(participant, str(body.press_id))
        except FloorBusy:
            message = "频道有人发言，请稍后再按" if channel == "ptt" else "视频频道已有画面，请稍后重试"
            raise HTTPException(409, message) from None
        except LeaseExpired:
            raise HTTPException(409, "发布授权已失效，请重新尝试或重新加入频道") from None
        except media.errors:
            raise HTTPException(503, "频道暂不可用") from None
        return {"lease_ms": remaining}
