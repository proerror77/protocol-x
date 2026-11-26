package com.mandonothing.ops.comm.aware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportAdapter
import com.mandonothing.ops.comm.CommTransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstraction over Android `WifiAwareManager` + NAN discovery + `WifiAwareNetworkSpecifier`
 * data-path negotiation so the adapter can run on live Android hardware or deterministic JVM tests.
 */
internal interface WifiAwareDriver<R : Any, C : Any> : Closeable {
    interface Events {
        fun onAvailabilityChanged(available: Boolean)
        fun onPeerDiscovered(peerId: String, remoteToken: Int, rssiDbm: Int?, isBridgeCandidate: Boolean)
        fun onPeerLost(peerId: String)
        fun onPeerHello(peerId: String, remoteToken: Int, rssiDbm: Int?, useUdp: Boolean)
        fun onPeerReady(peerId: String, useUdp: Boolean)
        fun onDataPathConnected(peerId: String, transport: RnsPeerLinkSession.Transport)
        fun onDataPathFailed(peerId: String)
        fun onDataPathLost(peerId: String)
    }

    fun isAvailable(): Boolean
    fun start(events: Events)
    fun sendHello(peerId: String, useUdp: Boolean): Boolean
    fun sendReady(peerId: String, useUdp: Boolean): Boolean
    fun buildDataPathRequest(
        peerId: String,
        port: Int?,
        useUdp: Boolean,
        events: Events,
        isRegistrationCurrent: (C) -> Boolean,
    ): Pair<R, C>?
    fun stop()
}

/**
 * RNS-04 Pluggable Wi-Fi Aware Interface Adapter.
 *
 * Bridges Android `WifiAwareManager` (NAN publish/subscribe + link-local IPv6 TCP/UDP data path)
 * into [RnsFrameBridge], replacing `NearbyRadios`'s fixed `MAX_LINKS = 3` with configurable
 * capacity + RSSI/RTT/staleness-aware peer rotation/pruning, exposing per-link quality metrics,
 * and guaranteeing zero `ConnectivityManager.NetworkCallback` or socket leaks.
 */
internal class RnsWifiAwareAdapter<R : Any, C : Any>(
    private val bridge: RnsFrameBridge,
    private val localDeviceToken: Int,
    private val config: RnsLinkConfig = RnsLinkConfig(),
    private val interfaceTag: String = DEFAULT_INTERFACE_TAG,
    private val driver: WifiAwareDriver<R, C>,
    callbackDriver: NetworkRegistrationDriver<R, C>,
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val ifacGuard: RnsIfacFrameGuard? = null,
    private val outboundCodec: (CommOutboundMessage) -> ByteArray? = ::defaultEncodeOutbound,
) : CommTransportAdapter, RnsCustomInterfaceOutboundHandler, Closeable {

    override val kind: CommTransportKind = CommTransportKind.WIFI_AWARE

    private val capacity = PeerCapacityManager(RnsRadioKind.WIFI_AWARE, config)
    private val callbackRegistry = TrackedNetworkCallbackRegistry<String, R, C>(callbackDriver)
    private val socketRegistry = TrackedSocketRegistry<String>()
    private val sessions = ConcurrentHashMap<String, RnsPeerLinkSession>()
    private val peerTokens = ConcurrentHashMap<String, Int>()
    private val peerUdpFlags = ConcurrentHashMap<String, Boolean>()
    private val pendingListeners = ConcurrentHashMap<String, Closeable>()

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val bridgeAttached = AtomicBoolean(false)
    @Volatile private var hardwareAvailable = false
    @Volatile private var lastActivityMs = 0L
    @Volatile private var lastDeliveryMs = 0L
    @Volatile private var maintenanceFuture: ScheduledFuture<*>? = null

    private val mutableLinkStatus = MutableStateFlow(
        CommLinkStatus(
            kind = CommTransportKind.WIFI_AWARE,
            reachability = CommReachability.UNAVAILABLE,
            statusLabel = "未启用",
        ),
    )
    override val linkStatus: StateFlow<CommLinkStatus> = mutableLinkStatus.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(RnsAwareDiagnosticsSnapshot())
    val diagnostics: StateFlow<RnsAwareDiagnosticsSnapshot> = mutableDiagnostics.asStateFlow()

    private val mutablePathCosts = MutableStateFlow<List<RnsPathCost>>(emptyList())
    val pathCosts: StateFlow<List<RnsPathCost>> = mutablePathCosts.asStateFlow()

    private val driverEvents = object : WifiAwareDriver.Events {
        override fun onAvailabilityChanged(available: Boolean) {
            if (closed.get()) return
            hardwareAvailable = available
            if (!available) {
                teardownAllLinks(detachBridge = true)
            } else {
                attachBridgeIfNeeded()
            }
            publishState()
        }

        override fun onPeerDiscovered(
            peerId: String,
            remoteToken: Int,
            rssiDbm: Int?,
            isBridgeCandidate: Boolean,
        ) {
            if (closed.get() || !hardwareAvailable) return
            if (ifacGuard?.isPeerAllowed(peerId, nowMs = clockMs()) == false) return
            peerTokens[peerId] = remoteToken
            capacity.onPeerDiscovered(
                peerId = peerId,
                rssiDbm = rssiDbm,
                isBridgeCandidate = isBridgeCandidate,
                nowMs = clockMs(),
            )
            // Deterministic initiator election matching personal-rns keeper duel.
            if (Integer.compareUnsigned(localDeviceToken, remoteToken) < 0) {
                tryEngagePeer(peerId, useUdp = config.enableUdpDatagramPath)
            }
            publishState()
        }

        override fun onPeerLost(peerId: String) {
            if (closed.get()) return
            peerTokens.remove(peerId)
            peerUdpFlags.remove(peerId)
            capacity.removePeer(peerId)
            dropPeer(peerId, failed = false)
        }

        override fun onPeerHello(peerId: String, remoteToken: Int, rssiDbm: Int?, useUdp: Boolean) {
            if (closed.get() || !hardwareAvailable) return
            if (ifacGuard?.isPeerAllowed(peerId, nowMs = clockMs()) == false) return
            if (sessions.containsKey(peerId) || callbackRegistry.contains(peerId)) return
            val now = clockMs()
            peerTokens[peerId] = remoteToken
            peerUdpFlags[peerId] = useUdp
            capacity.onPeerDiscovered(peerId = peerId, rssiDbm = rssiDbm, nowMs = now)
            if (!admitPeer(peerId, now)) return

            if (driver is AndroidWifiAwareDriver) {
                openResponderDataPath(peerId, useUdp)
            } else {
                // Non-Android / simulated driver accepts port=null and pushes transport via onDataPathConnected.
                val req = driver.buildDataPathRequest(
                    peerId = peerId,
                    port = 0,
                    useUdp = useUdp,
                    events = this,
                    isRegistrationCurrent = { cb -> callbackRegistry.isCurrent(peerId, cb) },
                )
                if (req == null || !callbackRegistry.register(peerId, req.first, req.second, config.ndpRequestTimeoutMs)) {
                    dropPeer(peerId, failed = true)
                } else {
                    driver.sendReady(peerId, useUdp)
                }
            }
            publishState()
        }

        override fun onPeerReady(peerId: String, useUdp: Boolean) {
            if (closed.get() || !hardwareAvailable) return
            if (sessions.containsKey(peerId) || callbackRegistry.contains(peerId)) return
            val now = clockMs()
            peerUdpFlags[peerId] = useUdp
            if (!admitPeer(peerId, now)) return
            val req = driver.buildDataPathRequest(
                peerId = peerId,
                port = null,
                useUdp = useUdp,
                events = this,
                isRegistrationCurrent = { cb -> callbackRegistry.isCurrent(peerId, cb) },
            )
            if (req == null || !callbackRegistry.register(peerId, req.first, req.second, config.ndpRequestTimeoutMs)) {
                dropPeer(peerId, failed = true)
            }
            publishState()
        }

        override fun onDataPathConnected(peerId: String, transport: RnsPeerLinkSession.Transport) {
            if (closed.get() || ifacGuard?.isPeerAllowed(peerId, nowMs = clockMs()) == false) {
                runCatching { transport.close() }
                return
            }
            val underlying = when (transport) {
                is RnsPeerLinkSession.Transport.Stream -> transport.socket
                is RnsPeerLinkSession.Transport.Datagram -> transport.socket
            }
            if (!socketRegistry.replace(peerId, underlying)) {
                runCatching { transport.close() }
                dropPeer(peerId, failed = true)
                return
            }
            val now = clockMs()
            capacity.markConnected(peerId, now)
            val session = RnsPeerLinkSession(
                peerId = peerId,
                radio = RnsRadioKind.WIFI_AWARE,
                transport = transport,
                maxFrameBytes = config.maxFrameBytes,
                probeIntervalMs = config.probeIntervalMs,
                capacity = capacity,
                ioExecutor = ioExecutor,
                scheduler = scheduler,
                clockMs = clockMs,
                nanoClock = nanoClock,
                onInboundFrame = ::handleInboundFrame,
                onMetricsUpdated = ::handleMetricsUpdated,
                onClosed = { closedPeerId, failed -> dropPeer(closedPeerId, failed = failed) },
            )
            sessions.put(peerId, session)?.close()
            session.start()
            capacity.metricsFor(peerId)?.let { pushBridgeMetrics(it, connected = true) }
            lastActivityMs = now
            publishState()
        }

        override fun onDataPathFailed(peerId: String) {
            dropPeer(peerId, failed = true)
        }

        override fun onDataPathLost(peerId: String) {
            dropPeer(peerId, failed = false)
        }
    }

    /** Starts NAN discovery, attaches to [RnsFrameBridge], and schedules peer pruning/rotation. */
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        hardwareAvailable = driver.isAvailable()
        if (hardwareAvailable) {
            attachBridgeIfNeeded()
        }
        driver.start(driverEvents)
        maintenanceFuture = scheduler.scheduleAtFixedRate(
            { runCatching { runMaintenance() } },
            config.probeIntervalMs.coerceAtLeast(250L),
            config.probeIntervalMs.coerceAtLeast(250L),
            TimeUnit.MILLISECONDS,
        )
        publishState()
    }

    /** Re-evaluates hardware availability and runs a maintenance cycle immediately. */
    fun refresh() {
        if (closed.get()) return
        val available = driver.isAvailable()
        if (available != hardwareAvailable) {
            driverEvents.onAvailabilityChanged(available)
        } else if (available) {
            runMaintenance()
        }
    }

    /** Runs deterministic peer pruning + anti-partition rotation and returns maintenance actions. */
    fun runMaintenance(): PeerCapacityManager.MaintenanceActions {
        if (closed.get()) return PeerCapacityManager.MaintenanceActions()
        val now = clockMs()
        val actions = capacity.pruneAndRotate(now)
        actions.evictPeers.forEach { evictId ->
            dropPeer(evictId, failed = false, skipCapacityUpdate = true)
        }
        actions.admitPeers.forEach { admitId ->
            tryEngagePeer(admitId, useUdp = peerUdpFlags[admitId] ?: config.enableUdpDatagramPath)
        }
        publishState()
        return actions
    }

    /** Sends an already-encoded RNS frame to active Wi-Fi Aware peers (or returns false if none). */
    fun sendRawFrame(frame: ByteArray): Boolean = onOutboundFrame(interfaceTag, frame)

    override fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean {
        if (closed.get() || interfaceTag != this.interfaceTag || frame.isEmpty()) return false
        val wireFrame = if (ifacGuard != null) {
            ifacGuard.sealOutboundFrame(frame, clockMs()) ?: return false
        } else {
            frame
        }
        val activeSessions = sessions.values.toList()
        if (activeSessions.isEmpty()) return false
        var sentCount = 0
        for (session in activeSessions) {
            if (session.sendFrame(wireFrame)) {
                sentCount++
            }
        }
        if (sentCount > 0) {
            lastDeliveryMs = clockMs()
            publishState()
        }
        return sentCount > 0
    }

    override fun onInterfaceDetached(interfaceTag: String) {
        if (interfaceTag == this.interfaceTag) {
            bridgeAttached.set(false)
        }
    }

    override fun supports(outbound: CommOutboundMessage): Boolean =
        outbound.kind != CommEnvelopeKind.SYSTEM_EVENT

    override suspend fun send(outbound: CommOutboundMessage): CommSendResult {
        if (closed.get() || !hardwareAvailable) {
            return CommSendResult.Rejected("Wi-Fi Aware 不可用", retryable = true)
        }
        val payload = outboundCodec(outbound)
            ?: return CommSendResult.Rejected("不支持的消息负载", retryable = false)
        if (sessions.isEmpty()) {
            return CommSendResult.Queued(outbound.id, "正在等待 Wi-Fi Aware 邻居连接")
        }
        return if (onOutboundFrame(interfaceTag, payload)) {
            CommSendResult.Accepted(outbound.id, CommTransportKind.WIFI_AWARE)
        } else {
            CommSendResult.Rejected("Wi-Fi Aware 发送失败", retryable = true)
        }
    }

    fun leakCounters(): RnsLeakCounters {
        val (activeCb, cbTotals) = callbackRegistry.snapshot()
        val (activeSock, sockOpened, sockClosed) = socketRegistry.counts()
        return RnsLeakCounters(
            activeNetworkCallbacks = activeCb,
            totalNetworkCallbacksRegistered = cbTotals.first,
            totalNetworkCallbacksUnregistered = cbTotals.second,
            activeSockets = activeSock,
            totalSocketsOpened = sockOpened,
            totalSocketsClosed = sockClosed,
        )
    }

    fun activePeerMetrics(): List<RnsPeerQualityMetrics> = capacity.activePeers()

    fun standbyPeerMetrics(): List<RnsPeerQualityMetrics> = capacity.standbyPeers()

    private fun attachBridgeIfNeeded() {
        if (closed.get() || !bridgeAttached.compareAndSet(false, true)) return
        bridge.attachInterface(
            interfaceTag = interfaceTag,
            kind = RnsCustomInterfaceKind.WIFI_AWARE,
            hwMtu = WIFI_AWARE_HW_MTU,
            bitrateBps = RnsRadioKind.WIFI_AWARE.defaultBitrateBps,
            outboundHandler = this,
        )
    }

    private fun detachBridgeIfNeeded() {
        if (bridgeAttached.compareAndSet(true, false)) {
            runCatching { bridge.detachInterface(interfaceTag) }
        }
    }

    private fun tryEngagePeer(peerId: String, useUdp: Boolean) {
        if (closed.get() || !hardwareAvailable) return
        if (sessions.containsKey(peerId) || callbackRegistry.contains(peerId)) return
        val now = clockMs()
        if (!admitPeer(peerId, now)) return
        if (!driver.sendHello(peerId, useUdp)) {
            capacity.markDisconnected(peerId, failed = true, nowMs = now)
        }
    }

    private fun admitPeer(peerId: String, nowMs: Long): Boolean {
        return when (val decision = capacity.evaluateAdmission(peerId, nowMs)) {
            is PeerCapacityManager.AdmissionDecision.Admitted -> true
            is PeerCapacityManager.AdmissionDecision.PreemptedAndAdmitted -> {
                dropPeer(decision.evictedPeerId, failed = false, skipCapacityUpdate = true)
                true
            }
            is PeerCapacityManager.AdmissionDecision.RotatedAndAdmitted -> {
                dropPeer(decision.rotatedOutPeerId, failed = false, skipCapacityUpdate = true)
                true
            }
            is PeerCapacityManager.AdmissionDecision.QueuedStandby,
            is PeerCapacityManager.AdmissionDecision.Suppressed -> false
        }
    }

    private fun openResponderDataPath(peerId: String, useUdp: Boolean) {
        if (useUdp) {
            val udpSocket = runCatching { DatagramSocket(0).apply { soTimeout = 15_000 } }.getOrNull()
            if (udpSocket == null || !socketRegistry.register(peerId, udpSocket)) {
                runCatching { udpSocket?.close() }
                dropPeer(peerId, failed = true)
                return
            }
            pendingListeners[peerId] = udpSocket
            val req = driver.buildDataPathRequest(
                peerId = peerId,
                port = udpSocket.localPort,
                useUdp = true,
                events = driverEvents,
                isRegistrationCurrent = { cb -> callbackRegistry.isCurrent(peerId, cb) },
            )
            if (req == null || !callbackRegistry.register(peerId, req.first, req.second, config.ndpRequestTimeoutMs)) {
                pendingListeners.remove(peerId)
                dropPeer(peerId, failed = true)
                return
            }
            driver.sendReady(peerId, useUdp = true)
            return
        }

        val server = runCatching { ServerSocket(0).apply { soTimeout = 15_000 } }.getOrNull()
        if (server == null || !socketRegistry.register(peerId, server)) {
            runCatching { server?.close() }
            dropPeer(peerId, failed = true)
            return
        }
        pendingListeners[peerId] = server
        val req = driver.buildDataPathRequest(
            peerId = peerId,
            port = server.localPort,
            useUdp = false,
            events = driverEvents,
            isRegistrationCurrent = { cb -> callbackRegistry.isCurrent(peerId, cb) },
        )
        if (req == null || !callbackRegistry.register(peerId, req.first, req.second, config.ndpRequestTimeoutMs)) {
            pendingListeners.remove(peerId)
            dropPeer(peerId, failed = true)
            return
        }
        driver.sendReady(peerId, useUdp = false)
        ioExecutor.execute {
            val socket = runCatching { server.accept() }.getOrNull()
            pendingListeners.remove(peerId, server)
            socketRegistry.closeResource(peerId, server)
            if (socket == null || closed.get() || !callbackRegistry.contains(peerId) || !socket.inetAddress.isLinkLocalAddress) {
                runCatching { socket?.close() }
                dropPeer(peerId, failed = socket == null)
                return@execute
            }
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            driverEvents.onDataPathConnected(
                peerId,
                RnsPeerLinkSession.Transport.Stream(socket, socket.getInputStream(), socket.getOutputStream()),
            )
        }
    }

    /** Immediately evicts a peer (e.g. upon RNS-05 revocation) and closes all sockets/callbacks. */
    fun evictPeer(peerId: String) {
        capacity.removePeer(peerId)
        dropPeer(peerId, failed = true, skipCapacityUpdate = true)
    }

    private fun handleInboundFrame(peerId: String, payload: ByteArray) {
        if (closed.get()) return
        val verified = if (ifacGuard != null) {
            val unwrapped = ifacGuard.verifyInboundFrame(peerId, payload, clockMs())
            if (unwrapped == null) {
                if (!ifacGuard.isPeerAllowed(peerId, nowMs = clockMs())) {
                    evictPeer(peerId)
                }
                return
            }
            unwrapped
        } else {
            payload
        }
        lastActivityMs = clockMs()
        bridge.pushInboundFrame(interfaceTag, verified)
        publishState()
    }

    private fun handleMetricsUpdated(metrics: RnsPeerQualityMetrics) {
        if (closed.get()) return
        pushBridgeMetrics(metrics, connected = sessions.containsKey(metrics.peerId))
        publishState()
    }

    private fun pushBridgeMetrics(metrics: RnsPeerQualityMetrics, connected: Boolean) {
        bridge.updateLinkMetrics(
            interfaceTag,
            RnsBridgeLinkMetrics(
                peerId = metrics.peerId,
                connected = connected,
                rssiDbm = metrics.rssiDbm,
                rttMs = metrics.rttMs,
                estimatedBandwidthBps = metrics.estimatedBandwidthBps,
            ),
        )
    }

    private fun dropPeer(
        peerId: String,
        failed: Boolean,
        skipCapacityUpdate: Boolean = false,
    ) {
        callbackRegistry.unregister(peerId)
        pendingListeners.remove(peerId)?.let { runCatching { it.close() } }
        sessions.remove(peerId)?.close()
        socketRegistry.closeKey(peerId)
        if (!skipCapacityUpdate) {
            capacity.markDisconnected(peerId, failed = failed, nowMs = clockMs())
        }
        if (bridgeAttached.get()) {
            capacity.metricsFor(peerId)?.let { pushBridgeMetrics(it, connected = false) }
        }
        publishState()
    }

    private fun teardownAllLinks(detachBridge: Boolean) {
        val activeIds = (sessions.keys + pendingListeners.keys).toSet()
        callbackRegistry.unregisterAll(permanent = false)
        activeIds.forEach { id ->
            pendingListeners.remove(id)?.let { runCatching { it.close() } }
            sessions.remove(id)?.close()
        }
        socketRegistry.closeAll(permanent = false)
        if (detachBridge) {
            detachBridgeIfNeeded()
        }
    }

    private fun publishState() {
        val now = clockMs()
        val activePeers = capacity.activePeers()
        val stats = capacity.stats()
        val costs = activePeers.map { it.pathCost(now, config.stalePeerTimeoutMs) }.sortedBy { it.cost }
        val bestCost = costs.firstOrNull()
        val reachability = when {
            closed.get() || !hardwareAvailable -> CommReachability.UNAVAILABLE
            activePeers.isNotEmpty() -> CommReachability.CONNECTED
            else -> CommReachability.DISCOVERING
        }
        mutablePathCosts.value = costs
        mutableLinkStatus.value = CommLinkStatus(
            kind = CommTransportKind.WIFI_AWARE,
            reachability = reachability,
            statusLabel = RnsPathCostCalculator.formatStatusLabel(reachability, bestCost, activePeers.size),
            peerCount = activePeers.size,
            lastActivityMs = lastActivityMs,
            lastDeliveryMs = lastDeliveryMs,
            lastDeliveryKind = if (lastDeliveryMs > 0L) CommTransportKind.WIFI_AWARE else null,
        )
        mutableDiagnostics.value = RnsAwareDiagnosticsSnapshot(
            radioReachability = mapOf(RnsRadioKind.WIFI_AWARE to reachability),
            activePeers = activePeers,
            standbyPeerCount = stats.standbyCount,
            prunedPeerCount = stats.totalPruned,
            rotatedPeerCount = stats.totalRotated,
            bestPathCostByRadio = bestCost?.let { mapOf(RnsRadioKind.WIFI_AWARE to it) } ?: emptyMap(),
            bleFallbackActive = false,
            leakCounters = leakCounters(),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        maintenanceFuture?.cancel(false)
        maintenanceFuture = null
        callbackRegistry.unregisterAll(permanent = true)
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        pendingListeners.values.toList().forEach { runCatching { it.close() } }
        pendingListeners.clear()
        socketRegistry.closeAll(permanent = true)
        detachBridgeIfNeeded()
        runCatching { driver.close() }
        capacity.clear()
        publishState()
    }

    companion object {
        const val DEFAULT_INTERFACE_TAG = "wifi-aware"
        const val WIFI_AWARE_HW_MTU = 1196

        /** Constructs a production Android [RnsWifiAwareAdapter] backed by [WifiAwareManager]. */
        fun create(
            context: Context,
            bridge: RnsFrameBridge,
            serviceName: String,
            wifiPassphrase: String,
            localDeviceToken: Int,
            config: RnsLinkConfig = RnsLinkConfig(),
            interfaceTag: String = DEFAULT_INTERFACE_TAG,
        ): RnsWifiAwareAdapter<NetworkRequest, ConnectivityManager.NetworkCallback> =
            RnsWifiAwareAdapter(
                bridge = bridge,
                localDeviceToken = localDeviceToken,
                config = config,
                interfaceTag = interfaceTag,
                driver = AndroidWifiAwareDriver(
                    context = context,
                    serviceName = serviceName,
                    wifiPassphrase = wifiPassphrase,
                    localDeviceToken = localDeviceToken,
                    preferUdp = config.enableUdpDatagramPath,
                ),
                callbackDriver = AndroidConnectivityDriver(
                    requireNotNull(context.getSystemService(ConnectivityManager::class.java)) {
                        "ConnectivityManager unavailable"
                    },
                ),
            )

        internal fun defaultEncodeOutbound(outbound: CommOutboundMessage): ByteArray? {
            if (outbound.audioBytes != null) return outbound.audioBytes
            if (outbound.photoBytes != null) return outbound.photoBytes
            return outbound.text.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        }
    }
}

/**
 * Production Android `WifiAwareDriver` bridging Android `WifiAwareManager` NAN discovery and
 * link-local IPv6 TCP/UDP data paths into [RnsWifiAwareAdapter].
 */
@SuppressLint("MissingPermission")
internal class AndroidWifiAwareDriver(
    private val context: Context,
    private val serviceName: String,
    private val wifiPassphrase: String,
    private val localDeviceToken: Int,
    private val preferUdp: Boolean = false,
) : WifiAwareDriver<NetworkRequest, ConnectivityManager.NetworkCallback> {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val localInfo = ByteBuffer.allocate(4).putInt(localDeviceToken).array()
    private val peers = ConcurrentHashMap<String, PeerHandle>()
    private val peerHandles = ConcurrentHashMap<PeerHandle, String>()
    private val sequence = AtomicInteger(0)

    @Volatile private var session: WifiAwareSession? = null
    @Volatile private var publisher: PublishDiscoverySession? = null
    @Volatile private var subscriber: SubscribeDiscoverySession? = null
    @Volatile private var stopped = false

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return false
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching { manager?.isAvailable == true }.getOrDefault(false)
    }

    override fun start(events: WifiAwareDriver.Events) {
        stopped = false
        val mgr = manager ?: run {
            events.onAvailabilityChanged(false)
            return
        }
        runCatching {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(attached: WifiAwareSession) {
                    if (stopped) {
                        attached.close()
                        return
                    }
                    session = attached
                    events.onAvailabilityChanged(true)
                    startDiscovery(attached, events)
                }

                override fun onAttachFailed() {
                    events.onAvailabilityChanged(false)
                }
            }, main)
        }.onFailure { events.onAvailabilityChanged(false) }
    }

    private fun startDiscovery(attached: WifiAwareSession, events: WifiAwareDriver.Events) {
        attached.publish(
            PublishConfig.Builder()
                .setServiceName(serviceName)
                .setServiceSpecificInfo(localInfo)
                .build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(value: PublishDiscoverySession) {
                    publisher = value
                }

                override fun onSessionTerminated() {
                    publisher = null
                }

                override fun onMessageReceived(peer: PeerHandle, payload: ByteArray) {
                    if (stopped || payload.size < 5 || payload[0] != MSG_HELLO) return
                    val remoteToken = ByteBuffer.wrap(payload, 1, 4).int
                    val useUdp = payload.size >= 6 && payload[5] == 1.toByte()
                    val peerId = registerPeer(peer, remoteToken)
                    events.onPeerHello(peerId, remoteToken, rssiDbm = null, useUdp = useUdp)
                }
            },
            main,
        )
        attached.subscribe(
            SubscribeConfig.Builder().setServiceName(serviceName).build(),
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(value: SubscribeDiscoverySession) {
                    subscriber = value
                }

                override fun onSessionTerminated() {
                    subscriber = null
                }

                override fun onServiceDiscovered(
                    peer: PeerHandle,
                    info: ByteArray,
                    filter: MutableList<ByteArray>,
                ) {
                    if (stopped || info.size != 4) return
                    val remoteToken = ByteBuffer.wrap(info).int
                    val peerId = registerPeer(peer, remoteToken)
                    events.onPeerDiscovered(peerId, remoteToken, rssiDbm = null, isBridgeCandidate = false)
                }

                override fun onMessageReceived(peer: PeerHandle, payload: ByteArray) {
                    if (stopped || payload.isEmpty() || payload[0] != MSG_READY) return
                    val peerId = peerHandles[peer] ?: return
                    val useUdp = payload.size >= 2 && payload[1] == 1.toByte()
                    events.onPeerReady(peerId, useUdp)
                }

                override fun onServiceLost(peer: PeerHandle, reason: Int) {
                    val peerId = peerHandles.remove(peer) ?: return
                    peers.remove(peerId)
                    events.onPeerLost(peerId)
                }
            },
            main,
        )
    }

    private fun registerPeer(handle: PeerHandle, remoteToken: Int): String {
        val peerId = "aware:${Integer.toUnsignedString(remoteToken)}"
        peers[peerId] = handle
        peerHandles[handle] = peerId
        return peerId
    }

    override fun sendHello(peerId: String, useUdp: Boolean): Boolean {
        val handle = peers[peerId] ?: return false
        val sub = subscriber ?: return false
        val payload = ByteBuffer.allocate(6)
            .put(MSG_HELLO)
            .putInt(localDeviceToken)
            .put(if (useUdp || preferUdp) 1 else 0)
            .array()
        return runCatching { sub.sendMessage(handle, sequence.getAndIncrement(), payload) }.isSuccess
    }

    override fun sendReady(peerId: String, useUdp: Boolean): Boolean {
        val handle = peers[peerId] ?: return false
        val pub = publisher ?: return false
        val payload = byteArrayOf(MSG_READY, if (useUdp) 1 else 0)
        return runCatching { pub.sendMessage(handle, sequence.getAndIncrement(), payload) }.isSuccess
    }

    override fun buildDataPathRequest(
        peerId: String,
        port: Int?,
        useUdp: Boolean,
        events: WifiAwareDriver.Events,
        isRegistrationCurrent: (ConnectivityManager.NetworkCallback) -> Boolean,
    ): Pair<NetworkRequest, ConnectivityManager.NetworkCallback>? {
        val handle = peers[peerId] ?: return null
        val discovery: DiscoverySession = if (port != null) publisher ?: return null else subscriber ?: return null
        val builder = WifiAwareNetworkSpecifier.Builder(discovery, handle)
            .setPskPassphrase(wifiPassphrase)
        if (port != null) {
            builder.setPort(port)
            if (useUdp) {
                builder.setTransportProtocol(OsConstants.IPPROTO_UDP)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(builder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            private val connecting = AtomicBoolean(false)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val address: InetAddress = info.peerIpv6Addr ?: return
                val remotePort = info.port
                if (port != null && !useUdp) {
                    // Responder TCP accepts via ServerSocket.
                    return
                }
                if (remotePort <= 0 || !connecting.compareAndSet(false, true)) return
                io.execute {
                    if (stopped || !isRegistrationCurrent(this)) return@execute
                    if (useUdp) {
                        val udp = runCatching {
                            DatagramSocket(0).apply {
                                network.bindSocket(this)
                                soTimeout = 10_000
                            }
                        }.getOrNull()
                        if (udp == null || stopped || !isRegistrationCurrent(this)) {
                            runCatching { udp?.close() }
                            if (!stopped) events.onDataPathFailed(peerId)
                        } else {
                            events.onDataPathConnected(
                                peerId,
                                RnsPeerLinkSession.Transport.Datagram(udp, address, remotePort),
                            )
                        }
                    } else {
                        val socket = runCatching {
                            network.socketFactory.createSocket().apply {
                                tcpNoDelay = true
                                soTimeout = 10_000
                                connect(InetSocketAddress(address, remotePort), 6_000)
                            }
                        }.getOrNull()
                        if (socket == null || stopped || !isRegistrationCurrent(this)) {
                            runCatching { socket?.close() }
                            if (!stopped) events.onDataPathFailed(peerId)
                        } else {
                            events.onDataPathConnected(
                                peerId,
                                RnsPeerLinkSession.Transport.Stream(socket, socket.getInputStream(), socket.getOutputStream()),
                            )
                        }
                    }
                }
            }

            override fun onUnavailable() {
                if (!stopped && isRegistrationCurrent(this)) events.onDataPathFailed(peerId)
            }

            override fun onLost(network: Network) {
                if (!stopped && isRegistrationCurrent(this)) events.onDataPathLost(peerId)
            }
        }
        return request to callback
    }

    override fun stop() {
        stopped = true
        runCatching { publisher?.close() }
        runCatching { subscriber?.close() }
        runCatching { session?.close() }
        publisher = null
        subscriber = null
        session = null
        peers.clear()
        peerHandles.clear()
    }

    override fun close() {
        stop()
        io.shutdownNow()
    }

    private companion object {
        const val MSG_HELLO: Byte = 1
        const val MSG_READY: Byte = 2
    }
}
