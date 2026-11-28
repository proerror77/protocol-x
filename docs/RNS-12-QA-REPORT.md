# RNS-12 QA & Decision Gate Report (`rns/integration` + `rns/s21-device-qa` + `rns/s26-dual-device-qa`)

- **Merged Baseline:** `origin/main` @ `d38a750` ([PR #22](https://github.com/proerror77/protocol-x/pull/22) & [PR #23](https://github.com/proerror77/protocol-x/pull/23); `RNS-01`..`RNS-11` `#9`–`#19` `CLOSED`)
- **Dual-Device QA Harness Branch:** `rns/s26-dual-device-qa` @ `f7c0774` (stacked on `origin/main` `d38a750`)
- **Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-full`
- **GitHub Tracking:** [Epic #8](https://github.com/proerror77/protocol-x/issues/8) · [RNS-12 #20](https://github.com/proerror77/protocol-x/issues/20) · [RNS-13 Gate #21](https://github.com/proerror77/protocol-x/issues/21)
- **Overall Gate Verdict:** **Automated / Loopback QA + NDK `.so` Bundling + Single-Device S21 RNS/Python E2E PASS (`f7c0774`)** · **S26 Android 17 & Live S21↔S26 RF Field Gate PENDING (`RNS-13` remains BLOCKED)**

---

## 1. Automated, Loopback & Single-Device S21 Verified Checks

All three language stacks plus real Android NDK `.so` packaging and physical S21 Ultra (`SM-G9980`) instrumented QA were independently verified with legacy Nearby Mesh (`NearbyRadios`, `NearbyMessages`, `NearbyLiveVoice`, `NearbyPrivate`, `NearbyService`) preserved behind `CommGateway` adapters.

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

### 1.4 Real Physical Device QA — S21 Ultra Verified & S26 / Dual-Device Harness (`rns/s26-dual-device-qa` @ `f7c0774`)

- **Target Device Verified:** Samsung Galaxy S21 Ultra (`SM-G9980`, serial `R5CR40E11PP`, Android 15 / SDK 35, `arm64-v8a`, `wifi.aware` + `bluetooth_le` supported). Installed under `-PopsAppIdSuffix=.rnsqa` (`com.mandonothing.ops.rnsqa.test`), leaving user `com.mandonothing.ops` `versionCode=48` / `0.27.4` untouched.
- **S21 Regression Command (`f7c0774`):** `/Users/proerror/Documents/toc/protocol-x-rns-full/scripts/run-s21-device-qa.sh` (`S21RealRnsDeviceTest`, updated with `checkSelfPermission` guard before `GrantPermissionRule` + parameterized `ADB_SERIAL` / `pm grant`).
- **Observed Result on S21 (`f7c0774`):** **`OK (5 tests)` in `4.021s`** (`t01`..`t05`), with Python `RNS 1.6.1`/`LXMF 1.3.1` peer logging `completed` (`got_opp_s21=true`, `got_res_s21=true`, `got_ptt_floor=true`, `got_ptt_frame=true`, `got_done_ack=true`, `sent_lxmf_to_s21=true`, `sent_ptt_to_s21=true`):
  - `t01`: Loaded real `arm64-v8a` `libprotocolx_rns.so` (`isNativeAvailable=true`); verified `generateIdentity`, `loadIdentityFromSecret`, `deriveEventIfacConfig`, `bindActorDeviceToRns`, and `verifyActorDeviceBinding` over UniFFI JNI.
  - `t02`: Verified `UniFFIRnsCoreEngine` (`RnsNode`) + `RnsRuntimeService` foreground service + `AndroidKeyStore` AES-GCM `RnsLocalStore` identity persistence across service restart and clean shutdown on Android 15.
  - `t03`: Verified S21 `WifiAwareManager` (`FEATURE_WIFI_AWARE`) + `BluetoothManager` (`FEATURE_BLUETOOTH_LE`, `AndroidBleL2capDriver.isAvailable=true`) + zero-leak `TrackedNetworkCallbackRegistry`/`RnsWifiAwareAdapter`/`RnsBleL2capAdapter` teardown (`activeNetworkCallbacks=0`, `activeSockets=0`).
  - `t04`: Verified real bidirectional **S21 `RnsNode` ↔ Mac Python `RNS 1.6.1`/`LXMF 1.3.1` (`private IFAC protocolx.s21.qa`)** over `adb reverse` (`17421`/`17422`): rogue wrong-IFAC peer isolation (`rogueLxmf` & `roguePrimary` blocked), `OppS21` & `ResS21` (`6,144` B Resource) + `OppPyS21` & `ResPyS21` (`5,120` B Resource) all `signature_validated=true`, `S21DoneAck` received, and PTT floor (`GRANT`/`RELEASE`) + `20ms`/`164B` IMA ADPCM PTT frames verified (`completed` exit `0`).
  - `t05`: Verified S21 `RnsPttAdpcmCodec` (`164B`/`320` samples/`20ms`) + `RnsPttSessionTransport` (`14` frames played, archived `VoiceCapturedClip`).
- **S26 Ultra & Dual-Device Harness (`f7c0774` — Code-Reviewed & Compiled; Live Execution Pending Online S26):**
  - Added `DualDeviceRnsTest.kt` (442 lines, gated by `-e role publisher|subscriber`, wiring `RnsWifiAwareAdapter` + `RnsBleL2capAdapter` + `RnsAwareBridgeConnector` + `RnsIfacFrameGuard` + `UniFFIRnsCoreEngine`, silent `20ms`/`164B` ADPCM PTT, synchronous `awareCoordinator.close()` leak assertions) and `scripts/run-dual-device-qa.sh`.
  - Host `./gradlew testDebugUnitTest --rerun-tasks` (**25 suites, 84 tests, 0 failures, 0 errors, 0 skipped**) and `./gradlew assembleDebug assembleDebugAndroidTest` (**`BUILD SUCCESSFUL`**) verified on `f7c0774`.
  - **S26 (`100.108.44.112:5555`) ADB Reachability:** `adb connect 100.108.44.112:5555` timed out (`connect_timeout` after 6 s) and S26 was offline during `@verifier` execution; therefore standalone S26 Android 17 `run-s21-device-qa.sh` and live S21 ↔ S26 `run-dual-device-qa.sh` remain **PENDING online S26 availability and co-located physical RF execution**.

---

## 2. P0 Target Status Matrix (Verified vs. Pending Multi-Phone Field Hardware)

| P0 Target | Automated / S21 Single-Device Status (`f7c0774`) | Physical Multi-Phone Field-Hardware Status (Required for Gate) |
| --- | --- | --- |
| **Android NDK Native `.so` Packaging** | **VERIFIED (S21 `arm64-v8a` + `x86_64`):** `libprotocolx_rns.so` built via `scripts/build-rns-android.sh`, bundled in `app-debug.apk`, and loaded on S21 (`isNativeAvailable=true`) | **DONE** |
| **Single-hop range (50–100 m)** | S21 `WifiAwareManager` & `BluetoothManager` hardware verified (`t03`); RF range requires a 2nd physical phone | **PENDING:** Two-device Android Wi-Fi Aware LOS field walk test (requires 2nd physical phone) |
| **Multi-hop depth (1–5 hops)** | **VERIFIED (Rust ↔ Python / Loopback + S21 ↔ Mac Python):** 1/3/5-hop Rust chains (`A↔B↔C↔D↔E↔F`) + 3-hop Rust ↔ Python LXMF + S21 ↔ Python E2E (`t04`) | **PENDING:** 3–5 physical Android phones chained over real Wi-Fi Aware / BLE L2CAP RF |
| **PTT E2E latency (<500 ms 1-hop, <800 ms 3-hop)** | **VERIFIED (Loopback + S21 Codec/JNI):** `4–22 ms` 1-hop & `8–39 ms` 3-hop Rust frame relay (`N=10..100`); S21 ADPCM + `RnsPttSessionTransport` (`t05`) + S21 ↔ Python PTT frames (`t04`) verified | **PENDING:** Multi-device physical Android microphone-to-speaker audio capture, Wi-Fi Aware RF hops & playout timing (`run-dual-device-qa.sh` awaiting online/co-located S26) |
| **Message & Task delivery (>= 99.9%)** | **VERIFIED (Loopback / Simulation + S21 ↔ Python):** 100% delivery across `N=10/20/50/100` tiers + S21/Python `5 KB`–`6 KB` LXMF Resource transfer (`t04`) | **PENDING:** Multi-hour field packet-loss & intermittent mobility verification on multiple physical phones |
| **Network failover (< 5 s)** | **VERIFIED (JVM Unit / Integration):** `DefaultCommGateway` + `RnsNetworkManager` + `RnsPttHandoffCoordinator` transition verified `< 5,000 ms` | **PENDING:** Live Cellular/Wi-Fi WAN ↔ Android Wi-Fi Aware ↔ BLE L2CAP radio handoff under real RF contention |
| **Duplicate actions (0)** | **VERIFIED (Backend + Android Unit):** Shared `execute_task_action` + `rns_envelopes` + `StageInput.idempotency_key` + `RnsOfflineSyncReconciler` `409` tests (`0` duplicates across `pytest` 63/63 & `unittest` 39/39) | **PENDING:** Live multi-path chaos soak against running Ops + `RnsGateway` |
| **Public RNS dependency (0)** | **VERIFIED (Sim + S21):** `ifac_leaks=0` across `N=10..100`, S21 `t04` verified rogue wrong-IFAC peer isolation (`rogueLxmf` & `roguePrimary` blocked) | **VERIFY ON MULTI-DEVICE FIELD BUILD:** Packet capture / log audit during physical field test |
| **24 h endurance** | Not yet run (requires long-running soak) | **PENDING:** 24 h continuous multi-device soak monitoring RSS memory, FDs, battery & wake locks |
| **Scale (10 / 20 / 50 / 100 nodes)** | **VERIFIED (Simulation):** `rns12_scale_chaos.rs` passed `N=10, 20, 50, 100` (100% delivery, `0` IFAC leaks) | **PENDING (Optional/Dense Field):** Real RF density test (target cluster size >= 10 physical devices) |

---

## 3. Decision Gate Verdict (`RNS-13` Remains Blocked)

Per [docs/RNS-ROADMAP.md](RNS-ROADMAP.md) Section 7, **RNS-13 (`#21` — Retire Custom Mesh Duplicates) MUST NOT proceed** until the remaining multi-phone physical field-hardware checks are completed and signed off (NDK `.so` bundling and single-device S21 `arm64-v8a` JNI/Keystore/radio/Python E2E are now complete):
1. ~~Build and bundle `libprotocolx_rns.so` (`arm64-v8a`, `x86_64`) into the Android APK~~ — **DONE** (`scripts/build-rns-android.sh`, verified on S21 `R5CR40E11PP`).
2. Verify `50–100 m` single-hop Wi-Fi Aware LOS range across at least 2 physical Android phones.
3. Verify `3–5 hop` end-to-end physical Android RF chain (LXMF + offline task actions + BLE L2CAP fallback).
4. Measure multi-device physical Android hardware audio PTT E2E latency (`< 500 ms` 1-hop, `< 800 ms` 3-hop) and live `< 5 s` WAN ↔ RF failover under RF contention.
5. Complete a `24 h` continuous device endurance soak with zero OOM/FD leaks or duplicate task executions.
