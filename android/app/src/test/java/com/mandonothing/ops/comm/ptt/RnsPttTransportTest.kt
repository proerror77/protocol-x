package com.mandonothing.ops.comm.ptt

import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.comm.PttCue
import com.mandonothing.ops.comm.PttPhase
import com.mandonothing.ops.comm.PttSessionTransport
import com.mandonothing.ops.comm.PttSpeaker
import com.mandonothing.ops.comm.PttTransportMode
import com.mandonothing.ops.comm.network.RnsManagedRoute
import com.mandonothing.ops.comm.network.RnsNetworkInputs
import com.mandonothing.ops.comm.network.RnsNetworkManager
import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommTransportKind
import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sin

class RnsPttTransportTest {

    private class FakeAudioIo : RnsPttAudioIo {
        var captureStarted = false
        var playbackStarted = false
        var muted = false
        val playedFrames = CopyOnWriteArrayList<ShortArray>()
        var captureCallback: ((ShortArray) -> Unit)? = null

        override fun startCapture(sampleRateHz: Int, frameSamples: Int, onPcmFrame: (ShortArray) -> Unit): Boolean {
            captureStarted = true
            captureCallback = onPcmFrame
            return true
        }
        override fun stopCapture() {
            captureStarted = false
            captureCallback = null
        }
        override fun startPlayback(sampleRateHz: Int) {
            playbackStarted = true
        }
        override fun playPcm(samples: ShortArray) {
            if (!muted) playedFrames += samples
        }
        override fun stopPlayback() {
            playbackStarted = false
        }
        override fun setReceiveMuted(muted: Boolean) {
            this.muted = muted
        }
        override fun audioOutputs(): List<AudioDevice> = emptyList()
        override fun currentOutput(): AudioDevice? = null
        override fun selectOutput(device: AudioDevice) {}
        override fun close() {
            stopCapture()
            stopPlayback()
        }
    }

    private class SimulatedThreeHopPttBridge(
        override val localHashHex: String,
        private val peers: List<String>,
        private val simulatedHopLatencyMs: Long = 60L,
        private val hopCount: Int = 3,
        private val clockMs: () -> Long,
        private val onForwardToRemote: (RnsPttFrame) -> Unit = {},
    ) : RnsPttTransportBridge {
        val sentFrames = CopyOnWriteArrayList<RnsPttFrame>()
        val floorRequests = CopyOnWriteArrayList<RnsPttFloorControl>()
        var remoteBusyHolder: String? = null

        override fun peerDestinations(channelId: String): List<String> = peers

        override fun sendPttFrame(destinationHashHex: String, frame: RnsPttFrame): Boolean {
            sentFrames += frame
            val delivered = frame.copy(hopCount = hopCount)
            onForwardToRemote(delivered)
            return true
        }

        override fun requestPttFloor(
            destinationHashHex: String,
            channelId: String,
            sessionId: String,
            priority: Int,
        ): RnsPttFloorControl {
            val busy = remoteBusyHolder
            val reply = if (busy != null && priority <= 1) {
                RnsPttFloorControl(
                    channelId = channelId,
                    sessionId = sessionId,
                    actorHashHex = destinationHashHex,
                    actorName = "BusyPeer",
                    action = RnsPttFloorAction.BUSY,
                    priority = 1,
                    timestampMs = clockMs() + simulatedHopLatencyMs,
                    holderHashHex = busy,
                )
            } else {
                RnsPttFloorControl(
                    channelId = channelId,
                    sessionId = sessionId,
                    actorHashHex = localHashHex,
                    action = RnsPttFloorAction.GRANT,
                    priority = priority,
                    timestampMs = clockMs(),
                )
            }
            floorRequests += reply
            return reply
        }

        override fun releasePttFloor(
            destinationHashHex: String,
            channelId: String,
            sessionId: String,
        ): RnsPttFloorControl = RnsPttFloorControl(
            channelId = channelId,
            sessionId = sessionId,
            actorHashHex = localHashHex,
            action = RnsPttFloorAction.RELEASE,
            priority = 1,
            timestampMs = clockMs(),
        )
    }

    private fun sineWaveFrame(samples: Int = RnsPttAdpcmCodec.FRAME_SAMPLES): ShortArray =
        ShortArray(samples) { idx -> (sin(idx * 0.22) * 12_000.0).toInt().toShort() }

    @Test
    fun adpcmCodecCompressesFourToOneWithSpeechPreservation() {
        val pcm = sineWaveFrame()
        val encoded = RnsPttAdpcmCodec.encode(pcm)
        assertEquals(164, encoded.size) // 4B header + 160B nibbles vs 640B PCM16; fits inside 383B RNS SINGLE MDU

        val decoded = RnsPttAdpcmCodec.decode(encoded, pcm.size)
        assertEquals(pcm.size, decoded.size)
        val peakDiff = pcm.indices.maxOf { abs(pcm[it] - decoded[it]) }
        assertTrue("ADPCM peak error too high: $peakDiff", peakDiff < 2_500)
    }

    @Test
    fun pttFloorArbiterHandlesContentionBusyPreemptAndExpiry() {
        val now = AtomicLong(1_000L)
        val arbiter = RnsPttFloorArbiter("node-a", "Alpha-1", leaseTtlMs = 10_000L, clockMs = { now.get() })

        // Remote low-priority peer claims floor.
        val (d1, _) = arbiter.onRemoteControl(
            RnsPttFloorControl("ch-1", "s-remote", "node-b", "Bravo-2", RnsPttFloorAction.REQUEST, priority = 1, timestampMs = 1_000L),
        )
        assertTrue(d1 is RnsPttFloorArbiter.Decision.Granted)
        assertEquals("Bravo-2", arbiter.currentSpeaker()?.name)

        // Local equal-priority press is denied BUSY.
        val d2 = arbiter.requestLocal("s-local", priority = 1, nowMs = 2_000L)
        assertTrue(d2 is RnsPttFloorArbiter.Decision.DeniedBusy)

        // Local higher-priority (dispatcher) press preempts remote speaker.
        val d3 = arbiter.requestLocal("s-dispatch", priority = 2, nowMs = 3_000L)
        assertTrue(d3 is RnsPttFloorArbiter.Decision.Granted)
        assertEquals(PttPhase.SPEAKING, arbiter.currentPhase(connected = true, nowMs = 3_000L))

        // Lease expiration clears floor automatically.
        assertEquals(PttPhase.LISTENING, arbiter.currentPhase(connected = true, nowMs = 14_000L))
    }

    @Test
    fun multiHopPttStreamsUnder800msJitterConcealsLossAndArchivesBurst() {
        val clockA = AtomicLong(10_000L)
        val clockB = AtomicLong(10_180L) // +180ms 3-hop transit latency (< 800ms target!)

        val audioA = FakeAudioIo()
        val audioB = FakeAudioIo()
        val archivedA = CopyOnWriteArrayList<VoiceCapturedClip>()
        val archivedB = CopyOnWriteArrayList<VoiceCapturedClip>()
        val cuesA = CopyOnWriteArrayList<PttCue>()
        val speakersB = CopyOnWriteArrayList<PttSpeaker?>()

        lateinit var transportB: RnsPttSessionTransport
        val bridgeA = SimulatedThreeHopPttBridge(
            localHashHex = "aaaa1111aaaa1111aaaa1111aaaa1111",
            peers = listOf("bbbb2222bbbb2222bbbb2222bbbb2222"),
            clockMs = { clockA.get() },
            onForwardToRemote = { frame ->
                // Simulate 3-hop delivery with packet seq=2 dropped in flight (concealed on B).
                if (frame.seq != 2L) {
                    transportB.onInboundFrame(frame)
                }
            },
        )
        val bridgeB = SimulatedThreeHopPttBridge(
            localHashHex = "bbbb2222bbbb2222bbbb2222bbbb2222",
            peers = listOf("aaaa1111aaaa1111aaaa1111aaaa1111"),
            clockMs = { clockB.get() },
        )

        val transportA = RnsPttSessionTransport(
            channelId = "ch-alpha",
            localDisplayName = "Alpha-1",
            bridge = bridgeA,
            audioIo = audioA,
            clockMs = { clockA.get() },
            onSpeaker = {},
            onOutput = {},
            onClip = { archivedA += it },
            onCue = { cuesA += it },
            onState = { _, _ -> },
        )
        transportB = RnsPttSessionTransport(
            channelId = "ch-alpha",
            localDisplayName = "Bravo-2",
            bridge = bridgeB,
            audioIo = audioB,
            clockMs = { clockB.get() },
            onSpeaker = { speakersB += it },
            onOutput = {},
            onClip = { archivedB += it },
            onCue = {},
            onState = { _, _ -> },
        )

        transportA.connect()
        transportB.connect()

        // Press PTT on A and stream 14 frames (280 ms > 250ms archive threshold), seq=2 dropped.
        assertTrue(transportA.startPress())
        repeat(14) { idx ->
            clockA.set(10_000L + idx * 20L)
            clockB.set(10_180L + idx * 20L)
            transportA.pushLocalPcmFrame(sineWaveFrame())
        }
        transportA.stopPress(cancel = false)

        // Verify remote B played all 14 frames (13 decoded + 1 concealed for dropped seq=2) under 800ms latency.
        assertEquals(14, audioB.playedFrames.size)
        val statsB = transportB.playoutStats()
        assertEquals(13L, statsB.framesPlayed)
        assertEquals(1L, statsB.framesConcealed)
        assertTrue("Expected 3-hop latency < 800ms, got ${statsB.maxLatencyMs}", statsB.maxLatencyMs < 800L)

        // Verify completed burst archived on both sender and receiver without auto-replaying as live audio.
        assertEquals(1, archivedA.size)
        assertEquals(1, archivedB.size)
        assertTrue(archivedA[0].mine)
        assertFalse(archivedB[0].mine)
        assertNotNull(speakersB.firstOrNull { it?.name == "Alpha-1" })

        // Verify BUSY cue when remote holds floor.
        bridgeA.remoteBusyHolder = "cccc3333"
        assertFalse(transportA.startPress())
        assertTrue(PttCue.BUSY in cuesA)

        transportA.close()
        transportB.close()
    }

    @Test
    fun pttHandoffCoordinatorSwitchesBetweenLiveKitAndRnsMeshWithin5Seconds() {
        val now = AtomicLong(1_000L)
        val networkManager = RnsNetworkManager(clockMs = { now.get() })
        val liveKitClosed = AtomicBoolean(false)
        val rnsConnected = AtomicBoolean(false)

        val fakeLiveKit = object : PttSessionTransport {
            override val mode = PttTransportMode.LIVEKIT
            override val receiveMuted = false
            override fun connect() {}
            override fun startPress() = true
            override fun stopPress(cancel: Boolean) {}
            override fun setReceiveMuted(muted: Boolean) {}
            override fun audioOutputs(): List<AudioDevice> = emptyList()
            override fun currentOutput(): AudioDevice? = null
            override fun selectOutput(device: AudioDevice) {}
            override fun close() {
                liveKitClosed.set(true)
            }
        }
        val fakeRns = object : PttSessionTransport {
            override val mode = PttTransportMode.NEARBY_LIVE
            override val receiveMuted = false
            override fun connect() {
                rnsConnected.set(true)
            }
            override fun startPress() = true
            override fun stopPress(cancel: Boolean) {}
            override fun setReceiveMuted(muted: Boolean) {}
            override fun audioOutputs(): List<AudioDevice> = emptyList()
            override fun currentOutput(): AudioDevice? = null
            override fun selectOutput(device: AudioDevice) {}
            override fun close() {}
        }

        val handoff = RnsPttHandoffCoordinator(
            liveKitFactory = { fakeLiveKit },
            rnsPttFactory = { fakeRns },
            clockMs = { now.get() },
        )

        val wanState = networkManager.evaluate(
            RnsNetworkInputs(
                hasInternet = true,
                validatedInternet = true,
                serverReachable = true,
                wifiAwareStatus = CommLinkStatus(CommTransportKind.WIFI_AWARE, CommReachability.CONNECTED, "已连接", peerCount = 2),
            ),
            nowMs = 1_000L,
        )
        assertEquals(PttTransportMode.LIVEKIT, handoff.reconcile(wanState, nowMs = 1_000L).mode)

        now.set(1_250L)
        val failoverState = networkManager.reportRouteFailure(RnsManagedRoute.WAN_HTTP, nowMs = 1_250L)
        val switched = handoff.reconcile(failoverState, nowMs = 1_250L)
        assertEquals(PttTransportMode.NEARBY_LIVE, switched.mode)
        assertTrue(liveKitClosed.get())
        assertTrue(rnsConnected.get())
        assertEquals(1L, handoff.totalHandoffs())
        assertTrue(handoff.lastHandoffMs() < 5_000L)

        handoff.close()
    }
}
