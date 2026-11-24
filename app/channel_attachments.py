"""Channel attachment storage and authorized access."""
import base64
import binascii
import hashlib
from struct import unpack_from

from fastapi import Depends, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response
from pydantic import UUID4
from starlette.concurrency import run_in_threadpool

from .field import stamp

PHOTO_BYTES = 750 * 1024
VIDEO_BYTES = 16 * 1024 * 1024
POSTER_BYTES = 48 * 1024
VIDEO_MS = 30_000
PAGE_SIZE = 20
MEDIA_TYPES = {"image/jpeg": PHOTO_BYTES, "video/mp4": VIDEO_BYTES}
PRIVATE_HEADERS = {"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"}
METADATA = "id,actor,speaker,created_at,caption,media_type,duration_ms"


def install_channel_attachments(conn):
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS field_channel_attachments (
            event_id TEXT NOT NULL, channel_id TEXT NOT NULL, id TEXT NOT NULL,
            actor TEXT NOT NULL, speaker TEXT NOT NULL, created_at TEXT NOT NULL,
            caption TEXT NOT NULL, media_type TEXT NOT NULL, duration_ms INTEGER,
            fingerprint TEXT NOT NULL, poster BLOB NOT NULL, content BLOB NOT NULL,
            PRIMARY KEY(event_id,channel_id,id)
        );
        CREATE INDEX IF NOT EXISTS field_channel_attachments_page
            ON field_channel_attachments(event_id,channel_id,created_at DESC,id DESC);
    """)


def attachment_page(conn, event, channel, before=None):
    params = (event, channel)
    condition = ""
    if before:
        anchor = conn.execute("""
            SELECT created_at FROM field_channel_attachments WHERE event_id=? AND channel_id=? AND id=?
        """, (*params, before)).fetchone()
        if anchor is None:
            return {"attachments": [], "attachments_before": None}
        condition = "AND (created_at,id)<(?,?)"
        params += (anchor["created_at"], before)
    rows = conn.execute(f"""
        SELECT {METADATA} FROM field_channel_attachments WHERE event_id=? AND channel_id=? {condition}
        ORDER BY created_at DESC,id DESC LIMIT ?
    """, (*params, PAGE_SIZE + 1)).fetchall()
    return {"attachments": [dict(row) for row in rows[:PAGE_SIZE]],
            "attachments_before": rows[PAGE_SIZE - 1]["id"] if len(rows) > PAGE_SIZE else None}


def mp4_boxes(content, start=0, end=None):
    end = len(content) if end is None else end
    while end - start >= 8:
        size, tag = unpack_from(">I4s", content, start)
        header = 8
        if size == 1:
            if end - start < 16:
                return
            size = unpack_from(">Q", content, start + 8)[0]
            header = 16
        if size == 0:
            size = end - start
        if not header <= size <= end - start:
            return
        yield tag, start + header, start + size
        start += size


def mp4_duration(content):
    boxes = mp4_boxes(content)
    first = next(boxes, None)
    if first is None or first[0] != b"ftyp" or first[2] - first[1] < 8:
        return None
    movie = None
    has_data = False
    for tag, start, end in boxes:
        if tag == b"moof":
            return None
        if tag == b"moov":
            movie = (start, end)
        if tag == b"mdat" and end > start:
            has_data = True
    if not movie or not has_data:
        return None

    duration = None
    has_video = False
    for tag, start, end in mp4_boxes(content, *movie):
        if tag == b"mvhd" and end > start:
            if content[start] == 0 and end - start >= 20:
                scale, ticks = unpack_from(">II", content, start + 12)
            elif content[start] == 1 and end - start >= 32:
                scale, ticks = unpack_from(">IQ", content, start + 20)
            else:
                return None
            duration = (ticks * 1000 + scale - 1) // scale if scale else None
        if tag == b"trak":
            for child, body, limit in mp4_boxes(content, start, end):
                if child == b"mdia" and any(
                    tag == b"hdlr" and end - start >= 12 and content[start + 8:start + 12] == b"vide"
                    for tag, start, end in mp4_boxes(content, body, limit)
                ):
                    has_video = True
    return duration if has_video else None


def valid_jpeg(content, max_dimension):
    if not content.startswith(b"\xff\xd8\xff") or not content.endswith(b"\xff\xd9"):
        return False
    offset = 2
    while offset + 4 <= len(content) and content[offset] == 255:
        marker = content[offset + 1]
        if marker == 255:
            offset += 1
            continue
        length = unpack_from(">H", content, offset + 2)[0]
        if length < 2 or offset + 2 + length > len(content) or marker == 0xDA:
            return False
        if marker in (0xC0, 0xC1, 0xC2) and length >= 8:
            height, width = unpack_from(">HH", content, offset + 5)
            return 0 < width <= max_dimension and 0 < height <= max_dimension
        offset += 2 + length
    return False


def register_channel_attachments(app, field, identity):
    store = field.store
    prefix = "/api/field/events/{event_id}/channels/{channel_id}/attachments"
    if field.enabled:
        with store.connect(write=True) as conn:
            install_channel_attachments(conn)

    def require_group(conn, request, event, channel, user):
        actor = field.require_person(user)
        current = field.authenticate(request.headers.get("Authorization"), conn)
        if not current or current["actor"] != actor:
            raise HTTPException(401, "设备绑定已失效")
        if field.authorized_channel(conn, event, channel, actor)["kind"] != "group":
            raise HTTPException(404, "工作群组才有频道附件")
        return current

    @app.get(prefix)
    def attachments(event_id: str, channel_id: str, request: Request, before: UUID4 | None = None,
                    user=Depends(identity)):
        with store.connect() as conn:
            require_group(conn, request, event_id, channel_id, user)
            return JSONResponse(attachment_page(conn, event_id, channel_id, str(before) if before else None),
                                headers=PRIVATE_HEADERS)

    @app.put(prefix + "/{attachment_id}")
    async def publish(
        event_id: str, channel_id: str, attachment_id: UUID4, request: Request,
        poster_bytes: int = Query(ge=1, le=POSTER_BYTES),
        caption_b64: str = Header(default="", alias="X-Channel-Caption", max_length=2700),
        user=Depends(identity),
    ):
        def authorize():
            with store.connect() as conn:
                require_group(conn, request, event_id, channel_id, user)

        await run_in_threadpool(authorize)
        media_type = request.headers.get("Content-Type")
        if media_type not in MEDIA_TYPES:
            raise HTTPException(415, "请选择 JPEG 照片或 MP4 短影片")
        try:
            caption = base64.b64decode(caption_b64, altchars=b"-_", validate=True).decode().strip()
        except (UnicodeDecodeError, binascii.Error):
            raise HTTPException(422, "附件说明无法读取") from None
        if len(caption) > 500 or "\x00" in caption:
            raise HTTPException(422, "附件说明最多 500 个字")
        limit = MEDIA_TYPES[media_type] + poster_bytes
        declared = request.headers.get("Content-Length")
        if declared and (not declared.isascii() or not declared.isdecimal() or len(declared) > 10):
            raise HTTPException(400, "附件长度无效")
        if declared and int(declared) > limit:
            raise HTTPException(413, "附件超过大小限制")
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > limit:
                raise HTTPException(413, "附件超过大小限制")
            body.extend(chunk)

        def save():
            payload = memoryview(body)
            poster = bytes(payload[:poster_bytes])
            content = bytes(payload[poster_bytes:])
            if not valid_jpeg(poster, 480):
                raise HTTPException(422, "附件封面无法读取")
            duration = None
            if media_type == "image/jpeg":
                if not valid_jpeg(content, 1600):
                    raise HTTPException(422, "照片无法读取")
            else:
                duration = mp4_duration(content)
                if duration is None or not 0 < duration <= VIDEO_MS:
                    raise HTTPException(422, "请选择不超过 30 秒的 MP4 短影片")
            checksum = f"{poster_bytes}:{hashlib.sha256(body).hexdigest()}"
            key = (event_id, channel_id, str(attachment_id))
            with store.connect(write=True) as conn:
                current = require_group(conn, request, event_id, channel_id, user)
                previous = conn.execute(f"""
                    SELECT {METADATA},fingerprint FROM field_channel_attachments
                    WHERE event_id=? AND channel_id=? AND id=?
                """, key).fetchone()
                if previous is not None:
                    if (previous["actor"], previous["media_type"], previous["caption"], previous["fingerprint"]) != (
                        current["actor"], media_type, caption, checksum
                    ):
                        raise HTTPException(409, "这个附件编号已使用")
                    item = dict(previous)
                    del item["fingerprint"]
                    return JSONResponse(item, headers=PRIVATE_HEADERS)
                item = {
                    "id": str(attachment_id), "actor": current["actor"], "speaker": current["name"],
                    "created_at": stamp(), "caption": caption, "media_type": media_type, "duration_ms": duration,
                }
                conn.execute("""
                    INSERT INTO field_channel_attachments
                    (event_id,channel_id,id,actor,speaker,created_at,caption,media_type,duration_ms,
                     fingerprint,poster,content) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, (event_id, channel_id, *item.values(), checksum, poster, content))
                if conn.postgres:
                    conn.execute("SELECT ops_bump_feed(?,'ops')", (channel_id,))
                else:
                    conn.execute("""
                        INSERT INTO channel_feed_versions VALUES (?,'ops',1)
                        ON CONFLICT(scope,kind) DO UPDATE SET version=channel_feed_versions.version+1
                    """, (channel_id,))
                return JSONResponse(item, status_code=201, headers=PRIVATE_HEADERS)

        return await run_in_threadpool(save)

    @app.get(prefix + "/{attachment_id}")
    def download(event_id: str, channel_id: str, attachment_id: UUID4, request: Request,
                 poster: bool = False, user=Depends(identity)):
        with store.connect() as conn:
            require_group(conn, request, event_id, channel_id, user)
            column = "poster" if poster else "content"
            item = conn.execute(f"""
                SELECT media_type,{column} AS media FROM field_channel_attachments
                WHERE event_id=? AND channel_id=? AND id=?
            """, (event_id, channel_id, str(attachment_id))).fetchone()
            if item is None:
                raise HTTPException(404, "找不到附件")
            return Response(item["media"], media_type="image/jpeg" if poster else item["media_type"],
                            headers=PRIVATE_HEADERS)
