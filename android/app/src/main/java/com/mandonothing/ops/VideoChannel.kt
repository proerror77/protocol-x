package com.mandonothing.ops

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal class VideoChannel(
    context: Context,
    val renderer: TextureViewRenderer,
    private val scope: CoroutineScope,
    private val client: OpsClient,
    private val eventId: String,
    private val allowLocal: Boolean,
    private val target: RadioTarget? = null,
    private val onState: (VideoState) -> Unit,
) {
    private val room = LiveKit.create(
        context.applicationContext,
        options = RoomOptions(videoTrackCaptureDefaults = LocalVideoTrackOptions(
            position = CameraPosition.BACK,
            captureParams = VideoCaptureParameter(640, 360, 15),
        )),
        overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler())),
    )
    private var events: Job? = null
    private var connection: Job? = null
    private var session: MediaSession? = null
    private var ending: CompletableDeferred<Unit>? = null
    private var remoteTrack: VideoTrack? = null
    private var remoteName: String? = null
    private var displayed: VideoTrack? = null
    private var sharing = false
    private var closed = false

    fun connect() {
        room.initVideoRenderer(renderer)
        update("正在连接画面…")
        events = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> if (event.publication.source == Track.Source.CAMERA) {
                        remoteTrack = event.track as? VideoTrack
                        remoteName = event.participant.name?.takeIf(String::isNotBlank)
                        if (!sharing) {
                            showTrack(remoteTrack)
                            update(receivingStatus())
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> if (remoteTrack === event.track) {
                        remoteTrack = null
                        remoteName = null
                        if (!sharing) {
                            showTrack(null)
                            update("对方已关闭画面")
                        }
                    }
                    is RoomEvent.Reconnecting, is RoomEvent.Disconnected -> fail("画面已断开，请重新加入")
                    else -> Unit
                }
            }
        }
        connection = scope.launch {
            try {
                val joined = client.join(eventId, "video", target)
                validateMediaUrl(joined.url, allowLocal)
                withTimeout(15_000) {
                    room.connect(joined.url, joined.token, ConnectOptions(audio = false, video = false))
                }
                session = joined
                update(if (displayed == null) "等待现场开启画面" else receivingStatus())
            } catch (error: TimeoutCancellationException) {
                fail("连接画面超时，请重试")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail((error as? OpsException)?.message ?: "无法连接画面")
            }
        }
    }

    fun startCamera() {
        val joined = session ?: return
        if (closed || ending != null) return
        val stop = CompletableDeferred<Unit>()
        val press = UUID.randomUUID().toString()
        ending = stop
        update("正在请求开启相机…")
        scope.launch {
            var status = "相机已关闭"
            try {
                transmitWhilePressed(
                    released = stop,
                    claim = { client.floor(eventId, joined.id, press, "claim", "video", target) },
                    renew = { client.floor(eventId, joined.id, press, "renew", "video", target) },
                    release = { client.floor(eventId, joined.id, press, "release", "video", target) },
                    microphone = { enabled -> if (!closed) camera(enabled, stop) },
                    onSpeaking = {
                        sharing = true
                        showTrack(room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack)
                        update("正在发送现场画面")
                    },
                    onReleaseFailure = { status = "相机已关闭，发布授权将自动到期" },
                )
            } catch (error: TimeoutCancellationException) {
                status = "开启相机超时，请重试"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = (error as? OpsException)?.message ?: "无法开启相机，请重试"
            } finally {
                sharing = false
                ending = null
                if (!closed) {
                    showTrack(remoteTrack)
                    update(status)
                }
            }
        }
    }

    private suspend fun camera(enabled: Boolean, stop: CompletableDeferred<Unit>) {
        try {
            withTimeout(3_000) {
                val local = room.localParticipant
                if (enabled) {
                    while (!stop.isCompleted && local.permissions?.canPublish != true) delay(25)
                    if (!stop.isCompleted) check(local.setCameraEnabled(true))
                } else {
                    local.getTrackPublication(Track.Source.CAMERA)?.track?.let {
                        local.unpublishTrack(it, stopOnUnpublish = true)
                    }
                }
            }
        } catch (error: Exception) {
            if (!enabled) fail("相机已随画面断开，请重新连接")
            throw error
        }
    }

    fun stopCamera() {
        ending?.complete(Unit)
    }

    fun close() {
        if (closed) return
        closed = true
        stopCamera()
        events?.cancel()
        connection?.cancel()
        showTrack(null)
        renderer.release()
        room.disconnect()
        room.release()
    }

    private fun showTrack(track: VideoTrack?) {
        if (displayed === track) return
        displayed?.removeRenderer(renderer)
        displayed = track
        track?.addRenderer(renderer)
    }

    private fun receivingStatus() = remoteName?.let { "$it 正在回传" } ?: "正在接收现场画面"

    private fun update(text: String) = onState(VideoState(text, session != null, ending != null, sharing, displayed != null))

    private fun fail(message: String) {
        if (closed) return
        close()
        onState(VideoState(message, failed = true))
    }
}

internal data class VideoState(
    val text: String,
    val ready: Boolean = false,
    val cameraRequested: Boolean = false,
    val sharing: Boolean = false,
    val showingTrack: Boolean = false,
    val failed: Boolean = false,
)
