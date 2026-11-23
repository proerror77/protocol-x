package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.AEADBadTagException

class NearbyPrivateTest {
    private val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    private val sender = generator.generateKeyPair()
    private val recipient = generator.generateKeyPair()
    private val relay = generator.generateKeyPair()
    private val context = "channel + key generation + actors + message ID".toByteArray()
    private val text = "接驳点改到 B2"

    private fun seal() = NearbyPrivate.seal(context,
        Base64.getEncoder().encodeToString(sender.public.encoded),
        Base64.getEncoder().encodeToString(recipient.public.encoded), text, null)

    @Test fun senderAndRecipientCanOpenButRelayCannot() {
        val envelope = seal()
        val incoming = NearbyPrivate.open(context, recipient.private, false, envelope)
        assertEquals(text, incoming.text)
        assertNull(incoming.photo)
        assertEquals(text, NearbyPrivate.open(context, sender.private, true, envelope).text)
        assertThrows(AEADBadTagException::class.java) { NearbyPrivate.open(context, relay.private, false, envelope) }
        assertThrows(AEADBadTagException::class.java) { NearbyPrivate.open(context, sender.private, false, envelope) }
        assertNotEquals(envelope, seal())
    }

    @Test fun alteredContextCiphertextAndOversizedEnvelopeAreRejected() {
        val envelope = seal()
        assertThrows(AEADBadTagException::class.java) {
            NearbyPrivate.open("another message".toByteArray(), recipient.private, false, envelope)
        }
        val tampered = Base64.getDecoder().decode(envelope)
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) {
            NearbyPrivate.open(context, recipient.private, false, Base64.getEncoder().encodeToString(tampered))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NearbyPrivate.open(context, recipient.private, false, "A".repeat(NEARBY_PRIVATE_ENVELOPE_LIMIT + 1))
        }
    }
}
