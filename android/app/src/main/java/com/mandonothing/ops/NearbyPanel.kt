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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

internal class NearbyPanel(private val activity: Activity, private val jobs: CoroutineScope) {
    private val settings = NearbySettings(activity)
    private var area: Area? = null
    var serverSupportsNearby = true
    private var dialog: AlertDialog? = null
    private var microphone: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var finishAtLimit: Runnable? = null
    private var recorder: MediaRecorder? = null
    private var liveHold: NearbyLiveVoice? = null
    private var recording: File? = null
    private var player: MediaPlayer? = null
    private var playback: File? = null

    data class Area(val account: String, val event: String, val channel: String, val title: String) {
        val scope = nearbyScope(account, event, channel)
    }

    fun select(next: Area?) {
        if (next?.scope != area?.scope) {
            pause()
            area = next
        }
        resume()
    }

    fun resume(refresh: Boolean = false) {
        val active = area
        val config = active?.let { settings.read(it.account, it.event, it.channel, it.title) }
        if (config == null || Build.VERSION.SDK_INT < 29) {
            if (NearbyService.activeScope != null) NearbyService.stop(activity)
            return
        }
        if (refresh || NearbyService.activeScope != config.scope) NearbyService.start(activity, config)
        if (nearbyPermissions(activity).any { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            val prefs = activity.getSharedPreferences("nearby_permissions", Activity.MODE_PRIVATE)
            if (!prefs.getBoolean("prompted", false)) {
                prefs.edit().putBoolean("prompted", true).apply()
                grantRadios()
            }
        }
    }

    fun pause() {
        dialog?.dismiss()
        microphone = null
        liveHold?.stop(cancel = true)
        liveHold = null
        discard()
        stopPlayer()
    }

    fun permissionResult(code: Int) {
        if (code == RADIO_PERMISSION) resume()
        if (code == MICROPHONE_PERMISSION) {
            val start = microphone
            microphone = null
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                start?.invoke()
        }
    }

    fun currentSession(): NearbyMessages? {
        val active = area ?: return null
        return NearbyService.session.value?.takeIf { NearbyService.activeScope == active.scope }
    }

    fun permissionsMissing() = nearbyPermissions(activity).any {
        activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun unavailableMessage(): String = when {
        Build.VERSION.SDK_INT < 29 -> "离线通信需要 Android 10 或更新版本"
        !serverSupportsNearby -> "服务端尚未启用频道离线通信"
        else -> "离线通信权限待同步"
    }

    fun openAssistance(send: (String) -> Unit) {
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

    fun grantRadios() {
        val requested = nearbyPermissions(activity) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = requested.filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) activity.requestPermissions(missing.toTypedArray(), RADIO_PERMISSION)
    }

    private fun state(active: Area) = checkNotNull(NearbyService.session.value)
        .also { check(active == area && NearbyService.activeScope == active.scope) }

    fun startHold(feedback: TextView) {
        val active = area ?: return
        val session = currentSession()
        if (session == null) {
            feedback.text = unavailableMessage()
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(permission), RADIO_PERMISSION)
            return
        }
        if (!session.voiceEnabled) {
            feedback.text = "当前频道语音未启用，可发送附近文字"
            return
        }
        if (hasMicrophone()) beginVoice(active, session, feedback)
        else requestMicrophone { feedback.text = "已允许麦克风，按住说话，松开发送" }
    }

    fun stopHold(feedback: TextView, cancel: Boolean) {
        val live = liveHold
        if (live != null) {
            liveHold = null
            live.stop(cancel)
            if (!cancel) feedback.text = "正在保存语音留言…"
            else feedback.text = ""
            return
        }
        if (cancel) discard()
        else area?.let { finishRecording(it, feedback) }
    }

    private fun beginVoice(active: Area, session: NearbyMessages, feedback: TextView) {
        val controller = NearbyService.liveVoice
        if (controller != null && NearbyService.diagnostics.value.liveVoicePeers > 0) {
            stopPlayer()
            val started = controller.start { audio ->
                jobs.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { session.send("", audio) }.isSuccess
                    }
                    if (area == active) feedback.text = if (saved)
                        "语音留言已保存" else "语音留言未保存，请重试"
                }
            }
            if (started) {
                liveHold = controller
                feedback.text = "附近实时对讲 · 松开结束"
                return
            }
            if (controller.receiving || controller.transmitting) {
                feedback.text = "当前有组员发言，请稍后再试"
                return
            }
        }
        beginRecording(active, feedback)
    }

    private fun hasMicrophone() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestMicrophone(onGranted: () -> Unit) {
        microphone = onGranted
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
    }

    @Suppress("DEPRECATION")
    private fun beginRecording(active: Area, feedback: TextView) {
        if (recording != null || active != area) return
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

    private fun finishRecording(active: Area, feedback: TextView) {
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
        val current = runCatching { state(active) }.getOrNull()
        jobs.launch {
            val saved = withContext(Dispatchers.IO) {
                try { current?.send("", file.readBytes()) != null }
                catch (_: Exception) { false }
                finally { file.delete() }
            }
            feedback.text = if (saved) "语音已保存 · 等待 Wi-Fi Aware 送达" else "暂时无法保存语音"
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

    fun play(encoded: String, feedback: TextView) {
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
