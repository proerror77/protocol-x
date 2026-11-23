package com.mandonothing.ops

import android.media.AudioFormat
import io.livekit.android.audio.AudioBufferCallback
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer

internal const val PTT_PREROLL_LIMIT_MS = 3_000
private const val QUIET_PEAK_PCM16 = 64
private const val PAUSE_TO_KEEP_MS = 70
private const val END_SILENCE_TO_KEEP_MS = 40

/** Buffers one press on the room's existing microphone, at the encoder's frame cadence. */
internal class PttPreRoll(
    private val onFirstFrame: () -> Unit = {},
    private val onFailure: () -> Unit = {},
) : AudioBufferCallback {
    private enum class Phase { BUFFERING, SENDING, DRAINING, CLOSED }
    private data class Frame(val bytes: ByteArray, val quiet: Boolean)

    private val pending = ArrayDeque<Frame>()
    private val sent = PttPcm()
    private val drained = CompletableDeferred<Unit>()
    private var phase = Phase.BUFFERING
    private var bytesQueued = 0
    private var frameSize = 0
    private var rate = 0
    private var channels = 0
    private var sentAudibleFrame = false
    @Volatile
    var overflowed = false
        private set

    @Synchronized
    override fun onBuffer(buffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int,
                          bytesRead: Int, captureTimeNs: Long): Long {
        val bytes = buffer.duplicate().apply { clear() }
        if (bytesRead !in 1..bytes.capacity()) return captureTimeNs
        if (phase == Phase.CLOSED) {
            clearFrame(bytes, bytesRead)
            return captureTimeNs
        }
        if ((audioFormat != AudioFormat.ENCODING_PCM_16BIT && audioFormat != AudioFormat.ENCODING_DEFAULT) ||
            sampleRate !in 8000..48000 || channelCount !in 1..2 || bytesRead % (channelCount * 2) != 0 ||
            (frameSize != 0 && (frameSize != bytesRead || rate != sampleRate || channels != channelCount))) {
            abort()
            clearFrame(bytes, bytesRead)
            onFailure()
            return captureTimeNs
        }
        if (frameSize == 0) {
            frameSize = bytesRead
            rate = sampleRate
            channels = channelCount
            onFirstFrame()
        }
        if (phase != Phase.DRAINING) {
            if (phase == Phase.BUFFERING && bytesQueued + bytesRead > rate * channels * 2 * PTT_PREROLL_LIMIT_MS / 1000) {
                overflowed = true
                abort()
                clearFrame(bytes, bytesRead)
                onFailure()
                return captureTimeNs
            }
            val frame = ByteArray(bytesRead)
            bytes.get(frame)
            pending.addLast(Frame(frame, isQuiet(frame)))
            bytesQueued += bytesRead
        }
        if (phase == Phase.BUFFERING || pending.isEmpty()) {
            clearFrame(bytes, bytesRead)
        } else {
            trimQuietPause()
            val frame = pending.removeFirst()
            bytesQueued -= frame.bytes.size
            if (!frame.quiet) sentAudibleFrame = true
            bytes.position(0)
            bytes.put(frame.bytes)
            sent.append(ByteBuffer.wrap(frame.bytes), 16, rate, channels, frame.bytes.size / (channels * 2))
        }
        if (phase == Phase.DRAINING && pending.isEmpty()) drained.complete(Unit)
        return captureTimeNs
    }

    /** Returns the queued head duration, or null after release/overflow. */
    @Synchronized
    fun startSending(): Int? {
        if (phase != Phase.BUFFERING) return null
        phase = Phase.SENDING
        return queuedDurationMs()
    }

    @Synchronized
    fun stopInput() {
        if (phase == Phase.BUFFERING) {
            abort()
        } else if (phase == Phase.SENDING) {
            phase = Phase.DRAINING
            trimQuietEnd()
            if (pending.isEmpty()) drained.complete(Unit)
        }
    }

    suspend fun awaitDrain() = drained.await()

    @Synchronized
    fun isDraining(): Boolean = phase == Phase.DRAINING

    @Synchronized
    fun queuedDurationMs(): Int = if (rate == 0) 0 else bytesQueued * 1000 / (rate * channels * 2)

    @Synchronized
    fun abort() {
        phase = Phase.CLOSED
        pending.clear()
        bytesQueued = 0
        drained.complete(Unit)
    }

    fun finish(): PttPcmSnapshot? {
        abort()
        return sent.finish()
    }

    private fun trimQuietPause() {
        // Preserve the opening and short speech gaps. Only use the middle of a quiet pause to catch up.
        if (!sentAudibleFrame || !pending.first().quiet) return
        val keep = framesFor(PAUSE_TO_KEEP_MS)
        var quiet = 0
        while (quiet < pending.size && pending[quiet].quiet) quiet++
        repeat((quiet - keep).coerceAtLeast(0)) {
            pending.removeFirst()
            bytesQueued -= frameSize
        }
    }

    private fun trimQuietEnd() {
        if (pending.isEmpty()) return
        val keep = framesFor(END_SILENCE_TO_KEEP_MS)
        var quiet = 0
        while (quiet < pending.size && pending[pending.lastIndex - quiet].quiet) quiet++
        repeat((quiet - keep).coerceAtLeast(0)) {
            pending.removeLast()
            bytesQueued -= frameSize
        }
    }

    private fun framesFor(ms: Int): Int =
        ((rate * channels * 2 * ms + frameSize * 1000 - 1) / (frameSize * 1000)).coerceAtLeast(1)

    private fun isQuiet(frame: ByteArray): Boolean {
        for (index in frame.indices step 2) {
            val sample = (frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)
            if (sample !in -QUIET_PEAK_PCM16..QUIET_PEAK_PCM16) return false
        }
        return true
    }

    private fun clearFrame(bytes: ByteBuffer, count: Int) {
        for (index in 0 until count) bytes.put(index, 0)
    }
}
