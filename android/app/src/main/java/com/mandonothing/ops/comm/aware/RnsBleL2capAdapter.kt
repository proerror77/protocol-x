package com.mandonothing.ops.comm.aware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstraction over Android BLE L2CAP advertising, scanning, server accept, and outbound L2CAP connect.
 */
internal interface BleL2capDriver : Closeable {
    interface Events {
        fun onPeerScanned(peerId: String, remoteToken: Int, psm: Int, rssiDbm: Int, handle: Any?)
        fun onInboundConnected(peerId: String, transport: RnsPeerLinkSession.Transport)
        fun onOutboundConnected(peerId: String, transport: RnsPeerLinkSession.Transport)
        fun onConnectFailed(peerId: String)
        fun onRadioStopped()
    }

    fun isAvailable(): Boolean
    fun start(events: Events): Boolean
    fun connect(peerId: String, psm: Int, handle: Any?, events: Events): Closeable?
    fun stop()
}

/**
 * RNS-04 BLE L2CAP Fallback Adapter.
 *
 * Registers BLE L2CAP (`NearbyRadios.Ble`) into [RnsFrameBridge] as a low-bandwidth control/text
 * RNS interface whenever Wi-Fi Aware is unavailable (or under simultaneous backup policy), with
 * configurable capacity + RSSI/RTT/staleness-aware peer rotation and leak-free socket teardown.
 */
internal class RnsBleL2capAdapter(
    private val bridge: RnsFrameBridge,
    private val localDeviceToken: Int,
    private val config: RnsLinkConfig = RnsLinkConfig(),
    private val interfaceTag: String = DEFAULT_INTERFACE_TAG,
    private val driver: BleL2capDriver,
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val outboundCodec: (CommOutboundMessage) -> ByteArray? = ::defaultEncodeControlOutbound,
) : CommTransportAdapter, RnsCustomInterfaceOutboundHandler, Closeable {

    constructor(
        context: Context,
        bridge: RnsFrameBridge,
        serviceUuid: UUID,
        localDeviceToken: Int,
        config: RnsLinkConfig = RnsLinkConfig(),
        interfaceTag: String = DEFAULT_INTERFACE_TAG,
    ) : this(
        bridge = bridge,
        localDeviceToken = localDeviceToken,
        config = config,
        interfaceTag = interfaceTag,
        driver = AndroidBleL2capDriver(
            context = context,
            serviceUuid = serviceUuid,
            localDeviceToken = localDeviceToken,
        ),
    )

    override val kind: CommTransportKind = CommTransportKind.BLE_L2CAP

    private val capacity = PeerCapacityManager(RnsRadioKind.BLE_L2CAP, config)
    private val socketRegistry = TrackedSocketRegistry<String>()
    private val sessions = ConcurrentHashMap<String, RnsPeerLinkSession>()
    private val scannedPeers = ConcurrentHashMap<String, ScannedPeer>()

    private val closed = AtomicBoolean(false)
    private val radioRunning = AtomicBoolean(false)
    private val bridgeAttached = AtomicBoolean(false)
    @Volatile private var wifiAwareConnected = false
    @Volatile private var wifiAwareAvailable = false
    @Volatile private var lastActivityMs = 0L
    @Volatile private var lastDeliveryMs = 0L
    @Volatile private var maintenanceFuture: ScheduledFuture<*>? = null

    private data class ScannedPeer(val peerId: String, val psm: Int, val handle: Any?)

    private val mutableLinkStatus = MutableStateFlow(
        CommLinkStatus(
            kind = CommTransportKind.BLE_L2CAP,
            reachability = CommReachability.UNAVAILABLE,
            statusLabel = "未启用",
        ),
    )
    override val linkStatus: StateFlow<CommLinkStatus> = mutableLinkStatus.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(RnsAwareDiagnosticsSnapshot())
    val diagnostics: StateFlow<RnsAwareDiagnosticsSnapshot> = mutableDiagnostics.asStateFlow()

    private val mutablePathCosts = MutableStateFlow<List<RnsPathCost>>(emptyList())
    val pathCosts: StateFlow<List<RnsPathCost>> = mutablePathCosts.asStateFlow()

    val isBridgeRegistered: Boolean
        get() = bridgeAttached.get()

    private val driverEvents = object : BleL2capDriver.Events {
        override fun onPeerScanned(peerId: String, remoteToken: Int, psm: Int, rssiDbm: Int, handle: Any?) {
            if (closed.get() || !radioRunning.get()) return
            if (psm <= 0 || remoteToken == localDeviceToken) return
            val now = clockMs()
            scannedPeers[peerId] = ScannedPeer(peerId, psm, handle)
            capacity.onPeerDiscovered(peerId = peerId, rssiDbm = rssiDbm, nowMs = now)
            if (Integer.compareUnsigned(localDeviceToken, remoteToken) >= 0) return
            tryConnectPeer(peerId, psm, handle, now)
            publishState()
        }

        override fun onInboundConnected(peerId: String, transport: RnsPeerLinkSession.Transport) {
            attachTransport(peerId, transport)
        }

        override fun onOutboundConnected(peerId: String, transport: RnsPeerLinkSession.Transport) {
            attachTransport(peerId, transport)
        }

        override fun onConnectFailed(peerId: String) {
            dropPeer(peerId, failed = true)
        }

        override fun onRadioStopped() {
            teardownRadio()
            publishState()
        }
    }

    /**
     * Updates Wi-Fi Aware reachability so BLE L2CAP automatically registers or detaches as an RNS
     * fallback interface according to [RnsLinkConfig.bleFallbackPolicy].
     */
    fun onWifiAwareStateChanged(available: Boolean, activeWifiAwarePeers: Int) {
        if (closed.get()) return
        wifiAwareAvailable = available
        wifiAwareConnected = available && activeWifiAwarePeers > 0
        reconcileFallbackState()
    }

    /** Explicitly reconciles whether BLE L2CAP should be registered and running right now. */
    fun start() {
        reconcileFallbackState()
    }

    private fun shouldRunFallback(): Boolean {
        if (closed.get() || !config.enableBleFallback) return false
        return when (config.bleFallbackPolicy) {
            BleFallbackPolicy.WHEN_WIFI_AWARE_UNAVAILABLE -> !wifiAwareAvailable || !wifiAwareConnected
            BleFallbackPolicy.SIMULTANEOUS_BACKUP -> true
        }
    }

    private fun reconcileFallbackState() {
        val wantActive = shouldRunFallback() && driver.isAvailable()
        if (wantActive && !radioRunning.get()) {
            if (driver.start(driverEvents)) {
                radioRunning.set(true)
                attachBridgeIfNeeded()
                startMaintenanceIfNeeded()
            }
        } else if (!wantActive && radioRunning.get()) {
            teardownRadio()
        }
        publishState()
    }

    private fun startMaintenanceIfNeeded() {
        if (maintenanceFuture != null) return
        maintenanceFuture = scheduler.scheduleAtFixedRate(
            { runCatching { runMaintenance() } },
            config.probeIntervalMs.coerceAtLeast(250L),
            config.probeIntervalMs.coerceAtLeast(250L),
            TimeUnit.MILLISECONDS,
        )
    }

    fun runMaintenance(): PeerCapacityManager.MaintenanceActions {
        if (closed.get() || !radioRunning.get()) return PeerCapacityManager.MaintenanceActions()
        val now = clockMs()
        val actions = capacity.pruneAndRotate(now)
        actions.evictPeers.forEach { evictId ->
            dropPeer(evictId, failed = false, skipCapacityUpdate = true)
        }
        actions.admitPeers.forEach { admitId ->
            scannedPeers[admitId]?.let { tryConnectPeer(it.peerId, it.psm, it.handle, now) }
        }
        publishState()
        return actions
    }

    private fun tryConnectPeer(peerId: String, psm: Int, handle: Any?, nowMs: Long) {
        if (sessions.containsKey(peerId)) return
        val admitted = when (val decision = capacity.evaluateAdmission(peerId, nowMs)) {
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
        if (!admitted) return
        val pendingSocket = driver.connect(peerId, psm, handle, driverEvents)
        if (pendingSocket != null && !sessions.containsKey(peerId) && !socketRegistry.replace(peerId, pendingSocket)) {
            runCatching { pendingSocket.close() }
        }
    }

    private fun attachTransport(peerId: String, transport: RnsPeerLinkSession.Transport) {
        if (closed.get() || !radioRunning.get()) {
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
            radio = RnsRadioKind.BLE_L2CAP,
            transport = transport,
            maxFrameBytes = config.maxFrameBytes.coerceAtMost(BLE_MAX_FRAME_BYTES),
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

    private fun handleInboundFrame(peerId: String, payload: ByteArray) {
        if (closed.get() || !bridgeAttached.get()) return
        lastActivityMs = clockMs()
        bridge.pushInboundFrame(interfaceTag, payload)
        publishState()
    }

    private fun handleMetricsUpdated(metrics: RnsPeerQualityMetrics) {
        if (closed.get() || !bridgeAttached.get()) return
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

    fun sendRawFrame(frame: ByteArray): Boolean = onOutboundFrame(interfaceTag, frame)

    override fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean {
        if (closed.get() || interfaceTag != this.interfaceTag || frame.isEmpty()) return false
        if (frame.size > BLE_MAX_FRAME_BYTES) return false
        val active = sessions.values.toList()
        if (active.isEmpty()) return false
        var sentCount = 0
        for (session in active) {
            if (session.sendFrame(frame)) sentCount++
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

    /** BLE L2CAP accepts low-bandwidth control & text envelopes only (no bulk audio/large photo). */
    override fun supports(outbound: CommOutboundMessage): Boolean {
        if (outbound.requiresBulkMediaTransport) return false
        return outbound.kind in setOf(
            CommEnvelopeKind.CHANNEL_TEXT,
            CommEnvelopeKind.PRIVATE_ENCRYPTED,
            CommEnvelopeKind.ASSISTANCE_ALERT,
            CommEnvelopeKind.CONTROL_ACK,
            CommEnvelopeKind.DELIVERY_RECEIPT,
        )
    }

    override suspend fun send(outbound: CommOutboundMessage): CommSendResult {
        if (!supports(outbound)) {
            return CommSendResult.Rejected("蓝牙 L2CAP 仅支持低带宽控制与文字消息", retryable = false)
        }
        if (closed.get() || !radioRunning.get()) {
            return CommSendResult.Rejected("蓝牙 L2CAP 备援未激活", retryable = true)
        }
        val payload = outboundCodec(outbound)
            ?: return CommSendResult.Rejected("不支持的消息负载", retryable = false)
        if (payload.size > BLE_MAX_FRAME_BYTES) {
            return CommSendResult.Rejected("消息超出蓝牙 L2CAP 单帧限制", retryable = false)
        }
        if (sessions.isEmpty()) {
            return CommSendResult.Queued(outbound.id, "正在等待蓝牙 L2CAP 邻居连接")
        }
        return if (onOutboundFrame(interfaceTag, payload)) {
            CommSendResult.Accepted(outbound.id, CommTransportKind.BLE_L2CAP)
        } else {
            CommSendResult.Rejected("蓝牙 L2CAP 发送失败", retryable = true)
        }
    }

    fun leakCounters(): RnsLeakCounters {
        val (activeSock, sockOpened, sockClosed) = socketRegistry.counts()
        return RnsLeakCounters(
            activeSockets = activeSock,
            totalSocketsOpened = sockOpened,
            totalSocketsClosed = sockClosed,
        )
    }

    fun activePeerMetrics(): List<RnsPeerQualityMetrics> = capacity.activePeers()

    private fun attachBridgeIfNeeded() {
        if (closed.get() || !bridgeAttached.compareAndSet(false, true)) return
        bridge.attachInterface(
            interfaceTag = interfaceTag,
            kind = RnsCustomInterfaceKind.BLE_L2CAP,
            hwMtu = BLE_HW_MTU,
            bitrateBps = RnsRadioKind.BLE_L2CAP.defaultBitrateBps,
            outboundHandler = this,
        )
    }

    private fun detachBridgeIfNeeded() {
        if (bridgeAttached.compareAndSet(true, false)) {
            runCatching { bridge.detachInterface(interfaceTag) }
        }
    }

    private fun dropPeer(
        peerId: String,
        failed: Boolean,
        skipCapacityUpdate: Boolean = false,
    ) {
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

    private fun teardownRadio() {
        radioRunning.set(false)
        maintenanceFuture?.cancel(false)
        maintenanceFuture = null
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        socketRegistry.closeAll(permanent = false)
        scannedPeers.clear()
        runCatching { driver.stop() }
        detachBridgeIfNeeded()
        capacity.clear()
    }

    private fun publishState() {
        val now = clockMs()
        val activePeers = capacity.activePeers()
        val stats = capacity.stats()
        val costs = activePeers.map { it.pathCost(now, config.stalePeerTimeoutMs) }.sortedBy { it.cost }
        val bestCost = costs.firstOrNull()
        val reachability = when {
            closed.get() || !radioRunning.get() -> CommReachability.UNAVAILABLE
            activePeers.isNotEmpty() -> CommReachability.CONNECTED
            else -> CommReachability.DISCOVERING
        }
        val fallbackActive = bridgeAttached.get()
        mutablePathCosts.value = costs
        mutableLinkStatus.value = CommLinkStatus(
            kind = CommTransportKind.BLE_L2CAP,
            reachability = reachability,
            statusLabel = RnsPathCostCalculator.formatStatusLabel(
                reachability = reachability,
                bestCost = bestCost,
                peerCount = activePeers.size,
                fallbackActive = fallbackActive,
            ),
            peerCount = activePeers.size,
            lastActivityMs = lastActivityMs,
            lastDeliveryMs = lastDeliveryMs,
            lastDeliveryKind = if (lastDeliveryMs > 0L) CommTransportKind.BLE_L2CAP else null,
        )
        mutableDiagnostics.value = RnsAwareDiagnosticsSnapshot(
            radioReachability = mapOf(RnsRadioKind.BLE_L2CAP to reachability),
            activePeers = activePeers,
            standbyPeerCount = stats.standbyCount,
            prunedPeerCount = stats.totalPruned,
            rotatedPeerCount = stats.totalRotated,
            bestPathCostByRadio = bestCost?.let { mapOf(RnsRadioKind.BLE_L2CAP to it) } ?: emptyMap(),
            bleFallbackActive = fallbackActive,
            leakCounters = leakCounters(),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        teardownRadio()
        socketRegistry.closeAll(permanent = true)
        runCatching { driver.close() }
        publishState()
    }

    companion object {
        const val DEFAULT_INTERFACE_TAG = "ble-l2cap"
        const val BLE_HW_MTU = 500
        const val BLE_MAX_FRAME_BYTES = 3_200

        internal fun defaultEncodeControlOutbound(outbound: CommOutboundMessage): ByteArray? {
            if (outbound.requiresBulkMediaTransport) return null
            return outbound.text.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        }
    }
}

/** Production Android BLE L2CAP driver using `BluetoothManager`, LE scan/advertise, and L2CAP CoC. */
@SuppressLint("MissingPermission")
internal class AndroidBleL2capDriver(
    private val context: Context,
    serviceUuid: UUID,
    private val localDeviceToken: Int,
) : BleL2capDriver {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val service = ParcelUuid(serviceUuid)
    private val io = Executors.newCachedThreadPool()

    @Volatile private var stopped = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var activeEvents: BleL2capDriver.Events? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            if (!stopped) activeEvents?.onRadioStopped()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (stopped) return
            val data = result.scanRecord?.getServiceData(service) ?: return
            if (data.size != 6) return
            val reader = ByteBuffer.wrap(data)
            val remoteToken = reader.int
            val psm = reader.short.toInt() and 0xffff
            val peerId = "ble:${Integer.toUnsignedString(remoteToken)}"
            activeEvents?.onPeerScanned(peerId, remoteToken, psm, result.rssi, result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!stopped) activeEvents?.onRadioStopped()
        }
    }

    override fun isAvailable(): Boolean {
        val bt = adapter ?: return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val granted = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return granted && runCatching { bt.isEnabled }.getOrDefault(false)
    }

    override fun start(events: BleL2capDriver.Events): Boolean {
        val bt = adapter ?: return false
        val advertiser = bt.bluetoothLeAdvertiser ?: return false
        val scanner = bt.bluetoothLeScanner ?: return false
        stopped = false
        activeEvents = events
        return runCatching {
            val server = bt.listenUsingInsecureL2capChannel()
            serverSocket = server
            val bytes = ByteBuffer.allocate(6)
                .putInt(localDeviceToken)
                .putShort(server.psm.toShort())
                .array()
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .build(),
                AdvertiseData.Builder().addServiceData(service, bytes).build(),
                advertiseCallback,
            )
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceData(service, byteArrayOf()).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            io.execute {
                var inboundSeq = 0
                while (!stopped && !Thread.currentThread().isInterrupted) {
                    val accepted = runCatching { server.accept() }.getOrNull() ?: break
                    if (stopped) {
                        runCatching { accepted.close() }
                        break
                    }
                    val addr = accepted.remoteDevice?.address ?: "inbound-${inboundSeq++}"
                    events.onInboundConnected(
                        peerId = "ble:$addr",
                        transport = RnsPeerLinkSession.Transport.Stream(
                            accepted,
                            accepted.inputStream,
                            accepted.outputStream,
                        ),
                    )
                }
            }
            true
        }.getOrElse {
            stop()
            false
        }
    }

    override fun connect(peerId: String, psm: Int, handle: Any?, events: BleL2capDriver.Events): Closeable? {
        val device = handle as? BluetoothDevice ?: return null
        val socket: BluetoothSocket = runCatching { device.createInsecureL2capChannel(psm) }.getOrNull() ?: return null
        io.execute {
            val ok = runCatching { socket.connect() }.isSuccess
            if (!ok || stopped) {
                runCatching { socket.close() }
                if (!stopped) events.onConnectFailed(peerId)
            } else {
                events.onOutboundConnected(
                    peerId,
                    RnsPeerLinkSession.Transport.Stream(socket, socket.inputStream, socket.outputStream),
                )
            }
        }
        return socket
    }

    override fun stop() {
        stopped = true
        activeEvents = null
        val bt = adapter
        runCatching { bt?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { bt?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    override fun close() {
        stop()
        io.shutdownNow()
    }
}
