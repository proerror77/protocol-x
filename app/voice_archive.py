"""Persistent, activity-scoped audio and ASR results for one LiveKit track per press."""
from array import array
from datetime import datetime, timedelta, timezone
import re
import sys

from fastapi import Depends, HTTPException, Request, Response

from .channel_feed import feed_version, install_voice_feed
from .database import connect, enable_local_wal


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def has_ptt_signal(pcm):
    """Conservative silence gate for mono 16 kHz signed PCM, not a speech recognizer."""
    samples = array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    quiet = 0
    strong = 0
    for start in range(0, len(samples) - 319, 320):
        window = samples[start:start + 320]
        peak = max(abs(sample) for sample in window)
        energy = sum(sample * sample for sample in window)
        if peak >= 480 and energy >= 220 * 220 * 320:
            quiet += 1
        if peak >= 1200 and energy >= 600 * 600 * 320:
            strong += 1
        if quiet >= 16 or strong >= 3:
            return True
    return False


class VoiceArchive:
    def __init__(self, path):
        self.path = str(path)
        enable_local_wal(self.path)
        with self.connect(write=True) as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS voice_transmissions (
                    event_id TEXT NOT NULL, track_sid TEXT NOT NULL, actor TEXT NOT NULL,
                    created_at TEXT NOT NULL, ended_at TEXT, status TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0, partial INTEGER NOT NULL DEFAULT 0,
                    text TEXT, audio BLOB, speaker_name TEXT,
                    PRIMARY KEY(event_id, track_sid)
                );
                CREATE INDEX IF NOT EXISTS voice_recent ON voice_transmissions(event_id, created_at DESC);
            """)
            if not conn.postgres and not any(column["name"] == "speaker_name" for column in conn.execute("PRAGMA table_info(voice_transmissions)")):
                conn.execute("ALTER TABLE voice_transmissions ADD COLUMN speaker_name TEXT")
            if conn.postgres:
                conn.execute("ALTER TABLE voice_transmissions ADD COLUMN IF NOT EXISTS agent_processed INTEGER NOT NULL DEFAULT 1")
                conn.execute("ALTER TABLE voice_transmissions ADD COLUMN IF NOT EXISTS review_engine TEXT")
            else:
                columns = {column["name"] for column in conn.execute("PRAGMA table_info(voice_transmissions)")}
                if "agent_processed" not in columns:
                    conn.execute("ALTER TABLE voice_transmissions ADD COLUMN agent_processed INTEGER NOT NULL DEFAULT 1")
                if "review_engine" not in columns:
                    conn.execute("ALTER TABLE voice_transmissions ADD COLUMN review_engine TEXT")

            install_voice_feed(conn)

    def connect(self, write=False):
        return connect(self.path, write=write)

    def start(self, event_id, track_sid, actor, speaker=None, review=False):
        with self.connect(write=True) as conn:
            return conn.execute("""
                INSERT INTO voice_transmissions(event_id, track_sid, actor, created_at, speaker_name, status, agent_processed)
                VALUES(?, ?, ?, ?, ?, 'recording', ?) ON CONFLICT DO NOTHING
            """, (event_id, track_sid, actor, timestamp(), speaker, int(not review))).rowcount == 1

    def finish(self, event_id, track_sid, audio, duration_ms, partial, *, silent=False):
        status = "silent" if silent else "pending" if audio else "unavailable"
        with self.connect(write=True) as conn:
            conn.execute("""
                UPDATE voice_transmissions
                SET audio=?, ended_at=?, duration_ms=?, partial=?, status=?
                WHERE event_id=? AND track_sid=? AND status='recording'
            """, (None if silent else audio, timestamp(), duration_ms, int(partial), status, event_id, track_sid))

    def recover(self, event_id):
        with self.connect(write=True) as conn:
            conn.execute("""
                UPDATE voice_transmissions SET status='unavailable', partial=1, ended_at=?
                WHERE event_id=? AND status='recording'
            """, (timestamp(), event_id))

    def next_pending(self, event_id):
        with self.connect() as conn:
            return conn.execute("""
                SELECT track_sid, audio FROM voice_transmissions
                WHERE event_id=? AND status='pending' ORDER BY created_at LIMIT 1
            """, (event_id,)).fetchone()

    def complete(self, event_id, track_sid, status, text=None, engine=None):
        with self.connect(write=True) as conn:
            conn.execute("""
                UPDATE voice_transmissions SET status=?, text=?, review_engine=?
                WHERE event_id=? AND track_sid=? AND status='pending'
            """, (status, text, engine, event_id, track_sid))

    def next_incident_review(self, scope):
        with self.connect() as conn:
            return conn.execute("""
                SELECT track_sid,actor,text,review_engine FROM voice_transmissions
                WHERE event_id=? AND status='ready' AND partial=0 AND agent_processed=0
                ORDER BY created_at,track_sid LIMIT 1
            """, (scope,)).fetchone()

    def finish_incident_review(self, scope, track_sid):
        with self.connect(write=True) as conn:
            conn.execute("UPDATE voice_transmissions SET agent_processed=1 WHERE event_id=? AND track_sid=?", (scope, track_sid))

    def recent(self, event_id):
        with self.connect() as conn:
            rows = conn.execute("""
                SELECT track_sid, actor, speaker_name, created_at, ended_at, status, duration_ms,
                       partial, text, review_engine, CASE WHEN audio IS NOT NULL THEN 1 ELSE 0 END AS has_audio
                FROM voice_transmissions WHERE event_id=? AND status NOT IN ('recording','silent')
                ORDER BY created_at DESC LIMIT 200
            """, (event_id,))
            return [dict(row) for row in rows]

    def page(self, scope, before=""):
        moment, sep, track = before.partition("|")
        if before and (not sep or not moment or not track or len(before) > 200):
            raise HTTPException(422, "无效的历史记录位置")
        with self.connect() as conn:
            rows = conn.execute("""
                SELECT track_sid, actor, speaker_name, created_at, ended_at, status, duration_ms,
                       partial, text, review_engine, CASE WHEN audio IS NOT NULL THEN 1 ELSE 0 END AS has_audio
                FROM voice_transmissions WHERE event_id=? AND status NOT IN ('recording','silent') AND (?!='' AND
                    (created_at<? OR (created_at=? AND track_sid<?)) OR ?='')
                ORDER BY created_at DESC,track_sid DESC LIMIT 31
                """, (scope, before, moment, moment, track, before)).fetchall()
        items = [dict(row) for row in rows[:30]]
        last = items[-1] if len(rows) > 30 else None
        return items, (last["created_at"] + "|" + last["track_sid"] if last else None)

    def feed(self, scope, since):
        with self.connect() as conn:
            current = feed_version(conn, scope, "voice")
            if since < feed_version(conn, scope, "voice_floor") or since > current:
                return current, None, []
            rows = conn.execute("""
                SELECT f.track_sid,f.revision,v.actor,v.speaker_name,v.created_at,v.ended_at,v.status,
                       v.duration_ms,v.partial,v.text,v.review_engine,CASE WHEN v.audio IS NOT NULL THEN 1 ELSE 0 END AS has_audio
                FROM voice_feed_entries f LEFT JOIN voice_transmissions v
                    ON v.event_id=f.scope AND v.track_sid=f.track_sid
                    AND v.status NOT IN ('recording','silent')
                WHERE f.scope=? AND f.revision>? ORDER BY f.revision LIMIT 61
            """, (scope, since)).fetchall()
        items = []
        removed = []
        for entry in rows[:60]:
            if entry["status"] is None:
                removed.append(entry["track_sid"])
            else:
                item = dict(entry)
                item.pop("revision")
                items.append(item)
        return (rows[59]["revision"] if len(rows) > 60 else current), items, removed

    def audio(self, event_id, track_sid):
        with self.connect() as conn:
            row = conn.execute("""
                SELECT audio FROM voice_transmissions WHERE event_id=? AND track_sid=?
            """, (event_id, track_sid)).fetchone()
            return row["audio"] if row else None

    def prune_all(self):
        with self.connect() as conn:
            rooms = [row["event_id"] for row in conn.execute(
                "SELECT event_id FROM voice_transmissions UNION SELECT scope FROM voice_feed_entries"
            )]
        for room in rooms:
            self.prune(room)

    def prune(self, event_id):
        now = datetime.now(timezone.utc)
        audio_cutoff = (now - timedelta(days=7)).isoformat()
        transcript_cutoff = (now - timedelta(days=30)).isoformat()
        with self.connect(write=True) as conn:
            all_rows = "ALL" if conn.postgres else "-1"
            conn.execute("""
                DELETE FROM voice_transmissions WHERE event_id=? AND status!='recording' AND created_at<?
            """, (event_id, transcript_cutoff))
            conn.execute(f"""
                UPDATE voice_transmissions SET audio=NULL
                WHERE event_id=? AND audio IS NOT NULL AND status NOT IN ('recording','pending') AND (
                    created_at < ? OR track_sid IN (
                        SELECT track_sid FROM voice_transmissions WHERE event_id=? AND audio IS NOT NULL
                        AND status NOT IN ('recording','pending')
                        ORDER BY created_at DESC,track_sid DESC LIMIT {all_rows} OFFSET 200
                    )
                )
            """, (event_id, audio_cutoff, event_id))
            floor = conn.execute("""SELECT MAX(revision) AS revision FROM voice_feed_entries
                                   WHERE scope=? AND changed_at<?""", (event_id, transcript_cutoff)).fetchone()["revision"]
            if floor is not None:
                conn.execute("DELETE FROM voice_feed_entries WHERE scope=? AND changed_at<?", (event_id, transcript_cutoff))
                conn.execute("""INSERT INTO channel_feed_versions VALUES (?,'voice_floor',?)
                                ON CONFLICT(scope,kind) DO UPDATE SET version=excluded.version""", (event_id, floor))


def audio_response(audio, range_header):
    payload = bytes(audio)
    length = len(payload)
    base_headers = {"Cache-Control": "private, no-store", "Accept-Ranges": "bytes"}
    if not range_header:
        return Response(payload, media_type="audio/wav", headers=base_headers)
    match = re.fullmatch(r"bytes=([0-9]{0,18})-([0-9]{0,18})", range_header.strip())
    if match and (match[1] or match[2]):
        if match[1]:
            first = int(match[1])
            last = min(int(match[2]), length - 1) if match[2] else length - 1
        else:
            first, last = max(0, length - int(match[2])), length - 1
        if first <= last:
            return Response(payload[first:last + 1], status_code=206, media_type="audio/wav",
                            headers={**base_headers, "Content-Range": f"bytes {first}-{last}/{length}"})
    return Response(status_code=416, headers={**base_headers, "Content-Range": f"bytes */{length}"})



def register_voice_archive(app, archive, pilot_event_id, identity):
    @app.get("/api/events/{event_id}/voice/{track_sid}/audio")
    def original_audio(event_id: str, track_sid: str, request: Request, _user=Depends(identity)):
        if event_id != pilot_event_id or "device" in _user:
            raise HTTPException(404, "未找到这条语音")
        audio = archive.audio(event_id, track_sid)
        if audio is None:
            raise HTTPException(404, "原音已过期或暂不可用")
        return audio_response(audio, request.headers.get("range"))
