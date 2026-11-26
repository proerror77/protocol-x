#!/usr/bin/env python3
"""Official Python RNS 1.6.1 + LXMF 1.3.1 peer for RNS-03 bidirectional integration tests."""

import argparse
import hashlib
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
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--rust-lxmf-dest", required=True)
    parser.add_argument("--configdir", required=True)
    parser.add_argument("--mode", choices=["direct", "propagation"], default="direct")
    args = parser.parse_args()

    os.makedirs(args.configdir, exist_ok=True)
    rns_cfg_dir = os.path.join(args.configdir, "rns")
    lxmf_storage_dir = os.path.join(args.configdir, "lxmf")
    os.makedirs(rns_cfg_dir, exist_ok=True)
    os.makedirs(lxmf_storage_dir, exist_ok=True)

    with open(os.path.join(rns_cfg_dir, "config"), "w", encoding="utf-8") as fh:
        fh.write(
            f"""[reticulum]
  enable_transport = Yes
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

    reticulum = RNS.Reticulum(configdir=rns_cfg_dir)
    _ = reticulum
    RNS.Transport.STAMP_DESTINATIONS = False
    RNS.Transport.STAMP_LRS = False
    RNS.Transport.STAMP_PRS = False

    router = LXMF.LXMRouter(storagepath=lxmf_storage_dir, propagation_cost=0)
    py_identity = RNS.Identity()
    py_delivery = router.register_delivery_identity(
        py_identity, display_name="py-lxmf-peer", stamp_cost=0
    )

    if args.mode == "propagation":
        router.set_message_storage_limit(megabytes=5.0)
        router.enable_propagation()
        RNS.Identity.remember(
            os.urandom(32),
            router.propagation_destination.hash,
            router.identity.get_public_key(),
            router.get_propagation_node_app_data(),
        )
        router.set_outbound_propagation_node(router.propagation_destination.hash)

    rust_dest_bytes = bytes.fromhex(args.rust_lxmf_dest)
    got_opp = threading.Event()
    got_resource = threading.Event()
    got_propagated = threading.Event()

    def on_lxmf_delivery(message: LXMF.LXMessage) -> None:
        title = message.title_as_string() or ""
        content = message.content_as_string() or ""
        attachments = []
        if LXMF.FIELD_FILE_ATTACHMENTS in message.fields:
            for att in message.fields[LXMF.FIELD_FILE_ATTACHMENTS]:
                name = att[0].decode("utf-8", errors="replace") if isinstance(att[0], bytes) else str(att[0])
                raw = att[1]
                attachments.append(
                    {
                        "name": name,
                        "len": len(raw),
                        "sha256": hashlib.sha256(raw).hexdigest(),
                    }
                )
        emit(
            "lxmf_received",
            method=message.method,
            title=title,
            content=content,
            signature_validated=bool(message.signature_validated),
            source_hex=message.source_hash.hex() if message.source_hash else "",
            attachments=attachments,
        )
        if message.method == LXMF.LXMessage.OPPORTUNISTIC:
            got_opp.set()
        elif len(attachments) > 0:
            got_resource.set()
        elif message.method == LXMF.LXMessage.PROPAGATED:
            got_propagated.set()

    router.register_delivery_callback(on_lxmf_delivery)

    pn_hex = router.propagation_destination.hash.hex() if router.propagation_destination else ""
    emit(
        "ready",
        lxmf_dest_hex=py_delivery.hash.hex(),
        pn_dest_hex=pn_hex,
        lxmf_version=LXMF.__version__,
    )

    deadline = time.monotonic() + 25.0
    last_announce = 0.0
    sent_to_rust = False
    sent_prop_to_rust = False
    requested_from_pn = False

    while time.monotonic() < deadline:
        now = time.monotonic()
        if now - last_announce >= 0.4:
            router.announce(py_delivery.hash)
            if router.propagation_destination is not None:
                router.announce_propagation_node()
            last_announce = now

        rust_identity = RNS.Identity.recall(rust_dest_bytes)
        if rust_identity is not None and not sent_to_rust and args.mode == "direct":
            rust_out = RNS.Destination(
                rust_identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                "lxmf",
                "delivery",
            )
            opp_msg = LXMF.LXMessage(
                rust_out,
                py_delivery,
                "Opp body from Python LXMF",
                title="OppPy",
                desired_method=LXMF.LXMessage.OPPORTUNISTIC,
            )
            router.handle_outbound(opp_msg)

            big_payload = bytes((i % 251 for i in range(5120)))
            res_msg = LXMF.LXMessage(
                rust_out,
                py_delivery,
                "Direct resource body from Python LXMF",
                title="ResPy",
                fields={LXMF.FIELD_FILE_ATTACHMENTS: [["py_chunk.bin", big_payload]]},
                desired_method=LXMF.LXMessage.DIRECT,
            )
            router.handle_outbound(res_msg)
            emit("python_direct_and_opp_queued")
            sent_to_rust = True

        if args.mode == "propagation":
            if rust_identity is not None and not sent_prop_to_rust:
                rust_out = RNS.Destination(
                    rust_identity,
                    RNS.Destination.OUT,
                    RNS.Destination.SINGLE,
                    "lxmf",
                    "delivery",
                )
                prop_msg = LXMF.LXMessage(
                    rust_out,
                    py_delivery,
                    "Propagated body from Python LXMF",
                    title="PropPy",
                    desired_method=LXMF.LXMessage.PROPAGATED,
                )
                prop_msg.defer_propagation_stamp = True
                prop_msg.pack()
                import RNS.vendor.umsgpack as umsgpack
                packed_entries = umsgpack.unpackb(prop_msg.propagation_packed)[1]
                router.lxmf_propagation(
                    packed_entries[0],
                    stamp_value=16.0,
                    stamp_data=b"\x00" * 32,
                )
                emit("python_propagated_queued")
                sent_prop_to_rust = True

            # Check if PN has stored any message for py_delivery
            for tid, entry in list(router.propagation_entries.items()):
                if entry[0] == py_delivery.hash and not requested_from_pn:
                    router.request_messages_from_propagation_node(py_identity)
                    requested_from_pn = True
                    emit("python_requested_from_pn", transient_id=tid.hex())

        if args.mode == "direct" and got_opp.is_set() and got_resource.is_set() and sent_to_rust:
            time.sleep(0.5)
            emit("completed")
            return 0

        if args.mode == "propagation" and got_propagated.is_set() and sent_prop_to_rust:
            time.sleep(0.5)
            emit("completed")
            return 0

        time.sleep(0.05)

    emit(
        "timeout",
        mode=args.mode,
        got_opp=got_opp.is_set(),
        got_resource=got_resource.is_set(),
        got_propagated=got_propagated.is_set(),
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
