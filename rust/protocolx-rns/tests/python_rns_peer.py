#!/usr/bin/env python3
"""Official Python RNS peer for bidirectional Rust <-> Python interoperability POC."""

import argparse
import json
import os
import sys
import threading
import time

import RNS


def emit(event_name: str, **fields) -> None:
    payload = {"event": event_name, **fields}
    print(json.dumps(payload), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--rust-dest", required=True)
    parser.add_argument("--configdir", required=True)
    args = parser.parse_args()

    os.makedirs(args.configdir, exist_ok=True)
    config_path = os.path.join(args.configdir, "config")
    with open(config_path, "w", encoding="utf-8") as fh:
        fh.write(
            f"""[reticulum]
  enable_transport = No
  share_instance = No

[logging]
  loglevel = 2

[interfaces]
  [[Rust TCP Peer]]
    type = TCPClientInterface
    enabled = Yes
    target_host = {args.host}
    target_port = {args.port}
"""
        )

    reticulum = RNS.Reticulum(configdir=args.configdir)
    _ = reticulum
    # RNS 1.6.1 enables cost-25 destination PoW stamps by default (~200s on 1 core).
    # Disable destination PoW stamping for deterministic private-mesh POC latency.
    RNS.Transport.STAMP_DESTINATIONS = False
    RNS.Transport.STAMP_LRS = False
    RNS.Transport.STAMP_PRS = False

    rust_dest_bytes = bytes.fromhex(args.rust_dest)
    py_identity = RNS.Identity()
    py_dest = RNS.Destination(
        py_identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        "protocolx",
        "node",
    )

    done_event = threading.Event()
    rust_announced = threading.Event()
    got_single_from_rust = threading.Event()
    got_link_from_rust = threading.Event()

    def on_single_packet(data: bytes, packet) -> None:
        text = data.decode("utf-8", errors="replace")
        emit("single_received", payload=text)
        got_single_from_rust.set()

    def on_link_packet(data: bytes, packet) -> None:
        text = data.decode("utf-8", errors="replace")
        emit("link_packet_received", payload=text)
        got_link_from_rust.set()

    def on_link_established(link: RNS.Link) -> None:
        link.set_packet_callback(on_link_packet)
        emit("link_established", link_id=link.link_id.hex())
        pkt = RNS.Packet(link, b"HELLO-LINK-FROM-PYTHON")
        pkt.send()

    py_dest.set_proof_strategy(RNS.Destination.PROVE_ALL)
    py_dest.set_packet_callback(on_single_packet)
    py_dest.set_link_established_callback(on_link_established)

    class AnnounceHandler:
        aspect_filter = "protocolx.node"

        def received_announce(self, destination_hash, announced_identity, app_data):
            if destination_hash == rust_dest_bytes:
                emit(
                    "rust_announce_heard",
                    dest_hex=destination_hash.hex(),
                    app_data=(app_data or b"").decode("utf-8", errors="replace"),
                )
                rust_announced.set()

    RNS.Transport.register_announce_handler(AnnounceHandler())

    emit(
        "ready",
        dest_hex=py_dest.hash.hex(),
        identity_hex=py_identity.hash.hex(),
        rns_version=RNS.__version__,
    )

    # Announce Python destination periodically until Rust hears it and we hear Rust
    deadline = time.monotonic() + 20.0
    last_announce = 0.0
    sent_single_to_rust = False
    while time.monotonic() < deadline:
        now = time.monotonic()
        if now - last_announce >= 0.4:
            py_dest.announce(app_data=b"python-rns-1.6.1")
            last_announce = now

        if rust_announced.is_set() and not sent_single_to_rust:
            recalled = RNS.Identity.recall(rust_dest_bytes)
            if recalled is not None:
                rust_out = RNS.Destination(
                    recalled,
                    RNS.Destination.OUT,
                    RNS.Destination.SINGLE,
                    "protocolx",
                    "node",
                )
                RNS.Packet(rust_out, b"PING-SINGLE-FROM-PYTHON-RNS").send()
                emit("single_sent_to_rust", dest_hex=args.rust_dest)
                sent_single_to_rust = True

        if (
            sent_single_to_rust
            and got_single_from_rust.is_set()
            and got_link_from_rust.is_set()
        ):
            done_event.set()
            break

        time.sleep(0.05)

    if not done_event.is_set():
        emit(
            "timeout",
            rust_announced=rust_announced.is_set(),
            sent_single_to_rust=sent_single_to_rust,
            got_single_from_rust=got_single_from_rust.is_set(),
            got_link_from_rust=got_link_from_rust.is_set(),
        )
        return 1

    emit("completed")
    time.sleep(0.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
