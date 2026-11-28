# Protocol X RNS Upgrade Roadmap (P2 → P0)

- **Epic Tracking Issue:** [proerror77/protocol-x#8](https://github.com/proerror77/protocol-x/issues/8)
- **Baseline Commit:** `origin/main` (`7971771`, Android `v0.27.3` / `versionCode = 47`)
- **Companion Contract:** [docs/RNS-TOC-CONTRACT.md](RNS-TOC-CONTRACT.md) (Track B Unified Event / Task / Communication Contract)
- **Priority:** `P0` (Upgraded from `P2`)
- **Total Estimate:** **21–38 engineering days** (parallelized across Track A & Track B)

---

## 1. Verified Repository Baseline (`origin/main` `7971771`)

Audit of `README.md`, `CONTEXT.md`, `docs/`, `app/`, and `android/app/src/main/java/com/mandonothing/ops/`:

| Capability | Current Implementation (Verified in Code) | Key Files | Current Gap for P0 |
| --- | --- | --- | --- |
| **Wi-Fi Aware Transport** | `WifiAwareManager` NAN publish/subscribe + link-local IPv6 TCP sockets, `MAX_LINKS = 3`, AES-GCM frame cipher (`NEARBY_FRAME_LIMIT = 82_000` B) | `android/.../NearbyRadios.kt` (`Aware`), `NearbyMessages.kt` (`NearbyGroup`) | 1-hop opportunistic sync only; fixed `MAX_LINKS = 3` cap; no RNS interface or link-quality routing |
| **BLE L2CAP Transport** | BLE insecure L2CAP CoC (`listenUsingInsecureL2capChannel`), `MAX_LINKS = 3`, `PRIVATE_BLE_LIMIT = 3_200` B, text/control frames only | `android/.../NearbyRadios.kt` (`Ble`) | 1-hop only; no RNS packet framing or multi-hop relay |
| **Custom Nearby Mesh** | Opportunistic inventory-exchange gossip (`MAX_INVENTORY = 64`, `MAX_MESSAGES = 128`, `MAX_TOMBSTONES = 1_024`, 24 h window), P-256 ECDSA/ECDH, 12 h server-provisioned channel secrets | `android/.../NearbyMessages.kt`, `NearbyPrivate.kt`, `NearbyService.kt`, `NearbyCredentials.kt`, `app/field_nearby.py` | **No multi-hop routing table**, no path discovery/announces, no LXMF propagation nodes, hard 128-message/82 KB limits |
| **Nearby Live Voice** | 8 kHz / 80 ms PCM bursts piggybacked on Wi-Fi Aware polling (`LIVE_PACKET_AGE_MS = 1_000` ms) | `android/.../NearbyLiveVoice.kt`, `NearbyVoiceAuthentication.kt` | 1-hop only, polling-driven (`160 ms`–`1_700 ms` loop), not usable for 3-hop `<800 ms` PTT |
| **LiveKit PTT & Video** | LiveKit Android SDK `2.30.0` (`VoiceChannel`, `VideoChannel`, `ConferenceMedia`) + Ops HTTP floor lease (`/api/.../ptt/floor/{claim\|renew\|release}`) | `android/.../VoiceChannel.kt`, `VideoChannel.kt`, `app/pilot.py`, `app/field_media.py` | Fails when Ops/LiveKit WAN is unreachable; no automatic mesh fallback |
| **Offline Queues & Attachments** | Encrypted local task action queue (`FieldOfflineStore`), JPEG outbox (`PhotoOutbox`), channel media drafts (`ChannelAttachmentFiles`: 750 KB JPEG / 16 MB `<=30 s` MP4) | `android/.../FieldOfflineStore.kt`, `PhotoOutbox.kt`, `ChannelAttachments.kt`, `app/channel_attachments.py` | HTTP-only flush to Ops API; cannot sync through multi-hop peer mesh |
| **UI / Transport Coupling** | `MainActivity.kt` directly binds `NearbyService`, `NearbyPanel`, `VideoChannel`, `FieldOfflineStore`, `PhotoOutbox`, `ChannelAttachmentFiles` | `android/.../MainActivity.kt`, `VoiceSessionService.kt` | No unified `Communication Interface`; transport swap touches Activity/UI logic |
| **TOC, Ontology & TAK** | FastAPI + SQLite/PG WorkItem projection, deterministic proposals, idempotent actions/receipts, OpenTAKServer CoT bridge | `app/main.py`, `app/bindings.py`, `ontologies/{golf,conference}.json`, `app/tak.py`, `app/tak_ptt.py` | No Python RNS/LXMF gateway; disconnected field nodes cannot reach TOC or ATAK |

---

## 2. Final Layered Architecture

```
+------------------------------------------------------------------------------+
| Layer 5: Application & Domain UI                                             |
|   Android Field App (MainActivity, PTT, Tasks, Channel) | Web TOC (web/)     |
+------------------------------------------------------------------------------+
| Layer 4: Unified Domain & Event Contract (Track B — docs/RNS-TOC-CONTRACT.md)|
|   WorkItem | Evidence | Idempotent Action/Receipt | TAK CoT Envelope         |
+------------------------------------------------------------------------------+
| Layer 3: Communication Interface & Network Manager (RNS-02, RNS-08, RNS-10)  |
|   TransportGateway | CommEnvelope | Auto Failover (<5s) | PTT Floor/Media    |
+------------------------------------------------------------------------------+
| Layer 2: RNS Runtime & Tactical Services (RNS-03, RNS-05, RNS-06, RNS-07)    |
|   LXMF Router & Propagation | Private IFAC Isolation | 1–5 Hop Routing       |
+------------------------------------------------------------------------------+
| Layer 1: Rust Core via UniFFI (RNS-01) & Physical Adapters (RNS-04)          |
|   reticulum-rs + LXMF | Wi-Fi Aware | BLE L2CAP | LAN TCP/UDP | WAN/LiveKit  |
+------------------------------------------------------------------------------+
```

### Key Architectural Principles
1. **No RNS Rewrite From Scratch:** Reuse battle-tested Rust Reticulum/LXMF crates wrapped via UniFFI (`arm64-v8a`, `armeabi-v7a`, `x86_64`), verified directly against official Python `rns`/`lxmf` wire protocol.
2. **Zero Public RNS Dependency:** Every interface operates strictly on isolated private networks using event/channel IFAC (Interface Access Codes) derived from Protocol X authority credentials (`RNS-05`).
3. **Preserve Domain Invariants:** All WorkItem updates remain idempotent, version-checked (`expected_version`), and auditable (`Receipt`), whether delivered over WAN HTTP or 1–5 hop RNS (`docs/RNS-TOC-CONTRACT.md`).
4. **Adapter-First Evolution:** Decouple Android UI/domain logic first (`RNS-02`) so existing Nearby and LiveKit paths keep shipping while RNS layers mature behind the interface.

---

## 3. 9-Stage Execution Plan

| Stage | Objective | Primary Scope | PRs | Exit Gate |
| --- | --- | --- | --- | --- |
| **Stage 01** | Tech Selection & Wire POC | Evaluate Rust RNS/LXMF crates & licenses; run real Rust ↔ Python `rns` bidirectional wire POC | RNS-01 | Real unmocked packet/message exchange passes |
| **Stage 02** | Core Bindings & Comm Boundary | Generate Android UniFFI bindings; decouple Android UI/domain from Nearby & LiveKit internals | RNS-01, RNS-02 | `./gradlew testDebugUnitTest` + `cargo test` green; zero regression |
| **Stage 03** | RNS Runtime & LXMF Integration | Bundle `.so` into Android APK; embed RNS lifecycle service, LXMF router, and Resource transfer | RNS-03 | Signed/encrypted LXMF messages & binary resources transfer end-to-end |
| **Stage 04** | Radio Adapters & Private Network | Bridge Android Wi-Fi Aware & BLE L2CAP into RNS interfaces; bind Protocol X keys & IFAC isolation | RNS-04, RNS-05 | 50–100 m Wi-Fi Aware RNS link verified; 0 public RNS leakage |
| **Stage 05** | Multi-hop Routing & Propagation | Tune 1–5 hop RNS transport, path reconvergence, and LXMF store-and-forward propagation nodes | RNS-06 | 1/3/5-hop chains & intermittent store-and-forward pass |
| **Stage 06** | Payload Migration & Auto Failover | Migrate channel messages, voice notes, photos & short videos to LXMF; implement `<5 s` Network Manager | RNS-07, RNS-08 | WAN ↔ Wi-Fi Aware ↔ BLE failover `<5 s`; 0 duplicate messages |
| **Stage 07** | Offline Tasks & Multi-hop PTT | Sync offline task actions/receipts over RNS; deliver multi-hop Opus PTT (`<500 ms` 1-hop / `<800 ms` 3-hop) | RNS-09, RNS-10 | 0 duplicate actions; multi-hop PTT latency & floor arbitration pass |
| **Stage 08** | TOC / TAK Gateway & System QA | Deploy Python RNS↔TOC/OpenTAKServer gateway; execute 10/20/50/100-node, security & 24 h endurance tests | RNS-11, RNS-12 | Offline field node ↔ TOC/ATAK roundtrip & all P0 KPIs verified |
| **Stage 09** | Decision Gate & Legacy Cleanup | Sign off compatibility & multi-hop gate; retire duplicate custom Nearby Mesh code and update docs | RNS-12, RNS-13 | Gate signed; legacy mesh removed; full CI suite green |

---

## 4. RNS-01 .. RNS-13 PR Breakdown & Implementation Status (`main` @ `d38a750` + `rns/s26-dual-device-qa` @ `f7c0774`)

| PR | Title | GitHub Issue | Stage | Track | Dependencies | Estimate | Current Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **RNS-01** | Rust Core / UniFFI | [#9](https://github.com/proerror77/protocol-x/issues/9) | 01–02 | Track A | — | 2–3 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + NDK `.so` S21 Verified** (`scripts/build-rns-android.sh`, `arm64-v8a` `4,030,296` B / `x86_64` `4,446,928` B loaded on S21) |
| **RNS-02** | Communication Interface | [#10](https://github.com/proerror77/protocol-x/issues/10) | 02 | Track A + B | [Epic #8](https://github.com/proerror77/protocol-x/issues/8), `RNS-TOC-CONTRACT.md` | 1.5–2 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22))** (`com.mandonothing.ops.comm.*` adapters & `DefaultCommGateway`) |
| **RNS-03** | RNS Runtime / LXMF | [#11](https://github.com/proerror77/protocol-x/issues/11) | 03 | Track A | `#9`, `#10` | 2–4 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + S21 Verified** (`RnsRuntimeService` + Keystore + real S21 ↔ Python LXMF `t02`/`t04`) |
| **RNS-04** | Wi-Fi Aware Adapter | [#12](https://github.com/proerror77/protocol-x/issues/12) | 04 | Track A | `#10`, `#11` | 2–3 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + S21 Hardware Check** (S21 `WifiAwareManager`/BLE L2CAP `t03` pass; 2-phone RF LOS pending) |
| **RNS-05** | Private Network / Identity | [#13](https://github.com/proerror77/protocol-x/issues/13) | 04 | Track A + B | `#11`, `#12` | 1.5–2.5 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + S21 Verified** (S21 private IFAC + rogue wrong-IFAC isolation `t04`, `app/field_nearby.py`) |
| **RNS-06** | 多跳路由 (Multi-hop Routing) | [#14](https://github.com/proerror77/protocol-x/issues/14) | 05 | Track A | `#11`, `#12`, `#13` | 2–4 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22))** (1–5+ hop Rust/Python relay + `RnsTopologyDiagnostics`; 3–5 phone RF chain pending) |
| **RNS-07** | 消息与附件迁移 (Message & Attachment Migration) | [#15](https://github.com/proerror77/protocol-x/issues/15) | 06 | Track A | `#10`, `#11`, `#14` | 2–3 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + S21 Verified** (S21 ↔ Python `5 KB`–`6 KB` LXMF Resource transfer `t04`) |
| **RNS-08** | Network Manager 自动切换 (Auto Failover) | [#16](https://github.com/proerror77/protocol-x/issues/16) | 06 | Track A | `#10`, `#12`, `#14`, `#15` | 1.5–2.5 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22))** (`RnsNetworkManager` `<5s` hysteresis failover in JVM tests; live RF handoff pending) |
| **RNS-09** | Offline Task Sync | [#17](https://github.com/proerror77/protocol-x/issues/17) | 07 | Track B | `#10`, `#14`, `#15`, `#16` | 2–3.5 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22))** (`POST /api/rns/v1/envelopes`, `FieldOfflineStore`, `RnsOfflineSyncReconciler`) |
| **RNS-10** | 实时 PTT 多跳适配 (Real-time PTT Multi-hop) | [#18](https://github.com/proerror77/protocol-x/issues/18) | 07 | Track A | `#10`, `#12`, `#14`, `#16` | 2–4 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22)) + S21 Self-Check** (S21 ADPCM + PTT floor/frames `t04`/`t05`; multi-phone RF latency pending) |
| **RNS-11** | TOC / TAK Gateway | [#19](https://github.com/proerror77/protocol-x/issues/19) | 08 | Track B | `#11`, `#13`, `#17`, `#18` | 1.5–3 d | **CLOSED (PR [#22](https://github.com/proerror77/protocol-x/pull/22))** (`app/rns_gateway.py`, `app/tak.py` CoT bridge + echo suppression) |
| **RNS-12** | 性能、安全与稳定性测试 (Perf, Security & Stability QA) | [#20](https://github.com/proerror77/protocol-x/issues/20) | 08–09 | Track A + B | `#14`–`#19` | 2–3 d | **OPEN — Auto/Loopback + S21 Single-Device QA PASS; Multi-Phone Field Gate Pending** (see [docs/RNS-12-QA-REPORT.md](RNS-12-QA-REPORT.md)) |
| **RNS-13** | 淘汰自研 Mesh 重复代码 (Retire Custom Mesh Duplicates) | [#21](https://github.com/proerror77/protocol-x/issues/21) | 09 | Track A | `#20` (**Hard Gate**) | 0.5–1 d | **OPEN — BLOCKED** (Legacy Nearby Mesh preserved behind `CommGateway` until `RNS-12` multi-phone physical field gate clears) |

---

## 5. P0 Acceptance & Performance Targets

All targets below are mandatory for `RNS-12` sign-off and clearing the `RNS-13` decision gate:

| Category | Metric | P0 Acceptance Target | Verification Method |
| --- | --- | --- | --- |
| **Radio Range** | Single-hop Wi-Fi Aware LOS | **50–100 m** | Two-device field walk test (e.g. S21/S26) |
| **Topology Depth** | Multi-hop relay depth | **1–5 hops** | Real 3–6 device chain + automated multi-hop testbed |
| **Voice Latency** | PTT E2E latency (1-hop) | **< 500 ms** | Hardware audio timestamp / RTP-to-playout delta |
| **Voice Latency** | PTT E2E latency (3-hop) | **< 800 ms** | 3-hop Wi-Fi Aware RNS audio frame benchmark |
| **Reliability** | Message & Task delivery rate | **>= 99.9%** | Packet loss / intermittent disconnect soak with LXMF retry & propagation |
| **Availability** | Network failover switch time | **< 5 s** | WAN ↔ Wi-Fi Aware ↔ BLE L2CAP transition timing |
| **Correctness** | Duplicate action executions | **0** | Replay & multi-path chaos test verifying `idempotency_key` + `expected_version` |
| **Security / Isolation** | Public RNS network dependency | **0** | Packet capture & config audit confirming private IFAC-only operation |
| **Stability** | Continuous endurance run | **24 h** | 24-hour soak monitoring RSS memory, FDs, battery & wake locks |
| **Scalability** | Network size benchmarks | **10 / 20 / 50 / 100 nodes** | Automated multi-node simulation measuring announce overhead & convergence |

---

## 6. Effort Estimate & Track A / Track B Parallelization

### Engineering Effort: **21–38 Engineering Days**
- **Track A — RNS Core, Transport & Android Mesh (14–25 eng-days):**
  - `RNS-01` (2–3d), `RNS-02` interface/adapters (1–1.5d), `RNS-03` (2–4d), `RNS-04` (2–3d), `RNS-05` IFAC/keystore (1–1.5d), `RNS-06` (2–4d), `RNS-07` (2–3d), `RNS-08` (1.5–2.5d), `RNS-10` (2–4d), `RNS-12` mesh QA (1–1.5d), `RNS-13` (0.5–1d).
- **Track B — TOC, Ontology, Offline Tasks & TAK Gateway (7–13 eng-days):**
  - `docs/RNS-TOC-CONTRACT.md` alignment, `RNS-02` envelope contract (0.5d), `RNS-05` server identity mapping (0.5–1d), `RNS-09` (2–3.5d), `RNS-11` (1.5–3d), `RNS-12` E2E domain/TAK QA (1–1.5d).

### Parallel Execution Schedule

```
Week 1 (Stages 01–03):
  Track A: [RNS-01 Rust Core/UniFFI] -> [RNS-02 Comm Interface] -> [RNS-03 RNS/LXMF Runtime]
  Track B: [RNS-TOC-CONTRACT.md]     -> [RNS-02 Domain Envelope] -> [Ops/TAK Gateway Prep]

Week 2–3 (Stages 04–06):
  Track A: [RNS-04 Wi-Fi Aware] + [RNS-05 Private Net] -> [RNS-06 Multi-hop] -> [RNS-07 Migration] + [RNS-08 Failover]
  Track B: [RNS-05 Authority Identity Mapping]        -> [RNS-09 Offline Task Sync Protocol & Server Outbox/Inbox]

Week 4–5 (Stages 07–09):
  Track A: [RNS-10 Multi-hop PTT] -------------------> [RNS-12 Perf/24h/Scale QA] -> [GATE] -> [RNS-13 Retire Legacy]
  Track B: [RNS-09 Android/Backend Integration] -> [RNS-11 TOC/TAK Gateway] -> [RNS-12 E2E Contract QA]
```

Track B proceeds concurrently against the frozen `CommEnvelope` and `Action`/`Receipt`/`CoT` schemas defined in [docs/RNS-TOC-CONTRACT.md](RNS-TOC-CONTRACT.md), avoiding waiting on Wi-Fi Aware physical radio tuning (`RNS-04`/`RNS-06`).

---

## 7. Mandatory Decision Gate Before Retiring Custom Mesh (`RNS-13`)

> **Current Gate Status (`origin/main` `d38a750` + `rns/s26-dual-device-qa` @ `f7c0774`):** **NOT CLEARED — `RNS-13` (`#21`) REMAINS BLOCKED.** Automated/loopback Rust (`14/14`), Python (`39/39` + `63/63`), and Android (`25` suites / `84` tests) suites, NDK `.so` bundling (`arm64-v8a` `4,030,296` B / `x86_64` `4,446,928` B), and single-device Samsung Galaxy S21 Ultra (`R5CR40E11PP`) JNI/Keystore/radio/Python E2E verification (`5/5` tests in `4.021s`) pass, and the S26/dual-device harness (`DualDeviceRnsTest.kt`, `scripts/run-dual-device-qa.sh`) is code-reviewed and compiled (`assembleDebugAndroidTest`). S26 (`100.108.44.112:5555`) was offline during verification, so standalone S26 Android 17 QA, live S21 ↔ S26 co-located Wi-Fi Aware/BLE RF `50–100 m` LOS range, `3–5` physical Android RF multi-hop chaining, multi-device RF audio PTT latency + live WAN ↔ RF failover, and the `24 h` device endurance soak remain pending (see [docs/RNS-12-QA-REPORT.md](RNS-12-QA-REPORT.md)).

Existing custom mesh code (`NearbyRadios`, `NearbyMessages`, `NearbyLiveVoice`, legacy `NearbyPrivate` transport loops) provides current v0.27.3 field capability and **must remain operational behind `RNS-02` adapters** until **all four gate criteria** are verified in `RNS-12`:

1. **Protocol Compatibility Gate:**
   - Rust RNS Core (`RNS-01`) and Android Runtime (`RNS-03`) pass automated bidirectional wire-compatibility tests against official Python `rns` and `lxmf` nodes (including `RNS-11` TOC Gateway), with `0` public RNS bootstrap/network dependencies.
2. **Multi-Hop & Range Gate:**
   - Real hardware tests confirm `50–100 m` single-hop Wi-Fi Aware and `1–5 hop` end-to-end delivery (`>= 99.9%` delivery rate) with automatic path recovery across BLE L2CAP fallback.
3. **Real-Time PTT & Failover Gate:**
   - Measured PTT E2E latency meets `< 500 ms` (1-hop) and `< 800 ms` (3-hop), and `RNS-08` Network Manager transitions between WAN LiveKit and Mesh RNS in `< 5 s`.
4. **Integrity, Scale & Endurance Gate:**
   - `RNS-09`/`RNS-11` pass `0` duplicate action executions under replay/partition chaos, `10/20/50/100`-node scale tests stay within CPU/memory budgets, and a `24 h` endurance test passes without leaks or crashes.

Only after `RNS-12` records passing evidence for all four gates may `RNS-13` merge to remove duplicate custom Mesh transport code.
