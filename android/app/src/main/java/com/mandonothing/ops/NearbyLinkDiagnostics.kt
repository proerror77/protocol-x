package com.mandonothing.ops

internal data class NearbyDiagnosticSnapshot(
    val wifiPeers: Int = 0,
    val bluetoothPeers: Int = 0,
    val reachablePeers: Int = 0,
    val lastActivityMillis: Long = 0,
    val lastDeliveryMillis: Long = 0,
    val lastDeliveryRoute: NearbyRadio? = null,
    val liveVoicePeers: Int = 0,
)

internal class NearbyLinkDiagnostics {
    private data class Link(val radio: NearbyRadio, val peer: String, val supportsLive: Boolean)

    private val links = mutableMapOf<Any, Link>()
    private var activity = 0L
    private var delivery = 0L
    private var route: NearbyRadio? = null

    fun observe(link: Any, radio: NearbyRadio, peer: String, delivered: Boolean, via: NearbyRadio?,
                now: Long, supportsLive: Boolean = false) {
        if (peer.isNotBlank()) links[link] = Link(radio, peer, supportsLive)
        activity = now
        if (delivered) {
            delivery = now
            route = via
        }
    }

    fun remove(link: Any) { links.remove(link) }

    fun snapshot(): NearbyDiagnosticSnapshot = NearbyDiagnosticSnapshot(
        links.values.filter { it.radio == NearbyRadio.WIFI }.map { it.peer }.distinct().size,
        links.values.filter { it.radio == NearbyRadio.BLE }.map { it.peer }.distinct().size,
        links.values.map { it.peer }.distinct().size, activity, delivery, route,
        links.values.filter { it.radio == NearbyRadio.WIFI && it.supportsLive }.map { it.peer }.distinct().size
    )
}
