package com.mandonothing.ops.comm

import android.content.Context
import android.view.View
import com.mandonothing.ops.ConferenceMedia
import com.mandonothing.ops.OpsClient
import com.mandonothing.ops.RadioTarget
import com.mandonothing.ops.VideoChannel
import com.mandonothing.ops.VideoState
import com.mandonothing.ops.VoiceCapturedClip
import com.mandonothing.ops.VoiceChannel
import com.mandonothing.ops.VoiceCue
import com.mandonothing.ops.VoiceSpeaker
import com.mandonothing.ops.VoiceState
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.CoroutineScope

/** Pure mapping helpers between LiveKit channel models and RNS-02 PTT/Video contracts. */
internal object LiveKitMediaMapper {
    fun toPttPhase(state: VoiceState): PttPhase = when (state) {
        VoiceState.OFFLINE -> PttPhase.OFFLINE
        VoiceState.CONNECTING -> PttPhase.CONNECTING
        VoiceState.LISTENING -> PttPhase.LISTENING
        VoiceState.REQUESTING -> PttPhase.REQUESTING
        VoiceState.SPEAKING -> PttPhase.SPEAKING
    }

    fun toVoiceState(phase: PttPhase): VoiceState = when (phase) {
        PttPhase.OFFLINE -> VoiceState.OFFLINE
        PttPhase.CONNECTING -> VoiceState.CONNECTING
        PttPhase.LISTENING -> VoiceState.LISTENING
        PttPhase.REQUESTING -> VoiceState.REQUESTING
        PttPhase.SPEAKING -> VoiceState.SPEAKING
    }

    fun toPttCue(cue: VoiceCue): PttCue = when (cue) {
        VoiceCue.ENDED -> PttCue.ENDED
        VoiceCue.BUSY -> PttCue.BUSY
    }

    fun toPttSpeaker(speaker: VoiceSpeaker?): PttSpeaker? =
        speaker?.let { PttSpeaker(it.trackSid, it.name) }

    fun toPttClip(clip: VoiceCapturedClip, atIso: String, durationMs: Int): PttClip =
        PttClip(clip.trackSid, clip.speaker, clip.mine, atIso, durationMs)

    fun toVideoChannelState(state: VideoState): VideoChannelState = VideoChannelState(
        text = state.text,
        ready = state.ready,
        cameraRequested = state.cameraRequested,
        sharing = state.sharing,
        showingTrack = state.showingTrack,
        failed = state.failed,
    )
}

/**
 * Adapter wrapping LiveKit-backed [VoiceChannel] behind [PttSessionTransport].
 * RNS-10 will provide an additional [PttSessionTransport] backed by RNS/Wi-Fi Aware.
 */
internal class LiveKitPttTransportAdapter(
    context: Context,
    scope: CoroutineScope,
    client: OpsClient,
    eventId: String,
    allowLocal: Boolean,
    target: RadioTarget? = null,
    onSpeaker: (PttSpeaker?) -> Unit,
    onOutput: (String) -> Unit,
    onClip: (VoiceCapturedClip) -> Unit = {},
    onCue: (PttCue) -> Unit,
    onState: (PttPhase, String) -> Unit,
) : PttSessionTransport {
    override val mode: PttTransportMode = PttTransportMode.LIVEKIT

    private val delegate = VoiceChannel(
        context = context,
        scope = scope,
        client = client,
        eventId = eventId,
        allowLocal = allowLocal,
        target = target,
        onSpeaker = { speaker -> onSpeaker(LiveKitMediaMapper.toPttSpeaker(speaker)) },
        onOutput = onOutput,
        onClip = onClip,
        onCue = { cue -> onCue(LiveKitMediaMapper.toPttCue(cue)) },
        onState = { state, message -> onState(LiveKitMediaMapper.toPttPhase(state), message) },
    )

    override val receiveMuted: Boolean
        get() = delegate.receiveMuted

    override fun connect() = delegate.connect()
    override fun startPress(): Boolean = delegate.startPress()
    override fun stopPress(cancel: Boolean) = delegate.stopPress(cancel)
    override fun setReceiveMuted(muted: Boolean) = delegate.setReceiveMuted(muted)
    override fun audioOutputs(): List<AudioDevice> = delegate.audioOutputs()
    override fun currentOutput(): AudioDevice? = delegate.currentOutput()
    override fun selectOutput(device: AudioDevice) = delegate.selectOutput(device)
    override fun close() = delegate.close()
}

/** Adapter wrapping LiveKit-backed [VideoChannel] behind [VideoChannelTransport]. */
internal class LiveKitVideoTransportAdapter(
    context: Context,
    renderer: TextureViewRenderer,
    scope: CoroutineScope,
    client: OpsClient,
    eventId: String,
    allowLocal: Boolean,
    target: RadioTarget? = null,
    onState: (VideoChannelState) -> Unit,
) : VideoChannelTransport {
    private val delegate = VideoChannel(
        context = context,
        renderer = renderer,
        scope = scope,
        client = client,
        eventId = eventId,
        allowLocal = allowLocal,
        target = target,
        onState = { state -> onState(LiveKitMediaMapper.toVideoChannelState(state)) },
    )

    override fun connect() = delegate.connect()
    override fun startCamera() = delegate.startCamera()
    override fun stopCamera() = delegate.stopCamera()
    override fun close() = delegate.close()
}

/** Adapter wrapping LiveKit-backed [ConferenceMedia] behind [ConferenceCallTransport]. */
internal class LiveKitConferenceTransportAdapter(
    context: Context,
    scope: CoroutineScope,
    client: OpsClient,
    eventId: String,
    callId: String,
    camera: Boolean,
    remoteView: TextureViewRenderer?,
    localView: TextureViewRenderer?,
    status: (String) -> Unit,
    failed: (String) -> Unit,
) : ConferenceCallTransport {
    private val delegate = ConferenceMedia(
        context = context,
        scope = scope,
        client = client,
        eventId = eventId,
        callId = callId,
        camera = camera,
        remoteView = remoteView,
        localView = localView,
        status = status,
        failed = failed,
    )

    override fun start() = delegate.start()
    override fun close() = delegate.close()
}

/** Factory keeping LiveKit SDK classes (such as TextureViewRenderer) out of MainActivity. */
internal object LiveKitMediaFactory {
    fun createVideoSurface(context: Context, description: String? = null): View =
        TextureViewRenderer(context).apply {
            if (description != null) contentDescription = description
        }

    fun createVideoChannel(
        context: Context,
        surface: View,
        scope: CoroutineScope,
        client: OpsClient,
        eventId: String,
        allowLocal: Boolean,
        target: RadioTarget? = null,
        onState: (VideoChannelState) -> Unit,
    ): VideoChannelTransport = LiveKitVideoTransportAdapter(
        context = context,
        renderer = surface as TextureViewRenderer,
        scope = scope,
        client = client,
        eventId = eventId,
        allowLocal = allowLocal,
        target = target,
        onState = onState,
    )

    fun createConference(
        context: Context,
        scope: CoroutineScope,
        client: OpsClient,
        eventId: String,
        callId: String,
        camera: Boolean,
        remoteSurface: View?,
        localSurface: View?,
        status: (String) -> Unit,
        failed: (String) -> Unit,
    ): ConferenceCallTransport = LiveKitConferenceTransportAdapter(
        context = context,
        scope = scope,
        client = client,
        eventId = eventId,
        callId = callId,
        camera = camera,
        remoteView = remoteSurface as? TextureViewRenderer,
        localView = localSurface as? TextureViewRenderer,
        status = status,
        failed = failed,
    )
}
