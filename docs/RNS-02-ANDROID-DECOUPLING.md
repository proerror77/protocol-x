# RNS-02: Android Communication Layer Audit & Interface Decoupling

## 1. Audit of Existing Android Implementations & Coupling Points

Before RNS-02, the Android app (`android/app/src/main/java/com/mandonothing/ops/`) had three transport stacks and three local offline persistence queues wired directly into UI and Android service orchestration:

### 1.1 Existing Transport & Persistence Inventory

| Area | Files | Current Responsibility |
| --- | --- | --- |
| Nearby Mesh & Local Radios | `NearbyRadios.kt`, `NearbyService.kt`, `NearbyMessages.kt`, `NearbyPrivate.kt`, `NearbyCredentials.kt`, `NearbyLinkDiagnostics.kt`, `NearbyVoiceAuthentication.kt`, `NearbyPhoto.kt`, `NearbyPanel.kt` | Wi-Fi Aware (`WifiAwareManager` + `WifiAwareNetworkSpecifier`) & BLE L2CAP (`BluetoothManager` insecure L2CAP) opportunistic frame sync, AES-GCM group frames (`NearbyGroup`), ECDSA P-256 message signing (`NearbyIdentity`), ECDH+HKDF+AES-GCM directed E2EE (`NearbyPrivate`), 12h server-issued leases (`NearbySettings`), opportunistic 8 kHz G.711 μ-law live voice (`NearbyLiveVoice`), and MediaRecorder/MediaPlayer fallback (`NearbyPanel`). |
| LiveKit PTT, Video & 1:1 Calls | `VoiceChannel.kt`, `VoiceSessionService.kt`, `VideoChannel.kt`, `ConferenceMedia.kt`, `Transmission.kt`, `PttPreRoll.kt`, `PttPcm.kt`, `VoicePlayback.kt`, `VoiceClipCache.kt`, `OfflineCaptions.kt`, `CaptionRouting.kt` | LiveKit WebRTC room lifecycle (`LiveKit.create`), HTTP floor lease state machine (`transmitWhilePressed`), pre-roll PCM buffering (`PttPreRoll`), `AudioSwitchHandler` output routing, WebRTC `AudioTrackSink` PCM capture -> WAV cache (`VoiceClipCache`), SenseVoice on-device ASR fallback (`OfflineCaptions`), field video (`VideoChannel`), and 1:1 calls (`ConferenceMedia`). |
| Online API & Offline Queues | `OpsClient.kt`, `FieldOfflineStore.kt`, `PhotoOutbox.kt`, `ChannelAttachments.kt`, `FieldSessionStore.kt`, `ArrivalWatchService.kt` | `HttpURLConnection` REST/SSE client (`OpsClient`), Keystore AES-GCM encrypted task action queue & cached workspace/directory (`FieldOfflineStore`), task JPEG outbox (`PhotoOutbox`), and channel photo/MP4 draft & upload queue (`ChannelAttachmentFiles`). |

### 1.2 Coupling Points Found in Audit

1. **`MainActivity.kt` -> Nearby Mesh internals**:
   - Directly held `NearbyPanel`, `NearbySettings`, `NearbyPrivateIdentity`, `privateContent: MutableMap<String, NearbyPrivateContent>`, and observed `NearbyService.radios`, `NearbyService.channelCredential`, `NearbyService.diagnostics`, `NearbyService.session` (`NearbyMessages`), and `NearbyService.uiActive`.
   - `renderConversation()` and `renderNearbyRecord()` directly filtered `NearbyMessage`, decrypted `NearbyPrivate.open(...)`, queried `NearbyMessages.readReceipts(...)`, switched on `NearbyRadio.WIFI` / `NearbyRadio.BLE` and `NearbyDelivery`, and called `decodeNearbyPhoto(...)`.
   - `sendChannelText()`, `saveChannelReadReceipts()`, `shareAssistance()`, `applyQueued()`, `showNearbyDiagnostics()`, and `provisionNearby()` directly invoked `NearbyMessages.send`, `NearbyPrivate.seal`, `NearbyMessages.markRead`, `NearbyMessages.syncAssistance`, `NearbyIdentity`, and `NearbySettings`.
2. **`MainActivity.kt` -> LiveKit SDK & media classes**:
   - Directly imported `io.livekit.android.renderer.TextureViewRenderer`, instantiated `VideoChannel` and `ConferenceMedia`, and directly depended on `VoiceState`, `VoiceSpeaker`, `VoiceCue`, `VoiceLocalClip`, and `VideoState`.
3. **`VoiceSessionService.kt` -> LiveKit & Nearby internals**:
   - Directly instantiated `VoiceChannel` inside `connect()` and exposed `VoiceState`, `VoiceSpeaker`, `VoiceCue`, and `VoiceLocalClip` in `VoiceSessionState` / `cues`.
   - `toggleReceiveMute()` directly called `NearbyService.setReceiveMuted(...)`.
4. **`NearbyPanel.kt` -> `NearbyMessages` & `NearbyService`**:
   - Directly read `NearbySettings`, called `NearbyService.start` / `stop` / `session` / `diagnostics` / `liveVoice`, and sent voice notes directly via `NearbyMessages.send`.
5. **`NearbyRadios.kt` -> `WifiAwareManager` / `BluetoothManager` & `NearbyMessages`**:
   - Embedded `Aware` and `Ble` as concrete inner classes coupled to `NearbyMessages.makeFrame` / `receive`.

---

## 2. RNS-02 Communication Interface Boundary (`com.mandonothing.ops.comm`)

All new abstractions live in `android/app/src/main/java/com/mandonothing/ops/comm/` so business/UI layers depend exclusively on transport-agnostic Kotlin contracts while existing implementations remain wrapped as adapters with zero behavioral regression.

### 2.1 Contracts

- **`CommModels.kt` (Network State & Reachability Model)**:
  - `CommTransportKind`: `INTERNET_HTTP`, `LIVEKIT_MEDIA`, `WIFI_AWARE`, `BLE_L2CAP`, `RNS_MESH`.
  - `CommReachability`: `UNAVAILABLE`, `DISCOVERING`, `CONNECTED`, `DEGRADED`.
  - `CommScope`: transport-agnostic addressing (`eventId`, `channelId`, `channelName`, `channelKind`, `accountKey`, `actorId`).
  - `CommLinkStatus` & `CommNetworkState`: aggregated reachability across Internet and local mesh links (`links`, `bestRoute`, `reachablePeerCount`, `liveVoicePeerCount`, `lastDeliveryMs`, `lastDeliveryKind`, `activeTransports`, `hasAnyPeerLink`).
  - `CommRecipient`: directed-messaging peer descriptor (`actorId`, `displayName`, `callsign`).
- **`CommEnvelopes.kt` (Unified Message / Attachment / Event Envelopes)**:
  - `CommEnvelopeKind`: `CHANNEL_TEXT`, `PRIVATE_ENCRYPTED`, `ASSISTANCE_ALERT`, `VOICE_NOTE`, `ATTACHMENT_MEDIA`, `TASK_ACTION`, `CONTROL_ACK`, `DELIVERY_RECEIPT`, `LIVE_VOICE_BURST`, `SYSTEM_EVENT`.
  - `CommDeliveryState`: `PENDING`, `QUEUED`, `AWAITING_RECEIPT`, `DELIVERED`, `SYNCED`, `CONFLICT`, `EXPIRED`, `FAILED`.
  - `CommRouteMetadata`: `preferredTransport`, `attemptedTransport`, `deliveredTransport`, `hopCount`, `idempotencyKey`.
  - `CommMessageEnvelope`, `CommOutboundMessage`, `CommSendResult` (`Accepted` / `Queued` / `Rejected`), `CommAttachmentEnvelope`, and `CommEventEnvelope`.
- **`CommGateway.kt` (Unified Gateway, Router & Media Transport Contracts)**:
  - `CommTransportAdapter`: pluggable per-transport send/ack/status interface (`kind`, `linkStatus`, `supports`, `send`, `acknowledgeRead`).
  - `CommRouterPolicy`: deterministic transport selection (`candidateTransports`, `selectBestRoute`) enforcing bulk-media constraints (e.g., excluding `BLE_L2CAP` for audio/large photos) and preferring `RNS_MESH` -> `WIFI_AWARE` -> `BLE_L2CAP` -> `INTERNET_HTTP` as appropriate.
  - `CommGateway`: unified gateway consumed by `MainActivity` and `NearbyPanel` (`networkState`, `messages`, `events`, `registerAdapter`, `selectScope`, `send`, `markRead`, `markAssistanceSynced`, `provisionCredentials`, `clearCredentials`, `localIdentityKeys`, `formatDiagnostics`).
  - `CommLocalFallbackController`: UI-facing local radio permissions and offline voice-note fallback controller implemented by `NearbyPanel`.
  - `PttSessionTransport`, `VideoChannelTransport`, `ConferenceCallTransport`, `PttPhase`, `PttSpeaker`, `PttCue`, `PttClip`, `PttTransportMode`, `PttSessionSnapshot`, `VideoChannelState`: transport-agnostic PTT/media contracts consumed by `VoiceSessionService` and `MainActivity`.
  - `CommOfflineQueue<T>`: unified queue contract for offline outboxes.

### 2.2 Zero-Regression Adapters

- **`NearbyCommAdapter.kt`**:
  - `DefaultCommGateway`: implements `CommGateway` over `NearbyService`, `NearbyMessages`, `NearbySettings`, `NearbyIdentity`, `NearbyPrivateIdentity`, and `NearbyPrivate` (owning the private decryption cache previously in `MainActivity`).
  - `NearbyTransportAdapter`: implements `CommTransportAdapter` for `WIFI_AWARE` and `BLE_L2CAP`.
  - `NearbyCommMapper`: pure mapping functions translating `NearbyMessage`, `NearbyDelivery`, `NearbyRadio`, and `NearbyDiagnosticSnapshot` into `CommMessageEnvelope` and `CommNetworkState`.
- **`NearbyRadios.kt`**:
  - Extracted `NearbyRadioChannel : Closeable` implemented by `Aware` and `Ble`, establishing the local radio link seam.
- **`LiveKitMediaAdapters.kt`**:
  - `LiveKitPttTransportAdapter` (wraps `VoiceChannel` behind `PttSessionTransport`), `LiveKitVideoTransportAdapter` (wraps `VideoChannel` behind `VideoChannelTransport`), `LiveKitConferenceTransportAdapter` (wraps `ConferenceMedia` behind `ConferenceCallTransport`), `LiveKitMediaFactory` (creates `TextureViewRenderer` as `android.view.View` so `MainActivity` has zero `io.livekit.*` imports), and `LiveKitMediaMapper`.
- **`OfflineOutboxAdapters.kt`**:
  - `FieldOfflineQueueAdapter`, `PhotoOutboxAdapter`, and `ChannelAttachmentOutboxAdapter` wrapping existing encrypted/atomic file stores without altering on-disk schemas.
- **`VoiceSessionService.kt` & `MainActivity.kt`**:
  - `VoiceSessionService` now depends on `PttSessionTransport` (with injectable `pttTransportFactory` and `onReceiveMuteChanged`) and emits `PttPhase` / `PttSpeaker` / `PttCue` / `PttClip`.
  - `MainActivity` now delegates all nearby messaging, directed encryption, diagnostics, permissions, PTT fallback, video, conference media, and outboxes through `CommGateway`, `CommLocalFallbackController`, `LiveKitMediaFactory`, and the outbox adapters.

---

## 3. Integration Notes for Downstream RNS Workstreams

### 3.1 RNS-04 (Wi-Fi Aware Adapter)
- Implement `CommTransportAdapter` with `kind = CommTransportKind.RNS_MESH` (or `WIFI_AWARE`) and register it via `commGateway.registerAdapter(adapter)`.
- Inside `NearbyRadios` (or an RNS-owned `NearbyRadioChannel` / UniFFI Rust interface bridge), raw byte frames from `WifiAwareManager` (`Aware`) or TCP-over-Wi-Fi-Aware sockets can feed directly into the Rust RNS `Interface` from RNS-01 instead of `NearbyMessages.makeFrame` / `receive`.
- `CommRouterPolicy.candidateTransports` already prioritizes `CommTransportKind.RNS_MESH` ahead of legacy `WIFI_AWARE` and `BLE_L2CAP` once registered and non-`UNAVAILABLE`, allowing incremental shadow/dual-stack rollout and clean retirement in RNS-13.

### 3.2 RNS-10 (Real-time PTT Multi-hop)
- Implement `PttSessionTransport` (with `mode = PttTransportMode.NEARBY_LIVE` or a dedicated RNS multi-hop mode) and inject it into `VoiceSessionService.pttTransportFactory` when Internet/LiveKit is unavailable or degraded (`CommNetworkState.serverReachable == false`).
- `VoiceSessionService` already handles foreground service promotion (`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` / `MICROPHONE`), `ToneGenerator` / haptic cues (`PttCue`), `VoiceClipCache` WAV persistence, and `OfflineCaptions` ASR fallback independent of LiveKit.
- `MainActivity` observes `VoiceSessionState` via `PttPhase` / `PttSpeaker` / `PttClip` without knowing whether audio frames travel over LiveKit WebRTC or RNS multi-hop streams.

---

## 4. Verification

- Command (JDK 17 + Android SDK 36):
  ```sh
  cd /Users/proerror/Documents/toc/protocol-x-rns-02/android
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew testDebugUnitTest --no-daemon --console=plain
  ```
- Result: `BUILD SUCCESSFUL` — 14 test suites, 59 unit tests (`55` existing + `4` new in `com.mandonothing.ops.comm.CommInterfaceTest`), 0 skipped, 0 failures, 0 errors.
