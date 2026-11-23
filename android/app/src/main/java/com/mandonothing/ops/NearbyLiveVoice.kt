package com.mandonothing.ops

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.Executors

private const val CAPTURE_RATE = 16_000
private const val WIRE_RATE = 8_000
private const val FRAME_MS = 80
private const val WIRE_SAMPLES = WIRE_RATE * FRAME_MS / 1_000
private const val CAPTURE_SAMPLES = CAPTURE_RATE * FRAME_MS / 1_000
private const val MAX_BURST_MS = 8_000
private const val MAX_SEQUENCE = MAX_BURST_MS / FRAME_MS
private const val KEEP_PACKETS = 6
private const val LIVE_PACKET_AGE_MS = 1_000L
private const val REMOTE_IDLE_MS = 640L
private const val PLAYOUT_DELAY_MS = 160L
private const val PLAYOUT_DRAIN_MS = 240L
private const val MAX_REMOTE_BURSTS = 8
private const val RETIRED_BURSTS = 24

internal data class NearbyLivePacket(
    val sender: String,
    val burst: String,
    val sequence: Int,
    val at: Long,
    val audio: String,
    val end: Boolean,
    val publicKey: String = "",
    val certificate: String = "",
    val signature: String = "",
) {
    fun wire(): JSONObject = JSONObject().put("sender", sender).put("burst", burst)
        .put("sequence", sequence).put("at", at).put("audio", audio).put("end", end).apply {
            if (publicKey.isNotEmpty()) put("key", publicKey)
            if (certificate.isNotEmpty()) put("certificate", certificate)
            if (signature.isNotEmpty()) put("signature", signature)
        }

    fun signedContent(scope: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeUTF("protocol-x-nearby-live-voice-v1")
            it.writeUTF(scope)
            it.writeUTF(sender)
            it.writeUTF(burst)
            it.writeInt(sequence)
            it.writeLong(at)
            it.writeBoolean(end)
            it.writeUTF(audio)
            it.writeUTF(publicKey)
            it.writeUTF(certificate)
        }
    }.toByteArray()

    fun fresh(now: Long): Boolean = at in (now - LIVE_PACKET_AGE_MS)..(now + 500)

    companion object {
        private val fingerprint = Regex("[0-9a-f]{32}")

        fun decode(value: JSONObject): NearbyLivePacket? = runCatching {
            val sender = value.getString("sender").also { require(fingerprint.matches(it)) }
            val burst = value.getString("burst").also { require(UUID.fromString(it).toString() == it) }
            val sequence = value.getInt("sequence").also { require(it in 0..MAX_SEQUENCE) }
            val end = value.getBoolean("end")
            val audio = value.getString("audio")
            if (end) {
                require(audio.isEmpty())
            } else {
                require(sequence < MAX_SEQUENCE && audio.length <= (WIRE_SAMPLES + 2) / 3 * 4)
                require(Base64.getDecoder().decode(audio).size == WIRE_SAMPLES)
            }
            NearbyLivePacket(sender, burst, sequence, value.getLong("at"), audio, end,
                value.optString("key").also { require(it.length <= 150) },
                value.optString("certificate").also { require(it.length <= 1_500) },
                value.optString("signature").also { require(it.length <= 110) })
        }.getOrNull()
    }
}

internal class NearbyLiveVoice(
    private val localSender: String,
    private val cacheDir: File,
    private val protect: (NearbyLivePacket) -> NearbyLivePacket? = { it },
) : Closeable {
    private data class Capture(val id: String, val record: AudioRecord, val save: (ByteArray) -> Unit) {
        @Volatile var stop = false
        @Volatile var cancel = false
    }

    private val lock = Any()
    private val captures = Executors.newSingleThreadExecutor { Thread(it, "nearby-microphone") }
    private val encoding = Executors.newSingleThreadExecutor { Thread(it, "nearby-voice-note") }
    private val playback = Executors.newSingleThreadScheduledExecutor { Thread(it, "nearby-speaker") }
    private val remote = NearbyLiveJitter()
    private val sent = ArrayDeque<NearbyLivePacket>()
    private var current: Capture? = null
    private var closed = false
    private var pumping = false
    private var outstandingNotes = 0

    private var speaker: AudioTrack? = null
    private var playingBurst: String? = null
    private var speakerBuffer: ShortArray? = null
    private var speakerOffset = 0
    private var speakerDrainsAt = 0L

    val transmitting: Boolean get() = synchronized(lock) { current?.stop == false }
    val receiving: Boolean get() = synchronized(lock) { remote.owner(SystemClock.elapsedRealtime()) != null }

    val active: Boolean get() = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        !closed && (current != null || remote.owner(now) != null || sent.any { it.fresh(System.currentTimeMillis()) })
    }

    @SuppressLint("MissingPermission")
    fun start(onSaved: (ByteArray) -> Unit): Boolean = synchronized(lock) {
        if (closed || current != null || outstandingNotes >= 3 || remote.owner(SystemClock.elapsedRealtime()) != null)
            return false
        val record = createRecord() ?: return false
        val capture = Capture(UUID.randomUUID().toString(), record, onSaved)
        sent.clear()
        current = capture
        schedulePlayback()
        captures.execute { capture(capture) }
        true
    }

    fun stop(cancel: Boolean = false) {
        val capture = synchronized(lock) {
            val value = current ?: return
            value.cancel = value.cancel || cancel
            value.stop = true
            if (cancel) sent.clear()
            value
        }
        runCatching { capture.record.stop() }
    }

    fun outbound(): List<NearbyLivePacket> = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (closed) emptyList() else sent.filter { it.fresh(now) }
    }

    fun receive(packets: List<NearbyLivePacket>) {
        if (packets.isEmpty()) return
        val wallTime = System.currentTimeMillis()
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (closed) return
            val hadNew = packets.take(8).fold(false) { changed, packet ->
                if (packet.sender == localSender || !packet.fresh(wallTime)) changed
                else remote.offer(packet, now) || changed
            }
            if (hadNew) schedulePlayback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        var record: AudioRecord? = null
        return try {
            val opened = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(CAPTURE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, CAPTURE_SAMPLES * 6))
                .build()
            record = opened
            check(opened.state == AudioRecord.STATE_INITIALIZED)
            opened.startRecording()
            check(opened.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            opened
        } catch (_: RuntimeException) {
            runCatching { record?.release() }
            null
        }
    }

    private fun capture(capture: Capture) {
        val samples = ShortArray(CAPTURE_RATE * MAX_BURST_MS / 1_000)
        var total = 0
        var sequence = 0
        var sentVoice = false
        try {
            while (!capture.stop && total < samples.size) {
                val count = capture.record.read(samples, total, CAPTURE_SAMPLES - total % CAPTURE_SAMPLES,
                    AudioRecord.READ_BLOCKING)
                if (count <= 0) break
                total += count
                if (total % CAPTURE_SAMPLES != 0) continue
                val floor = synchronized(lock) {
                    val winner = remote.owner(SystemClock.elapsedRealtime())
                    !closed && !capture.cancel && (winner == null || localSender < winner)
                }
                if (floor) {
                    val audio = encodeNearbyLive(samples, total - CAPTURE_SAMPLES)
                    val packet = NearbyLivePacket(localSender, capture.id, sequence, System.currentTimeMillis(),
                        Base64.getEncoder().encodeToString(audio), false)
                    sentVoice = publish(capture, packet) || sentVoice
                } else synchronized(lock) { sent.clear() }
                sequence++
            }
        } finally {
            runCatching { capture.record.stop() }
            runCatching { capture.record.release() }
            if (sentVoice && !capture.cancel) {
                publish(capture, NearbyLivePacket(localSender, capture.id, sequence, System.currentTimeMillis(), "", true))
            }
            synchronized(lock) {
                if (current === capture) current = null
                if (!closed && !capture.cancel && total >= CAPTURE_RATE / 10) {
                    val count = total
                    outstandingNotes++
                    encoding.execute {
                        try {
                            val note = encodeVoiceNote(cacheDir, samples, count) ?: nearbyFallbackWav(samples, count)
                            val deliver = synchronized(lock) { !closed && !capture.cancel }
                            if (deliver) runCatching { capture.save(note) }
                        } finally {
                            synchronized(lock) { outstandingNotes-- }
                        }
                    }
                }
                if (remote.owner(SystemClock.elapsedRealtime()) != null) schedulePlayback()
            }
        }
    }

    private fun publish(capture: Capture, packet: NearbyLivePacket): Boolean {
        val authenticated = runCatching { protect(packet) }.getOrNull() ?: return false
        return synchronized(lock) {
            if (closed || capture.cancel || current !== capture) return false
            sent.addLast(authenticated)
            while (sent.size > KEEP_PACKETS) sent.removeFirst()
            true
        }
    }

    private fun schedulePlayback() {
        if (!closed && !pumping) {
            pumping = true
            playback.execute(::pump)
        }
    }

    private fun pump() {
        val now = SystemClock.elapsedRealtime()
        val offer = synchronized(lock) {
            if (closed || current != null) null else remote.playout(now)
        }
        try {
            if (offer == null) {
                if (transmitting || now >= speakerDrainsAt) releaseSpeaker()
            } else {
                if (playingBurst != offer.burst) {
                    releaseSpeaker()
                    playingBurst = offer.burst
                }
                if (offer.samples != null) {
                    speakerBuffer = offer.samples
                    speakerOffset = 0
                }
                val data = speakerBuffer
                if (data != null) {
                    val track = speaker ?: createTrack()?.also { speaker = it }
                    if (track == null) {
                        speakerBuffer = null
                    } else {
                        val count = track.write(data, speakerOffset, data.size - speakerOffset,
                            AudioTrack.WRITE_NON_BLOCKING)
                        if (count < 0) releaseSpeaker()
                        else {
                            if (count > 0) speakerDrainsAt = SystemClock.elapsedRealtime() + PLAYOUT_DRAIN_MS
                            speakerOffset += count
                            if (speakerOffset >= data.size) speakerBuffer = null
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            releaseSpeaker()
        }
        val keepPlaying = synchronized(lock) {
            val time = SystemClock.elapsedRealtime()
            if (!closed && current == null && (remote.owner(time) != null || time < speakerDrainsAt)) {
                playback.schedule(::pump, 20, java.util.concurrent.TimeUnit.MILLISECONDS)
                true
            } else {
                pumping = false
                false
            }
        }
        if (!keepPlaying) releaseSpeaker()
    }

    private fun releaseSpeaker() {
        runCatching { speaker?.release() }
        speaker = null
        playingBurst = null
        speakerBuffer = null
        speakerOffset = 0
        speakerDrainsAt = 0
    }

    override fun close() {
        val capture = synchronized(lock) {
            if (closed) return
            closed = true
            sent.clear()
            val value = current
            value?.cancel = true
            value?.stop = true
            value
        }
        runCatching { capture?.record?.stop() }
        captures.shutdown()
        encoding.shutdown()
        playback.execute(::releaseSpeaker)
        playback.shutdown()
    }
}

internal data class NearbyPlayout(val burst: String, val samples: ShortArray?)

internal class NearbyLiveJitter {
    private data class Lane(
        val sender: String,
        val burst: String,
        val started: Long,
        var freshAt: Long,
        var firstAt: Long,
        val frames: TreeMap<Int, ShortArray> = TreeMap(),
        var highest: Int = -1,
        var next: Int? = null,
        var due: Long = 0,
        var end: Int? = null,
    )

    private val lanes = linkedMapOf<String, Lane>()
    private val retired = linkedMapOf<String, Long>()
    private var playing: String? = null

    fun offer(packet: NearbyLivePacket, now: Long): Boolean {
        prune(now)
        val key = packet.sender + ":" + packet.burst
        if (key in retired) return false
        val lane = lanes[key] ?: run {
            if (lanes.size >= MAX_REMOTE_BURSTS) return false
            Lane(packet.sender, packet.burst, now, now, now).also { lanes[key] = it }
        }
        if (packet.end) {
            if (lane.end != null || packet.sequence <= lane.highest) return false
            lane.end = packet.sequence
            lane.freshAt = now
            return true
        }
        if (packet.sequence < (lane.next ?: 0) || packet.sequence >= (lane.end ?: Int.MAX_VALUE) ||
            packet.sequence <= lane.highest - KEEP_PACKETS || packet.sequence in lane.frames) return false
        val bytes = runCatching { Base64.getDecoder().decode(packet.audio) }.getOrNull() ?: return false
        if (bytes.size != WIRE_SAMPLES) return false
        lane.frames[packet.sequence] = decodeNearbyLive(bytes)
        lane.highest = maxOf(lane.highest, packet.sequence)
        while (lane.frames.size > KEEP_PACKETS) lane.frames.pollFirstEntry()
        lane.freshAt = now
        return true
    }

    fun owner(now: Long): String? {
        prune(now)
        return select()?.sender
    }

    fun playout(now: Long): NearbyPlayout? {
        prune(now)
        val lane = select() ?: return null
        if (playing != lane.burst) {
            if (playing != null) {
                while (lane.frames.size > 2) lane.frames.pollFirstEntry()
                lane.firstAt = now - PLAYOUT_DELAY_MS
            }
            playing = lane.burst
        }
        if (lane.next == null) {
            if (now - lane.firstAt < PLAYOUT_DELAY_MS || lane.frames.isEmpty()) return NearbyPlayout(lane.burst, null)
            lane.next = lane.frames.firstKey()
            lane.due = now
        }
        if (now < lane.due) return NearbyPlayout(lane.burst, null)
        var next = checkNotNull(lane.next)
        if (now - lane.due >= REMOTE_IDLE_MS / 2 && lane.frames.isNotEmpty()) {
            next = maxOf(next, lane.frames.firstKey(), lane.highest - 1)
            lane.frames.headMap(next).clear()
            lane.due = now
        }
        if (next >= (lane.end ?: Int.MAX_VALUE)) {
            retire(lane.sender + ":" + lane.burst, now)
            return null
        }
        val frame = lane.frames.remove(next)
        if (frame == null && lane.frames.isEmpty()) return NearbyPlayout(lane.burst, null)
        lane.next = next + 1
        lane.due += FRAME_MS
        return NearbyPlayout(lane.burst, frame ?: ShortArray(WIRE_SAMPLES))
    }

    private fun select(): Lane? = lanes.values.filter { it.highest >= 0 &&
        (it.end == null || it.frames.isNotEmpty()) }.minWithOrNull(compareBy<Lane> { it.sender }.thenBy { it.started })

    private fun prune(now: Long) {
        retired.entries.removeAll { now - it.value >= LIVE_PACKET_AGE_MS }
        val stale = lanes.filterValues { now - it.freshAt >= REMOTE_IDLE_MS ||
            (it.end != null && it.frames.isEmpty() && (it.highest >= 0 || now - it.started >= PLAYOUT_DELAY_MS)) }.keys
        stale.forEach { retire(it, now) }
    }

    private fun retire(key: String, now: Long) {
        lanes.remove(key)
        retired[key] = now
        while (retired.size > RETIRED_BURSTS) retired.remove(retired.keys.first())
    }
}

/** G.711 μ-law narrows 8 kHz mono PCM to one byte per sample. */
internal fun encodeNearbyLive(samples: ShortArray, start: Int): ByteArray =
    ByteArray(WIRE_SAMPLES) { index ->
        val i = start + index * 2
        val value = (samples[i].toInt() + samples[i + 1].toInt()) / 2
        val sign = if (value < 0) 0x80 else 0
        val magnitude = minOf(kotlin.math.abs(value), 32635) + 132
        val exponent = 24 - Integer.numberOfLeadingZeros(magnitude)
        val mantissa = (magnitude shr (exponent + 3)) and 0x0f
        (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

internal fun decodeNearbyLive(samples: ByteArray): ShortArray = ShortArray(samples.size) { index ->
    val value = samples[index].toInt().inv() and 0xff
    val biased = (((value and 0x0f) shl 3) + 132) shl ((value shr 4) and 7)
    (if (value and 0x80 != 0) 132 - biased else biased - 132).toShort()
}

private fun createTrack(): AudioTrack? {
    val minimum = AudioTrack.getMinBufferSize(WIRE_RATE, AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT)
    if (minimum <= 0) return null
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(WIRE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minimum, WIRE_SAMPLES * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    return try {
        check(track.state == AudioTrack.STATE_INITIALIZED)
        track.setStartThresholdInFramesIfSupported()
        track.play()
        track
    } catch (_: RuntimeException) {
        track.release()
        null
    }
}

private fun AudioTrack.setStartThresholdInFramesIfSupported() {
    if (android.os.Build.VERSION.SDK_INT >= 31) setStartThresholdInFrames(WIRE_SAMPLES)
}

private fun encodeVoiceNote(cacheDir: File, samples: ShortArray, count: Int): ByteArray? {
    val file = runCatching { File.createTempFile("nearby-voice-", ".m4a", cacheDir) }.getOrNull() ?: return null
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var muxing = false
    return try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, CAPTURE_RATE, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = output
        val info = MediaCodec.BufferInfo()
        val limit = SystemClock.elapsedRealtime() + 3_000
        var offset = 0
        var submitted = false
        var complete = false
        var track = -1
        while (!complete && SystemClock.elapsedRealtime() < limit) {
            if (!submitted) {
                val index = encoder.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val buffer = checkNotNull(encoder.getInputBuffer(index))
                    buffer.clear()
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val size = minOf(count - offset, buffer.remaining() / 2, 1_024)
                    repeat(size) { buffer.putShort(samples[offset + it]) }
                    submitted = offset + size == count
                    encoder.queueInputBuffer(index, 0, size * 2, offset * 1_000_000L / CAPTURE_RATE,
                        if (submitted) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    offset += size
                }
            }
            val index = encoder.dequeueOutputBuffer(info, 5_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = output.addTrack(encoder.outputFormat)
                output.start()
                muxing = true
            } else if (index >= 0) {
                if (track >= 0 && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    output.writeSampleData(track, buffer, info)
                }
                complete = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
            }
        }
        if (muxing) {
            muxing = false
            output.stop()
        }
        if (complete && file.length() in 1L..NEARBY_AUDIO_LIMIT.toLong()) file.readBytes() else null
    } catch (_: Exception) {
        null
    } finally {
        if (muxing) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { codec?.release() }
        file.delete()
    }
}

/** Standard 8-bit mono WAV as a fallback when a platform AAC encoder is unavailable. */
internal fun nearbyFallbackWav(samples: ShortArray, count: Int): ByteArray {
    val size = minOf(count / 2, NEARBY_AUDIO_LIMIT - 44) and -2
    val output = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
    output.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(WIRE_RATE).putInt(WIRE_RATE)
        .putShort(1).putShort(8).put("data".toByteArray()).putInt(size)
    repeat(size) { index ->
        val value = (samples[index * 2].toInt() + samples[index * 2 + 1].toInt()) / 2
        output.put(((value shr 8) + 128).toByte())
    }
    return output.array()
}
