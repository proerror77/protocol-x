# RNS-12 QA & Decision Gate Report (`rns/integration`)

- **Branch / Commit:** `rns/integration` @ `219f499` (ahead of `origin/main` `7971771` by 16 commits; unpushed)
- **Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-full`
- **GitHub Tracking:** [Epic #8](https://github.com/proerror77/protocol-x/issues/8) · [RNS-12 #20](https://github.com/proerror77/protocol-x/issues/20) · [RNS-13 Gate #21](https://github.com/proerror77/protocol-x/issues/21)
- **Overall Gate Verdict:** **Automated / Loopback QA PASS** · **Physical Field-Hardware Gate PENDING (`RNS-13` remains BLOCKED)**

---

## 1. Automated & Loopback Verified Checks

All three language stacks were independently verified on `rns/integration` (`219f499`) with legacy Nearby Mesh (`NearbyRadios`, `NearbyMessages`, `NearbyLiveVoice`, `NearbyPrivate`) preserved behind `CommGateway` adapters.

### 1.1 Rust Core & Multi-Node Scale / Interop (`rust/protocolx-rns`)

- **Command:**
  ```sh
  cd /Users/proerror/Documents/toc/protocol-x-rns-full/rust/protocolx-rns && \
  RUSTC_WRAPPER="" PROTOCOLX_RNS_PYTHON="/Users/proerror/Documents/toc/protocol-x-rns-01/.venv-rns-poc/bin/python" \
  cargo test -- --nocapture
  ```
- **Observed Result:** **14 passed, 0 failed**
  - Unit tests (`src/lib.rs`): `3 passed` (`0.00s`) — IFAC derivation & actor binding roundtrip, PTT frame/floor codec & arbitration, LXMF pack/unpack with attachments.
  - `tests/rust_and_python_poc.rs`: `4 passed` (`0.81s`) — identity roundtrip, Rust ↔ Rust UDP & TCP/UDP, bidirectional Rust ↔ official Python `rns==1.6.1` packet exchange.
  - `tests/lxmf_and_bridge_poc.rs`: `3 passed` (`21.01s`) — custom `WIFI_AWARE` bridge metrics & loss recovery, bidirectional Rust ↔ Python `lxmf==1.3.1` opportunistic/direct/Resource transfer, Python LXMF propagation node roundtrip.
  - `tests/rns_05_06_10_multihop.rs`: `3 passed` (`6.50s`) — IFAC isolation/rotation/revocation & Python `rns==1.6.1`/`lxmf==1.3.1` interop; 5-hop Rust chain (`A↔B↔C↔D↔E↔F`), diamond relay-drop reconvergence, and 3-hop Rust ↔ Python LXMF; 3-hop PTT frame & floor preemption (`Grant`/`Preempt`/`Release`).
  - `tests/rns12_scale_chaos.rs`: `1 passed` (`6.29s`) — hierarchical multi-hop IFAC-isolated `CustomBridgeInterface` (`WifiAware`) clusters with rogue wrong-IFAC node, partition chaos, and peer revocation (`[RNS-12 BENCH]` observed output on `219f499`):

| Node Scale | Converge (ms) | LXMF Delivered | IFAC Leaks | 1-Hop PTT Loopback (ms) | 3-Hop PTT Loopback (ms) |
| ---: | ---: | :---: | ---: | ---: | ---: |
| **10** | 630 | 6/6 (100%) | 0 | 2 | 3 |
| **20** | 712 | 8/8 (100%) | 0 | 4 | 3 |
| **50** | 1046 | 10/10 (100%) | 0 | 3 | 4 |
| **100** | 2104 | 10/10 (100%) | 0 | 2 | 3 |

- **Android Rust Cross-Check:**
  - `RUSTC_WRAPPER="" cargo check --target aarch64-linux-android --target x86_64-linux-android`: **exit 0** (`16.28s`). Full `.so` linking requires an Android NDK sysroot (`cargo-ndk`).

### 1.2 Python TOC Backend, Envelope Sync & RNS/TAK Gateway

- **Commands:**
  ```sh
  /Users/proerror/Documents/toc/protocol-x-rns-backend/.venv-rns-backend/bin/python -m unittest discover -s tests -v
  /Users/proerror/Documents/toc/protocol-x-rns-backend/.venv-rns-backend/bin/python -m pytest -v
  ```
- **Observed Results:**
  - `unittest`: **Ran 39 tests in 8.415s — OK**
  - `pytest`: **63 passed, 7 subtests passed in 11.67s**
- **Verified Scope:** `POST /api/field/events/{event_id}/nearby` RNS identity binding & IFAC derivation, immediate revocation rotation + CRL (`GET /nearby/revocations`), `POST /api/rns/v1/envelopes` & `/envelopes/batch` 3-tier deduplication and `0`-duplicate action execution (`execute_task_action` + `StageInput.idempotency_key` + `409` version-drift details), Python `rns==1.6.1`/`lxmf==1.3.1` private-IFAC `RnsGateway`, and bidirectional TAK CoT projection + echo suppression.

### 1.3 Android App & Integrated RNS Stack (`android/`)

- **Commands (JDK 17 + Android SDK 36):**
  ```sh
  cd /Users/proerror/Documents/toc/protocol-x-rns-full/android && \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew testDebugUnitTest --rerun-tasks --no-daemon --console=plain && \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew assembleDebug --no-daemon --console=plain
  ```
- **Observed Results:**
  - `testDebugUnitTest --rerun-tasks`: **`BUILD SUCCESSFUL` in 33s** — **25 suites, 84 tests, 0 failures, 0 errors, 0 skipped**.
    - Includes `RnsFullIntegrationTest` (2 tests using `IntegratedFakeCoreEngine`, `FakeBridgeSink`, `NoopAudioIo`, and `DefaultCommGateway` verifying cross-package wiring across `comm.aware`, `comm.network`, `comm.ptt`, `comm.rns`, `RnsOfflineSyncReconciler`, and `<5s` LiveKit ↔ RNS PTT handoff), `RnsWave1IntegrationTest` (5 tests), `RnsAwareIfacBridgeTest` (2), `RnsNetworkAndTopologyTest` (2), `RnsPttTransportTest` (4), `RnsAwareAdaptersTest` (3), `PeerCapacityManagerTest` (4), `TrackedResourceRegistryTest` (2), `CommInterfaceTest` (5), `RnsLocalStoreTest` (2), `RnsRuntimeCoordinatorTest` (3), `RnsLxmfTransportAdapterTest` (3), plus 47 existing baseline tests.
  - `assembleDebug`: **`BUILD SUCCESSFUL` in 7s** — generated `android/app/build/outputs/apk/debug/app-debug.apk` (**159M**).

---

## 2. P0 Target Status Matrix (Automated vs. Pending Field Hardware)

| P0 Target | Automated / Loopback Status (`219f499`) | Physical Field-Hardware Status (Required for Gate) |
| --- | --- | --- |
| **Single-hop range (50–100 m)** | Not testable in loopback / JVM unit tests | **PENDING:** Two-device Android Wi-Fi Aware LOS field walk test |
| **Multi-hop depth (1–5 hops)** | **VERIFIED (Rust ↔ Python / Loopback):** 1/3/5-hop Rust chains (`A↔B↔C↔D↔E↔F`) + 3-hop Rust ↔ Python LXMF + Android `RnsTopologyDiagnostics` integration | **PENDING:** 3–5 physical Android phones chained over real Wi-Fi Aware / BLE L2CAP RF |
| **PTT E2E latency (<500 ms 1-hop, <800 ms 3-hop)** | **VERIFIED (Loopback IPC):** `1–4 ms` 1-hop & `3–4 ms` 3-hop Rust frame relay (`rns12_scale_chaos.rs`); Android ADPCM + `RnsPttJitterBuffer` unit-tested | **PENDING:** Real Android microphone-to-speaker hardware audio capture, ADPCM/Opus encode, Wi-Fi Aware RF hops & playout measurement |
| **Message & Task delivery (>= 99.9%)** | **VERIFIED (Loopback / Simulation):** 100% delivery across `N=10/20/50/100` tiers + Python LXMF resource/propagation tests + `RnsResourceTransferManager` chunk resume | **PENDING:** Multi-hour field packet-loss & intermittent mobility verification on physical devices |
| **Network failover (< 5 s)** | **VERIFIED (JVM Unit / Integration):** `DefaultCommGateway` + `RnsNetworkManager` + `RnsPttHandoffCoordinator` transition verified `< 5,000 ms` (`RnsFullIntegrationTest`, `RnsNetworkAndTopologyTest`) | **PENDING:** Live Cellular/Wi-Fi WAN ↔ Android Wi-Fi Aware ↔ BLE L2CAP radio handoff under real RF contention |
| **Duplicate actions (0)** | **VERIFIED (Backend + Android Unit):** Shared `execute_task_action` + `rns_envelopes` + `StageInput.idempotency_key` + `RnsOfflineSyncReconciler` `409` tests (`0` duplicates across `pytest` 63/63 & `unittest` 39/39) | **PENDING:** Live multi-path chaos soak against running Ops + `RnsGateway` |
| **Public RNS dependency (0)** | **VERIFIED:** `ifac_leaks=0` across `N=10..100`, `require_private_ifac` + Python `share_instance=No` / `0` public interfaces, `RnsIfacSecurityPolicy` blocks public config | **VERIFY ON DEVICE:** Packet capture / log audit on physical APK build |
| **24 h endurance** | Not yet run (requires long-running soak) | **PENDING:** 24 h continuous multi-device soak monitoring RSS memory, FDs, battery & wake locks |
| **Scale (10 / 20 / 50 / 100 nodes)** | **VERIFIED (Simulation):** `rns12_scale_chaos.rs` passed `N=10, 20, 50, 100` (100% delivery, `0` IFAC leaks, `630–2104 ms` convergence) | **PENDING (Optional/Dense Field):** Real RF density test (target cluster size >= 10 physical devices) |
| **Android NDK Native `.so` Packaging** | `cargo check` passed for `aarch64-linux-android` & `x86_64-linux-android`; `RnsNativeLoader` safely guards missing `.so` in JVM/host builds | **PENDING:** Install Android NDK sysroot and bundle `jniLibs/<abi>/libprotocolx_rns.so` via `cargo-ndk` into `app-debug.apk` / release APK |

---

## 3. Decision Gate Verdict (`RNS-13` Remains Blocked)

Per [docs/RNS-ROADMAP.md](RNS-ROADMAP.md) Section 7, **RNS-13 (`#21` — Retire Custom Mesh Duplicates) MUST NOT proceed** until the five pending items above are completed and signed off:
1. Build and bundle `libprotocolx_rns.so` (`arm64-v8a`, `x86_64`) into the Android APK using an Android NDK sysroot (`cargo-ndk`).
2. Verify `50–100 m` single-hop Wi-Fi Aware LOS range on physical Android devices.
3. Verify `3–5 hop` end-to-end physical Android RF chain (LXMF + offline task actions + BLE L2CAP fallback).
4. Measure physical Android hardware audio PTT E2E latency (`< 500 ms` 1-hop, `< 800 ms` 3-hop) and `< 5 s` WAN ↔ Mesh failover.
5. Complete a `24 h` continuous device endurance soak with zero OOM/FD leaks or duplicate task executions.
