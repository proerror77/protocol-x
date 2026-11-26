# RNS-04: Wi-Fi Aware & BLE L2CAP Pluggable RNS Interface Adapter

**Branch:** `rns/04-wifi-aware-adapter` (from `dd1211d`)  
**Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-04`  
**Package:** `com.mandonothing.ops.comm.aware`

---

## 1. Audit of `NearbyRadios.kt`

Inspecting `android/app/src/main/java/com/mandonothing/ops/NearbyRadios.kt` before RNS-04 revealed four structural issues blocking multi-hop Reticulum mesh operation:

1. **Fixed `MAX_LINKS = 3` Cap & Partition Risk:**
   - `NearbyRadios.Aware` (`onMessageReceived`, `hello`) and `NearbyRadios.Ble` (`onScanResult`, `inbound`) enforced a hardcoded `MAX_LINKS = 3` first-come-first-served limit.
   - Once 3 peers connected, any 4th neighbor (including bridge nodes linking separate clusters) was rejected until an existing socket dropped, creating persistent multi-hop topology partitions.
   - `Ble` tracked `links` (outbound `Map<Int, BluetoothSocket>`) and `inbound` (`Set<BluetoothSocket>`) separately with independent `MAX_LINKS = 3` checks and no peer deduplication for inbound connections.
2. **`ConnectivityManager.NetworkCallback` & Socket Lifecycle Leaks:**
   - In `Aware.request()`, `requests[peer] = callback` could overwrite an active callback or fail during `requestNetwork(...)` without atomic idempotent cleanup; `Aware.close()` did not cancel pending `Handler.postDelayed` runnables (`hello`, `drop`, `restart`) or clear `discoveryPeers`/`helloPending`.
   - Background `executor` tasks (`ServerSocket.accept()` in `Aware.discover`, `network.socketFactory.createSocket().connect()` in `Aware.request`, and `BluetoothServerSocket.accept()` / `socket.connect()` in `Ble`) could return a connected socket *after* `close()` or `drop(peer)` executed, relying on `main.post` to close it.
   - `NearbyRadios.sockets` was a `mutableSetOf<Closeable>()` mutated across `main`, `executor`, and `close()` caller threads without synchronization.
3. **Absent Link Quality Metrics:**
   - `NearbyLinkDiagnostics` only tracked `(radio, peer, supportsLive)` and delivery timestamps — no per-link RSSI, RTT, estimated bandwidth, packet loss, or RNS path cost was measured or exposed to `CommNetworkState`.
4. **Protocol Coupling to `NearbyMessages`:**
   - `NearbyRadios.exchange()` coupled socket I/O directly to `NearbyMessages.makeFrame` / `receive`, with no pluggable `RnsFrameBridge` seam for `protocolx-rns`.

---

## 2. Architecture (`com.mandonothing.ops.comm.aware`)

### 2.1 UniFFI Bridge Contract (`RnsFrameBridge.kt`)

Matches `@rns-poc`'s RNS-03 `uniffi.protocolx_rns` contract 1:1 so `RnsRuntimeService` plugs in without editing `rust/` or `RnsRuntimeService`:

- `RnsCustomInterfaceKind` (`WIFI_AWARE`, `BLE_L2CAP`, `CUSTOM`) ↔ `CustomInterfaceKind`
- `RnsBridgeLinkMetrics(peerId, connected, rssiDbm, rttMs, estimatedBandwidthBps)` ↔ `CustomLinkMetrics`
- `RnsCustomInterfaceOutboundHandler` (`onOutboundFrame(interfaceTag, frame)`, `onInterfaceDetached(interfaceTag)`) ↔ `RnsCustomInterfaceBridge`
- `RnsFrameBridge`:
  - `attachInterface(interfaceTag, kind, hwMtu, bitrateBps, outboundHandler)`
  - `detachInterface(interfaceTag)`
  - `pushInboundFrame(interfaceTag, frame)`
  - `updateLinkMetrics(interfaceTag, metrics)`
  - `getLinkMetrics(interfaceTag)`

### 2.2 Wi-Fi Aware Adapter (`RnsWifiAwareAdapter.kt`)

- Implements `CommTransportAdapter` (`kind = CommTransportKind.WIFI_AWARE`), `RnsCustomInterfaceOutboundHandler`, and `Closeable`.
- Bridges Android `WifiAwareManager` (NAN publish/subscribe + link-local IPv6 TCP `ServerSocket`/`Socket` and optional IPv6 UDP `DatagramSocket` via `WifiAwareNetworkSpecifier` + `OsConstants.IPPROTO_UDP`) via `WifiAwareDriver` into `RnsFrameBridge` under tag `"wifi-aware"` (`WIFI_AWARE_HW_MTU = 1196`, matching `prns_core::interfaces::wifi_aware::policy::WIFI_AWARE_HW_MTU`).
- Uses `RnsPeerLinkSession` and `RnsFrameFramer` (`0x01` `RNS_DATA`, `0x02` `RTT_PING`, `0x03` `RTT_PONG`) for transparent RNS packet ingress/egress alongside active RTT probing and EWMA throughput estimation.

### 2.3 BLE L2CAP Fallback Adapter (`RnsBleL2capAdapter.kt` & `RnsAwareTransportCoordinator.kt`)

- Implements `CommTransportAdapter` (`kind = CommTransportKind.BLE_L2CAP`), `RnsCustomInterfaceOutboundHandler`, and `Closeable`.
- Enforces low-bandwidth control/text constraints (`!outbound.requiresBulkMediaTransport`, `BLE_MAX_FRAME_BYTES = 3_200`, `BLE_HW_MTU = 500`).
- Coordinated by `RnsAwareTransportCoordinator` (or `onWifiAwareStateChanged(available, activeWifiAwarePeers)`):
  - Under `BleFallbackPolicy.WHEN_WIFI_AWARE_UNAVAILABLE`: automatically registers `"ble-l2cap"` on `RnsFrameBridge` when Wi-Fi Aware is unavailable or has zero active peers, and cleanly detaches `"ble-l2cap"` + closes BLE sockets/scan/advertise once Wi-Fi Aware connects.
  - Under `BleFallbackPolicy.SIMULTANEOUS_BACKUP`: keeps BLE L2CAP active alongside Wi-Fi Aware.

### 2.4 Configurable Capacity, Pruning & Anti-Partition Peer Rotation (`PeerCapacityManager.kt`)

Replaces `MAX_LINKS = 3` in both `NearbyRadios.kt` and the RNS-04 adapters with `RnsLinkConfig`:

- **Configurable Active Caps:** `maxActiveWifiAwareLinks = 8` (matching `personal-rns` `MAX_NDP_PEERS`), `maxActiveBleLinks = 6`, plus a tracked standby pool (`maxStandbyPeers = 64`).
- **Stale Eviction:** Active peers silent for `>= stalePeerTimeoutMs` (default 45s) are pruned to reclaim slots.
- **Quality-Based Preemption:** Standby peers with a quality score exceeding the worst incumbent by `>= preemptionScoreMargin` (after `minPeerDwellMs = 15s` hysteresis) preempt weak/lossy links.
- **Deterministic Peer Rotation:** When all slots hold healthy peers and unvisited standby neighbors wait past `peerRotationIntervalMs` (default 30s), the oldest/lowest-retention active peer yields cleanly to standby and an unvisited standby neighbor is admitted — preventing multi-hop mesh partitioning in dense clusters.
- **Failure Suppression:** Failed NDP/connect attempts apply a `suppressTtlMs` (default 12s) cooldown matching `prns_core::interfaces::wifi_aware::policy::SUPPRESS_TTL_MS`.

### 2.5 Per-Link Telemetry, Path Cost & `CommNetworkState` (`RnsAwareModels.kt`)

- `RnsPeerQualityMetrics` tracks per-peer `rssiDbm`, smoothed `rttMs`, EWMA `estimatedBandwidthBps`, `packetLossRate`, `bytesSent`/`bytesReceived`, `framesSent`/`framesReceived`, and `isBridgeCandidate`.
- `RnsPathCostCalculator` computes:
  - `qualityScore` (`0.0..100.0`) for neighbor selection.
  - `RnsPathCost` (`1..10_000`) for RNS-08 path selection and `CommLinkStatus.statusLabel`.
  - `mergeIntoNetworkState(...)` / `RnsAwareTransportCoordinator.applyTo(...)` to expose updated `CommLinkStatus`, `bestRoute`, and `reachablePeerCount` into `CommNetworkState`.

### 2.6 Zero-Leak Guarantees (`TrackedResourceRegistry.kt` & `NearbyRadios.kt`)

- `TrackedNetworkCallbackRegistry`: atomic replace-or-register semantics (old callbacks are unregistered before replacement), immediate cleanup if `requestNetwork` throws, idempotent `unregister` via `AtomicBoolean`, and `unregisterAll(permanent = true)` on `close()`.
- `TrackedSocketRegistry`: atomic keyed + unkeyed `Closeable` tracking; any socket returned asynchronously from a background `accept()` or `connect()` after `drop()`/`close()` is closed synchronously on the caller thread and rejected.
- `NearbyRadios.kt` was refactored to use `PeerCapacityManager`, `TrackedNetworkCallbackRegistry`, `TrackedSocketRegistry`, thread-safe `sockets`, and `main.removeCallbacksAndMessages(callbackToken)` on `close()` while keeping full backward compatibility with `NearbyService`.

---

## 3. Verification

Executed in `/Users/proerror/Documents/toc/protocol-x-rns-04/android`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest --rerun-tasks --no-daemon --console=plain && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew assembleDebug --no-daemon --console=plain
```

Observed results:
- `testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL` — 17 test suites, **61 unit tests** (`52` existing + `9` new in `com.mandonothing.ops.comm.aware`: `PeerCapacityManagerTest` [4], `TrackedResourceRegistryTest` [2], `RnsAwareAdaptersTest` [3]), 0 skipped, 0 failures, 0 errors.
- `assembleDebug`: `BUILD SUCCESSFUL`.
