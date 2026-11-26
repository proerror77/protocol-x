package com.mandonothing.ops.comm

import com.mandonothing.ops.nearbyScope

/** Transport channels that can carry Protocol X messages, media, or control events. */
internal enum class CommTransportKind(val displayName: String, val supportsBulkMedia: Boolean) {
    INTERNET_HTTP("网络直连", true),
    LIVEKIT_MEDIA("实时音视频", true),
    WIFI_AWARE("Wi-Fi Aware", true),
    BLE_L2CAP("蓝牙", false),
    RNS_MESH("RNS 多跳", true),
}

/** Normalized reachability level exposed to business and UI layers. */
internal enum class CommReachability {
    UNAVAILABLE,
    DISCOVERING,
    CONNECTED,
    DEGRADED,
}

/** Transport-agnostic addressing scope for an active event/channel session. */
internal data class CommScope(
    val eventId: String,
    val channelId: String,
    val channelName: String,
    val channelKind: String = "group",
    val accountKey: String = "",
    val actorId: String = "",
) {
    val nearbyScopeKey: String
        get() = if (accountKey.isNotEmpty() && eventId.isNotEmpty() && channelId.isNotEmpty()) {
            nearbyScope(accountKey, eventId, channelId)
        } else {
            ""
        }
}

/** Per-transport status snapshot without exposing Android radio or SDK types. */
internal data class CommLinkStatus(
    val kind: CommTransportKind,
    val reachability: CommReachability,
    val statusLabel: String,
    val peerCount: Int = 0,
    val liveVoicePeerCount: Int = 0,
    val lastActivityMs: Long = 0L,
    val lastDeliveryMs: Long = 0L,
    val lastDeliveryKind: CommTransportKind? = null,
)

/** Unified network and peer reachability state across Internet and local mesh transports. */
internal data class CommNetworkState(
    val hasInternet: Boolean = false,
    val validatedInternet: Boolean = false,
    val serverReachable: Boolean = false,
    val nearbyActive: Boolean = false,
    val nearbyVoiceSupported: Boolean = false,
    val nearbyScopeKey: String? = null,
    val links: Map<CommTransportKind, CommLinkStatus> = emptyMap(),
    val bestRoute: CommTransportKind? = null,
    val reachablePeerCount: Int = 0,
    val liveVoicePeerCount: Int = 0,
    val lastDeliveryMs: Long = 0L,
    val lastDeliveryKind: CommTransportKind? = null,
) {
    val activeTransports: Set<CommTransportKind>
        get() = links.filterValues { it.reachability == CommReachability.CONNECTED }.keys

    val hasAnyPeerLink: Boolean
        get() = reachablePeerCount > 0 || activeTransports.any {
            it == CommTransportKind.WIFI_AWARE || it == CommTransportKind.BLE_L2CAP || it == CommTransportKind.RNS_MESH
        }
}

/** Participant descriptor returned by the communication layer for directed messaging. */
internal data class CommRecipient(
    val actorId: String,
    val displayName: String,
    val callsign: String = "",
)
