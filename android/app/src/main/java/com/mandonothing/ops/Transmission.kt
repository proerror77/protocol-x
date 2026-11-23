package com.mandonothing.ops

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** One press owns one floor lease. A release received during claim never opens the mic. */
internal suspend fun transmitWhilePressed(
    released: CompletableDeferred<Unit>,
    claim: suspend () -> Long,
    renew: suspend () -> Long,
    release: suspend () -> Unit,
    microphone: suspend (Boolean) -> Unit,
    onSpeaking: () -> Unit,
    onReleaseFailure: () -> Unit,
    onGranted: suspend () -> Unit = {},
    onReleasedWhileSpeaking: suspend () -> Unit = {},
) {
    var mayHaveOpened = false
    suspend fun silence() {
        if (mayHaveOpened) {
            mayHaveOpened = false
            microphone(false)
        }
    }
    try {
        if (released.isCompleted) return
        val leaseMs = claim()
        check(leaseMs > 0) { "话权已过期，请重新按住" }
        if (released.isCompleted) return
        val started = coroutineScope {
            val opening = async {
                onGranted()
                if (released.isCompleted) return@async
                mayHaveOpened = true
                microphone(true)
            }
            try {
                select {
                    released.onAwait { false }
                    opening.onAwait { true }
                }
            } finally {
                opening.cancelAndJoin()
            }
        }
        if (!started || released.isCompleted) return
        onSpeaking()
        coroutineScope {
            val heartbeat = launch {
                var remainingMs = leaseMs
                while (true) {
                    delay((remainingMs / 3).coerceIn(100, 2_000))
                    remainingMs = renew()
                    check(remainingMs > 0) { "话权已过期，请重新按住" }
                }
            }
            try {
                released.await()
                onReleasedWhileSpeaking()
            } finally {
                heartbeat.cancel()
                withContext(NonCancellable) { silence() }
            }
        }
    } finally {
        withContext(NonCancellable) {
            try {
                silence()
            } finally {
                try {
                    release()
                } catch (_: Exception) {
                    onReleaseFailure()
                }
            }
        }
    }
}
