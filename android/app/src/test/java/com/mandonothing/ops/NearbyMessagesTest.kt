package com.mandonothing.ops

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException

class NearbyMessagesTest {
    @Test
    fun credentialIsCanonicalAndScopesDiscoveryAndFramesToItsEvent() {
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        assertTrue(NearbyGroup.isSecret(code))
        assertFalse(NearbyGroup.isSecret(code.dropLast(1)))
        val first = NearbyGroup(code, "https://example.test|first")
        val second = NearbyGroup(code, "https://example.test|second")
        assertNotEquals(first.serviceName, second.serviceName)
        assertNotEquals(first.radioUuid, second.radioUuid)
        val original = "task assistance".toByteArray()
        val encrypted = first.seal(original, true)
        assertArrayEquals(original, first.open(encrypted, true))
        assertThrows(AEADBadTagException::class.java) { second.open(encrypted, true) }
        assertThrows(AEADBadTagException::class.java) { first.open(encrypted, false) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { first.open(encrypted, true) }
    }

    @Test
    fun deliveryRequiresAPeerReceiptAndUnknownRoutesStayUnknown() {
        assertEquals(NearbyDelivery.WAITING, nearbyDelivery(false, null))
        assertEquals(NearbyDelivery.AWAITING_RECEIPT, nearbyDelivery(false, NearbyRadio.WIFI))
        assertEquals(NearbyDelivery.DELIVERED, nearbyDelivery(true, NearbyRadio.BLE))
        assertEquals(NearbyDelivery.DELIVERED, nearbyDelivery(true, null))
        assertEquals(NearbyRadio.WIFI, nearbyRadio("WIFI"))
        assertEquals(NearbyRadio.BLE, nearbyRadio("BLE"))
        assertNull(nearbyRadio("not-reported"))
    }

    @Test
    fun fullHistoryRollsAndKeepsPendingMessagesAndDeduplicationMarkers() {
        val now = NEARBY_SEND_WINDOW * 5
        val pending = row(1, now - NEARBY_SEND_WINDOW + 1, sender = "my-device")
        val history = (2..160).map { row(it, now - 180 + it, received = true) }
        val initial = NearbyJournal(listOf(pending) + history).retain("my-device", now)
        assertEquals(128, initial.entries.size)
        assertTrue(initial.entries.any { it.id == pending.id })
        assertTrue(history.first().id in initial.tombstones)
        assertFalse(history.last().id in initial.tombstones)

        val incoming = row(161, now - NEARBY_SEND_WINDOW + 2)
        val compact = initial.copy(entries = initial.entries + incoming)
            .retain("my-device", now, setOf(incoming.id))
        assertTrue(compact.entries.any { it.id == incoming.id })
        assertTrue(compact.entries.any { it.id == pending.id })
        assertTrue(history.first().id in compact.tombstones)
        val aged = compact.retain("my-device", now + 2)
        val old = aged.entries.single { it.id == pending.id }
        assertEquals(NearbyDelivery.EXPIRED, old.delivery)
        assertTrue(aged.active(now + 2).none { it.id == pending.id })
        assertTrue(compact.retain("my-device", now + NEARBY_SEND_WINDOW * 2).tombstones.isEmpty())
    }

    @Test
    fun queuedOrdinaryTextCannotUseTheAssistanceOrConfirmationCapacity() {
        val now = NEARBY_SEND_WINDOW * 5
        val pending = (1..24).map { row(it, now - it, sender = "my-device") }
        assertFalse(nearbyCanQueue(pending, "my-device", priority = false, control = false, now = now))
        assertTrue(nearbyCanQueue(pending, "my-device", priority = true, control = false, now = now))
        assertTrue(nearbyCanQueue(pending, "my-device", priority = true, control = true, now = now))
        assertTrue(nearbyCanQueue(pending, "my-device", priority = false, control = false,
            now = now + NEARBY_SEND_WINDOW))
        assertEquals(NearbyDelivery.EXPIRED, nearbyDelivery(false, NearbyRadio.WIFI, expired = true))
        assertEquals(NearbyDelivery.DELIVERED, nearbyDelivery(true, null, expired = true))
    }

    @Test
    fun privateDeliveryRequiresProofFromTheAddressedMember() {
        val time = NEARBY_SEND_WINDOW * 5
        val original = row(1, time, sender = "first", actor = "sender")
            .copy(recipient = "recipient", sealedPayload = "YWJj", confirmationRequired = true)
        val relayReceipt = row(2, time + 1, sender = "second", actor = "relay")
            .copy(receiptFor = original.id, recipient = original.actor)
        assertFalse(validAcknowledgment(original, relayReceipt))
        assertFalse(validAcknowledgment(original, relayReceipt.copy(actor = "recipient", recipient = "relay")))
        assertTrue(validAcknowledgment(original, relayReceipt.copy(actor = "recipient")))
        assertTrue(validAcknowledgment(original, relayReceipt.copy(actor = "recipient",
            confirmationFor = original.id, receiptFor = "")))
        val groupMessage = original.copy(recipient = "", sealedPayload = "")
        assertFalse(validAcknowledgment(groupMessage, relayReceipt))
        assertTrue(validAcknowledgment(groupMessage, relayReceipt.copy(receiptFor = "",
            confirmationFor = original.id)))
    }

    @Test
    fun extensionsChangeTheSignedBytesWhileLegacyTextRemainsCompatible() {
        val legacy = row(1, 123)
        val expected = ByteArrayOutputStream()
        DataOutputStream(expected).use {
            it.writeUTF("channel")
            it.writeUTF(legacy.id)
            it.writeUTF(legacy.sender)
            it.writeUTF(legacy.name)
            it.writeUTF(legacy.text)
            it.writeUTF(legacy.audio)
            it.writeLong(legacy.time)
            it.writeBoolean(legacy.assistance)
            it.writeUTF(legacy.certificate)
        }
        assertArrayEquals(expected.toByteArray(), legacy.signedContent("channel"))
        val extended = legacy.copy(confirmationRequired = true)
        assertFalse(expected.toByteArray().contentEquals(extended.signedContent("channel")))
        assertFalse(legacy.copy(recipient = "alice", sealedPayload = "YWJj").signedContent("channel")
            .contentEquals(legacy.copy(recipient = "bob", sealedPayload = "YWJj").signedContent("channel")))
    }

    private fun row(index: Int, time: Long, sender: String = "someone", actor: String = "other",
                    received: Boolean = false) =
        NearbyMessage(UUID(0, index.toLong()).toString(), sender, actor, "text", "", time,
            false, "key", "signature", "cert", received = received, actor = actor)

    @Test
    fun framingAcceptsCompletePacketsAndRejectsOversizedLengths() {
        val frame = ByteArray(28) { it.toByte() }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeNearby(frame)
        assertArrayEquals(frame, DataInputStream(ByteArrayInputStream(buffer.toByteArray())).readNearby())
        buffer.reset()
        DataOutputStream(buffer).writeInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            DataInputStream(ByteArrayInputStream(buffer.toByteArray())).readNearby()
        }
    }
}
