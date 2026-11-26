package com.mandonothing.ops.comm.aware

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Private-network IFAC (Interface Access Code) and peer-isolation configuration for RNS-05.
 * Rejects public/unauthenticated RNS connections (`allowPublicRns = false` by default).
 */
internal data class RnsIfacConfig(
    val networkName: String,
    val ifacKey: ByteArray,
    val credentialGeneration: Long = 1L,
    val expiresAtMs: Long = Long.MAX_VALUE,
    val allowPublicRns: Boolean = false,
    val revokedPeerIds: Set<String> = emptySet(),
    val revokedDestinationHashes: Set<String> = emptySet(),
    val wrapInterfaceFrames: Boolean = true,
) {
    fun isValid(nowMs: Long): Boolean =
        !allowPublicRns &&
            networkName.isNotBlank() &&
            ifacKey.isNotEmpty() &&
            nowMs < expiresAtMs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RnsIfacConfig) return false
        return networkName == other.networkName &&
            ifacKey.contentEquals(other.ifacKey) &&
            credentialGeneration == other.credentialGeneration &&
            expiresAtMs == other.expiresAtMs &&
            allowPublicRns == other.allowPublicRns &&
            revokedPeerIds == other.revokedPeerIds &&
            revokedDestinationHashes == other.revokedDestinationHashes &&
            wrapInterfaceFrames == other.wrapInterfaceFrames
    }

    override fun hashCode(): Int {
        var result = networkName.hashCode()
        result = 31 * result + ifacKey.contentHashCode()
        result = 31 * result + credentialGeneration.hashCode()
        result = 31 * result + expiresAtMs.hashCode()
        result = 31 * result + allowPublicRns.hashCode()
        result = 31 * result + revokedPeerIds.hashCode()
        result = 31 * result + revokedDestinationHashes.hashCode()
        result = 31 * result + wrapInterfaceFrames.hashCode()
        return result
    }
}

/** Security audit counters exposed by [RnsIfacFrameGuard] for RNS-05 / RNS-12 verification. */
internal data class RnsIfacSecurityStats(
    val framesSealed: Long = 0L,
    val framesAccepted: Long = 0L,
    val droppedPublicConfig: Long = 0L,
    val droppedExpiredLease: Long = 0L,
    val droppedWrongIfac: Long = 0L,
    val droppedRevokedPeer: Long = 0L,
    val droppedRevokedDestination: Long = 0L,
)

/** Deterministic IFAC key/network derivation aligned with Protocol X `/nearby` leases. */
internal object RnsIfacDerivation {
    fun deriveConfig(
        eventId: String,
        channelId: String,
        channelSecret: ByteArray,
        credentialGeneration: Long = 1L,
        expiresAtMs: Long = Long.MAX_VALUE,
        revokedPeerIds: Set<String> = emptySet(),
        revokedDestinationHashes: Set<String> = emptySet(),
        wrapInterfaceFrames: Boolean = true,
    ): RnsIfacConfig {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        require(channelSecret.isNotEmpty()) { "channelSecret must not be empty" }
        val networkName = "protocolx.$eventId.$channelId"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(channelSecret, "HmacSHA256"))
        }
        val derivedKey = mac.doFinal("protocolx-rns-ifac-v1|$networkName|$credentialGeneration".toByteArray(Charsets.UTF_8))
        return RnsIfacConfig(
            networkName = networkName,
            ifacKey = derivedKey,
            credentialGeneration = credentialGeneration,
            expiresAtMs = expiresAtMs,
            allowPublicRns = false,
            revokedPeerIds = revokedPeerIds,
            revokedDestinationHashes = revokedDestinationHashes,
            wrapInterfaceFrames = wrapInterfaceFrames,
        )
    }

    fun computeNetworkPrefix(networkName: String, ifacKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(networkName.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + ifacKey)
        return digest.copyOfRange(0, PREFIX_BYTES)
    }

    internal const val PREFIX_BYTES = 8
}

/**
 * Enforces RNS-05 private-network IFAC isolation, lease expiration, and immediate peer/destination
 * revocation at the Wi-Fi Aware / BLE L2CAP / [RnsFrameBridge] boundary.
 */
internal class RnsIfacFrameGuard(
    initialConfig: RnsIfacConfig,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    @Volatile private var config: RnsIfacConfig = initialConfig
    @Volatile private var networkPrefix: ByteArray = computePrefix(initialConfig)
    private val extraRevokedPeers = ConcurrentHashMap.newKeySet<String>()
    private val extraRevokedDestinations = ConcurrentHashMap.newKeySet<String>()

    private val framesSealed = AtomicLong(0L)
    private val framesAccepted = AtomicLong(0L)
    private val droppedPublicConfig = AtomicLong(0L)
    private val droppedExpiredLease = AtomicLong(0L)
    private val droppedWrongIfac = AtomicLong(0L)
    private val droppedRevokedPeer = AtomicLong(0L)
    private val droppedRevokedDestination = AtomicLong(0L)

    fun currentConfig(): RnsIfacConfig = config

    /** Rotates the IFAC configuration atomically without restarting radio sockets. */
    fun rotateConfig(newConfig: RnsIfacConfig) {
        synchronized(lock) {
            config = newConfig
            networkPrefix = computePrefix(newConfig)
        }
    }

    /** Revokes a local radio peer ID and/or RNS destination hash immediately. */
    fun revoke(peerId: String? = null, destinationHashHex: String? = null) {
        if (!peerId.isNullOrBlank()) extraRevokedPeers.add(peerId)
        if (!destinationHashHex.isNullOrBlank()) extraRevokedDestinations.add(destinationHashHex.lowercase())
    }

    /** Clears dynamic revocations (e.g., on full event credential reprovision). */
    fun clearDynamicRevocations() {
        extraRevokedPeers.clear()
        extraRevokedDestinations.clear()
    }

    fun isConfigurationValid(nowMs: Long = clockMs()): Boolean {
        val cfg = config
        if (cfg.allowPublicRns || cfg.networkName.isBlank() || cfg.ifacKey.isEmpty()) {
            droppedPublicConfig.incrementAndGet()
            return false
        }
        if (nowMs >= cfg.expiresAtMs) {
            droppedExpiredLease.incrementAndGet()
            return false
        }
        return true
    }

    fun isPeerAllowed(
        peerId: String?,
        destinationHashHex: String? = null,
        nowMs: Long = clockMs(),
    ): Boolean {
        if (!isConfigurationValid(nowMs)) return false
        val cfg = config
        if (!peerId.isNullOrBlank() && (peerId in cfg.revokedPeerIds || peerId in extraRevokedPeers)) {
            droppedRevokedPeer.incrementAndGet()
            return false
        }
        val normDest = destinationHashHex?.lowercase()
        if (!normDest.isNullOrBlank() &&
            (normDest in cfg.revokedDestinationHashes || normDest in extraRevokedDestinations)
        ) {
            droppedRevokedDestination.incrementAndGet()
            return false
        }
        return true
    }

    fun isDestinationAllowed(destinationHashHex: String, nowMs: Long = clockMs()): Boolean =
        isPeerAllowed(peerId = null, destinationHashHex = destinationHashHex, nowMs = nowMs)

    /** Seals an outbound raw RNS frame with the active IFAC prefix and HMAC-SHA256 tag. */
    fun sealOutboundFrame(rawFrame: ByteArray, nowMs: Long = clockMs()): ByteArray? {
        if (rawFrame.isEmpty() || !isConfigurationValid(nowMs)) return null
        val cfg: RnsIfacConfig
        val prefix: ByteArray
        synchronized(lock) {
            cfg = config
            prefix = networkPrefix
        }
        if (!cfg.wrapInterfaceFrames) {
            framesSealed.incrementAndGet()
            return rawFrame
        }
        val header = ByteBuffer.allocate(HEADER_WITHOUT_MAC_BYTES)
            .put(MAGIC_0)
            .put(MAGIC_1)
            .put(VERSION)
            .put(0.toByte())
            .putLong(cfg.credentialGeneration)
            .put(prefix)
            .array()
        val mac = computeMac(cfg.ifacKey, header, rawFrame)
        val out = ByteArray(HEADER_BYTES + rawFrame.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(mac, 0, out, header.size, MAC_BYTES)
        System.arraycopy(rawFrame, 0, out, HEADER_BYTES, rawFrame.size)
        framesSealed.incrementAndGet()
        return out
    }

    /** Verifies and strips the IFAC envelope from an inbound frame; returns null if rejected. */
    fun verifyInboundFrame(
        peerId: String?,
        frameBytes: ByteArray,
        nowMs: Long = clockMs(),
    ): ByteArray? {
        if (!isPeerAllowed(peerId = peerId, nowMs = nowMs)) return null
        val cfg: RnsIfacConfig
        val expectedPrefix: ByteArray
        synchronized(lock) {
            cfg = config
            expectedPrefix = networkPrefix
        }
        if (!cfg.wrapInterfaceFrames) {
            framesAccepted.incrementAndGet()
            return frameBytes
        }
        if (frameBytes.size <= HEADER_BYTES) {
            droppedWrongIfac.incrementAndGet()
            return null
        }
        if (frameBytes[0] != MAGIC_0 || frameBytes[1] != MAGIC_1 || frameBytes[2] != VERSION) {
            droppedWrongIfac.incrementAndGet()
            return null
        }
        val buf = ByteBuffer.wrap(frameBytes, 0, HEADER_BYTES)
        buf.position(4)
        val frameGeneration = buf.long
        val framePrefix = ByteArray(RnsIfacDerivation.PREFIX_BYTES)
        buf.get(framePrefix)
        val receivedMac = ByteArray(MAC_BYTES)
        buf.get(receivedMac)

        if (frameGeneration != cfg.credentialGeneration || !MessageDigest.isEqual(framePrefix, expectedPrefix)) {
            droppedWrongIfac.incrementAndGet()
            return null
        }
        val header = frameBytes.copyOfRange(0, HEADER_WITHOUT_MAC_BYTES)
        val payload = frameBytes.copyOfRange(HEADER_BYTES, frameBytes.size)
        val expectedMac = computeMac(cfg.ifacKey, header, payload)
        if (!MessageDigest.isEqual(receivedMac, expectedMac)) {
            droppedWrongIfac.incrementAndGet()
            return null
        }
        framesAccepted.incrementAndGet()
        return payload
    }

    fun stats(): RnsIfacSecurityStats = RnsIfacSecurityStats(
        framesSealed = framesSealed.get(),
        framesAccepted = framesAccepted.get(),
        droppedPublicConfig = droppedPublicConfig.get(),
        droppedExpiredLease = droppedExpiredLease.get(),
        droppedWrongIfac = droppedWrongIfac.get(),
        droppedRevokedPeer = droppedRevokedPeer.get(),
        droppedRevokedDestination = droppedRevokedDestination.get(),
    )

    private fun computePrefix(cfg: RnsIfacConfig): ByteArray =
        if (cfg.networkName.isBlank() || cfg.ifacKey.isEmpty()) ByteArray(RnsIfacDerivation.PREFIX_BYTES)
        else RnsIfacDerivation.computeNetworkPrefix(cfg.networkName, cfg.ifacKey)

    private fun computeMac(key: ByteArray, header: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        mac.update(header)
        return mac.doFinal(payload).copyOfRange(0, MAC_BYTES)
    }

    companion object {
        private const val MAGIC_0: Byte = 0x50 // 'P'
        private const val MAGIC_1: Byte = 0x58 // 'X'
        private const val VERSION: Byte = 0x01
        private const val MAC_BYTES = 16
        private const val HEADER_WITHOUT_MAC_BYTES = 4 + 8 + RnsIfacDerivation.PREFIX_BYTES
        const val HEADER_BYTES = HEADER_WITHOUT_MAC_BYTES + MAC_BYTES
    }
}
