package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.FieldOfflineStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Outcome of classifying an HTTP 409 Conflict during offline-queue replay
 * per `docs/RNS-TOC-CONTRACT.md` §5.4.3.
 */
internal sealed interface Rns409Resolution {
    /** Case A: the server object already achieved the action's target state via an earlier/peer commit. */
    data class AlreadyAchieved(
        val currentVersion: Int,
        val currentStatus: String,
        val reason: String,
    ) : Rns409Resolution

    /** Case B: server version drifted due to a distinct semantic mutation (e.g., dispatcher reassigned vehicle). */
    data class VersionDrift(
        val currentVersion: Int,
        val currentStatus: String,
        val detail: String,
    ) : Rns409Resolution
}

/**
 * Pure Kotlin RNS-09 helper for deterministic task envelope construction,
 * causal `client_seq` ordering, and HTTP 409 version-drift reconciliation.
 */
internal object RnsOfflineSyncReconciler {
    fun buildTaskEnvelope(
        entry: FieldOfflineStore.Queued,
        actor: String,
        deviceId: String,
        originRadio: String = "HTTPS",
        channelId: String? = null,
    ): JSONObject {
        val isHelp = entry.action == "help"
        return if (isHelp) {
            val payload = JSONObject()
                .put("scope", "qrf_assistance")
                .put("text", entry.parameters.optString("note").ifBlank { "现场需要协助" })
                .put("subject_ref", entry.ref)
                .put("assistance", true)
                .put("confirmation_required", true)
                .put("task_version", entry.version)
            RnsEnvelopeCodec.encodeEnvelopeV1(
                envelopeId = entry.envelopeId,
                kind = "message",
                eventId = entry.event,
                channelId = entry.parameters.optString("nearby_channel").takeIf { it.isNotBlank() } ?: channelId,
                actor = actor.ifBlank { entry.owner },
                deviceId = deviceId,
                idempotencyKey = entry.idempotencyKey,
                payload = payload,
                priority = 3,
                originRadio = originRadio,
            )
        } else {
            val payload = JSONObject()
                .put("op", "action")
                .put("target_ref", entry.ref)
                .put("expected_version", entry.version)
                .put("action_type", entry.action)
                .put("parameters", entry.parameters)
                .put("client_seq", entry.clientSeq)
            RnsEnvelopeCodec.encodeEnvelopeV1(
                envelopeId = entry.envelopeId,
                kind = "task",
                eventId = entry.event,
                channelId = channelId,
                actor = actor.ifBlank { entry.owner },
                deviceId = deviceId,
                idempotencyKey = entry.idempotencyKey,
                payload = payload,
                priority = 2,
                originRadio = originRadio,
            )
        }
    }

    /**
     * Sorts queued entries in causal drain order: priority assistance first, then ascending `clientSeq`
     * per `(event, target_ref)` so sequential offline steps (`acknowledge` -> `confirm_progress`) replay safely.
     */
    fun drainOrder(entries: List<FieldOfflineStore.Queued>): List<FieldOfflineStore.Queued> =
        entries.sortedWith(
            compareBy<FieldOfflineStore.Queued>(
                { if (it.action == "help") 0 else 1 },
                { it.clientSeq },
                { it.version },
                { it.id },
            ),
        )

    /**
     * Inspects a structured HTTP 409 conflict payload (`current_version`, `current_status`, `current_progress`, `detail`)
     * to distinguish Case A (identical desired state already achieved on TOC) from Case B (semantic drift requiring review).
     */
    fun classify409(
        entry: FieldOfflineStore.Queued,
        conflictPayload: JSONObject?,
        fallbackMessage: String = "对象版本已变化，请刷新任务后重新确认",
    ): Rns409Resolution {
        if (conflictPayload == null) {
            return Rns409Resolution.VersionDrift(
                currentVersion = entry.version,
                currentStatus = "",
                detail = fallbackMessage,
            )
        }
        val currentVersion = conflictPayload.optInt("current_version", entry.version)
        val currentStatus = conflictPayload.optString("current_status")
        val progress = conflictPayload.optJSONArray("current_progress")
        val detail = conflictPayload.optString("detail").ifBlank { fallbackMessage }

        val achieved = when (entry.action) {
            "acknowledge" -> currentStatus in setOf("accepted", "in_progress", "completed")
            "decline_response" -> currentStatus == "declined"
            "confirm_progress" -> isProgressIndexCompleted(
                progress = progress,
                index = entry.parameters.optInt("progress_index", -1),
            )
            else -> false
        }
        return if (achieved) {
            Rns409Resolution.AlreadyAchieved(
                currentVersion = currentVersion,
                currentStatus = currentStatus,
                reason = detail,
            )
        } else {
            Rns409Resolution.VersionDrift(
                currentVersion = currentVersion,
                currentStatus = currentStatus,
                detail = detail,
            )
        }
    }

    private fun isProgressIndexCompleted(progress: JSONArray?, index: Int): Boolean {
        if (progress == null || index !in 0 until progress.length()) return false
        val step = progress.optJSONObject(index) ?: return false
        val status = step.optString("status")
        return status == "completed" || status == "done" || !step.isNull("confirmed_at")
    }
}

/**
 * Pure Kotlin RNS-05 credential/IFAC/revocation synchronizer parsing TOC `/nearby` and
 * `/nearby/revocations` responses into [RnsLocalStore] and live [RnsRuntimeCoordinator].
 */
internal object RnsCredentialLeaseSync {
    fun applyNearbyCredentials(
        eventId: String,
        leases: JSONObject,
        store: RnsLocalStore,
        coordinator: RnsRuntimeCoordinator? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RnsIfacLease> {
        val channels = leases.optJSONArray("channels") ?: return emptyList()
        val gatewayDest = leases.optJSONObject("rns_gateway")?.optString("dest_hash")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: leases.optString("gateway_dest_hash").takeIf { it.isNotBlank() && it != "null" }
            ?: leases.optString("rns_gateway_dest_hash").takeIf { it.isNotBlank() && it != "null" }
        val eventRevoked = leases.optJSONArray("revoked_devices").orEmptyStrings() +
            leases.optJSONArray("revoked_identity_hashes").orEmptyStrings()
        val saved = mutableListOf<RnsIfacLease>()
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i) ?: continue
            val channelId = ch.optString("channel").takeIf { it.isNotBlank() } ?: continue
            val secret = ch.optString("secret").takeIf { it.isNotBlank() } ?: continue
            val expiresMs = Math.multiplyExact(ch.optLong("expires", 0L), 1_000L)
            val ifacObj = ch.optJSONObject("ifac") ?: leases.optJSONObject("ifac")
            val networkName = ifacObj?.optString("network_name")?.takeIf { it.isNotBlank() }
                ?: "protocolx.$eventId.$channelId"
            val passphrase = ifacObj?.optString("passphrase")?.takeIf { it.isNotBlank() }
                ?: RnsEnvelopeCodec.sha256Hex("ifac|$eventId|$channelId|$secret")
            val chRevoked = ch.optJSONArray("revoked_devices").orEmptyStrings() + eventRevoked
            val revokedActors = ch.optJSONArray("revoked_actors").orEmptyStrings()
            val chGateway = ifacObj?.optString("gateway_dest_hash")?.takeIf { it.isNotBlank() && it != "null" }
                ?: ch.optString("gateway_dest_hash").takeIf { it.isNotBlank() && it != "null" }
                ?: gatewayDest
            val peersArr = ch.optJSONArray("rns_peers")
            for (pIdx in 0 until (peersArr?.length() ?: 0)) {
                val peerObj = peersArr?.optJSONObject(pIdx) ?: continue
                val peerDest = peerObj.optString("dest_hash").takeIf { it.isNotBlank() && it != "null" } ?: continue
                store.upsertPeer(
                    RnsPeerRecord(
                        destinationHashHex = peerDest.lowercase(),
                        identityHashHex = peerObj.optString("identity_hash"),
                        appDataText = peerObj.optString("actor"),
                        hops = 1,
                        lastHeardAtMs = nowMs,
                    ),
                )
            }
            val lease = RnsIfacLease(
                eventId = eventId,
                channelId = channelId,
                networkName = networkName,
                passphrase = passphrase,
                ifacSizeBytes = ifacObj?.optInt("ifac_size_bytes", 16) ?: 16,
                requirePrivateIfac = ifacObj?.optBoolean("require_private_ifac", true) ?: true,
                generation = ifacObj?.optString("generation")?.takeIf { it.isNotBlank() }
                    ?: ch.optString("generation").ifBlank { RnsEnvelopeCodec.sha256Hex(secret) },
                expiresAtMs = expiresMs,
                gatewayDestinationHashHex = chGateway,
                revokedDeviceHashes = chRevoked.map { it.lowercase() }.toSet(),
                revokedActors = revokedActors.toSet(),
            )
            store.saveIfacLease(lease)
            coordinator?.applyIfacLease(lease)
            saved += lease
        }
        return saved
    }

    fun applyRevocations(
        eventId: String,
        payload: JSONObject,
        store: RnsLocalStore,
        coordinator: RnsRuntimeCoordinator? = null,
    ) {
        val list = payload.optJSONArray("revocations") ?: return
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val actor = item.optString("revoked_actor").takeIf { it.isNotBlank() && it != "null" }
            val devHash = item.optString("revoked_device_hash").takeIf { it.isNotBlank() && it != "null" }
            val destHash = item.optString("revoked_dest_hash").takeIf { it.isNotBlank() && it != "null" }
            if (destHash != null) {
                if (coordinator != null) coordinator.revokePeer(eventId, destHash, actor)
                else store.revokeInEvent(eventId, destHash, actor)
            }
            if (devHash != null) {
                if (coordinator != null) coordinator.revokePeer(eventId, devHash, actor)
                else store.revokeInEvent(eventId, devHash, actor)
            }
            if (destHash == null && devHash == null && actor != null) {
                if (coordinator != null) coordinator.revokePeer(eventId, null, actor)
                else store.revokeInEvent(eventId, null, actor)
            }
        }
    }

    private fun JSONArray?.orEmptyStrings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { idx -> optString(idx) }.filter { it.isNotBlank() }
    }
}
