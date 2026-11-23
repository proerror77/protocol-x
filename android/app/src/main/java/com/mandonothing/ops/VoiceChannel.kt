package com.mandonothing.ops

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import livekit.org.webrtc.AudioTrackSink

internal class VoiceChannel(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OpsClient,
    private val eventId: String,
    private val allowLocal: Boolean,
    private val target: RadioTarget? = null,
    private val onSpeaker: (VoiceSpeaker?) -> Unit,
    private val onOutput: (String) -> Unit,
    private val onClip: (VoiceCapturedClip) -> Unit = {},
    private val onCue: (VoiceCue) -> Unit,
    private val onState: (VoiceState, String) -> Unit,
) {
    private val audioPreferences = context.getSharedPreferences("voice_audio", Context.MODE_PRIVATE)
    private val muteKey = "mute-receive:" + (target?.id ?: eventId)
    var receiveMuted = audioPreferences.getBoolean(muteKey, false)
        private set
    private val audioHandler = AudioSwitchHandler(context.applicationContext).apply {
        registerAudioDeviceChangeListener { devices, selected ->
            scope.launch {
                if (closed) return@launch
                val preference = audioPreferences.getString("route", null)
                val preferred = devices.firstOrNull { audioRoute(it) == preference }
                if (preferred != null && preferred != selected) selectDevice(preferred)
                else onOutput(outputLabel(selected))
            }
        }
    }
    private val room = LiveKit.create(context.applicationContext,
        options = RoomOptions(audioTrackPublishDefaults = AudioTrackPublishDefaults(dtx = false, red = true)),
        overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = audioHandler)))
    private var session: MediaSession? = null
    private var events: Job? = null
    private var connectJob: Job? = null
    private var releaseSignal: CompletableDeferred<Unit>? = null
    private var closed = false
    private var state = VoiceState.OFFLINE
    private var remoteTrack: String? = null
    private var microphone: LocalAudioTrack? = null
    private var subscriptionReady: CompletableDeferred<Unit>? = null
    private var preRoll: PttPreRoll? = null
    private var captureFailure: String? = null
    private val incomingPcm = mutableMapOf<String, TrackPcm>()

    private class TrackPcm(val track: AudioTrack, val name: String) {
        val buffer = PttPcm()
        val sink = AudioTrackSink { bytes, bits, rate, channels, frames, _ ->
            buffer.append(bytes, bits, rate, channels, frames)
        }
    }

    private fun beginRemote(track: Track?, sid: String, name: String?) {
        if (track !is RemoteAudioTrack) return
        track.setVolume(if (receiveMuted) 0.0 else 1.0)
        if (sid in incomingPcm) return
        val pcm = TrackPcm(track, name?.takeIf(String::isNotBlank) ?: "频道成员")
        if (runCatching { track.addSink(pcm.sink) }.isSuccess) incomingPcm[sid] = pcm
    }

    private fun finishPcm(sid: String?, pcm: TrackPcm?, mine: Boolean) {
        if (pcm == null) return
        val snapshot = pcm.buffer.finish()
        runCatching { pcm.track.removeSink(pcm.sink) }
        if (sid != null && snapshot != null) onClip(VoiceCapturedClip(sid, pcm.name, mine, snapshot))
    }


    fun connect() {
        if (connectJob != null || closed) return
        update(VoiceState.CONNECTING, "正在连接频道…")
        events = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.LocalTrackSubscribed -> if (event.publication.track === microphone && microphone != null) {
                        subscriptionReady?.complete(Unit)
                    }
                    is RoomEvent.TrackSubscribed -> if (event.publication.source == Track.Source.MICROPHONE) {
                        beginRemote(event.track, event.publication.sid, event.participant.name)
                        reportSpeaker(event.publication.sid, event.participant.name)
                        verifyIncomingPackets(event.track, event.publication.sid)
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        finishPcm(event.publications.sid, incomingPcm.remove(event.publications.sid), false)
                        clearSpeaker(event.publications.sid)
                    }
                    is RoomEvent.TrackMuted -> {
                        finishPcm(event.publication.sid, incomingPcm.remove(event.publication.sid), false)
                        clearSpeaker(event.publication.sid)
                    }
                    is RoomEvent.TrackUnmuted -> if (event.publication.source == Track.Source.MICROPHONE &&
                        event.participant !== room.localParticipant && event.publication.track != null) {
                        beginRemote(event.publication.track, event.publication.sid, event.participant.name)
                        reportSpeaker(event.publication.sid, event.participant.name)
                    }
                    is RoomEvent.Reconnecting -> {
                        stopPress(cancel = true)
                        update(VoiceState.CONNECTING, "正在恢复收听…")
                    }
                    is RoomEvent.Reconnected -> if (session != null) update(VoiceState.LISTENING, "收听中")
                    is RoomEvent.Disconnected ->
                        fail("通话已断开，请重新加入")
                    else -> Unit
                }
            }
        }
        connectJob = scope.launch {
            try {
                val joined = client.join(eventId, target = target)
                validateMediaUrl(joined.url, allowLocal)
                withTimeout(15_000) {
                    room.connect(joined.url, joined.token, ConnectOptions(audio = false, video = false))
                }
                session = joined
                onOutput(outputLabel(audioHandler.selectedAudioDevice))
                update(VoiceState.LISTENING, "收听中")
            } catch (error: CancellationException) {
                if (!closed) fail("连接通话频道超时")
                throw error
            } catch (error: Exception) {
                fail((error as? OpsException)?.message ?: "无法加入通话频道")
            }
        }
    }

    fun startPress(): Boolean {
        val joined = session ?: return false
        if (state != VoiceState.LISTENING || releaseSignal != null || closed) return false
        val signal = CompletableDeferred<Unit>()
        releaseSignal = signal
        val pressId = UUID.randomUUID().toString()
        logStartup("press")
        update(VoiceState.REQUESTING, "正在启动麦克风…")
        scope.launch {
            var status = "收听中"
            var spoke = false
            try {
                transmitWhilePressed(
                    released = signal,
                    claim = {
                        coroutineScope {
                            val grant = async(start = CoroutineStart.UNDISPATCHED) {
                                logStartup("claim_started")
                                client.floor(eventId, joined.id, pressId, "claim", target = target)
                                    .also { logStartup("claim_returned") }
                            }
                            prepareCapture(signal)
                            grant.await()
                        }
                    },
                    renew = { client.floor(eventId, joined.id, pressId, "renew", target = target) },
                    release = {
                        logStartup("floor_release_requested")
                        client.floor(eventId, joined.id, pressId, "release", target = target)
                        logStartup("floor_release_confirmed")
                    },
                    microphone = { enabled ->
                        if (!closed) changeMicrophone(enabled, signal)
                    },
                    onSpeaking = {
                        spoke = true
                        logStartup("ready")
                        update(VoiceState.SPEAKING, "正在发言，松开结束")
                    },
                    onReleaseFailure = { status = "已关闭麦克风，正在等待话权过期" },
                    onGranted = {
                        awaitMicrophonePermission()
                    },
                    onReleasedWhileSpeaking = {
                        val buffered = preRoll
                        if (buffered != null && buffered.isDraining() && !closed) {
                            logStartup("tail_queued_ms=${buffered.queuedDurationMs()}")
                            update(VoiceState.SPEAKING, "正在发送尾音…")
                            if (withTimeoutOrNull(PTT_PREROLL_LIMIT_MS + 150L) { buffered.awaitDrain() } == null) {
                                status = "尾音发送超时，请核对后重试"
                            } else {
                                logStartup("tail_drained")
                            }
                            delay(80)
                        }
                    },
                )
            } catch (error: CancellationException) {
                if (!closed && state != VoiceState.CONNECTING) status = "话权等待超时，松开后重试"
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            } catch (error: Exception) {
                if (!closed && error is OpsException && error.status == 409) onCue(VoiceCue.BUSY)
                status = (error as? OpsException)?.message ?: "发言中断，请松开后重试"
            } finally {
                withContext(NonCancellable) { runCatching { closeMicrophone() } }
                captureFailure?.let { status = it }
                releaseSignal = null
                captureFailure = null
                if (spoke && !closed) onCue(VoiceCue.ENDED)
                if (state in setOf(VoiceState.REQUESTING, VoiceState.SPEAKING)) {
                    update(VoiceState.LISTENING, status)
                }
            }
        }
        return true
    }

    private suspend fun awaitMicrophonePermission() {
        val participant = room.localParticipant
        withTimeout(2_000) {
            participant::permissions.flow.first { it?.canPublish == true }
        }
    }

    private fun prepareCapture(released: CompletableDeferred<Unit>) {
        val track = room.localParticipant.createAudioTrack("现场通话").apply { enabled = false }
        microphone = track
        subscriptionReady = CompletableDeferred()
        val buffered = PttPreRoll(
            onFirstFrame = {
                logStartup("first_capture")
                scope.launch {
                    if (!released.isCompleted && state == VoiceState.REQUESTING && microphone === track)
                        update(VoiceState.REQUESTING, "本机录音 · 正在接通")
                }
            },
            onFailure = {
                scope.launch {
                    if (microphone === track) {
                        captureFailure = if (preRoll?.overflowed == true)
                            "接通超时，未发送，请重新按住" else "音频设备已切换，请重新按住"
                        released.complete(Unit)
                    }
                }
            },
        )
        preRoll = buffered
        track.setAudioBufferCallback(buffered)
        runCatching { track.prewarm() }
    }

    private suspend fun changeMicrophone(enabled: Boolean, released: CompletableDeferred<Unit>) {
        if (!enabled) {
            closeMicrophone()
            return
        }
        val track = checkNotNull(microphone) { "麦克风尚未准备" }
        val participant = room.localParticipant
        withTimeout(2_000) {
            check(participant.publishAudioTrack(track,
                AudioTrackPublishOptions(null, room.audioTrackPublishDefaults, Track.Source.MICROPHONE)))
        }
        logStartup("published_muted")
        withTimeoutOrNull(250) { subscriptionReady?.await() }
        if (released.isCompleted || closed) return
        // Frames stay masked until the published track has been enabled.
        track.enabled = true
        val queued = preRoll?.startSending() ?: error("发言已取消")
        logStartup("streaming_buffered_ms=$queued")
    }

    private fun closeMicrophone() {
        val track = microphone ?: return
        microphone = null
        subscriptionReady = null
        track.enabled = false
        track.setAudioBufferCallback(null)
        val participant = room.localParticipant
        val published = participant.getTrackPublication(Track.Source.MICROPHONE)
        val sid = published?.takeIf { it.track === track }?.sid
        val pcm = preRoll?.finish()
        preRoll = null
        try {
            if (published?.track === track) participant.unpublishTrack(track, stopOnUnpublish = true)
            else track.stop()
        } finally {
            track.stopPrewarm()
            track.dispose()
        }
        if (sid != null && pcm != null) onClip(VoiceCapturedClip(sid, "我的发言", true, pcm))
    }

    fun stopPress(cancel: Boolean = false) {
        if (releaseSignal?.isCompleted == false && !cancel) logStartup("released")
        if (cancel) preRoll?.abort() else preRoll?.stopInput()
        releaseSignal?.complete(Unit)
    }

    fun audioOutputs() = audioHandler.availableAudioDevices.toList()

    fun currentOutput() = audioHandler.selectedAudioDevice

    fun setReceiveMuted(muted: Boolean) {
        receiveMuted = muted
        audioPreferences.edit().putBoolean(muteKey, muted).apply()
        val volume = if (muted) 0.0 else 1.0
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                (publication.track as? RemoteAudioTrack)?.setVolume(volume)
            }
        }
    }

    fun selectOutput(device: AudioDevice) {
        if (!closed && device in audioHandler.availableAudioDevices) {
            audioPreferences.edit().putString("route", audioRoute(device)).apply()
            audioHandler.selectDevice(device)
        }
    }

    private fun clearSpeaker(sid: String) {
        if (remoteTrack != sid) return
        remoteTrack = null
        onSpeaker(null)
    }

    private fun reportSpeaker(trackSid: String, name: String?) {
        remoteTrack = trackSid
        onSpeaker(VoiceSpeaker(trackSid, name?.takeIf(String::isNotBlank) ?: "频道成员"))
    }

    private fun verifyIncomingPackets(track: Track, trackSid: String) {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            val report = withTimeoutOrNull(1_600) {
                delay(550)
                if (!closed && remoteTrack == trackSid) track.getRTCStats() else null
            }
            if (report?.statsMap?.values?.any {
                    it.type == "inbound-rtp" && ((it.members["packetsReceived"] as? Number)?.toLong() ?: 0L) > 0L
                } == true) {
                Log.d("ProtocolXVoiceService", "Incoming audio RTP received")
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        stopPress(cancel = true)
        session = null
        events?.cancel()
        connectJob?.cancel()
        runCatching { closeMicrophone() }
        incomingPcm.forEach { (sid, pcm) -> finishPcm(sid, pcm, false) }
        incomingPcm.clear()
        room.disconnect()
        room.release()
        remoteTrack = null
        onSpeaker(null)
        state = VoiceState.OFFLINE
    }

    private fun fail(message: String) {
        if (closed) return
        close()
        onState(VoiceState.OFFLINE, message)
    }

    private fun update(next: VoiceState, message: String) {
        if (closed) return
        state = next
        onState(next, message)
    }

    private fun logStartup(stage: String) {
        if (BuildConfig.DEBUG) Log.d("OpsPttTiming", stage)
    }
}

internal data class VoiceCapturedClip(val trackSid: String, val speaker: String, val mine: Boolean, val pcm: PttPcmSnapshot)

private fun audioRoute(device: AudioDevice) = when (device) {
    is AudioDevice.Speakerphone -> "speaker"
    is AudioDevice.Earpiece -> "earpiece"
    is AudioDevice.WiredHeadset -> "wired"
    is AudioDevice.BluetoothHeadset -> "bluetooth:" + device.name
    else -> null
}

internal data class VoiceSpeaker(val trackSid: String, val name: String)

internal enum class VoiceState { OFFLINE, CONNECTING, LISTENING, REQUESTING, SPEAKING }
internal enum class VoiceCue { ENDED, BUSY }

internal fun outputLabel(device: AudioDevice?) = when (device) {
    is AudioDevice.Speakerphone -> "扬声器"
    is AudioDevice.Earpiece -> "听筒"
    is AudioDevice.WiredHeadset -> "有线耳机"
    is AudioDevice.BluetoothHeadset -> device.name
    else -> "系统默认"
}
