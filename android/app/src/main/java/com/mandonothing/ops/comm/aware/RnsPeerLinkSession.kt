package com.mandonothing.ops.comm.aware

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared frame transport session for stream (Wi-Fi Aware TCP, BLE L2CAP) and datagram
 * (Wi-Fi Aware IPv6 UDP) peer links. Handles RNS frame ingress/egress, RTT PING/PONG probing,
 * EWMA bandwidth estimation, and idempotent teardown without duplicating radio loop code.
 */
internal class RnsPeerLinkSession(
    val peerId: String,
    private val radio: RnsRadioKind,
    private val transport: Transport,
    private val maxFrameBytes: Int,
    private val probeIntervalMs: Long,
    private val capacity: PeerCapacityManager,
    private val ioExecutor: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val clockMs: () -> Long,
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val onInboundFrame: (peerId: String, payload: ByteArray) -> Unit,
    private val onMetricsUpdated: (RnsPeerQualityMetrics) -> Unit,
    private val onClosed: (peerId: String, failed: Boolean) -> Unit,
) : Closeable {

    sealed interface Transport : Closeable {
        data class Stream(
            val socket: Closeable,
            val input: InputStream,
            val output: OutputStream,
        ) : Transport {
            override fun close() {
                runCatching { socket.close() }
            }
        }

        data class Datagram(
            val socket: DatagramSocket,
            val remoteAddress: InetAddress,
            val remotePort: Int,
        ) : Transport {
            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var probeFuture: ScheduledFuture<*>? = null

    fun start() {
        if (closed.get()) return
        if (probeIntervalMs > 0L) {
            probeFuture = scheduler.scheduleAtFixedRate(
                { runCatching { sendPing() } },
                probeIntervalMs.coerceAtMost(250L),
                probeIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
        ioExecutor.execute { readLoop() }
    }

    fun sendFrame(payload: ByteArray): Boolean {
        if (closed.get() || payload.isEmpty() || payload.size > maxFrameBytes) return false
        val startNs = nanoClock()
        return runCatching {
            synchronized(writeLock) {
                if (closed.get()) return false
                when (transport) {
                    is Transport.Stream -> RnsFrameFramer.writeData(transport.output, payload, maxFrameBytes)
                    is Transport.Datagram -> {
                        val wire = RnsFrameFramer.encodeDatagram(RnsFrameFramer.Frame.Data(payload), maxFrameBytes)
                        transport.socket.send(
                            DatagramPacket(wire, wire.size, InetSocketAddress(transport.remoteAddress, transport.remotePort)),
                        )
                    }
                }
            }
            val elapsedNs = (nanoClock() - startNs).coerceAtLeast(1_000L)
            val instBps = ((payload.size * 8L) * 1_000_000_000L) / elapsedNs
            val cappedBps = instBps.coerceIn(8_000L, radio.defaultBitrateBps * 2L)
            val updated = capacity.recordMetrics(
                peerId = peerId,
                measuredBandwidthBps = cappedBps,
                bytesSentDelta = payload.size.toLong(),
                framesSentDelta = 1L,
                nowMs = clockMs(),
            )
            if (updated != null) onMetricsUpdated(updated)
            true
        }.getOrElse {
            teardown(failed = true)
            false
        }
    }

    fun sendPing() {
        if (closed.get()) return
        val sentAt = nanoClock()
        runCatching {
            synchronized(writeLock) {
                if (closed.get()) return
                when (transport) {
                    is Transport.Stream -> RnsFrameFramer.writePing(transport.output, sentAt)
                    is Transport.Datagram -> {
                        val wire = RnsFrameFramer.encodeDatagram(RnsFrameFramer.Frame.Ping(sentAt), maxFrameBytes)
                        transport.socket.send(
                            DatagramPacket(wire, wire.size, InetSocketAddress(transport.remoteAddress, transport.remotePort)),
                        )
                    }
                }
            }
        }.onFailure {
            teardown(failed = true)
        }
    }

    private fun readLoop() {
        var failed = false
        try {
            val datagramBuf = if (transport is Transport.Datagram) ByteArray(maxFrameBytes + 16) else null
            while (!closed.get() && !Thread.currentThread().isInterrupted) {
                val frame = when (transport) {
                    is Transport.Stream -> RnsFrameFramer.readFrame(transport.input, maxFrameBytes)
                    is Transport.Datagram -> {
                        val packet = DatagramPacket(datagramBuf!!, datagramBuf.size)
                        transport.socket.receive(packet)
                        RnsFrameFramer.decodeDatagram(packet.data, packet.length, maxFrameBytes)
                    }
                }
                handleFrame(frame)
            }
        } catch (_: Exception) {
            failed = !closed.get()
        } finally {
            teardown(failed = failed)
        }
    }

    private fun handleFrame(frame: RnsFrameFramer.Frame) {
        val nowMs = clockMs()
        when (frame) {
            is RnsFrameFramer.Frame.Data -> {
                val updated = capacity.recordMetrics(
                    peerId = peerId,
                    bytesReceivedDelta = frame.payload.size.toLong(),
                    framesReceivedDelta = 1L,
                    nowMs = nowMs,
                )
                if (updated != null) onMetricsUpdated(updated)
                onInboundFrame(peerId, frame.payload)
            }
            is RnsFrameFramer.Frame.Ping -> {
                synchronized(writeLock) {
                    if (!closed.get()) {
                        when (transport) {
                            is Transport.Stream -> RnsFrameFramer.writePong(transport.output, frame.sentAtNanos)
                            is Transport.Datagram -> {
                                val wire = RnsFrameFramer.encodeDatagram(
                                    RnsFrameFramer.Frame.Pong(frame.sentAtNanos),
                                    maxFrameBytes,
                                )
                                transport.socket.send(
                                    DatagramPacket(
                                        wire,
                                        wire.size,
                                        InetSocketAddress(transport.remoteAddress, transport.remotePort),
                                    ),
                                )
                            }
                        }
                    }
                }
                capacity.recordMetrics(peerId = peerId, nowMs = nowMs)?.let(onMetricsUpdated)
            }
            is RnsFrameFramer.Frame.Pong -> {
                val rttMs = ((nanoClock() - frame.echoedNanos).coerceAtLeast(1_000_000L)) / 1_000_000L
                val updated = capacity.recordMetrics(
                    peerId = peerId,
                    rttMs = rttMs,
                    nowMs = nowMs,
                )
                if (updated != null) onMetricsUpdated(updated)
            }
        }
    }

    private fun teardown(failed: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        probeFuture?.cancel(false)
        probeFuture = null
        runCatching { transport.close() }
        onClosed(peerId, failed)
    }

    override fun close() {
        teardown(failed = false)
    }
}
