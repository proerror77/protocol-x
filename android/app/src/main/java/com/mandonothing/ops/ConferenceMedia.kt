package com.mandonothing.ops

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ConferenceMedia(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OpsClient,
    private val eventId: String,
    private val callId: String,
    private val camera: Boolean,
    private val remoteView: TextureViewRenderer?,
    private val localView: TextureViewRenderer?,
    private val status: (String) -> Unit,
    private val failed: (String) -> Unit,
) {
    private val room = LiveKit.create(context.applicationContext)
    private var mediaJob: Job? = null
    private var events: Job? = null
    private var remote: VideoTrack? = null
    private var local: VideoTrack? = null
    private var closed = false
    private var initialized = false

    fun start() {
        events = scope.launch {
            room.events.collect { event ->
                if (closed) return@collect
                when (event) {
                    is RoomEvent.ParticipantConnected -> if (initialized) status("通话中")
                    is RoomEvent.ParticipantDisconnected -> if (initialized) failed("对方已离开通话")
                    is RoomEvent.Reconnecting -> status("正在恢复连接…")
                    is RoomEvent.Reconnected -> if (initialized) status("通话中")
                    is RoomEvent.Disconnected -> failed("通话已断开")
                    is RoomEvent.TrackSubscribed -> {
                        if (remoteView != null && event.track is VideoTrack) {
                            remote?.removeRenderer(remoteView)
                            (event.track as VideoTrack).also { it.addRenderer(remoteView); remote = it }
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        if (remoteView != null && event.track === remote) {
                            remote?.removeRenderer(remoteView)
                            remote = null
                        }
                    }
                    else -> Unit
                }
            }
        }
        mediaJob = scope.launch {
            try {
                val session = client.joinConference(eventId, callId)
                validateMediaUrl(session.url, BuildConfig.DEBUG)
                remoteView?.let { room.initVideoRenderer(it) }
                localView?.let { room.initVideoRenderer(it) }
                withTimeout(15_000) { room.connect(session.url, session.token) }
                client.authorizeConference(eventId, callId, session)
                val participant = room.localParticipant
                withTimeout(5_000) {
                    while (participant.permissions?.canPublish != true) delay(20)
                    check(participant.setMicrophoneEnabled(true)) { "麦克风没有接通" }
                    if (camera) check(participant.setCameraEnabled(true)) { "相机没有接通" }
                }
                local = participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                if (localView != null) local?.addRenderer(localView)
                initialized = true
                status(if (room.remoteParticipants.isEmpty()) "等待对方进入…" else "通话中")
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (!closed) failed("通话连接超时")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed) failed(error.message ?: "通话连接失败")
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        mediaJob?.cancel()
        events?.cancel()
        if (remoteView != null) remote?.removeRenderer(remoteView)
        if (localView != null) local?.removeRenderer(localView)
        remoteView?.release()
        localView?.release()
        room.disconnect()
        room.release()
    }
}
