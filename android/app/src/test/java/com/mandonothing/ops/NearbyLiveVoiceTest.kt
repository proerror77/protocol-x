package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class NearbyLiveVoiceTest {
    @Test
    fun telephoneCodecKeepsSilenceAndAmplitudePolarity() {
        val values = ShortArray(1_280)
        for (index in 0 until 400) values[index] = 12_000
        for (index in 400 until 800) values[index] = -12_000
        val wire = encodeNearbyLive(values, 0)
        assertEquals(640, wire.size)
        val decoded = decodeNearbyLive(wire)
        assertTrue(decoded.take(200).all { it in 11_200..12_800 })
        assertTrue(decoded.drop(200).take(200).all { it in -12_800..-11_200 })
        assertTrue(decoded.drop(400).all { it == 0.toShort() })
    }

    @Test
    fun wavFallbackHasAPlatformReadableHeaderWithinTheOutboxLimit() {
        val samples = ShortArray(128_000)
        val wave = nearbyFallbackWav(samples, samples.size)
        assertEquals(NEARBY_AUDIO_LIMIT, wave.size)
        val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wave, 0, 4))
        assertEquals(wave.size - 8, header.getInt(4))
        assertEquals("WAVEfmt ", String(wave, 8, 8))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(8_000, header.getInt(24))
        assertEquals(8, header.getShort(34).toInt())
        assertEquals(wave.size - 44, header.getInt(40))
        assertEquals(0x80, wave[44].toInt() and 0xff)
        assertEquals(44, nearbyFallbackWav(ShortArray(2), 2).size)
    }

    @Test
    fun receiveOrdersDuplicatesAndDrainsBeforeRetiringTheBurst() {
        val jitter = NearbyLiveJitter()
        val first = audio("a", 0, positive)
        val second = audio("a", 1, negative)
        assertTrue(jitter.offer(second, 1_000))
        assertTrue(jitter.offer(first, 1_020))
        assertFalse(jitter.offer(second, 1_030))
        assertTrue(jitter.offer(second.copy(sequence = 2, audio = "", end = true), 1_035))
        assertNull(jitter.playout(1_080)?.samples)
        assertTrue(jitter.playout(1_160)?.samples?.all { it > 0 } == true)
        assertTrue(jitter.playout(1_240)?.samples?.all { it < 0 } == true)
        assertNull(jitter.owner(1_241))
        assertFalse(jitter.offer(first, 1_242))
    }

    @Test
    fun concurrentTalkersChooseSameOwnerRegardlessOfArrivalOrder() {
        val lower = audio("a", 0, positive)
        val higher = audio("b", 0, negative).copy(burst = "other")
        for (packets in listOf(listOf(lower, higher), listOf(higher, lower))) {
            val jitter = NearbyLiveJitter()
            packets.forEach { assertTrue(jitter.offer(it, 100)) }
            assertEquals(lower.sender, jitter.owner(120))
            assertNotNull(jitter.playout(270)?.samples)
            assertFalse(jitter.offer(audio("a", 0, positive), 700))
            assertNull(jitter.owner(750))
        }
    }

    @Test
    fun signedBytesBindThePayloadScopeAndPeerCredential() {
        val source = audio("a", 0, positive)
        val proof = source.signedContent("channel")
        assertTrue(proof.contentEquals(source.copy(signature = "sig").signedContent("channel")))
        assertFalse(proof.contentEquals(source.copy(audio = negative).signedContent("channel")))
        assertFalse(proof.contentEquals(source.copy(certificate = "other").signedContent("channel")))
        assertFalse(proof.contentEquals(source.copy(sequence = 1).signedContent("channel")))
        assertFalse(proof.contentEquals(source.signedContent("different")))
        assertTrue(source.fresh(1_200))
        assertFalse(source.fresh(2_100))
    }

    private fun audio(sender: String, sequence: Int, payload: String) =
        NearbyLivePacket(sender, "burst", sequence, 1_000, payload, false)

    private val positive = Base64.getEncoder().encodeToString(encodeNearbyLive(ShortArray(1_280) { 8_000 }, 0))
    private val negative = Base64.getEncoder().encodeToString(encodeNearbyLive(ShortArray(1_280) { -8_000 }, 0))
}
