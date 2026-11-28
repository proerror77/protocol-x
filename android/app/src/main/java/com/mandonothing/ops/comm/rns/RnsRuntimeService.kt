package com.mandonothing.ops.comm.rns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.mandonothing.ops.MainActivity
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.aware.RnsAwareDiagnosticsSnapshot
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.mandonothing.ops.comm.ptt.RnsPttTransportBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android Foreground Service owning the RNS / LXMF node lifecycle across Activity recreations
 * and service restarts (aligned with VoiceSessionService and Android 14/15 FGS rules).
 */
class RnsRuntimeService : Service() {
    internal inner class LocalBinder : Binder() {
        val service: RnsRuntimeService get() = this@RnsRuntimeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: RnsLocalStore
    private lateinit var coordinator: RnsRuntimeCoordinator
    private lateinit var integrationController: RnsAwareIntegrationController
    private var maintenanceJob: Job? = null
    private var stateForwardJob: Job? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        store = RnsLocalStore(applicationContext)
        val engine = engineFactory?.invoke(applicationContext) ?: UniFFIRnsCoreEngine()
        coordinator = RnsRuntimeCoordinator(
            store = store,
            engine = engine,
        )
        integrationController = RnsAwareIntegrationController(
            coordinator = coordinator,
            engine = engine,
            topologyDiagnostics = topologyDiagnostics,
            scope = serviceScope,
        )
        activeCoordinator = coordinator
        activeIntegration = integrationController
        pttBridge = RnsCorePttBridge(coordinator, engine)
        stateForwardJob = serviceScope.launch {
            launch { coordinator.snapshot.collect { sharedSnapshot.value = it } }
            launch { coordinator.inboundMessages.collect { sharedInbound.value = it } }
            launch { coordinator.peers.collect { sharedPeers.value = it } }
            launch { coordinator.ifacPolicy.collect { sharedIfacPolicy.value = it } }
            launch { coordinator.gatewayReceipts.collect { sharedGatewayReceipts.tryEmit(it) } }
            launch { coordinator.pttFrames.collect { sharedPttFrames.tryEmit(it) } }
            launch { coordinator.pttFloorEvents.collect { sharedPttFloorEvents.tryEmit(it) } }
            coordinator.deliveryReceipts.collect { sharedReceipts.tryEmit(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRuntime(clearPersistedConfig = true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val config = intent?.let(::readConfigFromIntent)
        val started = serviceScope.launch(Dispatchers.IO) {
            val ok = if (config != null) coordinator.start(config) else coordinator.restoreIfConfigured()
            val activeCfg = config ?: coordinator.currentConfig()
            if (ok && activeCfg != null) {
                runCatching {
                    integrationController.startAndroidRadios(applicationContext, activeCfg, serviceScope)
                }
                integrationController.awareCoordinator?.let { aware ->
                    serviceScope.launch {
                        launch { aware.networkState.collect { sharedAwareNetworkState.value = it } }
                        aware.diagnostics.collect { sharedAwareDiagnostics.value = it }
                    }
                }
            }
        }
        startMaintenanceLoop(started)
        return START_STICKY
    }

    private fun startMaintenanceLoop(startupJob: Job) {
        maintenanceJob?.cancel()
        maintenanceJob = serviceScope.launch(Dispatchers.IO) {
            startupJob.join()
            while (true) {
                delay(MAINTENANCE_INTERVAL_MS)
                runCatching { coordinator.retryPendingOutbox() }
                runCatching { integrationController.pollMultiHopTelemetry() }
            }
        }
    }

    private fun promoteToForeground(): Boolean {
        if (foreground) return true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RNS 多跳通信", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Protocol X RNS 运行中")
            .setContentText("正在维护私有网状身份与 LXMF 消息队列")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(SERVICE_ID, notification)
            }
            foreground = true
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun stopRuntime(clearPersistedConfig: Boolean) {
        maintenanceJob?.cancel()
        maintenanceJob = null
        coordinator.stop(clearPersistedConfig)
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    override fun onDestroy() {
        stopRuntime(clearPersistedConfig = false)
        runCatching { integrationController.close() }
        stateForwardJob?.cancel()
        activeCoordinator = null
        activeIntegration = null
        pttBridge = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun readConfigFromIntent(intent: Intent): RnsRuntimeConfig? {
        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return null
        val event = intent.getStringExtra(EXTRA_EVENT) ?: return null
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: channel
        val ifacNet = intent.getStringExtra(EXTRA_IFAC_NET)?.takeIf { it.isNotBlank() }
        val ifacPass = intent.getStringExtra(EXTRA_IFAC_PASS)?.takeIf { it.isNotBlank() }
        val ifacPolicy = if (ifacNet != null && ifacPass != null) {
            RnsIfacPolicy(
                networkName = ifacNet,
                passphrase = ifacPass,
                ifacSizeBytes = intent.getIntExtra(EXTRA_IFAC_SIZE, 16),
                requirePrivateIfac = intent.getBooleanExtra(EXTRA_IFAC_PRIVATE, true),
            )
        } else null
        return RnsRuntimeConfig(
            accountKey = account,
            eventId = event,
            channelId = channel,
            channelName = title,
            actorId = intent.getStringExtra(EXTRA_ACTOR).orEmpty(),
            storageDir = intent.getStringExtra(EXTRA_STORAGE_DIR)
                ?: java.io.File(filesDir, "rns-runtime/core").absolutePath,
            lxmfDisplayName = intent.getStringExtra(EXTRA_DISPLAY_NAME),
            tcpListenAddr = intent.getStringExtra(EXTRA_TCP_LISTEN),
            tcpConnectAddrs = intent.getStringArrayListExtra(EXTRA_TCP_CONNECT).orEmpty(),
            propagationNodeHashHex = intent.getStringExtra(EXTRA_PROPAGATION_NODE),
            enableTransportRelay = intent.getBooleanExtra(EXTRA_RELAY, false),
            ifac = ifacPolicy,
            revokedIdentityHashes = intent.getStringArrayListExtra(EXTRA_REVOKED).orEmpty(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "protocol_x_rns_runtime"
        private const val SERVICE_ID = 7821
        private const val MAINTENANCE_INTERVAL_MS = 10_000L
        private const val ACTION_START = "com.mandonothing.ops.rns.START"
        private const val ACTION_STOP = "com.mandonothing.ops.rns.STOP"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ACTOR = "actor"
        private const val EXTRA_STORAGE_DIR = "storage_dir"
        private const val EXTRA_DISPLAY_NAME = "display_name"
        private const val EXTRA_TCP_LISTEN = "tcp_listen"
        private const val EXTRA_TCP_CONNECT = "tcp_connect"
        private const val EXTRA_PROPAGATION_NODE = "propagation_node"
        private const val EXTRA_RELAY = "relay"
        private const val EXTRA_IFAC_NET = "ifac_net"
        private const val EXTRA_IFAC_PASS = "ifac_pass"
        private const val EXTRA_IFAC_SIZE = "ifac_size"
        private const val EXTRA_IFAC_PRIVATE = "ifac_private"
        private const val EXTRA_REVOKED = "revoked"

        @Volatile
        internal var engineFactory: ((Context) -> RnsCoreEngine)? = null

        @Volatile
        internal var activeCoordinator: RnsRuntimeCoordinator? = null
            private set

        @Volatile
        internal var activeIntegration: RnsAwareIntegrationController? = null
            private set

        @Volatile
        internal var pttBridge: RnsPttTransportBridge? = null
            private set

        internal val topologyDiagnostics = RnsTopologyDiagnostics()

        private val sharedSnapshot = MutableStateFlow(RnsRuntimeSnapshot())
        private val sharedInbound = MutableStateFlow<List<LxmfInboundRecord>>(emptyList())
        private val sharedPeers = MutableStateFlow<List<RnsPeerRecord>>(emptyList())
        private val sharedReceipts = MutableSharedFlow<LxmfDeliveryReceipt>(extraBufferCapacity = 64)
        private val sharedIfacPolicy = MutableStateFlow<RnsIfacPolicy?>(null)
        private val sharedGatewayReceipts = MutableSharedFlow<org.json.JSONObject>(extraBufferCapacity = 32)
        private val sharedAwareNetworkState = MutableStateFlow(CommNetworkState())
        private val sharedAwareDiagnostics = MutableStateFlow(RnsAwareDiagnosticsSnapshot())
        private val sharedPttFrames = MutableSharedFlow<RnsCoreEvent.PttFrameReceived>(extraBufferCapacity = 128)
        private val sharedPttFloorEvents = MutableSharedFlow<RnsCoreEvent.PttFloorEvent>(extraBufferCapacity = 32)

        internal val snapshot: StateFlow<RnsRuntimeSnapshot> = sharedSnapshot.asStateFlow()
        internal val inboundMessages: StateFlow<List<LxmfInboundRecord>> = sharedInbound.asStateFlow()
        internal val peers: StateFlow<List<RnsPeerRecord>> = sharedPeers.asStateFlow()
        internal val deliveryReceipts: SharedFlow<LxmfDeliveryReceipt> = sharedReceipts.asSharedFlow()
        internal val ifacPolicy: StateFlow<RnsIfacPolicy?> = sharedIfacPolicy.asStateFlow()
        internal val gatewayReceipts: SharedFlow<org.json.JSONObject> = sharedGatewayReceipts.asSharedFlow()
        internal val awareNetworkState: StateFlow<CommNetworkState> = sharedAwareNetworkState.asStateFlow()
        internal val awareDiagnostics: StateFlow<RnsAwareDiagnosticsSnapshot> = sharedAwareDiagnostics.asStateFlow()
        internal val pttFrames: SharedFlow<RnsCoreEvent.PttFrameReceived> = sharedPttFrames.asSharedFlow()
        internal val pttFloorEvents: SharedFlow<RnsCoreEvent.PttFloorEvent> = sharedPttFloorEvents.asSharedFlow()

        internal fun start(context: Context, config: RnsRuntimeConfig): Boolean = runCatching {
            val intent = Intent(context, RnsRuntimeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ACCOUNT, config.accountKey)
                .putExtra(EXTRA_EVENT, config.eventId)
                .putExtra(EXTRA_CHANNEL, config.channelId)
                .putExtra(EXTRA_TITLE, config.channelName)
                .putExtra(EXTRA_ACTOR, config.actorId)
                .putExtra(EXTRA_STORAGE_DIR, config.storageDir)
                .putExtra(EXTRA_DISPLAY_NAME, config.lxmfDisplayName)
                .putExtra(EXTRA_TCP_LISTEN, config.tcpListenAddr)
                .putStringArrayListExtra(EXTRA_TCP_CONNECT, ArrayList(config.tcpConnectAddrs))
                .putExtra(EXTRA_PROPAGATION_NODE, config.propagationNodeHashHex)
                .putExtra(EXTRA_RELAY, config.enableTransportRelay)
                .putExtra(EXTRA_IFAC_NET, config.ifac?.networkName)
                .putExtra(EXTRA_IFAC_PASS, config.ifac?.passphrase)
                .putExtra(EXTRA_IFAC_SIZE, config.ifac?.ifacSizeBytes ?: 16)
                .putExtra(EXTRA_IFAC_PRIVATE, config.ifac?.requirePrivateIfac ?: true)
                .putStringArrayListExtra(EXTRA_REVOKED, ArrayList(config.revokedIdentityHashes))
            context.startForegroundService(intent)
        }.isSuccess

        internal fun stop(context: Context) {
            context.startService(Intent(context, RnsRuntimeService::class.java).setAction(ACTION_STOP))
        }
    }
}
