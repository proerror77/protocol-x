package com.mandonothing.ops.comm.rns

import uniffi.protocolx_rns.IfacConfig
import uniffi.protocolx_rns.LxmfAttachment
import uniffi.protocolx_rns.LxmfDeliveryMode as UniFfiDeliveryMode
import uniffi.protocolx_rns.LxmfDeliveryReceipt as UniFfiDeliveryReceipt
import uniffi.protocolx_rns.LxmfInboundMessage as UniFfiInboundMessage
import uniffi.protocolx_rns.LxmfOutboundMessage as UniFfiOutboundMessage
import uniffi.protocolx_rns.LxmfReceiptStatus as UniFfiReceiptStatus
import uniffi.protocolx_rns.NodeConfig
import uniffi.protocolx_rns.PttFloorControlMessage
import uniffi.protocolx_rns.RnsEventListener
import uniffi.protocolx_rns.RnsInboundEvent
import uniffi.protocolx_rns.RnsNode
import uniffi.protocolx_rns.bindActorDeviceToRns

/**
 * [RnsCoreEngine] implementation backed by Rust `protocolx_rns` via Mozilla UniFFI bindings.
 * All native access is gated behind [RnsNativeLoader.ensureLoaded] so JVM unit tests run safely
 * without an Android `.so` loaded on the host.
 */
internal class UniFFIRnsCoreEngine : RnsCoreEngine {
    override val isAvailable: Boolean
        get() = RnsNativeLoader.ensureLoaded()

    override val unavailableReason: String?
        get() = RnsNativeLoader.loadError

    private var node: RnsNode? = null

    internal fun activeNode(): RnsNode? = node

    override fun start(
        config: RnsRuntimeConfig,
        identitySecret: ByteArray?,
        listener: (RnsCoreEvent) -> Unit,
    ): RnsCoreStatus {
        check(isAvailable) { unavailableReason ?: "protocolx_rns native library not loaded" }
        stop()
        val ifacConfig = config.ifac?.let {
            IfacConfig(
                networkName = it.networkName,
                passphrase = it.passphrase,
                ifacSizeBytes = it.ifacSizeBytes.coerceIn(1, 64).toUByte(),
                requirePrivateIfac = it.requirePrivateIfac,
            )
        }
        val nodeConfig = NodeConfig(
            identitySecret = identitySecret,
            appName = "protocolx",
            aspects = listOf("node"),
            announceAppData = config.actorId.ifBlank { config.channelId }.toByteArray(Charsets.UTF_8),
            tcpListenAddr = config.tcpListenAddr,
            tcpConnectAddrs = config.tcpConnectAddrs,
            udpBindAddr = config.udpBindAddr,
            udpPeerAddr = config.udpPeerAddr,
            udpBitrateBps = 1_000_000uL,
            enableTransportRelay = config.enableTransportRelay,
            storageDir = config.storageDir,
            lxmfDisplayName = config.lxmfDisplayName ?: config.actorId.ifBlank { config.channelName },
            propagationNodeHex = config.propagationNodeHashHex,
            ifac = ifacConfig,
            revokedIdentityHashes = config.revokedIdentityHashes,
        )
        val created = RnsNode(nodeConfig)
        created.setListener(
            object : RnsEventListener {
                override fun onEvent(event: RnsInboundEvent) {
                    mapInboundEvent(event)?.let(listener)
                }
            },
        )
        created.start()
        node = created
        return status()
    }

    override fun stop() {
        val current = node ?: return
        node = null
        runCatching { current.setListener(null) }
        runCatching { current.stop() }
        runCatching { current.close() }
    }

    override fun identitySecret(): ByteArray? = node?.let { current ->
        runCatching { current.identity().secretBytes() }.getOrNull()
    }

    override fun announce(appData: ByteArray) {
        node?.announce(appData)
    }

    override fun announceLxmf(displayName: String?) {
        node?.announceLxmf(displayName)
    }

    override fun sendLxmf(request: LxmfSendRequest): LxmfSendOutcome {
        val current = checkNotNull(node) { "RNS node is not running" }
        val attachments = if (request.attachmentBytes != null && request.attachmentKind != LxmfAttachmentKind.NONE) {
            listOf(
                LxmfAttachment(
                    filename = request.attachmentFilename.ifBlank { "attachment.bin" },
                    data = request.attachmentBytes,
                ),
            )
        } else {
            emptyList()
        }
        val receipt = current.sendLxmf(
            UniFfiOutboundMessage(
                messageId = request.messageId,
                destinationHashHex = request.destinationHashHex,
                title = request.title,
                content = request.contentText,
                deliveryMode = toUniFfiMode(request.deliveryMode),
                attachments = attachments,
                customType = if (request.attachmentKind == LxmfAttachmentKind.NONE) null else request.attachmentKind.name,
                customData = null,
                propagationNodeHex = request.propagationNodeHex,
            ),
        )
        return LxmfSendOutcome(
            clientMessageId = receipt.clientMessageId,
            messageHashHex = receipt.messageHashHex,
            initialStatus = fromUniFfiStatus(receipt.status),
        )
    }

    override fun hasDirectLink(destinationHashHex: String): Boolean {
        val current = node ?: return false
        return destinationHashHex in current.knownDestinations() && current.status().activeLinksCount > 0u
    }

    override fun fetchPropagatedLxmf(propagationNodeHashHex: String?): List<RnsCoreEvent.LxmfReceived> {
        val current = node ?: return emptyList()
        return current.fetchPropagatedLxmf(propagationNodeHashHex).map(::mapInboundLxmf)
    }

    override fun persistedLxmfMessages(): List<RnsCoreEvent.LxmfReceived> {
        val current = node ?: return emptyList()
        return current.persistedLxmfMessages().map(::mapInboundLxmf)
    }

    override fun persistedLxmfReceipts(): List<RnsCoreEvent.DeliveryUpdated> {
        val current = node ?: return emptyList()
        return current.persistedLxmfReceipts().map(::mapReceipt)
    }

    override fun rotateIfacCredentials(policy: RnsIfacPolicy) {
        node?.rotateIfacCredentials(
            IfacConfig(
                networkName = policy.networkName,
                passphrase = policy.passphrase,
                ifacSizeBytes = policy.ifacSizeBytes.coerceIn(1, 64).toUByte(),
                requirePrivateIfac = policy.requirePrivateIfac,
            ),
        )
    }

    override fun revokePeer(identityOrDestHashHex: String, reason: String?) {
        node?.revokePeer(identityOrDestHashHex, reason)
    }

    override fun revokedPeers(): List<String> = node?.revokedPeers().orEmpty()

    override fun ed25519PublicKeyHex(): String {
        val pub = node?.let { runCatching { it.identity().publicKeyBytes() }.getOrNull() } ?: return ""
        val slice = if (pub.size >= 64) pub.copyOfRange(32, 64) else pub
        return slice.joinToString("") { "%02x".format(it) }
    }

    override fun bindActorDevice(actorId: String, deviceTokenHash: String): RnsActorDeviceBinding? {
        val current = node ?: return null
        val destHash = runCatching { current.lxmfDestinationHashHex() }.getOrDefault(current.destinationHashHex())
        val idHash = current.identityHashHex()
        val edHex = ed25519PublicKeyHex()
        val bound = runCatching {
            bindActorDeviceToRns(actorId, deviceTokenHash, destHash, idHash, edHex)
        }.getOrNull() ?: return null
        return RnsActorDeviceBinding(
            actorId = bound.actorId,
            deviceTokenHash = bound.deviceTokenHash,
            rnsDestinationHashHex = bound.rnsDestinationHashHex,
            rnsIdentityHashHex = bound.rnsIdentityHashHex,
            ed25519PublicKeyHex = bound.ed25519PublicKeyHex,
            bindingDigestHex = bound.bindingDigestHex,
        )
    }

    override fun multiHopTelemetry(): RnsCoreMultiHopTelemetry {
        val current = node ?: return RnsCoreMultiHopTelemetry()
        val raw = runCatching { current.multiHopTelemetry() }.getOrNull() ?: return RnsCoreMultiHopTelemetry()
        return RnsCoreMultiHopTelemetry(
            enableTransportRelay = raw.enableTransportRelay,
            activeLinksCount = raw.activeLinksCount.toInt(),
            lastLinkRttMs = raw.lastLinkRttMs?.toLong(),
            routes = raw.routes.map { r ->
                RnsCoreRouteSnapshot(
                    destinationHashHex = r.destinationHashHex,
                    hops = r.hops.toInt(),
                    nextHopHex = r.nextHopHex,
                    learnedAtMs = r.learnedAtMs.toLong(),
                    lastActivityAtMs = r.lastActivityAtMs.toLong(),
                    expiresAtMs = r.expiresAtMs.toLong(),
                    interfaceTag = r.interfaceTag,
                )
            },
        )
    }

    override fun pttDestinationHashHex(): String =
        node?.let { runCatching { it.pttDestinationHashHex() }.getOrDefault("") }.orEmpty()

    override fun announcePtt() {
        node?.let { runCatching { it.announcePtt() } }
    }

    override fun sendPttFrame(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
        seq: Long,
        codec: String,
        durationMs: Int,
        audioBytes: ByteArray,
    ): Boolean {
        val current = node ?: return false
        return runCatching {
            current.sendPttFrame(
                destinationHashHex = destinationHashHex,
                channelId = channelId,
                sessionId = sessionId,
                seq = seq.coerceAtLeast(0L).toULong(),
                codec = codec,
                durationMs = durationMs.coerceAtLeast(1).toUInt(),
                audioBytes = audioBytes,
            )
            true
        }.getOrDefault(false)
    }

    override fun requestPttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
        priority: Int,
    ): RnsCoreEvent.PttFloorEvent? {
        val current = node ?: return null
        return runCatching {
            mapFloorControl(
                current.requestPttFloor(
                    destinationHashHex = destinationHashHex,
                    channelId = channelId,
                    sessionId = sessionId,
                    priority = priority.coerceIn(0, 255).toUByte(),
                ),
            )
        }.getOrNull()
    }

    override fun releasePttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
    ): RnsCoreEvent.PttFloorEvent? {
        val current = node ?: return null
        return runCatching {
            mapFloorControl(current.releasePttFloor(destinationHashHex, channelId, sessionId))
        }.getOrNull()
    }

    override fun status(): RnsCoreStatus {
        val current = node ?: return RnsCoreStatus(running = false)
        val raw = current.status()
        val lxmfHash = runCatching { current.lxmfDestinationHashHex() }.getOrDefault("")
        return RnsCoreStatus(
            running = raw.running,
            identityHashHex = raw.identityHashHex,
            destinationHashHex = raw.destinationHashHex,
            lxmfDestinationHashHex = lxmfHash,
            knownPeersCount = raw.knownPeersCount.toInt(),
            activeLinksCount = raw.activeLinksCount.toInt(),
        )
    }

    private fun mapInboundEvent(event: RnsInboundEvent): RnsCoreEvent? = when (event) {
        is RnsInboundEvent.AnnounceHeard -> RnsCoreEvent.AnnounceHeard(
            destinationHashHex = event.destinationHashHex,
            hops = event.hops.toInt(),
        )
        is RnsInboundEvent.LinkEstablished -> RnsCoreEvent.PeerLinkChanged(
            destinationHashHex = event.linkIdHex,
            connected = true,
            rttMs = event.rttMillis.toLong(),
        )
        is RnsInboundEvent.LinkClosed -> RnsCoreEvent.PeerLinkChanged(
            destinationHashHex = event.linkIdHex,
            connected = false,
        )
        is RnsInboundEvent.LxmfMessageReceived -> mapInboundLxmf(event.message)
        is RnsInboundEvent.LxmfDeliveryStatus -> mapReceipt(event.receipt)
        is RnsInboundEvent.PttFrameReceived -> RnsCoreEvent.PttFrameReceived(
            channelId = event.frame.channelId,
            sessionId = event.frame.sessionId,
            senderHashHex = event.frame.senderHashHex,
            seq = event.frame.seq.toLong(),
            timestampMs = event.frame.timestampMs.toLong(),
            codec = event.frame.codec,
            durationMs = event.frame.durationMs.toInt(),
            audioBytes = event.frame.audioBytes,
            hopCount = event.frame.hopCount.toInt(),
        )
        is RnsInboundEvent.PttFloorEvent -> mapFloorControl(event.control)
        is RnsInboundEvent.SinglePacketReceived,
        is RnsInboundEvent.LinkPacketReceived,
        is RnsInboundEvent.ResourceReceived,
        -> null
    }

    private fun mapFloorControl(msg: PttFloorControlMessage): RnsCoreEvent.PttFloorEvent =
        RnsCoreEvent.PttFloorEvent(
            channelId = msg.channelId,
            sessionId = msg.sessionId,
            actorHashHex = msg.actorHashHex,
            action = msg.action.name,
            priority = msg.priority.toInt(),
            timestampMs = msg.timestampMs.toLong(),
            holderHashHex = msg.holderHashHex,
            detail = msg.detail,
        )

    private fun mapInboundLxmf(msg: UniFfiInboundMessage): RnsCoreEvent.LxmfReceived {
        val firstAttachment = msg.attachments.firstOrNull()
        val attKind = msg.customType?.let {
            runCatching { LxmfAttachmentKind.valueOf(it) }.getOrNull()
        } ?: inferAttachmentKind(firstAttachment?.filename)
        return RnsCoreEvent.LxmfReceived(
            messageHashHex = msg.messageHashHex,
            sourceHashHex = msg.sourceHashHex,
            destinationHashHex = msg.destinationHashHex,
            title = msg.title,
            contentText = msg.content,
            attachmentKind = attKind,
            attachmentBytes = firstAttachment?.data ?: msg.customData,
            signatureValid = msg.signatureValid,
            isPropagated = msg.deliveryMode == UniFfiDeliveryMode.PROPAGATED,
            receivedAtMs = (msg.timestampSecs * 1_000.0).toLong().takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

    private fun mapReceipt(receipt: UniFfiDeliveryReceipt): RnsCoreEvent.DeliveryUpdated =
        RnsCoreEvent.DeliveryUpdated(
            clientMessageId = receipt.clientMessageId,
            messageHashHex = receipt.messageHashHex,
            status = fromUniFfiStatus(receipt.status),
            detail = receipt.detail,
            timestampMs = receipt.timestampMs.toLong(),
        )

    private fun inferAttachmentKind(filename: String?): LxmfAttachmentKind {
        val name = filename?.lowercase().orEmpty()
        return when {
            name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".ogg") -> LxmfAttachmentKind.AUDIO_CLIP
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> LxmfAttachmentKind.PHOTO_JPEG
            name.endsWith(".mp4") -> LxmfAttachmentKind.VIDEO_MP4
            name.isNotEmpty() -> LxmfAttachmentKind.BINARY_RESOURCE
            else -> LxmfAttachmentKind.NONE
        }
    }

    private fun toUniFfiMode(mode: LxmfDeliveryMode): UniFfiDeliveryMode = when (mode) {
        LxmfDeliveryMode.OPPORTUNISTIC -> UniFfiDeliveryMode.OPPORTUNISTIC
        LxmfDeliveryMode.DIRECT_LINK -> UniFfiDeliveryMode.DIRECT
        LxmfDeliveryMode.PROPAGATED -> UniFfiDeliveryMode.PROPAGATED
    }

    private fun fromUniFfiStatus(status: UniFfiReceiptStatus): LxmfDeliveryStatus = when (status) {
        UniFfiReceiptStatus.PENDING -> LxmfDeliveryStatus.SENDING
        UniFfiReceiptStatus.DELIVERED -> LxmfDeliveryStatus.DELIVERED
        UniFfiReceiptStatus.PROPAGATED -> LxmfDeliveryStatus.PROPAGATED
        UniFfiReceiptStatus.FAILED -> LxmfDeliveryStatus.FAILED
    }
}
