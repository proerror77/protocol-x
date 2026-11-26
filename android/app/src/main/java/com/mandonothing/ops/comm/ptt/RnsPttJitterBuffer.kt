package com.mandonothing.ops.comm.ptt

import com.mandonothing.ops.PttPcmSnapshot
import com.mandonothing.ops.VoiceCapturedClip
import java.io.ByteArrayOutputStream
import java.util.TreeMap

/** Latency and packet continuity metrics recorded by [RnsPttJitterBuffer]. */
internal data class RnsPttPlayoutStats(
    val framesPlayed: Long = 0L,
    val framesConcealed: Long = 0L,
    val lastLatencyMs: Long = 0L,
    val maxLatencyMs: Long = 0L,
    val averageLatencyMs: Double = 0.0,
)

/**
 * Ordered playout jitter buffer + burst archiver for RNS-10 multi-hop PTT audio frames.
 * Produces [VoiceCapturedClip] on burst completion for `VoiceClipCache` / ASR archiving while
 * maintaining low playout delay (<500ms 1-hop / <800ms 3-hop).
 */
internal class RnsPttJitterBuffer(
    private val sampleRateHz: Int = RnsPttAdpcmCodec.SAMPLE_RATE_HZ,
    private val frameSamples: Int = RnsPttAdpcmCodec.FRAME_SAMPLES,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onBurstArchived: (VoiceCapturedClip) -> Unit = {},
) {
    private class BurstStream(
        val sessionId: String,
        val speakerName: String,
        val mine: Boolean,
    ) {
        val frames = TreeMap<Long, RnsPttFrame>()
        val pcmArchive = ByteArrayOutputStream(32 * 1024)
        var nextExpectedSeq = 0L
        var endSeq: Long? = null
        var lastActivityMs = 0L
    }

    private val lock = Any()
    private val streams = HashMap<String, BurstStream>()
    private var framesPlayed = 0L
    private var framesConcealed = 0L
    private var lastLatencyMs = 0L
    private var maxLatencyMs = 0L
    private var totalLatencyMs = 0L

    /** Enqueues an inbound or locally captured [RnsPttFrame] and returns decoded PCM ready to play. */
    fun ingest(
        frame: RnsPttFrame,
        mine: Boolean,
        nowMs: Long = clockMs(),
    ): List<ShortArray> {
        val decodedToPlay = mutableListOf<ShortArray>()
        var archivedClip: VoiceCapturedClip? = null

        synchronized(lock) {
            val stream = streams.getOrPut(frame.sessionId) {
                BurstStream(
                    sessionId = frame.sessionId,
                    speakerName = frame.senderName.ifBlank { frame.senderHashHex.take(8) },
                    mine = mine,
                )
            }
            stream.lastActivityMs = nowMs
            if (frame.endOfBurst) {
                stream.endSeq = frame.seq
            }
            if (frame.seq >= stream.nextExpectedSeq) {
                stream.frames[frame.seq] = frame
            }

            // Emit contiguous ordered frames; conceal small gaps if a higher sequence arrived.
            while (true) {
                val nextSeq = stream.nextExpectedSeq
                val queued = stream.frames.remove(nextSeq)
                if (queued != null) {
                    val pcm = decodeFramePcm(queued)
                    if (pcm.isNotEmpty()) {
                        val bytes = RnsPttAdpcmCodec.shortsToLittleEndianBytes(pcm)
                        stream.pcmArchive.write(bytes)
                        if (!mine) {
                            decodedToPlay += pcm
                            val latency = (nowMs - queued.timestampMs).coerceAtLeast(0L)
                            framesPlayed++
                            lastLatencyMs = latency
                            maxLatencyMs = maxOf(maxLatencyMs, latency)
                            totalLatencyMs += latency
                        }
                    }
                    stream.nextExpectedSeq = nextSeq + 1L
                } else {
                    val highest = stream.frames.lastKeyOrNull()
                    if (highest != null && highest - nextSeq >= 2L) {
                        val silence = ShortArray(frameSamples)
                        stream.pcmArchive.write(RnsPttAdpcmCodec.shortsToLittleEndianBytes(silence))
                        if (!mine) {
                            decodedToPlay += silence
                            framesConcealed++
                        }
                        stream.nextExpectedSeq = nextSeq + 1L
                    } else {
                        break
                    }
                }
            }

            val end = stream.endSeq
            if (end != null && stream.nextExpectedSeq > end && stream.frames.isEmpty()) {
                archivedClip = finalizeStreamLocked(frame.sessionId, stream)
            }
        }

        val archived = archivedClip
        if (archived != null) {
            onBurstArchived(archived)
        }
        return decodedToPlay
    }

    /** Flushes and archives a stream explicitly (e.g., on floor release or remote silence timeout). */
    fun finishBurst(sessionId: String): VoiceCapturedClip? {
        val clip = synchronized(lock) {
            val stream = streams[sessionId] ?: return null
            // Drain any remaining buffered frames in sequence order.
            while (stream.frames.isNotEmpty()) {
                val (_, nextFrame) = stream.frames.pollFirstEntry()!!
                val pcm = decodeFramePcm(nextFrame)
                if (pcm.isNotEmpty()) {
                    stream.pcmArchive.write(RnsPttAdpcmCodec.shortsToLittleEndianBytes(pcm))
                }
            }
            finalizeStreamLocked(sessionId, stream)
        }
        if (clip != null) onBurstArchived(clip)
        return clip
    }

    fun stats(): RnsPttPlayoutStats = synchronized(lock) {
        val avg = if (framesPlayed == 0L) 0.0 else totalLatencyMs.toDouble() / framesPlayed.toDouble()
        RnsPttPlayoutStats(
            framesPlayed = framesPlayed,
            framesConcealed = framesConcealed,
            lastLatencyMs = lastLatencyMs,
            maxLatencyMs = maxLatencyMs,
            averageLatencyMs = avg,
        )
    }

    fun clear() = synchronized(lock) {
        streams.clear()
    }

    private fun finalizeStreamLocked(sessionId: String, stream: BurstStream): VoiceCapturedClip? {
        streams.remove(sessionId)
        val raw = stream.pcmArchive.toByteArray()
        // Match PttPcm's minimum quarter-second threshold before creating an archived clip.
        if (raw.size < sampleRateHz * 2 / 4) return null
        return VoiceCapturedClip(
            trackSid = sessionId,
            speaker = stream.speakerName,
            mine = stream.mine,
            pcm = PttPcmSnapshot(raw = raw, rate = sampleRateHz, channels = 1),
        )
    }

    private fun decodeFramePcm(frame: RnsPttFrame): ShortArray {
        if (frame.audioBytes.isEmpty()) return ShortArray(0)
        return when (frame.codec) {
            RnsPttAdpcmCodec.CODEC_ID -> RnsPttAdpcmCodec.decode(frame.audioBytes, frameSamples)
            else -> {
                val count = frame.audioBytes.size / 2
                val out = ShortArray(count)
                val buf = java.nio.ByteBuffer.wrap(frame.audioBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) out[i] = buf.short
                out
            }
        }
    }

    private fun <K, V> TreeMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()
}
