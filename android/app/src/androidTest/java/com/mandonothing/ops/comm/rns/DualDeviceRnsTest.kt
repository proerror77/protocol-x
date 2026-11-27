package com.mandonothing.ops.comm.rns

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandonothing.ops.comm.aware.AndroidBleL2capDriver
import com.mandonothing.ops.comm.aware.AndroidConnectivityDriver
import com.mandonothing.ops.comm.aware.AndroidWifiAwareDriver
import com.mandonothing.ops.comm.aware.BleFallbackPolicy
import com.mandonothing.ops.comm.aware.RnsAwareBridgeConnector
import com.mandonothing.ops.comm.aware.RnsAwareTransportCoordinator
import com.mandonothing.ops.comm.aware.RnsBleL2capAdapter
import com.mandonothing.ops.comm.aware.RnsIfacConfig
import com.mandonothing.ops.comm.aware.RnsIfacFrameGuard
import com.mandonothing.ops.comm.aware.RnsLinkConfig
import com.mandonothing.ops.comm.aware.RnsWifiAwareAdapter
import com.mandonothing.ops.comm.aware.UniFfiRnsNodeBridgeSink
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.mandonothing.ops.comm.ptt.RnsPttAdpcmCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.protocolx_rns.loadIdentityFromSecret
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dual-phone S21 <-> S26 physical RF P2P verification (Zero Internet relay).
 *
 * Activated only when `-e role publisher` or `-e role subscriber` is passed so
 * standalone `S21RealRnsDeviceTest` runs remain unaffected.
 */
@RunWith(AndroidJUnit4::class)
class DualDeviceRnsTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dualPhoneDirectEncryptedP2pOverLocalRadios() {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("role")?.lowercase()?.trim().orEmpty()
        assumeTrue(
            "Skipping DualDeviceRnsTest unless -e role publisher|subscriber is passed",
            role == "publisher" || role == "subscriber",
        )
        val isPublisher = role == "publisher"
        val discoveryTimeoutMs = args.getString("discoveryTimeoutMs")?.toLongOrNull() ?: 30_000L
        val exchangeTimeoutMs = args.getString("exchangeTimeoutMs")?.toLongOrNull() ?: 35_000L
        val enableBleFallback = args.getString("enableBleFallback")?.toBooleanStrictOrNull() ?: true

        assertTrue(RnsNativeLoader.ensureLoaded())
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))

        val mySecret = deterministicSecret(if (isPublisher) "s21s26-pub-v1" else "s21s26-sub-v1")
        val peerSecret = deterministicSecret(if (isPublisher) "s21s26-sub-v1" else "s21s26-pub-v1")
        val peerIdentity = loadIdentityFromSecret(peerSecret)
        val peerPrimaryDest = peerIdentity.destinationHashHex("protocolx", listOf("node"))
        val peerLxmfDest = peerIdentity.destinationHashHex("lxmf", listOf("delivery"))
        val peerPttDest = peerIdentity.destinationHashHex("protocolx", listOf("ptt"))

        val localDeviceToken = if (isPublisher) 0x10000001 else 0x20000002
        val coreDir = File(context.cacheDir, "rns-dual-$role-${System.currentTimeMillis()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val topologyDiagnostics = RnsTopologyDiagnostics()
        val engine = UniFFIRnsCoreEngine()
        val ifacKeyBytes = MessageDigest.getInstance("SHA-256")
            .digest("$IFAC_NET|$IFAC_PASS".toByteArray(Charsets.UTF_8))
        val ifacGuard = RnsIfacFrameGuard(
            RnsIfacConfig(
                networkName = IFAC_NET,
                ifacKey = ifacKeyBytes,
                allowPublicRns = false,
                expiresAtMs = Long.MAX_VALUE,
            ),
        )
        val bridgeConnector = RnsAwareBridgeConnector(
            sink = UniFfiRnsNodeBridgeSink { engine.activeNode() },
            ifacGuard = ifacGuard,
            coreEngineProvider = { engine },
            onTopologyMetrics = { tag, metrics ->
                Log.i(TAG, "[$role] topologyMetrics tag=$tag peer=${metrics.peerId} connected=${metrics.connected} rssi=${metrics.rssiDbm} rttMs=${metrics.rttMs}")
            },
        )

        val linkConfig = RnsLinkConfig(
            enableBleFallback = enableBleFallback,
            bleFallbackPolicy = BleFallbackPolicy.WHEN_WIFI_AWARE_UNAVAILABLE,
            ndpRequestTimeoutMs = 12_000L,
            probeIntervalMs = 2_000L,
        )
        val connectivityMgr = requireNotNull(context.getSystemService(android.net.ConnectivityManager::class.java))
        val wifiAdapter = RnsWifiAwareAdapter(
            bridge = bridgeConnector,
            localDeviceToken = localDeviceToken,
            config = linkConfig,
            driver = AndroidWifiAwareDriver(
                context = context,
                serviceName = WIFI_AWARE_SERVICE,
                wifiPassphrase = WIFI_AWARE_PSK,
                localDeviceToken = localDeviceToken,
                preferUdp = false,
            ),
            callbackDriver = AndroidConnectivityDriver(connectivityMgr),
            ifacGuard = ifacGuard,
        )
        val bleAdapter = RnsBleL2capAdapter(
            bridge = bridgeConnector,
            localDeviceToken = localDeviceToken,
            config = linkConfig,
            driver = AndroidBleL2capDriver(
                context = context,
                serviceUuid = UUID.fromString(BLE_SERVICE_UUID),
                localDeviceToken = localDeviceToken,
            ),
            ifacGuard = ifacGuard,
        )
        val awareCoordinator = RnsAwareTransportCoordinator(wifiAdapter, bleAdapter, scope = scope)
        bridgeConnector.bindCoordinator(awareCoordinator)

        val events = CopyOnWriteArrayList<RnsCoreEvent>()
        val config = RnsRuntimeConfig(
            accountKey = "dual-$role",
            eventId = "s21s26-evt",
            channelId = PTT_CHANNEL,
            channelName = "S21-S26 Direct QA",
            actorId = "dual-$role-${Build.MODEL}",
            storageDir = coreDir.absolutePath,
            lxmfDisplayName = "dual-$role-${Build.MODEL}",
            tcpListenAddr = null,
            tcpConnectAddrs = emptyList(),
            udpBindAddr = null,
            udpPeerAddr = null,
            ifac = RnsIfacPolicy(
                networkName = IFAC_NET,
                passphrase = IFAC_PASS,
                ifacSizeBytes = 16,
                requirePrivateIfac = true,
            ),
        )

        try {
            val status = engine.start(config, identitySecret = mySecret) { ev ->
                events.add(ev)
                topologyDiagnostics.onCoreEvent(ev)
            }
            assertTrue("RnsNode failed to start on $role", status.running)
            awareCoordinator.start()
            Log.i(
                TAG,
                "[$role] started model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} myLxmf=${status.lxmfDestinationHashHex} peerLxmf=$peerLxmfDest wifiReach=${wifiAdapter.linkStatus.value.reachability} bleReach=${bleAdapter.linkStatus.value.reachability}",
            )

            // Phase 1: Wait for physical RF link + RNS Announce convergence over Wi-Fi Aware (preferred)
            // or BLE L2CAP fallback. Give Wi-Fi Aware up to 8s to establish NDP before committing to BLE
            // so we don't start a 4.5KB Resource transfer on BLE mid-handoff to Wi-Fi Aware.
            val startMs = System.currentTimeMillis()
            val discDeadline = startMs + discoveryTimeoutMs
            val wifiGraceMs = args.getString("wifiAwareGraceMs")?.toLongOrNull() ?: 8_000L
            var lastLogMs = 0L
            while (System.currentTimeMillis() < discDeadline) {
                val now = System.currentTimeMillis()
                val diag = awareCoordinator.diagnostics.value
                val wifiConnected = wifiAdapter.linkStatus.value.reachability == com.mandonothing.ops.comm.CommReachability.CONNECTED
                val elapsed = now - startMs
                if (diag.activePeers.isNotEmpty() && (wifiConnected || !enableBleFallback || elapsed >= wifiGraceMs)) {
                    engine.announce("dual-$role".toByteArray(Charsets.UTF_8))
                    engine.announceLxmf("dual-$role")
                    engine.announcePtt()
                }
                val known = engine.activeNode()?.knownDestinations().orEmpty()
                if (now - lastLogMs >= 3_000L) {
                    lastLogMs = now
                    Log.i(
                        TAG,
                        "[$role] discovering: radio=${diag.radioReachability} activePeers=${diag.activePeers.map { "${it.radio}:${it.peerId}:rtt=${it.rttMs}" }} known=${known.size} events=${events.size}",
                    )
                }
                if (peerLxmfDest in known && peerPttDest in known && (wifiConnected || !enableBleFallback || elapsed >= wifiGraceMs)) {
                    break
                }
                Thread.sleep(500L)
            }

            val postDiscDiag = awareCoordinator.diagnostics.value
            val postDiscKnown = engine.activeNode()?.knownDestinations().orEmpty()
            Log.i(
                TAG,
                "[$role] discovery end: radio=${postDiscDiag.radioReachability} activePeers=${postDiscDiag.activePeers} known=$postDiscKnown ifacStats=${ifacGuard.stats()}",
            )
            assertTrue(
                "No direct RF peer discovered or RNS announce not received on $role (${Build.MODEL}): radio=${postDiscDiag.radioReachability}, activePeers=${postDiscDiag.activePeers}, known=$postDiscKnown, wifiStatus=${wifiAdapter.linkStatus.value}, bleStatus=${bleAdapter.linkStatus.value}",
                peerLxmfDest in postDiscKnown && peerPttDest in postDiscKnown,
            )

            // Let peer_pubkeys cache the peer's Ed25519 key from the Announce before exchanging signed LXMF
            Thread.sleep(300L)
            val expectedSet = setOf(peerPrimaryDest, peerLxmfDest, peerPttDest)
            assertTrue(
                "Unexpected non-IFAC destinations leaked into knownDestinations: $postDiscKnown",
                postDiscKnown.all { it in expectedSet },
            )

            // Phase 2: Bidirectional encrypted LXMF + 4.5KB Resource + silent 20ms/164B PTT frames & floor
            val pubPayload = ByteArray(4608) { i -> (i % 241).toByte() }
            val subPayload = ByteArray(4608) { i -> ((i * 3) % 239).toByte() }
            val sampleAdpcm = RnsPttAdpcmCodec.encode(generateSilentTestTone())
            assertEquals(164, sampleAdpcm.size)

            if (isPublisher) {
                val opp = engine.sendLxmf(
                    LxmfSendRequest(
                        messageId = "dual-pub-opp",
                        destinationHashHex = peerLxmfDest,
                        title = "DualPubOpp",
                        contentText = "Publisher opportunistic over RF",
                        deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                        idempotencyKey = "dual-pub-opp",
                    ),
                )
                assertEquals(LxmfDeliveryStatus.DELIVERED, opp.initialStatus)

                val res = engine.sendLxmf(
                    LxmfSendRequest(
                        messageId = "dual-pub-res",
                        destinationHashHex = peerLxmfDest,
                        title = "DualPubRes",
                        contentText = "Publisher 4.5KB Resource over RF",
                        attachmentKind = LxmfAttachmentKind.BINARY_RESOURCE,
                        attachmentFilename = "dual_pub.bin",
                        attachmentBytes = pubPayload,
                        deliveryMode = LxmfDeliveryMode.DIRECT_LINK,
                        idempotencyKey = "dual-pub-res",
                    ),
                )
                assertEquals(LxmfDeliveryStatus.DELIVERED, res.initialStatus)

                val pttStartNs = System.nanoTime()
                val grant = engine.requestPttFloor(peerPttDest, PTT_CHANNEL, "dual-ptt-1", priority = 1)
                assertNotNull(grant)
                for (seq in 0L until 5L) {
                    assertTrue(
                        engine.sendPttFrame(
                            destinationHashHex = peerPttDest,
                            channelId = PTT_CHANNEL,
                            sessionId = "dual-ptt-1",
                            seq = seq,
                            codec = RnsPttAdpcmCodec.CODEC_ID,
                            durationMs = RnsPttAdpcmCodec.FRAME_DURATION_MS,
                            audioBytes = sampleAdpcm,
                        ),
                    )
                }
                engine.releasePttFloor(peerPttDest, PTT_CHANNEL, "dual-ptt-1")
                val pttTxMs = (System.nanoTime() - pttStartNs) / 1_000_000L
                Log.i(TAG, "[publisher] sent Opp+Res(4608B)+PTT(5x164B) pttTxMs=$pttTxMs")

                waitUntil(exchangeTimeoutMs, "Publisher timed out waiting for Subscriber replies") {
                    val gotSubOpp = events.any {
                        it is RnsCoreEvent.LxmfReceived &&
                            it.title == "DualSubOpp" &&
                            it.signatureValid
                    }
                    val gotSubRes = events.any {
                        it is RnsCoreEvent.LxmfReceived &&
                            it.title == "DualSubRes" &&
                            it.signatureValid &&
                            it.attachmentBytes != null &&
                            it.attachmentBytes.contentEquals(subPayload)
                    }
                    val gotSubPtt = events.count {
                        it is RnsCoreEvent.PttFrameReceived &&
                            it.channelId == PTT_CHANNEL &&
                            it.audioBytes.size == 164
                    } >= 3
                    val gotSubFloor = events.any {
                        it is RnsCoreEvent.PttFloorEvent && it.channelId == PTT_CHANNEL
                    }
                    gotSubOpp && gotSubRes && gotSubPtt && gotSubFloor
                }

                engine.sendLxmf(
                    LxmfSendRequest(
                        messageId = "dual-done-ack",
                        destinationHashHex = peerLxmfDest,
                        title = "DualDoneAck",
                        contentText = "Publisher verified all replies",
                        deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                        idempotencyKey = "dual-done-ack",
                    ),
                )
                Thread.sleep(1_500L)
            } else {
                waitUntil(exchangeTimeoutMs, "Subscriber timed out waiting for Publisher Opp+Res+PTT") {
                    val gotPubOpp = events.any {
                        it is RnsCoreEvent.LxmfReceived &&
                            it.title == "DualPubOpp" &&
                            it.signatureValid
                    }
                    val gotPubRes = events.any {
                        it is RnsCoreEvent.LxmfReceived &&
                            it.title == "DualPubRes" &&
                            it.signatureValid &&
                            it.attachmentBytes != null &&
                            it.attachmentBytes.contentEquals(pubPayload)
                    }
                    val gotPubPtt = events.count {
                        it is RnsCoreEvent.PttFrameReceived &&
                            it.channelId == PTT_CHANNEL &&
                            it.audioBytes.size == 164
                    } >= 3
                    val gotPubFloor = events.any {
                        it is RnsCoreEvent.PttFloorEvent && it.channelId == PTT_CHANNEL
                    }
                    gotPubOpp && gotPubRes && gotPubPtt && gotPubFloor
                }

                val subOpp = engine.sendLxmf(
                    LxmfSendRequest(
                        messageId = "dual-sub-opp",
                        destinationHashHex = peerLxmfDest,
                        title = "DualSubOpp",
                        contentText = "Subscriber opportunistic over RF",
                        deliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
                        idempotencyKey = "dual-sub-opp",
                    ),
                )
                assertEquals(LxmfDeliveryStatus.DELIVERED, subOpp.initialStatus)

                val subRes = engine.sendLxmf(
                    LxmfSendRequest(
                        messageId = "dual-sub-res",
                        destinationHashHex = peerLxmfDest,
                        title = "DualSubRes",
                        contentText = "Subscriber 4.5KB Resource over RF",
                        attachmentKind = LxmfAttachmentKind.BINARY_RESOURCE,
                        attachmentFilename = "dual_sub.bin",
                        attachmentBytes = subPayload,
                        deliveryMode = LxmfDeliveryMode.DIRECT_LINK,
                        idempotencyKey = "dual-sub-res",
                    ),
                )
                assertEquals(LxmfDeliveryStatus.DELIVERED, subRes.initialStatus)

                engine.requestPttFloor(peerPttDest, PTT_CHANNEL, "dual-ptt-2", priority = 1)
                for (seq in 0L until 5L) {
                    assertTrue(
                        engine.sendPttFrame(
                            destinationHashHex = peerPttDest,
                            channelId = PTT_CHANNEL,
                            sessionId = "dual-ptt-2",
                            seq = seq,
                            codec = RnsPttAdpcmCodec.CODEC_ID,
                            durationMs = RnsPttAdpcmCodec.FRAME_DURATION_MS,
                            audioBytes = sampleAdpcm,
                        ),
                    )
                }
                engine.releasePttFloor(peerPttDest, PTT_CHANNEL, "dual-ptt-2")

                // Keep AudioManager silent (do not open AudioTrack) and wait for Publisher DualDoneAck
                waitUntil(exchangeTimeoutMs, "Subscriber timed out waiting for DualDoneAck") {
                    events.any {
                        it is RnsCoreEvent.LxmfReceived &&
                            it.title == "DualDoneAck" &&
                            it.signatureValid
                    }
                }
            }

            val finalDiag = awareCoordinator.diagnostics.value
            val ifacStats = ifacGuard.stats()
            val multiHop = engine.multiHopTelemetry()
            assertEquals(0L, ifacStats.droppedPublicConfig)
            assertEquals(0L, ifacStats.droppedWrongIfac)
            Log.i(
                TAG,
                "[$role] PASS activePeers=${finalDiag.activePeers.map { "${it.radio}:${it.peerId}:rtt=${it.rttMs}:bw=${it.estimatedBandwidthBps}" }} linkRttMs=${multiHop.lastLinkRttMs} ifacSealed=${ifacStats.framesSealed} ifacAccepted=${ifacStats.framesAccepted}",
            )
            // Keep volume untouched (silent test verified via no AudioTrack output)
            assertFalse(AudioManager::class.java.name.isBlank())
        } finally {
            awareCoordinator.close()
            bridgeConnector.close()
            engine.stop()
            scope.cancel()
            coreDir.deleteRecursively()
            val leaks = awareCoordinator.diagnostics.value.leakCounters
            assertEquals(0, leaks.activeNetworkCallbacks)
            assertEquals(0, leaks.activeSockets)
        }
    }

    private fun deterministicSecret(seed: String): ByteArray {
        val dig1 = MessageDigest.getInstance("SHA-256").digest("$seed:x25519".toByteArray(Charsets.UTF_8))
        val dig2 = MessageDigest.getInstance("SHA-256").digest("$seed:ed25519".toByteArray(Charsets.UTF_8))
        return dig1 + dig2
    }

    private fun generateSilentTestTone(): ShortArray =
        ShortArray(RnsPttAdpcmCodec.FRAME_SAMPLES) { idx ->
            (sin(2.0 * PI * 440.0 * idx / RnsPttAdpcmCodec.SAMPLE_RATE_HZ) * 10_000.0).roundToInt().toShort()
        }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100L)
        }
        assertTrue(message, condition())
    }

    private companion object {
        const val TAG = "DualDeviceRnsTest"
        const val IFAC_NET = "protocolx.s21s26.qa"
        const val IFAC_PASS = "s21s26-qa-secret-v1"
        const val WIFI_AWARE_SERVICE = "pxs21s26"
        const val WIFI_AWARE_PSK = "s21s26-aware-psk"
        const val BLE_SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb"
        const val PTT_CHANNEL = "dual-ptt-ch"
    }
}
