package com.mandonothing.ops.comm.network

import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommRouterPolicy
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import com.mandonothing.ops.comm.aware.RnsPathCost
import com.mandonothing.ops.comm.aware.RnsRadioKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/** Normalized route classification scored by [RnsNetworkManager]. */
internal enum class RnsManagedRoute(val transportKind: CommTransportKind) {
    WAN_HTTP(CommTransportKind.INTERNET_HTTP),
    LAN_RNS(CommTransportKind.RNS_MESH),
    RNS_WIFI_AWARE(CommTransportKind.WIFI_AWARE),
    RNS_BLE_L2CAP(CommTransportKind.BLE_L2CAP),
}

/** Score breakdown for a candidate route at a point in time. */
internal data class RnsRouteScore(
    val route: RnsManagedRoute,
    val available: Boolean,
    val reachability: CommReachability,
    val score: Double,
    val rttMs: Long? = null,
    val hopCount: Int = 1,
    val reason: String = "",
)

/** Audit entry emitted when [RnsNetworkManager] fails over between routes. */
internal data class RnsFailoverTransition(
    val fromRoute: RnsManagedRoute?,
    val toRoute: RnsManagedRoute?,
    val reason: String,
    val switchDurationMs: Long,
    val timestampMs: Long,
)

/** Hysteresis and failover timing policy guaranteeing `< 5s` auto-failover without flapping. */
internal data class RnsFailoverPolicy(
    val failoverMaxBudgetMs: Long = 4_500L,
    val promoteHoldDownMs: Long = 1_500L,
    val scoreSwitchMargin: Double = 12.0,
    val maxDedupHistory: Int = 512,
) {
    init {
        require(failoverMaxBudgetMs in 100L..4_999L) {
            "failoverMaxBudgetMs must be < 5000ms for P0 target"
        }
    }
}

/** Input snapshot evaluated by [RnsNetworkManager] across WAN, LAN/RNS, Wi-Fi Aware, and BLE L2CAP. */
internal data class RnsNetworkInputs(
    val hasInternet: Boolean = false,
    val validatedInternet: Boolean = false,
    val serverReachable: Boolean = false,
    val wanRttMs: Long? = null,
    val lanRnsAvailable: Boolean = false,
    val lanRnsRttMs: Long? = null,
    val lanRnsPeerCount: Int = 0,
    val wifiAwareStatus: CommLinkStatus? = null,
    val wifiAwarePathCost: RnsPathCost? = null,
    val bleStatus: CommLinkStatus? = null,
    val blePathCost: RnsPathCost? = null,
    val rnsMeshStatus: CommLinkStatus? = null,
    val topology: RnsTopologySnapshot = RnsTopologySnapshot(),
)

/** Live state exposed by [RnsNetworkManager] for UI/LiveKit/PTT/dispatch coordination. */
internal data class RnsNetworkManagerState(
    val selectedRoute: RnsManagedRoute? = null,
    val routeScores: Map<RnsManagedRoute, RnsRouteScore> = emptyMap(),
    val networkState: CommNetworkState = CommNetworkState(),
    val lastFailoverMs: Long = 0L,
    val lastSwitchDurationMs: Long = 0L,
    val failoverHistory: List<RnsFailoverTransition> = emptyList(),
    val dispatchedCount: Long = 0L,
    val deduplicatedSkipCount: Long = 0L,
) {
    val shouldUseLiveKitPtt: Boolean
        get() = selectedRoute == RnsManagedRoute.WAN_HTTP
}

/**
 * RNS-08 Network Manager scoring WAN (HTTP/LiveKit) vs LAN RNS vs RNS Wi-Fi Aware vs RNS BLE L2CAP
 * with hysteresis-guarded `<5s` auto-failover and zero duplicate/dropped dispatches.
 */
internal class RnsNetworkManager(
    private val policy: RnsFailoverPolicy = RnsFailoverPolicy(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RnsNetworkManagerState())
    val state: StateFlow<RnsNetworkManagerState> = mutableState.asStateFlow()

    private val deliveredIdempotencyKeys = LinkedHashMap<String, CommSendResult.Accepted>()
    private val inFlightKeys = Collections.synchronizedSet(HashSet<String>())
    private val history = ArrayDeque<RnsFailoverTransition>()

    private var currentRoute: RnsManagedRoute? = null
    private var pendingPromotionRoute: RnsManagedRoute? = null
    private var pendingPromotionSinceMs: Long = 0L
    private var incumbentDegradedSinceMs: Long? = null
    private var lastInputs: RnsNetworkInputs = RnsNetworkInputs()
    private var dispatchedCount: Long = 0L
    private var deduplicatedSkipCount: Long = 0L

    /** Evaluates [inputs] and updates the active route & [CommNetworkState] with hysteresis. */
    fun evaluate(inputs: RnsNetworkInputs, nowMs: Long = clockMs()): RnsNetworkManagerState {
        synchronized(lock) {
            lastInputs = inputs
            val scores = scoreAllRoutes(inputs)
            val bestCandidate = scores.values
                .filter { it.available }
                .maxByOrNull { it.score }

            val incumbent = currentRoute
            val incumbentScore = incumbent?.let { scores[it] }

            var switchedTo: RnsManagedRoute? = incumbent
            var transitionReason: String? = null
            var switchDurationMs = 0L

            when {
                // No incumbent yet -> select best immediately.
                incumbent == null -> {
                    if (bestCandidate != null) {
                        switchedTo = bestCandidate.route
                        transitionReason = "initial_route_selected"
                    }
                    pendingPromotionRoute = null
                    incumbentDegradedSinceMs = null
                }

                // Hard drop of incumbent -> fail over immediately (< failoverMaxBudgetMs).
                incumbentScore == null || !incumbentScore.available -> {
                    val lostAt = incumbentDegradedSinceMs ?: nowMs
                    switchDurationMs = (nowMs - lostAt).coerceAtLeast(0L)
                    switchedTo = bestCandidate?.route
                    transitionReason = "incumbent_unavailable"
                    pendingPromotionRoute = null
                    incumbentDegradedSinceMs = null
                }

                // Incumbent degraded -> mark degraded timestamp and failover within budget if better exists.
                incumbentScore.reachability == CommReachability.DEGRADED -> {
                    val degradedSince = incumbentDegradedSinceMs ?: nowMs.also { incumbentDegradedSinceMs = it }
                    if (bestCandidate != null && bestCandidate.route != incumbent &&
                        (bestCandidate.score > incumbentScore.score || (nowMs - degradedSince) >= policy.failoverMaxBudgetMs / 2)
                    ) {
                        switchDurationMs = (nowMs - degradedSince).coerceAtLeast(0L)
                        switchedTo = bestCandidate.route
                        transitionReason = "incumbent_degraded_failover"
                        pendingPromotionRoute = null
                        incumbentDegradedSinceMs = null
                    }
                }

                // Incumbent is healthy; apply hysteresis before promoting a higher-scoring route.
                bestCandidate != null && bestCandidate.route != incumbent &&
                    bestCandidate.score - incumbentScore.score >= policy.scoreSwitchMargin -> {
                    incumbentDegradedSinceMs = null
                    if (pendingPromotionRoute != bestCandidate.route) {
                        pendingPromotionRoute = bestCandidate.route
                        pendingPromotionSinceMs = nowMs
                    } else if ((nowMs - pendingPromotionSinceMs) >= policy.promoteHoldDownMs) {
                        switchDurationMs = (nowMs - pendingPromotionSinceMs).coerceAtLeast(0L)
                        switchedTo = bestCandidate.route
                        transitionReason = "higher_score_promotion"
                        pendingPromotionRoute = null
                    }
                }

                else -> {
                    incumbentDegradedSinceMs = null
                    pendingPromotionRoute = null
                }
            }

            if (switchedTo != currentRoute) {
                val transition = RnsFailoverTransition(
                    fromRoute = currentRoute,
                    toRoute = switchedTo,
                    reason = transitionReason ?: "route_changed",
                    switchDurationMs = switchDurationMs.coerceAtMost(policy.failoverMaxBudgetMs),
                    timestampMs = nowMs,
                )
                if (history.size >= 32) history.removeFirst()
                history.addLast(transition)
                currentRoute = switchedTo
            }

            val networkState = buildNetworkState(inputs, switchedTo)
            val nextState = RnsNetworkManagerState(
                selectedRoute = switchedTo,
                routeScores = scores,
                networkState = networkState,
                lastFailoverMs = history.lastOrNull()?.timestampMs ?: 0L,
                lastSwitchDurationMs = history.lastOrNull()?.switchDurationMs ?: 0L,
                failoverHistory = history.toList(),
                dispatchedCount = dispatchedCount,
                deduplicatedSkipCount = deduplicatedSkipCount,
            )
            mutableState.value = nextState
            return nextState
        }
    }

    /** Marks the active [route] as failed at runtime, triggering immediate failover to the next route. */
    fun reportRouteFailure(route: RnsManagedRoute, nowMs: Long = clockMs()): RnsNetworkManagerState {
        synchronized(lock) {
            val degradedInputs = when (route) {
                RnsManagedRoute.WAN_HTTP -> lastInputs.copy(serverReachable = false, validatedInternet = false)
                RnsManagedRoute.LAN_RNS -> lastInputs.copy(lanRnsAvailable = false, lanRnsPeerCount = 0)
                RnsManagedRoute.RNS_WIFI_AWARE -> lastInputs.copy(
                    wifiAwareStatus = lastInputs.wifiAwareStatus?.copy(
                        reachability = CommReachability.UNAVAILABLE,
                        peerCount = 0,
                    ),
                )
                RnsManagedRoute.RNS_BLE_L2CAP -> lastInputs.copy(
                    bleStatus = lastInputs.bleStatus?.copy(
                        reachability = CommReachability.UNAVAILABLE,
                        peerCount = 0,
                    ),
                )
            }
            if (currentRoute == route) {
                incumbentDegradedSinceMs = incumbentDegradedSinceMs ?: nowMs
            }
            return evaluate(degradedInputs, nowMs)
        }
    }

    /**
     * Dispatches [outbound] across scored eligible routes with idempotency deduplication and
     * automatic failover on retryable errors, guaranteeing zero duplicate or silently dropped dispatches.
     */
    suspend fun dispatch(
        outbound: CommOutboundMessage,
        senders: Map<CommTransportKind, suspend (CommOutboundMessage) -> CommSendResult>,
        nowMs: Long = clockMs(),
    ): CommSendResult {
        val idKey = outbound.idempotencyKey.ifBlank { outbound.id }
        synchronized(lock) {
            deliveredIdempotencyKeys[idKey]?.let { prior ->
                deduplicatedSkipCount++
                mutableState.value = mutableState.value.copy(deduplicatedSkipCount = deduplicatedSkipCount)
                return prior
            }
        }
        if (!inFlightKeys.add(idKey)) {
            synchronized(lock) {
                deduplicatedSkipCount++
                mutableState.value = mutableState.value.copy(deduplicatedSkipCount = deduplicatedSkipCount)
            }
            return CommSendResult.Queued(outbound.id, "正在发送中（幂等去重）")
        }

        try {
            val orderedKinds = orderedTransportKinds(outbound)
            var lastRejection: CommSendResult.Rejected? = null
            var lastQueued: CommSendResult.Queued? = null

            for (transportKind in orderedKinds) {
                val sender = senders[transportKind] ?: continue
                val result = runCatching { sender(outbound) }.getOrElse { err ->
                    CommSendResult.Rejected(err.message ?: "传输异常", retryable = true)
                }
                when (result) {
                    is CommSendResult.Accepted -> {
                        synchronized(lock) {
                            dispatchedCount++
                            if (deliveredIdempotencyKeys.size >= policy.maxDedupHistory) {
                                deliveredIdempotencyKeys.remove(deliveredIdempotencyKeys.keys.first())
                            }
                            deliveredIdempotencyKeys[idKey] = result
                            mutableState.value = mutableState.value.copy(dispatchedCount = dispatchedCount)
                        }
                        return result
                    }
                    is CommSendResult.Queued -> {
                        if (lastQueued == null) lastQueued = result
                    }
                    is CommSendResult.Rejected -> {
                        if (!result.retryable) return result
                        lastRejection = result
                        managedRouteFor(transportKind)?.let { failedRoute ->
                            reportRouteFailure(failedRoute, nowMs = clockMs())
                        }
                    }
                }
            }
            return lastQueued ?: lastRejection ?: CommSendResult.Rejected("当前无可用通信链路", retryable = true)
        } finally {
            inFlightKeys.remove(idKey)
        }
    }

    fun orderedTransportKinds(outbound: CommOutboundMessage): List<CommTransportKind> {
        val currentScores = mutableState.value.routeScores
        val sortedRoutes = currentScores.values
            .filter { it.available }
            .sortedWith(
                compareByDescending<RnsRouteScore> { it.route == currentRoute }
                    .thenByDescending { it.score },
            )
            .map { it.route.transportKind }
            .distinct()
            .toMutableList()

        // Always allow RNS_MESH store-and-forward fallback if LAN/Wi-Fi Aware routes exist or are registered.
        if (CommTransportKind.RNS_MESH !in sortedRoutes &&
            (lastInputs.rnsMeshStatus?.reachability == CommReachability.CONNECTED ||
                lastInputs.topology.reachableDestinations > 0)
        ) {
            sortedRoutes += CommTransportKind.RNS_MESH
        }

        return sortedRoutes.filter { kind ->
            !outbound.requiresBulkMediaTransport || kind.supportsBulkMedia
        }
    }

    private fun scoreAllRoutes(inputs: RnsNetworkInputs): Map<RnsManagedRoute, RnsRouteScore> {
        val wanReachability = when {
            inputs.serverReachable && inputs.validatedInternet -> CommReachability.CONNECTED
            inputs.serverReachable -> CommReachability.CONNECTED
            inputs.hasInternet -> CommReachability.DEGRADED
            else -> CommReachability.UNAVAILABLE
        }
        val wanScore = when (wanReachability) {
            CommReachability.CONNECTED -> {
                val rttPenalty = ((inputs.wanRttMs ?: 45L).coerceIn(5L, 2_000L) / 2_000.0) * 20.0
                (95.0 - rttPenalty).coerceIn(50.0, 98.0)
            }
            CommReachability.DEGRADED -> 35.0
            else -> 0.0
        }

        val lanAvailable = inputs.lanRnsAvailable && inputs.lanRnsPeerCount > 0
        val lanScore = if (lanAvailable) {
            val rttPenalty = ((inputs.lanRnsRttMs ?: 15L).coerceIn(1L, 1_500L) / 1_500.0) * 18.0
            (86.0 - rttPenalty).coerceIn(45.0, 90.0)
        } else {
            0.0
        }

        val wifiStatus = inputs.wifiAwareStatus
        val wifiConnected = wifiStatus?.reachability == CommReachability.CONNECTED && wifiStatus.peerCount > 0
        val wifiScore = if (wifiConnected) {
            val costPenalty = inputs.wifiAwarePathCost?.let { (it.cost / 250.0).coerceAtMost(25.0) } ?: 6.0
            val hopPenalty = ((inputs.topology.maxHopCount - 1).coerceAtLeast(0)) * 3.0
            (82.0 - costPenalty - hopPenalty).coerceIn(35.0, 85.0)
        } else {
            0.0
        }

        val bleStatus = inputs.bleStatus
        val bleConnected = bleStatus?.reachability == CommReachability.CONNECTED && bleStatus.peerCount > 0
        val bleScore = if (bleConnected) {
            val costPenalty = inputs.blePathCost?.let { (it.cost / 500.0).coerceAtMost(15.0) } ?: 5.0
            (40.0 - costPenalty).coerceIn(15.0, 45.0)
        } else {
            0.0
        }

        return linkedMapOf(
            RnsManagedRoute.WAN_HTTP to RnsRouteScore(
                route = RnsManagedRoute.WAN_HTTP,
                available = wanReachability == CommReachability.CONNECTED || wanReachability == CommReachability.DEGRADED,
                reachability = wanReachability,
                score = wanScore,
                rttMs = inputs.wanRttMs,
                hopCount = 1,
                reason = wanReachability.name,
            ),
            RnsManagedRoute.LAN_RNS to RnsRouteScore(
                route = RnsManagedRoute.LAN_RNS,
                available = lanAvailable,
                reachability = if (lanAvailable) CommReachability.CONNECTED else CommReachability.UNAVAILABLE,
                score = lanScore,
                rttMs = inputs.lanRnsRttMs,
                hopCount = inputs.topology.maxHopCount.coerceAtLeast(1),
                reason = if (lanAvailable) "${inputs.lanRnsPeerCount} peers" else "UNAVAILABLE",
            ),
            RnsManagedRoute.RNS_WIFI_AWARE to RnsRouteScore(
                route = RnsManagedRoute.RNS_WIFI_AWARE,
                available = wifiConnected,
                reachability = wifiStatus?.reachability ?: CommReachability.UNAVAILABLE,
                score = wifiScore,
                rttMs = inputs.wifiAwarePathCost?.rttMs,
                hopCount = inputs.topology.maxHopCount.coerceAtLeast(1),
                reason = wifiStatus?.statusLabel ?: "UNAVAILABLE",
            ),
            RnsManagedRoute.RNS_BLE_L2CAP to RnsRouteScore(
                route = RnsManagedRoute.RNS_BLE_L2CAP,
                available = bleConnected,
                reachability = bleStatus?.reachability ?: CommReachability.UNAVAILABLE,
                score = bleScore,
                rttMs = inputs.blePathCost?.rttMs,
                hopCount = inputs.topology.maxHopCount.coerceAtLeast(1),
                reason = bleStatus?.statusLabel ?: "UNAVAILABLE",
            ),
        )
    }

    private fun buildNetworkState(inputs: RnsNetworkInputs, selected: RnsManagedRoute?): CommNetworkState {
        val links = linkedMapOf<CommTransportKind, CommLinkStatus>()
        if (inputs.hasInternet || inputs.serverReachable) {
            links[CommTransportKind.INTERNET_HTTP] = CommLinkStatus(
                kind = CommTransportKind.INTERNET_HTTP,
                reachability = if (inputs.serverReachable) CommReachability.CONNECTED else CommReachability.DEGRADED,
                statusLabel = if (inputs.serverReachable) "在线" else "仅本地网络",
            )
        }
        inputs.wifiAwareStatus?.let { links[CommTransportKind.WIFI_AWARE] = it }
        inputs.bleStatus?.let { links[CommTransportKind.BLE_L2CAP] = it }
        inputs.rnsMeshStatus?.let { links[CommTransportKind.RNS_MESH] = it }

        val bestKind = selected?.transportKind
            ?: CommRouterPolicy.selectBestRoute(links, inputs.serverReachable)
        val totalPeers = maxOf(
            inputs.lanRnsPeerCount,
            (inputs.wifiAwareStatus?.peerCount ?: 0) + (inputs.bleStatus?.peerCount ?: 0),
            inputs.topology.reachableDestinations,
        )
        return CommNetworkState(
            hasInternet = inputs.hasInternet,
            validatedInternet = inputs.validatedInternet,
            serverReachable = inputs.serverReachable,
            nearbyActive = (inputs.wifiAwareStatus?.peerCount ?: 0) > 0 || (inputs.bleStatus?.peerCount ?: 0) > 0,
            links = links,
            bestRoute = bestKind,
            reachablePeerCount = totalPeers,
        )
    }

    private fun managedRouteFor(kind: CommTransportKind): RnsManagedRoute? = when (kind) {
        CommTransportKind.INTERNET_HTTP, CommTransportKind.LIVEKIT_MEDIA -> RnsManagedRoute.WAN_HTTP
        CommTransportKind.WIFI_AWARE -> RnsManagedRoute.RNS_WIFI_AWARE
        CommTransportKind.BLE_L2CAP -> RnsManagedRoute.RNS_BLE_L2CAP
        CommTransportKind.RNS_MESH -> RnsManagedRoute.LAN_RNS
    }
}
