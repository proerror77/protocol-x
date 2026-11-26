# RNS-03 Rust Core: Clean-Room LXMF Runtime, Local Persistence & Pluggable Interface Bridge

Branch: `rns/03-runtime-lxmf`  
Crate: `rust/protocolx-rns/` (`protocolx-rns v0.1.0`, license `MIT OR Apache-2.0`)

---

## 1. Crate Stack & Licensing (Zero Copyleft)

| Crate / Dependency | Version | License | Purpose |
|---|---:|---|---|
| `personal-rns` | `0.3.7` | `MIT OR Apache-2.0` | RNS packet, identity, link, channel, resource, and persistence runtime |
| `prns-core` | `0.3.7` | `MIT OR Apache-2.0` | Low-level RNS crypto (`Ed25519`, `X25519`, `Token`), `InterfaceKind`, `InterfaceDescriptor` |
| `prns-runtime` | `0.3.7` | `MIT OR Apache-2.0` | `manifold::interface_seam::Interface` trait for pluggable custom transports |
| `prns-runtime-tokio` | `0.3.7` | `MIT OR Apache-2.0` | Tokio node runner, `DestinationIdentityQuery`, `RequestEndpointId`, `PrnsNodeHandle` |
| `prns-interfaces-tokio` | `0.3.7` | `MIT OR Apache-2.0` | TCP client/server and UDP interfaces |
| `rmpv` | `1.3` | `MIT` | Clean-room MessagePack encoding/decoding for LXMF wire payloads |
| `serde` / `serde_json` | `1` | `MIT OR Apache-2.0` | Atomic JSON persistence under `storage_dir` |
| `uniffi` | `0.33.0` | `MPL-2.0` | Kotlin FFI bindings (`protocolx_rns.kt`) |

No GPL, AGPL, `reticulum-rs`, or `lxmf-rs` crates are used anywhere in `rust/protocolx-rns/`.

---

## 2. Implemented Architecture & UniFFI Contract

### 2.1 Dual Destinations & Local Persistence (`src/node.rs`, `src/store.rs`)
- Every `RnsNode` binds both:
  1. Primary application destination (`config.app_name` / `config.aspects`)
  2. Standard LXMF delivery destination (`lxmf.delivery`, returned by `lxmf_destination_hash_hex()`), using `ProofStrategy::ProveAll` and announcing `msgpack([display_name, stamp_cost])` via `announce_lxmf(display_name_override)`.
- When `NodeConfig.storage_dir` is set:
  - RNS runtime persistence (`NodePersistence::custom_dir`) stores transport identity/ratchets/path state inside `<storage_dir>/prns/`.
  - `LxmfLocalStore` (`src/store.rs`) persists inbound LXMF messages (`lxmf_messages.json`) and outbound delivery receipts (`lxmf_receipts.json`) atomically via temp-file write + rename across restarts, queryable via `persisted_lxmf_messages()` and `persisted_lxmf_receipts()`.
- On `AnnounceHeard`, the runtime resolves and caches peer `PublicIdentityMaterial` asynchronously via `DestinationIdentityQuery::Destination` so inbound LXMF signatures (`signature_valid`) and outbound opportunistic/propagated encryption work automatically.

### 2.2 Clean-Room LXMF Wire Codec (`src/lxmf.rs`)
- Wire framing: `dest_hash (16B) || src_hash (16B) || ed25519_sig (64B) || msgpack([timestamp_f64, title_bytes, content_bytes, fields_map])`.
- Signature input matches upstream LXMF: `sha256(dest_hash || src_hash || canonical_payload)` signed over `dest_hash || src_hash || canonical_payload || message_hash`.
- Supports:
  - `LxmfDeliveryMode::Opportunistic`: single encrypted packet (`SINGLE` `lxmf.delivery`), automatically rejected cleanly if payload exceeds single-packet limit (`416B` content budget).
  - `LxmfDeliveryMode::Direct`: link establishment + link-packet (`<= 431B` content) or RNS `Resource` (`> 431B`, verified with 5 KB–6 KB multi-chunk transfers) + link close + retry on transient loss (`Failed` receipt emitted if all attempts fail).
  - `LxmfDeliveryMode::Propagated`: encrypts `src_hash || sig || msgpack_payload` for recipient `PublicIdentityMaterial`, computes a fast SHA-256 PN proof-of-work stamp (`no_expand=True` workblock, 16-bit target), transmits `[timestamp, [lxm_data || 32B_stamp]]` to the PN `lxmf.propagation` destination, and supports fetching stored messages via `/get` (`fetch_propagated_lxmf(propagation_node_hex)` after `link.identify`).
- Supported LXMF fields: `FIELD_FILE_ATTACHMENTS (5)` (`Vec<LxmfAttachment>`), `FIELD_CUSTOM_TYPE (0xFB)`, and `FIELD_CUSTOM_DATA (0xFC)`.

### 2.3 Pluggable Interface Bridge (`src/bridge.rs`, `src/types.rs`)
- Implements RNS-04's contract via `RnsCustomInterfaceBridge` callback interface (`on_outbound_frame`, `on_interface_detached`) and `CustomBridgeInterface` implementing `prns_runtime::manifold::interface_seam::Interface`.
- Supports `CustomInterfaceKind::WifiAware`, `BleL2cap`, and `Custom`.
- Exposed `RnsNode` methods:
  - `attach_custom_interface(interface_tag, kind, hw_mtu, bitrate_bps, bridge)`
  - `detach_custom_interface(interface_tag)`
  - `push_custom_inbound_frame(interface_tag, frame)`
  - `update_custom_interface_metrics(interface_tag, metrics)` / `get_custom_interface_metrics(interface_tag)`
- Inbound LXMF packets/resources arriving over a custom bridge are decrypted, verified, persisted, and emitted as `RnsInboundEvent::LxmfReceived`.

---

## 3. Verification Commands & Observed Results

> Note: On macOS hosts with `sccache` configured as `RUSTC_WRAPPER`, set `RUSTC_WRAPPER=""` so parallel builds do not stall.

### 3.1 Unit + Bidirectional Official Python `rns==1.6.1` / `lxmf==1.3.1` Tests
Python venv location: `/Users/proerror/Documents/toc/protocol-x-rns-01/.venv-rns-poc/bin/python` (`rns==1.6.1`, `lxmf==1.3.1`; tests auto-detect sibling worktree venv or accept `PROTOCOLX_RNS_PYTHON`).

```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-03/rust/protocolx-rns && \
RUSTC_WRAPPER="" cargo test -- --nocapture
```

Observed result (8 tests passed, 0 failed):
- Unit test (`src/lib.rs`): `lxmf::tests::lxmf_pack_and_unpack_roundtrip_with_attachments` — **ok**
- `tests/lxmf_and_bridge_poc.rs` (3 passed):
  - `custom_bridge_wifi_aware_interface_with_metrics_and_loss_recovery` (custom `WIFI_AWARE` `LossyMemoryBridge`, RSSI/RTT/BW metrics tracking, simulated packet drop & retry recovery, clean detach callback) — **ok**
  - `bidirectional_rust_and_python_lxmf_opportunistic_direct_and_resource` (Rust <-> official Python `LXMF 1.3.1`: `OPPORTUNISTIC`, `DIRECT` + 5 KB Python `Resource` attachment, 6 KB Rust `Resource` attachment, `FAILED` delivery receipt + atomic persistence check across `RnsNode` restart) — **ok**
  - `rust_and_python_lxmf_propagation_node_roundtrip` (Rust -> Python `LXMRouter` PN `lxmf.propagation` ingest with stamp, and Python -> PN -> Rust `fetch_propagated_lxmf` `/get` retrieval & decryption) — **ok**
- `tests/rust_and_python_poc.rs` (4 passed, RNS-01 regression suite):
  - `identity_roundtrip_sign_verify_and_destination_hash` — **ok**
  - `rust_to_rust_udp_bidirectional_exchange` — **ok**
  - `rust_to_rust_tcp_and_udp_bidirectional_exchange` — **ok**
  - `bidirectional_rust_and_official_python_rns_poc` — **ok**

### 3.2 UniFFI Kotlin Bindings Regeneration
```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-03/rust/protocolx-rns && \
RUSTC_WRAPPER="" cargo build && \
RUSTC_WRAPPER="" cargo run --features bindgen-cli --bin uniffi-bindgen -- \
  generate --library target/debug/libprotocolx_rns.dylib \
  --language kotlin --out-dir bindings/kotlin
```

Observed output:
- `/Users/proerror/Documents/toc/protocol-x-rns-03/rust/protocolx-rns/bindings/kotlin/uniffi/protocolx_rns/protocolx_rns.kt` (`164,329` bytes / `4,487` lines).

### 3.3 Android Cross-Compilation Check (`arm64-v8a` & `x86_64`)
Installed Rust Android targets: `aarch64-linux-android`, `x86_64-linux-android`.

```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-03/rust/protocolx-rns && \
RUSTC_WRAPPER="" cargo check --target aarch64-linux-android --target x86_64-linux-android
```

Observed result:
- `cargo check` for both `aarch64-linux-android` and `x86_64-linux-android` completed with **exit code 0**.
- `RUSTC_WRAPPER="" cargo zigbuild --target aarch64-linux-android --target x86_64-linux-android --release` compiles all Rust crates and `protocolx-rns` object code cleanly, stopping only at the final `lld` shared-library link stage because no Android NDK sysroot (`libdl.so`, `liblog.so`, `libm.so`) is installed on this host (`/opt/homebrew/share/android-commandlinetools` contains no `ndk/` directory).
- On a host/CI runner with Android NDK + `cargo-ndk` installed, build JNI `.so` libraries directly into Android `jniLibs`:

```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-03/rust/protocolx-rns && \
RUSTC_WRAPPER="" cargo ndk -t arm64-v8a -t x86_64 -o ../../android/app/src/main/jniLibs build --release
```
