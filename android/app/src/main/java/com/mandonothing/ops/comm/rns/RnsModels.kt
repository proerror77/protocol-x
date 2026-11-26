package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommDeliveryState
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommMessageEnvelope
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommRouteMetadata
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommTransportKind
import org.json.JSONObject
import java.util.Base64

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
            deliveryState = CommDeliveryState.DELIVERED,
            syncedToServer = false,
            needsReadReceipt = !record.assistance && audioB64.isEmpty(),
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
