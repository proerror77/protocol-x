#!/usr/bin/env python3
"""Send bounded synthetic CoT positions to the Hong Kong TAK demonstration."""

from argparse import ArgumentParser
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
from pathlib import Path
import socket
import ssl
import time
from xml.etree.ElementTree import Element, SubElement, tostring


ASSETS = (
    ("golf-hk-car-02", "HK Demo Car 02", "a-f-G-E-V", 22.3160, 113.9390),
    ("golf-hk-car-03", "HK Demo Car 03", "a-f-G-E-V", 22.2990, 113.9330),
    ("golf-hk-car-05", "HK Demo Car 05", "a-f-G-E-V", 22.2910, 113.9520),
    ("golf-hk-staff-a", "HK Demo Staff A", "a-f-G-U-C", 22.3110, 113.9220),
    ("golf-hk-staff-b", "HK Demo Staff B", "a-f-G-U-C", 22.2800, 113.9400),
)


def cot_time(value):
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def position_event(asset, step, observed_at):
    uid, callsign, cot_type, latitude, longitude = asset
    stamp = cot_time(observed_at)
    event = Element("event", {
        "version": "2.0", "uid": uid, "type": cot_type, "how": "h-e",
        "time": stamp, "start": stamp, "stale": cot_time(observed_at + timedelta(minutes=2)),
    })
    SubElement(event, "point", {
        "lat": f"{latitude + step * 0.0002:.6f}", "lon": f"{longitude + step * 0.0003:.6f}",
        "hae": "10", "ce": "10", "le": "10",
    })
    detail = SubElement(event, "detail")
    SubElement(detail, "contact", {"callsign": callsign})
    SubElement(detail, "__group", {"name": "Blue", "role": "Team Member"})
    SubElement(detail, "takv", {"platform": "Golf Ops", "device": "Synthetic replay", "version": "1", "os": "Linux"})
    SubElement(detail, "track", {"speed": "10", "course": "55"})
    SubElement(detail, "remarks").text = "Synthetic Hong Kong demonstration."
    return tostring(event, encoding="utf-8") + b"\n"


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--tls-dir", type=Path, default=Path("/etc/golf-ops-demo-positions"))
    parser.add_argument("--server-name", default="47.238.64.250")
    parser.add_argument("--steps", type=int, choices=range(1, 11), default=6)
    args = parser.parse_args()
    tls = ssl.create_default_context(cafile=str(args.tls_dir / "ca.pem"))
    tls.load_cert_chain(args.tls_dir / "client.pem", args.tls_dir / "client.key")
    with ExitStack() as stack:
        connections = []
        for _ in ASSETS:
            raw = stack.enter_context(socket.create_connection(("127.0.0.1", 8089), timeout=6))
            connections.append(stack.enter_context(tls.wrap_socket(raw, server_hostname=args.server_name)))
        for step in range(args.steps):
            observed_at = datetime.now(timezone.utc)
            for connection, asset in zip(connections, ASSETS):
                connection.sendall(position_event(asset, step, observed_at))
            print(f"{cot_time(observed_at)} · frame {step + 1}/{args.steps} · {len(ASSETS)} synthetic positions", flush=True)
            if step + 1 < args.steps:
                time.sleep(4)
        time.sleep(1)


if __name__ == "__main__":
    main()
