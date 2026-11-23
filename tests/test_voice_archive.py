import os
import tempfile
import unittest
from importlib.util import find_spec
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from app.main import create_app
from app.voice_archive import VoiceArchive


class PilotMediaTests(unittest.TestCase):
    @unittest.skipUnless(find_spec("livekit"), "requires requirements-pilot.txt")
    def test_archive_requires_pilot_and_keeps_records_with_their_activity(self):
        from livekit import api

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "voice.db"
            env = {
                "OPS_PILOT_STAFF_TOKEN": "staff" * 8,
                "OPS_PILOT_DISPATCHER_TOKEN": "dispatcher" * 8,
                "OPS_LIVEKIT_SERVER_URL": "http://127.0.0.1:7880",
                "OPS_LIVEKIT_CLIENT_URL": "ws://127.0.0.1:7890",
                "OPS_LIVEKIT_API_KEY": "test-key",
                "OPS_LIVEKIT_API_SECRET": "secret" * 8,
                "OPS_PTT_VOICE_DB": str(path),
                "OPS_VIDEO_ENABLED": "true",
            }
            archive = VoiceArchive(path)
            self.assertTrue(archive.start("golf-demo", "TR_demo", "demo-staff", "S26 · 工作人员"))
            self.assertFalse(archive.start("golf-demo", "TR_demo", "demo-staff"))
            archive.finish("golf-demo", "TR_demo", b"RIFF", 2_000, False)
            archive.complete("golf-demo", "TR_demo", "ready", "三号车已到")

            with patch.dict(os.environ, env, clear=True), TestClient(create_app(Path(folder) / "ops.db")) as client:
                golf = "/api/events/golf-demo"
                history = golf + "/voice/TR_demo/audio"
                bearer = {"Authorization": "Bearer " + env["OPS_PILOT_STAFF_TOKEN"]}
                self.assertEqual(client.get(history).status_code, 401)
                self.assertEqual(client.get(history, headers=bearer).content, b"RIFF")
                workspace = client.get(golf + "/workspace", headers=bearer).json()
                self.assertEqual(workspace["voice_messages"][0]["text"], "三号车已到")
                self.assertEqual(workspace["voice_messages"][0]["speaker_name"], "S26 · 工作人员")
                conference = "/api/events/conference-demo"
                self.assertEqual(client.get(conference + "/voice/TR_demo/audio", headers=bearer).status_code, 404)
                self.assertEqual(client.get(conference + "/workspace", headers=bearer).json()["voice_messages"], [])

                verifier = api.TokenVerifier(env["OPS_LIVEKIT_API_KEY"], env["OPS_LIVEKIT_API_SECRET"])
                self.assertEqual(client.post(golf + "/ptt/join", headers=bearer, json={"callsign": "S26\nX"}).status_code, 422)
                for channel, room in (("ptt", "ops-golf-demo"), ("video", "ops-golf-demo-video")):
                    joined = client.post(golf + f"/{channel}/join", headers=bearer, json={"callsign": "S26"}).json()
                    claims = verifier.verify(joined["token"])
                    self.assertEqual(claims.name, "S26 · 工作人员")
                    grant = claims.video
                    self.assertEqual(grant.room, room)
                    self.assertFalse(grant.can_publish)
                    self.assertEqual(client.get("/internal/ptt/admission", headers={
                        "Authorization": "Bearer " + joined["token"],
                    }).status_code, 204)



def test_audio_range_can_start_with_first_bytes():
    from app.voice_archive import audio_response

    raw = b"RIFF" + b"abcd" * 16
    original = audio_response(raw, None)
    assert original.body == raw and original.headers["accept-ranges"] == "bytes"
    head = audio_response(raw, "bytes=0-3")
    assert head.status_code == 206 and head.body == b"RIFF"
    assert head.headers["content-range"] == f"bytes 0-3/{len(raw)}"
    assert audio_response(raw, "bytes=-4").body == b"abcd"
    assert audio_response(raw, "bytes=100-").status_code == 416


def test_voice_retention_keeps_transcript_after_original_expires(tmp_path):
    from datetime import datetime, timedelta, timezone

    archive = VoiceArchive(tmp_path / "retention.db")
    for track, days in (("original", 0), ("transcript", 10), ("expired", 32)):
        assert archive.start("group", track, "operator")
        archive.finish("group", track, b"RIFF", 2000, False)
        archive.complete("group", track, "ready", "演练报告")
        with archive.connect(write=True) as conn:
            conn.execute("UPDATE voice_transmissions SET created_at=? WHERE track_sid=?",
                         ((datetime.now(timezone.utc) - timedelta(days=days)).isoformat(), track))
    archive.prune("group")
    page, _ = archive.page("group")
    assert [(item["track_sid"], item["text"], item["has_audio"]) for item in page] == [
        ("original", "演练报告", 1), ("transcript", "演练报告", 0)]
    assert archive.audio("group", "transcript") is None


def test_silent_ptt_is_not_published_and_audible_unknown_speech_is_retained(tmp_path):
    from struct import pack
    from app.voice_archive import has_ptt_signal

    assert not has_ptt_signal(b"")
    assert not has_ptt_signal(pack("<960h", *([75, -75] * 480)))
    assert not has_ptt_signal(pack("<960h", *([0] * 400 + [3000] + [0] * 559)))
    assert not has_ptt_signal(pack("<4800h", *([550, -550] * 2400)))
    assert has_ptt_signal(pack("<5120h", *([550, -550] * 2560)))
    assert has_ptt_signal(pack("<960h", *([1400, -1400] * 480)))

    archive = VoiceArchive(tmp_path / "silent.db")
    for track in ("silent", "audible"):
        assert archive.start("group", track, "operator", review=True)
    assert archive.recent("group") == []
    assert archive.page("group") == ([], None)
    archive.finish("group", "silent", b"not kept", 2_000, False, silent=True)
    assert archive.next_pending("group") is None
    assert archive.audio("group", "silent") is None
    assert archive.page("group") == ([], None)
    archive.finish("group", "audible", b"retained", 700, False)
    assert archive.next_pending("group")["track_sid"] == "audible"
    archive.complete("group", "audible", "no_speech")
    assert [item["track_sid"] for item in archive.recent("group")] == ["audible"]
    assert archive.audio("group", "audible") == b"retained"
