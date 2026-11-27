package com.mandonothing.ops.comm

import com.mandonothing.ops.FieldOfflineStore
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.comm.aware.RnsBridgeLinkMetrics
import com.mandonothing.ops.comm.aware.RnsCustomBridgeSink
import com.mandonothing.ops.comm.aware.RnsCustomInterfaceKind
import com.mandonothing.ops.comm.aware.RnsCustomInterfaceOutboundHandler
import com.mandonothing.ops.comm.aware.RnsIfacDerivation
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.mandonothing.ops.comm.ptt.RnsPttAudioIo
import com.mandonothing.ops.comm.ptt.RnsPttFloorAction
import com.mandonothing.ops.comm.ptt.RnsPttHandoffCoordinator
import com.mandonothing.ops.comm.ptt.RnsPttSessionTransport
import com.mandonothing.ops.comm.rns.LxmfDeliveryStatus
import com.mandonothing.ops.comm.rns.LxmfSendOutcome
import com.mandonothing.ops.comm.rns.LxmfSendRequest
import com.mandonothing.ops.comm.rns.Rns409Resolution
import com.mandonothing.ops.comm.rns.RnsAwareIntegrationController
import com.mandonothing.ops.comm.rns.RnsCoreEngine
import com.mandonothing.ops.comm.rns.RnsCoreEvent
import com.mandonothing.ops.comm.rns.RnsCoreMultiHopTelemetry
import com.mandonothing.ops.comm.rns.RnsCorePttBridge
import com.mandonothing.ops.comm.rns.RnsCoreRouteSnapshot
import com.mandonothing.ops.comm.rns.RnsCoreStatus
import com.mandonothing.ops.comm.rns.RnsIfacLease
import com.mandonothing.ops.comm.rns.RnsIfacPolicy
import com.mandonothing.ops.comm.rns.RnsLocalStore
import com.mandonothing.ops.comm.rns.RnsOfflineSyncReconciler
import com.mandonothing.ops.comm.rns.RnsPayloadCipher
import com.mandonothing.ops.comm.rns.RnsRuntimeConfig
import com.mandonothing.ops.comm.rns.RnsRuntimeCoordinator
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RnsFullIntegrationTest {
    private val passThroughCipher = object : RnsPayloadCipher {
        override fun encrypt(data: ByteArray, context: String): ByteArray = data
        override fun decrypt(data: ByteArray, context: String): ByteArray = data
    }

    private val scope = CommScope(
        eventId = "evt-golf",
        channelId = "ch-alpha",
        channelName = "调度一组",
        accountKey = "acc-1",
        actorId = "person-1",
    )

    private class IntegratedFakeCoreEngine : RnsCoreEngine {
        override val isAvailable: Boolean = true
        override val unavailableReason: String? = null
        var running = false
        var listener: ((RnsCoreEvent) -> Unit)? = null
        val rotatedPolicies = CopyOnWriteArrayList<RnsIfacPolicy>()
        val revokedPeersList = CopyOnWriteArrayList<String>()
        val sentPttFrames = CopyOnWriteArrayList<ByteArray>()
        var telemetry = RnsCoreMultiHopTelemetry(
            enableTransportRelay = true,
            activeLinksCount = 2,
            lastLinkRttMs = 42L,
            routes = listOf(
                RnsCoreRouteSnapshot(
                    destinationHashHex = "bb".repeat(16),
                    hops = 3,
                    nextHopHex = "aa".repeat(16),
                    learnedAtMs = 1_000L,
                    lastActivityAtMs = 2_000L,
                    expiresAtMs = 60_000L,
                    interfaceTag = "wifi-aware",
                ),
            ),
        )

        override fun start(config: RnsRuntimeConfig, identitySecret: ByteArray?, listener: (RnsCoreEvent) -> Unit): RnsCoreStatus {
            this.listener = listener
            running = true
            return status()
        }

        override fun stop() {
            running = false
        }

        override fun identitySecret(): ByteArray = ByteArray(64) { 3 }
        override fun announce(appData: ByteArray) {}
        override fun sendLxmf(request: LxmfSendRequest): LxmfSendOutcome =
            LxmfSendOutcome(request.messageId, "hash-${request.messageId}", LxmfDeliveryStatus.SENT)

        override fun hasDirectLink(destinationHashHex: String): Boolean = true
        override fun rotateIfacCredentials(policy: RnsIfacPolicy) {
            rotatedPolicies += policy
        }

        override fun revokePeer(identityOrDestHashHex: String, reason: String?) {
            revokedPeersList += identityOrDestHashHex
        }

        override fun revokedPeers(): List<String> = revokedPeersList.toList()
        override fun multiHopTelemetry(): RnsCoreMultiHopTelemetry = telemetry
        override fun pttDestinationHashHex(): String = "cc".repeat(16)
        override fun sendPttFrame(
            destinationHashHex: String,
            channelId: String,
            sessionId: String,
            seq: Long,
            codec: String,
            durationMs: Int,
            audioBytes: ByteArray,
        ): Boolean {
            sentPttFrames += audioBytes
            return true
        }

        override fun requestPttFloor(
            destinationHashHex: String,
            channelId: String,
            sessionId: String,
            priority: Int,
        ): RnsCoreEvent.PttFloorEvent = RnsCoreEvent.PttFloorEvent(
            channelId = channelId,
            sessionId = sessionId,
            actorHashHex = "11".repeat(16),
            action = "GRANT",
            priority = priority,
            timestampMs = 2_000L,
            holderHashHex = "11".repeat(16),
        )

        override fun releasePttFloor(
            destinationHashHex: String,
            channelId: String,
            sessionId: String,
        ): RnsCoreEvent.PttFloorEvent = RnsCoreEvent.PttFloorEvent(
            channelId = channelId,
            sessionId = sessionId,
            actorHashHex = "11".repeat(16),
            action = "RELEASE",
            priority = 1,
            timestampMs = 2_100L,
        )

        override fun status(): RnsCoreStatus = RnsCoreStatus(
            running = running,
            identityHashHex = "id-1",
            destinationHashHex = "11".repeat(16),
            lxmfDestinationHashHex = "22".repeat(16),
            knownPeersCount = 1,
            activeLinksCount = 2,
        )
    }

    private class FakeBridgeSink : RnsCustomBridgeSink {
        val attachedTags = CopyOnWriteArrayList<String>()
        val metricsUpdated = CopyOnWriteArrayList<RnsBridgeLinkMetrics>()

        override fun attachInterface(
            interfaceTag: String,
            kind: RnsCustomInterfaceKind,
            hwMtu: Int,
            bitrateBps: Long,
            outboundHandler: RnsCustomInterfaceOutboundHandler,
        ) {
            attachedTags += interfaceTag
        }

        override fun detachInterface(interfaceTag: String): Boolean = attachedTags.remove(interfaceTag)
        override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) {}
        override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) {
            metricsUpdated += metrics
        }

        override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> = metricsUpdated.toList()
    }

    private class NoopAudioIo : RnsPttAudioIo {
        override fun startCapture(sampleRateHz: Int, frameSamples: Int, onPcmFrame: (ShortArray) -> Unit): Boolean = true
        override fun stopCapture() {}
        override fun startPlayback(sampleRateHz: Int) {}
        override fun playPcm(samples: ShortArray) {}
        override fun stopPlayback() {}
        override fun setReceiveMuted(muted: Boolean) {}
        override fun audioOutputs(): List<AudioDevice> = emptyList()
        override fun currentOutput(): AudioDevice? = null
        override fun selectOutput(device: AudioDevice) {}
        override fun close() {}
    }

    @Test
    fun awareBridgeIfacTopologyAndPttCoordinateThroughRuntimeController() = runTest(UnconfinedTestDispatcher()) {
        val dir = Files.createTempDirectory("rns-full-").toFile()
        val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val now = System.currentTimeMillis()
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = IntegratedFakeCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine, clockMs = { now })
            val topology = RnsTopologyDiagnostics(clockMs = { now })
            val sink = FakeBridgeSink()
            val initialIfac = RnsIfacDerivation.deriveConfig(
                "evt-golf",
                "ch-alpha",
                "secret-1".toByteArray(),
                expiresAtMs = now + 3_600_000L,
            )
            val integration = RnsAwareIntegrationController(
                coordinator = coordinator,
                engine = engine,
                topologyDiagnostics = topology,
                sink = sink,
                initialIfacConfig = initialIfac,
                scope = testScope,
            )

            assertTrue(
                coordinator.start(
                    RnsRuntimeConfig(
                        accountKey = "acc-1",
                        eventId = "evt-golf",
                        channelId = "ch-alpha",
                        channelName = "调度一组",
                        actorId = "person-1",
                    ),
                ),
            )

            // RNS-04 bridge attaches custom Wi-Fi Aware interface and propagates metrics into RNS-06 topology.
            val handler = object : RnsCustomInterfaceOutboundHandler {
                override fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean = true
                override fun onInterfaceDetached(interfaceTag: String) {}
            }
            integration.bridgeConnector.attachInterface("wifi-aware", RnsCustomInterfaceKind.WIFI_AWARE, 8192, 25_000_000L, handler)
            assertTrue("wifi-aware" in sink.attachedTags)
            integration.bridgeConnector.updateLinkMetrics(
                "wifi-aware",
                RnsBridgeLinkMetrics(peerId = "aa".repeat(16), connected = true, rssiDbm = -58, rttMs = 42L, estimatedBandwidthBps = 20_000_000L),
            )

            engine.telemetry = engine.telemetry.copy(
                routes = engine.telemetry.routes.map { it.copy(expiresAtMs = now + 3_600_000L) },
            )
            // RNS-06 telemetry ingestion + CoreEvent announce populates 3-hop topology.
            coordinator.handleCoreEvent(RnsCoreEvent.AnnounceHeard("bb".repeat(16), "id-b", "ch-alpha".toByteArray(), hops = 3))
            integration.pollMultiHopTelemetry()
            val topoSnap = topology.snapshot.value
            assertEquals(3, topoSnap.maxHopCount)
            assertEquals(42L, topoSnap.bestLinkRttMs)
            assertTrue(topoSnap.reachableDestinations >= 1)

            // RNS-05 IFAC rotation + revocation syncs across coordinator -> Rust engine -> Aware IFAC guard.
            coordinator.applyIfacLease(
                RnsIfacLease(
                    eventId = "evt-golf",
                    channelId = "ch-alpha",
                    networkName = "px.evt-golf.ch-alpha",
                    passphrase = "rotated-passphrase",
                    expiresAtMs = now + 7_200_000L,
                    revokedDeviceHashes = setOf("dd".repeat(16)),
                ),
            )
            assertEquals(1, engine.rotatedPolicies.size)
            assertEquals("rotated-passphrase", integration.ifacGuard.currentConfig().ifacKey.decodeToString())
            coordinator.revokePeer("evt-golf", deviceOrIdentityHash = "ee".repeat(16), actorId = "peer-evil")
            assertFalse(integration.ifacGuard.isPeerAllowed(peerId = "peer-evil", destinationHashHex = "ee".repeat(16), nowMs = now))

            // RNS-10 PTT bridge requests floor, encodes ADPCM frame, and sends to announced peers.
            val pttBridge = RnsCorePttBridge(coordinator, engine)
            val clips = CopyOnWriteArrayList<VoiceCapturedClip>()
            val rnsPtt = RnsPttSessionTransport(
                channelId = "ch-alpha",
                localDisplayName = "1号车",
                bridge = pttBridge,
                audioIo = NoopAudioIo(),
                onSpeaker = {},
                onOutput = {},
                onClip = { clips += it },
                onCue = {},
                onState = { _, _ -> },
            )
            rnsPtt.connect()
            assertTrue(rnsPtt.startPress())
            repeat(14) {
                rnsPtt.pushLocalPcmFrame(
                    ShortArray(com.mandonothing.ops.comm.ptt.RnsPttAdpcmCodec.FRAME_SAMPLES) { idx ->
                        ((idx * 31) % 3000).toShort()
                    },
                )
            }
            rnsPtt.stopPress(cancel = false)
            assertEquals(15, engine.sentPttFrames.size) // 14 audio frames + 1 endOfBurst frame
            assertEquals(1, clips.size)

            rnsPtt.close()
            integration.close()
        } finally {
            testScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultCommGatewayAutoFailoverAndIdempotentDispatchCoordinateWithPttHandoff() {
        val gateway = DefaultCommGateway(context = null, scope = CoroutineScope(SupervisorJob()), observeNearby = false)
        val rnsSendCalls = AtomicInteger(0)

        gateway.setServerReachable(true)
        gateway.registerAdapter(
            object : CommTransportAdapter {
                override val kind = CommTransportKind.RNS_MESH
                override val linkStatus: StateFlow<CommLinkStatus> = MutableStateFlow(
                    CommLinkStatus(CommTransportKind.RNS_MESH, CommReachability.CONNECTED, "已连接", peerCount = 2),
                )
                override fun supports(outbound: CommOutboundMessage): Boolean = true
                override suspend fun send(outbound: CommOutboundMessage): CommSendResult {
                    rnsSendCalls.incrementAndGet()
                    return CommSendResult.Accepted(outbound.id, CommTransportKind.RNS_MESH)
                }
            },
        )
        gateway.selectScope(scope)

        val fakeLiveKit = object : PttSessionTransport {
            override val mode = PttTransportMode.LIVEKIT
            override val receiveMuted = false
            var connected = false
            override fun connect() { connected = true }
            override fun startPress(): Boolean = true
            override fun stopPress(cancel: Boolean) {}
            override fun setReceiveMuted(muted: Boolean) {}
            override fun audioOutputs(): List<AudioDevice> = emptyList()
            override fun currentOutput(): AudioDevice? = null
            override fun selectOutput(device: AudioDevice) {}
            override fun close() { connected = false }
        }
        val fakeRnsPtt = object : PttSessionTransport {
            override val mode = PttTransportMode.NEARBY_LIVE
            override val receiveMuted = false
            var connected = false
            override fun connect() { connected = true }
            override fun startPress(): Boolean = true
            override fun stopPress(cancel: Boolean) {}
            override fun setReceiveMuted(muted: Boolean) {}
            override fun audioOutputs(): List<AudioDevice> = emptyList()
            override fun currentOutput(): AudioDevice? = null
            override fun selectOutput(device: AudioDevice) {}
            override fun close() { connected = false }
        }
        val handoff = RnsPttHandoffCoordinator(
            liveKitFactory = { fakeLiveKit },
            rnsPttFactory = { fakeRnsPtt },
        )

        // Online WAN prefers LiveKit PTT; WAN drop triggers <5s handoff to RNS PTT and RNS_MESH dispatch.
        val t1 = handoff.reconcile(gateway.rnsNetworkManager().state.value)
        assertEquals(PttTransportMode.LIVEKIT, t1.mode)

        gateway.setServerReachable(false)
        val netState = gateway.rnsNetworkManager().state.value
        assertFalse(netState.shouldUseLiveKitPtt)
        assertTrue(netState.lastSwitchDurationMs < 5_000L)
        val t2 = handoff.reconcile(netState)
        assertEquals(PttTransportMode.NEARBY_LIVE, t2.mode)

        val outbound = CommOutboundMessage(id = "e2e-msg-1", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "车队已发车", idempotencyKey = "idem-e2e-1")
        assertTrue(gateway.send(outbound) is CommSendResult.Accepted)
        assertEquals(1, rnsSendCalls.get())

        // Verify RNS-09 reconciler agrees on 409 state already achieved.
        val ref = JSONObject().put("ontology_id", "golf.v1").put("object_type", "TransferLeg").put("id", "t-1")
        val queued = FieldOfflineStore.Queued("op-e2e", "person-1", "https://ops", "evt-golf", ref, 1, "acknowledge", JSONObject(), clientSeq = 1L)
        val res = RnsOfflineSyncReconciler.classify409(
            queued,
            JSONObject().put("current_version", 2).put("current_status", "accepted").put("current_progress", JSONArray()),
        )
        assertTrue(res is Rns409Resolution.AlreadyAchieved)
        handoff.close()
    }
}
