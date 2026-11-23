package com.mandonothing.ops

import android.media.AudioFormat
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttPreRollTest {
    private fun frame(queue: PttPreRoll, number: Byte): Byte {
        val bytes = ByteBuffer.wrap(ByteArray(320) { number })
        queue.onBuffer(bytes, AudioFormat.ENCODING_PCM_16BIT, 1, 16_000, 320, 10)
        return bytes[0]
    }

    @Test
    fun replaysTheHeadThenLiveFramesInOrderAndStopsTakingNewInputOnRelease() = runTest {
        val queue = PttPreRoll()
        assertEquals(0, frame(queue, 1).toInt())
        assertEquals(0, frame(queue, 2).toInt())
        assertEquals(20, queue.startSending())
        assertEquals(1, frame(queue, 3).toInt())
        assertEquals(2, frame(queue, 4).toInt())
        queue.stopInput()
        assertEquals(3, frame(queue, 90).toInt())
        assertEquals(4, frame(queue, 91).toInt())
        queue.awaitDrain()
        assertEquals(0, frame(queue, 92).toInt())
    }

    @Test
    fun catchesUpDuringAQuietPauseAndPreservesBothWords() = runTest {
        val queue = PttPreRoll()
        frame(queue, 1)
        repeat(18) { frame(queue, 0) }
        frame(queue, 2)
        assertEquals(200, queue.startSending())
        assertEquals(1, frame(queue, 3).toInt())
        repeat(7) { assertEquals(0, frame(queue, 4).toInt()) }
        assertEquals(2, frame(queue, 5).toInt())
        assertEquals(90, queue.queuedDurationMs())
    }

    @Test
    fun trimsOnlyQuietFramesAfterTheLastWordOnRelease() = runTest {
        val queue = PttPreRoll()
        frame(queue, 1)
        frame(queue, 2)
        repeat(16) { frame(queue, 0) }
        assertEquals(180, queue.startSending())
        assertEquals(1, frame(queue, 0).toInt())
        queue.stopInput()
        assertEquals(50, queue.queuedDurationMs())
        assertEquals(2, frame(queue, 9).toInt())
        repeat(4) { assertEquals(0, frame(queue, 9).toInt()) }
        queue.awaitDrain()
        assertEquals(0, queue.queuedDurationMs())
    }

    @Test
    fun keepsAShortPauseAndTheAudioThatFollowsItOnRelease() = runTest {
        val queue = PttPreRoll()
        listOf<Byte>(1, 0, 0, 0, 0, 0, 2).forEach { frame(queue, it) }
        assertEquals(70, queue.startSending())
        assertEquals(1, frame(queue, 3).toInt())
        queue.stopInput()
        assertEquals(listOf(0, 0, 0, 0, 0, 2, 3), List(7) { frame(queue, 9).toInt() })
        queue.awaitDrain()
    }

    @Test
    fun releaseBeforePermissionDiscardsTheEntireRecording() {
        val queue = PttPreRoll()
        frame(queue, 1)
        queue.stopInput()
        assertNull(queue.startSending())
        assertEquals(0, frame(queue, 2).toInt())
        assertNull(queue.finish())
    }

    @Test
    fun timeoutDiscardsTheHeadInsteadOfSilentlySendingOnlyLaterWords() {
        var failures = 0
        val queue = PttPreRoll(onFailure = { failures++ })
        repeat(PTT_PREROLL_LIMIT_MS / 10 + 1) { assertEquals(0, frame(queue, 1).toInt()) }
        assertEquals(1, failures)
        assertTrue(queue.overflowed)
        assertNull(queue.startSending())
    }
}
