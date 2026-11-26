package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommEnvelopeKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RnsLocalStoreTest {
    private val testKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    private val jvmAesGcmCipher = object : RnsPayloadCipher {
        override fun encrypt(data: ByteArray, context: String): ByteArray {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, testKey, GCMParameterSpec(128, nonce))
                updateAAD(context.toByteArray(Charsets.UTF_8))
            }
            return nonce + cipher.doFinal(data)
        }

        override fun decrypt(data: ByteArray, context: String): ByteArray {
            require(data.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, testKey, GCMParameterSpec(128, data.copyOfRange(0, 12)))
                updateAAD(context.toByteArray(Charsets.UTF_8))
            }
            return cipher.doFinal(data, 12, data.size - 12)
        }
    }

    @Test
    fun encryptedIdentityAndRuntimeConfigSurviveStoreRestart() {
        val dir = Files.createTempDirectory("rns-store-id-").toFile()
        try {
            val store1 = RnsLocalStore(dir, jvmAesGcmCipher)
            val secret = ByteArray(64) { (it * 3).toByte() }
            store1.saveIdentity(
                StoredRnsIdentity(
                    accountKey = "acc-1",
                    secretBytes = secret,
                    identityHashHex = "11".repeat(16),
                    destinationHashHex = "22".repeat(16),
                    createdAtMs = 1_000L,
                ),
            )
            store1.saveRuntimeConfig(
                RnsRuntimeConfig(
                    accountKey = "acc-1",
                    eventId = "evt-golf",
                    channelId = "ch-1",
                    channelName = "一组",
                    actorId = "person-a",
                    tcpConnectAddrs = listOf("127.0.0.1:4242"),
                    propagationNodeHashHex = "aa".repeat(16),
                    enableTransportRelay = true,
                ),
            )

            // Reopen against same encrypted files
            val store2 = RnsLocalStore(dir, jvmAesGcmCipher)
            val loadedId = store2.loadIdentity("acc-1")
            assertNotNull(loadedId)
            assertArrayEquals(secret, loadedId!!.secretBytes)
            assertEquals("11".repeat(16), loadedId.identityHashHex)
            assertEquals("22".repeat(16), loadedId.destinationHashHex)

            val loadedConfig = store2.loadRuntimeConfig()
            assertNotNull(loadedConfig)
            assertEquals("evt-golf", loadedConfig!!.eventId)
            assertEquals(listOf("127.0.0.1:4242"), loadedConfig.tcpConnectAddrs)
            assertTrue(loadedConfig.enableTransportRelay)

            store2.clearRuntimeConfig()
            assertNull(store2.loadRuntimeConfig())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun outboxIsIdempotentTracksReceiptsAndInboundDeduplicatesByMessageHash() {
        val dir = Files.createTempDirectory("rns-store-msg-").toFile()
        try {
            val store = RnsLocalStore(dir, jvmAesGcmCipher)
            val first = LxmfOutboxRecord(
                id = "env-1",
                accountKey = "acc-1",
                eventId = "evt-1",
                channelId = "ch-1",
                destinationHashHex = "cc".repeat(16),
                envelopeKind = CommEnvelopeKind.CHANNEL_TEXT,
                title = "title",
                text = "hello",
                createdAtMs = 100L,
                updatedAtMs = 100L,
            )
            store.enqueueOutbox(first)
            // Duplicate enqueue with different text must keep original immutable record
            val duplicate = store.enqueueOutbox(first.copy(text = "tampered"))
            assertEquals("hello", duplicate.text)

            store.updateOutboxStatus(
                id = "env-1",
                status = LxmfDeliveryStatus.SENT,
                messageHashHex = "dd".repeat(16),
                incrementAttempt = true,
                nowMs = 200L,
            )
            assertEquals(1, store.pendingOutbox("acc-1", "evt-1").size)
            assertEquals("env-1", store.findOutboxByMessageHash("dd".repeat(16))?.id)

            store.updateOutboxStatus("env-1", LxmfDeliveryStatus.DELIVERED, nowMs = 300L)
            assertTrue(store.pendingOutbox("acc-1", "evt-1").isEmpty())

            val inbound = LxmfInboundRecord(
                messageHashHex = "ee".repeat(16),
                envelopeId = "in-1",
                sourceHashHex = "ff".repeat(16),
                destinationHashHex = "22".repeat(16),
                eventId = "evt-1",
                channelId = "ch-1",
                senderActor = "person-b",
                senderName = "李四",
                envelopeKind = CommEnvelopeKind.VOICE_NOTE,
                text = "",
                attachmentKind = LxmfAttachmentKind.AUDIO_CLIP,
                attachmentBytesB64 = "AQIDBA==",
                receivedAtMs = 400L,
            )
            assertTrue(store.saveInboundIfNew(inbound))
            assertFalse(store.saveInboundIfNew(inbound))
            assertEquals(1, store.inboundMessages("evt-1", "ch-1").size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
