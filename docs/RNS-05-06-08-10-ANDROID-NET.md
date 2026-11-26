# RNS-05 / RNS-06 / RNS-08 / RNS-10: Android Private IFAC, Multi-Hop Topology, Network Manager & PTT Transport

**Branch:** `rns/android-net` (from `rns/integration` `cc67dbb`)  
**Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-android-net`  
**Packages:**
- `com.mandonothing.ops.comm.aware` (RNS-04↔03 bridge + RNS-05 IFAC & revocation)
- `com.mandonothing.ops.comm.network` (RNS-06 topology diagnostics + RNS-08 network manager)
- `com.mandonothing.ops.comm.ptt` (RNS-10 multi-hop PTT + LiveKit handoff)

---

## 1. RNS-04 ↔ RNS-03 Bridge & RNS-05 IFAC Isolation (`comm.aware`)

### 1.1 `RnsAwareBridgeConnector.kt`
- **`RnsCustomBridgeSink` & `UniFfiRnsNodeBridgeSink`:** Bridges `RnsAwareTransportCoordinator` / `RnsWifiAwareAdapter` / `RnsBleL2capAdapter` (`RnsFrameBridge`) directly into `uniffi.protocolx_rns.RnsNode` (`attachCustomInterface`, `detachCustomInterface`, `pushCustomInboundFrame`, `updateCustomInterfaceMetrics`, `getCustomInterfaceMetrics`) gated behind `RnsNativeLoader.ensureLoaded()`.
- **`RnsAwareBridgeConnector`:** Implements `RnsFrameBridge`, enforces `RnsIfacFrameGuard` at interface attach, inbound frame push, outbound callback, and link-metrics updates, and forwards metrics to `RnsTopologyDiagnostics`.
- **`bindCoordinator` / `revokePeer` / `rotateIfacConfig`:** Immediately evicts active Wi-Fi Aware & BLE L2CAP sockets for revoked peers and rotates IFAC credentials atomically without socket leaks.

### 1.2 `RnsIfacSecurityPolicy.kt`
- **`RnsIfacConfig` & `RnsIfacDerivation.deriveConfig`:** Derives per-event/channel private network names (`protocolx.<eventId>.<channelId>`) and HMAC-SHA256 IFAC keys; rejects public/unauthenticated configurations (`allowPublicRns = true`, blank `networkName`, or empty key) and expired leases (`nowMs >= expiresAtMs`).
- **`RnsIfacFrameGuard`:** Seals outbound custom-interface frames with an 8-byte network prefix + 16-byte HMAC-SHA256 tag and verifies inbound frames in constant time (`MessageDigest.isEqual`), dropping wrong-IFAC, expired, or revoked-peer frames directly at `RnsWifiAwareAdapter` / `RnsBleL2capAdapter` / `RnsFrameBridge` boundaries.

---

## 2. RNS-06 Multi-Hop Topology Diagnostics (`comm.network`)

### 2.1 `RnsTopologyDiagnostics.kt`
- Tracks per-destination `RnsRouteHopEntry` (`destinationHashHex`, `nextHopPeerId`, `hopCount`, `linkRttMs`, `estimatedBandwidthBps`, `viaRadio`, `reachable`).
- Ingests `RnsCoreEvent` (`AnnounceHeard`, `PeerLinkChanged`, `LxmfReceived`) and `RnsRemoteRouteTelemetry` (`ingestMultiHopTelemetry`, aligned with `@rns-poc`'s `RouteTelemetry` / `MultiHopTelemetry` in board `#58`).
- Measures `RnsReconvergenceEvent` (`reconvergenceMs`) whenever a relay/next-hop disconnects (`onPeerDisconnected`) and an alternate multi-hop route converges.
- Enriches `CommNetworkState` via `toRnsMeshLinkStatus` and `applyToNetworkState`.

---

## 3. RNS-08 Network Manager & `<5s` Auto-Failover (`comm.network`)

### 3.1 `RnsNetworkManager.kt`
- Scores candidate transports across `RnsManagedRoute`:
  - `WAN_HTTP` (`CommTransportKind.INTERNET_HTTP` / LiveKit media readiness)
  - `LAN_RNS` (`CommTransportKind.RNS_MESH` over LAN/relay)
  - `RNS_WIFI_AWARE` (`CommTransportKind.WIFI_AWARE`)
  - `RNS_BLE_L2CAP` (`CommTransportKind.BLE_L2CAP`, excluded for bulk media)
- **`RnsFailoverPolicy`:**
  - `failoverMaxBudgetMs = 4_500L` (`<5s` P0 requirement): hard drops or route failures trigger immediate failover (`switchDurationMs < 5_000L`).
  - `promoteHoldDownMs = 1_500L` + `scoreSwitchMargin = 12.0`: hysteresis prevents ping-ponging on transient link fluctuations.
- **`dispatch(...)`:** Enforces idempotency deduplication (`deliveredIdempotencyKeys` + `inFlightKeys`) and automatic retryable spillover across scored transports (`0` duplicate dispatches, `0` dropped messages).

---

## 4. RNS-10 Multi-Hop PTT Transport (`comm.ptt`)

### 4.1 `RnsPttModels.kt`, `RnsPttFloorArbiter.kt`, `RnsPttJitterBuffer.kt`, `RnsPttSessionTransport.kt`
- **4-bit IMA ADPCM Codec (`RnsPttAdpcmCodec`):** Compresses 40 ms 16 kHz mono PCM frames (640 samples / 1,280 B) into 324-byte payloads for low-latency 1–5 hop delivery (`<500 ms` 1-hop / `<800 ms` 3-hop).
- **Distributed Floor Arbitration (`RnsPttFloorArbiter`):** Handles `REQUEST`, `GRANT`, `BUSY` (`PttCue.BUSY`), `PREEMPT` (higher priority preemption), `RELEASE` (`PttCue.ENDED`), deterministic tie-breaking, and 15 s lease expiry; models align 1:1 with `@rns-poc`'s UniFFI `PttFloorAction`, `PttFramePacket`, and `PttFloorControlMessage` (`#58`).
- **Ordered Playout & Burst Archiving (`RnsPttJitterBuffer`):** Reorders frames by `seq`, conceals lost packets, tracks E2E latency (`RnsPttPlayoutStats`), and emits `VoiceCapturedClip` on `endOfBurst` / `finishBurst` for `VoiceClipCache` / ASR archiving without auto-replaying offline clips as live PTT.
- **Transport & LiveKit Handoff (`RnsPttSessionTransport` & `RnsPttHandoffCoordinator`):** Implements `PttSessionTransport` (`mode = PttTransportMode.NEARBY_LIVE`) and coordinates `<5s` LiveKit ↔ RNS Mesh switches driven by `RnsNetworkManager.state`.

---

## 5. Verification

Executed in `/Users/proerror/Documents/toc/protocol-x-rns-android-net/android`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest --rerun-tasks --no-daemon --console=plain && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew assembleDebug --no-daemon --console=plain
```

Observed results:
- `testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL` — **23 test suites, 77 unit tests** (`69` existing + `8` new in `RnsAwareIfacBridgeTest` [2], `RnsNetworkAndTopologyTest` [2], `RnsPttTransportTest` [4]), 0 skipped, 0 failures, 0 errors.
- `assembleDebug`: `BUILD SUCCESSFUL`.
