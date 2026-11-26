# RNS-01 Technical Evaluation & Rust Core / UniFFI POC

**Branch:** `rns/01-rust-core-uniffi` (from `origin/main` `7971771`)  
**Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-01`  
**Crate:** [`rust/protocolx-rns/`](../rust/protocolx-rns/Cargo.toml) (`protocolx_rns` v0.1.0)

---

## 1. Rust RNS & LXMF Crate Evaluation

All candidate crates on `crates.io` were downloaded and inspected directly against Protocol X requirements: (a) wire compatibility with official Python `rns` (`1.6.1`) and `lxmf` (`1.3.1`), (b) commercial permissive licensing (`MIT` / `Apache-2.0` vs copyleft or non-OSI Reticulum License), (c) active maintenance & code safety, and (d) Android NDK + Mozilla UniFFI cross-compilation feasibility.

| Crate Family (crates.io / GitHub) | Version | License | Wire & LXMF Scope | Maintenance & Safety | Android NDK + UniFFI Feasibility | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| **`personal-rns`** (`prns-core`, `prns-runtime`, `prns-runtime-tokio`, `prns-interfaces-tokio`, `prns-ffi`)<br>`KenAKAFrosty/Prns` | `0.3.7` | **`MIT OR Apache-2.0`** | Full RNS Layers 1–3: Identity (X25519+Ed25519), Single/Group/Plain destinations, Announces & Ratchets, ECDH Links, Channel/Buffer, Resources, Request/Response, IFAC, multi-hop routing, Persistence. Qualified against RNS 1.5.4; verified live against Python RNS `1.6.1` in this POC. **No built-in LXMF crate yet** (LXMF wire codec planned in RNS-03). | Actively maintained (`0.3.7`, MSRV `1.90`), `#![forbid(unsafe_code)]`, `no_std` core (`prns-core`), explicit mobile `CryptoPoolConfig`, native `WifiAwareBackend` (`WifiAwareAuto`) matching RNS-04. | **Excellent.** Pure Rust with `tokio-host,tcp,udp` (zero C/SQLite/bzip2-sys dependencies). Exports cleanly via `uniffi = "0.33.0"` proc-macros (`cdylib` + `staticlib`). | **SELECTED (RNS-01 Core)** |
| **`reticulum-sdk`**<br>`ReticulaLabs/reticulum-sdk` | `2.3.13` | `MIT` | RNS transport, Identity, Single/Plain destinations, Announces, Links, Channel, Resource, TCP/UDP/Backbone/Serial/RNode/LoRa. No LXMF crate. | Active (`2.3.13`, edition `2024`), pure Rust (`bzip2 0.6` uses `libbz2-rs-sys`), but lacks `WifiAwareBackend` abstraction and mobile persistence hooks. | Good (`--no-default-features --features alloc` drops `tokio-serial` / `gpio-cdev`), though `bzip2` is unconditional. | Runner-up |
| **`styrene-rns` + `styrene-lxmf`**<br>`styrene-lab/styrene-rs` | `0.1.0` | `MIT` | `rns_core` + `lxmf_core`. `styrene-rns` has Python interop fixtures; `styrene-lxmf` `AGENTS.md` states *"zero integration tests"* and *"No interop tests against Python LXMF"*. | Early `0.1.0`. `styrene-lxmf` requires MSRV `1.97`; `styrene-rns` `AGENTS.md` notes incomplete UDP IFAC and `.unwrap()` in `fernet.rs`. | `transport` feature pulls `rusqlite 0.31` + `bzip2 0.4` (`bzip2-sys` C dependency), complicating NDK builds. | Not selected (early maturity; revisit `styrene-lxmf` wire types or clean-room LXMF in RNS-03) |
| **`reticulum-rs` + `lxmf`** (`reticulum-rs-core`, `reticulum-rs-transport`, `lxmf-runtime`, `rns-embedded-ffi`)<br>`FreeTAKTeam/LXMF-rs` | `0.17.0` | **`EPL-2.0 OR GPL-2.0-or-later`** | Highest RNS + LXMF feature parity (1,655 RNS + 202 LXMF callables, Sideband/Columba interop, propagation, RPC). | Very active (`0.17.0`, MSRV `1.89`). | `reticulum-rs-transport` pulls `rusqlite` by default (can disable via `default-features = false`). Has C FFI crate (`rns-embedded-ffi`), not UniFFI. | Rejected for core due to **weak-copyleft EPL-2.0 / GPL-2.0-or-later** obligations on static/cdylib distribution |
| **`retinue` + `outrider`**<br>`merely-made/retinue` | `0.3.0` | **`Reticulum License`** (non-OSI) | RNS (`retinue`) + LXMF (`outrider`) qualified against RNS 1.5.4 / LXMF 1.1.1; **UDP is not implemented** (`retinue` README). | Active pre-1.0 (`0.3.0`, MSRV `1.88`), `#![no_std]` core. | TCP + raw interface only (no UDP). | Rejected: **non-OSI Reticulum License** (no SPDX ID, anti-AI/ML dataset & harm clauses) + missing UDP |
| **`rns-core` + `rns-net` + `lxmf-core`**<br>`lelloman/rns-rs` / `lxmf-rs` | `0.10.0` / `0.15.0` / `0.3.1` | **`Reticulum License`** (non-OSI) | Full RNS + LXMF wire & node driver. | Active, golden traces & proptests. | Pure Rust by default (optional `aws-lc-sys`). | Rejected: **non-OSI Reticulum License** |
| **`reticulum`**<br>`BeechatNetworkSystemsLtd/Reticulum-rs` | `0.1.0` | `MIT` | Early RNS port (`TCP`, `UDP`, `Kaonic` gRPC). | Superseded in ecosystem by `reticulum-sdk` (`2.3.13`). | `build.rs` compiles `kaonic.proto` (`protoc` build dependency). | Rejected (superseded) |

---

## 2. Commercial Licensing Assessment

- **Why `personal-rns` (`MIT OR Apache-2.0`) wins:**
  - **Zero copyleft propagation:** Unlike `EPL-2.0 OR GPL-2.0-or-later` (`reticulum-rs`, `lxmf`, `coyote-rns-transport`), `MIT OR Apache-2.0` imposes no source-disclosure requirement when statically/dynamically linked into `libprotocolx_rns.so` inside the proprietary Protocol X Android APK (`com.mandonothing.ops`), and includes Apache-2.0's explicit patent grant.
  - **No non-OSI custom restrictions:** `retinue`/`outrider` and `lelloman/rns-rs` use the **Reticulum License**, which is not OSI-approved (no standard SPDX identifier) and includes broad prohibitions on AI/ML training dataset derivation and harm-capable systems—creating legal friction for Protocol X's enterprise deployments and on-device/server ASR/LLM workflows (`Whisper`, `sherpa-onnx`).
  - **UniFFI dependency (`uniffi = "0.33.0"`):** Licensed under `MPL-2.0` (file-level permissive-compatible; Mozilla's generated Kotlin/C-FFI scaffolding binds cleanly without altering `protocolx-rns` or `personal-rns` licensing).

---

## 3. Wire-Protocol Compatibility Notes (RNS `1.6.1` vs `personal-rns` `0.3.7`)

1. **Verified Wire Interoperability:**
   - **Identity & Destination Hashing:** `PrivateIdentityMaterial` / `PublicIdentityMaterial` (`X25519` 32B + `Ed25519` 32B) and `derive_destination_hash` produce identical 16-byte truncated destination hashes as Python `RNS.Destination(..., SINGLE, ...)`.
   - **Announces:** Bidirectional Ed25519-signed announces over `TCPClientInterface` <-> `TcpServer` validate cleanly in both directions.
   - **Single-Packet Encrypted Messaging:** Ephemeral X25519 ECDH + Fernet (AES-256-CBC + HMAC-SHA256) encrypted packets and `PROVE_ALL` cryptographic delivery proofs interoperate in both directions (`Rust -> Python` and `Python -> Rust`).
   - **ECDH Links & Encrypted Link Packets:** Link handshake (`LinkRequest` -> `LinkProof` -> `LinkId` derivation -> `LinkData`) interoperates bidirectionally between `personal-rns` and official Python `RNS 1.6.1`.
2. **Important RNS `1.6.1` Behavior Observed During POC:**
   - Official Python `RNS 1.6.1` sets `RNS.Transport.STAMP_DESTINATIONS = True` with `DESTINATION_COST = 25.0` by default, causing a single-core Python process to spend ~200+ seconds computing a PoW stamp before emitting its first announce. In private/tactical Protocol X mesh networks (and in our local POC), destination PoW stamping must be explicitly disabled (`RNS.Transport.STAMP_DESTINATIONS = False`) or tuned via `[stamps]` so nodes announce immediately.
   - Python `RNS.Destination` defaults to `PROVE_NONE`; callers expecting `send_single_packet` delivery receipts (`PacketReceiptDelivered`) must configure `PROVE_ALL` (or `PROVE_APP`) on receiving destinations, as `protocolx-rns` does via `ProofStrategy::ProveAll`.
3. **LXMF Roadmap Note (RNS-03):**
   - `personal-rns` provides the full RNS transport, link, channel, and resource foundation but does not bundle LXMF. In **RNS-03**, Protocol X can implement a clean-room `MIT OR Apache-2.0` LXMF wire codec (MsgPack 4-element envelope `[destination_hash, source_hash, signature, packed_payload]` over opportunistic single-packet, direct link, and Resource transfers) validated against the isolated `lxmf==1.3.1` venv installed in `.venv-rns-poc`.

---

## 4. RNS-01 Crate Architecture & UniFFI Surface

Located at `rust/protocolx-rns/`:

- [`Cargo.toml`](../rust/protocolx-rns/Cargo.toml): `crate-type = ["cdylib", "staticlib", "rlib"]`; features `default = []`, `bindgen-cli = ["uniffi/cli"]`.
- [`src/lib.rs`](../rust/protocolx-rns/src/lib.rs): Calls `uniffi::setup_scaffolding!();` (proc-macro UniFFI export—no `.udl` drift).
- [`src/error.rs`](../rust/protocolx-rns/src/error.rs): `ProtocolxRnsError` (`#[derive(uniffi::Error)]`).
- [`src/identity.rs`](../rust/protocolx-rns/src/identity.rs): `RnsIdentity` (`generate`, `from_secret_bytes`, `secret_bytes`, `public_key_bytes`, `identity_hash_hex`, `destination_hash_hex`, `sign`, `verify_with_public_key`) + top-level `generate_identity`, `load_identity_from_secret`, `compute_destination_hash_hex`.
- [`src/types.rs`](../rust/protocolx-rns/src/types.rs): `NodeConfig`, `NodeStatus`, `RnsInboundEvent` (`AnnounceHeard`, `SinglePacketReceived`, `LinkEstablished`, `LinkPacketReceived`, `LinkClosed`, `ResourceReceived`), and `RnsEventListener` (`#[uniffi::export(callback_interface)]`).
- [`src/node.rs`](../rust/protocolx-rns/src/node.rs): `RnsNode` managing a dedicated Tokio node thread (`!Send`-safe `PrnsNode::run_until` isolation) + `PrnsNodeHandle` (`Send + Sync`) exposing:
  - Lifecycle: `new`, `start`, `stop`, `is_running`, `status`, `local_tcp_addr`
  - Discovery: `announce`, `known_destinations`
  - Messaging & Links: `send_single_packet`, `establish_link`, `send_link_packet`, `send_resource`, `close_link`
  - Event delivery: `set_listener`, `poll_events`, `wait_for_event`
- [`src/bin/uniffi_bindgen.rs`](../rust/protocolx-rns/src/bin/uniffi_bindgen.rs): Self-contained `uniffi-bindgen` CLI binary behind `--features bindgen-cli`.

---

## 5. Android NDK + UniFFI Build Path

Target Android app config ([`android/app/build.gradle.kts`](../android/app/build.gradle.kts)): `minSdk = 26`, `targetSdk = 35`, `compileSdk = 36`, `JavaVersion.VERSION_17`.

1. **Dependencies are 100% Pure Rust (NDK-friendly):**
   - `personal-rns` (`tokio-host,tcp,udp`) + `bzip2 0.6` (`libbz2-rs-sys`) require **zero external C/C++ libraries** (`openssl`, `sqlite3`, `libbz2`, `protoc` are all absent).
2. **Android Rust Targets & Build Commands** (in a CI/dev environment with Android NDK r26+ and `cargo-ndk` installed):
   ```sh
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   cargo install cargo-ndk

   cd rust/protocolx-rns
   cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
     -p 26 \
     -o ../../android/app/src/main/jniLibs \
     build --release
   ```
3. **Kotlin UniFFI Binding Generation** (verified locally on host `.dylib`):
   ```sh
   cd rust/protocolx-rns
   cargo build
   cargo run --features bindgen-cli --bin uniffi-bindgen -- \
     generate --library target/debug/libprotocolx_rns.dylib \
     --language kotlin \
     --out-dir bindings/kotlin
   ```
   *(In RNS-02/RNS-03 integration, `--out-dir` targets `android/app/src/main/java` and `net.java.dev.jna:jna:<version>@aar` is added to `android/app/build.gradle.kts`.)*
4. **RNS-04 Wi-Fi Aware Alignment:**
   - `personal-rns` provides `prns_core::interfaces::wifi_aware::WifiAwareBackend` (`local_token`, `set_discovery`, `request_data_path`, `abandon_data_path`, `next_event`). In RNS-04, Android's `WifiAwareSession` / `PeerHandle` / `WifiAwareNetworkSpecifier` (bridged via Kotlin callback interface) will plug directly into `WifiAwareAuto<B>` without custom framing hacks.

---

## 6. POC Verification Commands & Observed Output

### Isolated Python Environment Setup
```sh
python3 -m venv .venv-rns-poc
.venv-rns-poc/bin/pip install rns lxmf
# Installed: rns-1.6.1, lxmf-1.3.1
```

### Run Rust Unit, Rust↔Rust (TCP & UDP), and Rust↔Python RNS Bidirectional POC Tests
```sh
RUSTC_WRAPPER="" cargo test --manifest-path rust/protocolx-rns/Cargo.toml -- --nocapture
```
*(Note: `RUSTC_WRAPPER=""` bypasses the local `sccache` daemon if `sccache` stalls under parallel builds; plain `cargo test` is equivalent on clean environments.)*

Observed test cases in [`rust/protocolx-rns/tests/rust_and_python_poc.rs`](../rust/protocolx-rns/tests/rust_and_python_poc.rs) + [`rust/protocolx-rns/tests/python_rns_peer.py`](../rust/protocolx-rns/tests/python_rns_peer.py):
- `identity_roundtrip_sign_verify_and_destination_hash` — 64B keypair persistence, Ed25519 sign/verify + tamper rejection, RNS Single destination hash parity.
- `rust_to_rust_tcp_and_udp_bidirectional_exchange` — Two `RnsNode` instances exchanging announces and encrypted single packets over local TCP.
- `rust_to_rust_udp_bidirectional_exchange` — Two `RnsNode` instances exchanging announces and encrypted single packets over local UDP.
- `bidirectional_rust_and_official_python_rns_poc` — Live `RnsNode` (`TcpServer`) <-> official Python `RNS 1.6.1` (`TCPClientInterface`) exchanging:
  1. Bidirectional RNS Announces (`protocolx.node`)
  2. Encrypted Single Packets (`PING-SINGLE-FROM-PYTHON-RNS` & `PONG-SINGLE-FROM-RUST-PRNS`) with `PROVE_ALL` delivery receipts
  3. ECDH Link handshake (`establish_link`) + bidirectional Link packets (`HELLO-LINK-FROM-PYTHON` & `HELLO-LINK-FROM-RUST`)

Observed `cargo test` summary (`tests/rust_and_python_poc.rs`):
```text
running 4 tests
test identity_roundtrip_sign_verify_and_destination_hash ... ok
test rust_to_rust_udp_bidirectional_exchange ... ok
test rust_to_rust_tcp_and_udp_bidirectional_exchange ... ok
test bidirectional_rust_and_official_python_rns_poc ... ok

test result: ok. 4 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.87s
```

Observed `uniffi-bindgen` Kotlin output (generated from `target/debug/libprotocolx_rns.dylib`):
```text
Code generation complete, formatting with ktlint (use --no-format to disable)
bindings/kotlin/uniffi/protocolx_rns/protocolx_rns.kt (126,028 bytes)
```
