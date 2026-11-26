package com.mandonothing.ops.comm.ptt

import com.mandonothing.ops.comm.PttCue
import com.mandonothing.ops.comm.PttPhase
import com.mandonothing.ops.comm.PttSpeaker

/**
 * Distributed PTT floor-control arbiter supporting contention (`BUSY`), priority preemption
 * (`PREEMPT`), deterministic tie-breaking, and lease expiration across 1–5 hop RNS networks.
 */
internal class RnsPttFloorArbiter(
    private val localActorHash: String,
    private val localDisplayName: String,
    private val leaseTtlMs: Long = 15_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    data class FloorHolder(
        val actorHashHex: String,
        val actorName: String,
        val sessionId: String,
        val priority: Int,
        val acquiredAtMs: Long,
        val expiresAtMs: Long,
    )

    sealed interface Decision {
        data class Granted(val holder: FloorHolder, val preemptedPrevious: FloorHolder? = null) : Decision
        data class DeniedBusy(val currentHolder: FloorHolder) : Decision
        data class Released(val previousHolder: FloorHolder?) : Decision
        data class PreemptedLocal(val newHolder: FloorHolder) : Decision
        data object Ignored : Decision
    }

    private val lock = Any()
    private var currentHolder: FloorHolder? = null

    fun currentHolder(nowMs: Long = clockMs()): FloorHolder? = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        currentHolder
    }

    fun currentPhase(connected: Boolean, nowMs: Long = clockMs()): PttPhase {
        if (!connected) return PttPhase.OFFLINE
        val holder = currentHolder(nowMs)
        return if (holder != null && holder.actorHashHex == localActorHash) {
            PttPhase.SPEAKING
        } else {
            PttPhase.LISTENING
        }
    }

    fun currentSpeaker(nowMs: Long = clockMs()): PttSpeaker? {
        val holder = currentHolder(nowMs) ?: return null
        if (holder.actorHashHex == localActorHash) return null
        return PttSpeaker(trackSid = holder.sessionId, name = holder.actorName.ifBlank { holder.actorHashHex.take(8) })
    }

    /** Attempts to claim the PTT floor locally with [priority]. */
    fun requestLocal(
        sessionId: String,
        priority: Int = 1,
        nowMs: Long = clockMs(),
    ): Decision = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        val existing = currentHolder
        if (existing != null && existing.actorHashHex != localActorHash) {
            if (priority <= existing.priority) {
                return Decision.DeniedBusy(existing)
            }
        }
        val granted = FloorHolder(
            actorHashHex = localActorHash,
            actorName = localDisplayName,
            sessionId = sessionId,
            priority = priority,
            acquiredAtMs = nowMs,
            expiresAtMs = nowMs + leaseTtlMs,
        )
        currentHolder = granted
        return Decision.Granted(granted, preemptedPrevious = existing)
    }

    /** Releases local floor claim if held by [localActorHash]. */
    fun releaseLocal(sessionId: String? = null, nowMs: Long = clockMs()): Decision = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        val existing = currentHolder ?: return Decision.Ignored
        if (existing.actorHashHex != localActorHash) return Decision.Ignored
        if (sessionId != null && existing.sessionId != sessionId) return Decision.Ignored
        currentHolder = null
        return Decision.Released(existing)
    }

    /** Processes an inbound [RnsPttFloorControl] from a remote peer over RNS. */
    fun onRemoteControl(
        control: RnsPttFloorControl,
        nowMs: Long = clockMs(),
    ): Pair<Decision, PttCue?> = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        val actor = control.actorHashHex.lowercase()
        when (control.action) {
            RnsPttFloorAction.RELEASE -> {
                val existing = currentHolder
                if (existing != null && existing.actorHashHex == actor) {
                    currentHolder = null
                    Decision.Released(existing) to PttCue.ENDED
                } else {
                    Decision.Ignored to null
                }
            }

            RnsPttFloorAction.BUSY -> {
                val existing = currentHolder
                if (existing != null && existing.actorHashHex == localActorHash) {
                    val holderHex = control.holderHashHex ?: actor
                    val remoteHolder = FloorHolder(
                        actorHashHex = holderHex,
                        actorName = control.actorName.ifBlank { holderHex.take(8) },
                        sessionId = control.sessionId,
                        priority = control.priority,
                        acquiredAtMs = control.timestampMs,
                        expiresAtMs = nowMs + leaseTtlMs,
                    )
                    currentHolder = remoteHolder
                    Decision.PreemptedLocal(remoteHolder) to PttCue.BUSY
                } else {
                    Decision.Ignored to null
                }
            }

            RnsPttFloorAction.REQUEST,
            RnsPttFloorAction.GRANT,
            RnsPttFloorAction.PREEMPT -> {
                val existing = currentHolder
                if (existing == null || existing.actorHashHex == actor ||
                    winsContention(control, actor, existing)
                ) {
                    val wasLocal = existing?.actorHashHex == localActorHash && actor != localActorHash
                    val newHolder = FloorHolder(
                        actorHashHex = actor,
                        actorName = control.actorName.ifBlank { actor.take(8) },
                        sessionId = control.sessionId,
                        priority = control.priority,
                        acquiredAtMs = control.timestampMs,
                        expiresAtMs = nowMs + leaseTtlMs,
                    )
                    currentHolder = newHolder
                    if (wasLocal) {
                        Decision.PreemptedLocal(newHolder) to PttCue.BUSY
                    } else {
                        Decision.Granted(newHolder, preemptedPrevious = existing) to null
                    }
                } else {
                    Decision.DeniedBusy(existing) to null
                }
            }
        }
    }

    private fun winsContention(
        incoming: RnsPttFloorControl,
        incomingActor: String,
        incumbent: FloorHolder,
    ): Boolean {
        if (incoming.action == RnsPttFloorAction.PREEMPT && incoming.priority > incumbent.priority) {
            return true
        }
        if (incoming.priority != incumbent.priority) {
            return incoming.priority > incumbent.priority
        }
        if (incoming.timestampMs != incumbent.acquiredAtMs) {
            return incoming.timestampMs < incumbent.acquiredAtMs
        }
        return incomingActor < incumbent.actorHashHex
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        val existing = currentHolder ?: return
        if (nowMs >= existing.expiresAtMs) {
            currentHolder = null
        }
    }
}
