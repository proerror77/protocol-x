package com.mandonothing.ops.comm.aware

/**
 * Deterministic neighbor admission, RSSI/RTT/staleness-aware pruning, and anti-partition peer
 * rotation manager replacing the fixed `MAX_LINKS = 3` cap.
 */
internal class PeerCapacityManager(
    private val radio: RnsRadioKind,
    private val config: RnsLinkConfig = RnsLinkConfig(),
) {
    enum class PeerState {
        STANDBY,
        CONNECTING,
        ACTIVE,
    }

    sealed interface AdmissionDecision {
        data object Admitted : AdmissionDecision
        data class PreemptedAndAdmitted(val evictedPeerId: String) : AdmissionDecision
        data class RotatedAndAdmitted(val rotatedOutPeerId: String) : AdmissionDecision
        data object QueuedStandby : AdmissionDecision
        data class Suppressed(val untilMs: Long) : AdmissionDecision
    }

    data class MaintenanceActions(
        val evictPeers: List<String> = emptyList(),
        val admitPeers: List<String> = emptyList(),
        val rotatedOutPeers: List<String> = emptyList(),
        val stalePrunedCount: Int = 0,
    )

    data class CapacityStats(
        val activeCount: Int,
        val connectingCount: Int,
        val standbyCount: Int,
        val suppressedCount: Int,
        val totalPruned: Long,
        val totalRotated: Long,
    )

    private data class PeerEntry(
        var metrics: RnsPeerQualityMetrics,
        var state: PeerState,
        var everConnected: Boolean = false,
        var lastRotationAtMs: Long = 0L,
    )

    private val lock = Any()
    private val peers = LinkedHashMap<String, PeerEntry>()
    private val suppressedUntil = HashMap<String, Long>()
    private var totalPruned: Long = 0L
    private var totalRotated: Long = 0L
    private var lastRotationTickMs: Long = 0L

    val maxActiveLinks: Int
        get() = config.maxLinksFor(radio)

    /** Records discovery of [peerId] and updates its RSSI / last-seen timestamp. */
    fun onPeerDiscovered(
        peerId: String,
        rssiDbm: Int? = null,
        isBridgeCandidate: Boolean = false,
        nowMs: Long,
    ): RnsPeerQualityMetrics = synchronized(lock) {
        val existing = peers[peerId]
        if (existing != null) {
            val updated = existing.metrics.copy(
                rssiDbm = rssiDbm ?: existing.metrics.rssiDbm,
                lastSeenMs = nowMs,
                lastActiveMs = if (existing.state == PeerState.ACTIVE) existing.metrics.lastActiveMs else nowMs,
                isBridgeCandidate = existing.metrics.isBridgeCandidate || isBridgeCandidate,
            )
            existing.metrics = updated
            return updated
        }
        evictExcessStandbyLocked(nowMs)
        val created = RnsPeerQualityMetrics(
            peerId = peerId,
            radio = radio,
            rssiDbm = rssiDbm,
            estimatedBandwidthBps = radio.defaultBitrateBps,
            lastSeenMs = nowMs,
            lastActiveMs = nowMs,
            isBridgeCandidate = isBridgeCandidate,
        )
        peers[peerId] = PeerEntry(created, PeerState.STANDBY)
        return created
    }

    /**
     * Evaluates whether [peerId] can enter [PeerState.CONNECTING] immediately, preempting a stale
     * or significantly lower-quality active peer when at capacity.
     */
    fun evaluateAdmission(peerId: String, nowMs: Long): AdmissionDecision = synchronized(lock) {
        val suppressedExpiry = suppressedUntil[peerId]
        if (suppressedExpiry != null && nowMs < suppressedExpiry) {
            return AdmissionDecision.Suppressed(suppressedExpiry)
        }
        suppressedUntil.remove(peerId)

        val entry = peers[peerId] ?: run {
            onPeerDiscovered(peerId = peerId, nowMs = nowMs)
            peers.getValue(peerId)
        }

        if (entry.state == PeerState.ACTIVE || entry.state == PeerState.CONNECTING) {
            return AdmissionDecision.Admitted
        }

        val occupiedCount = peers.values.count { it.state != PeerState.STANDBY }
        if (occupiedCount < maxActiveLinks) {
            entry.state = PeerState.CONNECTING
            entry.metrics = entry.metrics.copy(lastActiveMs = nowMs)
            return AdmissionDecision.Admitted
        }

        // 1. Reclaim any stale active peer first.
        val staleCandidate = findWorstActivePeerLocked(nowMs, onlyStale = true)
        if (staleCandidate != null) {
            demoteToStandbyLocked(staleCandidate, nowMs, isRotation = false)
            entry.state = PeerState.CONNECTING
            entry.metrics = entry.metrics.copy(lastActiveMs = nowMs)
            return AdmissionDecision.PreemptedAndAdmitted(staleCandidate.metrics.peerId)
        }

        // 2. Quality-based preemption when the new peer materially outperforms the worst incumbent.
        val incumbent = findWorstActivePeerLocked(nowMs, onlyStale = false)
        if (incumbent != null) {
            val dwellMs = nowMs - incumbent.metrics.connectedSinceMs
            val candidateScore = entry.metrics.qualityScore(nowMs, config.stalePeerTimeoutMs)
            val incumbentScore = incumbent.metrics.qualityScore(nowMs, config.stalePeerTimeoutMs)
            if (dwellMs >= config.minPeerDwellMs &&
                candidateScore - incumbentScore >= config.preemptionScoreMargin
            ) {
                demoteToStandbyLocked(incumbent, nowMs, isRotation = false)
                entry.state = PeerState.CONNECTING
                entry.metrics = entry.metrics.copy(lastActiveMs = nowMs)
                return AdmissionDecision.PreemptedAndAdmitted(incumbent.metrics.peerId)
            }
        }

        return AdmissionDecision.QueuedStandby
    }

    /** Marks [peerId] as connected and starts its dwell timer. */
    fun markConnected(peerId: String, nowMs: Long) = synchronized(lock) {
        suppressedUntil.remove(peerId)
        val entry = peers.getOrPut(peerId) {
            PeerEntry(
                metrics = RnsPeerQualityMetrics(peerId = peerId, radio = radio, lastSeenMs = nowMs, lastActiveMs = nowMs),
                state = PeerState.ACTIVE,
            )
        }
        entry.state = PeerState.ACTIVE
        entry.everConnected = true
        entry.metrics = entry.metrics.copy(
            lastSeenMs = nowMs,
            lastActiveMs = nowMs,
            connectedSinceMs = if (entry.metrics.connectedSinceMs == 0L) nowMs else entry.metrics.connectedSinceMs,
        )
    }

    /** Updates RTT, RSSI, bandwidth EWMA, packet loss, and traffic counters for [peerId]. */
    fun recordMetrics(
        peerId: String,
        rssiDbm: Int? = null,
        rttMs: Long? = null,
        measuredBandwidthBps: Long? = null,
        packetLossRate: Double? = null,
        bytesSentDelta: Long = 0L,
        bytesReceivedDelta: Long = 0L,
        framesSentDelta: Long = 0L,
        framesReceivedDelta: Long = 0L,
        nowMs: Long,
    ): RnsPeerQualityMetrics? = synchronized(lock) {
        val entry = peers[peerId] ?: return null
        val prev = entry.metrics
        val smoothedRtt = when {
            rttMs == null -> prev.rttMs
            prev.rttMs == null -> rttMs.coerceAtLeast(1L)
            else -> ((prev.rttMs * 7L + rttMs.coerceAtLeast(1L) * 3L) / 10L).coerceAtLeast(1L)
        }
        val smoothedBw = when {
            measuredBandwidthBps == null -> prev.estimatedBandwidthBps
            else -> ((prev.estimatedBandwidthBps * 7L + measuredBandwidthBps.coerceAtLeast(1_000L) * 3L) / 10L)
                .coerceAtLeast(1_000L)
        }
        val updated = prev.copy(
            rssiDbm = rssiDbm ?: prev.rssiDbm,
            rttMs = smoothedRtt,
            estimatedBandwidthBps = smoothedBw,
            packetLossRate = packetLossRate?.coerceIn(0.0, 1.0) ?: prev.packetLossRate,
            lastSeenMs = nowMs,
            lastActiveMs = nowMs,
            bytesSent = prev.bytesSent + bytesSentDelta.coerceAtLeast(0L),
            bytesReceived = prev.bytesReceived + bytesReceivedDelta.coerceAtLeast(0L),
            framesSent = prev.framesSent + framesSentDelta.coerceAtLeast(0L),
            framesReceived = prev.framesReceived + framesReceivedDelta.coerceAtLeast(0L),
        )
        entry.metrics = updated
        return updated
    }

    /** Transitions [peerId] back to standby (and applies suppression cooldown on failure). */
    fun markDisconnected(peerId: String, failed: Boolean, nowMs: Long) {
        synchronized(lock) {
            val entry = peers[peerId] ?: return
            entry.state = PeerState.STANDBY
            entry.metrics = entry.metrics.copy(
                connectedSinceMs = 0L,
                lastDisconnectedMs = nowMs,
            )
            if (failed && config.suppressTtlMs > 0L) {
                suppressedUntil[peerId] = nowMs + config.suppressTtlMs
            }
        }
    }

    /** Removes a peer entirely when discovery reports service lost. */
    fun removePeer(peerId: String) = synchronized(lock) {
        peers.remove(peerId)
        suppressedUntil.remove(peerId)
    }

    /**
     * Periodic maintenance: prunes stale active peers, fills open slots from highest-scoring standby
     * peers, and performs deterministic anti-partition rotation when unvisited standby neighbors wait.
     */
    fun pruneAndRotate(nowMs: Long): MaintenanceActions = synchronized(lock) {
        val evictPeers = mutableListOf<String>()
        val admitPeers = mutableListOf<String>()
        val rotatedOutPeers = mutableListOf<String>()
        var staleCount = 0

        // Expire old suppression entries.
        suppressedUntil.entries.removeIf { it.value <= nowMs }

        // 1. Prune stale active links.
        val justEvictedIds = HashSet<String>()
        val staleEntries = peers.values.filter {
            it.state == PeerState.ACTIVE && (nowMs - it.metrics.lastActiveMs) >= config.stalePeerTimeoutMs
        }
        for (stale in staleEntries) {
            justEvictedIds += stale.metrics.peerId
            evictPeers += stale.metrics.peerId
            demoteToStandbyLocked(stale, nowMs, isRotation = false)
            staleCount++
        }

        // 2. Fill any free slots with best eligible standby peers (excluding peers just pruned in this tick).
        var occupied = peers.values.count { it.state != PeerState.STANDBY }
        val eligibleStandby = eligibleStandbyLocked(nowMs)
            .filter { it.metrics.peerId !in justEvictedIds }
            .sortedByDescending { standbyPriority(it, nowMs) }
        val standbyIterator = eligibleStandby.iterator()
        while (occupied < maxActiveLinks && standbyIterator.hasNext()) {
            val candidate = standbyIterator.next()
            candidate.state = PeerState.CONNECTING
            candidate.metrics = candidate.metrics.copy(lastActiveMs = nowMs)
            admitPeers += candidate.metrics.peerId
            occupied++
        }

        // 3. Anti-partition rotation: if healthy standby peers haven't had a turn for >= rotationInterval,
        //    swap out the lowest-value active peer that has satisfied minPeerDwellMs.
        val waitingStandby = eligibleStandbyLocked(nowMs)
            .filter { (nowMs - it.metrics.lastDisconnectedMs) >= config.peerRotationIntervalMs }
            .sortedByDescending { standbyPriority(it, nowMs) }

        if (waitingStandby.isNotEmpty() &&
            occupied >= maxActiveLinks &&
            (nowMs - lastRotationTickMs) >= config.peerRotationIntervalMs
        ) {
            val victim = peers.values
                .filter {
                    it.state == PeerState.ACTIVE &&
                        (nowMs - it.metrics.connectedSinceMs) >= config.minPeerDwellMs
                }
                .minByOrNull { activeRetentionScore(it, nowMs) }

            val incoming = waitingStandby.firstOrNull()
            if (victim != null && incoming != null) {
                demoteToStandbyLocked(victim, nowMs, isRotation = true)
                rotatedOutPeers += victim.metrics.peerId
                evictPeers += victim.metrics.peerId

                incoming.state = PeerState.CONNECTING
                incoming.metrics = incoming.metrics.copy(lastActiveMs = nowMs)
                admitPeers += incoming.metrics.peerId
                lastRotationTickMs = nowMs
            }
        }

        MaintenanceActions(
            evictPeers = evictPeers,
            admitPeers = admitPeers,
            rotatedOutPeers = rotatedOutPeers,
            stalePrunedCount = staleCount,
        )
    }

    fun activePeers(): List<RnsPeerQualityMetrics> = synchronized(lock) {
        peers.values.filter { it.state == PeerState.ACTIVE }.map { it.metrics }
    }

    fun standbyPeers(): List<RnsPeerQualityMetrics> = synchronized(lock) {
        peers.values.filter { it.state == PeerState.STANDBY }.map { it.metrics }
    }

    fun metricsFor(peerId: String): RnsPeerQualityMetrics? = synchronized(lock) {
        peers[peerId]?.metrics
    }

    fun stats(): CapacityStats = synchronized(lock) {
        CapacityStats(
            activeCount = peers.values.count { it.state == PeerState.ACTIVE },
            connectingCount = peers.values.count { it.state == PeerState.CONNECTING },
            standbyCount = peers.values.count { it.state == PeerState.STANDBY },
            suppressedCount = suppressedUntil.size,
            totalPruned = totalPruned,
            totalRotated = totalRotated,
        )
    }

    fun clear() = synchronized(lock) {
        peers.clear()
        suppressedUntil.clear()
        totalPruned = 0L
        totalRotated = 0L
        lastRotationTickMs = 0L
    }

    private fun findWorstActivePeerLocked(nowMs: Long, onlyStale: Boolean): PeerEntry? {
        return peers.values
            .filter { entry ->
                entry.state == PeerState.ACTIVE &&
                    (!onlyStale || (nowMs - entry.metrics.lastActiveMs) >= config.stalePeerTimeoutMs)
            }
            .minByOrNull { activeRetentionScore(it, nowMs) }
    }

    private fun eligibleStandbyLocked(nowMs: Long): List<PeerEntry> {
        return peers.values.filter { entry ->
            if (entry.state != PeerState.STANDBY) return@filter false
            val suppressedExpiry = suppressedUntil[entry.metrics.peerId]
            suppressedExpiry == null || nowMs >= suppressedExpiry
        }
    }

    private fun standbyPriority(entry: PeerEntry, nowMs: Long): Double {
        val base = entry.metrics.qualityScore(nowMs, config.stalePeerTimeoutMs)
        val unvisitedBoost = if (!entry.everConnected) 50.0 else 0.0
        val starvationWaitMs = (nowMs - entry.metrics.lastDisconnectedMs).coerceAtLeast(0L)
        val starvationBoost = if (config.peerRotationIntervalMs > 0L) {
            ((starvationWaitMs.toDouble() / config.peerRotationIntervalMs.toDouble()).coerceAtMost(3.0)) * 12.0
        } else {
            0.0
        }
        return base + unvisitedBoost + starvationBoost
    }

    private fun activeRetentionScore(entry: PeerEntry, nowMs: Long): Double {
        val base = entry.metrics.qualityScore(nowMs, config.stalePeerTimeoutMs)
        val tenureMs = (nowMs - entry.metrics.connectedSinceMs).coerceAtLeast(0L)
        // Older links gradually yield during rotation if standby neighbors are starved.
        val ageDecay = if (config.peerRotationIntervalMs > 0L) {
            ((tenureMs.toDouble() / config.peerRotationIntervalMs.toDouble()).coerceAtMost(3.0)) * 5.0
        } else {
            0.0
        }
        return base - ageDecay
    }

    private fun demoteToStandbyLocked(entry: PeerEntry, nowMs: Long, isRotation: Boolean) {
        entry.state = PeerState.STANDBY
        entry.metrics = entry.metrics.copy(
            connectedSinceMs = 0L,
            lastDisconnectedMs = nowMs,
        )
        if (isRotation) {
            entry.lastRotationAtMs = nowMs
            totalRotated++
        } else {
            totalPruned++
        }
    }

    private fun evictExcessStandbyLocked(nowMs: Long) {
        val standby = peers.values.filter { it.state == PeerState.STANDBY }
        if (standby.size < config.maxStandbyPeers) return
        val victim = standby.minByOrNull { it.metrics.qualityScore(nowMs, config.stalePeerTimeoutMs) }
        if (victim != null) {
            peers.remove(victim.metrics.peerId)
        }
    }
}
