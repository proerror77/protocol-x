"""Optional Model Studio re-transcription for explicitly selected work channels."""
import base64
import json
import os
import re
from urllib.request import HTTPRedirectHandler, Request, build_opener

MODEL = "qwen3-asr-flash"
REGIONS = {"ap-southeast-1", "cn-beijing"}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class AliyunASR:
    def __init__(self, key, workspace, region, channels):
        if region not in REGIONS or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,79}", workspace):
            raise ValueError("Invalid Model Studio workspace or ASR region")
        self.key = key
        self.channels = frozenset(channels)
        self.endpoint = (
            f"https://{workspace}.{region}.maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        )

    @classmethod
    def from_env(cls):
        key = os.getenv("DASHSCOPE_API_KEY", "").strip()
        workspace = os.getenv("OPS_PTT_DASHSCOPE_WORKSPACE", "").strip()
        region = os.getenv("OPS_PTT_DASHSCOPE_REGION", "").strip()
        channels = {v.strip() for v in os.getenv("OPS_PTT_DASHSCOPE_CHANNELS", "").split(",") if v.strip()}
        if not all((key, workspace, region, channels)):
            return None
        if any(not re.fullmatch(r"group-[a-f0-9]{32}", v) for v in channels):
            raise ValueError("Model Studio requires an explicit list of work group IDs")
        return cls(key, workspace, region, channels)

    def transcribe(self, audio):
        audio = bytes(audio)
        if not (44 < len(audio) <= 2_880_044 and audio.startswith(b"RIFF") and audio[8:12] == b"WAVE"):
            raise ValueError("Expected bounded Protocol X WAV")
        encoded = base64.b64encode(audio).decode("ascii")
        payload = {"model": MODEL,
                   "input": {"messages": [{"role": "user", "content": [{"audio": "data:audio/wav;base64," + encoded}]}]},
                   "parameters": {"asr_options": {"enable_itn": True}}}
        req = Request(self.endpoint, data=json.dumps(payload).encode(), method="POST",
                      headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"})
        with build_opener(NoRedirect()).open(req, timeout=8) as response:
            result = json.loads(response.read(128_000))
        choice = result["output"]["choices"][0]
        if choice.get("finish_reason") != "stop":
            raise ValueError("Incomplete ASR response")
        parts = choice["message"]["content"]
        text = " ".join(part["text"].strip() for part in parts if isinstance(part.get("text"), str))
        if len(text) > 4000:
            raise ValueError("ASR result exceeds clip transcript limit")
        return text
