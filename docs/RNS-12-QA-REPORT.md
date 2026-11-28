# RNS-12 QA & Decision Gate Report (`rns/integration` + `rns/s21-device-qa` + `rns/s26-live-verified`)

- **Merged Baseline:** `origin/main` @ `f858fb4` ([PR #22](https://github.com/proerror77/protocol-x/pull/22), [PR #23](https://github.com/proerror77/protocol-x/pull/23), [PR #24](https://github.com/proerror77/protocol-x/pull/24); `RNS-01`..`RNS-11` `#9`–`#19` `CLOSED`)
- **Live S26 & Dual-Device Verification Branch:** `rns/s26-live-verified` (stacked on `origin/main` `f858fb4`)
- **Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-full`
- **GitHub Tracking:** [Epic #8](https://github.com/proerror77/protocol-x/issues/8) · [RNS-12 #20](https://github.com/proerror77/protocol-x/issues/20) · [RNS-13 Gate #21](https://github.com/proerror77/protocol-x/issues/21)
- **Overall Gate Verdict:** **Automated / Loopback QA + NDK `.so` Bundling + S21 & S26 Standalone + Live Dual-Phone S21↔S26 Wi-Fi Aware RF P2P PASS** · **Multi-Hop (3–5 Phones), 50–100 m LOS Walk & 24 h Soak PENDING (`RNS-13` remains BLOCKED)**

---

## 1. Automated, Loopback & Real-Device Verified Checks

All three language stacks, Android NDK `.so` packaging, standalone S21 (Android 15) & S26 (Android 17) hardware runs, and live dual-phone S21 ↔ S26 direct Wi-Fi Aware RF P2P (zero Internet) were independently verified with legacy Nearby Mesh (`NearbyRadios`, `NearbyMessages`, `NearbyLiveVoice`, `NearbyPrivate`, `NearbyService`) preserved behind `CommGateway` adapters.

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

- **Android NDK `.so` Build & APK Bundling (`rns/s21-device-qa` @ `97bc200` / `9d44af3`):**
  - Installed Android NDK `28.2.13676358` + `cargo-ndk 4.1.2`; built stripped ELF release binaries via `scripts/build-rns-android.sh`:
    - `android/app/src/main/jniLibs/arm64-v8a/libprotocolx_rns.so`: `4,030,296` B (`3.8M`)
    - `android/app/src/main/jniLibs/x86_64/libprotocolx_rns.so`: `4,446,928` B (`4.2M`)
  - Verified inside `android/app/build/outputs/apk/debug/app-debug.apk` via `unzip -l`:
    - `lib/arm64-v8a/libprotocolx_rns.so` (`4,030,296` B), `lib/x86_64/libprotocolx_rns.so` (`4,446,928` B), and `lib/arm64-v8a/libjnidispatch.so` (`176,520` B) + JNA ABIs.
  - Host Rust regression re-run (`f7c0774`): **14 passed, 0 failed** (`[RNS-12 BENCH]` `N=10`: `618ms` `6/6` `ptt 22/39ms`; `N=20`: `838ms` `8/8` `ptt 4/8ms`; `N=50`: `2542ms` `10/10` `ptt 6/12ms`; `N=100`: `10544ms` `10/10` `ptt 7/12ms`; `ifac_leaks=0`).

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
  - `assembleDebug`: **`BUILD SUCCESSFUL` in 7s** — generated `android/app/build/outputs/apk/debug/app-debug.apk` (**159M**, verified with bundled `libprotocolx_rns.so` on `rns/s21-device-qa` `9d44af3`).

### 1.4 Real Physical Device QA — S21 Ultra, S26 Ultra & Live S21 ↔ S26 Wi-Fi Aware RF P2P (`f858fb4`)

Both physical phones were verified using `-PopsAppIdSuffix=.rnsqa` (`com.mandonothing.ops.rnsqa.test`), leaving user `com.mandonothing.ops` (`versionCode=48`, `versionName=0.27.4`) completely untouched on both devices:
- **Galaxy S21 Ultra:** `SM-G9980`, serial `R5CR40E11PP`, Android 15 / SDK 35, `arm64-v8a`, `wifi.aware` + `bluetooth_le` supported.
- **Galaxy S26 Ultra:** `SM-S9480`, serial `100.108.44.112:5555`, Android 17 / SDK 37, `arm64-v8a`, `wifi.aware` + `bluetooth_le` supported.

#### Run 1 — Standalone S26 Ultra (Android 17 / SDK 37)
- **Command:** `ADB_SERIAL=100.108.44.112:5555 /Users/proerror/Documents/toc/protocol-x-rns-full/scripts/run-s21-device-qa.sh`
- **Observed Result (`RUN1_EXIT=0`):** **`OK (5 tests)` in `3.861s`** (`S21RealRnsDeviceTest` `t01`..`t05`), with Mac Python `RNS 1.6.1`/`LXMF 1.3.1` peer logging `completed` (`got_opp_s21=true`, `got_res_s21=true`, `got_ptt_floor=true`, `got_ptt_frame=true`, `got_done_ack=true`, `sent_lxmf_to_s21=true`, `sent_ptt_to_s21=true`).
  - Matches S21 Ultra baseline (`OK (5 tests)` in `4.021s` on `f7c0774`), confirming `arm64-v8a` `libprotocolx_rns.so` JNI load, Android 17 `RnsRuntimeService` + `AndroidKeyStore`, `WifiAwareManager` + `BluetoothManager` zero-leak teardown, bidirectional S26 ↔ Mac Python LXMF/Resource/PTT, and `RnsPttSessionTransport` self-check.

#### Run 2 — Live Dual-Phone S21 ↔ S26 Direct Encrypted P2P (Zero Internet)
- **Command:** `/Users/proerror/Documents/toc/protocol-x-rns-full/scripts/run-dual-device-qa.sh`
- **Observed Result (`RUN2_EXIT=0`):**
  - **S21 Publisher (`R5CR40E11PP`, Android 15):** **`OK (1 test)` in `8.299s`**
  - **S26 Subscriber (`100.108.44.112:5555`, Android 17):** **`OK (1 test)` in `7.748s`**
  - **Transport Convergence:** Initial BLE L2CAP probe (`rssi=-53` on S21) cleanly converged onto direct **Wi-Fi Aware (`WIFI_AWARE`)** (`BLE_L2CAP=UNAVAILABLE`) in ~`5 s` (`packetLossRate=0.0` on both phones):
    - S21 (`aware:536870914`): `rttMs=69`, `estimatedBandwidthBps=11,085,182` (`~11.1 Mbps`), final `RnsNode` `linkRttMs=29`.
    - S26 (`aware:268435457`): `rttMs=75`, `estimatedBandwidthBps=18,471,551` at discovery / `8,584,915` at completion, final `RnsNode` `linkRttMs=25`.
  - **IFAC Isolation & Zero-Leak Teardown:** Both S21 & S26 logged `droppedPublicConfig=0`, `droppedExpiredLease=0`, `droppedWrongIfac=0`, `droppedRevokedPeer=0`, `droppedRevokedDestination=0`, final `ifacSealed=30`, `ifacAccepted=30` on both phones, and `activeNetworkCallbacks=0`, `activeSockets=0`.
  - **Bidirectional Payloads:** Signed/encrypted LXMF (`DualPubOpp`/`DualSubOpp`, `DualDoneAck`), `4,608` B Resource attachments (`DualPubRes`/`DualSubRes`), and silent `20 ms`/`164 B` IMA ADPCM PTT frames + floor control (`pttTxMs=265` on S21 for floor grant + 5 frames + release).

---

## 2. P0 Target Status Matrix (Verified vs. Pending Multi-Phone Field Hardware)

| P0 Target | Automated & Dual-Phone S21/S26 Status (`f858fb4`) | Remaining Physical Field-Hardware Status (Required for Gate) |
| --- | --- | --- |
| **Android NDK Native `.so` Packaging** | **VERIFIED (S21 + S26 `arm64-v8a`):** `libprotocolx_rns.so` built via `scripts/build-rns-android.sh`, bundled in `app-debug.apk`, and loaded on both S21 & S26 (`isNativeAvailable=true`) | **DONE** |
| **Single-hop range (50–100 m)** | **PARTIAL (S21 ↔ S26 Wi-Fi Aware RF P2P PASS):** Direct `WIFI_AWARE` link (`~11.1 Mbps` / `8.6–18.5 Mbps`, `linkRttMs=25..29`, `0.0` loss) verified between S21 & S26 | **PENDING:** Measured `50–100 m` outdoor LOS distance walk test |
| **Multi-hop depth (1–5 hops)** | **VERIFIED (1-hop S21↔S26 RF + 1/3/5-hop Rust/Python Loopback):** Live 1-hop S21 ↔ S26 RF + 1/3/5-hop Rust chains (`A↔B↔C↔D↔E↔F`) + 3-hop Rust ↔ Python LXMF | **PENDING:** `3–5` physical Android phones chained over real Wi-Fi Aware / BLE L2CAP RF |
| **PTT E2E latency (<500 ms 1-hop, <800 ms 3-hop)** | **PARTIAL (S21↔S26 Silent ADPCM + Loopback):** S21 ↔ S26 `20ms`/`164B` ADPCM PTT floor + 5 frames + release completed (`pttTxMs=265`); Rust loopback `4–22 ms` (1-hop) / `8–39 ms` (3-hop) | **PENDING:** Multi-device live hardware mic-to-speaker audio PTT latency & `3`-hop physical RF timing |
| **Message & Task delivery (>= 99.9%)** | **VERIFIED (S21↔S26 RF + `N=10..100` Sim):** Live S21 ↔ S26 bidirectional LXMF + `4,608` B Resource (`0.0` loss) + 100% delivery across `N=10/20/50/100` tiers | **PENDING:** Multi-hour field packet-loss & intermittent mobility verification |
| **Network failover (< 5 s)** | **PARTIAL (S21↔S26 BLE→Wi-Fi Aware + JVM):** S21 ↔ S26 converged BLE probe -> Wi-Fi Aware in `~5 s`; `RnsNetworkManager` + `RnsPttHandoffCoordinator` `< 5,000 ms` in JVM tests | **PENDING:** Live Cellular/Wi-Fi WAN ↔ Wi-Fi Aware ↔ BLE L2CAP failover under active RF contention |
| **Duplicate actions (0)** | **VERIFIED (Backend + Android Unit):** Shared `execute_task_action` + `rns_envelopes` + `StageInput.idempotency_key` + `RnsOfflineSyncReconciler` `409` tests (`0` duplicates across `pytest` 63/63 & `unittest` 39/39) | **PENDING:** Live multi-path chaos soak against running Ops + `RnsGateway` |
| **Public RNS dependency (0)** | **VERIFIED (S21 + S26 + Sim):** Both S21 & S26 logged `droppedPublicConfig=0`, `ifacSealed=30`, `ifacAccepted=30`, rogue wrong-IFAC blocked (`t04`), `ifac_leaks=0` across `N=10..100` | **VERIFY ON 3–5 PHONE FIELD BUILD:** Packet capture / log audit during multi-hop field walk |
| **24 h endurance** | Not yet run (requires long-running soak) | **PENDING:** 24 h continuous multi-device soak monitoring RSS memory, FDs, battery & wake locks |
| **Scale (10 / 20 / 50 / 100 nodes)** | **VERIFIED (Simulation):** `rns12_scale_chaos.rs` passed `N=10, 20, 50, 100` (100% delivery, `0` IFAC leaks) | **PENDING (Optional/Dense Field):** Real RF density test (target cluster size >= 10 physical devices) |

---

## 3. Decision Gate Verdict (`RNS-13` Remains Blocked)

Per [docs/RNS-ROADMAP.md](RNS-ROADMAP.md) Section 7, **RNS-13 (`#21` — Retire Custom Mesh Duplicates) MUST NOT proceed** until the remaining multi-phone physical field-hardware checks are completed and signed off (NDK `.so` bundling, standalone S21/S26 Android 15/17 runs, and live 1-hop S21 ↔ S26 direct Wi-Fi Aware RF P2P are now verified):
1. ~~Build and bundle `libprotocolx_rns.so` (`arm64-v8a`, `x86_64`) into the Android APK & verify on S21/S26~~ — **DONE** (`scripts/build-rns-android.sh`, S21 `4.021s` / S26 `3.861s` / S21↔S26 `RUN2_EXIT=0`).
2. Verify `50–100 m` outdoor Wi-Fi Aware LOS range walk test across physical Android phones.
3. Verify `3–5 hop` end-to-end physical Android RF chain (LXMF + offline task actions + BLE L2CAP fallback).
4. Measure multi-device live hardware mic-to-speaker audio PTT E2E latency (`< 500 ms` 1-hop, `< 800 ms` 3-hop) and live `< 5 s` WAN ↔ RF failover under RF contention.
5. Complete a `24 h` continuous device endurance soak with zero OOM/FD leaks or duplicate task executions.
