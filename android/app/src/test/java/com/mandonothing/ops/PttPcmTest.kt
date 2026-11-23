package com.mandonothing.ops

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PttPcmTest {
    @Test fun skipsSilenceAndAnIsolatedTapWhileRetainingQuietSpeechEnergy() {
        assertFalse(hasPttSignal(shortArrayOf()))
        assertFalse(hasPttSignal(ShortArray(960) { if (it % 2 == 0) 75 else -75 }))
        assertFalse(hasPttSignal(ShortArray(960).apply { this[400] = 3000 }))
        assertFalse(hasPttSignal(ShortArray(4800) { if (it % 2 == 0) 550 else -550 }))
        assertTrue(hasPttSignal(ShortArray(5120) { if (it % 2 == 0) 550 else -550 }))
        assertTrue(hasPttSignal(ShortArray(960) { if (it % 2 == 0) 1400 else -1400 }))
    }
    @Test fun takesASeparateCopyAndBuildsCanonicalCacheAndCaptionPcm() {
        val capture = PttPcm()
        val buffer = ByteBuffer.allocate(480 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { buffer.putShort(3000).putShort(1000) }
        buffer.flip()
        repeat(30) { capture.append(buffer, 16, 48000, 2, 480) }
        assertEquals(0, buffer.position())
        val samples = capture.finish()!!.mono16()
        assertEquals(4800, samples.size)
        assertEquals(2000, samples[0].toInt())
        assertEquals(9600, PttWav.length(DataInputStream(wave16(samples).inputStream())))
        assertNull(capture.finish())
    }

    @Test fun doesNotMixSamplesAcrossOutputRouteChanges() {
        val capture = PttPcm()
        capture.append(ByteBuffer.allocate(9600), 16, 16000, 1, 4800)
        capture.append(ByteBuffer.allocate(960), 16, 48000, 1, 480)
        assertNull(capture.finish())
    }
}
