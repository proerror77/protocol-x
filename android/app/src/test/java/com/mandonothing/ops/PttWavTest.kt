package com.mandonothing.ops

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PttWavTest {
    private fun header(rate: Int = PttWav.RATE) = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + 3200).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(2 * rate).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(3200).array()

    @Test fun startsAfterReadingOnlyPcmHeader() {
        val source = DataInputStream(ByteArrayInputStream(header() + ByteArray(3_200)))
        assertEquals(3200, PttWav.length(source))
        assertEquals(3200, source.available())
    }

    @Test fun streamedClipCachesExactlyTheBytesConsumed() {
        val original = header() + ByteArray(3200) { (it % 127).toByte() }
        val capture = CapturedClip(original.inputStream())
        val input = DataInputStream(capture)
        val count = PttWav.length(input)
        input.readFully(ByteArray(count))
        assertArrayEquals(original, capture.bytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedSampleRate() {
        PttWav.length(DataInputStream(ByteArrayInputStream(header(8000))))
    }
}
