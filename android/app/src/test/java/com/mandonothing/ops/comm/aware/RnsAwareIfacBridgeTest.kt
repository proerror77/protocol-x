package com.mandonothing.ops.comm.aware

import com.mandonothing.ops.comm.CommReachability
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class RnsAwareIfacBridgeTest {

    private class RecordingBridgeSink : RnsCustomBridgeSink {
        val attached = linkedMapOf<String, RnsCustomInterfaceOutboundHandler>()
        val inboundFrames = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val metrics = linkedMapOf<String, MutableMap<String, RnsBridgeLinkMetrics>>()

        override fun attachInterface(
            interfaceTag: String,
            kind: RnsCustomInterfaceKind,
            hwMtu: Int,
            bitrateBps: Long,
            outboundHandler: RnsCustomInterfaceOutboundHandler,
        ) {
            attached[interfaceTag] = outboundHandler
        }

        override fun detachInterface(interfaceTag: String): Boolean =
            attached.remove(interfaceTag) != null

        override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) {
            inboundFrames += interfaceTag to frame
        }

        override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) {
            this.metrics.getOrPut(interfaceTag) { linkedMapOf() }[metrics.peerId] = metrics
        }

        override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> =
            metrics[interfaceTag]?.values?.toList().orEmpty()
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

    private class LoopbackWifiDriver : WifiAwareDriver<String, Any> {
        var events: WifiAwareDriver.Events? = null
        val peerServers = mutableMapOf<String, ServerSocket>()
        val peerSockets = mutableMapOf<String, Socket>()

        override fun isAvailable(): Boolean = true
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
        ): Pair<String, Any> {
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            peerServers[peerId] = server
            val client = Socket("127.0.0.1", server.localPort)
            peerSockets[peerId] = server.accept()
            events.onDataPathConnected(
                peerId,
                RnsPeerLinkSession.Transport.Stream(client, client.getInputStream(), client.getOutputStream()),
            )
            return "req-$peerId" to Any()
        }

        override fun stop() {
            peerSockets.values.forEach { runCatching { it.close() } }
            peerServers.values.forEach { runCatching { it.close() } }
        }
        override fun close() = stop()
    }

    @Test
    fun ifacGuardRejectsPublicConfigsWrongKeysExpiredLeasesAndSupportsRotation() {
        val now = AtomicLong(1_000L)
        val publicCfg = RnsIfacConfig(
            networkName = "",
            ifacKey = ByteArray(0),
            allowPublicRns = true,
        )
        val guard = RnsIfacFrameGuard(publicCfg, clockMs = { now.get() })
        assertFalse(guard.isConfigurationValid())
        assertNull(guard.sealOutboundFrame("hello".toByteArray()))
        assertTrue(guard.stats().droppedPublicConfig >= 1L)

        val validCfg = RnsIfacDerivation.deriveConfig(
            eventId = "golf-01",
            channelId = "ch-alpha",
            channelSecret = "secret-alpha-32-bytes-key-material".toByteArray(),
            credentialGeneration = 1L,
            expiresAtMs = 5_000L,
        )
        val attackerCfg = RnsIfacDerivation.deriveConfig(
            eventId = "golf-01",
            channelId = "ch-alpha",
            channelSecret = "wrong-secret-attacker-key-material".toByteArray(),
            credentialGeneration = 1L,
            expiresAtMs = 5_000L,
        )
        guard.rotateConfig(validCfg)
        val attackerGuard = RnsIfacFrameGuard(attackerCfg, clockMs = { now.get() })

        val payload = "RNS-IFAC-PAYLOAD".toByteArray()
        val sealedValid = guard.sealOutboundFrame(payload)
        val sealedWrong = attackerGuard.sealOutboundFrame(payload)
        assertNotNull(sealedValid)
        assertNotNull(sealedWrong)

        assertArrayEquals(payload, guard.verifyInboundFrame("aware:p1", sealedValid!!))
        assertNull(guard.verifyInboundFrame("aware:attacker", sealedWrong!!))
        assertEquals(1L, guard.stats().droppedWrongIfac)

        // Rotation invalidates old generation frames and lease expiry drops traffic.
        val rotatedCfg = RnsIfacDerivation.deriveConfig(
            eventId = "golf-01",
            channelId = "ch-alpha",
            channelSecret = "secret-alpha-32-bytes-key-material".toByteArray(),
            credentialGeneration = 2L,
            expiresAtMs = 5_000L,
        )
        guard.rotateConfig(rotatedCfg)
        assertNull(guard.verifyInboundFrame("aware:p1", sealedValid))
        assertNotNull(guard.verifyInboundFrame("aware:p1", guard.sealOutboundFrame(payload)!!))

        now.set(5_500L)
        assertNull(guard.sealOutboundFrame(payload))
        assertTrue(guard.stats().droppedExpiredLease >= 1L)
    }

    @Test
    fun bridgeConnectorEnforcesIfacAndImmediatePeerRevocationOnRadioLinks() {
        val now = AtomicLong(1_000L)
        val ifac = RnsIfacDerivation.deriveConfig(
            eventId = "evt-1",
            channelId = "ch-1",
            channelSecret = "shared-event-secret".toByteArray(),
            expiresAtMs = 60_000L,
        )
        val guard = RnsIfacFrameGuard(ifac, clockMs = { now.get() })
        val sink = RecordingBridgeSink()
        val topologyCallbacks = CopyOnWriteArrayList<Pair<String, RnsBridgeLinkMetrics>>()
        val connector = RnsAwareBridgeConnector(
            sink = sink,
            ifacGuard = guard,
            clockMs = { now.get() },
            onTopologyMetrics = { tag, metrics -> topologyCallbacks += tag to metrics },
        )

        val wifiDriver = LoopbackWifiDriver()
        val wifiAdapter = RnsWifiAwareAdapter(
            bridge = connector,
            localDeviceToken = 10,
            config = RnsLinkConfig(probeIntervalMs = 0L),
            driver = wifiDriver,
            callbackDriver = FakeNetworkDriver(),
            ifacGuard = guard,
            clockMs = { now.get() },
        )
        val bleAdapter = RnsBleL2capAdapter(
            bridge = connector,
            localDeviceToken = 10,
            config = RnsLinkConfig(probeIntervalMs = 0L),
            driver = object : BleL2capDriver {
                override fun isAvailable(): Boolean = false
                override fun start(events: BleL2capDriver.Events): Boolean = false
                override fun connect(peerId: String, psm: Int, handle: Any?, events: BleL2capDriver.Events) = null
                override fun stop() {}
                override fun close() {}
            },
            ifacGuard = guard,
            clockMs = { now.get() },
        )
        val coordinator = RnsAwareTransportCoordinator(wifiAdapter, bleAdapter)
        connector.bindCoordinator(coordinator)
        coordinator.start()

        wifiDriver.events!!.onPeerDiscovered("aware:good", 20, -45, false)
        assertEquals(1, wifiAdapter.activePeerMetrics().size)
        assertEquals(CommReachability.CONNECTED, wifiAdapter.linkStatus.value.reachability)
        assertTrue(topologyCallbacks.isNotEmpty())

        // Remote sends raw unauthenticated bytes -> dropped at radio boundary by IFAC guard!
        val goodSock = wifiDriver.peerSockets.getValue("aware:good")
        RnsFrameFramer.writeData(goodSock.getOutputStream(), "UNAUTHENTICATED-NOISE".toByteArray())
        Thread.sleep(80)
        assertTrue(sink.inboundFrames.isEmpty())
        assertTrue(guard.stats().droppedWrongIfac >= 1L)

        // Remote sends properly IFAC-sealed bytes -> accepted and forwarded to RnsCustomBridgeSink!
        val validRaw = "AUTHENTICATED-RNS-FRAME".toByteArray()
        val sealed = guard.sealOutboundFrame(validRaw)!!
        RnsFrameFramer.writeData(goodSock.getOutputStream(), sealed)
        val deadline = System.currentTimeMillis() + 2_000L
        while (sink.inboundFrames.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(1, sink.inboundFrames.size)
        assertArrayEquals(validRaw, sink.inboundFrames[0].second)

        // Revoking peer immediately evicts active socket & blocks reconnect attempts.
        connector.revokePeer(peerId = "aware:good", destinationHashHex = "abcd1234")
        assertEquals(0, wifiAdapter.activePeerMetrics().size)
        assertFalse(guard.isDestinationAllowed("abcd1234"))

        wifiDriver.events!!.onPeerDiscovered("aware:good", 20, -45, false)
        assertEquals(0, wifiAdapter.activePeerMetrics().size)

        coordinator.close()
        connector.close()
        assertFalse(wifiAdapter.leakCounters().hasLeaks)
    }
}
