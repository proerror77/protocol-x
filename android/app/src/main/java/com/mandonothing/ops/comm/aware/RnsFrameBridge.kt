package com.mandonothing.ops.comm.aware

/**
 * Interface kind matching `uniffi.protocolx_rns.CustomInterfaceKind` in RNS-03.
 */
internal enum class RnsCustomInterfaceKind {
    WIFI_AWARE,
    BLE_L2CAP,
    CUSTOM,
}

/**
 * Per-peer link telemetry matching `uniffi.protocolx_rns.CustomLinkMetrics` in RNS-03.
 */
internal data class RnsBridgeLinkMetrics(
    val peerId: String,
    val connected: Boolean,
    val rssiDbm: Int? = null,
    val rttMs: Long? = null,
    val estimatedBandwidthBps: Long? = null,
)

/**
 * Outbound callback contract matching `uniffi.protocolx_rns.RnsCustomInterfaceBridge` in RNS-03.
 * Implemented by [RnsWifiAwareAdapter] and [RnsBleL2capAdapter] so the Rust RNS core can push
 * serialized RNS packets directly onto Android local radio links.
 */
internal interface RnsCustomInterfaceOutboundHandler {
    fun onOutboundFrame(interfaceTag: String, frame: ByteArray): Boolean
    fun onInterfaceDetached(interfaceTag: String)
}

/**
 * Pluggable interface bridge matching `@rns-poc`'s UniFFI `RnsNode` custom interface contract.
 * `RnsRuntimeService` (RNS-03) implements this interface by delegating directly to `RnsNode`.
 */
internal interface RnsFrameBridge {
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
