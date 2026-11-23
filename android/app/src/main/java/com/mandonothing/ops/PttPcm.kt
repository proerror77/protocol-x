package com.mandonothing.ops

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copies the existing WebRTC PCM. Callbacks never change the live track's buffer. */
internal class PttPcm {
    private val raw = ByteArrayOutputStream(32 * 1024)
    private var rate = 0
    private var channels = 0
    private var ended = false
    private var invalid = false

    @Synchronized
    fun append(buffer: ByteBuffer, bits: Int, sourceRate: Int, sourceChannels: Int, frames: Int) {
        if (ended || invalid) return
        if (bits != 16 || sourceRate !in 8000..48000 || sourceChannels !in 1..2 || frames !in 1..48000) return
        if (rate != 0 && (rate != sourceRate || channels != sourceChannels)) {
            invalid = true
            return
        }
        rate = sourceRate
        channels = sourceChannels
        val size = frames * channels * 2
        if (raw.size() + size > rate * channels * 2 * 90) {
            invalid = true
            return
        }
        val input = buffer.duplicate()
        if (input.remaining() < size) return
        val block = ByteArray(size)
        input.get(block)
        raw.write(block)
    }

    @Synchronized
    fun finish(): PttPcmSnapshot? {
        if (ended) return null
        ended = true
        if (invalid || rate == 0 || raw.size() < rate * channels * 2 / 4) return null
        return PttPcmSnapshot(raw.toByteArray(), rate, channels)
    }
}

internal class PttPcmSnapshot(private val raw: ByteArray, private val rate: Int, private val channels: Int) {
    fun mono16(): ShortArray {
        val source = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = source.remaining() / channels
        val destination = ShortArray((frames.toLong() * 16_000 / rate).toInt())
        for (index in destination.indices) {
            val start = (index.toLong() * rate / 16_000).toInt()
            val limit = maxOf(start + 1, ((index + 1L) * rate / 16_000).toInt()).coerceAtMost(frames)
            var sum = 0
            for (frame in start until limit)
                for (channel in 0 until channels) sum += source.get(frame * channels + channel).toInt()
            destination[index] = (sum / ((limit - start) * channels)).toShort()
        }
        return destination
    }
}

/** Matches the archive gate on 20 ms frames of mono 16 kHz signed PCM. */
internal fun hasPttSignal(samples: ShortArray): Boolean {
    var quiet = 0
    var strong = 0
    for (start in 0..(samples.size - 320) step 320) {
        var peak = 0
        var energy = 0L
        for (index in start until start + 320) {
            val sample = samples[index].toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
            energy += sample.toLong() * sample
        }
        if (peak >= 480 && energy >= 220L * 220 * 320) quiet++
        if (peak >= 1200 && energy >= 600L * 600 * 320) strong++
        if (quiet >= 16 || strong >= 3) return true
    }
    return false
}

internal fun wave16(samples: ShortArray): ByteArray {
    val size = samples.size * 2
    val out = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)).putInt(size)
    }
    samples.forEach { out.putShort(it) }
    return out.array()
}
