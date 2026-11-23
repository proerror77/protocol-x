package com.mandonothing.ops

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

internal object PttWav {
    const val RATE = 16000
    const val BYTES_PER_FRAME = 2

    fun length(stream: DataInputStream): Int {
        // Protocol X records mono, 16-bit PCM WAVs with a fixed 44-byte header.
        val header = ByteArray(44)
        stream.readFully(header)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun fourCc(offset: Int) = header.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
        require(fourCc(0) == "RIFF" && fourCc(8) == "WAVE" && fourCc(12) == "fmt " && fourCc(36) == "data")
        require(fields.getInt(16) == 16 && fields.getShort(20).toInt() == 1 &&
                 fields.getShort(22).toInt() == 1 && fields.getInt(24) == RATE && fields.getShort(34).toInt() == 16)
        return fields.getInt(40).also { require(it in 2..RATE * BYTES_PER_FRAME * 90 && it % BYTES_PER_FRAME == 0) }
    }
}

internal class VoicePlayback {
    @Volatile private var track: AudioTrack? = null

    suspend fun play(wav: InputStream, onPlaying: suspend () -> Unit) {
        val stream = DataInputStream(wav)
        var remaining = PttWav.length(stream)
        val frames = remaining / PttWav.BYTES_PER_FRAME
        val buffer = ByteArray(3200) // 100 ms of mono PCM
        val first = minOf(remaining, buffer.size)
        stream.readFully(buffer, 0, first)
        coroutineContext.ensureActive()
        val format = AudioFormat.Builder().setSampleRate(PttWav.RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(buffer.size * 3, AudioTrack.getMinBufferSize(PttWav.RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
        track = output
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                output.startThresholdInFrames = first / PttWav.BYTES_PER_FRAME
            }
            coroutineContext.ensureActive()
            output.play()
            var count = first
            var reported = false
            while (remaining > 0) {
                var offset = 0
                while (offset < count) {
                    coroutineContext.ensureActive()
                    val written = output.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0)
                    offset += written
                }
                if (!reported) {
                    onPlaying()
                    reported = true
                }
                remaining -= count
                if (remaining > 0) {
                    count = minOf(buffer.size, remaining)
                    stream.readFully(buffer, 0, count)
                }
            }
            while (output.playbackHeadPosition < frames) delay(30)
        } finally {
            track = null
            runCatching { output.stop() }
            output.release()
        }
    }

    fun pause() {
        val current = track ?: return
        runCatching {
            current.pause()
            current.flush()
        }
    }
}
