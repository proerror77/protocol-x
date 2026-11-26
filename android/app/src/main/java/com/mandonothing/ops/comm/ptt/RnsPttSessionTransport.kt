package com.mandonothing.ops.comm.ptt

import android.content.Context
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.comm.PttCue
import com.mandonothing.ops.comm.PttPhase
import com.mandonothing.ops.comm.PttSessionTransport
import com.mandonothing.ops.comm.PttSpeaker
import com.mandonothing.ops.comm.PttTransportMode
import com.mandonothing.ops.comm.network.RnsNetworkManager
import com.mandonothing.ops.comm.network.RnsNetworkManagerState
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * RNS-10 Multi-Hop PTT Transport implementing [PttSessionTransport].
 *
 * Streams low-latency IMA ADPCM voice frames across 1–5 hop RNS networks, arbitrates floor
 * contention/preemption via [RnsPttFloorArbiter], plays out and archives completed bursts via
 * [RnsPttJitterBuffer] (without ever auto-replaying offline clips as live PTT), and supports `<5s`
 * LiveKit ↔ RNS Mesh handoff.
 */
internal class RnsPttSessionTransport(
    private val channelId: String,
    private val localDisplayName: String,
    private val bridge: RnsPttTransportBridge,
    private val audioIo: RnsPttAudioIo,
    private val priority: Int = 1,
    private val maxBurstMs: Long = 15_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onSpeaker: (PttSpeaker?) -> Unit,
    private val onOutput: (String) -> Unit,
    private val onClip: (VoiceCapturedClip) -> Unit,
    private val onCue: (PttCue) -> Unit,
    private val onState: (PttPhase, String) -> Unit,
) : PttSessionTransport {

    companion object {
        /** Production factory creating [RnsPttSessionTransport] backed by [AndroidRnsPttAudioIo]. */
        fun create(
            context: Context,
            channelId: String,
            localDisplayName: String,
            bridge: RnsPttTransportBridge,
            priority: Int = 1,
            onSpeaker: (PttSpeaker?) -> Unit,
            onOutput: (String) -> Unit,
            onClip: (VoiceCapturedClip) -> Unit,
            onCue: (PttCue) -> Unit,
            onState: (PttPhase, String) -> Unit,
        ): RnsPttSessionTransport {
            val audio = AndroidRnsPttAudioIo(context, onOutputChanged = onOutput)
            return RnsPttSessionTransport(
                channelId = channelId,
                localDisplayName = localDisplayName,
                bridge = bridge,
                audioIo = audio,
                priority = priority,
                onSpeaker = onSpeaker,
                onOutput = onOutput,
                onClip = onClip,
                onCue = onCue,
                onState = onState,
            )
        }
    }

    override val mode: PttTransportMode = PttTransportMode.NEARBY_LIVE

    private val arbiter = RnsPttFloorArbiter(
        localActorHash = bridge.localHashHex,
        localDisplayName = localDisplayName,
        clockMs = clockMs,
    )
    private val jitterBuffer = RnsPttJitterBuffer(
        clockMs = clockMs,
        onBurstArchived = onClip,
    )

    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val seqCounter = AtomicLong(0L)

    @Volatile private var receiveMutedState = false
    @Volatile private var activeLocalSessionId: String? = null
    @Volatile private var activeLocalStartedMs = 0L

    override val receiveMuted: Boolean
        get() = receiveMutedState

    override fun connect() {
        if (closed.get() || connected.getAndSet(true)) return
        audioIo.startPlayback(RnsPttAdpcmCodec.SAMPLE_RATE_HZ)
        audioIo.setReceiveMuted(receiveMutedState)
        onOutput(audioIo.currentOutput()?.name ?: "系统默认")
        onState(PttPhase.LISTENING, "RNS 多跳对讲就绪")
    }

    override fun startPress(): Boolean {
        if (closed.get() || !connected.get()) return false
        val sessionId = UUID.randomUUID().toString()
        val now = clockMs()
        when (val decision = arbiter.requestLocal(sessionId = sessionId, priority = priority, nowMs = now)) {
            is RnsPttFloorArbiter.Decision.DeniedBusy -> {
                onCue(PttCue.BUSY)
                onState(PttPhase.LISTENING, "${decision.currentHolder.actorName} 正在发言")
                return false
            }
            is RnsPttFloorArbiter.Decision.Granted -> {
                onState(PttPhase.REQUESTING, "正在申请频道话权…")
                val destinations = bridge.peerDestinations(channelId)
                var rejectedRemote: RnsPttFloorControl? = null
                for (dest in destinations) {
                    val reply = runCatching {
                        bridge.requestPttFloor(dest, channelId, sessionId, priority)
                    }.getOrNull()
                    if (reply != null && reply.action == RnsPttFloorAction.BUSY) {
                        rejectedRemote = reply
                        break
                    }
                }
                if (rejectedRemote != null) {
                    arbiter.onRemoteControl(rejectedRemote, clockMs())
                    onCue(PttCue.BUSY)
                    onState(PttPhase.LISTENING, "频道忙，稍后再试")
                    return false
                }
                activeLocalSessionId = sessionId
                activeLocalStartedMs = now
                seqCounter.set(0L)
                val captureStarted = audioIo.startCapture(
                    sampleRateHz = RnsPttAdpcmCodec.SAMPLE_RATE_HZ,
                    frameSamples = RnsPttAdpcmCodec.FRAME_SAMPLES,
                    onPcmFrame = ::onCapturedPcmFrame,
                )
                if (!captureStarted) {
                    arbiter.releaseLocal(sessionId, clockMs())
                    activeLocalSessionId = null
                    onState(PttPhase.LISTENING, "麦克风采集失败")
                    return false
                }
                onState(PttPhase.SPEAKING, "RNS 多跳讲话中")
                return true
            }
            else -> return false
        }
    }

    override fun stopPress(cancel: Boolean) {
        val sessionId = activeLocalSessionId ?: return
        activeLocalSessionId = null
        audioIo.stopCapture()
        val now = clockMs()
        if (!cancel) {
            val endFrame = RnsPttFrame(
                channelId = channelId,
                sessionId = sessionId,
                senderHashHex = bridge.localHashHex,
                senderName = localDisplayName,
                seq = seqCounter.getAndIncrement(),
                timestampMs = now,
                durationMs = 0,
                audioBytes = ByteArray(0),
                endOfBurst = true,
            )
            jitterBuffer.ingest(endFrame, mine = true, nowMs = now)
            broadcastFrame(endFrame)
        } else {
            jitterBuffer.finishBurst(sessionId)
        }
        arbiter.releaseLocal(sessionId, now)
        bridge.peerDestinations(channelId).forEach { dest ->
            runCatching { bridge.releasePttFloor(dest, channelId, sessionId) }
        }
        if (connected.get() && !closed.get()) {
            onState(PttPhase.LISTENING, "RNS 多跳对讲就绪")
        }
    }

    /** Accepts a locally captured or test-injected PCM frame while speaking. */
    fun pushLocalPcmFrame(samples: ShortArray) {
        onCapturedPcmFrame(samples)
    }

    /** Delivers an inbound [RnsPttFrame] received from `RnsInboundEvent.PttFrameReceived`. */
    fun onInboundFrame(frame: RnsPttFrame) {
        if (closed.get() || !connected.get() || frame.channelId != channelId) return
        if (frame.senderHashHex.equals(bridge.localHashHex, ignoreCase = true)) return

        val now = clockMs()
        val speaker = PttSpeaker(
            trackSid = frame.sessionId,
            name = frame.senderName.ifBlank { frame.senderHashHex.take(8) },
        )
        if (!frame.endOfBurst) {
            onSpeaker(speaker)
        }
        val pcmFrames = jitterBuffer.ingest(frame, mine = false, nowMs = now)
        pcmFrames.forEach { pcm -> audioIo.playPcm(pcm) }
        if (frame.endOfBurst) {
            onSpeaker(arbiter.currentSpeaker(now))
        }
    }

    /** Delivers an inbound [RnsPttFloorControl] received from `RnsInboundEvent.PttFloorEvent`. */
    fun onInboundFloorControl(control: RnsPttFloorControl) {
        if (closed.get() || !connected.get() || control.channelId != channelId) return
        val now = clockMs()
        val (decision, cue) = arbiter.onRemoteControl(control, now)
        when (decision) {
            is RnsPttFloorArbiter.Decision.PreemptedLocal -> {
                stopPress(cancel = true)
                onSpeaker(arbiter.currentSpeaker(now))
            }
            is RnsPttFloorArbiter.Decision.Granted -> {
                onSpeaker(arbiter.currentSpeaker(now))
            }
            is RnsPttFloorArbiter.Decision.Released -> {
                jitterBuffer.finishBurst(control.sessionId)
                onSpeaker(arbiter.currentSpeaker(now))
            }
            else -> Unit
        }
        if (cue != null) onCue(cue)
    }

    fun playoutStats(): RnsPttPlayoutStats = jitterBuffer.stats()

    override fun setReceiveMuted(muted: Boolean) {
        receiveMutedState = muted
        audioIo.setReceiveMuted(muted)
    }

    override fun audioOutputs(): List<AudioDevice> = audioIo.audioOutputs()

    override fun currentOutput(): AudioDevice? = audioIo.currentOutput()

    override fun selectOutput(device: AudioDevice) = audioIo.selectOutput(device)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopPress(cancel = true)
        connected.set(false)
        jitterBuffer.clear()
        runCatching { audioIo.close() }
        onSpeaker(null)
        onState(PttPhase.OFFLINE, "RNS 多跳对讲已断开")
    }

    private fun onCapturedPcmFrame(samples: ShortArray) {
        val sessionId = activeLocalSessionId ?: return
        val now = clockMs()
        if (now - activeLocalStartedMs >= maxBurstMs) {
            stopPress(cancel = false)
            onCue(PttCue.ENDED)
            return
        }
        val encoded = RnsPttAdpcmCodec.encode(samples)
        val frame = RnsPttFrame(
            channelId = channelId,
            sessionId = sessionId,
            senderHashHex = bridge.localHashHex,
            senderName = localDisplayName,
            seq = seqCounter.getAndIncrement(),
            timestampMs = now,
            codec = RnsPttAdpcmCodec.CODEC_ID,
            durationMs = RnsPttAdpcmCodec.FRAME_DURATION_MS,
            audioBytes = encoded,
            hopCount = 1,
            endOfBurst = false,
        )
        jitterBuffer.ingest(frame, mine = true, nowMs = now)
        broadcastFrame(frame)
    }

    private fun broadcastFrame(frame: RnsPttFrame) {
        bridge.peerDestinations(channelId).forEach { dest ->
            runCatching { bridge.sendPttFrame(dest, frame) }
        }
    }
}

/**
 * Coordinates `<5s` LiveKit ↔ RNS Mesh PTT transport handoffs driven by [RnsNetworkManager].
 * Ensures the outgoing transport stops/closes before the incoming transport connects.
 */
internal class RnsPttHandoffCoordinator(
    private val liveKitFactory: () -> PttSessionTransport,
    private val rnsPttFactory: () -> PttSessionTransport,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var activeTransport: PttSessionTransport? = null
    private var usingLiveKit: Boolean? = null
    private var lastHandoffDurationMs: Long = 0L
    private var handoffCount: Long = 0L
    private var watchJob: Job? = null

    fun currentTransport(): PttSessionTransport? = synchronized(lock) { activeTransport }
    fun lastHandoffMs(): Long = synchronized(lock) { lastHandoffDurationMs }
    fun totalHandoffs(): Long = synchronized(lock) { handoffCount }

    /** Evaluates [networkState] and performs an immediate LiveKit ↔ Mesh PTT switch if needed. */
    fun reconcile(networkState: RnsNetworkManagerState, nowMs: Long = clockMs()): PttSessionTransport {
        synchronized(lock) {
            val wantLiveKit = networkState.shouldUseLiveKitPtt
            val current = activeTransport
            if (current != null && usingLiveKit == wantLiveKit) {
                return current
            }
            val startAt = nowMs
            current?.let { outgoing ->
                runCatching { outgoing.stopPress(cancel = true) }
                runCatching { outgoing.close() }
            }
            val next = if (wantLiveKit) liveKitFactory() else rnsPttFactory()
            next.connect()
            activeTransport = next
            usingLiveKit = wantLiveKit
            if (current != null) {
                lastHandoffDurationMs = (clockMs() - startAt).coerceAtLeast(0L)
                handoffCount++
            }
            return next
        }
    }

    fun observe(networkManager: RnsNetworkManager, scope: CoroutineScope) {
        watchJob?.cancel()
        watchJob = scope.launch {
            networkManager.state.collect { state ->
                reconcile(state)
            }
        }
    }

    fun close() {
        watchJob?.cancel()
        watchJob = null
        synchronized(lock) {
            activeTransport?.close()
            activeTransport = null
            usingLiveKit = null
        }
    }
}
