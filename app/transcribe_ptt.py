"""Subscribe to pilot microphone tracks and run Whisper locally off the PTT path."""
import asyncio
import io
import logging
import os
import re
import sqlite3

from pathlib import Path
import signal
import wave
from datetime import datetime, timedelta, timezone
from contextlib import closing

import numpy as np
from faster_whisper import WhisperModel
from faster_whisper.vad import VadOptions, get_speech_timestamps
from livekit import api, rtc

from .pilot import speaker_name
from .tak_ptt import EVENT_ID
from .voice_archive import VoiceArchive, has_ptt_signal
from .database import connect, is_postgres
from .bindings import load_definitions
from .aliyun_asr import AliyunASR
from .ptt_agent import record_incident

LOG = logging.getLogger(__name__)
ARCHIVE_PARTICIPANT = re.compile(r"(demo-staff|demo-dispatcher|person-[a-f0-9]{32})-([0-9a-f-]{36})\Z")
SAMPLE_RATE = 16_000
LIMIT = SAMPLE_RATE * 2 * 90
PTT_VAD = VadOptions(threshold=0.74, min_speech_duration_ms=160, min_silence_duration_ms=160, speech_pad_ms=0)


def wav(pcm):
    output = io.BytesIO()
    with wave.open(output, "wb") as stream:
        stream.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
        stream.writeframes(pcm)
    return output.getvalue()


def contains_ptt_speech(pcm):
    if not pcm:
        return False
    samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
    try:
        return any(has_ptt_signal(memoryview(pcm)[span["start"] * 2:span["end"] * 2])
                   for span in get_speech_timestamps(samples, PTT_VAD))
    except Exception as error:
        LOG.warning("PTT activity detection unavailable: %s", type(error).__name__)
        return has_ptt_signal(pcm)


async def capture(track, archive, event_id, track_sid, ended):
    pcm = bytearray()
    partial = False
    stream = rtc.AudioStream(track, sample_rate=SAMPLE_RATE, num_channels=1, capacity=128)

    async def read_frames():
        nonlocal partial
        async for item in stream:
            remaining = LIMIT - len(pcm)
            samples = item.frame.data.cast("B")
            pcm.extend(samples[:remaining])
            if len(samples) >= remaining:
                partial = True
                return

    reading = asyncio.create_task(read_frames())
    waiting = asyncio.create_task(ended.wait())
    try:
        async with asyncio.timeout(100):
            await asyncio.wait((reading, waiting), return_when=asyncio.FIRST_COMPLETED)
            if ended.is_set():
                # Allow already-arriving RTP frames to drain after signaling unpublish.
                await asyncio.wait({reading}, timeout=0.25)
            if reading.done():
                reading.result()
    except asyncio.CancelledError:
        partial = True
        raise
    except Exception as exc:
        partial = True
        LOG.warning("Audio stream ended early for %s: %s", track_sid, type(exc).__name__)
    finally:
        try:
            for task in (reading, waiting):
                task.cancel()
            await asyncio.gather(reading, waiting, return_exceptions=True)
            silent = not partial and not await asyncio.to_thread(contains_ptt_speech, pcm)
            archive.finish(event_id, track_sid, wav(pcm) if pcm and not silent else None,
                           len(pcm) * 1000 // (SAMPLE_RATE * 2), partial, silent=silent)
        finally:
            await stream.aclose()


def waveform_from_wav(audio):
    with wave.open(io.BytesIO(audio), "rb") as source:
        if (source.getframerate(), source.getnchannels(), source.getsampwidth()) != (SAMPLE_RATE, 1, 2):
            raise ValueError("Expected mono 16-bit PCM at 16 kHz")
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
    return samples.astype(np.float32) / 32768.0


def transcribe(model, audio):
    waveform = waveform_from_wav(audio)
    segments, _ = model.transcribe(waveform, task="transcribe", beam_size=2,
                                   vad_filter=True, condition_on_previous_text=False)
    return " ".join(part.text.strip() for part in segments if part.text.strip())


def field_transcriber(fallback):
    directory = os.getenv("OPS_PTT_SENSEVOICE_MODEL_DIR", "").strip()
    if not directory:
        return None
    try:
        import sherpa_onnx

        source = Path(directory)
        recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(source / "model.int8.onnx"), tokens=str(source / "tokens.txt"),
            num_threads=1, use_itn=True, language="auto")
    except Exception as exc:
        LOG.error("Field ASR model unavailable, using Whisper: %s", type(exc).__name__)
        return None
    LOG.info("Field channel ASR ready: SenseVoice int8 (local CPU)")

    def recognize(audio):
        samples = waveform_from_wav(audio)
        if len(samples) < SAMPLE_RATE // 4 or not np.any(np.abs(samples) > 0.006):
            return "", "sensevoice"
        try:
            stream = recognizer.create_stream()
            stream.accept_waveform(SAMPLE_RATE, samples)
            recognizer.decode_stream(stream)
            return stream.result.text.strip(), "sensevoice"
        except Exception as exc:
            LOG.warning("Field ASR failed, using Whisper: %s", type(exc).__name__)
            return transcribe(fallback, audio), "whisper"

    return recognize


async def process_pending(archive, event_id, model, review=None, recognize=None):
    while True:
        if review:
            awaiting = archive.next_incident_review(event_id)
            if awaiting:
                try:
                    await asyncio.to_thread(review, awaiting)
                except Exception as exc:
                    LOG.warning("Channel incident review will retry for %s: %s", awaiting["track_sid"], type(exc).__name__)
                    await asyncio.sleep(2)
                else:
                    archive.finish_incident_review(event_id, awaiting["track_sid"])
        item = archive.next_pending(event_id)
        if item is None:
            await asyncio.sleep(0.4 if review else 1)
            continue
        try:
            if recognize:
                text, engine = await asyncio.to_thread(recognize, item["audio"])
            else:
                text, engine = await asyncio.to_thread(transcribe, model, item["audio"]), "whisper"
        except Exception as exc:
            LOG.warning("Transcription failed for %s: %s", item["track_sid"], type(exc).__name__)
            archive.complete(event_id, item["track_sid"], "failed")
            continue
        archive.complete(event_id, item["track_sid"], "ready" if text else "no_speech", text or None, engine=engine)
        LOG.info("Transcription stored for %s", item["track_sid"])


async def run_room(archive, model, event_id, room_name, review=None, recognize=None):
    url = os.environ["OPS_LIVEKIT_SERVER_URL"].replace("http", "ws", 1)
    token = (
        api.AccessToken(os.environ["OPS_LIVEKIT_API_KEY"], os.environ["OPS_LIVEKIT_API_SECRET"])
        .with_identity(f"ops-transcription-{event_id}")
        .with_ttl(timedelta(minutes=10))
        .with_grants(api.VideoGrants(room_join=True, room=room_name, hidden=True,
                                    can_subscribe=True, can_publish=False, can_publish_data=False))
        .to_jwt()
    )
    archive.recover(event_id)
    room = rtc.Room()
    stopped = asyncio.Event()
    captures = {}
    def subscribe(publication, participant):
        if publication.source == rtc.TrackSource.SOURCE_MICROPHONE and ARCHIVE_PARTICIPANT.fullmatch(participant.identity):
            publication.set_subscribed(True)

    @room.on("track_published")
    def published(publication, participant):
        subscribe(publication, participant)

    @room.on("track_subscribed")
    def subscribed(track, publication, participant):
        identity = ARCHIVE_PARTICIPANT.fullmatch(participant.identity)
        if (not identity or publication.source != rtc.TrackSource.SOURCE_MICROPHONE
                or not EVENT_ID.fullmatch(publication.sid)):
            return
        sid = publication.sid
        if archive.start(event_id, sid, identity.group(1), speaker_name(participant.name) or None, review=review is not None):
            ended = asyncio.Event()
            task = asyncio.create_task(capture(track, archive, event_id, sid, ended))
            captures[sid] = (task, ended)
            task.add_done_callback(lambda _task: captures.pop(sid, None))

    def finish_track(publication):
        active = captures.get(publication.sid)
        if active:
            active[1].set()

    @room.on("track_unsubscribed")
    def unsubscribed(_track, publication, _participant):
        finish_track(publication)

    @room.on("track_unpublished")
    def unpublished(publication, _participant):
        finish_track(publication)

    @room.on("disconnected")
    def disconnected(_reason):
        stopped.set()

    await room.connect(url, token, rtc.RoomOptions(auto_subscribe=False))
    for participant in room.remote_participants.values():
        for publication in participant.track_publications.values():
            subscribe(publication, participant)
    LOG.info("Transcription listening on pilot room %s", room_name)
    worker = asyncio.create_task(process_pending(archive, event_id, model, review, recognize))
    shutdown = asyncio.create_task(stopped.wait())
    try:
        await asyncio.wait((worker, shutdown), return_when=asyncio.FIRST_COMPLETED)
        if worker.done():
            worker.result()
    finally:
        await room.disconnect()
        pending = [worker, shutdown, *(task for task, _ended in captures.values())]
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)



def field_rooms(path):
    if is_postgres(path):
        with connect(path) as connection:
            table = connection.execute("SELECT to_regclass('field_radio_sessions') AS name").fetchone()
            if not table["name"]:
                return []
            rows = connection.execute("""
                SELECT DISTINCT s.channel_id,s.wire_room FROM field_radio_sessions s
                JOIN field_devices d ON d.token_hash=s.device_hash
                WHERE s.removed_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>?
                    AND s.wire_room='px-' || s.channel_id
            """, (datetime.now(timezone.utc).isoformat(),))
            return [(row["channel_id"], row["wire_room"]) for row in rows]
    path = Path(path)
    if not path.is_file():
        return []
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        return connection.execute("""
            SELECT DISTINCT s.channel_id,s.wire_room FROM field_radio_sessions s
            JOIN field_devices d ON d.token_hash=s.device_hash
            WHERE s.removed_at IS NULL AND d.revoked_at IS NULL AND d.expires_at>?
                AND s.wire_room='px-' || s.channel_id
            """, (datetime.now(timezone.utc).isoformat(),)).fetchall()


async def run(archive, model):
    event = os.getenv("OPS_PTT_EVENT_ID", "golf-demo")
    legacy = os.getenv("OPS_PTT_ROOM", f"ops-{event}")
    path = os.getenv("OPS_DATABASE_URL") or os.getenv(
        "OPS_DB_PATH", str(Path(__file__).resolve().parents[1] / "data" / "ops.db"))
    db_errors = (sqlite3.OperationalError,)
    if is_postgres(path):
        import psycopg
        db_errors += (psycopg.OperationalError,)
    definitions = load_definitions(Path(__file__).resolve().parents[1] / "ontologies")
    recognize_field = field_transcriber(model)
    try:
        aliyun = AliyunASR.from_env()
    except ValueError:
        LOG.error("Model Studio ASR configuration invalid; using local review")
        aliyun = None
    if aliyun:
        LOG.info("Model Studio ASR enabled for %d named work groups", len(aliyun.channels))

    def review_channel_audio(scope, audio):
        if aliyun and scope in aliyun.channels:
            try:
                text = aliyun.transcribe(audio)
                if text:
                    return text, "aliyun_qwen3_asr"
            except Exception as exc:
                LOG.warning("Model Studio review failed, using Hong Kong model: %s", type(exc).__name__)
        if recognize_field:
            return recognize_field(audio)
        return transcribe(model, audio), "whisper"

    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stopped.set)
    workers = {}
    next_prune = 0.0
    try:
        while not stopped.is_set():
            if loop.time() >= next_prune:
                try:
                    await asyncio.to_thread(archive.prune_all)
                except db_errors:
                    LOG.warning("Could not prune voice archive")
                next_prune = loop.time() + 600
            try:
                channels = field_rooms(path)
            except db_errors as error:
                if "no such table" not in str(error):
                    LOG.warning("Could not query field channel registry")
                channels = []
            expected = dict([(event, legacy), *channels])
            for scope in set(workers) - set(expected):
                task = workers.pop(scope)
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            for scope, room in expected.items():
                worker = workers.get(scope)
                if worker is None or worker.done():
                    if worker is not None and not worker.cancelled():
                        failure = worker.exception()
                        if failure:
                            LOG.warning("Recorder for %s restarted after %s", scope, type(failure).__name__)
                    def review(item, channel=scope):
                        return record_incident(path, definitions, channel, item)
                    def recognize(audio, channel=scope):
                        return review_channel_audio(channel, audio)
                    workers[scope] = asyncio.create_task(run_room(
                        archive, model, scope, room, review if scope != event else None,
                        recognize if scope != event else None,
                    ))
            try:
                await asyncio.wait_for(stopped.wait(), 1.0)
            except TimeoutError:
                pass
    finally:
        for worker in workers.values():
            worker.cancel()
        await asyncio.gather(*workers.values(), return_exceptions=True)

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("livekit").setLevel(logging.WARNING)
    voice_url = os.getenv("OPS_PTT_VOICE_DATABASE_URL", "").strip()
    if voice_url and not is_postgres(voice_url):
        raise ValueError("OPS_PTT_VOICE_DATABASE_URL must be a PostgreSQL URL.")
    if os.getenv("OPS_DATABASE_URL") and not voice_url:
        raise ValueError("PostgreSQL deployment requires OPS_PTT_VOICE_DATABASE_URL.")
    archive = VoiceArchive(voice_url or os.environ["OPS_PTT_VOICE_DB"])
    model = WhisperModel(os.environ["OPS_WHISPER_MODEL_PATH"], device="cpu", compute_type="int8",
                         cpu_threads=1, num_workers=1, local_files_only=True)
    asyncio.run(run(archive, model))
