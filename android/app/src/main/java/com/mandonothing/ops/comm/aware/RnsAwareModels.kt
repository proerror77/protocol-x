package com.mandonothing.ops.comm.aware

import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommRouterPolicy
import com.mandonothing.ops.comm.CommTransportKind
import kotlin.math.roundToInt

/** Local radio medium managed by the RNS-04 adapter layer. */
internal enum class RnsRadioKind(
    val displayName: String,
    val supportsBulkMedia: Boolean,
    val defaultBitrateBps: Long,
    val baseCost: Int,
) {
    WIFI_AWARE(
        displayName = "Wi-Fi Aware",
        supportsBulkMedia = true,
        defaultBitrateBps = 25_000_000L,
        baseCost = 10,
    ),
    BLE_L2CAP(
        displayName = "蓝牙 L2CAP",
        supportsBulkMedia = false,
        defaultBitrateBps = 250_000L,
        baseCost = 200,
    );

    fun toCommTransportKind(): CommTransportKind = when (this) {
        WIFI_AWARE -> CommTransportKind.WIFI_AWARE
        BLE_L2CAP -> CommTransportKind.BLE_L2CAP
    }
}

/** Policy controlling when BLE L2CAP acts as an RNS transport alongside or behind Wi-Fi Aware. */
internal enum class BleFallbackPolicy {
    /** Register BLE L2CAP as active RNS fallback whenever Wi-Fi Aware is unavailable or has no active links. */
    WHEN_WIFI_AWARE_UNAVAILABLE,

    /** Keep BLE L2CAP active even while Wi-Fi Aware has links (for hybrid reachability). */
    SIMULTANEOUS_BACKUP,
}

/** Tunable capacity, rotation, pruning, and probe configuration for RNS-04 adapters. */
internal data class RnsLinkConfig(
    val maxActiveWifiAwareLinks: Int = DEFAULT_MAX_WIFI_AWARE_LINKS,
    val maxActiveBleLinks: Int = DEFAULT_MAX_BLE_LINKS,
    val maxStandbyPeers: Int = 64,
    val minPeerDwellMs: Long = 15_000L,
    val peerRotationIntervalMs: Long = 30_000L,
    val stalePeerTimeoutMs: Long = 45_000L,
    val suppressTtlMs: Long = 12_000L,
    val ndpRequestTimeoutMs: Long = 12_000L,
    val probeIntervalMs: Long = 5_000L,
    val preemptionScoreMargin: Double = 15.0,
    val enableBleFallback: Boolean = true,
    val bleFallbackPolicy: BleFallbackPolicy = BleFallbackPolicy.WHEN_WIFI_AWARE_UNAVAILABLE,
    val enableUdpDatagramPath: Boolean = false,
    val maxFrameBytes: Int = 65_536,
) {
    init {
        require(maxActiveWifiAwareLinks >= 1) { "maxActiveWifiAwareLinks must be >= 1" }
        require(maxActiveBleLinks >= 1) { "maxActiveBleLinks must be >= 1" }
        require(maxStandbyPeers >= 1) { "maxStandbyPeers must be >= 1" }
        require(maxFrameBytes >= 512) { "maxFrameBytes must be >= 512" }
    }

    fun maxLinksFor(radio: RnsRadioKind): Int = when (radio) {
        RnsRadioKind.WIFI_AWARE -> maxActiveWifiAwareLinks
        RnsRadioKind.BLE_L2CAP -> maxActiveBleLinks
    }

    companion object {
        /** Matches personal-rns `MAX_NDP_PEERS` (replaces legacy `MAX_LINKS = 3`). */
        const val DEFAULT_MAX_WIFI_AWARE_LINKS = 8
        const val DEFAULT_MAX_BLE_LINKS = 6
    }
}

/** Real-time quality & traffic telemetry for a single discovered or connected neighbor. */
internal data class RnsPeerQualityMetrics(
    val peerId: String,
    val radio: RnsRadioKind,
    val rssiDbm: Int? = null,
    val rttMs: Long? = null,
    val estimatedBandwidthBps: Long = radio.defaultBitrateBps,
    val packetLossRate: Double = 0.0,
    val lastSeenMs: Long = 0L,
    val lastActiveMs: Long = 0L,
    val connectedSinceMs: Long = 0L,
    val lastDisconnectedMs: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val framesSent: Long = 0L,
    val framesReceived: Long = 0L,
    val isBridgeCandidate: Boolean = false,
) {
    fun qualityScore(nowMs: Long, staleTimeoutMs: Long): Double =
        RnsPathCostCalculator.computeQualityScore(this, nowMs, staleTimeoutMs)

    fun pathCost(nowMs: Long, staleTimeoutMs: Long): RnsPathCost =
        RnsPathCostCalculator.computePathCost(this, nowMs, staleTimeoutMs)
}

/** Normalized RNS path cost derived from per-link RSSI, RTT, bandwidth, and loss (ready for RNS-08). */
internal data class RnsPathCost(
    val radio: RnsRadioKind,
    val cost: Int,
    val rssiDbm: Int?,
    val rttMs: Long?,
    val estimatedBandwidthBps: Long,
    val packetLossRate: Double,
    val computedAtMs: Long,
)

/** Deterministic scoring and path-cost mapping from link telemetry into RNS-08 & CommNetworkState. */
internal object RnsPathCostCalculator {
    private const val MAX_COST = 10_000

    fun computeQualityScore(
        metrics: RnsPeerQualityMetrics,
        nowMs: Long,
        staleTimeoutMs: Long,
    ): Double {
        val rssiScore = when (val rssi = metrics.rssiDbm) {
            null -> 20.0
            else -> ((rssi.coerceIn(-100, -35) + 100) / 65.0) * 35.0
        }
        val rttScore = when (val rtt = metrics.rttMs) {
            null -> 15.0
            else -> (1.0 - (rtt.coerceIn(1L, 1_500L) / 1_500.0)) * 30.0
        }
        val bwRatio = (metrics.estimatedBandwidthBps.coerceAtLeast(1L).toDouble() /
            metrics.radio.defaultBitrateBps.toDouble()).coerceIn(0.05, 1.5)
        val bwScore = (bwRatio / 1.5) * 20.0
        val lossPenalty = metrics.packetLossRate.coerceIn(0.0, 1.0) * 25.0
        val stalenessMs = (nowMs - metrics.lastActiveMs).coerceAtLeast(0L)
        val stalePenalty = if (staleTimeoutMs > 0L) {
            (stalenessMs.toDouble() / staleTimeoutMs.toDouble()).coerceIn(0.0, 1.5) * 20.0
        } else {
            0.0
        }
        val bridgeBonus = if (metrics.isBridgeCandidate) 8.0 else 0.0
        return (rssiScore + rttScore + bwScore + bridgeBonus - lossPenalty - stalePenalty).coerceIn(0.0, 100.0)
    }

    fun computePathCost(
        metrics: RnsPeerQualityMetrics,
        nowMs: Long,
        staleTimeoutMs: Long,
    ): RnsPathCost {
        val rttTerm = (metrics.rttMs ?: 40L).coerceIn(1L, 5_000L).toDouble() * 0.6
        val rssiPenalty = when (val rssi = metrics.rssiDbm) {
            null -> 15.0
            else -> (-rssi.coerceIn(-105, -30) - 30) * 1.2
        }
        val bwMbps = (metrics.estimatedBandwidthBps.coerceAtLeast(1_000L) / 1_000_000.0)
        val bwTerm = (25.0 / bwMbps.coerceAtLeast(0.05)).coerceAtMost(2_500.0)
        val lossTerm = metrics.packetLossRate.coerceIn(0.0, 1.0) * 2_000.0
        val stalenessMs = (nowMs - metrics.lastActiveMs).coerceAtLeast(0L)
        val staleTerm = if (staleTimeoutMs > 0L && stalenessMs > staleTimeoutMs / 2) {
            ((stalenessMs - staleTimeoutMs / 2).toDouble() / staleTimeoutMs.toDouble()) * 500.0
        } else {
            0.0
        }
        val raw = metrics.radio.baseCost + rttTerm + rssiPenalty + bwTerm + lossTerm + staleTerm
        return RnsPathCost(
            radio = metrics.radio,
            cost = raw.roundToInt().coerceIn(1, MAX_COST),
            rssiDbm = metrics.rssiDbm,
            rttMs = metrics.rttMs,
            estimatedBandwidthBps = metrics.estimatedBandwidthBps,
            packetLossRate = metrics.packetLossRate,
            computedAtMs = nowMs,
        )
    }

    fun formatStatusLabel(
        reachability: CommReachability,
        bestCost: RnsPathCost?,
        peerCount: Int,
        fallbackActive: Boolean = false,
    ): String = when (reachability) {
        CommReachability.UNAVAILABLE -> "不可用"
        CommReachability.DISCOVERING -> if (fallbackActive) "备援发现中" else "正在发现"
        CommReachability.CONNECTED, CommReachability.DEGRADED -> {
            val base = if (reachability == CommReachability.DEGRADED) "降级运行" else "已连接"
            if (bestCost == null) {
                base
            } else {
                val parts = buildList {
                    bestCost.rttMs?.let { add("${it}ms") }
                    bestCost.rssiDbm?.let { add("${it}dBm") }
                    add(formatBandwidth(bestCost.estimatedBandwidthBps))
                    add("cost=${bestCost.cost}")
                }
                "$base (${parts.joinToString(" · ")}, ${peerCount}节点)"
            }
        }
    }

    fun mergeIntoNetworkState(
        base: CommNetworkState,
        wifiStatus: CommLinkStatus?,
        bleStatus: CommLinkStatus?,
        rnsMeshStatus: CommLinkStatus? = null,
    ): CommNetworkState {
        val updatedLinks = base.links.toMutableMap()
        if (wifiStatus != null) updatedLinks[CommTransportKind.WIFI_AWARE] = wifiStatus
        if (bleStatus != null) updatedLinks[CommTransportKind.BLE_L2CAP] = bleStatus
        if (rnsMeshStatus != null) updatedLinks[CommTransportKind.RNS_MESH] = rnsMeshStatus
        val localPeers = listOfNotNull(wifiStatus, bleStatus).sumOf { it.peerCount }
        val rnsPeers = rnsMeshStatus?.peerCount ?: 0
        return base.copy(
            links = updatedLinks,
            bestRoute = CommRouterPolicy.selectBestRoute(updatedLinks, base.serverReachable),
            reachablePeerCount = maxOf(base.reachablePeerCount, localPeers, rnsPeers),
        )
    }

    private fun formatBandwidth(bps: Long): String = when {
        bps >= 1_000_000L -> String.format("%.1fMbps", bps / 1_000_000.0)
        bps >= 1_000L -> "${bps / 1_000L}kbps"
        else -> "${bps}bps"
    }
}

/** Reference-counted leak audit counters for ConnectivityManager callbacks and sockets. */
internal data class RnsLeakCounters(
    val activeNetworkCallbacks: Int = 0,
    val totalNetworkCallbacksRegistered: Long = 0L,
    val totalNetworkCallbacksUnregistered: Long = 0L,
    val activeSockets: Int = 0,
    val totalSocketsOpened: Long = 0L,
    val totalSocketsClosed: Long = 0L,
) {
    val hasLeaks: Boolean
        get() = activeNetworkCallbacks != 0 || activeSockets != 0
}

/** Consolidated diagnostics snapshot exposed by RNS-04 adapters for observability and RNS-08. */
internal data class RnsAwareDiagnosticsSnapshot(
    val radioReachability: Map<RnsRadioKind, CommReachability> = emptyMap(),
    val activePeers: List<RnsPeerQualityMetrics> = emptyList(),
    val standbyPeerCount: Int = 0,
    val prunedPeerCount: Long = 0L,
    val rotatedPeerCount: Long = 0L,
    val bestPathCostByRadio: Map<RnsRadioKind, RnsPathCost> = emptyMap(),
    val bleFallbackActive: Boolean = false,
    val leakCounters: RnsLeakCounters = RnsLeakCounters(),
)
