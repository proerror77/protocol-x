package com.mandonothing.ops.comm.aware

import com.mandonothing.ops.comm.rns.RnsCoreEngine
import com.mandonothing.ops.comm.rns.RnsNativeLoader
import uniffi.protocolx_rns.CustomInterfaceKind
import uniffi.protocolx_rns.CustomLinkMetrics
import uniffi.protocolx_rns.RnsCustomInterfaceBridge
import uniffi.protocolx_rns.RnsNode
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Backend sink for [RnsAwareBridgeConnector] bridging custom-interface calls into [RnsNode]
 * (via UniFFI) or a test/simulated RNS runtime.
 */
internal interface RnsCustomBridgeSink {
    fun attachInterface(
        interfaceTag: String,
        kind: RnsCustomInterfaceKind,
        hwMtu: Int,
        bitrateBps: Long,
        outboundHandler: RnsCustomInterfaceOutboundHandler,
    )

    fun detachInterface(interfaceTag: String): Boolean
    fun pushInboundFrame(interfaceTag: String, frame: ByteArray)
    fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics)
    fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics>
}

/**
 * Production [RnsCustomBridgeSink] wired to `uniffi.protocolx_rns.RnsNode` custom interface APIs.
 * All native calls are safely guarded behind [RnsNativeLoader.ensureLoaded] and null node checks.
 */
internal class UniFfiRnsNodeBridgeSink(
    private val nodeProvider: () -> RnsNode?,
) : RnsCustomBridgeSink {

    override fun attachInterface(
        interfaceTag: String,
        kind: RnsCustomInterfaceKind,
        hwMtu: Int,
        bitrateBps: Long,
        outboundHandler: RnsCustomInterfaceOutboundHandler,
    ) {
        if (!RnsNativeLoader.ensureLoaded()) return
        val node = nodeProvider() ?: return
        val uniKind = when (kind) {
            RnsCustomInterfaceKind.WIFI_AWARE -> CustomInterfaceKind.WIFI_AWARE
            RnsCustomInterfaceKind.BLE_L2CAP -> CustomInterfaceKind.BLE_L2CAP
            RnsCustomInterfaceKind.CUSTOM -> CustomInterfaceKind.CUSTOM
        }
        node.attachCustomInterface(
            interfaceTag = interfaceTag,
            kind = uniKind,
            hwMtu = hwMtu.coerceAtLeast(128).toUInt(),
            bitrateBps = bitrateBps.coerceAtLeast(1_000L).toULong(),
            bridge = object : RnsCustomInterfaceBridge {
                override fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean =
                    outboundHandler.onOutboundFrame(interfaceTag, frame)

                override fun onInterfaceDetached(interfaceTag: String) {
                    outboundHandler.onInterfaceDetached(interfaceTag)
                }
            },
        )
    }

    override fun detachInterface(interfaceTag: String): Boolean {
        if (!RnsNativeLoader.ensureLoaded()) return false
        val node = nodeProvider() ?: return false
        return runCatching { node.detachCustomInterface(interfaceTag) }.getOrDefault(false)
    }

    override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) {
        if (!RnsNativeLoader.ensureLoaded()) return
        val node = nodeProvider() ?: return
        runCatching { node.pushCustomInboundFrame(interfaceTag, frame) }
    }

    override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) {
        if (!RnsNativeLoader.ensureLoaded()) return
        val node = nodeProvider() ?: return
        runCatching {
            node.updateCustomInterfaceMetrics(
                interfaceTag,
                CustomLinkMetrics(
                    peerId = metrics.peerId,
                    connected = metrics.connected,
                    rssiDbm = metrics.rssiDbm,
                    rttMs = metrics.rttMs?.coerceAtLeast(0L)?.toUInt(),
                    estimatedBandwidthBps = metrics.estimatedBandwidthBps?.coerceAtLeast(0L)?.toULong(),
                ),
            )
        }
    }

    override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> {
        if (!RnsNativeLoader.ensureLoaded()) return emptyList()
        val node = nodeProvider() ?: return emptyList()
        return runCatching {
            node.getCustomInterfaceMetrics(interfaceTag).map { m ->
                RnsBridgeLinkMetrics(
                    peerId = m.peerId,
                    connected = m.connected,
                    rssiDbm = m.rssiDbm,
                    rttMs = m.rttMs?.toLong(),
                    estimatedBandwidthBps = m.estimatedBandwidthBps?.toLong(),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * RNS-04↔03 Pluggable Bridge Connector.
 *
 * Implements [RnsFrameBridge] to connect [RnsWifiAwareAdapter], [RnsBleL2capAdapter], and
 * [RnsAwareTransportCoordinator] to [RnsCustomBridgeSink] (`UniFfiRnsNodeBridgeSink` / [RnsCoreEngine]),
 * while enforcing RNS-05 private-network IFAC isolation and peer revocation.
 */
internal class RnsAwareBridgeConnector(
    private val sink: RnsCustomBridgeSink,
    private val ifacGuard: RnsIfacFrameGuard? = null,
    private val coreEngineProvider: (() -> RnsCoreEngine?)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val onTopologyMetrics: ((interfaceTag: String, RnsBridgeLinkMetrics) -> Unit)? = null,
) : RnsFrameBridge, Closeable {

    private val attachedHandlers = ConcurrentHashMap<String, RnsCustomInterfaceOutboundHandler>()
    private val boundAdapters = ConcurrentHashMap<String, (String) -> Unit>()

    /** Binds an eviction callback so revoked peers drop immediately at the radio adapter. */
    fun registerPeerEvictor(interfaceTag: String, evictPeer: (String) -> Unit) {
        boundAdapters[interfaceTag] = evictPeer
    }

    /** Binds a [RnsAwareTransportCoordinator]'s adapters for immediate revocation teardown. */
    fun bindCoordinator(coordinator: RnsAwareTransportCoordinator) {
        registerPeerEvictor(RnsWifiAwareAdapter.DEFAULT_INTERFACE_TAG, coordinator.wifiAwareAdapter::evictPeer)
        registerPeerEvictor(RnsBleL2capAdapter.DEFAULT_INTERFACE_TAG, coordinator.bleL2capAdapter::evictPeer)
    }

    /** Revokes a radio peer or RNS destination hash and evicts any active radio links immediately. */
    fun revokePeer(peerId: String? = null, destinationHashHex: String? = null) {
        ifacGuard?.revoke(peerId = peerId, destinationHashHex = destinationHashHex)
        if (!peerId.isNullOrBlank()) {
            boundAdapters.values.forEach { evictor -> evictor(peerId) }
        }
    }

    /** Rotates the IFAC secret/generation; detaches interfaces if the new config is invalid/public. */
    fun rotateIfacConfig(newConfig: RnsIfacConfig) {
        val guard = ifacGuard ?: return
        guard.rotateConfig(newConfig)
        if (!guard.isConfigurationValid(clockMs())) {
            attachedHandlers.keys.toList().forEach { detachInterface(it) }
        }
    }

    fun isRuntimeReady(): Boolean = coreEngineProvider?.invoke()?.isAvailable != false

    override fun attachInterface(
        interfaceTag: String,
        kind: RnsCustomInterfaceKind,
        hwMtu: Int,
        bitrateBps: Long,
        outboundHandler: RnsCustomInterfaceOutboundHandler,
    ) {
        val guard = ifacGuard
        if (guard != null && !guard.isConfigurationValid(clockMs())) {
            return
        }
        val wrappedHandler = object : RnsCustomInterfaceOutboundHandler {
            override fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean {
                if (guard != null && !guard.isConfigurationValid(clockMs())) {
                    return false
                }
                return outboundHandler.onOutboundFrame(interfaceTag, frame)
            }

            override fun onInterfaceDetached(interfaceTag: String) {
                attachedHandlers.remove(interfaceTag)
                outboundHandler.onInterfaceDetached(interfaceTag)
            }
        }
        attachedHandlers[interfaceTag] = wrappedHandler
        sink.attachInterface(interfaceTag, kind, hwMtu, bitrateBps, wrappedHandler)
    }

    override fun detachInterface(interfaceTag: String): Boolean {
        val handler = attachedHandlers.remove(interfaceTag)
        val detached = sink.detachInterface(interfaceTag)
        handler?.onInterfaceDetached(interfaceTag)
        return detached || handler != null
    }

    override fun pushInboundFrame(interfaceTag: String, frame: ByteArray) {
        val guard = ifacGuard
        if (guard != null && !guard.isConfigurationValid(clockMs())) {
            return
        }
        sink.pushInboundFrame(interfaceTag, frame)
    }

    override fun updateLinkMetrics(interfaceTag: String, metrics: RnsBridgeLinkMetrics) {
        val guard = ifacGuard
        if (guard != null && !guard.isPeerAllowed(peerId = metrics.peerId, nowMs = clockMs())) {
            boundAdapters[interfaceTag]?.invoke(metrics.peerId)
            return
        }
        sink.updateLinkMetrics(interfaceTag, metrics)
        onTopologyMetrics?.invoke(interfaceTag, metrics)
    }

    override fun getLinkMetrics(interfaceTag: String): List<RnsBridgeLinkMetrics> =
        sink.getLinkMetrics(interfaceTag)

    override fun close() {
        attachedHandlers.keys.toList().forEach { detachInterface(it) }
        boundAdapters.clear()
    }
}
