package com.mandonothing.ops.comm.aware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCapacityManagerTest {

    @Test
    fun admitsMoreThanLegacyMaxLinksThreeWhenConfigured() {
        val config = RnsLinkConfig(maxActiveWifiAwareLinks = 6)
        val manager = PeerCapacityManager(RnsRadioKind.WIFI_AWARE, config)

        (1..5).forEach { idx ->
            val peerId = "aware:peer-$idx"
            manager.onPeerDiscovered(peerId, rssiDbm = -55 - idx, nowMs = 1_000L)
            val decision = manager.evaluateAdmission(peerId, nowMs = 1_000L)
            assertEquals(PeerCapacityManager.AdmissionDecision.Admitted, decision)
            manager.markConnected(peerId, nowMs = 1_000L)
        }

        assertEquals(5, manager.activePeers().size)
        assertEquals(0, manager.standbyPeers().size)
    }

    @Test
    fun prunesStalePeersAndPreemptsWeakIncumbentWithStrongerCandidate() {
        val config = RnsLinkConfig(
            maxActiveWifiAwareLinks = 3,
            minPeerDwellMs = 5_000L,
            stalePeerTimeoutMs = 20_000L,
            preemptionScoreMargin = 10.0,
        )
        val manager = PeerCapacityManager(RnsRadioKind.WIFI_AWARE, config)

        // Admit 3 initial peers at t=0.
        listOf("p1" to -88, "p2" to -52, "p3" to -50).forEach { (id, rssi) ->
            manager.onPeerDiscovered(id, rssiDbm = rssi, nowMs = 0L)
            assertEquals(PeerCapacityManager.AdmissionDecision.Admitted, manager.evaluateAdmission(id, 0L))
            manager.markConnected(id, 0L)
        }
        manager.recordMetrics("p1", rssiDbm = -95, rttMs = 600L, packetLossRate = 0.25, nowMs = 1_000L)
        manager.recordMetrics("p2", rssiDbm = -50, rttMs = 12L, nowMs = 7_000L)
        manager.recordMetrics("p3", rssiDbm = -48, rttMs = 10L, nowMs = 7_000L)

        // Strong p4 arrives after minPeerDwellMs: should preempt weak p1.
        manager.onPeerDiscovered("p4", rssiDbm = -45, isBridgeCandidate = true, nowMs = 7_500L)
        val decision = manager.evaluateAdmission("p4", nowMs = 7_500L)
        assertTrue(decision is PeerCapacityManager.AdmissionDecision.PreemptedAndAdmitted)
        assertEquals("p1", (decision as PeerCapacityManager.AdmissionDecision.PreemptedAndAdmitted).evictedPeerId)
        manager.markConnected("p4", nowMs = 7_500L)

        // Now make p2 stale at t=30_000 and verify pruneAndRotate evicts p2 and brings back p1 if eligible.
        manager.recordMetrics("p3", rttMs = 10L, nowMs = 29_000L)
        manager.recordMetrics("p4", rttMs = 10L, nowMs = 29_000L)
        val maintenance = manager.pruneAndRotate(nowMs = 30_000L)
        assertTrue("p2" in maintenance.evictPeers)
        assertEquals(1, maintenance.stalePrunedCount)
        assertTrue("p1" in maintenance.admitPeers)
    }

    @Test
    fun rotatesHealthyPeersDeterministicallySoDenseTopologiesDoNotPartition() {
        val config = RnsLinkConfig(
            maxActiveWifiAwareLinks = 4,
            minPeerDwellMs = 10_000L,
            peerRotationIntervalMs = 15_000L,
            stalePeerTimeoutMs = 60_000L,
        )
        val manager = PeerCapacityManager(RnsRadioKind.WIFI_AWARE, config)

        // 6 healthy peers discovered at t=0; 4 connect, 2 stay in standby.
        (1..6).forEach { i ->
            val id = "aware:$i"
            manager.onPeerDiscovered(id, rssiDbm = -55, nowMs = 0L)
            val d = manager.evaluateAdmission(id, 0L)
            if (i <= 4) {
                assertEquals(PeerCapacityManager.AdmissionDecision.Admitted, d)
                manager.markConnected(id, 0L)
            } else {
                assertEquals(PeerCapacityManager.AdmissionDecision.QueuedStandby, d)
            }
        }
        assertEquals(4, manager.activePeers().size)
        assertEquals(2, manager.standbyPeers().size)

        // Keep all 4 active peers fresh at t=16_000, then trigger rotation.
        (1..4).forEach { i ->
            manager.recordMetrics("aware:$i", rttMs = 15L, nowMs = 16_000L)
        }
        val round1 = manager.pruneAndRotate(nowMs = 16_000L)
        assertEquals(1, round1.rotatedOutPeers.size)
        assertEquals(1, round1.admitPeers.size)
        val admitted1 = round1.admitPeers.single()
        assertTrue(admitted1 in listOf("aware:5", "aware:6"))
        manager.markConnected(admitted1, 16_000L)

        // Advance past another rotation interval and verify the remaining unvisited peer gets a slot.
        manager.activePeers().forEach {
            manager.recordMetrics(it.peerId, rttMs = 15L, nowMs = 32_000L)
        }
        val round2 = manager.pruneAndRotate(nowMs = 32_000L)
        assertEquals(1, round2.rotatedOutPeers.size)
        assertEquals(1, round2.admitPeers.size)
        val admitted2 = round2.admitPeers.single()
        assertFalse(admitted2 == admitted1)
        assertTrue(admitted2 in listOf("aware:5", "aware:6"))
        assertEquals(2L, manager.stats().totalRotated)
    }

    @Test
    fun enforcesSuppressionCooldownAfterFailureUntilExpiry() {
        val config = RnsLinkConfig(suppressTtlMs = 12_000L)
        val manager = PeerCapacityManager(RnsRadioKind.WIFI_AWARE, config)

        manager.onPeerDiscovered("peer-x", nowMs = 1_000L)
        assertEquals(PeerCapacityManager.AdmissionDecision.Admitted, manager.evaluateAdmission("peer-x", 1_000L))
        manager.markDisconnected("peer-x", failed = true, nowMs = 2_000L)

        val blocked = manager.evaluateAdmission("peer-x", nowMs = 5_000L)
        assertTrue(blocked is PeerCapacityManager.AdmissionDecision.Suppressed)

        val unblocked = manager.evaluateAdmission("peer-x", nowMs = 14_000L)
        assertEquals(PeerCapacityManager.AdmissionDecision.Admitted, unblocked)
    }
}
