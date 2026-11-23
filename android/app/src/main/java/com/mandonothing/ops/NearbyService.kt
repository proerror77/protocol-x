package com.mandonothing.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NearbyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { NearbySettings(this) }
    private var refreshJob: Job? = null
    private var lastRefresh = 0L
    private var radios: NearbyRadios? = null
    @Volatile private var config: NearbyConfig? = null
    private val notify get() = getSystemService(NotificationManager::class.java)
    private val tick = object : Runnable {
        override fun run() {
            if (config?.live() != true) {
                stopSelf()
                return
            }
            radios?.refresh()
            val active = current.value
            if (active != null) jobs.launch(Dispatchers.IO) { runCatching { active.maintenance() } }
            if (!uiActive) config?.let(::refreshInBackground)
            main.postDelayed(this, 5_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notify.createNotificationChannel(NotificationChannel(CHANNEL, "附近通信", NotificationManager.IMPORTANCE_LOW))
        notify.createNotificationChannel(NotificationChannel(MESSAGES, "附近消息", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = notification(CHANNEL, "Protocol X 附近通信", "正在监听当前频道的 Wi-Fi Aware 和蓝牙")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(SERVICE_ID, notification)
        val requested = intent?.getStringExtra("account")?.let { account ->
            val event = intent.getStringExtra("event") ?: return@let null
            val channel = intent.getStringExtra("channel") ?: return@let null
            val title = intent.getStringExtra("title") ?: return@let null
            settings.read(account, event, channel, title)
        }
        if (requested == null) stopSelf(startId) else use(requested)
        return START_NOT_STICKY
    }

    private fun use(requested: NearbyConfig) {
        lastRefresh = SystemClock.elapsedRealtime()
        if (requested.sameTransport(config)) {
            config = requested
            activeCredential.value = requested
            current.value?.updateCredential(requested)
            if (!requested.voiceEnabled) voice.value?.stop(cancel = true)
        } else {
            reset()
            runCatching {
                val deviceIdentity = NearbyIdentity(requested.account)
                val messages = NearbyMessages(applicationContext, requested, deviceIdentity)
                config = requested
                activeCredential.value = requested
                scope.value = requested.scope
                current.value = messages
                val auth = NearbyVoiceAuthentication(messages.group.serviceName, deviceIdentity) { config }
                val controller = NearbyLiveVoice(messages.sender, cacheDir, protect = auth::protect)
                voice.value = controller
                radios = NearbyRadios(this, messages, ::incoming, liveVoice = controller, voiceAuth = auth,
                    onDiagnostics = { metrics.value = it }) { radio, available, linked ->
                    val value = status.value.toMutableMap()
                    value[radio] = if (linked) "已连接" else if (available) "正在发现" else "不可用"
                    status.value = value
                }
                acknowledgePrivate(messages, requested, messages.messages.value)
                tick.run()
            }.onFailure {
                failure.value = "附近通信无法打开本机记录"
                stopSelf()
            }
        }
    }

    private fun refreshInBackground(active: NearbyConfig) {
        val now = SystemClock.elapsedRealtime()
        if (refreshJob?.isActive == true || now - lastRefresh < 60_000) return
        lastRefresh = now
        refreshJob = jobs.launch {
            val (address, token) = FieldSessionStore(this@NearbyService).read() ?: return@launch
            try {
                val client = OpsClient(address, token, BuildConfig.DEBUG, "")
                if (client.localSessionKey != active.account) return@launch
                val signingKey = NearbyIdentity(active.account).publicKey
                val agreement = runCatching { NearbyPrivateIdentity(this@NearbyService, active.account).publicKey }.getOrNull()
                val data = client.nearbyCredentials(active.event, signingKey, agreement)
                if (uiActive || config != active) return@launch
                settings.save(active.account, active.event, data)
                val updated = settings.read(active.account, active.event, active.channel, active.title)
                if (updated == null) stopSelf() else use(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OpsException) {
                if (!uiActive && config == active && error.status in setOf(401, 403)) {
                    runCatching { settings.clear(active.account, active.event) }
                    stopSelf()
                }
            } catch (_: Exception) {
                // Hold the current lease during temporary loss of server reachability.
            }
        }
    }

    private fun acknowledgePrivate(session: NearbyMessages, config: NearbyConfig, records: List<NearbyMessage>) {
        val addressed = records.filter { session.isPrivateAddressedToMe(it) }
        if (addressed.isEmpty()) return
        jobs.launch(Dispatchers.IO) {
            val own = runCatching { NearbyPrivateIdentity(this@NearbyService, config.account) }.getOrNull() ?: return@launch
            for (record in addressed) {
                if (NearbyPrivate.open(config, own, record.actor, record.recipient, record.id, record.sealedPayload) != null)
                    runCatching { session.acknowledgePrivate(record.id) }
            }
        }
    }

    private fun incoming(messages: List<NearbyMessage>, radio: NearbyRadio) {
        val active = config ?: return
        val session = current.value ?: return
        acknowledgePrivate(session, active, messages)
        val visibleMessages = messages.filter {
            !it.isControl && (!it.isPrivate || it.recipient == active.actor)
        }
        val newest = visibleMessages.lastOrNull() ?: return
        val title = if (visibleMessages.any { it.assistance }) "附近有人请求协助" else "收到附近消息"
        val transport = if (radio == NearbyRadio.WIFI) "Wi-Fi Aware" else "蓝牙"
        // Keep activity payload out of the lock-screen notification.
        runCatching { notify.notify(MESSAGE_ID, notification(MESSAGES, title, "${config?.title ?: "当前频道"} · $transport")
            .setWhen(newest.time).setAutoCancel(true).build()) }
    }

    private fun notification(channel: String, title: String, detail: String): Notification.Builder =
        Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(detail).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    private fun reset() {
        main.removeCallbacks(tick)
        radios?.close()
        radios = null
        voice.value?.close()
        voice.value = null
        config = null
        activeCredential.value = null
        metrics.value = NearbyDiagnosticSnapshot()
        scope.value = null
        current.value = null
        status.value = emptyMap()
        failure.value = ""
        notify.cancel(MESSAGE_ID)
    }

    override fun onDestroy() {
        jobs.cancel()
        reset()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "nearby-session"
        private const val MESSAGES = "nearby-messages"
        private const val SERVICE_ID = 4281
        private const val MESSAGE_ID = 4282
        private val current = MutableStateFlow<NearbyMessages?>(null)
        private val scope = MutableStateFlow<String?>(null)
        private val status = MutableStateFlow<Map<NearbyRadio, String>>(emptyMap())
        private val failure = MutableStateFlow("")
        private val metrics = MutableStateFlow(NearbyDiagnosticSnapshot())
        private val activeCredential = MutableStateFlow<NearbyConfig?>(null)
        private val voice = MutableStateFlow<NearbyLiveVoice?>(null)
        val diagnostics = metrics.asStateFlow()
        val channelCredential = activeCredential.asStateFlow()
        val liveVoice: NearbyLiveVoice? get() = voice.value
        val session = current.asStateFlow()
        val radios = status.asStateFlow()
        val error = failure.asStateFlow()
        val activeScope: String? get() = scope.value
        var uiActive: Boolean = false

        fun start(context: Context, config: NearbyConfig): Boolean = runCatching {
            context.startForegroundService(Intent(context, NearbyService::class.java)
                .putExtra("account", config.account).putExtra("event", config.event)
                .putExtra("channel", config.channel).putExtra("title", config.title))
        }.isSuccess

        fun stop(context: Context) { context.stopService(Intent(context, NearbyService::class.java)) }
    }
}
