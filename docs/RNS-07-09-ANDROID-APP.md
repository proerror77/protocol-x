# RNS-05 / RNS-07 / RNS-09: Android App Identity Binding, Media Migration & Offline Sync

**Branch:** `rns/android-app` (stacked on `cc67dbb` = RNS-01..04 + contracts)  
**Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-android-app`  
**Packages:** `com.mandonothing.ops`, `com.mandonothing.ops.comm`, `com.mandonothing.ops.comm.rns`

---

## 1. Summary of Changes

### 1.1 RNS-05: Private IFAC Lease, Revocation & Actor-Device Binding
- **`OpsClient.kt`**:
  - Extended `nearbyCredentials(event, publicKey, agreementPublicKey, rnsIdentity)` (`POST /api/field/events/{event_id}/nearby`) to send optional `rns_identity` (`dest_hash`, `identity_hash`, `ed25519_pub`, `x25519_pub`) with automatic `422 extra_forbidden` backwards-compatible fallback.
  - Added `nearbyRevocations(event)` (`GET /api/field/events/{event_id}/nearby/revocations`).
- **`RnsCredentialLeaseSync` (`RnsOfflineSyncReconciler.kt`), `RnsLocalStore.kt`, `RnsRuntimeCoordinator.kt`, `RnsRuntimeService.kt`, `NearbyCommAdapter.kt`**:
  - Parses per-channel `ifac` (`network_name`, `passphrase`, `generation`), top-level `rns_gateway.dest_hash`, and `rns_peers`, persisting encrypted `RnsIfacLease` + `RnsIdentityBinding` (`actor_id` / `deviceTokenHash` <-> `rns_destination_hash`) via `FieldSessionStore`.
  - Applies IFAC rotation live and enforces immediate local peer/actor/destination revocation + expired lease rejection on inbound LXMF traffic.

### 1.2 RNS-07: Message, Voice Note, PhotoOutbox & ChannelAttachment Migration + Zero-Duplicate Reconciliation
- **`RnsModels.kt` & `RnsResourceTransferManager.kt`**:
  - Canonical `protocol-x.rns.envelope/v1` encoding (`RnsEnvelopeCodec.canonicalJsonDump` + SHA-256 `fingerprint`) across `task`, `message`, `attachment`, and `delivery_receipt` kinds.
  - Resumable chunked LXMF Resource transfer engine (`RnsResourceTransferManager`) supporting `PhotoOutbox` JPEGs (up to 1 MB) and `ChannelAttachmentFiles` (`image/jpeg` up to 750 KB + `48 KB` poster, `video/mp4` up to 16 MB / <= 30 s + `48 KB` poster), tracking `sha256_content`, `sha256_combined`, and per-chunk resume checkpoints in `RnsLocalStore`.
- **`RnsRuntimeCoordinator.kt`, `RnsLxmfTransportAdapter.kt`, `NearbyCommAdapter.kt`, `NearbyPanel.kt`, `MainActivity.kt`**:
  - `DefaultCommGateway.isSessionReady` and `markRead` integrate `RNS_MESH` alongside Nearby Mesh; `CONTROL_ACK` (`confirm:<id>`) read receipts update target message `readByNames` cleanly without emitting extra chat bubbles.
  - HTTP `/history`, `/attachments`, and `/photos` responses reconcile local `PhotoOutbox`, `ChannelAttachmentFiles`, and `RnsLocalStore` envelopes by canonical `id` (`markEnvelopeSynced` -> `CommDeliveryState.SYNCED`) for zero duplicates.

### 1.3 RNS-09: Deterministic Idempotency, Checkpointing, Causal Replay & 409 Reconciliation
- **`FieldOfflineStore.kt` & `OfflineOutboxAdapters.kt`**:
  - Extended `Queued` with persisted `idempotencyKey`, canonical `fingerprint`, `envelopeId`, `baseVersion`, `deliveryState`, `conflictDetailJson`, monotonic `clientSeq`, chained `expected_version`, and encrypted sync checkpoints (`workspace_revision`, `channel_cursor`, `flight_notice_seq`).
- **`OpsClient.kt`, `RnsOfflineSyncReconciler.kt`, `MainActivity.kt`**:
  - Added `submitRnsEnvelope` (`POST /api/rns/v1/envelopes`), `submitRnsEnvelopesBatch` (`POST /api/rns/v1/envelopes/batch`), `confirmArrivalStage` (`StageInput.idempotency_key`), and structured HTTP `409` `conflictPayload` parsing on `OpsException`.
  - Offline task actions and assistance requests propagate canonical `EnvelopeV1` payloads over `RNS_MESH`, drain in causal `clientSeq` order, consume gateway `action_committed` / `conflict` receipts, and classify `409 Conflict` into `AlreadyAchieved` (clean auto-complete) vs `VersionDrift` (surface `CONFLICT_HOLD` after workspace refresh).

---

## 2. Verification

Synced `android/app/src/main/java/uniffi/protocolx_rns/protocolx_rns.kt` (`5,540` lines, `201,860` bytes) from `@rns-poc`'s `rns/rust-complete` (`a21fdf1`) and wired `UniFFIRnsCoreEngine.kt` (`IfacConfig`, `revokedIdentityHashes`, `rotateIfacCredentials`, `revokePeer`, `revokedPeers`, `bindActorDeviceToRns`, `PttFrameReceived`/`PttFloorEvent`).

Verified with JDK 17 and Android SDK 36:

```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-android-app/android && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest --rerun-tasks --no-daemon --console=plain && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew assembleDebug --no-daemon --console=plain
```

Observed results:
- `./gradlew testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL` — **21 suites, 74 tests, 0 failures, 0 errors, 0 skipped** (69 existing regression tests across 20 suites + 5 new Wave-1 RNS-05/07/09 integration tests in `RnsWave1IntegrationTest`).
- `./gradlew assembleDebug`: `BUILD SUCCESSFUL` — produced `android/app/build/outputs/apk/debug/app-debug.apk` (`159M`).
