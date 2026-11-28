package com.mandonothing.ops.comm

import com.mandonothing.ops.PttPcmSnapshot
import com.mandonothing.ops.VideoState
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.VoiceCue
import com.mandonothing.ops.VoiceSpeaker
import com.mandonothing.ops.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommInterfaceTest {
    private val sampleScope = CommScope(
        eventId = "evt-golf-01",
        channelId = "ch-alpha",
        channelName = "调度一组",
        channelKind = "group",
        accountKey = "acc-key",
        actorId = "actor-1",
    )

    @Test
    fun routerExcludesBleForBulkMediaAndSelectsConnectedBestRoute() {
        val state = CommNetworkState(
            hasInternet = false,
            validatedInternet = false,
            serverReachable = false,
            links = mapOf(
                CommTransportKind.WIFI_AWARE to CommLinkStatus(
                    kind = CommTransportKind.WIFI_AWARE,
                    reachability = CommReachability.CONNECTED,
                    statusLabel = "已连接",
                    peerCount = 2,
                ),
                CommTransportKind.BLE_L2CAP to CommLinkStatus(
                    kind = CommTransportKind.BLE_L2CAP,
                    reachability = CommReachability.CONNECTED,
                    statusLabel = "已连接",
                    peerCount = 1,
                ),
            ),
        )
        val adapters = setOf(
            CommTransportKind.RNS_MESH,
            CommTransportKind.WIFI_AWARE,
            CommTransportKind.BLE_L2CAP,
            CommTransportKind.INTERNET_HTTP,
        )

        val textCandidates = CommRouterPolicy.candidateTransports(
            state = state,
            outbound = CommOutboundMessage(scope = sampleScope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "到达"),
            availableAdapters = adapters,
        )
        assertEquals(
            listOf(CommTransportKind.RNS_MESH, CommTransportKind.WIFI_AWARE, CommTransportKind.BLE_L2CAP),
            textCandidates,
        )

        val voiceCandidates = CommRouterPolicy.candidateTransports(
            state = state,
            outbound = CommOutboundMessage(
                scope = sampleScope,
                kind = CommEnvelopeKind.VOICE_NOTE,
                audioBytes = ByteArray(1024),
            ),
            availableAdapters = adapters,
        )
        assertEquals(listOf(CommTransportKind.RNS_MESH, CommTransportKind.WIFI_AWARE), voiceCandidates)
        assertFalse(CommTransportKind.BLE_L2CAP in voiceCandidates)

        val largePhotoCandidates = CommRouterPolicy.candidateTransports(
            state = state,
            outbound = CommOutboundMessage(
                scope = sampleScope,
                kind = CommEnvelopeKind.PRIVATE_ENCRYPTED,
                photoBytes = ByteArray(4096),
                recipientActor = "actor-2",
            ),
            availableAdapters = adapters,
        )
        assertEquals(listOf(CommTransportKind.RNS_MESH, CommTransportKind.WIFI_AWARE), largePhotoCandidates)
    }

    @Test
    fun routerBestRoutePrefersInternetThenRnsThenWifiThenBleWithDiscoveringFallback() {
        val wifiDiscovering = mapOf(
            CommTransportKind.WIFI_AWARE to CommLinkStatus(
                CommTransportKind.WIFI_AWARE, CommReachability.DISCOVERING, "正在发现",
            ),
            CommTransportKind.BLE_L2CAP to CommLinkStatus(
                CommTransportKind.BLE_L2CAP, CommReachability.UNAVAILABLE, "不可用",
            ),
        )
        assertEquals(CommTransportKind.WIFI_AWARE, CommRouterPolicy.selectBestRoute(wifiDiscovering, false))
        assertEquals(CommTransportKind.INTERNET_HTTP, CommRouterPolicy.selectBestRoute(wifiDiscovering, true))

        val rnsConnected = wifiDiscovering + (
            CommTransportKind.RNS_MESH to CommLinkStatus(
                CommTransportKind.RNS_MESH, CommReachability.CONNECTED, "已连接", peerCount = 3,
            )
        )
        assertEquals(CommTransportKind.RNS_MESH, CommRouterPolicy.selectBestRoute(rnsConnected, false))
    }

    @Test
    fun liveKitMediaMapperTranslatesPhasesCuesSpeakersAndVideoState() {
        VoiceState.entries.forEach { voiceState ->
            val mapped = LiveKitMediaMapper.toPttPhase(voiceState)
            assertEquals(voiceState, LiveKitMediaMapper.toVoiceState(mapped))
        }
        assertEquals(PttCue.ENDED, LiveKitMediaMapper.toPttCue(VoiceCue.ENDED))
        assertEquals(PttCue.BUSY, LiveKitMediaMapper.toPttCue(VoiceCue.BUSY))
        assertNull(LiveKitMediaMapper.toPttSpeaker(null))
        assertEquals(PttSpeaker("sid-9", "01号车"), LiveKitMediaMapper.toPttSpeaker(VoiceSpeaker("sid-9", "01号车")))

        val clip = LiveKitMediaMapper.toPttClip(
            VoiceCapturedClip("sid-1", "调度", false, PttPcmSnapshot(ByteArray(8000), 16000, 1)),
            atIso = "2026-11-26T05:00:00Z",
            durationMs = 250,
        )
        assertEquals("sid-1", clip.trackSid)
        assertEquals("调度", clip.speaker)
        assertEquals(250, clip.durationMs)

        val video = LiveKitMediaMapper.toVideoChannelState(
            VideoState(text = "正在发送现场画面", ready = true, cameraRequested = true, sharing = true, showingTrack = true),
        )
        assertTrue(video.ready && video.sharing && video.showingTrack)
        assertFalse(video.failed)
    }

    @Test
    fun gatewayDispatchesRegisteredCustomAdaptersAndFailsOverOnRetryableRejections() {
        val gateway = DefaultCommGateway(context = null, scope = CoroutineScope(SupervisorJob()), observeNearby = false)
        val attempted = mutableListOf<CommTransportKind>()

        val rnsAdapter = FakeAdapter(
            kind = CommTransportKind.RNS_MESH,
            reachability = CommReachability.CONNECTED,
            handler = { outbound ->
                attempted += CommTransportKind.RNS_MESH
                when (outbound.text) {
                    "fail-retryable" -> CommSendResult.Rejected("rns temporary busy", retryable = true)
                    "fail-fatal" -> CommSendResult.Rejected("payload invalid", retryable = false)
                    else -> CommSendResult.Accepted(outbound.id, CommTransportKind.RNS_MESH)
                }
            },
        )
        val wifiAdapter = FakeAdapter(
            kind = CommTransportKind.WIFI_AWARE,
            reachability = CommReachability.CONNECTED,
            handler = { outbound ->
                attempted += CommTransportKind.WIFI_AWARE
                CommSendResult.Accepted(outbound.id, CommTransportKind.WIFI_AWARE)
            },
        )
        gateway.registerAdapter(rnsAdapter)
        gateway.registerAdapter(wifiAdapter)

        // 1. Custom RNS_MESH adapter handles outbound directly
        val directResult = gateway.send(
            CommOutboundMessage(id = "m-1", scope = sampleScope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "ok"),
        )
        assertEquals(CommSendResult.Accepted("m-1", CommTransportKind.RNS_MESH), directResult)
        assertEquals(listOf(CommTransportKind.RNS_MESH), attempted)

        // 2. Retryable rejection on RNS_MESH fails over to WIFI_AWARE
        attempted.clear()
        val failoverResult = gateway.send(
            CommOutboundMessage(
                id = "m-2",
                scope = sampleScope,
                kind = CommEnvelopeKind.CHANNEL_TEXT,
                text = "fail-retryable",
            ),
        )
        assertEquals(CommSendResult.Accepted("m-2", CommTransportKind.WIFI_AWARE), failoverResult)
        assertEquals(listOf(CommTransportKind.RNS_MESH, CommTransportKind.WIFI_AWARE), attempted)

        // 3. Non-retryable rejection short-circuits without hitting WIFI_AWARE
        attempted.clear()
        val fatalResult = gateway.send(
            CommOutboundMessage(
                id = "m-3",
                scope = sampleScope,
                kind = CommEnvelopeKind.CHANNEL_TEXT,
                text = "fail-fatal",
            ),
        )
        assertEquals(CommSendResult.Rejected("payload invalid", retryable = false), fatalResult)
        assertEquals(listOf(CommTransportKind.RNS_MESH), attempted)
    }

    private class FakeAdapter(
        override val kind: CommTransportKind,
        reachability: CommReachability,
        private val handler: suspend (CommOutboundMessage) -> CommSendResult,
    ) : CommTransportAdapter {
        override val linkStatus: StateFlow<CommLinkStatus> = MutableStateFlow(
            CommLinkStatus(kind = kind, reachability = reachability, statusLabel = reachability.name, peerCount = 1),
        )

        override fun supports(outbound: CommOutboundMessage): Boolean = true

        override suspend fun send(outbound: CommOutboundMessage): CommSendResult = handler(outbound)
    }
}
