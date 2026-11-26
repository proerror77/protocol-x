package com.mandonothing.ops.comm

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.mandonothing.ops.NEARBY_PRIVATE_ENVELOPE_LIMIT
import com.mandonothing.ops.NearbyConfig
import com.mandonothing.ops.NearbyDelivery
import com.mandonothing.ops.NearbyDiagnosticSnapshot
import com.mandonothing.ops.NearbyIdentity
import com.mandonothing.ops.NearbyMessage
import com.mandonothing.ops.NearbyMessages
import com.mandonothing.ops.NearbyPrivate
import com.mandonothing.ops.NearbyPrivateContent
import com.mandonothing.ops.NearbyPrivateIdentity
import com.mandonothing.ops.NearbyRadio
import com.mandonothing.ops.NearbyService
import com.mandonothing.ops.NearbySettings
import com.mandonothing.ops.decodeNearbyPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Pure mapping helpers between Nearby Mesh internals and RNS-02 envelopes/state. */
internal object NearbyCommMapper {
    fun toTransportKind(radio: NearbyRadio): CommTransportKind = when (radio) {
        NearbyRadio.WIFI -> CommTransportKind.WIFI_AWARE
        NearbyRadio.BLE -> CommTransportKind.BLE_L2CAP
    }

    fun toDeliveryState(delivery: NearbyDelivery, synced: Boolean): CommDeliveryState = when (delivery) {
        NearbyDelivery.WAITING -> CommDeliveryState.QUEUED
        NearbyDelivery.AWAITING_RECEIPT -> CommDeliveryState.AWAITING_RECEIPT
        NearbyDelivery.DELIVERED -> if (synced) CommDeliveryState.SYNCED else CommDeliveryState.DELIVERED
        NearbyDelivery.EXPIRED -> CommDeliveryState.EXPIRED
    }

    fun toNetworkState(
        radios: Map<NearbyRadio, String>,
        diagnostics: NearbyDiagnosticSnapshot,
        credential: NearbyConfig?,
        activeScopeKey: String?,
        hasInternet: Boolean = false,
        validatedInternet: Boolean = false,
        serverReachable: Boolean = false,
    ): CommNetworkState {
        val wifiStatusLabel = radios[NearbyRadio.WIFI] ?: "未启用"
        val bleStatusLabel = radios[NearbyRadio.BLE] ?: "未启用"
        val wifiReachability = reachabilityFor(wifiStatusLabel, diagnostics.wifiPeers)
        val bleReachability = reachabilityFor(bleStatusLabel, diagnostics.bluetoothPeers)
        val lastKind = diagnostics.lastDeliveryRoute?.let(::toTransportKind)
        val links = buildMap {
            put(
                CommTransportKind.WIFI_AWARE,
                CommLinkStatus(
                    kind = CommTransportKind.WIFI_AWARE,
                    reachability = wifiReachability,
                    statusLabel = wifiStatusLabel,
                    peerCount = diagnostics.wifiPeers,
                    liveVoicePeerCount = diagnostics.liveVoicePeers,
                    lastActivityMs = diagnostics.lastActivityMillis,
                    lastDeliveryMs = diagnostics.lastDeliveryMillis,
                    lastDeliveryKind = lastKind,
                ),
            )
            put(
                CommTransportKind.BLE_L2CAP,
                CommLinkStatus(
                    kind = CommTransportKind.BLE_L2CAP,
                    reachability = bleReachability,
                    statusLabel = bleStatusLabel,
                    peerCount = diagnostics.bluetoothPeers,
                    lastActivityMs = diagnostics.lastActivityMillis,
                    lastDeliveryMs = diagnostics.lastDeliveryMillis,
                    lastDeliveryKind = lastKind,
                ),
            )
            if (hasInternet || serverReachable) {
                put(
                    CommTransportKind.INTERNET_HTTP,
                    CommLinkStatus(
                        kind = CommTransportKind.INTERNET_HTTP,
                        reachability = if (serverReachable) CommReachability.CONNECTED else CommReachability.DEGRADED,
                        statusLabel = if (serverReachable) "在线" else "仅本地网络",
                    ),
                )
            }
        }
        return CommNetworkState(
            hasInternet = hasInternet,
            validatedInternet = validatedInternet,
            serverReachable = serverReachable,
            nearbyActive = credential != null && activeScopeKey != null,
            nearbyVoiceSupported = credential?.voiceEnabled == true && diagnostics.liveVoicePeers > 0,
            nearbyScopeKey = activeScopeKey,
            links = links,
            bestRoute = CommRouterPolicy.selectBestRoute(links, serverReachable),
            reachablePeerCount = diagnostics.reachablePeers,
            liveVoicePeerCount = diagnostics.liveVoicePeers,
            lastDeliveryMs = diagnostics.lastDeliveryMillis,
            lastDeliveryKind = lastKind,
        )
    }

    private fun reachabilityFor(label: String, peerCount: Int): CommReachability = when {
        peerCount > 0 || label == "已连接" -> CommReachability.CONNECTED
        label == "正在发现" -> CommReachability.DISCOVERING
        else -> CommReachability.UNAVAILABLE
    }

    fun toEnvelope(
        message: NearbyMessage,
        scope: CommScope,
        localSender: String,
        localActor: String,
        receipts: List<NearbyMessage>,
        privateContent: NearbyPrivateContent?,
    ): CommMessageEnvelope {
        val mine = message.sender == localSender
        val kind = when {
            message.isPrivate -> CommEnvelopeKind.PRIVATE_ENCRYPTED
            message.assistance -> CommEnvelopeKind.ASSISTANCE_ALERT
            message.audio.isNotEmpty() -> CommEnvelopeKind.VOICE_NOTE
            message.confirmationFor.isNotEmpty() -> CommEnvelopeKind.CONTROL_ACK
            message.receiptFor.isNotEmpty() -> CommEnvelopeKind.DELIVERY_RECEIPT
            else -> CommEnvelopeKind.CHANNEL_TEXT
        }
        val routeKind = (if (message.received || !mine) message.deliveredVia else message.attemptedVia)
            ?.let(::toTransportKind)
        return CommMessageEnvelope(
            id = message.id,
            scope = scope,
            kind = kind,
            senderId = message.sender,
            senderName = message.name,
            recipientId = message.recipient,
            text = privateContent?.text ?: message.text,
            audioBase64 = message.audio,
            photoBase64 = privateContent?.photo?.let(java.util.Base64.getEncoder()::encodeToString),
            assistance = message.assistance,
            isPrivate = message.isPrivate,
            isMine = mine,
            createdAtMs = message.time,
            deliveryState = toDeliveryState(message.delivery, message.synced),
            syncedToServer = message.synced,
            needsReadReceipt = message.confirmationRequired,
            readByMe = receipts.any { it.actor == localActor },
            readByNames = receipts.map { it.name }.distinct(),
            route = CommRouteMetadata(
                attemptedTransport = message.attemptedVia?.let(::toTransportKind),
                deliveredTransport = message.deliveredVia?.let(::toTransportKind),
                idempotencyKey = message.id,
            ).let { meta ->
                if (routeKind != null && meta.deliveredTransport == null && meta.attemptedTransport == null) {
                    meta.copy(attemptedTransport = routeKind)
                } else meta
            },
        )
    }
}

/**
 * Wraps NearbyMessages / NearbyPrivate behind CommTransportAdapter for Wi-Fi Aware & BLE L2CAP.
 * RNS-04 (Wi-Fi Aware Adapter) and RNS-03/06 (RNS Mesh) can register alongside or replace this adapter.
 */
internal class NearbyTransportAdapter(
    override val kind: CommTransportKind,
    private val linkStateProvider: () -> StateFlow<CommLinkStatus>,
    private val sessionProvider: () -> NearbyMessages?,
    private val configProvider: () -> NearbyConfig?,
) : CommTransportAdapter {
    override val linkStatus: StateFlow<CommLinkStatus>
        get() = linkStateProvider()

    override fun supports(outbound: CommOutboundMessage): Boolean {
        if (!kind.supportsBulkMedia && outbound.requiresBulkMediaTransport) return false
        return outbound.kind in setOf(
            CommEnvelopeKind.CHANNEL_TEXT,
            CommEnvelopeKind.PRIVATE_ENCRYPTED,
            CommEnvelopeKind.ASSISTANCE_ALERT,
            CommEnvelopeKind.VOICE_NOTE,
        )
    }

    override suspend fun send(outbound: CommOutboundMessage): CommSendResult = sendSync(outbound)

    fun sendSync(outbound: CommOutboundMessage): CommSendResult {
        val session = sessionProvider()
            ?: return CommSendResult.Rejected("附近通信会话未就绪", retryable = true)
        return try {
            val id = outbound.id.ifBlank { UUID.randomUUID().toString() }
            if (outbound.recipientActor.isNotEmpty() || outbound.photoBytes != null) {
                val config = configProvider()
                    ?: return CommSendResult.Rejected("当前频道离线凭证不可用", retryable = true)
                val recipient = config.recipients.firstOrNull { it.actor == outbound.recipientActor }
                    ?: return CommSendResult.Rejected("收件人离线密钥待同步", retryable = true)
                val envelope = NearbyPrivate.seal(config, recipient, id, outbound.text, outbound.photoBytes)
                require(envelope.length <= NEARBY_PRIVATE_ENVELOPE_LIMIT)
                session.send("", id = id, recipient = outbound.recipientActor, sealedPayload = envelope)
            } else {
                session.send(
                    text = outbound.text,
                    audio = outbound.audioBytes,
                    assistance = outbound.assistance,
                    id = id,
                )
            }
            val activeLink = linkStatus.value.reachability
            if (activeLink == CommReachability.CONNECTED) {
                CommSendResult.Accepted(id, kind)
            } else {
                CommSendResult.Queued(id, "等待附近设备可达")
            }
        } catch (error: Exception) {
            CommSendResult.Rejected(error.message ?: "暂时无法保存，请重试", retryable = true)
        }
    }

    override suspend fun acknowledgeRead(scope: CommScope, messageId: String): Boolean {
        val session = sessionProvider() ?: return false
        return session.markRead(messageId)
    }
}

/**
 * Default implementation of [CommGateway] wrapping NearbyService, NearbyMessages,
 * NearbySettings, and NearbyPrivate without altering on-wire protocols or storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultCommGateway(
    private val context: Context?,
    private val scope: CoroutineScope,
    observeNearby: Boolean = context != null,
) : CommGateway {
    private val settings by lazy { NearbySettings(requireNotNull(context)) }
    private val adapters = linkedMapOf<CommTransportKind, CommTransportAdapter>()
    private val mutableNetworkState = MutableStateFlow(CommNetworkState())
    private val mutableMessages = MutableStateFlow<List<CommMessageEnvelope>>(emptyList())
    private val mutableEvents = MutableSharedFlow<CommEventEnvelope>(extraBufferCapacity = 32)
    private val mutableServerNearby = MutableStateFlow(true)
    private val wifiLinkStatus = MutableStateFlow(
        CommLinkStatus(CommTransportKind.WIFI_AWARE, CommReachability.UNAVAILABLE, "未启用"),
    )
    private val bleLinkStatus = MutableStateFlow(
        CommLinkStatus(CommTransportKind.BLE_L2CAP, CommReachability.UNAVAILABLE, "未启用"),
    )
    private var activeScope: CommScope? = null
    private var privateReader: Pair<String, NearbyPrivateIdentity>? = null
    private val privateContentCache = LinkedHashMap<String, NearbyPrivateContent>()
    private var lastEmittedIds = emptySet<String>()

    override val networkState: StateFlow<CommNetworkState> = mutableNetworkState.asStateFlow()
    override val messages: StateFlow<List<CommMessageEnvelope>> = mutableMessages.asStateFlow()
    override val events: SharedFlow<CommEventEnvelope> = mutableEvents.asSharedFlow()
    override val serverSupportsNearby: StateFlow<Boolean> = mutableServerNearby.asStateFlow()

    init {
        registerAdapter(
            NearbyTransportAdapter(
                kind = CommTransportKind.WIFI_AWARE,
                linkStateProvider = { wifiLinkStatus },
                sessionProvider = { currentNearbySession(activeScope) },
                configProvider = { currentCredential(activeScope) },
            ),
        )
        registerAdapter(
            NearbyTransportAdapter(
                kind = CommTransportKind.BLE_L2CAP,
                linkStateProvider = { bleLinkStatus },
                sessionProvider = { currentNearbySession(activeScope) },
                configProvider = { currentCredential(activeScope) },
            ),
        )
        if (observeNearby) observeNearbyService()
    }

    override fun registerAdapter(adapter: CommTransportAdapter) {
        adapters[adapter.kind] = adapter
    }

    private fun observeNearbyService() {
        scope.launch {
            launch { NearbyService.radios.collect { publishNetworkAndMessages() } }
            launch { NearbyService.channelCredential.collect { publishNetworkAndMessages() } }
            launch { NearbyService.diagnostics.collect { publishNetworkAndMessages() } }
            NearbyService.session.collectLatest { session ->
                if (session == null) {
                    lastEmittedIds = emptySet()
                    publishNetworkAndMessages()
                } else {
                    session.messages.collect { publishNetworkAndMessages() }
                }
            }
        }
    }

    override fun selectScope(scope: CommScope?) {
        if (scope?.nearbyScopeKey != activeScope?.nearbyScopeKey) {
            privateContentCache.clear()
            lastEmittedIds = emptySet()
        }
        activeScope = scope
        publishNetworkAndMessages()
    }

    override fun setUiActive(active: Boolean) {
        NearbyService.uiActive = active
    }

    override fun setServerNearbySupported(supported: Boolean) {
        mutableServerNearby.value = supported
    }

    override fun setServerReachable(reachable: Boolean) {
        updateServerConnectivity(
            hasInternet = reachable || mutableNetworkState.value.hasInternet,
            validatedInternet = reachable || mutableNetworkState.value.validatedInternet,
            serverReachable = reachable,
        )
    }

    override fun refresh(forceRestart: Boolean) {
        val appContext = context ?: return
        val current = activeScope
        val config = current?.takeIf { it.channelKind == "group" && it.nearbyScopeKey.isNotEmpty() }?.let {
            settings.read(it.accountKey, it.eventId, it.channelId, it.channelName)
        }
        if (config == null || Build.VERSION.SDK_INT < 29) {
            if (NearbyService.activeScope != null) NearbyService.stop(appContext)
            publishNetworkAndMessages()
            return
        }
        if (forceRestart || NearbyService.activeScope != config.scope) {
            NearbyService.start(appContext, config)
        }
        publishNetworkAndMessages()
    }

    override fun isSessionReady(scope: CommScope?): Boolean = currentNearbySession(scope) != null

    override fun unavailableReason(): String = when {
        Build.VERSION.SDK_INT < 29 -> "离线通信需要 Android 10 或更新版本"
        !mutableServerNearby.value -> "服务端尚未启用频道离线通信"
        else -> "离线通信权限待同步"
    }

    override fun availableRecipients(scope: CommScope?): List<CommRecipient> {
        val config = currentCredential(scope) ?: return emptyList()
        return config.recipients
            .filter { it.actor != config.actor }
            .sortedBy { it.name }
            .map { CommRecipient(actorId = it.actor, displayName = it.name) }
    }

    override fun hasDirectedEncryptionKey(scope: CommScope?): Boolean =
        currentCredential(scope)?.ownAgreementKey != null

    override fun send(outbound: CommOutboundMessage): CommSendResult {
        val currentState = mutableNetworkState.value.let { state ->
            val extraLinks = adapters.values
                .filter { it.kind !in state.links }
                .associate { it.kind to it.linkStatus.value }
            if (extraLinks.isEmpty()) state else state.copy(links = state.links + extraLinks)
        }
        val ordered = CommRouterPolicy.candidateTransports(
            state = currentState,
            outbound = outbound,
            availableAdapters = adapters.keys,
        )
        var lastRejection: CommSendResult.Rejected? = null
        for (kind in ordered) {
            val adapter = adapters[kind] ?: continue
            if (!adapter.supports(outbound)) continue
            val result = if (adapter is NearbyTransportAdapter) {
                adapter.sendSync(outbound)
            } else {
                runBlocking { adapter.send(outbound) }
            }
            if (result is CommSendResult.Rejected && result.retryable) {
                lastRejection = result
                continue
            }
            return result
        }
        return lastRejection ?: CommSendResult.Rejected("离线通信暂不可用", retryable = true)
    }

    override suspend fun markRead(scope: CommScope, messageId: String): Boolean {
        val session = currentNearbySession(scope) ?: return false
        return session.markRead(messageId)
    }

    override fun markAssistanceSynced(scope: CommScope, messageId: String) {
        currentNearbySession(scope)?.syncAssistance(messageId)
    }

    override fun provisionCredentials(accountKey: String, eventId: String, leases: JSONObject) {
        settings.save(accountKey, eventId, leases)
    }

    override fun clearCredentials(accountKey: String, eventId: String) {
        if (activeScope?.accountKey == accountKey && activeScope?.eventId == eventId) {
            privateReader = null
            privateContentCache.clear()
        }
        settings.clear(accountKey, eventId)
    }

    override suspend fun localIdentityKeys(accountKey: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val signing = NearbyIdentity(accountKey).publicKey
        val agreement = context?.let { runCatching { NearbyPrivateIdentity(it, accountKey).publicKey }.getOrNull() }
        signing to agreement
    }

    override fun setReceiveMuted(channelId: String, muted: Boolean) {
        NearbyService.setReceiveMuted(channelId, muted)
    }

    override fun decodePrivatePhoto(bytes: ByteArray): Bitmap? = decodeNearbyPhoto(bytes)

    override fun formatDiagnostics(timeFormatter: (Long) -> String): String {
        val state = mutableNetworkState.value
        val wifi = state.links[CommTransportKind.WIFI_AWARE]
        val ble = state.links[CommTransportKind.BLE_L2CAP]
        val live = NearbyService.liveVoice
        val delivered = if (state.lastDeliveryMs == 0L) "尚无送达回执" else timeFormatter(state.lastDeliveryMs)
        return buildString {
            appendLine("可达组员  ${state.reachablePeerCount}")
            appendLine("Wi-Fi Aware  ${wifi?.statusLabel ?: "未启用"} · ${wifi?.peerCount ?: 0} 人")
            appendLine("蓝牙  ${ble?.statusLabel ?: "未启用"} · ${ble?.peerCount ?: 0} 人")
            append("最近送达  $delivered")
            state.lastDeliveryKind?.let { append(" · ${it.displayName}") }
            if (state.liveVoicePeerCount > 0 || live?.transmitting == true) {
                appendLine()
                if (live?.transmitting == true) append("正在附近发言")
                else append("支持附近实时语音  ${state.liveVoicePeerCount} 人")
            }
        }
    }

    fun updateServerConnectivity(hasInternet: Boolean, validatedInternet: Boolean, serverReachable: Boolean) {
        val current = mutableNetworkState.value
        if (current.hasInternet == hasInternet &&
            current.validatedInternet == validatedInternet &&
            current.serverReachable == serverReachable
        ) {
            return
        }
        publishNetworkAndMessages(hasInternet, validatedInternet, serverReachable)
    }

    private fun publishNetworkAndMessages(
        hasInternet: Boolean = mutableNetworkState.value.hasInternet,
        validatedInternet: Boolean = mutableNetworkState.value.validatedInternet,
        serverReachable: Boolean = mutableNetworkState.value.serverReachable,
    ) {
        val currentScope = activeScope
        val nextNetwork = NearbyCommMapper.toNetworkState(
            radios = NearbyService.radios.value,
            diagnostics = NearbyService.diagnostics.value,
            credential = currentCredential(currentScope),
            activeScopeKey = NearbyService.activeScope,
            hasInternet = hasInternet,
            validatedInternet = validatedInternet,
            serverReachable = serverReachable,
        )
        nextNetwork.links[CommTransportKind.WIFI_AWARE]?.let { wifiLinkStatus.value = it }
        nextNetwork.links[CommTransportKind.BLE_L2CAP]?.let { bleLinkStatus.value = it }
        if (nextNetwork != mutableNetworkState.value) {
            mutableNetworkState.value = nextNetwork
            mutableEvents.tryEmit(CommEventEnvelope.NetworkChanged(nextNetwork))
        }

        val session = currentNearbySession(currentScope)
        if (currentScope == null || session == null) {
            if (mutableMessages.value.isNotEmpty()) mutableMessages.value = emptyList()
            return
        }
        val entries = session.messages.value
        val envelopes = entries.asSequence()
            .filterNot { it.isControl }
            .filter { !it.isPrivate || it.sender == session.sender || session.isPrivateAddressedToMe(it) }
            .toList()
            .takeLast(24)
            .mapNotNull { message ->
                val privateBody = if (message.isPrivate) {
                    readPrivateMessage(currentScope, message) ?: return@mapNotNull null
                } else {
                    null
                }
                NearbyCommMapper.toEnvelope(
                    message = message,
                    scope = currentScope,
                    localSender = session.sender,
                    localActor = session.actor,
                    receipts = session.readReceipts(message.id),
                    privateContent = privateBody,
                )
            }
        mutableMessages.value = envelopes
        val newIds = envelopes.mapTo(mutableSetOf()) { it.id }
        envelopes.filter { it.id !in lastEmittedIds && !it.isMine }.forEach {
            mutableEvents.tryEmit(CommEventEnvelope.MessageReceived(it))
        }
        lastEmittedIds = newIds
    }

    private fun currentNearbySession(scope: CommScope?): NearbyMessages? {
        val key = scope?.nearbyScopeKey?.takeIf { it.isNotEmpty() } ?: return null
        return NearbyService.session.value?.takeIf { NearbyService.activeScope == key }
    }

    private fun currentCredential(scope: CommScope?): NearbyConfig? {
        val key = scope?.nearbyScopeKey?.takeIf { it.isNotEmpty() } ?: return null
        return NearbyService.channelCredential.value?.takeIf { it.scope == key && it.live() }
    }

    private fun readPrivateMessage(scope: CommScope, message: NearbyMessage): NearbyPrivateContent? {
        val appContext = context ?: return null
        val config = currentCredential(scope) ?: return null
        val cacheKey = "${config.generation}|${message.id}"
        privateContentCache[cacheKey]?.let { return it }
        return runCatching {
            val identity = privateReader?.takeIf { it.first == config.account }?.second
                ?: NearbyPrivateIdentity(appContext, config.account).also { privateReader = config.account to it }
            NearbyPrivate.open(config, identity, message.actor, message.recipient, message.id, message.sealedPayload)
                ?.also {
                    if (privateContentCache.size >= 28) {
                        privateContentCache.remove(privateContentCache.keys.first())
                    }
                    privateContentCache[cacheKey] = it
                }
        }.getOrNull()
    }
}
