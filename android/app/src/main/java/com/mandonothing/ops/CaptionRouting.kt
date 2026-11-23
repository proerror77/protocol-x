package com.mandonothing.ops

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ServerCaption { PENDING, READY, UNAVAILABLE }

internal fun serverCaption(status: String, text: String): ServerCaption = when (status) {
    "ready" -> if (text.isNotBlank() && text != "null") ServerCaption.READY else ServerCaption.UNAVAILABLE
    "failed", "unavailable", "no_speech" -> ServerCaption.UNAVAILABLE
    else -> ServerCaption.PENDING
}

internal data class CaptionOnlineState(
    val hasNetwork: Boolean = false,
    val validatedInternet: Boolean = false,
    val archiveReachable: Boolean = true,
    val reviews: Map<String, ServerCaption> = emptyMap(),
)

internal const val ONLINE_CAPTION_WAIT_MS = 2_500L

// A completed server transcript is authoritative. Wait briefly for it before
// starting on-device ASR, and release the wait as soon as the route fails.
internal suspend fun needsLocalCaption(
    route: StateFlow<CaptionOnlineState>,
    trackSid: String,
    archiveEnabled: Boolean,
    requireValidatedInternet: Boolean,
    deadlineMs: Long = ONLINE_CAPTION_WAIT_MS,
): Boolean {
    val result = withTimeoutOrNull(deadlineMs) {
        route.map { snapshot ->
            when {
                snapshot.reviews[trackSid] == ServerCaption.READY -> ServerCaption.READY
                !archiveEnabled || !snapshot.archiveReachable || !snapshot.hasNetwork ||
                    (requireValidatedInternet && !snapshot.validatedInternet) -> ServerCaption.UNAVAILABLE
                else -> snapshot.reviews[trackSid] ?: ServerCaption.PENDING
            }
        }.first { it != ServerCaption.PENDING }
    }
    // A server response can arrive on the same tick as the timeout.
    return result != ServerCaption.READY && route.value.reviews[trackSid] != ServerCaption.READY
}
