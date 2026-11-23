"""Serve the pinned handset caption model to authenticated Protocol X users."""
import os
from pathlib import Path

from fastapi import Depends, HTTPException
from fastapi.responses import FileResponse

MODEL_BYTES = {"model.int8.onnx": 239_233_841, "tokens.txt": 315_894}


def register_voice_model_assets(app, identity):
    @app.get("/api/voice-models/sensevoice-v1/{asset}")
    def download_voice_model(asset: str, _user=Depends(identity)):
        home = os.getenv("OPS_PTT_HANDSET_MODEL_DIR", "").strip()
        if not home or asset not in MODEL_BYTES:
            raise HTTPException(404, "离线字幕包暂不可用")
        path = Path(home) / asset
        if not path.is_file() or path.stat().st_size != MODEL_BYTES[asset]:
            raise HTTPException(503, "离线字幕包尚未准备好")
        return FileResponse(path, media_type="application/octet-stream",
                            headers={"Cache-Control": "private, max-age=86400"})
