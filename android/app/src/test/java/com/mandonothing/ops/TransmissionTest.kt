package com.mandonothing.ops

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransmissionTest {
    @Test
    fun releaseDuringClaimNeverOpensMicrophone() = runTest {
        val up = CompletableDeferred<Unit>()
        val grant = CompletableDeferred<Long>()
        val changes = mutableListOf<Boolean>()
        var releaseCount = 0
        launch {
            transmitWhilePressed(up, { grant.await() }, { 8_000 }, { releaseCount++ },
                { changes.add(it) }, { error("Unexpected publication") }, { error("Release failed") })
        }
        runCurrent()
        up.complete(Unit)
        grant.complete(8_000)
        runCurrent()
        assertTrue(changes.isEmpty())
        assertEquals(1, releaseCount)
    }

    @Test
    fun releaseWhileWaitingForPermissionNeverOpensMicrophone() = runTest {
        val up = CompletableDeferred<Unit>()
        val permission = CompletableDeferred<Unit>()
        val changes = mutableListOf<Boolean>()
        var releases = 0
        launch {
            transmitWhilePressed(up, { 8_000 }, { 8_000 }, { releases++ },
                { changes.add(it) }, { error("Unexpected publication") }, { error("Release failed") },
                onGranted = { permission.await() })
        }
        runCurrent()
        up.complete(Unit)
        runCurrent()
        assertTrue(changes.isEmpty())
        assertEquals(1, releases)
    }

    @Test
    fun releaseKeepsTheGrantUntilTheBufferedTailHasDrained() = runTest {
        val up = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val states = mutableListOf<String>()
        launch {
            transmitWhilePressed(up, { 8_000 }, { 8_000 }, { states += "released" },
                { enabled -> states += if (enabled) "opened" else "closed" }, {},
                { error("Release failed") }, onReleasedWhileSpeaking = {
                    drained.await()
                    states += "drained"
                })
        }
        runCurrent()
        up.complete(Unit)
        runCurrent()
        assertEquals(listOf("opened"), states)
        drained.complete(Unit)
        runCurrent()
        assertEquals(listOf("opened", "drained", "closed", "released"), states)
    }

    @Test
    fun releaseClosesMicrophoneBeforeAnInFlightRenewalFinishes() = runTest {
        val up = CompletableDeferred<Unit>()
        val renewal = CompletableDeferred<Long>()
        val changes = mutableListOf<Boolean>()
        var releaseCount = 0
        launch {
            transmitWhilePressed(up, { 8_000 }, { renewal.await() }, { releaseCount++ },
                { changes.add(it) }, {}, { error("Release failed") })
        }
        runCurrent()
        assertEquals(listOf(true), changes)
        advanceTimeBy(2_001)
        runCurrent()
        up.complete(Unit)
        runCurrent()
        assertEquals(listOf(true, false), changes)
        assertEquals(1, releaseCount)
    }

    @Test
    fun releaseCancelsPendingMicrophoneStartup() = runTest {
        val up = CompletableDeferred<Unit>()
        val microphoneReady = CompletableDeferred<Unit>()
        var closed = false
        launch {
            transmitWhilePressed(up, { 8_000 }, { 8_000 }, {}, { enabled ->
                if (enabled) microphoneReady.await() else closed = true
            }, { error("Unexpected publication") }, { error("Release failed") })
        }
        runCurrent()
        up.complete(Unit)
        runCurrent()
        assertTrue(closed)
    }
}
