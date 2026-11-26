#!/usr/bin/env python3
"""Official Python RNS 1.6.1 + LXMF 1.3.1 peer for RNS-05 IFAC and RNS-06 multi-hop relay tests."""

import argparse
import json
import os
import sys
import threading
import time

import LXMF
import RNS


def emit(event_name: str, **fields) -> None:
    print(json.dumps({"event": event_name, **fields}), flush=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--configdir", required=True)
    parser.add_argument("--enable-transport", action="store_true")
    parser.add_argument("--tcp-listen-port", type=int, default=0)
    parser.add_argument("--connect-host", default="127.0.0.1")
    parser.add_argument("--connect-port", type=int, default=0)
    parser.add_argument("--ifac-netname", default="")
    parser.add_argument("--ifac-passphrase", default="")
    parser.add_argument("--peer-lxmf-dest", default="")
    parser.add_argument("--peer-primary-dest", default="")
    parser.add_argument("--expect-text", default="")
    parser.add_argument("--send-lxmf-body", default="")
    parser.add_argument("--timeout-secs", type=float, default=25.0)
    args = parser.parse_args()

    os.makedirs(args.configdir, exist_ok=True)
    rns_cfg_dir = os.path.join(args.configdir, "rns")
    lxmf_dir = os.path.join(args.configdir, "lxmf")
    os.makedirs(rns_cfg_dir, exist_ok=True)
    os.makedirs(lxmf_dir, exist_ok=True)

    ifac_lines = ""
    if args.ifac_netname:
        ifac_lines += f"    networkname = {args.ifac_netname}\n"
    if args.ifac_passphrase:
        ifac_lines += f"    passphrase = {args.ifac_passphrase}\n"

    interfaces_cfg = "[interfaces]\n"
    if args.tcp_listen_port > 0:
        interfaces_cfg += f"""  [[Python TCP Server]]
    type = TCPServerInterface
    enabled = Yes
    listen_ip = 127.0.0.1
    listen_port = {args.tcp_listen_port}
{ifac_lines}"""
    if args.connect_port > 0:
        interfaces_cfg += f"""  [[Rust TCP Peer]]
    type = TCPClientInterface
    enabled = Yes
    target_host = {args.connect_host}
    target_port = {args.connect_port}
{ifac_lines}"""

    transport_flag = "Yes" if args.enable_transport else "No"
    with open(os.path.join(rns_cfg_dir, "config"), "w", encoding="utf-8") as fh:
        fh.write(
            f"""[reticulum]
  enable_transport = {transport_flag}
  share_instance = No

[logging]
  loglevel = 2

{interfaces_cfg}
"""
        )

    reticulum = RNS.Reticulum(configdir=rns_cfg_dir)
    _ = reticulum
    RNS.Transport.STAMP_DESTINATIONS = False
    RNS.Transport.STAMP_LRS = False
    RNS.Transport.STAMP_PRS = False

    identity = RNS.Identity()
    primary_dest = RNS.Destination(
        identity,
        RNS.Destination.IN,
        RNS.Destination.SINGLE,
        "protocolx",
        "node",
    )
    primary_dest.set_proof_strategy(RNS.Destination.PROVE_ALL)

    router = LXMF.LXMRouter(storagepath=lxmf_dir)
    lxmf_dest = router.register_delivery_identity(
        identity, display_name="py-multihop-peer", stamp_cost=0
    )

    got_expected = threading.Event()
    got_packet = threading.Event()
    if not args.expect_text:
        got_expected.set()

    def on_primary_packet(data: bytes, packet: RNS.Packet) -> None:
        text = data.decode("utf-8", errors="replace")
        emit("primary_packet", text=text, hops=packet.hops)
        got_packet.set()

    primary_dest.set_packet_callback(on_primary_packet)

    def on_lxmf_delivery(message: LXMF.LXMessage) -> None:
        title = message.title_as_string() or ""
        content = message.content_as_string() or ""
        emit(
            "lxmf_received",
            title=title,
            content=content,
            signature_validated=bool(message.signature_validated),
        )
        if args.expect_text and args.expect_text in content:
            got_expected.set()

    router.register_delivery_callback(on_lxmf_delivery)

    emit(
        "ready",
        primary_dest_hex=primary_dest.hash.hex(),
        lxmf_dest_hex=lxmf_dest.hash.hex(),
        rns_version=RNS.__version__,
        lxmf_version=LXMF.__version__,
    )

    peer_lxmf_bytes = bytes.fromhex(args.peer_lxmf_dest) if args.peer_lxmf_dest else None
    peer_primary_bytes = bytes.fromhex(args.peer_primary_dest) if args.peer_primary_dest else None
    sent_lxmf = False
    sent_primary = False
    last_announce = 0.0
    deadline = time.monotonic() + args.timeout_secs

    while time.monotonic() < deadline:
        now = time.monotonic()
        if now - last_announce >= 2.0:
            primary_dest.announce(b"py-multihop")
            router.announce(lxmf_dest.hash)
            last_announce = now

        if peer_primary_bytes and not sent_primary:
            id_p = RNS.Identity.recall(peer_primary_bytes)
            if id_p is not None and RNS.Transport.has_path(peer_primary_bytes):
                out_dest = RNS.Destination(
                    id_p, RNS.Destination.OUT, RNS.Destination.SINGLE, "protocolx", "node"
                )
                pkt = RNS.Packet(out_dest, b"py-ifac-hello")
                pkt.send()
                sent_primary = True
                emit("py_sent_primary", hops=RNS.Transport.hops_to(peer_primary_bytes))

        if peer_lxmf_bytes and args.send_lxmf_body and not sent_lxmf:
            if not RNS.Transport.has_path(peer_lxmf_bytes) and now - last_announce >= 1.5:
                RNS.Transport.request_path(peer_lxmf_bytes)
            id_l = RNS.Identity.recall(peer_lxmf_bytes)
            if id_l is not None and RNS.Transport.has_path(peer_lxmf_bytes):
                out_lx = RNS.Destination(
                    id_l, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery"
                )
                msg = LXMF.LXMessage(
                    out_lx,
                    lxmf_dest,
                    args.send_lxmf_body,
                    title="PyMultiHop",
                    desired_method=LXMF.LXMessage.OPPORTUNISTIC,
                )
                router.handle_outbound(msg)
                sent_lxmf = True
                emit("py_sent_lxmf", hops=RNS.Transport.hops_to(peer_lxmf_bytes))

        # Relay-only mode stays alive until killed or timeout; leaf mode exits once done.
        if not args.enable_transport:
            done_send = (not args.send_lxmf_body or sent_lxmf) and (not peer_primary_bytes or sent_primary)
            if got_expected.is_set() and done_send:
                time.sleep(0.6)
                emit("completed")
                return 0

        time.sleep(0.05)

    if args.enable_transport:
        emit("relay_finished")
        return 0

    emit("timeout", got_expected=got_expected.is_set(), sent_lxmf=sent_lxmf, sent_primary=sent_primary)
    return 1


if __name__ == "__main__":
    sys.exit(main())
