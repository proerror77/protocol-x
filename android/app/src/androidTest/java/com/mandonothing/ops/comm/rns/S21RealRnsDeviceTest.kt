package com.mandonothing.ops.comm.rns

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.mandonothing.ops.FieldSessionStore
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.comm.PttCue
import com.mandonothing.ops.comm.PttPhase
import com.mandonothing.ops.comm.aware.AndroidBleL2capDriver
import com.mandonothing.ops.comm.aware.AndroidConnectivityDriver
import com.mandonothing.ops.comm.aware.RnsBleL2capAdapter
import com.mandonothing.ops.comm.aware.RnsBridgeLinkMetrics
import com.mandonothing.ops.comm.aware.RnsCustomInterfaceKind
import com.mandonothing.ops.comm.aware.RnsCustomInterfaceOutboundHandler
import com.mandonothing.ops.comm.aware.RnsFrameBridge
import com.mandonothing.ops.comm.aware.RnsLinkConfig
import com.mandonothing.ops.comm.aware.RnsWifiAwareAdapter
import com.mandonothing.ops.comm.aware.TrackedNetworkCallbackRegistry
import com.mandonothing.ops.comm.ptt.RnsPttAdpcmCodec
import com.mandonothing.ops.comm.ptt.RnsPttFloorAction
import com.mandonothing.ops.comm.ptt.RnsPttFrame
import com.mandonothing.ops.comm.ptt.RnsPttSessionTransport
import com.mandonothing.ops.comm.ptt.RnsPttTransportBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import uniffi.protocolx_rns.bindActorDeviceToRns
import uniffi.protocolx_rns.deriveEventIfacConfig
import uniffi.protocolx_rns.generateIdentity
import uniffi.protocolx_rns.loadIdentityFromSecret
import uniffi.protocolx_rns.verifyActorDeviceBinding
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Real on-device instrumented QA suite verified against Samsung Galaxy S21 Ultra (`SM-G9980`).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class S21RealRnsDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t01_nativeLoaderAndUniFfiPrimitivesSucceedOnS21Arm64() {
        assertTrue(
            "Expected arm64-v8a device ABI, saw ${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.contains("arm64-v8a"),
        )
        assertTrue(
            "RnsNativeLoader.ensureLoaded() failed: ${RnsNativeLoader.loadError}",
            RnsNativeLoader.ensureLoaded(),
        )
        assertTrue(RnsNativeLoader.isNativeAvailable)
        assertNull(RnsNativeLoader.loadError)

        val generated = generateIdentity()
        val secret = generated.secretBytes()
        assertEquals(64, secret.size)

        val restored = loadIdentityFromSecret(secret)
        assertEquals(generated.identityHashHex(), restored.identityHashHex())
        assertEquals(
            generated.destinationHashHex("protocolx", listOf("node")),
            restored.destinationHashHex("protocolx", listOf("node")),
        )

        val ifac = deriveEventIfacConfig(
            eventId = "s21-qa-event",
            channelId = "s21-qa-channel",
            sharedSecret = "s21-shared-secret",
            epoch = 1u,
        )
        assertTrue(ifac.requirePrivateIfac)
        assertEquals(16, ifac.ifacSizeBytes.toInt())
        assertFalse(ifac.networkName.isNullOrBlank())
        assertFalse(ifac.passphrase.isNullOrBlank())

        val pub = generated.publicKeyBytes()
        val edHex = (if (pub.size >= 64) pub.copyOfRange(32, 64) else pub)
            .joinToString("") { "%02x".format(it) }
        val destHex = generated.destinationHashHex("lxmf", listOf("delivery"))
        val binding = bindActorDeviceToRns(
            actorId = "s21-actor",
            deviceTokenHash = "0123456789abcdef0123456789abcdef",
            rnsDestinationHashHex = destHex,
            rnsIdentityHashHex = generated.identityHashHex(),
            ed25519PublicKeyHex = edHex,
        )
        assertTrue(verifyActorDeviceBinding(binding))
        assertEquals(64, binding.bindingDigestHex.length)
    }

    @Test
    fun t02_uniFfiNodeAndRnsRuntimeServiceAndKeystorePersistOnAndroid15() {
        val accountKey = "s21-qa-acct-${System.currentTimeMillis()}"
        val eventId = "s21-qa-evt"
        val channelId = "s21-qa-ch"
        val runtimeDir = File(context.filesDir, "rns-runtime")
        val coreDir = File(context.cacheDir, "s21-rns-core-${System.currentTimeMillis()}")
        runtimeDir.deleteRecursively()
        coreDir.mkdirs()

        val fieldStore = FieldSessionStore(context)
        val rawRoundtrip = "android-keystore-aes-gcm-check".toByteArray(Charsets.UTF_8)
        val encrypted = fieldStore.encrypt(rawRoundtrip, "s21-aad")
        assertArrayEquals(rawRoundtrip, fieldStore.decrypt(encrypted, "s21-aad"))

        val config = RnsRuntimeConfig(
            accountKey = accountKey,
            eventId = eventId,
            channelId = channelId,
            channelName = "S21 QA",
            actorId = "s21-device",
            storageDir = coreDir.absolutePath,
            lxmfDisplayName = "s21-fgs-node",
            ifac = RnsIfacPolicy(
                networkName = "protocolx.s21.service",
                passphrase = "s21-service-passphrase",
                ifacSizeBytes = 16,
                requirePrivateIfac = true,
            ),
        )

        try {
            assertTrue("RnsRuntimeService.start failed", RnsRuntimeService.start(context, config))
            waitUntil(timeoutMs = 10_000L, failureMessage = "RnsRuntimeService did not start cleanly") {
                RnsRuntimeService.snapshot.value.running && RnsRuntimeService.snapshot.value.nativeAvailable
            }
            val firstSnap = RnsRuntimeService.snapshot.value
            assertFalse(firstSnap.identityHashHex.isBlank())
            assertFalse(firstSnap.lxmfDestinationHashHex.isBlank())

            val store = RnsLocalStore(context)
            val savedIdentity = store.loadIdentity(accountKey)
            assertNotNull("Expected encrypted RnsLocalStore identity", savedIdentity)
            assertEquals(firstSnap.identityHashHex, savedIdentity!!.identityHashHex)
            val savedConfig = store.loadRuntimeConfig()
            assertNotNull(savedConfig)
            assertEquals(accountKey, savedConfig!!.accountKey)

            RnsRuntimeService.stop(context)
            waitUntil(timeoutMs = 8_000L, failureMessage = "RnsRuntimeService did not stop") {
                !RnsRuntimeService.snapshot.value.running
            }

            // Re-save config and restart to verify Keystore-encrypted identity persistence across restarts
            assertTrue("RnsRuntimeService restart failed", RnsRuntimeService.start(context, config))
            waitUntil(timeoutMs = 10_000L, failureMessage = "RnsRuntimeService did not restart") {
                RnsRuntimeService.snapshot.value.running && RnsRuntimeService.snapshot.value.nativeAvailable
            }
            val secondSnap = RnsRuntimeService.snapshot.value
            assertEquals(
                "RNS identity must persist across RnsRuntimeService restart",
                firstSnap.identityHashHex,
                secondSnap.identityHashHex,
            )
            assertEquals(firstSnap.lxmfDestinationHashHex, secondSnap.lxmfDestinationHashHex)
        } finally {
            RnsRuntimeService.stop(context)
            waitUntil(timeoutMs = 5_000L, failureMessage = "cleanup stop timed out") {
                !RnsRuntimeService.snapshot.value.running
            }
            runtimeDir.deleteRecursively()
            coreDir.deleteRecursively()
        }
    }

    @Test
    fun t03_s21WifiAwareAndBleHardwareAndLeakFreeRegistryTeardown() {
        val pm = context.packageManager
        assertTrue(
            "Expected FEATURE_WIFI_AWARE on S21",
            pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
        )
        assertTrue(
            "Expected FEATURE_BLUETOOTH_LE on S21",
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        )

        val wifiAwareMgr = context.getSystemService(WifiAwareManager::class.java)
        assertNotNull("WifiAwareManager must be present", wifiAwareMgr)

        val btMgr = context.getSystemService(BluetoothManager::class.java)
        assertNotNull("BluetoothManager must be present", btMgr)
        val btAdapter = btMgr!!.adapter
        assertNotNull("BluetoothAdapter must be present", btAdapter)
        assertTrue("Expected Bluetooth enabled on S21", btAdapter.isEnabled)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        assertNotNull("ConnectivityManager must be present", cm)

        val dummyBridge = object : RnsFrameBridge {
            override fun attachInterface(
                interfaceTag: String,
                kind: RnsCustomInterfaceKind,
                hwMtu: Int,
                bitrateBps: Long,
                outboundHandler: RnsCustomInterfaceOutboundHandler,
            ) = Unit

            override fun detachInterface(interfaceTag: String): Boolean = true
            override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) = Unit
            override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) = Unit
            override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> = emptyList()
        }
        val wifiAdapter = RnsWifiAwareAdapter.create(
            context = context,
            bridge = dummyBridge,
            serviceName = "pxs21qa",
            wifiPassphrase = "s21-aware-passphrase",
            localDeviceToken = 0x11223344,
            config = RnsLinkConfig(),
        )
        val bleDriver = AndroidBleL2capDriver(
            context = context,
            serviceUuid = UUID.fromString("0000cafe-0000-1000-8000-00805f9b34fb"),
            localDeviceToken = 0x11223344,
        )
        val bleAdapter = RnsBleL2capAdapter(
            bridge = dummyBridge,
            localDeviceToken = 0x11223344,
            config = RnsLinkConfig(),
            driver = bleDriver,
        )

        val registry = TrackedNetworkCallbackRegistry<String, NetworkRequest, ConnectivityManager.NetworkCallback>(
            AndroidConnectivityDriver(cm!!),
        )

        try {
            wifiAdapter.start()
            Thread.sleep(300L)

            assertTrue(
                "AndroidBleL2capDriver should be available on S21 with BT enabled & permissions granted",
                bleDriver.isAvailable(),
            )
            bleAdapter.start()
            Thread.sleep(250L)

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {}
            assertTrue(registry.register("s21-aware-probe", request, callback, 500L))
            assertEquals(1, registry.activeCount())

            registry.unregisterAll(permanent = true)
            val (activeCount, totals) = registry.snapshot()
            assertEquals(0, activeCount)
            assertEquals(1L, totals.first)
            assertEquals(1L, totals.second)
        } finally {
            wifiAdapter.close()
            bleAdapter.close()
            val wifiLeaks = wifiAdapter.leakCounters()
            assertEquals(0, wifiLeaks.activeNetworkCallbacks)
            assertEquals(0, wifiLeaks.activeSockets)
            assertEquals(wifiLeaks.totalNetworkCallbacksRegistered, wifiLeaks.totalNetworkCallbacksUnregistered)
            val bleLeaks = bleAdapter.leakCounters()
            assertEquals(0, bleLeaks.activeSockets)
        }
    }

    @Test
    fun t04_s21RnsNodeBidirectionalWithMacPythonRnsAndLxmfOverAdbReverse() {
        val args = InstrumentationRegistry.getArguments()
        val peerHost = args.getString("rnsPeerHost") ?: "127.0.0.1"
        val peerPort = args.getString("rnsPeerPort")?.toIntOrNull() ?: 17421
        val wrongPort = args.getString("rnsWrongPort")?.toIntOrNull() ?: 17422
        val ifacNet = args.getString("rnsIfacNet") ?: "protocolx.s21.qa"
        val ifacPass = args.getString("rnsIfacPass") ?: "s21-qa-secret-v1"
        val expectedPyLxmf = args.getString("pyLxmfDest").orEmpty().ifBlank { DEFAULT_PY_LXMF_DEST }
        val expectedPyPtt = args.getString("pyPttDest").orEmpty().ifBlank { DEFAULT_PY_PTT_DEST }
        val rogueLxmf = args.getString("rogueLxmfDest").orEmpty().ifBlank { DEFAULT_ROGUE_LXMF_DEST }
        val roguePrimary = args.getString("roguePrimaryDest").orEmpty().ifBlank { DEFAULT_ROGUE_PRIMARY_DEST }

        assertTrue(
            "Mac Python RNS peer not reachable at $peerHost:$peerPort (start scripts/s21_rns_peer.py and adb reverse tcp:$peerPort tcp:$peerPort)",
            isTcpPortReachable(peerHost, peerPort),
        )

        val engine = UniFFIRnsCoreEngine()
        assertTrue(engine.isAvailable)
        val events = CopyOnWriteArrayList<RnsCoreEvent>()
        val coreDir = File(context.cacheDir, "s21-py-e2e-${System.currentTimeMillis()}").apply { mkdirs() }
        val connectAddrs = buildList {
            add("$peerHost:$peerPort")
            if (wrongPort > 0 && isTcpPortReachable(peerHost, wrongPort)) {
                add("$peerHost:$wrongPort")
            }
        }
        val config = RnsRuntimeConfig(
            accountKey = "s21-py-e2e",
            eventId = "s21-e2e-evt",
            channelId = "s21-ptt-ch",
            channelName = "S21 E2E",
            actorId = "s21-android-node",
            storageDir = coreDir.absolutePath,
            lxmfDisplayName = "s21-android-node",
            tcpConnectAddrs = connectAddrs,
            ifac = RnsIfacPolicy(
                networkName = ifacNet,
                passphrase = ifacPass,
                ifacSizeBytes = 16,
                requirePrivateIfac = true,
            ),
        )

        try {
            val status = engine.start(config, identitySecret = null) { ev ->
                events.add(ev)
            }
            assertTrue(status.running)
            assertFalse(status.lxmfDestinationHashHex.isBlank())
            assertFalse(engine.pttDestinationHashHex().isBlank())

            waitUntil(
                timeoutMs = 15_000L,
                failureMessage = "S21 did not hear Mac Python RNS announces for $expectedPyLxmf and $expectedPyPtt",
                onTick = {
                    engine.announce("s21-android-node".toByteArray(Charsets.UTF_8))
                    engine.announceLxmf("s21-android-node")
                    engine.announcePtt()
                },
                tickIntervalMs = 250L,
            ) {
                val known = engine.activeNode()?.knownDestinations().orEmpty()
                expectedPyLxmf in known && expectedPyPtt in known
            }

            // Verify IFAC isolation: rogue peer on wrongPort must NEVER be known
            val knownSet = engine.activeNode()!!.knownDestinations()
            assertFalse("Wrong-IFAC rogue LXMF destination leaked into S21", rogueLxmf in knownSet)
            assertFalse("Wrong-IFAC rogue primary destination leaked into S21", roguePrimary in knownSet)
            val rogueOutcome = engine.sendLxmf(
                LxmfSendRequest(
                    messageId = "s21-rogue-fail",
                    destinationHashHex = rogueLxmf,
                    title = "ShouldFail",
                    contentText = "Blocked by IFAC",
                    deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                    idempotencyKey = "s21-rogue-fail",
                ),
            )
            assertEquals(LxmfDeliveryStatus.FAILED, rogueOutcome.initialStatus)

            // 1. S21 -> Mac Python Opportunistic LXMF
            val oppOutcome = engine.sendLxmf(
                LxmfSendRequest(
                    messageId = "s21-opp-1",
                    destinationHashHex = expectedPyLxmf,
                    title = "OppS21",
                    contentText = "Hello from S21 Android RNS",
                    deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                    idempotencyKey = "s21-opp-1",
                ),
            )
            assertEquals(LxmfDeliveryStatus.DELIVERED, oppOutcome.initialStatus)

            // 2. S21 -> Mac Python Direct + 6KB Resource Attachment LXMF
            val sixKbPayload = ByteArray(6144) { i -> (i % 247).toByte() }
            val resOutcome = engine.sendLxmf(
                LxmfSendRequest(
                    messageId = "s21-res-1",
                    destinationHashHex = expectedPyLxmf,
                    title = "ResS21",
                    contentText = "6KB attachment from S21",
                    attachmentKind = LxmfAttachmentKind.BINARY_RESOURCE,
                    attachmentFilename = "s21_photo.bin",
                    attachmentBytes = sixKbPayload,
                    deliveryMode = LxmfDeliveryMode.DIRECT_LINK,
                    idempotencyKey = "s21-res-1",
                ),
            )
            assertEquals(LxmfDeliveryStatus.DELIVERED, resOutcome.initialStatus)

            // 3. S21 <-> Mac Python PTT Floor Control + IMA ADPCM PTT Frame
            val floorGrant = engine.requestPttFloor(
                destinationHashHex = expectedPyPtt,
                channelId = "s21-ptt-ch",
                sessionId = "s21-ptt-sess",
                priority = 1,
            )
            assertNotNull(floorGrant)
            assertEquals("GRANT", floorGrant!!.action.uppercase())

            val adpcmPayload = RnsPttAdpcmCodec.encode(generateSineSamples())
            assertTrue(
                "sendPttFrame to Python failed",
                engine.sendPttFrame(
                    destinationHashHex = expectedPyPtt,
                    channelId = "s21-ptt-ch",
                    sessionId = "s21-ptt-sess",
                    seq = 1L,
                    codec = RnsPttAdpcmCodec.CODEC_ID,
                    durationMs = RnsPttAdpcmCodec.FRAME_DURATION_MS,
                    audioBytes = adpcmPayload,
                ),
            )
            val floorRelease = engine.releasePttFloor(
                destinationHashHex = expectedPyPtt,
                channelId = "s21-ptt-ch",
                sessionId = "s21-ptt-sess",
            )
            assertNotNull(floorRelease)
            assertEquals("RELEASE", floorRelease!!.action.uppercase())

            // 4. Verify S21 receives Opportunistic LXMF, 5KB Direct Resource LXMF, PTT Floor, and PTT Frame from Python
            val expectedPyRes = ByteArray(5120) { i -> (i % 251).toByte() }
            waitUntil(
                timeoutMs = 20_000L,
                failureMessage = "Timed out waiting for Python LXMF + Resource + PTT replies on S21 (events=${events.size})",
            ) {
                val gotOpp = events.any {
                    it is RnsCoreEvent.LxmfReceived &&
                        it.title == "OppPyS21" &&
                        it.contentText == "Opp from Python RNS to S21" &&
                        it.signatureValid
                }
                val gotRes = events.any {
                    it is RnsCoreEvent.LxmfReceived &&
                        it.title == "ResPyS21" &&
                        it.signatureValid &&
                        it.attachmentBytes != null &&
                        it.attachmentBytes.contentEquals(expectedPyRes)
                }
                val gotPttFloor = events.any {
                    it is RnsCoreEvent.PttFloorEvent &&
                        it.channelId == "s21-ptt-ch" &&
                        it.action.equals("GRANT", ignoreCase = true)
                }
                val gotPttFrame = events.any {
                    it is RnsCoreEvent.PttFrameReceived &&
                        it.channelId == "s21-ptt-ch" &&
                        it.codec == RnsPttAdpcmCodec.CODEC_ID &&
                        it.audioBytes.size == 164
                }
                gotOpp && gotRes && gotPttFloor && gotPttFrame
            }

            // Signal Python peer that S21 verified all inbound payloads so Python exits cleanly
            val ackOutcome = engine.sendLxmf(
                LxmfSendRequest(
                    messageId = "s21-done-ack",
                    destinationHashHex = expectedPyLxmf,
                    title = "S21DoneAck",
                    contentText = "S21 verified all LXMF, Resource, IFAC, and PTT checks",
                    deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                    idempotencyKey = "s21-done-ack",
                ),
            )
            assertEquals(LxmfDeliveryStatus.DELIVERED, ackOutcome.initialStatus)
            Thread.sleep(400L)
        } finally {
            engine.stop()
            coreDir.deleteRecursively()
        }
    }

    @Test
    fun t05_s21AdpcmAndPttSessionTransportSelfCheck() {
        val sine = generateSineSamples()
        val encoded = RnsPttAdpcmCodec.encode(sine)
        assertEquals(164, encoded.size)
        val decoded = RnsPttAdpcmCodec.decode(encoded, RnsPttAdpcmCodec.FRAME_SAMPLES)
        assertEquals(RnsPttAdpcmCodec.FRAME_SAMPLES, decoded.size)
        assertTrue("Decoded ADPCM waveform must preserve signal energy", decoded.any { kotlin.math.abs(it.toInt()) > 1000 })

        val sentFrames = CopyOnWriteArrayList<RnsPttFrame>()
        val clips = CopyOnWriteArrayList<VoiceCapturedClip>()
        val cues = CopyOnWriteArrayList<PttCue>()
        val phases = CopyOnWriteArrayList<PttPhase>()

        val bridge = object : RnsPttTransportBridge {
            override val localHashHex: String = "00112233445566778899aabbccddeeff"
            override fun peerDestinations(channelId: String): List<String> = listOf("ffeeddccbbaa99887766554433221100")
            override fun sendPttFrame(destinationHashHex: String, frame: RnsPttFrame): Boolean {
                sentFrames.add(frame)
                return true
            }

            override fun requestPttFloor(
                destinationHashHex: String,
                channelId: String,
                sessionId: String,
                priority: Int,
            ) = com.mandonothing.ops.comm.ptt.RnsPttFloorControl(
                channelId = channelId,
                sessionId = sessionId,
                actorHashHex = localHashHex,
                action = RnsPttFloorAction.GRANT,
                priority = priority,
                timestampMs = System.currentTimeMillis(),
                holderHashHex = localHashHex,
                detail = "s21_test_grant",
            )

            override fun releasePttFloor(
                destinationHashHex: String,
                channelId: String,
                sessionId: String,
            ) = com.mandonothing.ops.comm.ptt.RnsPttFloorControl(
                channelId = channelId,
                sessionId = sessionId,
                actorHashHex = localHashHex,
                action = RnsPttFloorAction.RELEASE,
                timestampMs = System.currentTimeMillis(),
                detail = "s21_test_release",
            )
        }

        lateinit var transport: RnsPttSessionTransport
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            transport = RnsPttSessionTransport.create(
                context = context,
                channelId = "s21-audio-ch",
                localDisplayName = "S21 Tester",
                bridge = bridge,
                onSpeaker = {},
                onOutput = {},
                onClip = { clips.add(it) },
                onCue = { cues.add(it) },
                onState = { phase, _ -> phases.add(phase) },
            )
            transport.connect()
        }

        try {
            assertTrue(phases.contains(PttPhase.LISTENING))
            transport.setReceiveMuted(true)
            assertTrue(transport.receiveMuted)
            transport.setReceiveMuted(false)
            assertFalse(transport.receiveMuted)

            val outputs = runCatching { transport.audioOutputs() }.getOrDefault(emptyList())
            assertNotNull(outputs)

            // Exercise real Android AudioRecord capture when mic is granted & free, plus ADPCM frame pipeline
            val speaking = transport.startPress()
            if (speaking) {
                transport.pushLocalPcmFrame(sine)
                transport.stopPress(cancel = false)
                assertTrue(sentFrames.isNotEmpty())
            }

            // Deliver >= 280 ms (14 x 20ms frames + endOfBurst) inbound burst -> AudioTrack playout + clip archive
            val baseTs = System.currentTimeMillis()
            for (seq in 0L until 14L) {
                transport.onInboundFrame(
                    RnsPttFrame(
                        channelId = "s21-audio-ch",
                        sessionId = "s21-rx-burst",
                        senderHashHex = "ffeeddccbbaa99887766554433221100",
                        senderName = "RemotePeer",
                        seq = seq,
                        timestampMs = baseTs + seq * 20L,
                        codec = RnsPttAdpcmCodec.CODEC_ID,
                        durationMs = RnsPttAdpcmCodec.FRAME_DURATION_MS,
                        audioBytes = encoded,
                    ),
                )
            }
            transport.onInboundFrame(
                RnsPttFrame(
                    channelId = "s21-audio-ch",
                    sessionId = "s21-rx-burst",
                    senderHashHex = "ffeeddccbbaa99887766554433221100",
                    senderName = "RemotePeer",
                    seq = 14L,
                    timestampMs = baseTs + 280L,
                    durationMs = 0,
                    audioBytes = ByteArray(0),
                    endOfBurst = true,
                ),
            )

            assertEquals("14 inbound audio frames expected in playout stats", 14L, transport.playoutStats().framesPlayed)
            assertTrue("Expected archived VoiceCapturedClip for >=0.25s burst", clips.isNotEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                transport.close()
            }
        }
    }

    private fun generateSineSamples(): ShortArray =
        ShortArray(RnsPttAdpcmCodec.FRAME_SAMPLES) { idx ->
            (sin(2.0 * PI * 440.0 * idx / RnsPttAdpcmCodec.SAMPLE_RATE_HZ) * 12_000.0).roundToInt().toShort()
        }

    private fun isTcpPortReachable(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 1_500)
                true
            }
        }.getOrDefault(false)

    private fun waitUntil(
        timeoutMs: Long,
        failureMessage: String,
        tickIntervalMs: Long = 100L,
        onTick: () -> Unit = {},
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            onTick()
            if (condition()) return
            Thread.sleep(tickIntervalMs)
        }
        assertTrue(failureMessage, condition())
    }

    private companion object {
        // Deterministic fallbacks matching scripts/s21_rns_peer.py default seeds when -e overrides are omitted
        const val DEFAULT_PY_LXMF_DEST = "c3de1a888e6368d50000d9f71da58782"
        const val DEFAULT_PY_PTT_DEST = "da3cb5c0dd85014bf8090ab199872b47"
        const val DEFAULT_ROGUE_LXMF_DEST = "c3d7a612f31abf48a5fa862253a8b0f9"
        const val DEFAULT_ROGUE_PRIMARY_DEST = "a21fea88a56c17d75df3db9e63c2502c"
    }
}
