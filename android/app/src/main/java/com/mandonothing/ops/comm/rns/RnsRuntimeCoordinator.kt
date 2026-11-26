package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Pure Kotlin RNS/LXMF runtime coordinator managed by [RnsRuntimeService].
 * Handles identity persistence, outbox store-and-forward, deduplicated inbound archive,
 * delivery receipt propagation, and clean restarts without Android Service coupling.
 */
internal class RnsRuntimeCoordinator(
    private val store: RnsLocalStore,
    private val engine: RnsCoreEngine,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(
        RnsRuntimeSnapshot(nativeAvailable = engine.isAvailable),
    )
    private val mutableInbound = MutableStateFlow<List<LxmfInboundRecord>>(emptyList())
    private val mutableReceipts = MutableSharedFlow<LxmfDeliveryReceipt>(extraBufferCapacity = 64)
    private val mutablePeers = MutableStateFlow<List<RnsPeerRecord>>(emptyList())
    private val mutableIfacPolicy = MutableStateFlow<RnsIfacPolicy?>(null)
    private val mutableGatewayReceipts = MutableSharedFlow<JSONObject>(extraBufferCapacity = 32)
    private val resourceManager = RnsResourceTransferManager(store, clockMs = clockMs)
    private var activeConfig: RnsRuntimeConfig? = null
    private var activeLease: RnsIfacLease? = null
    private val revokedHashes = mutableSetOf<String>()
    private val revokedActors = mutableSetOf<String>()

    val snapshot: StateFlow<RnsRuntimeSnapshot> = mutableSnapshot.asStateFlow()
    val inboundMessages: StateFlow<List<LxmfInboundRecord>> = mutableInbound.asStateFlow()
    val deliveryReceipts: SharedFlow<LxmfDeliveryReceipt> = mutableReceipts.asSharedFlow()
    val peers: StateFlow<List<RnsPeerRecord>> = mutablePeers.asStateFlow()
    val ifacPolicy: StateFlow<RnsIfacPolicy?> = mutableIfacPolicy.asStateFlow()
    val gatewayReceipts: SharedFlow<JSONObject> = mutableGatewayReceipts.asSharedFlow()

    fun start(config: RnsRuntimeConfig): Boolean = synchronized(lock) {
        val persistedLease = store.loadIfacLease(config.eventId, config.channelId)?.takeIf { it.isLive(clockMs()) }
        val resolvedIfac = config.ifac ?: persistedLease?.toPolicy()
        val mergedRevoked = (config.revokedIdentityHashes + persistedLease?.revokedDeviceHashes.orEmpty())
            .map { it.lowercase() }
            .distinct()
        val effectiveConfig = config.copy(
            ifac = resolvedIfac,
            revokedIdentityHashes = mergedRevoked,
        )
        activeConfig = effectiveConfig
        activeLease = persistedLease
        revokedHashes.clear()
        revokedHashes += mergedRevoked
        revokedActors.clear()
        persistedLease?.let { revokedActors += it.revokedActors }
        mutableIfacPolicy.value = resolvedIfac
        store.saveRuntimeConfig(effectiveConfig)
        mutablePeers.value = store.loadPeers()
        mutableInbound.value = store.inboundMessages(eventId = effectiveConfig.eventId, channelId = effectiveConfig.channelId)

        if (!engine.isAvailable) {
            val savedId = store.loadIdentity(effectiveConfig.accountKey)
            mutableSnapshot.value = RnsRuntimeSnapshot(
                running = false,
                nativeAvailable = false,
                identityHashHex = savedId?.identityHashHex.orEmpty(),
                destinationHashHex = savedId?.destinationHashHex.orEmpty(),
                knownPeersCount = mutablePeers.value.size,
                pendingOutboxCount = store.pendingOutbox(effectiveConfig.accountKey, effectiveConfig.eventId).size,
                lastError = engine.unavailableReason ?: "RNS native library unavailable",
            )
            return false
        }

        val existing = store.loadIdentity(effectiveConfig.accountKey)
        val started = runCatching {
            val status = engine.start(effectiveConfig, existing?.secretBytes, ::handleCoreEvent)
            val secret = engine.identitySecret()
            if (existing == null && secret != null && status.identityHashHex.isNotEmpty()) {
                store.saveIdentity(
                    StoredRnsIdentity(
                        accountKey = effectiveConfig.accountKey,
                        secretBytes = secret,
                        identityHashHex = status.identityHashHex,
                        destinationHashHex = status.destinationHashHex,
                        createdAtMs = clockMs(),
                    ),
                )
            }
            mergedRevoked.forEach { hash -> runCatching { engine.revokePeer(hash, "lease_revoked") } }
            val appData = effectiveConfig.actorId.ifBlank { effectiveConfig.channelId }.toByteArray(Charsets.UTF_8)
            runCatching { engine.announce(appData) }
            runCatching { engine.announceLxmf(effectiveConfig.lxmfDisplayName ?: effectiveConfig.actorId.ifBlank { null }) }
            runCatching {
                engine.persistedLxmfMessages().forEach { handleCoreEvent(it) }
                engine.persistedLxmfReceipts().forEach { handleCoreEvent(it) }
            }
            status
        }

        return started.fold(
            onSuccess = { status ->
                refreshSnapshot(status, lastError = "")
                retryPendingOutbox()
                true
            },
            onFailure = { error ->
                mutableSnapshot.value = RnsRuntimeSnapshot(
                    running = false,
                    nativeAvailable = engine.isAvailable,
                    identityHashHex = existing?.identityHashHex.orEmpty(),
                    destinationHashHex = existing?.destinationHashHex.orEmpty(),
                    knownPeersCount = mutablePeers.value.size,
                    pendingOutboxCount = store.pendingOutbox(effectiveConfig.accountKey, effectiveConfig.eventId).size,
                    lastError = error.message ?: "RNS node failed to start",
                )
                false
            },
        )
    }

    fun applyIfacLease(lease: RnsIfacLease) = synchronized(lock) {
        store.saveIfacLease(lease)
        val current = activeConfig
        if (current != null && current.eventId == lease.eventId && current.channelId == lease.channelId) {
            val policy = lease.toPolicy()
            activeLease = lease
            mutableIfacPolicy.value = policy
            revokedHashes += lease.revokedDeviceHashes.map { it.lowercase() }
            revokedActors += lease.revokedActors
            val updated = current.copy(ifac = policy, revokedIdentityHashes = revokedHashes.toList())
            activeConfig = updated
            store.saveRuntimeConfig(updated)
            if (engine.isAvailable) {
                runCatching { engine.rotateIfacCredentials(policy) }
                lease.revokedDeviceHashes.forEach { hash ->
                    runCatching { engine.revokePeer(hash, "ifac_rotation") }
                }
            }
        }
    }

    fun revokePeer(eventId: String, deviceOrIdentityHash: String? = null, actorId: String? = null) = synchronized(lock) {
        store.revokeInEvent(eventId, deviceOrIdentityHash, actorId)
        if (!deviceOrIdentityHash.isNullOrBlank()) {
            val norm = deviceOrIdentityHash.lowercase()
            revokedHashes += norm
            if (engine.isAvailable) runCatching { engine.revokePeer(norm, "revoked") }
        }
        if (!actorId.isNullOrBlank()) revokedActors += actorId
    }

    fun isPeerRevoked(hashOrActor: String): Boolean = synchronized(lock) {
        if (hashOrActor.isBlank()) return@synchronized false
        val norm = hashOrActor.lowercase()
        norm in revokedHashes || hashOrActor in revokedActors
    }

    fun activeGatewayDestinationHex(): String? = synchronized(lock) {
        activeLease?.takeIf { it.isLive(clockMs()) }?.gatewayDestinationHashHex
    }

    fun bindActorIdentity(accountKey: String, eventId: String, actorId: String, deviceTokenHash: String): RnsIdentityBinding? =
        synchronized(lock) {
            val snap = mutableSnapshot.value
            val destHash = snap.lxmfDestinationHashHex.ifBlank { snap.destinationHashHex }
            if (destHash.isBlank() && snap.identityHashHex.isBlank()) return@synchronized store.loadIdentityBinding(accountKey, eventId)
            val nativeBinding = if (engine.isAvailable) runCatching { engine.bindActorDevice(actorId, deviceTokenHash) }.getOrNull() else null
            val digest = nativeBinding?.bindingDigestHex
                ?: RnsEnvelopeCodec.sha256Hex("$actorId|$deviceTokenHash|$destHash|${snap.identityHashHex}")
            val edHex = nativeBinding?.ed25519PublicKeyHex ?: engine.ed25519PublicKeyHex()
            val binding = RnsIdentityBinding(
                accountKey = accountKey,
                eventId = eventId,
                actorId = actorId,
                deviceTokenHash = deviceTokenHash,
                rnsIdentityHashHex = snap.identityHashHex,
                rnsDestinationHashHex = destHash,
                lxmfDestinationHashHex = snap.lxmfDestinationHashHex,
                ed25519PubHex = edHex,
                bindingDigestHex = digest,
                boundAtMs = clockMs(),
            )
            store.saveIdentityBinding(binding)
            binding
        }

    fun sendAttachmentResource(
        payload: RnsResourceTransferManager.TransferPayload,
        scope: CommScope,
        destinationHashHex: String,
    ): RnsAttachmentTransferState =
        resourceManager.sendOrResume(payload, scope, destinationHashHex, ::enqueueAndSend)

    fun markInboundSyncedToServer(envelopeId: String) = synchronized(lock) {
        if (store.markInboundSyncedToServer(envelopeId)) {
            val cfg = activeConfig
            mutableInbound.value = store.inboundMessages(eventId = cfg?.eventId, channelId = cfg?.channelId)
        }
    }

    fun restoreIfConfigured(): Boolean {
        val saved = store.loadRuntimeConfig() ?: return false
        return start(saved)
    }

    fun stop(clearPersistedConfig: Boolean = false): Unit = synchronized(lock) {
        runCatching { engine.stop() }
        if (clearPersistedConfig) store.clearRuntimeConfig()
        val cfg = activeConfig
        activeConfig = if (clearPersistedConfig) null else cfg
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            running = false,
            activeLinksCount = 0,
            pendingOutboxCount = cfg?.let { store.pendingOutbox(it.accountKey, it.eventId).size } ?: 0,
        )
    }

    fun enqueueAndSend(outbound: CommOutboundMessage, destinationHashHex: String): CommSendResult {
        if (destinationHashHex.isBlank()) {
            return CommSendResult.Rejected("目标 RNS 地址未知", retryable = true)
        }
        val mode = RnsEnvelopeCodec.selectDeliveryMode(
            outbound = outbound,
            hasDirectLink = engine.isAvailable && engine.hasDirectLink(destinationHashHex),
            hasPropagationNode = !activeConfig?.propagationNodeHashHex.isNullOrBlank(),
        )
        val queued = store.enqueueOutbox(
            RnsEnvelopeCodec.toOutboxRecord(
                outbound = outbound,
                destinationHashHex = destinationHashHex,
                deliveryMode = mode,
                nowMs = clockMs(),
            ),
        )
        val outcome = attemptSend(queued)
        refreshCurrentSnapshot()
        return outcome
    }

    fun retryPendingOutbox(): Int = synchronized(lock) {
        val config = activeConfig ?: return 0
        if (!engine.isAvailable || !engine.status().running) return 0
        val pending = store.pendingOutbox(config.accountKey, config.eventId)
            .filter { it.status == LxmfDeliveryStatus.QUEUED }
        var sentCount = 0
        for (entry in pending) {
            val result = attemptSend(entry)
            if (result is CommSendResult.Accepted) sentCount++
        }
        refreshCurrentSnapshot()
        return sentCount
    }

    fun allOutbox(accountKey: String, eventId: String): List<LxmfOutboxRecord> =
        store.allOutbox(accountKey, eventId)

    fun handleCoreEvent(event: RnsCoreEvent) {
        when (event) {
            is RnsCoreEvent.AnnounceHeard -> {
                val peer = RnsPeerRecord(
                    destinationHashHex = event.destinationHashHex,
                    identityHashHex = event.identityHashHex,
                    appDataText = event.appData.decodeToString(),
                    hops = event.hops,
                    lastHeardAtMs = clockMs(),
                )
                store.upsertPeer(peer)
                mutablePeers.value = store.loadPeers()
                retryPendingOutbox()
                refreshCurrentSnapshot()
            }

            is RnsCoreEvent.LxmfReceived -> {
                if (!event.signatureValid) return
                val lease = activeLease
                if (lease != null && !lease.isLive(clockMs())) return
                val record = parseInboundRecord(event)
                if (isPeerRevoked(event.sourceHashHex) || isPeerRevoked(record.senderActor)) return
                if (record.envelopeKind == CommEnvelopeKind.CONTROL_ACK && record.targetMessageId.isNotBlank()) {
                    store.appendReadReceipt(
                        targetEnvelopeId = record.targetMessageId,
                        readerName = record.senderName.ifBlank { record.senderActor },
                    )
                    val cfg = activeConfig
                    mutableInbound.value = store.inboundMessages(
                        eventId = cfg?.eventId,
                        channelId = cfg?.channelId,
                    )
                    return
                }
                if (record.envelopeKind == CommEnvelopeKind.DELIVERY_RECEIPT) {
                    runCatching { JSONObject(record.text) }.getOrNull()?.let { receiptJson ->
                        if (receiptJson.has("receipt_kind") || receiptJson.has("target_envelope_id")) {
                            mutableGatewayReceipts.tryEmit(receiptJson)
                            return
                        }
                    }
                }
                if (record.envelopeKind == CommEnvelopeKind.ATTACHMENT_MEDIA &&
                    event.contentText.contains("\"chunk_index\"") &&
                    event.attachmentBytes != null
                ) {
                    val reassembled = resourceManager.acceptInboundChunk(event.contentText, event.attachmentBytes)
                        ?: return
                    val completedRecord = record.copy(
                        envelopeId = reassembled.attachmentId,
                        attachmentBytesB64 = java.util.Base64.getEncoder().encodeToString(reassembled.contentBytes),
                    )
                    if (store.saveInboundIfNew(completedRecord)) {
                        val cfg = activeConfig
                        mutableInbound.value = store.inboundMessages(
                            eventId = cfg?.eventId,
                            channelId = cfg?.channelId,
                        )
                    }
                    return
                }
                if (store.saveInboundIfNew(record)) {
                    val cfg = activeConfig
                    mutableInbound.value = store.inboundMessages(
                        eventId = cfg?.eventId,
                        channelId = cfg?.channelId,
                    )
                }
            }

            is RnsCoreEvent.DeliveryUpdated -> {
                val matched = event.clientMessageId.takeIf { it.isNotBlank() }?.let { cid ->
                    store.allOutbox().firstOrNull { it.id == cid }
                } ?: store.findOutboxByMessageHash(event.messageHashHex)
                if (matched != null) {
                    store.updateOutboxStatus(
                        id = matched.id,
                        status = event.status,
                        messageHashHex = event.messageHashHex,
                        error = event.detail,
                        nowMs = event.timestampMs,
                    )
                    mutableReceipts.tryEmit(
                        LxmfDeliveryReceipt(
                            messageId = matched.id,
                            messageHashHex = event.messageHashHex,
                            status = event.status,
                            timestampMs = event.timestampMs,
                            detail = event.detail,
                        ),
                    )
                } else {
                    mutableReceipts.tryEmit(
                        LxmfDeliveryReceipt(
                            messageId = event.clientMessageId.ifBlank { event.messageHashHex },
                            messageHashHex = event.messageHashHex,
                            status = event.status,
                            timestampMs = event.timestampMs,
                            detail = event.detail,
                        ),
                    )
                }
                refreshCurrentSnapshot()
            }

            is RnsCoreEvent.PeerLinkChanged -> refreshCurrentSnapshot()
        }
    }

    private fun attemptSend(entry: LxmfOutboxRecord): CommSendResult {
        if (!engine.isAvailable || !engine.status().running) {
            return CommSendResult.Queued(entry.id, "RNS 运行时未启动，已加密排队")
        }
        val attachmentBytes = entry.attachmentBytesB64.takeIf { it.isNotEmpty() }?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }
        return runCatching {
            val outcome = engine.sendLxmf(
                LxmfSendRequest(
                    messageId = entry.id,
                    destinationHashHex = entry.destinationHashHex,
                    title = entry.title,
                    contentText = entry.text,
                    attachmentKind = entry.attachmentKind,
                    attachmentFilename = RnsEnvelopeCodec.defaultAttachmentFilename(entry.attachmentKind, entry.id),
                    attachmentBytes = attachmentBytes,
                    deliveryMode = entry.deliveryMode,
                    propagationNodeHex = activeConfig?.propagationNodeHashHex,
                    idempotencyKey = entry.idempotencyKey,
                ),
            )
            store.updateOutboxStatus(
                id = entry.id,
                status = outcome.initialStatus,
                messageHashHex = outcome.messageHashHex,
                incrementAttempt = true,
                nowMs = clockMs(),
            )
            if (outcome.initialStatus == LxmfDeliveryStatus.FAILED) {
                mutableReceipts.tryEmit(
                    LxmfDeliveryReceipt(
                        messageId = entry.id,
                        messageHashHex = outcome.messageHashHex,
                        status = LxmfDeliveryStatus.FAILED,
                        timestampMs = clockMs(),
                        detail = "LXMF send failed",
                    ),
                )
                CommSendResult.Rejected("LXMF 发送失败", retryable = true)
            } else if (outcome.initialStatus == LxmfDeliveryStatus.DELIVERED ||
                outcome.initialStatus == LxmfDeliveryStatus.PROPAGATED ||
                outcome.initialStatus == LxmfDeliveryStatus.SENT
            ) {
                CommSendResult.Accepted(entry.id, CommTransportKind.RNS_MESH)
            } else {
                CommSendResult.Queued(entry.id, "已加入 LXMF 队列")
            }
        }.getOrElse { error ->
            store.updateOutboxStatus(
                id = entry.id,
                status = LxmfDeliveryStatus.QUEUED,
                error = error.message ?: "send deferred",
                incrementAttempt = true,
                nowMs = clockMs(),
            )
            CommSendResult.Queued(entry.id, error.message ?: "RNS 暂不可达，已排队等待重试")
        }
    }

    private fun parseInboundRecord(event: RnsCoreEvent.LxmfReceived): LxmfInboundRecord {
        val meta = runCatching { JSONObject(event.title) }.getOrNull()
        val cfg = activeConfig
        val kindName = meta?.optString("kind").orEmpty()
        val kind = runCatching { CommEnvelopeKind.valueOf(kindName) }
            .getOrDefault(
                when {
                    event.attachmentKind == LxmfAttachmentKind.AUDIO_CLIP -> CommEnvelopeKind.VOICE_NOTE
                    meta?.optBoolean("assist") == true -> CommEnvelopeKind.ASSISTANCE_ALERT
                    !meta?.optString("recipient").isNullOrBlank() -> CommEnvelopeKind.PRIVATE_ENCRYPTED
                    else -> CommEnvelopeKind.CHANNEL_TEXT
                },
            )
        val recipient = meta?.optString("recipient").orEmpty()
        val targetMessageId = meta?.optString("target_id")?.takeIf { it.isNotBlank() }
            ?: if (kind == CommEnvelopeKind.CONTROL_ACK && event.contentText.startsWith("confirm:")) {
                event.contentText.removePrefix("confirm:").trim()
            } else ""
        return LxmfInboundRecord(
            messageHashHex = event.messageHashHex,
            envelopeId = meta?.optString("id")?.takeIf { it.isNotBlank() } ?: event.messageHashHex,
            sourceHashHex = event.sourceHashHex,
            destinationHashHex = event.destinationHashHex,
            eventId = meta?.optString("event")?.takeIf { it.isNotBlank() } ?: cfg?.eventId.orEmpty(),
            channelId = meta?.optString("channel")?.takeIf { it.isNotBlank() } ?: cfg?.channelId.orEmpty(),
            senderActor = meta?.optString("actor").orEmpty(),
            senderName = meta?.optString("actor").orEmpty().ifBlank { event.sourceHashHex.take(8) },
            envelopeKind = kind,
            text = event.contentText,
            attachmentKind = event.attachmentKind,
            attachmentBytesB64 = event.attachmentBytes?.let {
                java.util.Base64.getEncoder().encodeToString(it)
            }.orEmpty(),
            assistance = meta?.optBoolean("assist") == true,
            isPrivate = recipient.isNotEmpty(),
            receivedAtMs = event.receivedAtMs,
            isPropagated = event.isPropagated,
            hopCount = event.hopCount,
            targetMessageId = targetMessageId,
        )
    }

    private fun refreshCurrentSnapshot() {
        val status = if (engine.isAvailable) runCatching { engine.status() }.getOrDefault(RnsCoreStatus()) else RnsCoreStatus()
        refreshSnapshot(status, mutableSnapshot.value.lastError)
    }

    private fun refreshSnapshot(status: RnsCoreStatus, lastError: String) {
        val cfg = activeConfig
        mutableSnapshot.value = RnsRuntimeSnapshot(
            running = status.running,
            nativeAvailable = engine.isAvailable,
            identityHashHex = status.identityHashHex,
            destinationHashHex = status.destinationHashHex,
            lxmfDestinationHashHex = status.lxmfDestinationHashHex,
            knownPeersCount = maxOf(status.knownPeersCount, mutablePeers.value.size),
            activeLinksCount = status.activeLinksCount,
            pendingOutboxCount = cfg?.let { store.pendingOutbox(it.accountKey, it.eventId).size } ?: 0,
            lastError = lastError,
        )
    }
}
