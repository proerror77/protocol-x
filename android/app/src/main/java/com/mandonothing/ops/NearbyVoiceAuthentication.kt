package com.mandonothing.ops

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal class NearbyVoiceAuthentication(
    private val group: String,
    private val identity: NearbyIdentity,
    private val credential: () -> NearbyConfig?,
) {
    private val recent = LinkedHashSet<String>()

    fun protect(packet: NearbyLivePacket): NearbyLivePacket? = runCatching {
        val config = credential()?.takeIf { it.live() && it.voiceEnabled } ?: return null
        val secured = packet.copy(publicKey = identity.publicKey, certificate = config.certificate)
        secured.copy(signature = identity.sign(secured.signedContent(group)))
    }.getOrNull()

    @Synchronized fun verify(peerNode: String, packets: List<NearbyLivePacket>): List<NearbyLivePacket> {
        val config = credential()?.takeIf { it.live() && it.voiceEnabled } ?: return emptyList()
        val now = System.currentTimeMillis()
        return packets.filter { packet ->
            if (peerNode.length != 8 || !packet.sender.startsWith(peerNode) ||
                packet.at !in (now - 2_500)..(now + 2_500) || packet.signature.isEmpty()) return@filter false
            val id = "${packet.sender}:${packet.burst}:${packet.sequence}"
            if (id in recent) return@filter false
            val accepted = runCatching {
                val keyBytes = Base64.getDecoder().decode(packet.publicKey)
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(keyBytes)
                    .take(16).joinToString("") { "%02x".format(it) }
                if (fingerprint != packet.sender ||
                    config.verifiedSender(packet.certificate, packet.publicKey, packet.at) == null) return@runCatching false
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(packet.signedContent(group))
                    verify(Base64.getDecoder().decode(packet.signature))
                }
            }.getOrDefault(false)
            if (accepted) {
                recent.add(id)
                if (recent.size > 256) recent.remove(recent.first())
            }
            accepted
        }
    }
}
