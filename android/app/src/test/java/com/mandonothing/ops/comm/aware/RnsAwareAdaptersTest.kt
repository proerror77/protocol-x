package com.mandonothing.ops.comm.aware

import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RnsAwareAdaptersTest {

    private class FakeRnsFrameBridge : RnsFrameBridge {
        data class Attached(
            val kind: RnsCustomInterfaceKind,
            val hwMtu: Int,
            val bitrateBps: Long,
            val handler: RnsCustomInterfaceOutboundHandler,
        )

        val attached = linkedMapOf<String, Attached>()
        val inboundFrames = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val metricsByInterface = linkedMapOf<String, MutableMap<String, RnsBridgeLinkMetrics>>()
        val inboundLatch = AtomicLong(0L)

        override fun attachInterface(
            interfaceTag: String,
            kind: RnsCustomInterfaceKind,
            hwMtu: Int,
            bitrateBps: Long,
            outboundHandler: RnsCustomInterfaceOutboundHandler,
        ) {
            attached[interfaceTag] = Attached(kind, hwMtu, bitrateBps, outboundHandler)
        }

        override fun detachInterface(interfaceTag: String): Boolean {
            val removed = attached.remove(interfaceTag) ?: return false
            removed.handler.onInterfaceDetached(interfaceTag)
            return true
        }

        override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) {
            inboundFrames += interfaceTag to frame
            inboundLatch.incrementAndGet()
        }

        override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) {
            metricsByInterface.getOrPut(interfaceTag) { linkedMapOf() }[metrics.peerId] = metrics
        }

        override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> =
            metricsByInterface[interfaceTag]?.values?.toList().orEmpty()

        fun sendOutbound(interfaceTag: String, frame: ByteArray): Boolean =
            attached[interfaceTag]?.handler?.onOutboundFrame(interfaceTag, frame) == true
    }

    private class FakeNetworkDriver : NetworkRegistrationDriver<String, Any> {
        val active = mutableSetOf<Any>()
        override fun requestNetwork(request: String, callback: Any, timeoutMs: Long) {
            active.add(callback)
        }
        override fun unregisterNetworkCallback(callback: Any) {
            active.remove(callback)
        }
    }

    private class SimulatedWifiAwareDriver(
        var available: Boolean = true,
    ) : WifiAwareDriver<String, Any> {
        var events: WifiAwareDriver.Events? = null
        val peerServers = mutableMapOf<String, ServerSocket>()
        val peerSockets = mutableMapOf<String, Socket>()
        val peerUdpSockets = mutableMapOf<String, DatagramSocket>()

        override fun isAvailable(): Boolean = available

        override fun start(events: WifiAwareDriver.Events) {
            this.events = events
        }

        override fun sendHello(peerId: String, useUdp: Boolean): Boolean {
            events?.onPeerReady(peerId, useUdp)
            return true
        }

        override fun sendReady(peerId: String, useUdp: Boolean): Boolean = true

        override fun buildDataPathRequest(
            peerId: String,
            port: Int?,
            useUdp: Boolean,
            events: WifiAwareDriver.Events,
            isRegistrationCurrent: (Any) -> Boolean,
        ): Pair<String, Any>? {
            val callback = Any()
            val transport = if (useUdp) {
                val peerUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                val localUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                peerUdpSockets[peerId] = peerUdp
                RnsPeerLinkSession.Transport.Datagram(
                    socket = localUdp,
                    remoteAddress = InetAddress.getByName("127.0.0.1"),
                    remotePort = peerUdp.localPort,
                )
            } else {
                val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                peerServers[peerId] = server
                val client = Socket("127.0.0.1", server.localPort)
                val accepted = server.accept()
                peerSockets[peerId] = accepted
                RnsPeerLinkSession.Transport.Stream(
                    socket = client,
                    input = client.getInputStream(),
                    output = client.getOutputStream(),
                )
            }
            events.onDataPathConnected(peerId, transport)
            return "req-$peerId" to callback
        }

        override fun stop() {
            peerSockets.values.forEach { runCatching { it.close() } }
            peerServers.values.forEach { runCatching { it.close() } }
            peerUdpSockets.values.forEach { runCatching { it.close() } }
            peerSockets.clear()
            peerServers.clear()
            peerUdpSockets.clear()
        }

        override fun close() = stop()
    }

    private class SimulatedBleDriver(
        var available: Boolean = true,
    ) : BleL2capDriver {
        var events: BleL2capDriver.Events? = null
        val peerServers = mutableMapOf<String, ServerSocket>()
        val peerSockets = mutableMapOf<String, Socket>()

        override fun isAvailable(): Boolean = available

        override fun start(events: BleL2capDriver.Events): Boolean {
            this.events = events
            return true
        }

        override fun connect(peerId: String, psm: Int, handle: Any?, events: BleL2capDriver.Events): java.io.Closeable? {
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            peerServers[peerId] = server
            val client = Socket("127.0.0.1", server.localPort)
            val accepted = server.accept()
            peerSockets[peerId] = accepted
            events.onOutboundConnected(
                peerId,
                RnsPeerLinkSession.Transport.Stream(client, client.getInputStream(), client.getOutputStream()),
            )
            return client
        }

        override fun stop() {
            peerSockets.values.forEach { runCatching { it.close() } }
            peerServers.values.forEach { runCatching { it.close() } }
            peerSockets.clear()
            peerServers.clear()
        }

        override fun close() = stop()
    }

    @Test
    fun wifiAwareRoutesFramesMeasuresRttAndSupportsMoreThanThreePeersWithoutLeaks() {
        val bridge = FakeRnsFrameBridge()
        val wifiDriver = SimulatedWifiAwareDriver(available = true)
        val callbackDriver = FakeNetworkDriver()
        val nowMs = AtomicLong(10_000L)
        val config = RnsLinkConfig(
            maxActiveWifiAwareLinks = 6,
            probeIntervalMs = 200L,
            stalePeerTimeoutMs = 30_000L,
        )
        val adapter = RnsWifiAwareAdapter(
            bridge = bridge,
            localDeviceToken = 100,
            config = config,
            driver = wifiDriver,
            callbackDriver = callbackDriver,
            clockMs = { nowMs.get() },
        )

        adapter.start()
        assertTrue("wifi-aware" in bridge.attached)
        assertEquals(CommReachability.DISCOVERING, adapter.linkStatus.value.reachability)

        // Connect 5 peers (exceeding legacy MAX_LINKS = 3).
        (1..5).forEach { i ->
            wifiDriver.events!!.onPeerDiscovered(
                peerId = "aware:p$i",
                remoteToken = 200 + i,
                rssiDbm = -50 - i,
                isBridgeCandidate = i == 5,
            )
        }
        assertEquals(5, adapter.activePeerMetrics().size)
        assertEquals(CommReachability.CONNECTED, adapter.linkStatus.value.reachability)
        assertEquals(5, adapter.linkStatus.value.peerCount)

        // Verify outbound RNS frame is delivered to remote TCP socket and inbound frame reaches RnsFrameBridge.
        val peer1Sock = wifiDriver.peerSockets.getValue("aware:p1")
        val outboundFrame = "RNS-PACKET-OUT-1".toByteArray()
        assertTrue(bridge.sendOutbound("wifi-aware", outboundFrame))

        var receivedFrame: ByteArray? = null
        while (receivedFrame == null) {
            when (val f = RnsFrameFramer.readFrame(peer1Sock.getInputStream())) {
                is RnsFrameFramer.Frame.Data -> receivedFrame = f.payload
                is RnsFrameFramer.Frame.Ping -> RnsFrameFramer.writePong(peer1Sock.getOutputStream(), f.sentAtNanos)
                is RnsFrameFramer.Frame.Pong -> Unit
            }
        }
        assertArrayEquals(outboundFrame, receivedFrame)

        // Reply with a PONG (via PING/PONG) and an inbound RNS frame.
        val inboundFrame = "RNS-PACKET-IN-1".toByteArray()
        RnsFrameFramer.writeData(peer1Sock.getOutputStream(), inboundFrame)
        val deadline = System.currentTimeMillis() + 3_000L
        while (bridge.inboundFrames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(1, bridge.inboundFrames.size)
        assertEquals("wifi-aware", bridge.inboundFrames[0].first)
        assertArrayEquals(inboundFrame, bridge.inboundFrames[0].second)

        // Wait briefly for RTT PING/PONG probe or send explicit PING->PONG cycle; verify metrics & CommNetworkState.
        val pongWaitDeadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < pongWaitDeadline) {
            if (peer1Sock.getInputStream().available() > 0) {
                val frame = RnsFrameFramer.readFrame(peer1Sock.getInputStream())
                if (frame is RnsFrameFramer.Frame.Ping) {
                    RnsFrameFramer.writePong(peer1Sock.getOutputStream(), frame.sentAtNanos)
                    break
                }
            }
            Thread.sleep(20)
        }
        Thread.sleep(100)

        val metrics = bridge.getLinkMetrics("wifi-aware")
        assertEquals(5, metrics.size)
        assertTrue(metrics.all { it.connected && it.estimatedBandwidthBps != null && it.rssiDbm != null })
        assertTrue(adapter.pathCosts.value.isNotEmpty())
        val mergedState = RnsPathCostCalculator.mergeIntoNetworkState(
            CommNetworkState(),
            wifiStatus = adapter.linkStatus.value,
            bleStatus = null,
        )
        assertEquals(CommTransportKind.WIFI_AWARE, mergedState.bestRoute)
        assertEquals(5, mergedState.reachablePeerCount)
        assertTrue(mergedState.links[CommTransportKind.WIFI_AWARE]!!.statusLabel.contains("cost="))

        // Drop one peer, then close adapter -> zero leaked callbacks or sockets!
        wifiDriver.events!!.onPeerLost("aware:p5")
        assertEquals(4, adapter.activePeerMetrics().size)

        adapter.close()
        val leaks = adapter.leakCounters()
        assertFalse(leaks.hasLeaks)
        assertEquals(0, leaks.activeNetworkCallbacks)
        assertEquals(0, leaks.activeSockets)
        assertEquals(0, callbackDriver.active.size)
        assertFalse("wifi-aware" in bridge.attached)
    }

    @Test
    fun wifiAwareUdpDatagramDataPathRoutesFramesBidirectionally() {
        val bridge = FakeRnsFrameBridge()
        val wifiDriver = SimulatedWifiAwareDriver(available = true)
        val callbackDriver = FakeNetworkDriver()
        val adapter = RnsWifiAwareAdapter(
            bridge = bridge,
            localDeviceToken = 10,
            config = RnsLinkConfig(enableUdpDatagramPath = true, probeIntervalMs = 0L),
            driver = wifiDriver,
            callbackDriver = callbackDriver,
        )

        adapter.start()
        wifiDriver.events!!.onPeerDiscovered("aware:udp1", remoteToken = 20, rssiDbm = -49, isBridgeCandidate = false)

        val remoteUdp = wifiDriver.peerUdpSockets.getValue("aware:udp1")
        remoteUdp.soTimeout = 3_000

        val outBytes = "UDP-RNS-HELLO".toByteArray()
        assertTrue(bridge.sendOutbound("wifi-aware", outBytes))

        val buf = ByteArray(1024)
        val rxPacket = DatagramPacket(buf, buf.size)
        remoteUdp.receive(rxPacket)
        val decoded = RnsFrameFramer.decodeDatagram(rxPacket.data, rxPacket.length)
        assertTrue(decoded is RnsFrameFramer.Frame.Data)
        assertArrayEquals(outBytes, (decoded as RnsFrameFramer.Frame.Data).payload)

        // Reply back over UDP to adapter's local UDP port.
        val inBytes = "UDP-RNS-REPLY".toByteArray()
        val replyWire = RnsFrameFramer.encodeDatagram(RnsFrameFramer.Frame.Data(inBytes))
        remoteUdp.send(DatagramPacket(replyWire, replyWire.size, rxPacket.address, rxPacket.port))

        val deadline = System.currentTimeMillis() + 3_000L
        while (bridge.inboundFrames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(1, bridge.inboundFrames.size)
        assertArrayEquals(inBytes, bridge.inboundFrames[0].second)

        adapter.close()
        assertFalse(adapter.leakCounters().hasLeaks)
    }

    @Test
    fun bleL2capRegistersAsFallbackWhenWifiAwareUnavailableAndDetachesWhenWifiAwareConnects() = runBlocking {
        val bridge = FakeRnsFrameBridge()
        val wifiDriver = SimulatedWifiAwareDriver(available = false)
        val bleDriver = SimulatedBleDriver(available = true)
        val callbackDriver = FakeNetworkDriver()
        val config = RnsLinkConfig(probeIntervalMs = 0L, bleFallbackPolicy = BleFallbackPolicy.WHEN_WIFI_AWARE_UNAVAILABLE)

        val wifiAdapter = RnsWifiAwareAdapter(
            bridge = bridge,
            localDeviceToken = 50,
            config = config,
            driver = wifiDriver,
            callbackDriver = callbackDriver,
        )
        val bleAdapter = RnsBleL2capAdapter(
            bridge = bridge,
            localDeviceToken = 50,
            config = config,
            driver = bleDriver,
        )
        val coordinator = RnsAwareTransportCoordinator(wifiAdapter, bleAdapter)
        coordinator.start()

        // Wi-Fi Aware unavailable -> BLE L2CAP must register on RnsFrameBridge automatically.
        assertFalse("wifi-aware" in bridge.attached)
        assertTrue("ble-l2cap" in bridge.attached)
        assertTrue(bleAdapter.isBridgeRegistered)

        // Discover a BLE peer and verify low-bandwidth control/text vs bulk media enforcement.
        bleDriver.events!!.onPeerScanned("ble:peer1", remoteToken = 99, psm = 128, rssiDbm = -64, handle = Any())
        coordinator.syncSnapshots()
        assertEquals(CommReachability.CONNECTED, bleAdapter.linkStatus.value.reachability)
        assertEquals(CommTransportKind.BLE_L2CAP, coordinator.networkState.value.bestRoute)

        val scope = CommScope("evt-1", "ch-1", "Alpha")
        val textSend = bleAdapter.send(
            CommOutboundMessage(scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "到达集结点"),
        )
        assertTrue(textSend is CommSendResult.Accepted)

        val bulkSend = bleAdapter.send(
            CommOutboundMessage(scope = scope, kind = CommEnvelopeKind.VOICE_NOTE, audioBytes = ByteArray(1024)),
        )
        assertTrue(bulkSend is CommSendResult.Rejected)

        // Verify frame arrived at remote BLE peer socket.
        val blePeerSock = bleDriver.peerSockets.getValue("ble:peer1")
        val received = RnsFrameFramer.readFrame(blePeerSock.getInputStream()) as RnsFrameFramer.Frame.Data
        assertEquals("到达集结点", received.payload.toString(Charsets.UTF_8))

        // Now Wi-Fi Aware becomes available and connects a peer -> BLE fallback cleanly detaches!
        wifiDriver.available = true
        wifiAdapter.refresh()
        wifiDriver.events!!.onPeerDiscovered("aware:p1", remoteToken = 99, rssiDbm = -45, isBridgeCandidate = false)
        coordinator.syncSnapshots()

        assertTrue("wifi-aware" in bridge.attached)
        assertFalse("ble-l2cap" in bridge.attached)
        assertFalse(bleAdapter.isBridgeRegistered)
        assertEquals(CommTransportKind.WIFI_AWARE, coordinator.networkState.value.bestRoute)
        assertFalse(bleAdapter.leakCounters().hasLeaks)

        coordinator.close()
        assertFalse(coordinator.diagnostics.value.leakCounters.hasLeaks)
        assertTrue(bridge.attached.isEmpty())
    }
}
