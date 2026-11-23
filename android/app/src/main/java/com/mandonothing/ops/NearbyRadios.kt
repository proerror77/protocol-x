package com.mandonothing.ops

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.os.ParcelUuid
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

internal fun nearbyPermissions(context: Context): List<String> = buildList {
    val features = context.packageManager
    if (Build.VERSION.SDK_INT >= 31 && features.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (Build.VERSION.SDK_INT >= 33 && features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (Build.VERSION.SDK_INT in 29..32) add(Manifest.permission.ACCESS_FINE_LOCATION)
}

internal enum class NearbyRadio { WIFI, BLE }

internal class NearbyRadios(
    private val context: Context,
    private val messages: NearbyMessages,
    private val onReceive: (List<NearbyMessage>, NearbyRadio) -> Unit,
    private val enabled: Set<NearbyRadio> = NearbyRadio.entries.toSet(),
    private val liveVoice: NearbyLiveVoice? = null,
    private val voiceAuth: NearbyVoiceAuthentication? = null,
    private val onDiagnostics: (NearbyDiagnosticSnapshot) -> Unit = {},
    private val onStatus: (NearbyRadio, Boolean, Boolean) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val sockets = mutableSetOf<Closeable>()
    private val diagnostics = NearbyLinkDiagnostics()
    private var closed = false
    private var wifi: Aware? = null
    private var ble: Ble? = null

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun refresh() {
        if (closed || Build.VERSION.SDK_INT < 29) return
        val wifiManager = context.getSystemService(WifiAwareManager::class.java)
        val wifiSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val wifiGranted = allowed(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION)
        val wifiAvailable = NearbyRadio.WIFI in enabled && wifiSupported && wifiGranted && runCatching { wifiManager?.isAvailable == true }.getOrDefault(false)
        if (wifiAvailable && wifi == null && wifiManager != null) {
            val radio = Aware(wifiManager)
            wifi = radio
            radio.start()
        }
        if (!wifiAvailable) {
            wifi?.close()
            wifi = null
            onStatus(NearbyRadio.WIFI, false, false)
        }
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bleGranted = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE).all(::allowed)
        else allowed(Manifest.permission.ACCESS_FINE_LOCATION)
        val bleAvailable = NearbyRadio.BLE in enabled && bleGranted && runCatching { bt?.isEnabled == true }.getOrDefault(false)
        if (bleAvailable && ble == null) {
            val radio = Ble()
            ble = radio
            radio.start()
        }
        if (!bleAvailable) {
            ble?.close()
            ble = null
            onStatus(NearbyRadio.BLE, false, false)
        }
    }

    private fun exchange(socket: Closeable, input: InputStream, output: OutputStream,
                         radio: NearbyRadio, initiator: Boolean, finished: () -> Unit) {
        val wifiLink = radio == NearbyRadio.WIFI
        if (closed) { socket.close(); return }
        sockets.add(socket)
        executor.execute {
            var inventory = emptySet<String>()
            var reportedInventory = emptySet<String>()
            var peerLive = false
            val read = DataInputStream(input)
            val write = DataOutputStream(output)
            val expire = Runnable { runCatching { socket.close() } }
            try {
                while (!Thread.currentThread().isInterrupted) {
                    main.postDelayed(expire, 12_000)
                    if (initiator) writeFrame(write, radio, true, inventory, peerLive)
                    val packet = messages.receive(read.readNearby(), wifiLink, initiator)
                    inventory = packet.inventory
                    val capabilityChanged = packet.supportsLive != peerLive
                    peerLive = packet.supportsLive
                    if (wifiLink && peerLive && packet.livePackets.isNotEmpty()) {
                        val verified = voiceAuth?.verify(packet.peerNode, packet.livePackets).orEmpty()
                        if (verified.isNotEmpty()) liveVoice?.receive(verified)
                    }
                    if (capabilityChanged || inventory != reportedInventory || packet.new.isNotEmpty() || packet.newlyDelivered > 0) {
                        reportedInventory = inventory
                        main.post {
                            if (!closed) {
                                onStatus(radio, true, true)
                                diagnostics.observe(socket, radio, packet.peerNode, packet.newlyDelivered > 0,
                                    packet.lastDeliveryRoute, System.currentTimeMillis(), packet.supportsLive)
                                onDiagnostics(diagnostics.snapshot())
                                if (packet.new.isNotEmpty()) onReceive(packet.new, radio)
                            }
                        }
                    }
                    if (!initiator) writeFrame(write, radio, false, inventory, peerLive)
                    main.removeCallbacks(expire)
                    if (initiator) Thread.sleep(when {
                        wifiLink && peerLive && liveVoice?.active == true -> 160
                        wifiLink && peerLive -> 420
                        else -> 1_700
                    }.toLong())
                }
            } catch (_: Exception) {
                // Links are opportunistic; persisted messages will be retried on the next connection.
            } finally {
                main.removeCallbacks(expire)
                runCatching { socket.close() }
                main.post {
                    sockets.remove(socket)
                    diagnostics.remove(socket)
                    if (!closed) {
                        onDiagnostics(diagnostics.snapshot())
                        finished()
                    }
                }
            }
        }
    }

    private fun writeFrame(output: DataOutputStream, radio: NearbyRadio, initiator: Boolean,
                           inventory: Set<String>, peerLive: Boolean) {
        val wifi = radio == NearbyRadio.WIFI
        val supportsLive = wifi && messages.voiceEnabled && liveVoice != null && voiceAuth != null
        val live = if (supportsLive && peerLive) liveVoice?.outbound().orEmpty() else emptyList()
        val frame = messages.makeFrame(wifi, initiator, inventory, live, supportsLive)
        output.writeNearby(frame.data)
        messages.frameSent(frame.ownMessageIds, radio)
    }

    override fun close() {
        closed = true
        wifi?.close()
        ble?.close()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private inner class Aware(private val manager: WifiAwareManager) : Closeable {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private var session: WifiAwareSession? = null
        private var active = true
        private val localId = ByteBuffer.allocate(4).putInt(messages.device).array()
        private val requests = mutableMapOf<PeerHandle, ConnectivityManager.NetworkCallback>()
        private val discoveryPeers = mutableMapOf<PeerHandle, SubscribeDiscoverySession>()
        private val helloPending = mutableSetOf<PeerHandle>()
        private val listeners = mutableMapOf<PeerHandle, ServerSocket>()
        private val connections = mutableMapOf<PeerHandle, Socket>()
        private var sequence = 0

        fun start() {
            runCatching {
                manager.attach(object : AttachCallback() {
                    override fun onAttached(attached: WifiAwareSession) {
                        if (!active) { attached.close(); return }
                        session = attached
                        onStatus(NearbyRadio.WIFI, true, false)
                        runCatching { discover(attached) }.onFailure { restart() }
                    }

                    override fun onAttachFailed() = restart()
                }, main)
            }.onFailure { restart() }
        }

        private fun discover(attached: WifiAwareSession) {
            val name = messages.group.serviceName
            attached.publish(PublishConfig.Builder().setServiceName(name).setServiceSpecificInfo(localId).build(),
                object : DiscoverySessionCallback() {
                    private var publisher: PublishDiscoverySession? = null
                    override fun onPublishStarted(value: PublishDiscoverySession) { publisher = value }
                    override fun onSessionConfigFailed() = restart()
                    override fun onSessionTerminated() = restart()
                    override fun onMessageReceived(peer: PeerHandle, payload: ByteArray) {
                        val discovery = publisher ?: return
                        if (!active || !payload.contentEquals(HELLO) || peer in requests || requests.size >= MAX_LINKS) return
                        val server = runCatching { ServerSocket(0).apply { soTimeout = 15_000 } }.getOrNull() ?: return
                        listeners[peer] = server
                        request(discovery, peer, server.localPort)
                        runCatching { discovery.sendMessage(peer, sequence++, READY) }
                        executor.execute {
                            val socket = runCatching { server.accept() }.getOrNull()
                            runCatching { server.close() }
                            main.post {
                                listeners.remove(peer)
                                if (!active || peer !in requests || socket == null || !socket.inetAddress.isLinkLocalAddress) {
                                    runCatching { socket?.close() }
                                    drop(peer)
                                } else connect(peer, socket, false)
                            }
                        }
                    }
                }, main)
            attached.subscribe(SubscribeConfig.Builder().setServiceName(name).build(),
                object : DiscoverySessionCallback() {
                    private var subscriber: SubscribeDiscoverySession? = null
                    override fun onSubscribeStarted(value: SubscribeDiscoverySession) { subscriber = value }
                    override fun onSessionConfigFailed() = restart()
                    override fun onSessionTerminated() = restart()
                    override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray, filter: MutableList<ByteArray>) {
                        val discovery = subscriber ?: return
                        if (!active || info.size != 4 ||
                            Integer.compareUnsigned(messages.device, ByteBuffer.wrap(info).int) >= 0) return
                        discoveryPeers[peer] = discovery
                        hello(peer)
                    }

                    override fun onMessageReceived(peer: PeerHandle, payload: ByteArray) {
                        val discovery = subscriber ?: return
                        if (active && peer in discoveryPeers && payload.contentEquals(READY) && peer !in requests &&
                            requests.size < MAX_LINKS) request(discovery, peer, null)
                    }

                    override fun onServiceLost(peer: PeerHandle, reason: Int) {
                        discoveryPeers.remove(peer)
                        helloPending.remove(peer)
                        drop(peer)
                    }
                }, main)
        }

        private fun hello(peer: PeerHandle) {
            val discovery = discoveryPeers[peer] ?: return
            if (!active || peer in requests || requests.size >= MAX_LINKS || !helloPending.add(peer)) return
            runCatching { discovery.sendMessage(peer, sequence++, HELLO) }
            main.postDelayed({
                helloPending.remove(peer)
                hello(peer)
            }, 6_000)
        }

        private fun request(discovery: DiscoverySession, peer: PeerHandle, port: Int?) {
            runCatching {
                val builder = WifiAwareNetworkSpecifier.Builder(discovery, peer).setPskPassphrase(messages.group.wifiPassword)
                if (port != null) builder.setPort(port)
                val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(builder.build()).build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    private var connecting = false
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        if (port != null) return
                        val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                        val address = info.peerIpv6Addr ?: return
                        if (info.port <= 0) return
                        main.post {
                            if (!active || connecting || requests[peer] !== this) return@post
                            connecting = true
                            executor.execute {
                                val socket = runCatching {
                                    network.socketFactory.createSocket().apply {
                                        try { connect(InetSocketAddress(address, info.port), 6_000) }
                                        catch (error: Exception) { close(); throw error }
                                    }
                                }.getOrNull()
                                main.post {
                                    if (!active || requests[peer] !== this || socket == null) {
                                        runCatching { socket?.close() }
                                        if (requests[peer] === this) drop(peer)
                                    } else connect(peer, socket, true)
                                }
                            }
                        }
                    }
                    override fun onUnavailable() { main.post { if (requests[peer] === this) drop(peer) } }
                    override fun onLost(network: Network) { main.post { if (requests[peer] === this) drop(peer) } }
                }
                requests[peer] = callback
                connectivity.requestNetwork(request, callback, 12_000)
            }.onFailure { drop(peer) }
        }

        private fun connect(peer: PeerHandle, socket: Socket, initiator: Boolean) {
            runCatching {
                socket.tcpNoDelay = true
                socket.soTimeout = 10_000
                connections[peer] = socket
                exchange(socket, socket.getInputStream(), socket.getOutputStream(), NearbyRadio.WIFI, initiator) {
                    if (connections[peer] === socket) drop(peer)
                }
            }.onFailure { runCatching { socket.close() }; drop(peer) }
        }

        private fun drop(peer: PeerHandle) {
            val callback = requests.remove(peer)
            if (callback != null) runCatching { connectivity.unregisterNetworkCallback(callback) }
            runCatching { listeners.remove(peer)?.close() }
            runCatching { connections.remove(peer)?.close() }
            if (active) {
                if (connections.isEmpty()) onStatus(NearbyRadio.WIFI, true, false)
                main.postDelayed({ discoveryPeers.keys.toList().forEach(::hello) }, 2_000)
            }
        }

        private fun restart() {
            if (!active || closed || wifi !== this) return
            close()
            wifi = null
            onStatus(NearbyRadio.WIFI, false, false)
            main.postDelayed({ refresh() }, 2_000)
        }

        override fun close() {
            active = false
            requests.values.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
            requests.clear()
            listeners.values.forEach { runCatching { it.close() } }
            listeners.clear()
            connections.values.forEach { runCatching { it.close() } }
            connections.clear()
            session?.close()
            session = null
        }
    }

    @SuppressLint("MissingPermission")
    private inner class Ble : Closeable {
        private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        private val service = ParcelUuid(messages.group.radioUuid)
        private val scanner = adapter.bluetoothLeScanner
        private val advertiser = adapter.bluetoothLeAdvertiser
        private val links = mutableMapOf<Int, BluetoothSocket>()
        private val inbound = mutableSetOf<BluetoothSocket>()
        private var active = true
        private var listener: android.bluetooth.BluetoothServerSocket? = null
        private val advertising = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { restart() }
        }
        private val scanning = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(service) ?: return
                if (!active || data.size != 6 || links.size >= MAX_LINKS) return
                val reader = ByteBuffer.wrap(data)
                val node = reader.int
                val psm = reader.short.toInt() and 0xffff
                if (psm == 0 || node == messages.device || node in links) return
                // One connection per pair. Either device may accept links while initiating others.
                if (Integer.compareUnsigned(messages.device, node) >= 0) return
                connect(result.device, node, psm)
            }

            override fun onScanFailed(errorCode: Int) { restart() }
        }

        fun start() {
            runCatching {
                val server = adapter.listenUsingInsecureL2capChannel()
                listener = server
                check(advertiser != null && scanner != null)
                val bytes = ByteBuffer.allocate(6).putInt(messages.device).putShort(server.psm.toShort()).array()
                advertiser.startAdvertising(AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(),
                    AdvertiseData.Builder().addServiceData(service, bytes).build(), advertising)
                scanner.startScan(listOf(ScanFilter.Builder().setServiceData(service, byteArrayOf()).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanning)
                onStatus(NearbyRadio.BLE, true, false)
                executor.execute {
                    while (!Thread.currentThread().isInterrupted) {
                        val accepted = runCatching { server.accept() }.getOrNull() ?: break
                        main.post {
                            if (!active || inbound.size >= MAX_LINKS) runCatching { accepted.close() }
                            else {
                                inbound.add(accepted)
                                connectSocket(accepted, false) {
                                    inbound.remove(accepted)
                                    if (links.isEmpty() && inbound.isEmpty()) onStatus(NearbyRadio.BLE, true, false)
                                }
                            }
                        }
                    }
                }
            }.onFailure { restart() }
        }

        private fun connect(device: BluetoothDevice, node: Int, psm: Int) {
            val socket = runCatching { device.createInsecureL2capChannel(psm) }.getOrNull() ?: return
            links[node] = socket
            val timeout = Runnable { runCatching { socket.close() } }
            main.postDelayed(timeout, 8_000)
            executor.execute {
                val ok = runCatching { socket.connect() }.isSuccess
                main.post {
                    main.removeCallbacks(timeout)
                    if (!active || !ok) {
                        runCatching { socket.close() }
                        links.remove(node)
                    } else connectSocket(socket, true) {
                        links.remove(node)
                        if (links.isEmpty() && inbound.isEmpty()) onStatus(NearbyRadio.BLE, true, false)
                    }
                }
            }
        }

        private fun connectSocket(socket: BluetoothSocket, initiator: Boolean, finished: () -> Unit) {
            runCatching { exchange(socket, socket.inputStream, socket.outputStream, NearbyRadio.BLE, initiator, finished) }
                .onFailure { runCatching { socket.close() }; finished() }
        }

        private fun restart() {
            if (!active || closed || ble !== this) return
            close()
            ble = null
            onStatus(NearbyRadio.BLE, false, false)
            main.postDelayed({ refresh() }, 3_000)
        }

        override fun close() {
            active = false
            runCatching { scanner?.stopScan(scanning) }
            runCatching { advertiser?.stopAdvertising(advertising) }
            runCatching { listener?.close() }
            links.values.forEach { runCatching { it.close() } }
            inbound.forEach { runCatching { it.close() } }
            links.clear()
            inbound.clear()
        }
    }

    private companion object {
        val HELLO = byteArrayOf(1)
        val READY = byteArrayOf(2)
        const val MAX_LINKS = 3
    }
}
