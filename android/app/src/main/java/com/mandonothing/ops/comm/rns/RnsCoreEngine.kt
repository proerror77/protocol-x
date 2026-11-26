package com.mandonothing.ops.comm.rns

/** Inbound events emitted by the underlying RNS / LXMF engine (UniFFI or test fake). */
internal sealed interface RnsCoreEvent {
    data class AnnounceHeard(
        val destinationHashHex: String,
        val identityHashHex: String = "",
        val appData: ByteArray = ByteArray(0),
        val hops: Int = 1,
    ) : RnsCoreEvent

    data class LxmfReceived(
        val messageHashHex: String,
        val sourceHashHex: String,
        val destinationHashHex: String,
        val title: String,
        val contentText: String,
        val attachmentKind: LxmfAttachmentKind = LxmfAttachmentKind.NONE,
        val attachmentBytes: ByteArray? = null,
        val signatureValid: Boolean = true,
        val isPropagated: Boolean = false,
        val hopCount: Int = 1,
        val receivedAtMs: Long = System.currentTimeMillis(),
    ) : RnsCoreEvent

    data class DeliveryUpdated(
        val clientMessageId: String = "",
        val messageHashHex: String,
        val status: LxmfDeliveryStatus,
        val detail: String = "",
        val timestampMs: Long = System.currentTimeMillis(),
    ) : RnsCoreEvent

    data class PeerLinkChanged(
        val destinationHashHex: String,
        val connected: Boolean,
        val rttMs: Long = 0L,
    ) : RnsCoreEvent
}

internal data class RnsCoreStatus(
    val running: Boolean = false,
    val identityHashHex: String = "",
    val destinationHashHex: String = "",
    val lxmfDestinationHashHex: String = "",
    val knownPeersCount: Int = 0,
    val activeLinksCount: Int = 0,
)

internal data class LxmfSendRequest(
    val messageId: String,
    val destinationHashHex: String,
    val title: String,
    val contentText: String,
    val attachmentKind: LxmfAttachmentKind = LxmfAttachmentKind.NONE,
    val attachmentFilename: String = "",
    val attachmentBytes: ByteArray? = null,
    val deliveryMode: LxmfDeliveryMode = LxmfDeliveryMode.OPPORTUNISTIC,
    val propagationNodeHex: String? = null,
    val idempotencyKey: String,
)

internal data class LxmfSendOutcome(
    val clientMessageId: String,
    val messageHashHex: String,
    val initialStatus: LxmfDeliveryStatus,
)

/**
 * Abstraction over the Rust `protocolx_rns` UniFFI node & LXMF runtime.
 * Decouples Android service/adapter tests from native `.so` loading on host JVM.
 */
internal interface RnsCoreEngine {
    val isAvailable: Boolean
    val unavailableReason: String?

    fun start(
        config: RnsRuntimeConfig,
        identitySecret: ByteArray?,
        listener: (RnsCoreEvent) -> Unit,
    ): RnsCoreStatus

    fun stop()
    fun identitySecret(): ByteArray?
    fun announce(appData: ByteArray = ByteArray(0))
    fun announceLxmf(displayName: String? = null) {}
    fun sendLxmf(request: LxmfSendRequest): LxmfSendOutcome
    fun hasDirectLink(destinationHashHex: String): Boolean
    fun fetchPropagatedLxmf(propagationNodeHashHex: String? = null): List<RnsCoreEvent.LxmfReceived> = emptyList()
    fun persistedLxmfMessages(): List<RnsCoreEvent.LxmfReceived> = emptyList()
    fun persistedLxmfReceipts(): List<RnsCoreEvent.DeliveryUpdated> = emptyList()
    fun rotateIfacCredentials(policy: RnsIfacPolicy) {}
    fun revokePeer(identityOrDestHashHex: String, reason: String? = null) {}
    fun revokedPeers(): List<String> = emptyList()
    fun bindActorDevice(actorId: String, deviceTokenHash: String): RnsActorDeviceBinding? = null
    fun ed25519PublicKeyHex(): String = ""
    fun status(): RnsCoreStatus
}
