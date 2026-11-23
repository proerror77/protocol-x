"""Run the local-only Golf Ops business-loop starter."""
from pathlib import Path
import argparse
import os


def main():
    parser = argparse.ArgumentParser(description="Golf Ops local demonstration; synthetic data only")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--db", type=Path, default=Path(__file__).parent / "data" / "ops.db")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use an unprivileged port between 1024 and 65535")
    os.environ["OPS_DB_PATH"] = str(args.db.resolve())
    try:
        import uvicorn
        import fastapi  # noqa: F401
    except ImportError:
        parser.exit(1, "Install dependencies first: python -m pip install -r requirements.txt\n")
    print(f"Open http://127.0.0.1:{args.port} — local demo, synthetic data, no real dispatch")
    uvicorn.run("app.main:app", host="127.0.0.1", port=args.port, log_level="info")


if __name__ == "__main__":
    main()
