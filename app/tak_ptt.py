"""Bridge verified LiveKit microphone lifecycle events to OpenTAKServer."""
import re
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException, Request, Response
from starlette.concurrency import run_in_threadpool

from .tak import cot_time, record_delivery

PARTICIPANT = re.compile(
    r"(demo-staff|demo-dispatcher)-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z"
)
EVENT_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
MAX_WEBHOOK_BYTES = 65536
MICROPHONE_EVENTS = {
    "track_published": ("started", "开启"),
    "track_unpublished": ("ended", "关闭"),
}


def register_tak_ptt(app, store, transport, media):
    from livekit import api

    receiver = api.WebhookReceiver(api.TokenVerifier(media.key, media.secret, leeway=timedelta(0)))

    @app.post("/internal/ptt/livekit-events", include_in_schema=False, status_code=204)
    async def livekit_events(request: Request):
        token = request.headers.get("Authorization", "").strip()
        if token.lower().startswith("bearer "):
            token = token[7:]
        if not token:
            raise HTTPException(401, "需要 LiveKit Webhook 签名")

        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > MAX_WEBHOOK_BYTES:
                raise HTTPException(413, "LiveKit Webhook 超出大小限制")
            body.extend(chunk)
        try:
            event = receiver.receive(body.decode("utf-8"), token)
        except Exception:
            # The SDK also raises plain Exception for a missing or invalid body hash.
            raise HTTPException(401, "LiveKit Webhook 验签失败") from None

        participant = PARTICIPANT.fullmatch(event.participant.identity)
        if (event.event not in MICROPHONE_EVENTS or event.room.name != media.room
                or event.track.type != api.TrackType.AUDIO or event.track.source != api.TrackSource.MICROPHONE
                or not participant or not EVENT_ID.fullmatch(event.id) or not EVENT_ID.fullmatch(event.track.sid)
                or event.created_at <= 0):
            return Response(status_code=204)

        actor, session = participant.groups()
        phase, label = MICROPHONE_EVENTS[event.event]
        role = "工作人员" if actor == "demo-staff" else "调度员"
        try:
            observed_at = cot_time(datetime.fromtimestamp(event.created_at, timezone.utc))
        except (ValueError, OverflowError, OSError):
            raise HTTPException(400, "LiveKit Webhook 时间无效") from None
        content = {
            "text": f"PTT 麦克风音轨{label} · {role}",
            "event": {"phase": phase, "actor": actor, "session_id": session, "track_sid": event.track.sid,
                      "room": event.room.name, "livekit_event_id": event.id, "observed_at": observed_at},
        }
        result = await run_in_threadpool(
            record_delivery, store, transport, media.event_id, "ptt", event.id, content, actor
        )
        if result["status"] != "received":
            raise HTTPException(503, "等待 OpenTAKServer 确认 PTT 事件")
        return Response(status_code=204)

