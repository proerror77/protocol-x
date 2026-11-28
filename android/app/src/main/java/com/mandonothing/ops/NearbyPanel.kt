package com.mandonothing.ops

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommGateway
import com.mandonothing.ops.comm.CommLocalFallbackController
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

internal class NearbyPanel(
    private val activity: Activity,
    private val jobs: CoroutineScope,
    private val gateway: CommGateway,
) : CommLocalFallbackController {
    private var activeScope: CommScope? = null
    private var dialog: AlertDialog? = null
    private var microphone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var finishAtLimit: Runnable? = null
    private var recorder: MediaRecorder? = null
    private var recording: File? = null
    private var player: MediaPlayer? = null
    private var playback: File? = null

    override fun select(scope: CommScope?) {
        if (scope?.nearbyScopeKey != activeScope?.nearbyScopeKey) {
            pause()
            activeScope = scope
            gateway.selectScope(scope)
        }
        resume()
    }

    override fun resume(refresh: Boolean) {
        gateway.refresh(forceRestart = refresh)
        if (gateway.isSessionReady(activeScope) &&
            nearbyPermissions(activity).any { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        ) {
            val prefs = activity.getSharedPreferences("nearby_permissions", Activity.MODE_PRIVATE)
            if (!prefs.getBoolean("prompted", false)) {
                prefs.edit().putBoolean("prompted", true).apply()
                grantRadios()
            }
        }
    }

    override fun pause() {
        dialog?.dismiss()
        microphone = null
        discard()
        stopPlayer()
    }

    override fun permissionResult(code: Int) {
        if (code == RADIO_PERMISSION) resume()
        if (code == MICROPHONE_PERMISSION) {
            val start = microphone
            microphone = null
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                start?.invoke()
            }
        }
    }

    override fun permissionsMissing() = nearbyPermissions(activity).any {
        activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    override fun openAssistance(send: (String) -> Unit) {
        dialog?.dismiss()
        val field = EditText(activity).apply {
            hint = "填写需要的协助"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(NEARBY_TEXT_LIMIT))
            maxLines = 3
        }
        val shown = AlertDialog.Builder(activity).setTitle("请求协助")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送求助") { _, _ -> send(field.text.toString().trim()) }
            .create()
        dialog = shown
        shown.setOnDismissListener { if (dialog === shown) dialog = null }
        shown.show()
    }

    override fun grantRadios() {
        val requested = nearbyPermissions(activity) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = requested.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) activity.requestPermissions(missing.toTypedArray(), RADIO_PERMISSION)
    }

    override fun startHold(feedback: TextView) {
        val active = activeScope ?: return
        if (!gateway.isSessionReady(active)) {
            feedback.text = gateway.unavailableReason()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(permission), RADIO_PERMISSION)
            return
        }
        if (hasMicrophone()) beginRecording(active, feedback)
        else requestMicrophone { feedback.text = "已允许麦克风，按住说话，松开发送" }
    }

    override fun stopHold(feedback: TextView, cancel: Boolean) {
        if (cancel) discard()
        else activeScope?.let { finishRecording(it, feedback) }
    }

    private fun hasMicrophone() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestMicrophone(onGranted: () -> Unit) {
        microphone = onGranted
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun beginRecording(active: CommScope, feedback: TextView) {
        if (recording != null || active != activeScope) return
        stopPlayer()
        try {
            val file = File.createTempFile("nearby-record-", ".m4a", activity.cacheDir)
            recording = file
            val device = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(activity) else MediaRecorder()
            recorder = device
            device.setAudioSource(MediaRecorder.AudioSource.MIC)
            device.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            device.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            device.setAudioChannels(1)
            device.setAudioSamplingRate(16_000)
            device.setAudioEncodingBitRate(24_000)
            device.setOutputFile(file.absolutePath)
            device.prepare()
            device.start()
            finishAtLimit = Runnable { finishRecording(active, feedback) }.also { main.postDelayed(it, 8_000) }
            feedback.text = "正在录音 · 最长 8 秒"
        } catch (_: Exception) {
            discard()
            feedback.text = "无法录音，请检查麦克风"
        }
    }

    private fun finishRecording(active: CommScope, feedback: TextView) {
        val file = recording ?: return
        finishAtLimit?.let(main::removeCallbacks)
        finishAtLimit = null
        val device = recorder
        recording = null
        recorder = null
        val completed = runCatching { device?.stop() }.isSuccess
        runCatching { device?.release() }
        if (!completed || file.length() !in 1..NEARBY_AUDIO_LIMIT.toLong()) {
            file.delete()
            feedback.text = "录音太短或无法完成，请重试"
            return
        }
        jobs.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    gateway.send(
                        CommOutboundMessage(
                            scope = active,
                            kind = CommEnvelopeKind.VOICE_NOTE,
                            audioBytes = file.readBytes(),
                        ),
                    )
                } finally {
                    file.delete()
                }
            }
            feedback.text = if (result !is CommSendResult.Rejected) "语音已保存 · 等待 RNS 链路送达" else "暂时无法保存语音"
        }
    }

    private fun discard() {
        finishAtLimit?.let(main::removeCallbacks)
        finishAtLimit = null
        runCatching { recorder?.release() }
        recorder = null
        recording?.delete()
        recording = null
    }

    override fun play(encoded: String, feedback: TextView) {
        stopPlayer()
        try {
            val file = File.createTempFile("nearby-play-", ".m4a", activity.cacheDir)
            playback = file
            file.writeBytes(Base64.getDecoder().decode(encoded))
            val audio = MediaPlayer()
            player = audio
            audio.setDataSource(file.absolutePath)
            audio.setOnPreparedListener { if (player === it) it.start() }
            audio.setOnCompletionListener { stopPlayer() }
            audio.setOnErrorListener { _, _, _ ->
                stopPlayer()
                feedback.text = "这条语音暂时无法播放"
                true
            }
            audio.prepareAsync()
        } catch (_: Exception) {
            stopPlayer()
            feedback.text = "这条语音暂时无法播放"
        }
    }

    private fun stopPlayer() {
        runCatching { player?.release() }
        player = null
        playback?.delete()
        playback = null
    }

    companion object {
        const val RADIO_PERMISSION = 4283
        const val MICROPHONE_PERMISSION = 4284
    }
}
