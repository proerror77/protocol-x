package com.mandonothing.ops

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionRoutingTest {
    private val connected = CaptionOnlineState(hasNetwork = true, validatedInternet = true)

    @Test fun serverTextArrivesInTimeAndPhoneDoesNotDecodeAgain() = runTest {
        val state = MutableStateFlow(connected)
        val waiting = async { needsLocalCaption(state, "clip-a", true, true) }
        runCurrent()
        advanceTimeBy(1_200)
        assertFalse(waiting.isCompleted)
        state.value = connected.copy(reviews = mapOf("clip-a" to ServerCaption.READY))
        runCurrent()
        assertFalse(waiting.await())
    }

    @Test fun missingOrUnvalidatedConnectionFallsBackWithoutWaiting() = runTest {
        val offline = MutableStateFlow(CaptionOnlineState())
        assertTrue(needsLocalCaption(offline, "a", true, true))
        val captive = MutableStateFlow(connected.copy(validatedInternet = false))
        assertTrue(needsLocalCaption(captive, "a", true, true))
    }

    @Test fun serverFailureReleasesPendingWaitForOnlyTheMatchingTrack() = runTest {
        val state = MutableStateFlow(connected)
        val waiting = async { needsLocalCaption(state, "a", true, true) }
        runCurrent()
        state.value = connected.copy(reviews = mapOf("b" to ServerCaption.READY))
        runCurrent()
        assertFalse(waiting.isCompleted)
        state.value = connected.copy(reviews = mapOf("a" to ServerCaption.UNAVAILABLE))
        runCurrent()
        assertTrue(waiting.await())
    }

    @Test fun disconnectAndTimeoutBothReleaseTheOnDeviceModel() = runTest {
        val state = MutableStateFlow(connected)
        val interrupted = async { needsLocalCaption(state, "a", true, true) }
        runCurrent()
        state.value = connected.copy(archiveReachable = false)
        runCurrent()
        assertTrue(interrupted.await())

        state.value = connected
        val exceedsDeadline = async { needsLocalCaption(state, "b", true, true) }
        runCurrent()
        advanceTimeBy(ONLINE_CAPTION_WAIT_MS - 1)
        assertFalse(exceedsDeadline.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(exceedsDeadline.await())
    }

    @Test fun finishedServerCaptionWinsEvenAfterNetworkIsLost() = runTest {
        val state = MutableStateFlow(CaptionOnlineState(reviews = mapOf("a" to ServerCaption.READY)))
        assertFalse(needsLocalCaption(state, "a", true, true))
        assertEquals(ServerCaption.UNAVAILABLE, serverCaption("ready", " "))
        assertEquals(ServerCaption.UNAVAILABLE, serverCaption("ready", "null"))
    }
}
