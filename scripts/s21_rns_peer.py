#!/usr/bin/env python3
"""Official Python RNS 1.6.1 + LXMF 1.3.1 peer for Samsung S21 real-device QA.

Runs a primary private-IFAC TCPServerInterface (carrying LXMF opportunistic,
LXMF Direct + multi-KB Resource, and `protocolx.ptt` PXPT1 frame/floor traffic)
and optionally spawns an isolated rogue TCPServerInterface subprocess with a
mismatched IFAC passphrase to prove IFAC cryptographic isolation against the S21.
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import json

import LXMF
import RNS
import RNS.vendor.umsgpack as umsgpack

PTT_WIRE_MAGIC = b"PXPT1"
KIND_PTT_FRAME = 1
KIND_PTT_FLOOR = 2


def emit(event_name: str, **fields) -> None:
    print(json.dumps({"event": event_name, **fields}), flush=True)


def identity_from_seed(seed: str) -> RNS.Identity:
    priv = (
        hashlib.sha256((seed + ":x25519").encode("utf-8")).digest()
        + hashlib.sha256((seed + ":ed25519").encode("utf-8")).digest()
    )
    ident = RNS.Identity(create_keys=False)
    if not ident.load_private_key(priv):
        raise RuntimeError(f"failed to load deterministic key for {seed}")
    return ident


def write_rns_config(cfg_dir: str, label: str, port: int, netname: str, passphrase: str) -> None:
    os.makedirs(cfg_dir, exist_ok=True)
    ifac_lines = ""
    if netname:
        ifac_lines += f"    networkname = {netname}\n"
    if passphrase:
        ifac_lines += f"    passphrase = {passphrase}\n"
    with open(os.path.join(cfg_dir, "config"), "w", encoding="utf-8") as fh:
        fh.write(
            f"""[reticulum]
  enable_transport = Yes
  share_instance = No

[logging]
  loglevel = 2

[interfaces]
  [[{label}]]
    type = TCPServerInterface
    enabled = Yes
    listen_ip = 127.0.0.1
    listen_port = {port}
{ifac_lines}"""
        )


def run_rogue_child(args: argparse.Namespace) -> int:
    rns_cfg = os.path.join(args.configdir, "rogue_rns")
    lxmf_dir = os.path.join(args.configdir, "rogue_lxmf")
    os.makedirs(lxmf_dir, exist_ok=True)
    write_rns_config(
        rns_cfg,
        "Rogue Wrong-IFAC TCP Server",
        args.port,
        args.wrong_ifac_netname,
        args.wrong_ifac_passphrase,
    )
    _ = RNS.Reticulum(configdir=rns_cfg)
    RNS.Transport.STAMP_DESTINATIONS = False
    RNS.Transport.STAMP_LRS = False
    RNS.Transport.STAMP_PRS = False

    ident = identity_from_seed(args.rogue_seed)
    primary = RNS.Destination(
        ident, RNS.Destination.IN, RNS.Destination.SINGLE, "protocolx", "node"
    )
    router = LXMF.LXMRouter(storagepath=lxmf_dir, propagation_cost=0)
    lxmf_dest = router.register_delivery_identity(
        ident, display_name="py-rogue-peer", stamp_cost=0
    )

    leaked = {"count": 0}

    def on_rogue_packet(data: bytes, _packet: RNS.Packet) -> None:
        leaked["count"] += 1
        emit("rogue_leaked_packet", size=len(data))

    def on_rogue_lxmf(msg: LXMF.LXMessage) -> None:
        leaked["count"] += 1
        emit("rogue_leaked_lxmf", title=msg.title_as_string() or "")

    primary.set_packet_callback(on_rogue_packet)
    router.register_delivery_callback(on_rogue_lxmf)

    emit(
        "rogue_ready",
        port=args.port,
        rogue_primary_dest_hex=primary.hash.hex(),
        rogue_lxmf_dest_hex=lxmf_dest.hash.hex(),
    )

    deadline = time.monotonic() + args.timeout_secs
    last_announce = 0.0
    while time.monotonic() < deadline:
        now = time.monotonic()
        if now - last_announce >= 0.4:
            primary.announce(b"rogue-ifac-peer")
            router.announce(lxmf_dest.hash)
            last_announce = now
        time.sleep(0.05)

    emit("rogue_stopped", leaked_count=leaked["count"])
    return 0 if leaked["count"] == 0 else 2


class AspectAnnounceTracker:
    def __init__(self, aspect_filter: str, own_hash: bytes, on_peer):
        self.aspect_filter = aspect_filter
        self._own_hash = own_hash
        self._on_peer = on_peer

    def received_announce(self, destination_hash, announced_identity, app_data) -> None:
        if destination_hash == self._own_hash:
            return
        self._on_peer(destination_hash, announced_identity, app_data)


def run_primary(args: argparse.Namespace) -> int:
    rogue_proc = None
    rogue_ready_info = {}
    if args.wrong_port > 0:
        rogue_proc = subprocess.Popen(
            [
                sys.executable,
                os.path.abspath(__file__),
                "--child-rogue",
                "--port",
                str(args.wrong_port),
                "--wrong-ifac-netname",
                args.wrong_ifac_netname,
                "--wrong-ifac-passphrase",
                args.wrong_ifac_passphrase,
                "--rogue-seed",
                args.rogue_seed,
                "--configdir",
                args.configdir,
                "--timeout-secs",
                str(args.timeout_secs),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        rogue_deadline = time.monotonic() + 10.0
        while time.monotonic() < rogue_deadline:
            line = rogue_proc.stdout.readline()
            if not line:
                break
            line = line.strip()
            if line.startswith("{"):
                parsed = json.loads(line)
                if parsed.get("event") == "rogue_ready":
                    rogue_ready_info = parsed
                    break

    try:
        rns_cfg = os.path.join(args.configdir, "primary_rns")
        lxmf_dir = os.path.join(args.configdir, "primary_lxmf")
        os.makedirs(lxmf_dir, exist_ok=True)
        write_rns_config(
            rns_cfg,
            "S21 Primary IFAC TCP Server",
            args.port,
            args.ifac_netname,
            args.ifac_passphrase,
        )
        _ = RNS.Reticulum(configdir=rns_cfg)
        RNS.Transport.STAMP_DESTINATIONS = False
        RNS.Transport.STAMP_LRS = False
        RNS.Transport.STAMP_PRS = False

        ident = identity_from_seed(args.seed)
        primary_dest = RNS.Destination(
            ident, RNS.Destination.IN, RNS.Destination.SINGLE, "protocolx", "node"
        )
        primary_dest.set_proof_strategy(RNS.Destination.PROVE_ALL)

        ptt_dest = RNS.Destination(
            ident, RNS.Destination.IN, RNS.Destination.SINGLE, "protocolx", "ptt"
        )
        ptt_dest.set_proof_strategy(RNS.Destination.PROVE_ALL)

        router = LXMF.LXMRouter(storagepath=lxmf_dir, propagation_cost=0)
        lxmf_dest = router.register_delivery_identity(
            ident, display_name="py-s21-peer", stamp_cost=0
        )

        lock = threading.Lock()
        state = {
            "s21_lxmf_hash": None,
            "s21_ptt_hash": None,
            "got_opp_s21": False,
            "got_res_s21": False,
            "got_ptt_floor": False,
            "got_ptt_frame": False,
            "got_done_ack": False,
            "sent_lxmf_to_s21": False,
            "sent_ptt_to_s21": False,
        }
        expected_s21_res = bytes((i % 247 for i in range(6144)))
        py_res_payload = bytes((i % 251 for i in range(5120)))

        def on_lxmf_announce(dest_hash: bytes, _id, _app_data) -> None:
            with lock:
                if state["s21_lxmf_hash"] is None:
                    state["s21_lxmf_hash"] = dest_hash
                    emit("s21_lxmf_announced", dest_hex=dest_hash.hex())

        def on_ptt_announce(dest_hash: bytes, _id, _app_data) -> None:
            with lock:
                if state["s21_ptt_hash"] is None:
                    state["s21_ptt_hash"] = dest_hash
                    emit("s21_ptt_announced", dest_hex=dest_hash.hex())

        RNS.Transport.register_announce_handler(
            AspectAnnounceTracker("lxmf.delivery", lxmf_dest.hash, on_lxmf_announce)
        )
        RNS.Transport.register_announce_handler(
            AspectAnnounceTracker("protocolx.ptt", ptt_dest.hash, on_ptt_announce)
        )

        def on_lxmf_delivery(message: LXMF.LXMessage) -> None:
            title = message.title_as_string() or ""
            content = message.content_as_string() or ""
            attachments = []
            if LXMF.FIELD_FILE_ATTACHMENTS in message.fields:
                for att in message.fields[LXMF.FIELD_FILE_ATTACHMENTS]:
                    name = att[0].decode("utf-8", errors="replace") if isinstance(att[0], bytes) else str(att[0])
                    raw = att[1]
                    attachments.append({"name": name, "len": len(raw), "raw": raw})
            emit(
                "lxmf_received",
                method=message.method,
                title=title,
                content=content,
                signature_validated=bool(message.signature_validated),
                attachments=[{"name": a["name"], "len": a["len"]} for a in attachments],
            )
            with lock:
                if title == "OppS21" and "Hello from S21" in content:
                    state["got_opp_s21"] = True
                elif (
                    title == "ResS21"
                    and len(attachments) == 1
                    and attachments[0]["raw"] == expected_s21_res
                ):
                    state["got_res_s21"] = True
                elif title == "S21DoneAck":
                    state["got_done_ack"] = True

        router.register_delivery_callback(on_lxmf_delivery)

        def on_ptt_packet(data: bytes, _packet: RNS.Packet) -> None:
            if not data.startswith(PTT_WIRE_MAGIC):
                return
            decoded = umsgpack.unpackb(data[len(PTT_WIRE_MAGIC) :])
            if not isinstance(decoded, list) or len(decoded) < 9:
                return
            kind = decoded[0]
            emit(
                "ptt_packet_received",
                kind=kind,
                channel_id=decoded[1],
                session_id=decoded[2],
                payload_len=len(decoded[8]) if kind == KIND_PTT_FRAME and isinstance(decoded[8], (bytes, bytearray)) else 0,
            )
            with lock:
                if kind == KIND_PTT_FLOOR:
                    state["got_ptt_floor"] = True
                elif kind == KIND_PTT_FRAME:
                    state["got_ptt_frame"] = True

        ptt_dest.set_packet_callback(on_ptt_packet)

        emit(
            "ready",
            port=args.port,
            wrong_port=args.wrong_port,
            ifac_netname=args.ifac_netname,
            py_primary_dest_hex=primary_dest.hash.hex(),
            py_lxmf_dest_hex=lxmf_dest.hash.hex(),
            py_ptt_dest_hex=ptt_dest.hash.hex(),
            rogue_lxmf_dest_hex=rogue_ready_info.get("rogue_lxmf_dest_hex", ""),
            rogue_primary_dest_hex=rogue_ready_info.get("rogue_primary_dest_hex", ""),
            rns_version=RNS.__version__,
            lxmf_version=LXMF.__version__,
        )

        deadline = time.monotonic() + args.timeout_secs
        last_announce = 0.0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now - last_announce >= 0.25:
                primary_dest.announce(b"py-s21-primary")
                router.announce(lxmf_dest.hash)
                ptt_dest.announce(b"")
                last_announce = now

            with lock:
                s21_lxmf_hash = state["s21_lxmf_hash"]
                s21_ptt_hash = state["s21_ptt_hash"]
                need_send_lxmf = s21_lxmf_hash is not None and not state["sent_lxmf_to_s21"]
                need_send_ptt = s21_ptt_hash is not None and not state["sent_ptt_to_s21"]

            if need_send_lxmf:
                s21_id = RNS.Identity.recall(s21_lxmf_hash)
                if s21_id is not None:
                    out_lx = RNS.Destination(
                        s21_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery"
                    )
                    opp_msg = LXMF.LXMessage(
                        out_lx,
                        lxmf_dest,
                        "Opp from Python RNS to S21",
                        title="OppPyS21",
                        desired_method=LXMF.LXMessage.OPPORTUNISTIC,
                    )
                    router.handle_outbound(opp_msg)

                    res_msg = LXMF.LXMessage(
                        out_lx,
                        lxmf_dest,
                        "Direct Resource from Python RNS to S21",
                        title="ResPyS21",
                        fields={LXMF.FIELD_FILE_ATTACHMENTS: [["py_s21_chunk.bin", py_res_payload]]},
                        desired_method=LXMF.LXMessage.DIRECT,
                    )
                    router.handle_outbound(res_msg)
                    with lock:
                        state["sent_lxmf_to_s21"] = True
                    emit("sent_lxmf_to_s21", dest_hex=s21_lxmf_hash.hex())

            if need_send_ptt:
                s21_ptt_id = RNS.Identity.recall(s21_ptt_hash)
                if s21_ptt_id is not None:
                    out_ptt = RNS.Destination(
                        s21_ptt_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "protocolx", "ptt"
                    )
                    ts_ms = int(time.time() * 1000)
                    floor_payload = PTT_WIRE_MAGIC + umsgpack.packb(
                        [
                            KIND_PTT_FLOOR,
                            "s21-ptt-ch",
                            "py-ptt-sess-1",
                            ident.hash,
                            2,  # Grant
                            2,
                            ts_ms,
                            ident.hash,
                            "py_floor_grant",
                        ]
                    )
                    RNS.Packet(out_ptt, floor_payload).send()

                    sample_adpcm = bytes(((i * 7 + 3) & 0xFF) for i in range(164))
                    frame_payload = PTT_WIRE_MAGIC + umsgpack.packb(
                        [
                            KIND_PTT_FRAME,
                            "s21-ptt-ch",
                            "py-ptt-sess-1",
                            ident.hash,
                            1,
                            ts_ms,
                            "adpcm-ima-16k",
                            20,
                            sample_adpcm,
                        ]
                    )
                    RNS.Packet(out_ptt, frame_payload).send()
                    with lock:
                        state["sent_ptt_to_s21"] = True
                    emit("sent_ptt_to_s21", dest_hex=s21_ptt_hash.hex())

            with lock:
                all_done = (
                    state["got_opp_s21"]
                    and state["got_res_s21"]
                    and state["got_ptt_floor"]
                    and state["got_ptt_frame"]
                    and state["sent_lxmf_to_s21"]
                    and state["sent_ptt_to_s21"]
                    and state["got_done_ack"]
                )
            if all_done:
                emit("completed", **{k: v for k, v in state.items() if not k.endswith("_hash")})
                return 0

            time.sleep(0.05)

        with lock:
            emit(
                "timeout",
                **{k: v for k, v in state.items() if not k.endswith("_hash")},
            )
        return 1
    finally:
        if rogue_proc is not None:
            rogue_proc.terminate()
            try:
                rogue_proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                rogue_proc.kill()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=17421)
    parser.add_argument("--wrong-port", type=int, default=17422)
    parser.add_argument("--ifac-netname", default="protocolx.s21.qa")
    parser.add_argument("--ifac-passphrase", default="s21-qa-secret-v1")
    parser.add_argument("--wrong-ifac-netname", default="protocolx.rogue.qa")
    parser.add_argument("--wrong-ifac-passphrase", default="wrong-secret-v1")
    parser.add_argument("--seed", default="protocolx-s21-primary-v1")
    parser.add_argument("--rogue-seed", default="protocolx-s21-rogue-v1")
    parser.add_argument("--configdir", default="")
    parser.add_argument("--timeout-secs", type=float, default=60.0)
    parser.add_argument("--child-rogue", action="store_true")
    args = parser.parse_args()

    cleanup_dir = False
    if not args.configdir:
        args.configdir = tempfile.mkdtemp(prefix="protocolx-s21-rns-")
        cleanup_dir = True

    try:
        if args.child_rogue:
            return run_rogue_child(args)
        return run_primary(args)
    finally:
        if cleanup_dir:
            shutil.rmtree(args.configdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
