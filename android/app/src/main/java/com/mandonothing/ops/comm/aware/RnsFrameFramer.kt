package com.mandonothing.ops.comm.aware

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Lightweight length-prefixed framing for RNS packets and link RTT probes over Wi-Fi Aware TCP,
 * Wi-Fi Aware UDP datagrams, and BLE L2CAP streams.
 *
 * Wire layout (stream): `[1-byte type][4-byte big-endian length][payload]`.
 * Wire layout (datagram): `[1-byte type][payload]`.
 */
internal object RnsFrameFramer {
    const val TYPE_RNS_DATA: Byte = 0x01
    const val TYPE_RTT_PING: Byte = 0x02
    const val TYPE_RTT_PONG: Byte = 0x03

    sealed interface Frame {
        data class Data(val payload: ByteArray) : Frame
        data class Ping(val sentAtNanos: Long) : Frame
        data class Pong(val echoedNanos: Long) : Frame
    }

    fun writeData(output: OutputStream, payload: ByteArray, maxFrameBytes: Int = 65_536) {
        require(payload.isNotEmpty() && payload.size <= maxFrameBytes) {
            "invalid RNS frame size: ${payload.size} (max=$maxFrameBytes)"
        }
        val out = DataOutputStream(output)
        out.writeByte(TYPE_RNS_DATA.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun writePing(output: OutputStream, sentAtNanos: Long) {
        val out = DataOutputStream(output)
        out.writeByte(TYPE_RTT_PING.toInt())
        out.writeInt(8)
        out.writeLong(sentAtNanos)
        out.flush()
    }

    fun writePong(output: OutputStream, echoedNanos: Long) {
        val out = DataOutputStream(output)
        out.writeByte(TYPE_RTT_PONG.toInt())
        out.writeInt(8)
        out.writeLong(echoedNanos)
        out.flush()
    }

    fun readFrame(input: InputStream, maxFrameBytes: Int = 65_536): Frame {
        val reader = DataInputStream(input)
        val type = reader.readByte()
        val length = reader.readInt()
        if (length <= 0 || length > maxFrameBytes) {
            throw IllegalArgumentException("invalid frame length $length (max=$maxFrameBytes)")
        }
        val payload = ByteArray(length)
        reader.readFully(payload)
        return decode(type, payload)
    }

    fun encodeDatagram(frame: Frame, maxFrameBytes: Int = 65_536): ByteArray = when (frame) {
        is Frame.Data -> {
            require(frame.payload.isNotEmpty() && frame.payload.size <= maxFrameBytes) {
                "invalid RNS datagram size: ${frame.payload.size}"
            }
            ByteArray(1 + frame.payload.size).apply {
                this[0] = TYPE_RNS_DATA
                System.arraycopy(frame.payload, 0, this, 1, frame.payload.size)
            }
        }
        is Frame.Ping -> ByteBuffer.allocate(9).put(TYPE_RTT_PING).putLong(frame.sentAtNanos).array()
        is Frame.Pong -> ByteBuffer.allocate(9).put(TYPE_RTT_PONG).putLong(frame.echoedNanos).array()
    }

    fun decodeDatagram(bytes: ByteArray, length: Int = bytes.size, maxFrameBytes: Int = 65_536): Frame {
        if (length < 2 || length - 1 > maxFrameBytes) {
            throw EOFException("invalid datagram length $length")
        }
        return decode(bytes[0], bytes.copyOfRange(1, length))
    }

    private fun decode(type: Byte, payload: ByteArray): Frame = when (type) {
        TYPE_RNS_DATA -> Frame.Data(payload)
        TYPE_RTT_PING -> {
            require(payload.size == 8) { "invalid ping payload size ${payload.size}" }
            Frame.Ping(ByteBuffer.wrap(payload).long)
        }
        TYPE_RTT_PONG -> {
            require(payload.size == 8) { "invalid pong payload size ${payload.size}" }
            Frame.Pong(ByteBuffer.wrap(payload).long)
        }
        else -> throw IllegalArgumentException("unknown frame type $type")
    }
}
