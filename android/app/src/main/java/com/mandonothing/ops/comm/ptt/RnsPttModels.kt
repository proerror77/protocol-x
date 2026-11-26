package com.mandonothing.ops.comm.ptt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Floor arbitration actions aligned with `@rns-poc` UniFFI `PttFloorAction` (#58). */
internal enum class RnsPttFloorAction {
    REQUEST,
    GRANT,
    BUSY,
    PREEMPT,
    RELEASE,
}

/** Compact multi-hop PTT audio frame packet aligned with UniFFI `PttFramePacket` (#58). */
internal data class RnsPttFrame(
    val channelId: String,
    val sessionId: String,
    val senderHashHex: String,
    val senderName: String = "",
    val seq: Long,
    val timestampMs: Long,
    val codec: String = RnsPttAdpcmCodec.CODEC_ID,
    val durationMs: Int = RnsPttAdpcmCodec.FRAME_DURATION_MS,
    val audioBytes: ByteArray,
    val hopCount: Int = 1,
    val endOfBurst: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RnsPttFrame) return false
        return channelId == other.channelId &&
            sessionId == other.sessionId &&
            senderHashHex == other.senderHashHex &&
            seq == other.seq &&
            timestampMs == other.timestampMs &&
            codec == other.codec &&
            durationMs == other.durationMs &&
            audioBytes.contentEquals(other.audioBytes) &&
            hopCount == other.hopCount &&
            endOfBurst == other.endOfBurst
    }

    override fun hashCode(): Int {
        var result = channelId.hashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + senderHashHex.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + audioBytes.contentHashCode()
        return result
    }
}

/** Distributed floor-control message aligned with UniFFI `PttFloorControlMessage` (#58). */
internal data class RnsPttFloorControl(
    val channelId: String,
    val sessionId: String,
    val actorHashHex: String,
    val actorName: String = "",
    val action: RnsPttFloorAction,
    val priority: Int = 1,
    val timestampMs: Long,
    val holderHashHex: String? = null,
    val detail: String = "",
)

/**
 * Bridge between RNS-10 Android PTT transport and Rust `RnsNode` (`sendPttFrame`,
 * `requestPttFloor`, `releasePttFloor`) or a simulated multi-hop test fabric.
 */
internal interface RnsPttTransportBridge {
    val localHashHex: String
    fun peerDestinations(channelId: String): List<String>
    fun sendPttFrame(destinationHashHex: String, frame: RnsPttFrame): Boolean
    fun requestPttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
        priority: Int,
    ): RnsPttFloorControl

    fun releasePttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
    ): RnsPttFloorControl
}

/** Audio capture, playback, and output routing abstraction for [RnsPttSessionTransport]. */
internal interface RnsPttAudioIo : Closeable {
    fun startCapture(sampleRateHz: Int, frameSamples: Int, onPcmFrame: (ShortArray) -> Unit): Boolean
    fun stopCapture()
    fun startPlayback(sampleRateHz: Int)
    fun playPcm(samples: ShortArray)
    fun stopPlayback()
    fun setReceiveMuted(muted: Boolean)
    fun audioOutputs(): List<AudioDevice>
    fun currentOutput(): AudioDevice?
    fun selectOutput(device: AudioDevice)
}

/**
 * 4-bit IMA ADPCM codec for 16 kHz mono PCM PTT bursts.
 * Compresses a 40 ms 16 kHz frame (640 samples = 1,280 B PCM16) into a 324-byte wire payload
 * (4-byte predictor/step header + 320 B nibbles) for sub-500ms 1-hop / sub-800ms 3-hop delivery.
 */
internal object RnsPttAdpcmCodec {
    const val CODEC_ID = "adpcm-ima-16k"
    const val SAMPLE_RATE_HZ = 16_000
    const val FRAME_DURATION_MS = 40
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000 // 640 samples

    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    fun encode(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        var predictor = samples[0].toInt()
        val initDelta = if (samples.size > 1) kotlin.math.abs(samples[1].toInt() - predictor) else 0
        var stepIndex = STEP_TABLE.indexOfFirst { it >= initDelta }.let { idx ->
            if (idx < 0) STEP_TABLE.lastIndex else idx
        }
        val outCount = (samples.size + 1) / 2
        val buf = ByteBuffer.allocate(4 + outCount).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(predictor.toShort())
        buf.put(stepIndex.toByte())
        buf.put(0.toByte())

        var packed = 0
        for (i in samples.indices) {
            val step = STEP_TABLE[stepIndex]
            var diff = samples[i].toInt() - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var vpdiff = step shr 3
            if (diff >= step) {
                code = code or 4
                diff -= step
                vpdiff += step
            }
            val stepHalf = step shr 1
            if (diff >= stepHalf) {
                code = code or 2
                diff -= stepHalf
                vpdiff += stepHalf
            }
            val stepQuarter = step shr 2
            if (diff >= stepQuarter) {
                code = code or 1
                vpdiff += stepQuarter
            }
            predictor = if ((code and 8) != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32_768, 32_767)
            stepIndex = (stepIndex + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.lastIndex)

            if ((i and 1) == 0) {
                packed = code and 0x0F
            } else {
                packed = packed or ((code and 0x0F) shl 4)
                buf.put(packed.toByte())
            }
        }
        if ((samples.size and 1) == 1) {
            buf.put(packed.toByte())
        }
        return buf.array()
    }

    fun decode(encoded: ByteArray, expectedSamples: Int = FRAME_SAMPLES): ShortArray {
        if (encoded.size < 4) return ShortArray(0)
        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        var predictor = buf.short.toInt()
        var stepIndex = (buf.get().toInt() and 0xFF).coerceIn(0, STEP_TABLE.lastIndex)
        buf.get() // reserved

        val maxSamples = ((encoded.size - 4) * 2).coerceAtMost(expectedSamples.coerceAtLeast(1))
        val out = ShortArray(maxSamples)
        for (i in 0 until maxSamples) {
            val byteVal = encoded[4 + (i / 2)].toInt() and 0xFF
            val code = if ((i and 1) == 0) byteVal and 0x0F else (byteVal ushr 4) and 0x0F
            val step = STEP_TABLE[stepIndex]
            var vpdiff = step shr 3
            if ((code and 4) != 0) vpdiff += step
            if ((code and 2) != 0) vpdiff += step shr 1
            if ((code and 1) != 0) vpdiff += step shr 2
            predictor = if ((code and 8) != 0) predictor - vpdiff else predictor + vpdiff
            predictor = predictor.coerceIn(-32_768, 32_767)
            stepIndex = (stepIndex + INDEX_TABLE[code]).coerceIn(0, STEP_TABLE.lastIndex)
            out[i] = predictor.toShort()
        }
        return out
    }

    fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putShort(it) }
        return buf.array()
    }
}

/** Production Android audio hardware implementation using `AudioRecord`, `AudioTrack`, and `AudioSwitchHandler`. */
@SuppressLint("MissingPermission")
internal class AndroidRnsPttAudioIo(
    context: Context,
    private val onOutputChanged: (String) -> Unit = {},
) : RnsPttAudioIo {
    private val appContext = context.applicationContext
    private val audioHandler = AudioSwitchHandler(appContext).apply {
        registerAudioDeviceChangeListener { _, selected ->
            onOutputChanged(selected?.name ?: "系统默认")
        }
    }

    @Volatile private var capturing = false
    @Volatile private var receiveMuted = false
    private var captureThread: Thread? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null

    override fun startCapture(
        sampleRateHz: Int,
        frameSamples: Int,
        onPcmFrame: (ShortArray) -> Unit,
    ): Boolean {
        stopCapture()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frameSamples * 4)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }
        recorder = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread(
            {
                val buf = ShortArray(frameSamples)
                while (capturing && !Thread.currentThread().isInterrupted) {
                    val read = rec.read(buf, 0, frameSamples)
                    if (read == frameSamples) {
                        onPcmFrame(buf.copyOf())
                    } else if (read < 0) {
                        break
                    }
                }
            },
            "rns-ptt-capture",
        ).apply { start() }
        return true
    }

    override fun stopCapture() {
        capturing = false
        captureThread?.interrupt()
        captureThread = null
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
    }

    override fun startPlayback(sampleRateHz: Int) {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRateHz / 5)
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()
        built.setVolume(if (receiveMuted) 0f else 1f)
        built.play()
        track = built
    }

    override fun playPcm(samples: ShortArray) {
        if (receiveMuted || samples.isEmpty()) return
        val active = track ?: return
        active.write(samples, 0, samples.size)
    }

    override fun stopPlayback() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    override fun setReceiveMuted(muted: Boolean) {
        receiveMuted = muted
        track?.setVolume(if (muted) 0f else 1f)
    }

    override fun audioOutputs(): List<AudioDevice> = audioHandler.availableAudioDevices

    override fun currentOutput(): AudioDevice? = audioHandler.selectedAudioDevice

    override fun selectOutput(device: AudioDevice) {
        audioHandler.selectDevice(device)
    }

    override fun close() {
        stopCapture()
        stopPlayback()
        runCatching { audioHandler.stop() }
    }
}
