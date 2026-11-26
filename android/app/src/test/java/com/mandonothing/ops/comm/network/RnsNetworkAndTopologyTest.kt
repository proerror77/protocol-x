package com.mandonothing.ops.comm.network

import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import com.mandonothing.ops.comm.aware.RnsPathCost
import com.mandonothing.ops.comm.aware.RnsRadioKind
import com.mandonothing.ops.comm.rns.RnsCoreEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RnsNetworkAndTopologyTest {

    private val scope = CommScope(
        eventId = "evt-golf-01",
        channelId = "ch-alpha",
        channelName = "Alpha",
        accountKey = "acc-1",
        actorId = "actor-1",
    )

    @Test
    fun topologyDiagnosticsTracksMultiHopRoutesAndRelayReconvergence() {
        val now = AtomicLong(1_000L)
        val diag = RnsTopologyDiagnostics(clockMs = { now.get() })

        // Ingest 3-hop & 5-hop routes via relay B.
        diag.ingestMultiHopTelemetry(
            lastLinkRttMs = 42L,
            remoteRoutes = listOf(
                RnsRemoteRouteTelemetry("dest-3hop", hops = 3, nextHopHex = "relay-b", learnedAtMs = 900L, lastActivityAtMs = 1_000L, expiresAtMs = 60_000L, interfaceTag = "wifi-aware"),
                RnsRemoteRouteTelemetry("dest-5hop", hops = 5, nextHopHex = "relay-b", learnedAtMs = 950L, lastActivityAtMs = 1_000L, expiresAtMs = 60_000L, interfaceTag = "wifi-aware"),
            ),
            nowMs = 1_000L,
        )
        val snap1 = diag.snapshot.value
        assertEquals(2, snap1.reachableDestinations)
        assertEquals(5, snap1.maxHopCount)
        assertEquals(42L, snap1.bestLinkRttMs)
        assertEquals(1, snap1.activeRelayCount)

        // Relay B drops at t=2_000; alternate path via Relay C converges at t=3_100 (1,100ms reconvergence).
        now.set(2_000L)
        diag.onPeerDisconnected("relay-b", nowMs = 2_000L)
        assertEquals(0, diag.snapshot.value.reachableDestinations)

        now.set(3_100L)
        diag.onRouteDiscovered(
            destinationHashHex = "dest-5hop",
            nextHopPeerId = "relay-c",
            hopCount = 4,
            linkRttMs = 55L,
            viaRadio = RnsRadioKind.WIFI_AWARE,
            nowMs = 3_100L,
        )
        diag.onCoreEvent(RnsCoreEvent.AnnounceHeard("dest-3hop", hops = 3), nowMs = 3_100L)

        val snap2 = diag.snapshot.value
        assertEquals(2, snap2.reachableDestinations)
        assertEquals(2L, snap2.reconvergenceCount)
        assertEquals(1_100L, snap2.lastReconvergenceMs)

        val network = diag.applyToNetworkState(CommNetworkState())
        val rnsStatus = network.links[CommTransportKind.RNS_MESH]
        assertNotNull(rnsStatus)
        assertEquals(CommReachability.CONNECTED, rnsStatus!!.reachability)
        assertTrue(rnsStatus.statusLabel.contains("4跳"))
        assertTrue(rnsStatus.statusLabel.contains("恢复1100ms"))
    }

    @Test
    fun networkManagerPerformsSub5sHysteresisFailoverAndZeroDuplicateDispatch() = runBlocking {
        val now = AtomicLong(1_000L)
        val manager = RnsNetworkManager(
            policy = RnsFailoverPolicy(
                failoverMaxBudgetMs = 4_500L,
                promoteHoldDownMs = 1_500L,
                scoreSwitchMargin = 12.0,
            ),
            clockMs = { now.get() },
        )

        val wifiStatus = CommLinkStatus(CommTransportKind.WIFI_AWARE, CommReachability.CONNECTED, "已连接", peerCount = 3)
        val bleStatus = CommLinkStatus(CommTransportKind.BLE_L2CAP, CommReachability.CONNECTED, "已连接", peerCount = 1)
        val wifiCost = RnsPathCost(RnsRadioKind.WIFI_AWARE, cost = 22, rssiDbm = -50, rttMs = 18L, estimatedBandwidthBps = 20_000_000L, packetLossRate = 0.0, computedAtMs = 1_000L)
        val bleCost = RnsPathCost(RnsRadioKind.BLE_L2CAP, cost = 300, rssiDbm = -70, rttMs = 110L, estimatedBandwidthBps = 200_000L, packetLossRate = 0.0, computedAtMs = 1_000L)

        // 1. All routes present -> WAN selected initially (LiveKit PTT enabled).
        val initial = manager.evaluate(
            RnsNetworkInputs(
                hasInternet = true,
                validatedInternet = true,
                serverReachable = true,
                wanRttMs = 40L,
                lanRnsAvailable = true,
                lanRnsRttMs = 12L,
                lanRnsPeerCount = 2,
                wifiAwareStatus = wifiStatus,
                wifiAwarePathCost = wifiCost,
                bleStatus = bleStatus,
                blePathCost = bleCost,
            ),
            nowMs = 1_000L,
        )
        assertEquals(RnsManagedRoute.WAN_HTTP, initial.selectedRoute)
        assertTrue(initial.shouldUseLiveKitPtt)

        // 2. WAN drops -> immediate failover (< 5s) to LAN_RNS, then to RNS_WIFI_AWARE, then to RNS_BLE_L2CAP.
        now.set(1_400L)
        val lanFailover = manager.reportRouteFailure(RnsManagedRoute.WAN_HTTP, nowMs = 1_400L)
        assertEquals(RnsManagedRoute.LAN_RNS, lanFailover.selectedRoute)
        assertFalse(lanFailover.shouldUseLiveKitPtt)
        assertTrue(lanFailover.lastSwitchDurationMs < 5_000L)

        now.set(1_800L)
        val wifiFailover = manager.reportRouteFailure(RnsManagedRoute.LAN_RNS, nowMs = 1_800L)
        assertEquals(RnsManagedRoute.RNS_WIFI_AWARE, wifiFailover.selectedRoute)
        assertTrue(wifiFailover.lastSwitchDurationMs < 5_000L)

        // 3. Hysteresis prevents premature switch back on brief WAN blip < promoteHoldDownMs.
        now.set(2_000L)
        val wanRestoredInputs = RnsNetworkInputs(
            hasInternet = true,
            validatedInternet = true,
            serverReachable = true,
            wanRttMs = 35L,
            wifiAwareStatus = wifiStatus,
            wifiAwarePathCost = wifiCost,
            bleStatus = bleStatus,
            blePathCost = bleCost,
        )
        assertEquals(RnsManagedRoute.RNS_WIFI_AWARE, manager.evaluate(wanRestoredInputs, nowMs = 2_000L).selectedRoute)

        // Once hold-down >= 1_500ms elapses, promotion occurs cleanly.
        now.set(3_600L)
        assertEquals(RnsManagedRoute.WAN_HTTP, manager.evaluate(wanRestoredInputs, nowMs = 3_600L).selectedRoute)

        // 4. Dispatch failover + 0-duplicate idempotency:
        //    WAN rejects retryably -> spills over to Wi-Fi Aware; repeating same idempotencyKey does NOT re-send!
        val sentByKind = linkedMapOf<CommTransportKind, Int>()
        val msg = CommOutboundMessage(
            id = "msg-uuid-1",
            scope = scope,
            kind = CommEnvelopeKind.VOICE_NOTE,
            audioBytes = ByteArray(2048),
            idempotencyKey = "idem-voice-1",
        )
        val senders: Map<CommTransportKind, suspend (CommOutboundMessage) -> CommSendResult> = mapOf(
            CommTransportKind.INTERNET_HTTP to {
                sentByKind[CommTransportKind.INTERNET_HTTP] = (sentByKind[CommTransportKind.INTERNET_HTTP] ?: 0) + 1
                CommSendResult.Rejected("wan timeout", retryable = true)
            },
            CommTransportKind.WIFI_AWARE to { outbound ->
                sentByKind[CommTransportKind.WIFI_AWARE] = (sentByKind[CommTransportKind.WIFI_AWARE] ?: 0) + 1
                CommSendResult.Accepted(outbound.id, CommTransportKind.WIFI_AWARE)
            },
            CommTransportKind.BLE_L2CAP to { outbound ->
                sentByKind[CommTransportKind.BLE_L2CAP] = (sentByKind[CommTransportKind.BLE_L2CAP] ?: 0) + 1
                CommSendResult.Accepted(outbound.id, CommTransportKind.BLE_L2CAP)
            },
        )
        val first = manager.dispatch(msg, senders, nowMs = 4_000L)
        assertTrue(first is CommSendResult.Accepted)
        assertEquals(CommTransportKind.WIFI_AWARE, (first as CommSendResult.Accepted).transport)
        assertEquals(1, sentByKind[CommTransportKind.WIFI_AWARE])
        assertEquals(null, sentByKind[CommTransportKind.BLE_L2CAP])

        // Re-dispatch same idempotency key -> zero duplicate transmission!
        val duplicate = manager.dispatch(msg, senders, nowMs = 4_100L)
        assertEquals(first, duplicate)
        assertEquals(1, sentByKind[CommTransportKind.WIFI_AWARE])
        assertEquals(1L, manager.state.value.deduplicatedSkipCount)
    }
}
