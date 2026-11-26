package com.mandonothing.ops.comm

import com.mandonothing.ops.NearbyDelivery
import com.mandonothing.ops.NearbyDiagnosticSnapshot
import com.mandonothing.ops.NearbyMessage
import com.mandonothing.ops.NearbyPrivateContent
import com.mandonothing.ops.NearbyRadio
import com.mandonothing.ops.PttPcmSnapshot
import com.mandonothing.ops.VideoState
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.VoiceCue
import com.mandonothing.ops.VoiceSpeaker
import com.mandonothing.ops.VoiceState
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
    fun nearbyMapperMapsNetworkStateAndMessageEnvelopesWithoutRegression() {
        val network = NearbyCommMapper.toNetworkState(
            radios = mapOf(NearbyRadio.WIFI to "已连接", NearbyRadio.BLE to "正在发现"),
            diagnostics = NearbyDiagnosticSnapshot(
                wifiPeers = 2,
                bluetoothPeers = 0,
                reachablePeers = 2,
                lastActivityMillis = 1_000L,
                lastDeliveryMillis = 900L,
                lastDeliveryRoute = NearbyRadio.WIFI,
                liveVoicePeers = 1,
            ),
            credential = null,
            activeScopeKey = "scope-1",
            hasInternet = true,
            validatedInternet = false,
            serverReachable = false,
        )
        assertEquals(CommReachability.CONNECTED, network.links[CommTransportKind.WIFI_AWARE]?.reachability)
        assertEquals(CommReachability.DISCOVERING, network.links[CommTransportKind.BLE_L2CAP]?.reachability)
        assertEquals(CommReachability.DEGRADED, network.links[CommTransportKind.INTERNET_HTTP]?.reachability)
        assertEquals(CommTransportKind.WIFI_AWARE, network.bestRoute)
        assertEquals(CommTransportKind.WIFI_AWARE, network.lastDeliveryKind)
        assertTrue(network.hasAnyPeerLink)

        assertEquals(CommDeliveryState.QUEUED, NearbyCommMapper.toDeliveryState(NearbyDelivery.WAITING, false))
        assertEquals(
            CommDeliveryState.AWAITING_RECEIPT,
            NearbyCommMapper.toDeliveryState(NearbyDelivery.AWAITING_RECEIPT, false),
        )
        assertEquals(CommDeliveryState.DELIVERED, NearbyCommMapper.toDeliveryState(NearbyDelivery.DELIVERED, false))
        assertEquals(CommDeliveryState.SYNCED, NearbyCommMapper.toDeliveryState(NearbyDelivery.DELIVERED, true))
        assertEquals(CommDeliveryState.EXPIRED, NearbyCommMapper.toDeliveryState(NearbyDelivery.EXPIRED, false))

        val msg = NearbyMessage(
            id = "msg-1",
            sender = "abcd1234abcd1234abcd1234abcd1234",
            name = "张三",
            text = "加密定向消息",
            audio = "",
            time = 42_000L,
            assistance = false,
            publicKey = "pk",
            signature = "sig",
            certificate = "cert",
            received = true,
            synced = false,
            attemptedVia = NearbyRadio.BLE,
            deliveredVia = NearbyRadio.WIFI,
            confirmationRequired = true,
            recipient = "actor-2",
            sealedPayload = "AQID",
            actor = "actor-1",
        )
        val receipt = NearbyMessage(
            id = "rcpt-1",
            sender = "11112222333344441111222233334444",
            name = "李四",
            text = "队员确认",
            audio = "",
            time = 43_000L,
            assistance = false,
            publicKey = "pk2",
            signature = "sig2",
            certificate = "cert2",
            confirmationFor = "msg-1",
            recipient = "actor-1",
            actor = "actor-2",
        )
        val envelope = NearbyCommMapper.toEnvelope(
            message = msg,
            scope = sampleScope,
            localSender = msg.sender,
            localActor = "actor-1",
            receipts = listOf(receipt),
            privateContent = NearbyPrivateContent("改在 B2 门", byteArrayOf(1, 2, 3)),
        )
        assertEquals(CommEnvelopeKind.PRIVATE_ENCRYPTED, envelope.kind)
        assertTrue(envelope.isMine)
        assertTrue(envelope.isPrivate)
        assertEquals("改在 B2 门", envelope.text)
        assertEquals("AQID", envelope.photoBase64)
        assertEquals(CommDeliveryState.DELIVERED, envelope.deliveryState)
        assertEquals(CommTransportKind.WIFI_AWARE, envelope.route.deliveredTransport)
        assertEquals(listOf("李四"), envelope.readByNames)
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
}
