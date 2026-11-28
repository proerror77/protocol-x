package com.mandonothing.ops.comm

import com.mandonothing.ops.comm.network.RnsNetworkManager
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Pluggable transport adapter implemented by Wi-Fi Aware, BLE L2CAP, RNS/LXMF (RNS-03/04/06),
 * or HTTP transport backends.
 */
internal interface CommTransportAdapter {
    val kind: CommTransportKind
    val linkStatus: StateFlow<CommLinkStatus>

    fun supports(outbound: CommOutboundMessage): Boolean
    suspend fun send(outbound: CommOutboundMessage): CommSendResult
    suspend fun acknowledgeRead(scope: CommScope, messageId: String): Boolean = false
}

/** Pure routing policy selecting eligible transports for an outbound message. */
internal object CommRouterPolicy {
    fun candidateTransports(
        state: CommNetworkState,
        outbound: CommOutboundMessage,
        availableAdapters: Set<CommTransportKind>,
    ): List<CommTransportKind> {
        val preference = when {
            outbound.kind == CommEnvelopeKind.TASK_ACTION || outbound.kind == CommEnvelopeKind.ATTACHMENT_MEDIA ->
                listOf(CommTransportKind.INTERNET_HTTP, CommTransportKind.RNS_MESH)
            outbound.requiresBulkMediaTransport ->
                listOf(CommTransportKind.RNS_MESH, CommTransportKind.WIFI_AWARE, CommTransportKind.INTERNET_HTTP)
            else ->
                listOf(
                    CommTransportKind.RNS_MESH,
                    CommTransportKind.WIFI_AWARE,
                    CommTransportKind.BLE_L2CAP,
                    CommTransportKind.INTERNET_HTTP,
                )
        }
        return preference.filter { candidate ->
            if (candidate !in availableAdapters) return@filter false
            if (outbound.requiresBulkMediaTransport && !candidate.supportsBulkMedia) return@filter false
            val link = state.links[candidate]
            when (candidate) {
                CommTransportKind.INTERNET_HTTP -> state.serverReachable || state.hasInternet
                else -> link == null || link.reachability != CommReachability.UNAVAILABLE
            }
        }
    }

    fun selectBestRoute(links: Map<CommTransportKind, CommLinkStatus>, serverReachable: Boolean): CommTransportKind? {
        val connectedOrder = listOf(
            CommTransportKind.INTERNET_HTTP,
            CommTransportKind.RNS_MESH,
            CommTransportKind.WIFI_AWARE,
            CommTransportKind.BLE_L2CAP,
        )
        connectedOrder.firstOrNull { kind ->
            if (kind == CommTransportKind.INTERNET_HTTP) serverReachable
            else links[kind]?.reachability == CommReachability.CONNECTED
        }?.let { return it }
        return connectedOrder.firstOrNull { kind ->
            links[kind]?.reachability == CommReachability.DISCOVERING
        }
    }
}

/**
 * Unified communication gateway consumed by MainActivity and application services.
 * Decouples business/UI code from RNS, Wi-Fi Aware, BLE L2CAP, HTTP, and LiveKit internals.
 */
internal interface CommGateway {
    val networkState: StateFlow<CommNetworkState>
    val messages: StateFlow<List<CommMessageEnvelope>>
    val events: SharedFlow<CommEventEnvelope>
    val serverSupportsNearby: StateFlow<Boolean>

    fun registerAdapter(adapter: CommTransportAdapter)
    fun selectScope(scope: CommScope?)
    fun setUiActive(active: Boolean)
    fun setServerNearbySupported(supported: Boolean)
    fun setServerReachable(reachable: Boolean)
    fun refresh(forceRestart: Boolean = false)

    fun isSessionReady(scope: CommScope?): Boolean
    fun unavailableReason(): String
    fun availableRecipients(scope: CommScope?): List<CommRecipient>
    fun hasDirectedEncryptionKey(scope: CommScope?): Boolean

    fun send(outbound: CommOutboundMessage): CommSendResult
    suspend fun markRead(scope: CommScope, messageId: String): Boolean
    fun markAssistanceSynced(scope: CommScope, messageId: String)

    fun provisionCredentials(accountKey: String, eventId: String, leases: JSONObject)
    fun clearCredentials(accountKey: String, eventId: String)
    suspend fun localIdentityKeys(accountKey: String): Pair<String, String?>
    fun rnsIdentityPayload(accountKey: String, eventId: String): JSONObject? = null
    fun bindRnsIdentity(accountKey: String, eventId: String, actorId: String, deviceTokenHash: String) {}
    fun applyRevocations(eventId: String, payload: JSONObject) {}
    fun sendAttachmentResource(
        attachmentId: String,
        scope: CommScope,
        targetKind: String,
        mediaType: String,
        caption: String,
        posterBytes: ByteArray,
        contentBytes: ByteArray,
        taskId: String = "",
        durationMs: Int? = null,
    ): Boolean = false
    fun markEnvelopeSynced(envelopeId: String) {}
    fun gatewayReceipts(): SharedFlow<JSONObject>? = null
    fun rnsNetworkManager(): RnsNetworkManager? = null
    fun rnsTopologyDiagnostics(): RnsTopologyDiagnostics? = null
    fun setReceiveMuted(channelId: String, muted: Boolean)
    fun decodePrivatePhoto(bytes: ByteArray): android.graphics.Bitmap?
    fun formatDiagnostics(timeFormatter: (Long) -> String): String
}

// --- Unified PTT / Media / Conference Transport Contracts (decouples LiveKit & Nearby Voice) ---

internal enum class PttTransportMode {
    NONE,
    LIVEKIT,
    NEARBY_LIVE,
    NEARBY_VOICE_NOTE,
}

internal enum class PttPhase {
    OFFLINE,
    CONNECTING,
    LISTENING,
    REQUESTING,
    SPEAKING,
}

internal enum class PttCue {
    ENDED,
    BUSY,
}

internal data class PttSpeaker(
    val trackSid: String,
    val name: String,
)

internal data class PttClip(
    val trackSid: String,
    val speaker: String,
    val mine: Boolean,
    val at: String,
    val durationMs: Int,
    val caption: String? = null,
)

internal data class PttSessionSnapshot(
    val phase: PttPhase = PttPhase.OFFLINE,
    val statusMessage: String = "未加入频道",
    val speaker: PttSpeaker? = null,
    val lastHeard: PttSpeaker? = null,
    val outputLabel: String = "系统默认",
    val receiveMuted: Boolean = false,
    val mode: PttTransportMode = PttTransportMode.NONE,
    val localClips: List<PttClip> = emptyList(),
)

/** Transport-agnostic PTT session engine (LiveKit now; RNS-10 multi-hop PTT later). */
internal interface PttSessionTransport {
    val mode: PttTransportMode
    val receiveMuted: Boolean

    fun connect()
    fun startPress(): Boolean
    fun stopPress(cancel: Boolean = false)
    fun setReceiveMuted(muted: Boolean)
    fun audioOutputs(): List<AudioDevice>
    fun currentOutput(): AudioDevice?
    fun selectOutput(device: AudioDevice)
    fun close()
}

/** Transport-agnostic live video feed contract (LiveKit VideoChannel adapter). */
internal interface VideoChannelTransport {
    fun connect()
    fun startCamera()
    fun stopCamera()
    fun close()
}

internal data class VideoChannelState(
    val text: String,
    val ready: Boolean = false,
    val cameraRequested: Boolean = false,
    val sharing: Boolean = false,
    val showingTrack: Boolean = false,
    val failed: Boolean = false,
)

/** Transport-agnostic 1:1 conference media contract (LiveKit ConferenceMedia adapter). */
internal interface ConferenceCallTransport {
    fun start()
    fun close()
}

/** Generic offline store-and-forward queue contract for task mutations, photos, and attachments. */
internal interface CommOfflineQueue<T> {
    fun pending(owner: String, origin: String): List<T>
    fun complete(entry: T): Boolean
}

/** Permission request codes owned by local radio & fallback voice controllers. */
internal object CommPermissionCodes {
    const val RADIO = 4283
    const val MICROPHONE = 4284
}

/** UI-facing local radio permission and offline PTT fallback controller. */
internal interface CommLocalFallbackController {
    fun select(scope: CommScope?)
    fun resume(refresh: Boolean = false)
    fun pause()
    fun permissionResult(code: Int)
    fun permissionsMissing(): Boolean
    fun grantRadios()
    fun openAssistance(send: (String) -> Unit)
    fun startHold(feedback: android.widget.TextView)
    fun stopHold(feedback: android.widget.TextView, cancel: Boolean)
    fun play(encoded: String, feedback: android.widget.TextView)
}
