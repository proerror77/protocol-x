package com.mandonothing.ops.comm.network

import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommRouterPolicy
import com.mandonothing.ops.comm.CommTransportKind
import com.mandonothing.ops.comm.aware.RnsBridgeLinkMetrics
import com.mandonothing.ops.comm.aware.RnsRadioKind
import com.mandonothing.ops.comm.rns.RnsCoreEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-destination multi-hop RNS path telemetry exposed for RNS-06 / RNS-08 diagnostics. */
internal data class RnsRouteHopEntry(
    val destinationHashHex: String,
    val nextHopPeerId: String,
    val hopCount: Int,
    val linkRttMs: Long? = null,
    val estimatedBandwidthBps: Long? = null,
    val viaRadio: RnsRadioKind? = null,
    val updatedAtMs: Long = 0L,
    val reachable: Boolean = true,
)

/**
 * Mirrors `@rns-poc`'s UniFFI `RouteTelemetry` record (#58) so `RnsNode.multiHopTelemetry()`
 * feeds directly into [RnsTopologyDiagnostics].
 */
internal data class RnsRemoteRouteTelemetry(
    val destinationHashHex: String,
    val hops: Int,
    val nextHopHex: String?,
    val learnedAtMs: Long,
    val lastActivityAtMs: Long,
    val expiresAtMs: Long,
    val interfaceTag: String,
)

/** Recorded path reconvergence event after a relay/next-hop failure. */
internal data class RnsReconvergenceEvent(
    val destinationHashHex: String,
    val previousNextHop: String,
    val newNextHop: String,
    val previousHops: Int,
    val newHops: Int,
    val reconvergenceMs: Long,
    val timestampMs: Long,
)

/** Consolidated multi-hop RNS topology snapshot merged into [CommNetworkState]. */
internal data class RnsTopologySnapshot(
    val knownDestinations: Int = 0,
    val reachableDestinations: Int = 0,
    val maxHopCount: Int = 0,
    val averageHopCount: Double = 0.0,
    val bestLinkRttMs: Long? = null,
    val activeRelayCount: Int = 0,
    val reconvergenceCount: Long = 0L,
    val lastReconvergenceMs: Long? = null,
    val routes: Map<String, RnsRouteHopEntry> = emptyMap(),
    val recentReconvergences: List<RnsReconvergenceEvent> = emptyList(),
)

/**
 * Tracks 1–5+ hop RNS route entries (`hopCount`, `nextHopPeerId`, `linkRttMs`), relay reconvergence
 * timings on neighbor failure, and projects multi-hop topology telemetry into [CommNetworkState].
 */
internal class RnsTopologyDiagnostics(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class LostRouteMarker(
        val previousNextHop: String,
        val previousHops: Int,
        val lostAtMs: Long,
    )

    private val lock = Any()
    private val routes = LinkedHashMap<String, RnsRouteHopEntry>()
    private val peerMetrics = HashMap<String, RnsBridgeLinkMetrics>()
    private val peerRadios = HashMap<String, RnsRadioKind>()
    private val pendingReconvergence = HashMap<String, LostRouteMarker>()
    private val reconvergences = ArrayDeque<RnsReconvergenceEvent>()
    private var reconvergenceCount = 0L
    private var lastReconvergenceMs: Long? = null

    private val mutableSnapshot = MutableStateFlow(RnsTopologySnapshot())
    val snapshot: StateFlow<RnsTopologySnapshot> = mutableSnapshot.asStateFlow()

    /** Ingests [RnsCoreEvent] announcements and link state changes from [RnsCoreEngine]. */
    fun onCoreEvent(event: RnsCoreEvent, nowMs: Long = clockMs()) {
        synchronized(lock) {
            when (event) {
                is RnsCoreEvent.AnnounceHeard -> {
                    val dest = event.destinationHashHex.lowercase()
                    val existing = routes[dest]
                    val nextHop = existing?.nextHopPeerId?.takeIf { existing.reachable } ?: dest
                    recordRouteLocked(
                        destinationHashHex = dest,
                        nextHopPeerId = nextHop,
                        hopCount = event.hops.coerceAtLeast(1),
                        linkRttMs = existing?.linkRttMs ?: peerMetrics[nextHop]?.rttMs,
                        estimatedBandwidthBps = existing?.estimatedBandwidthBps ?: peerMetrics[nextHop]?.estimatedBandwidthBps,
                        viaRadio = existing?.viaRadio ?: peerRadios[nextHop],
                        nowMs = nowMs,
                    )
                }

                is RnsCoreEvent.PeerLinkChanged -> {
                    val peer = event.destinationHashHex.lowercase()
                    if (event.connected) {
                        val existing = routes[peer]
                        recordRouteLocked(
                            destinationHashHex = peer,
                            nextHopPeerId = peer,
                            hopCount = existing?.hopCount ?: 1,
                            linkRttMs = event.rttMs.takeIf { it > 0L } ?: existing?.linkRttMs,
                            estimatedBandwidthBps = existing?.estimatedBandwidthBps,
                            viaRadio = existing?.viaRadio,
                            nowMs = nowMs,
                        )
                    } else {
                        markPeerDisconnectedLocked(peer, nowMs)
                    }
                }

                is RnsCoreEvent.LxmfReceived -> {
                    val src = event.sourceHashHex.lowercase()
                    val existing = routes[src]
                    recordRouteLocked(
                        destinationHashHex = src,
                        nextHopPeerId = existing?.nextHopPeerId ?: src,
                        hopCount = event.hopCount.coerceAtLeast(1),
                        linkRttMs = existing?.linkRttMs,
                        estimatedBandwidthBps = existing?.estimatedBandwidthBps,
                        viaRadio = existing?.viaRadio,
                        nowMs = nowMs,
                    )
                }

                is RnsCoreEvent.DeliveryUpdated -> Unit
            }
            publishLocked()
        }
    }

    /** Records an explicit multi-hop route discovered via relay or announce table. */
    fun onRouteDiscovered(
        destinationHashHex: String,
        nextHopPeerId: String,
        hopCount: Int,
        linkRttMs: Long? = null,
        estimatedBandwidthBps: Long? = null,
        viaRadio: RnsRadioKind? = null,
        nowMs: Long = clockMs(),
    ) {
        synchronized(lock) {
            recordRouteLocked(
                destinationHashHex = destinationHashHex.lowercase(),
                nextHopPeerId = nextHopPeerId,
                hopCount = hopCount.coerceAtLeast(1),
                linkRttMs = linkRttMs ?: peerMetrics[nextHopPeerId]?.rttMs,
                estimatedBandwidthBps = estimatedBandwidthBps ?: peerMetrics[nextHopPeerId]?.estimatedBandwidthBps,
                viaRadio = viaRadio ?: peerRadios[nextHopPeerId],
                nowMs = nowMs,
            )
            publishLocked()
        }
    }

    /** Updates per-link RTT/bandwidth telemetry from [RnsAwareBridgeConnector]. */
    fun onLinkMetricsUpdated(
        peerId: String,
        radio: RnsRadioKind,
        metrics: RnsBridgeLinkMetrics,
        nowMs: Long = clockMs(),
    ) {
        synchronized(lock) {
            peerMetrics[peerId] = metrics
            peerRadios[peerId] = radio
            if (!metrics.connected) {
                markPeerDisconnectedLocked(peerId, nowMs)
            } else {
                for ((dest, entry) in routes) {
                    if (entry.nextHopPeerId == peerId) {
                        routes[dest] = entry.copy(
                            linkRttMs = metrics.rttMs ?: entry.linkRttMs,
                            estimatedBandwidthBps = metrics.estimatedBandwidthBps ?: entry.estimatedBandwidthBps,
                            viaRadio = radio,
                            updatedAtMs = nowMs,
                            reachable = true,
                        )
                    }
                }
            }
            publishLocked()
        }
    }

    /** Ingests Rust `RnsNode.multiHopTelemetry()` snapshots matching UniFFI contract #58. */
    fun ingestMultiHopTelemetry(
        lastLinkRttMs: Long?,
        remoteRoutes: List<RnsRemoteRouteTelemetry>,
        nowMs: Long = clockMs(),
    ) {
        synchronized(lock) {
            val activeDests = HashSet<String>(remoteRoutes.size)
            for (route in remoteRoutes) {
                val dest = route.destinationHashHex.lowercase()
                if (nowMs >= route.expiresAtMs) continue
                activeDests += dest
                val radio = when {
                    route.interfaceTag.contains("ble", ignoreCase = true) -> RnsRadioKind.BLE_L2CAP
                    route.interfaceTag.contains("aware", ignoreCase = true) -> RnsRadioKind.WIFI_AWARE
                    else -> null
                }
                val nextHop = route.nextHopHex?.lowercase()?.takeIf { it.isNotBlank() } ?: dest
                recordRouteLocked(
                    destinationHashHex = dest,
                    nextHopPeerId = nextHop,
                    hopCount = route.hops.coerceAtLeast(1),
                    linkRttMs = lastLinkRttMs ?: peerMetrics[nextHop]?.rttMs,
                    estimatedBandwidthBps = peerMetrics[nextHop]?.estimatedBandwidthBps,
                    viaRadio = radio ?: peerRadios[nextHop],
                    nowMs = route.lastActivityAtMs.takeIf { it > 0L } ?: nowMs,
                )
            }
            for ((dest, entry) in routes) {
                if (entry.reachable && dest !in activeDests) {
                    routes[dest] = entry.copy(reachable = false, updatedAtMs = nowMs)
                    pendingReconvergence.putIfAbsent(
                        dest,
                        LostRouteMarker(
                            previousNextHop = entry.nextHopPeerId,
                            previousHops = entry.hopCount,
                            lostAtMs = nowMs,
                        ),
                    )
                }
            }
            publishLocked()
        }
    }

    /** Marks all routes traversing [peerId] unreachable and starts reconvergence timing. */
    fun onPeerDisconnected(peerId: String, nowMs: Long = clockMs()) {
        synchronized(lock) {
            markPeerDisconnectedLocked(peerId, nowMs)
            publishLocked()
        }
    }

    /** Prunes stale unreachable routes older than [maxAgeMs]. */
    fun pruneStaleRoutes(maxAgeMs: Long, nowMs: Long = clockMs()): Int {
        synchronized(lock) {
            val before = routes.size
            routes.entries.removeIf { (_, entry) ->
                (nowMs - entry.updatedAtMs) >= maxAgeMs
            }
            pendingReconvergence.entries.removeIf { (_, marker) ->
                (nowMs - marker.lostAtMs) >= maxAgeMs
            }
            val removed = before - routes.size
            if (removed > 0) publishLocked()
            return removed
        }
    }

    /** Formats a [CommLinkStatus] for [CommTransportKind.RNS_MESH] enriched with hop & RTT metrics. */
    fun toRnsMeshLinkStatus(baseStatus: CommLinkStatus? = null): CommLinkStatus {
        val snap = mutableSnapshot.value
        val baseReachability = baseStatus?.reachability ?: CommReachability.UNAVAILABLE
        val reachability = when {
            snap.reachableDestinations > 0 -> CommReachability.CONNECTED
            baseReachability == CommReachability.CONNECTED -> CommReachability.CONNECTED
            baseReachability == CommReachability.DISCOVERING -> CommReachability.DISCOVERING
            else -> baseReachability
        }
        val label = when {
            snap.reachableDestinations > 0 -> {
                val rttPart = snap.bestLinkRttMs?.let { " · ${it}ms" }.orEmpty()
                val reconvPart = snap.lastReconvergenceMs?.let { " · 恢复${it}ms" }.orEmpty()
                "已连接 (${snap.reachableDestinations}节点 · ${snap.maxHopCount}跳$rttPart$reconvPart)"
            }
            baseStatus != null -> baseStatus.statusLabel
            else -> "未连接"
        }
        return (baseStatus ?: CommLinkStatus(CommTransportKind.RNS_MESH, reachability, label)).copy(
            reachability = reachability,
            statusLabel = label,
            peerCount = maxOf(baseStatus?.peerCount ?: 0, snap.reachableDestinations),
        )
    }

    /** Merges RNS multi-hop topology state into [CommNetworkState]. */
    fun applyToNetworkState(base: CommNetworkState): CommNetworkState {
        val updatedRns = toRnsMeshLinkStatus(base.links[CommTransportKind.RNS_MESH])
        val links = base.links.toMutableMap().apply {
            put(CommTransportKind.RNS_MESH, updatedRns)
        }
        return base.copy(
            links = links,
            bestRoute = CommRouterPolicy.selectBestRoute(links, base.serverReachable),
            reachablePeerCount = maxOf(base.reachablePeerCount, updatedRns.peerCount),
        )
    }

    private fun recordRouteLocked(
        destinationHashHex: String,
        nextHopPeerId: String,
        hopCount: Int,
        linkRttMs: Long?,
        estimatedBandwidthBps: Long?,
        viaRadio: RnsRadioKind?,
        nowMs: Long,
    ) {
        val marker = pendingReconvergence.remove(destinationHashHex)
        if (marker != null) {
            val reconvMs = (nowMs - marker.lostAtMs).coerceAtLeast(0L)
            reconvergenceCount++
            lastReconvergenceMs = reconvMs
            if (reconvergences.size >= 16) reconvergences.removeFirst()
            reconvergences.addLast(
                RnsReconvergenceEvent(
                    destinationHashHex = destinationHashHex,
                    previousNextHop = marker.previousNextHop,
                    newNextHop = nextHopPeerId,
                    previousHops = marker.previousHops,
                    newHops = hopCount,
                    reconvergenceMs = reconvMs,
                    timestampMs = nowMs,
                ),
            )
        }
        routes[destinationHashHex] = RnsRouteHopEntry(
            destinationHashHex = destinationHashHex,
            nextHopPeerId = nextHopPeerId,
            hopCount = hopCount,
            linkRttMs = linkRttMs,
            estimatedBandwidthBps = estimatedBandwidthBps,
            viaRadio = viaRadio,
            updatedAtMs = nowMs,
            reachable = true,
        )
    }

    private fun markPeerDisconnectedLocked(peerId: String, nowMs: Long) {
        for ((dest, entry) in routes) {
            if (entry.reachable && (entry.nextHopPeerId == peerId || dest == peerId)) {
                routes[dest] = entry.copy(reachable = false, updatedAtMs = nowMs)
                pendingReconvergence.putIfAbsent(
                    dest,
                    LostRouteMarker(
                        previousNextHop = entry.nextHopPeerId,
                        previousHops = entry.hopCount,
                        lostAtMs = nowMs,
                    ),
                )
            }
        }
    }

    private fun publishLocked() {
        val activeRoutes = routes.values.filter { it.reachable }
        val maxHops = activeRoutes.maxOfOrNull { it.hopCount } ?: 0
        val avgHops = if (activeRoutes.isEmpty()) 0.0 else activeRoutes.map { it.hopCount }.average()
        val bestRtt = activeRoutes.mapNotNull { it.linkRttMs }.minOrNull()
        val relays = activeRoutes.filter { it.hopCount > 1 }.map { it.nextHopPeerId }.distinct().size
        mutableSnapshot.value = RnsTopologySnapshot(
            knownDestinations = routes.size,
            reachableDestinations = activeRoutes.size,
            maxHopCount = maxHops,
            averageHopCount = avgHops,
            bestLinkRttMs = bestRtt,
            activeRelayCount = relays,
            reconvergenceCount = reconvergenceCount,
            lastReconvergenceMs = lastReconvergenceMs,
            routes = routes.toMap(),
            recentReconvergences = reconvergences.toList(),
        )
    }
}
