package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommDeliveryState
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommMessageEnvelope
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommRouteMetadata
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommTransportKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** LXMF delivery modes aligned with RNS-03 Rust core & Python lxmf==1.3.1. */
internal enum class LxmfDeliveryMode {
    OPPORTUNISTIC,
    DIRECT_LINK,
    PROPAGATED,
}

/** Delivery lifecycle states surfaced through RNS-02 CommDeliveryState. */
internal enum class LxmfDeliveryStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    PROPAGATED,
    FAILED,
}

/** Resource/attachment classification for LXMF Resource chunking & field encoding. */
internal enum class LxmfAttachmentKind {
    NONE,
    AUDIO_CLIP,
    PHOTO_JPEG,
    VIDEO_MP4,
    BINARY_RESOURCE,
}

internal data class RnsIfacPolicy(
    val networkName: String,
    val passphrase: String,
    val ifacSizeBytes: Int = 16,
    val requirePrivateIfac: Boolean = true,
)

internal data class RnsActorDeviceBinding(
    val actorId: String,
    val deviceTokenHash: String,
    val rnsDestinationHashHex: String,
    val rnsIdentityHashHex: String,
    val ed25519PublicKeyHex: String,
    val bindingDigestHex: String,
)

internal data class RnsIfacLease(
    val eventId: String,
    val channelId: String,
    val networkName: String,
    val passphrase: String,
    val ifacSizeBytes: Int = 16,
    val requirePrivateIfac: Boolean = true,
    val generation: String = "",
    val expiresAtMs: Long,
    val gatewayDestinationHashHex: String? = null,
    val revokedDeviceHashes: Set<String> = emptySet(),
    val revokedActors: Set<String> = emptySet(),
) {
    fun isLive(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs > nowMs

    fun toPolicy(): RnsIfacPolicy = RnsIfacPolicy(
        networkName = networkName,
        passphrase = passphrase,
        ifacSizeBytes = ifacSizeBytes,
        requirePrivateIfac = requirePrivateIfac,
    )
}

internal data class RnsIdentityBinding(
    val accountKey: String,
    val eventId: String,
    val actorId: String,
    val deviceTokenHash: String,
    val rnsIdentityHashHex: String,
    val rnsDestinationHashHex: String,
    val lxmfDestinationHashHex: String = "",
    val ed25519PubB64: String = "",
    val x25519PubB64: String = "",
    val ed25519PubHex: String = "",
    val bindingDigestHex: String = "",
    val boundAtMs: Long = 0L,
)

internal data class RnsSyncCheckpoints(
    val accountKey: String,
    val eventId: String,
    val workspaceRevision: String? = null,
    val channelCursors: Map<String, String> = emptyMap(),
    val flightNoticeSeq: Long = 0L,
    val updatedAtMs: Long = 0L,
)

internal data class RnsAttachmentTransferState(
    val attachmentId: String,
    val accountKey: String,
    val eventId: String,
    val channelId: String,
    val taskId: String = "",
    val targetKind: String,
    val mediaType: String,
    val caption: String = "",
    val totalBytes: Long,
    val posterBytes: Int = 0,
    val durationMs: Int? = null,
    val sha256Content: String,
    val sha256Combined: String,
    val chunkSize: Int = 16_384,
    val chunkCount: Int,
    val ackedChunks: Set<Int> = emptySet(),
    val status: LxmfDeliveryStatus = LxmfDeliveryStatus.QUEUED,
    val syncedToServer: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    fun isCompleteTransfer(): Boolean = chunkCount <= 0 || ackedChunks.size >= chunkCount

    fun nextChunkIndex(): Int? = (0 until chunkCount).firstOrNull { it !in ackedChunks }
}

internal data class RnsRuntimeConfig(
    val accountKey: String,
    val eventId: String,
    val channelId: String,
    val channelName: String,
    val actorId: String = "",
    val storageDir: String? = null,
    val lxmfDisplayName: String? = null,
    val tcpListenAddr: String? = null,
    val tcpConnectAddrs: List<String> = emptyList(),
    val udpBindAddr: String? = null,
    val udpPeerAddr: String? = null,
    val propagationNodeHashHex: String? = null,
    val enableTransportRelay: Boolean = false,
    val ifac: RnsIfacPolicy? = null,
    val revokedIdentityHashes: List<String> = emptyList(),
)

internal data class RnsRuntimeSnapshot(
    val running: Boolean = false,
    val nativeAvailable: Boolean = false,
    val identityHashHex: String = "",
    val destinationHashHex: String = "",
    val lxmfDestinationHashHex: String = "",
    val knownPeersCount: Int = 0,
    val activeLinksCount: Int = 0,
    val pendingOutboxCount: Int = 0,
    val lastError: String = "",
)

internal data class RnsPeerRecord(
    val destinationHashHex: String,
    val identityHashHex: String = "",
    val appDataText: String = "",
    val hops: Int = 1,
    val lastHeardAtMs: Long = 0L,
)

internal data class LxmfOutboxRecord(
    val id: String,
    val accountKey: String,
    val eventId: String,
    val channelId: String,
    val destinationHashHex: String,
    val envelopeKind: CommEnvelopeKind,
    val title: String,
    val text: String,
    val recipientActor: String = "",
    val assistance: Boolean = false,
    val attachmentKind: LxmfAttachmentKind = LxmfAttachmentKind.NONE,
    val attachmentBytesB64: String = "",
    val deliveryMode: LxmfDeliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
    val status: LxmfDeliveryStatus = LxmfDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val idempotencyKey: String = id,
    val messageHashHex: String = "",
    val lastError: String = "",
)

internal data class LxmfInboundRecord(
    val messageHashHex: String,
    val envelopeId: String,
    val sourceHashHex: String,
    val destinationHashHex: String,
    val eventId: String,
    val channelId: String,
    val senderActor: String,
    val senderName: String,
    val envelopeKind: CommEnvelopeKind,
    val text: String,
    val attachmentKind: LxmfAttachmentKind = LxmfAttachmentKind.NONE,
    val attachmentBytesB64: String = "",
    val assistance: Boolean = false,
    val isPrivate: Boolean = false,
    val receivedAtMs: Long,
    val isPropagated: Boolean = false,
    val hopCount: Int = 1,
    val readByNames: List<String> = emptyList(),
    val syncedToServer: Boolean = false,
    val targetMessageId: String = "",
)

internal data class LxmfDeliveryReceipt(
    val messageId: String,
    val messageHashHex: String,
    val status: LxmfDeliveryStatus,
    val timestampMs: Long,
    val detail: String = "",
)

/**
 * Canonical encoding between RNS-02 [CommOutboundMessage] / [CommMessageEnvelope] and
 * LXMF wire fields/resources aligned with `docs/RNS-TOC-CONTRACT.md` (`protocol-x.rns.envelope/v1`).
 */
internal object RnsEnvelopeCodec {
    const val SCHEMA_VERSION = "protocol-x.rns.envelope/v1"
    const val MAX_OPPORTUNISTIC_BYTES = 295

    fun selectDeliveryMode(
        outbound: CommOutboundMessage,
        hasDirectLink: Boolean,
        hasPropagationNode: Boolean,
    ): LxmfDeliveryMode {
        val payloadBytes = estimatePayloadSize(outbound)
        return when {
            payloadBytes > MAX_OPPORTUNISTIC_BYTES || outbound.requiresBulkMediaTransport ->
                if (hasDirectLink || !hasPropagationNode) LxmfDeliveryMode.DIRECT_LINK else LxmfDeliveryMode.PROPAGATED
            hasDirectLink -> LxmfDeliveryMode.DIRECT_LINK
            else -> LxmfDeliveryMode.OPPORTUNISTIC
        }
    }

    fun estimatePayloadSize(outbound: CommOutboundMessage): Int {
        val textBytes = outbound.text.encodeToByteArray().size
        val audioSize = outbound.audioBytes?.size ?: 0
        val photoSize = outbound.photoBytes?.size ?: 0
        return textBytes + audioSize + photoSize + 96
    }

    fun canonicalJsonDump(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean, is Int, is Long, is Double -> value.toString()
        is Float -> if (value.isNaN() || value.isInfinite()) "null" else value.toString()
        is String -> JSONObject.quote(value)
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                "${JSONObject.quote(key)}:${canonicalJsonDump(value.opt(key))}"
            }
        }
        is JSONArray -> {
            (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { idx ->
                canonicalJsonDump(value.opt(idx))
            }
        }
        is Map<*, *> -> {
            value.keys.map { it.toString() }.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                "${JSONObject.quote(key)}:${canonicalJsonDump(value[key])}"
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJsonDump(it) }
        else -> JSONObject.quote(value.toString())
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun toEnvelopeV1Kind(kind: CommEnvelopeKind): String = when (kind) {
        CommEnvelopeKind.TASK_ACTION -> "task"
        CommEnvelopeKind.ATTACHMENT_MEDIA -> "attachment"
        CommEnvelopeKind.CONTROL_ACK, CommEnvelopeKind.DELIVERY_RECEIPT -> "delivery_receipt"
        CommEnvelopeKind.SYSTEM_EVENT, CommEnvelopeKind.LIVE_VOICE_BURST -> "event"
        CommEnvelopeKind.CHANNEL_TEXT,
        CommEnvelopeKind.PRIVATE_ENCRYPTED,
        CommEnvelopeKind.ASSISTANCE_ALERT,
        CommEnvelopeKind.VOICE_NOTE,
        -> "message"
    }

    fun envelopePriority(kind: CommEnvelopeKind, assistance: Boolean = false): Int = when {
        assistance || kind == CommEnvelopeKind.ASSISTANCE_ALERT ||
            kind == CommEnvelopeKind.DELIVERY_RECEIPT || kind == CommEnvelopeKind.CONTROL_ACK -> 3
        kind == CommEnvelopeKind.TASK_ACTION -> 2
        kind == CommEnvelopeKind.ATTACHMENT_MEDIA -> 0
        else -> 1
    }

    fun encodeEnvelopeV1(
        envelopeId: String,
        kind: String,
        eventId: String,
        channelId: String?,
        actor: String,
        deviceId: String,
        idempotencyKey: String,
        payload: JSONObject,
        priority: Int = 1,
        hopCount: Int = 1,
        originRadio: String = "RNS_IP",
        ttlMs: Long = 86_400_000L,
        createdAtIso: String = Instant.now().toString(),
        rnsPacketHash: String? = null,
    ): JSONObject {
        val fingerprintMaterial = JSONObject()
            .put("event_id", eventId)
            .put("channel_id", channelId ?: JSONObject.NULL)
            .put("actor", actor)
            .put("kind", kind)
            .put("payload", payload)
        val fingerprint = sha256Hex(canonicalJsonDump(fingerprintMaterial))
        return JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("envelope_id", envelopeId)
            .put("kind", kind)
            .put("event_id", eventId)
            .put("channel_id", channelId ?: JSONObject.NULL)
            .put("actor", actor)
            .put("device_id", deviceId.ifBlank { actor })
            .put("created_at", createdAtIso)
            .put("ttl_ms", ttlMs)
            .put("priority", priority)
            .put("idempotency_key", idempotencyKey)
            .put("fingerprint", fingerprint)
            .put(
                "trace",
                JSONObject()
                    .put("hop_count", hopCount)
                    .put("origin_radio", originRadio)
                    .put("rns_packet_hash", rnsPacketHash ?: JSONObject.NULL)
                    .put("gateway_actor", JSONObject.NULL),
            )
            .put(
                "auth",
                JSONObject()
                    .put("key_id", deviceId.ifBlank { actor })
                    .put("public_key", "")
                    .put("certificate", "")
                    .put("signature", ""),
            )
            .put("payload", payload)
    }

    fun encodeTitle(outbound: CommOutboundMessage): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("id", outbound.id)
        .put("idem", outbound.idempotencyKey)
        .put("event", outbound.scope.eventId)
        .put("channel", outbound.scope.channelId)
        .put("kind", outbound.kind.name)
        .put("actor", outbound.scope.actorId)
        .put("recipient", outbound.recipientActor)
        .put("assist", outbound.assistance)
        .toString()

    fun encodeAttachment(outbound: CommOutboundMessage): Pair<LxmfAttachmentKind, ByteArray?> = when {
        outbound.audioBytes != null -> LxmfAttachmentKind.AUDIO_CLIP to outbound.audioBytes
        outbound.photoBytes != null -> LxmfAttachmentKind.PHOTO_JPEG to outbound.photoBytes
        else -> LxmfAttachmentKind.NONE to null
    }

    fun defaultAttachmentFilename(kind: LxmfAttachmentKind, id: String): String = when (kind) {
        LxmfAttachmentKind.AUDIO_CLIP -> "voice-$id.m4a"
        LxmfAttachmentKind.PHOTO_JPEG -> "photo-$id.jpg"
        LxmfAttachmentKind.VIDEO_MP4 -> "video-$id.mp4"
        LxmfAttachmentKind.BINARY_RESOURCE -> "resource-$id.bin"
        LxmfAttachmentKind.NONE -> ""
    }

    fun toOutboxRecord(
        outbound: CommOutboundMessage,
        destinationHashHex: String,
        deliveryMode: LxmfDeliveryMode,
        nowMs: Long = System.currentTimeMillis(),
    ): LxmfOutboxRecord {
        val (attKind, attBytes) = encodeAttachment(outbound)
        return LxmfOutboxRecord(
            id = outbound.id,
            accountKey = outbound.scope.accountKey,
            eventId = outbound.scope.eventId,
            channelId = outbound.scope.channelId,
            destinationHashHex = destinationHashHex,
            envelopeKind = outbound.kind,
            title = encodeTitle(outbound),
            text = outbound.text,
            recipientActor = outbound.recipientActor,
            assistance = outbound.assistance,
            attachmentKind = attKind,
            attachmentBytesB64 = attBytes?.let { Base64.getEncoder().encodeToString(it) }.orEmpty(),
            deliveryMode = deliveryMode,
            status = LxmfDeliveryStatus.QUEUED,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            idempotencyKey = outbound.idempotencyKey,
        )
    }

    fun toDeliveryState(status: LxmfDeliveryStatus): CommDeliveryState = when (status) {
        LxmfDeliveryStatus.QUEUED -> CommDeliveryState.QUEUED
        LxmfDeliveryStatus.SENDING -> CommDeliveryState.PENDING
        LxmfDeliveryStatus.SENT -> CommDeliveryState.AWAITING_RECEIPT
        LxmfDeliveryStatus.DELIVERED -> CommDeliveryState.DELIVERED
        LxmfDeliveryStatus.PROPAGATED -> CommDeliveryState.AWAITING_RECEIPT
        LxmfDeliveryStatus.FAILED -> CommDeliveryState.FAILED
    }

    fun inboundToEnvelope(record: LxmfInboundRecord, scope: CommScope): CommMessageEnvelope {
        val audioB64 = if (record.attachmentKind == LxmfAttachmentKind.AUDIO_CLIP) record.attachmentBytesB64 else ""
        val photoB64 = if (record.attachmentKind == LxmfAttachmentKind.PHOTO_JPEG) record.attachmentBytesB64 else null
        return CommMessageEnvelope(
            id = record.envelopeId,
            scope = scope,
            kind = record.envelopeKind,
            senderId = record.sourceHashHex,
            senderName = record.senderName.ifBlank { record.senderActor.ifBlank { record.sourceHashHex.take(8) } },
            recipientId = scope.actorId,
            text = record.text,
            audioBase64 = audioB64,
            photoBase64 = photoB64,
            assistance = record.assistance,
            isPrivate = record.isPrivate,
            isMine = false,
            createdAtMs = record.receivedAtMs,
            deliveryState = if (record.syncedToServer) CommDeliveryState.SYNCED else CommDeliveryState.DELIVERED,
            syncedToServer = record.syncedToServer,
            needsReadReceipt = !record.assistance && audioB64.isEmpty() &&
                record.envelopeKind != CommEnvelopeKind.CONTROL_ACK &&
                record.envelopeKind != CommEnvelopeKind.DELIVERY_RECEIPT &&
                record.envelopeKind != CommEnvelopeKind.TASK_ACTION &&
                record.envelopeKind != CommEnvelopeKind.ATTACHMENT_MEDIA,
            readByMe = scope.actorId.isNotBlank() && scope.actorId in record.readByNames,
            readByNames = record.readByNames,
            route = CommRouteMetadata(
                deliveredTransport = CommTransportKind.RNS_MESH,
                hopCount = record.hopCount,
                idempotencyKey = record.envelopeId,
            ),
        )
    }

    fun outboxToEnvelope(record: LxmfOutboxRecord, scope: CommScope): CommMessageEnvelope {
        val audioB64 = if (record.attachmentKind == LxmfAttachmentKind.AUDIO_CLIP) record.attachmentBytesB64 else ""
        val photoB64 = if (record.attachmentKind == LxmfAttachmentKind.PHOTO_JPEG) record.attachmentBytesB64 else null
        return CommMessageEnvelope(
            id = record.id,
            scope = scope,
            kind = record.envelopeKind,
            senderId = scope.actorId,
            senderName = "我",
            recipientId = record.recipientActor,
            text = record.text,
            audioBase64 = audioB64,
            photoBase64 = photoB64,
            assistance = record.assistance,
            isPrivate = record.recipientActor.isNotEmpty(),
            isMine = true,
            createdAtMs = record.createdAtMs,
            deliveryState = toDeliveryState(record.status),
            syncedToServer = false,
            needsReadReceipt = false,
            route = CommRouteMetadata(
                preferredTransport = CommTransportKind.RNS_MESH,
                attemptedTransport = if (record.attemptCount > 0) CommTransportKind.RNS_MESH else null,
                deliveredTransport = if (record.status == LxmfDeliveryStatus.DELIVERED) CommTransportKind.RNS_MESH else null,
                idempotencyKey = record.idempotencyKey,
            ),
        )
    }
}
