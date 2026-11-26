# RNS-03: Android RNS Runtime & LXMF Integration

**Branch:** `rns/03-android-runtime` (stacked on `dd1211d` = RNS-01 + RNS-02)  
**Worktree:** `/Users/proerror/Documents/toc/protocol-x-rns-03-android`  
**Package:** `com.mandonothing.ops.comm.rns` + `uniffi.protocolx_rns`

---

## 1. Architecture & Components

### 1.1 UniFFI + JNA + Native Packaging
- **`android/app/build.gradle.kts`**:
  - Added `implementation("net.java.dev.jna:jna:5.15.0@aar")` for Android runtime FFI (`com.sun.jna.internal.Cleaner` required by `uniffi 0.33.0`).
  - Added `testImplementation("net.java.dev.jna:jna:5.15.0")` and `testImplementation("org.json:json:20240303")` for host JVM unit tests.
- **`android/app/src/main/java/uniffi/protocolx_rns/protocolx_rns.kt`**:
  - Copied from `@rns-poc`'s generated `rust/protocolx-rns/bindings/kotlin/uniffi/protocolx_rns/protocolx_rns.kt` (`uniffi 0.33.0`, 4,487 lines).
- **`RnsNativeLoader.kt`**:
  - Loads `libprotocolx_rns.so` (`System.loadLibrary("protocolx_rns")`) once on Android and catches `UnsatisfiedLinkError` / `SecurityException` so JVM host unit tests and environments without the NDK-linked `.so` degrade gracefully (`isNativeAvailable == false`).
- **Native `.so` ABI packaging (`android/app/src/main/jniLibs/<abi>/libprotocolx_rns.so`)**:
  - `app-debug.apk` bundles `lib/<abi>/libjnidispatch.so` from JNA `5.15.0@aar` across `arm64-v8a`, `armeabi-v7a`, `armeabi`, `mips`, `mips64`, `x86`, and `x86_64`. `libprotocolx_rns.so` is loaded from `jniLibs/<abi>/libprotocolx_rns.so` when linked with an Android NDK sysroot (`cargo ndk` / `cargo-zigbuild`); no local Android NDK sysroot is installed on this host, so `libprotocolx_rns.so` is not pre-checked-in and `RnsNativeLoader` keeps runtime startup safe until NDK-linked `.so` artifacts are supplied.

### 1.2 Encrypted Local Persistence (`RnsLocalStore.kt`)
- Reuses `FieldSessionStore` (`AndroidKeyStore` AES-256-GCM with per-record AAD contexts) via `RnsPayloadCipher` (`FieldSessionRnsCipher` in production, injectable cipher in JVM unit tests).
- Persists atomically under `filesDir/rns-runtime/`:
  - `identities/<account>.dat` — 64-byte `RnsIdentity` key material (`x25519_prv || ed25519_prv`), `identity_hash_hex`, `destination_hash_hex`.
  - `runtime-config.dat` — active `RnsRuntimeConfig` for automatic OS service restart recovery (`START_STICKY`).
  - `outbox/<id>.dat` — immutable-ID LXMF outbox queue tracking `LxmfDeliveryMode` (`OPPORTUNISTIC`, `DIRECT_LINK`, `PROPAGATED`), `LxmfDeliveryStatus` (`QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `PROPAGATED`, `FAILED`), `message_hash_hex`, and retry counts.
  - `inbox/<messageHashHex>.dat` — signature-verified, deduplicated inbound LXMF messages & attachments.
  - `peers.dat` — discovered peer announces (`destinationHashHex`, `appDataText`, `hops`, `lastHeardAtMs`).

### 1.3 Foreground Service & Runtime Coordinator (`RnsRuntimeService.kt`, `RnsRuntimeCoordinator.kt`, `UniFFIRnsCoreEngine.kt`)
- **`AndroidManifest.xml`**: Declares `.comm.rns.RnsRuntimeService` (`android:exported="false"`, `android:foregroundServiceType="connectedDevice|dataSync"`), aligned with Android 14/15 foreground service restrictions and `NearbyService` / `VoiceSessionService` patterns.
- **`RnsRuntimeCoordinator`**: Pure Kotlin lifecycle & store-and-forward engine:
  - Restores persisted identity and active config on start/restart (`restoreIfConfigured()`).
  - Enforces inbound LXMF signature verification (`signatureValid`) and hash deduplication.
  - Matches `LxmfDeliveryReceipt` (`clientMessageId` / `messageHashHex`) back to outbox records and emits `LxmfDeliveryReceipt` flows.
  - Retries `QUEUED` entries on peer announce arrival and periodic 10 s maintenance ticks.
- **`UniFFIRnsCoreEngine`**: Adapts `uniffi.protocolx_rns.RnsNode` (`NodeConfig`, `sendLxmf`, `fetchPropagatedLxmf`, `persistedLxmfMessages`, `persistedLxmfReceipts`, `RnsEventListener`) behind `RnsCoreEngine`.

### 1.4 RNS-02 Gateway Integration (`RnsLxmfTransportAdapter.kt`, `NearbyCommAdapter.kt`)
- Implements `CommTransportAdapter` with `kind = CommTransportKind.RNS_MESH`.
- `RnsEnvelopeCodec` maps `CommOutboundMessage` (`CHANNEL_TEXT`, `PRIVATE_ENCRYPTED`, `ASSISTANCE_ALERT`, `VOICE_NOTE`, `ATTACHMENT_MEDIA`, `TASK_ACTION`, `CONTROL_ACK`, `DELIVERY_RECEIPT`) to `protocol-x.rns.envelope/v1` LXMF metadata + `LxmfAttachment` payloads, selecting `OPPORTUNISTIC` (<= 295 B) vs `DIRECT_LINK` / `PROPAGATED` (multi-KB Resource transfers).
- Registered in `DefaultCommGateway` alongside `NearbyTransportAdapter` (`WIFI_AWARE`, `BLE_L2CAP`), surfacing `DELIVERED`, `PROPAGATED`, and `FAILED` receipts as `CommEventEnvelope.DeliveryUpdated` and merging inbound LXMF envelopes into `CommGateway.messages`.

---

## 2. Verification

Ran full unit test suite and debug APK build with JDK 17 and Android SDK 36:

```sh
cd /Users/proerror/Documents/toc/protocol-x-rns-03-android/android && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest --rerun-tasks --no-daemon --console=plain && \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew assembleDebug --no-daemon --console=plain
```

Observed results:
- `./gradlew testDebugUnitTest --rerun-tasks`: `BUILD SUCCESSFUL` — **17 suites, 60 tests, 0 failures, 0 errors, 0 skipped** (52 existing regression tests across 14 suites + 8 new RNS-03 unit tests across `RnsLocalStoreTest` [2], `RnsRuntimeCoordinatorTest` [3], and `RnsLxmfTransportAdapterTest` [3]).
- `./gradlew assembleDebug`: `BUILD SUCCESSFUL` — generated `android/app/build/outputs/apk/debug/app-debug.apk` (159 MB), verified to bundle `lib/*/libjnidispatch.so` (`arm64-v8a`, `armeabi-v7a`, `armeabi`, `mips`, `mips64`, `x86`, `x86_64`).
