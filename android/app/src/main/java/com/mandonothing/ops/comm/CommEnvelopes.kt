package com.mandonothing.ops.comm

import java.util.UUID

/** Semantic message / payload types routed through the Communication Interface. */
internal enum class CommEnvelopeKind {
    CHANNEL_TEXT,
    PRIVATE_ENCRYPTED,
    ASSISTANCE_ALERT,
    VOICE_NOTE,
    ATTACHMENT_MEDIA,
    TASK_ACTION,
    CONTROL_ACK,
    DELIVERY_RECEIPT,
    LIVE_VOICE_BURST,
    SYSTEM_EVENT,
}

/** Unified delivery lifecycle state across online, offline queue, and mesh transports. */
internal enum class CommDeliveryState {
    PENDING,
    QUEUED,
    AWAITING_RECEIPT,
    DELIVERED,
    SYNCED,
    CONFLICT,
    EXPIRED,
    FAILED,
}

/** Transport routing & idempotency metadata attached to every envelope. */
internal data class CommRouteMetadata(
    val preferredTransport: CommTransportKind? = null,
    val attemptedTransport: CommTransportKind? = null,
    val deliveredTransport: CommTransportKind? = null,
    val hopCount: Int = 1,
    val idempotencyKey: String = "",
)

/**
 * Transport-agnostic message envelope rendered by UI and consumed by business logic.
 * Replaces direct UI inspection of NearbyMessage / NearbyRadio / NearbyDelivery / NearbyPrivateContent.
 */
internal data class CommMessageEnvelope(
    val id: String,
    val scope: CommScope,
    val kind: CommEnvelopeKind,
    val senderId: String,
    val senderName: String,
    val recipientId: String = "",
    val text: String = "",
    val audioBase64: String = "",
    val photoBase64: String? = null,
    val assistance: Boolean = false,
    val isPrivate: Boolean = false,
    val isMine: Boolean = false,
    val createdAtMs: Long,
    val deliveryState: CommDeliveryState,
    val syncedToServer: Boolean = false,
    val needsReadReceipt: Boolean = false,
    val readByMe: Boolean = false,
    val readByNames: List<String> = emptyList(),
    val route: CommRouteMetadata = CommRouteMetadata(idempotencyKey = id),
)

/** Outbound request passed by UI/business layers into CommGateway. */
internal data class CommOutboundMessage(
    val id: String = UUID.randomUUID().toString(),
    val scope: CommScope,
    val kind: CommEnvelopeKind,
    val text: String = "",
    val audioBytes: ByteArray? = null,
    val photoBytes: ByteArray? = null,
    val recipientActor: String = "",
    val assistance: Boolean = false,
    val idempotencyKey: String = id,
) {
    val requiresBulkMediaTransport: Boolean
        get() = audioBytes != null || (photoBytes != null && photoBytes.size > 3_200)
}

/** Result of submitting an outbound message or command to the gateway/adapter. */
internal sealed interface CommSendResult {
    data class Accepted(val messageId: String, val transport: CommTransportKind?) : CommSendResult
    data class Queued(val messageId: String, val reason: String) : CommSendResult
    data class Rejected(val reason: String, val retryable: Boolean = false) : CommSendResult
}

/** Transport-agnostic attachment metadata for channel photos/short videos. */
internal data class CommAttachmentEnvelope(
    val id: String,
    val eventId: String,
    val channelId: String,
    val mediaType: String,
    val caption: String,
    val durationMs: Int? = null,
    val createdAtIso: String,
    val deliveryState: CommDeliveryState,
    val failureMessage: String = "",
    val localPosterPath: String? = null,
    val localContentPath: String? = null,
    val isLocalPending: Boolean = false,
)

/** Real-time communication events pushed to UI/services. */
internal sealed interface CommEventEnvelope {
    data class MessageReceived(val envelope: CommMessageEnvelope) : CommEventEnvelope
    data class DeliveryUpdated(val messageId: String, val state: CommDeliveryState, val transport: CommTransportKind?) : CommEventEnvelope
    data class NetworkChanged(val state: CommNetworkState) : CommEventEnvelope
    data class PttStateChanged(val snapshot: PttSessionSnapshot) : CommEventEnvelope
    data class TransportError(val transport: CommTransportKind?, val message: String, val recoverable: Boolean = true) : CommEventEnvelope
}
