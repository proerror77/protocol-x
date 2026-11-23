"""Create a private, untracked credential file for the local Compose pilot."""
import os
from pathlib import Path
from secrets import token_hex


def main():
    path = Path(__file__).with_name("pilot.env")
    names = (
        "OPS_PILOT_DISPATCHER_TOKEN",
        "OPS_PILOT_STAFF_TOKEN",
        "OPS_LIVEKIT_API_KEY",
        "OPS_LIVEKIT_API_SECRET",
    )
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit("pilot.env already exists. Existing credentials are still valid.") from None
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        for name in names:
            output.write(f"{name}={token_hex(32)}\n")
    print(f"Created {path.name}")


if __name__ == "__main__":
    main()
