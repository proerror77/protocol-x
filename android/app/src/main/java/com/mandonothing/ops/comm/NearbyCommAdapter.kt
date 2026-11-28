package com.mandonothing.ops.comm

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.mandonothing.ops.NearbyConfig
import com.mandonothing.ops.NearbyProvisioningIdentity
import com.mandonothing.ops.NearbySettings
import com.mandonothing.ops.comm.aware.RnsRadioKind
import com.mandonothing.ops.comm.network.RnsManagedRoute
import com.mandonothing.ops.comm.network.RnsNetworkInputs
import com.mandonothing.ops.comm.network.RnsNetworkManager
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.mandonothing.ops.comm.rns.LxmfDeliveryStatus
import com.mandonothing.ops.comm.rns.RnsCredentialLeaseSync
import com.mandonothing.ops.comm.rns.RnsLocalStore
import com.mandonothing.ops.comm.rns.RnsLxmfTransportAdapter
import com.mandonothing.ops.comm.rns.RnsResourceTransferManager
import com.mandonothing.ops.comm.rns.RnsRuntimeConfig
import com.mandonothing.ops.comm.rns.RnsRuntimeService
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of [CommGateway] backed purely by RNS / LXMF ([RnsLxmfTransportAdapter],
 * [RnsRuntimeService], [RnsNetworkManager], [RnsTopologyDiagnostics]) and Ops `/nearby` leases
 * ([NearbySettings], [RnsCredentialLeaseSync]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultCommGateway(
    private val context: Context?,
    private val scope: CoroutineScope,
    observeNearby: Boolean = context != null,
) : CommGateway {
    private val settings by lazy { context?.let(::NearbySettings) }
    private val rnsStore by lazy { context?.let(::RnsLocalStore) }
    private val topologyDiagnostics: RnsTopologyDiagnostics =
        if (observeNearby) RnsRuntimeService.topologyDiagnostics else RnsTopologyDiagnostics()
    private val networkManager: RnsNetworkManager = RnsNetworkManager()
    private val adapters = linkedMapOf<CommTransportKind, CommTransportAdapter>()
    private val mutableNetworkState = MutableStateFlow(CommNetworkState())
    private val mutableMessages = MutableStateFlow<List<CommMessageEnvelope>>(emptyList())
    private val mutableEvents = MutableSharedFlow<CommEventEnvelope>(extraBufferCapacity = 32)
    private val mutableServerNearby = MutableStateFlow(true)
    private val receiveMutedChannels = ConcurrentHashMap<String, Boolean>()
    private var activeScope: CommScope? = null
    private var cachedCredential: NearbyConfig? = null
    private var uiActive: Boolean = false
    private var lastEmittedIds = emptySet<String>()
    private val rnsAdapter: RnsLxmfTransportAdapter? = if (observeNearby) {
        RnsLxmfTransportAdapter(
            scope = scope,
            coordinatorProvider = { RnsRuntimeService.activeCoordinator },
        )
    } else {
        null
    }

    override val networkState: StateFlow<CommNetworkState> = mutableNetworkState.asStateFlow()
    override val messages: StateFlow<List<CommMessageEnvelope>> = mutableMessages.asStateFlow()
    override val events: SharedFlow<CommEventEnvelope> = mutableEvents.asSharedFlow()
    override val serverSupportsNearby: StateFlow<Boolean> = mutableServerNearby.asStateFlow()

    init {
        rnsAdapter?.let(::registerAdapter)
        if (observeNearby) observeRnsRuntime()
    }

    override fun registerAdapter(adapter: CommTransportAdapter) {
        adapters[adapter.kind] = adapter
        publishNetworkAndMessages()
    }

    private fun observeRnsRuntime() {
        scope.launch {
            launch { RnsRuntimeService.awareNetworkState.collect { publishNetworkAndMessages() } }
            launch { RnsRuntimeService.awareDiagnostics.collect { publishNetworkAndMessages() } }
            launch { RnsRuntimeService.snapshot.collect { publishNetworkAndMessages() } }
            launch { topologyDiagnostics.snapshot.collect { publishNetworkAndMessages() } }
            rnsAdapter?.let { rns ->
                launch { rns.linkStatus.collect { publishNetworkAndMessages() } }
                launch { rns.messages.collect { publishNetworkAndMessages() } }
                launch { rns.events.collect { mutableEvents.tryEmit(it) } }
            }
        }
    }

    override fun selectScope(scope: CommScope?) {
        if (scope?.nearbyScopeKey != activeScope?.nearbyScopeKey) {
            cachedCredential = null
            lastEmittedIds = emptySet()
        }
        activeScope = scope
        rnsAdapter?.selectScope(scope)
        publishNetworkAndMessages()
    }

    override fun setUiActive(active: Boolean) {
        uiActive = active
        if (active) {
            RnsRuntimeService.activeIntegration?.pollMultiHopTelemetry()
        }
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
            settings?.read(it.accountKey, it.eventId, it.channelId, it.channelName)
        }
        cachedCredential = config
        if (config == null || Build.VERSION.SDK_INT < 29) {
            if (current == null && RnsRuntimeService.activeCoordinator != null) {
                RnsRuntimeService.stop(appContext)
            }
            publishNetworkAndMessages()
            return
        }
        if (forceRestart || RnsRuntimeService.activeCoordinator == null) {
            val ifacLease = rnsStore?.loadIfacLease(config.event, config.channel)?.takeIf { it.isLive() }
            RnsRuntimeService.start(
                appContext,
                RnsRuntimeConfig(
                    accountKey = config.account,
                    eventId = config.event,
                    channelId = config.channel,
                    channelName = config.title,
                    actorId = config.actor.orEmpty(),
                    lxmfDisplayName = config.actor,
                    propagationNodeHashHex = ifacLease?.gatewayDestinationHashHex,
                    ifac = ifacLease?.toPolicy(),
                    revokedIdentityHashes = ifacLease?.revokedDeviceHashes.orEmpty().toList(),
                ),
            )
        }
        publishNetworkAndMessages()
    }

    override fun isSessionReady(scope: CommScope?): Boolean = rnsAdapter?.isReadyFor(scope) == true

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

    override fun hasDirectedEncryptionKey(scope: CommScope?): Boolean {
        val cred = currentCredential(scope)
        if (cred?.ownAgreementKey != null) return true
        if (scope == null) return false
        return rnsStore?.loadIdentityBinding(scope.accountKey, scope.eventId) != null
    }

    override fun send(outbound: CommOutboundMessage): CommSendResult {
        val currentState = mutableNetworkState.value.let { state ->
            val extraLinks = adapters.values
                .filter { it.kind !in state.links }
                .associate { it.kind to it.linkStatus.value }
            if (extraLinks.isEmpty()) state else state.copy(links = state.links + extraLinks)
        }
        evaluateNetworkManager(currentState)
        val policyKinds = CommRouterPolicy.candidateTransports(
            state = currentState,
            outbound = outbound,
            availableAdapters = adapters.keys,
        )
        val managedKinds = networkManager.orderedTransportKinds(outbound)
        val ordered = (policyKinds + managedKinds).distinct()
        var lastRejection: CommSendResult.Rejected? = null
        for (kind in ordered) {
            val adapter = adapters[kind] ?: continue
            if (!adapter.supports(outbound)) continue
            val result = runBlocking { adapter.send(outbound) }
            if (result is CommSendResult.Rejected && result.retryable) {
                lastRejection = result
                val managed = when (kind) {
                    CommTransportKind.INTERNET_HTTP, CommTransportKind.LIVEKIT_MEDIA -> RnsManagedRoute.WAN_HTTP
                    CommTransportKind.WIFI_AWARE -> RnsManagedRoute.RNS_WIFI_AWARE
                    CommTransportKind.BLE_L2CAP -> RnsManagedRoute.RNS_BLE_L2CAP
                    CommTransportKind.RNS_MESH -> RnsManagedRoute.LAN_RNS
                }
                networkManager.reportRouteFailure(managed)
                continue
            }
            return result
        }
        return lastRejection ?: CommSendResult.Rejected("离线通信暂不可用", retryable = true)
    }

    override suspend fun markRead(scope: CommScope, messageId: String): Boolean =
        rnsAdapter?.acknowledgeRead(scope, messageId) == true

    override fun markAssistanceSynced(scope: CommScope, messageId: String) {
        rnsAdapter?.markSyncedToServer(messageId)
    }

    override fun provisionCredentials(accountKey: String, eventId: String, leases: JSONObject) {
        cachedCredential = null
        settings?.save(accountKey, eventId, leases)
        val store = rnsStore ?: return
        RnsCredentialLeaseSync.applyNearbyCredentials(
            eventId = eventId,
            leases = leases,
            store = store,
            coordinator = RnsRuntimeService.activeCoordinator,
        )
        publishNetworkAndMessages()
    }

    override fun clearCredentials(accountKey: String, eventId: String) {
        if (activeScope?.accountKey == accountKey && activeScope?.eventId == eventId) {
            cachedCredential = null
        }
        settings?.clear(accountKey, eventId)
        rnsStore?.clearIfacLeases(eventId)
        rnsStore?.clearIdentityBinding(accountKey, eventId)
        publishNetworkAndMessages()
    }

    override fun rnsIdentityPayload(accountKey: String, eventId: String): JSONObject? {
        val store = rnsStore ?: return null
        val snap = RnsRuntimeService.snapshot.value
        val storedId = store.loadIdentity(accountKey)
        val savedBinding = store.loadIdentityBinding(accountKey, eventId)
        val destHash = snap.lxmfDestinationHashHex
            .ifBlank { snap.destinationHashHex }
            .ifBlank { savedBinding?.rnsDestinationHashHex.orEmpty() }
            .ifBlank { storedId?.destinationHashHex.orEmpty() }
        if (destHash.isBlank()) return null
        return JSONObject()
            .put("dest_hash", destHash)
            .put("identity_hash", snap.identityHashHex.ifBlank { storedId?.identityHashHex.orEmpty() })
            .put("ed25519_pub", savedBinding?.ed25519PubB64.orEmpty())
            .put("x25519_pub", savedBinding?.x25519PubB64.orEmpty())
    }

    override fun bindRnsIdentity(accountKey: String, eventId: String, actorId: String, deviceTokenHash: String) {
        RnsRuntimeService.activeCoordinator?.bindActorIdentity(accountKey, eventId, actorId, deviceTokenHash)
    }

    override fun applyRevocations(eventId: String, payload: JSONObject) {
        val store = rnsStore ?: return
        RnsCredentialLeaseSync.applyRevocations(
            eventId = eventId,
            payload = payload,
            store = store,
            coordinator = RnsRuntimeService.activeCoordinator,
        )
    }

    override fun sendAttachmentResource(
        attachmentId: String,
        scope: CommScope,
        targetKind: String,
        mediaType: String,
        caption: String,
        posterBytes: ByteArray,
        contentBytes: ByteArray,
        taskId: String,
        durationMs: Int?,
    ): Boolean {
        val adapter = rnsAdapter ?: return false
        val state = adapter.sendAttachmentResource(
            RnsResourceTransferManager.TransferPayload(
                attachmentId = attachmentId,
                accountKey = scope.accountKey,
                eventId = scope.eventId,
                channelId = scope.channelId,
                taskId = taskId,
                targetKind = targetKind,
                mediaType = mediaType,
                caption = caption,
                posterBytes = posterBytes,
                contentBytes = contentBytes,
                durationMs = durationMs,
            ),
            scope = scope,
        ) ?: return false
        return state.status != LxmfDeliveryStatus.FAILED
    }

    override fun markEnvelopeSynced(envelopeId: String) {
        rnsAdapter?.markSyncedToServer(envelopeId)
    }

    override fun gatewayReceipts(): SharedFlow<JSONObject>? = rnsAdapter?.gatewayReceipts

    override fun rnsNetworkManager(): RnsNetworkManager = networkManager

    override fun rnsTopologyDiagnostics(): RnsTopologyDiagnostics = topologyDiagnostics

    override suspend fun localIdentityKeys(accountKey: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        NearbyProvisioningIdentity(accountKey).publicKey to null
    }

    override fun setReceiveMuted(channelId: String, muted: Boolean) {
        receiveMutedChannels[channelId] = muted
    }

    override fun decodePrivatePhoto(bytes: ByteArray): Bitmap? = decodeNearbyPhoto(bytes)

    override fun formatDiagnostics(timeFormatter: (Long) -> String): String {
        val state = mutableNetworkState.value
        val wifi = state.links[CommTransportKind.WIFI_AWARE]
        val ble = state.links[CommTransportKind.BLE_L2CAP]
        val delivered = if (state.lastDeliveryMs == 0L) "尚无送达回执" else timeFormatter(state.lastDeliveryMs)
        val topo = topologyDiagnostics.snapshot.value
        val netMgrState = networkManager.state.value
        return buildString {
            appendLine("可达组员  ${state.reachablePeerCount}")
            netMgrState.selectedRoute?.let { appendLine("当前路由  ${it.transportKind.displayName}") }
            appendLine("Wi-Fi Aware  ${wifi?.statusLabel ?: "未启用"} · ${wifi?.peerCount ?: 0} 人")
            appendLine("蓝牙  ${ble?.statusLabel ?: "未启用"} · ${ble?.peerCount ?: 0} 人")
            if (topo.reachableDestinations > 0 || topo.reconvergenceCount > 0L) {
                val rttPart = topo.bestLinkRttMs?.let { " · RTT ${it}ms" }.orEmpty()
                appendLine("RNS 多跳  ${topo.reachableDestinations} 节点 · 最大 ${topo.maxHopCount} 跳$rttPart")
            }
            append("最近送达  $delivered")
            state.lastDeliveryKind?.let { append(" · ${it.displayName}") }
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
        val cred = currentCredential(currentScope)
        val awareLinks = RnsRuntimeService.awareNetworkState.value.links
        val mergedLinks = linkedMapOf<CommTransportKind, CommLinkStatus>()
        mergedLinks[CommTransportKind.WIFI_AWARE] = awareLinks[CommTransportKind.WIFI_AWARE]
            ?: CommLinkStatus(CommTransportKind.WIFI_AWARE, CommReachability.UNAVAILABLE, "未启用")
        mergedLinks[CommTransportKind.BLE_L2CAP] = awareLinks[CommTransportKind.BLE_L2CAP]
            ?: CommLinkStatus(CommTransportKind.BLE_L2CAP, CommReachability.UNAVAILABLE, "未启用")

        adapters.values.forEach { adapter ->
            if (adapter.kind != CommTransportKind.RNS_MESH) {
                val customStatus = adapter.linkStatus.value
                val current = mergedLinks[adapter.kind]
                if (current == null || (customStatus.reachability == CommReachability.CONNECTED &&
                        (current.reachability != CommReachability.CONNECTED || customStatus.peerCount > current.peerCount))
                ) {
                    mergedLinks[adapter.kind] = customStatus
                }
            }
        }
        if (hasInternet || serverReachable) {
            mergedLinks[CommTransportKind.INTERNET_HTTP] = CommLinkStatus(
                kind = CommTransportKind.INTERNET_HTTP,
                reachability = if (serverReachable) CommReachability.CONNECTED else CommReachability.DEGRADED,
                statusLabel = if (serverReachable) "在线" else "仅本地网络",
            )
        }
        val rnsLink = adapters[CommTransportKind.RNS_MESH]?.linkStatus?.value ?: rnsAdapter?.linkStatus?.value
        if (rnsLink != null && rnsLink.reachability != CommReachability.UNAVAILABLE) {
            mergedLinks[CommTransportKind.RNS_MESH] = topologyDiagnostics.toRnsMeshLinkStatus(rnsLink)
        }

        val rnsEnvelopes = rnsAdapter?.messages?.value.orEmpty().sortedBy { it.createdAtMs }
        val lastDelivery = rnsEnvelopes.filter { it.deliveryState == CommDeliveryState.DELIVERED || it.deliveryState == CommDeliveryState.SYNCED }
            .maxByOrNull { it.createdAtMs }
        val awarePeerCount = mergedLinks.values
            .filter { it.kind == CommTransportKind.WIFI_AWARE || it.kind == CommTransportKind.BLE_L2CAP }
            .sumOf { it.peerCount }
        val baseNetwork = CommNetworkState(
            hasInternet = hasInternet,
            validatedInternet = validatedInternet,
            serverReachable = serverReachable,
            nearbyActive = cred != null && currentScope != null,
            nearbyVoiceSupported = cred?.voiceEnabled == true && topologyDiagnostics.snapshot.value.reachableDestinations > 0,
            nearbyScopeKey = currentScope?.nearbyScopeKey,
            links = mergedLinks,
            bestRoute = CommRouterPolicy.selectBestRoute(mergedLinks, serverReachable),
            reachablePeerCount = awarePeerCount,
            lastDeliveryMs = lastDelivery?.createdAtMs ?: 0L,
            lastDeliveryKind = lastDelivery?.route?.deliveredTransport ?: lastDelivery?.route?.attemptedTransport,
        )
        val topoApplied = topologyDiagnostics.applyToNetworkState(baseNetwork)
        evaluateNetworkManager(topoApplied)
        val mergedNetwork = topoApplied.copy(
            bestRoute = networkManager.state.value.selectedRoute?.transportKind ?: topoApplied.bestRoute,
            reachablePeerCount = maxOf(
                topoApplied.reachablePeerCount,
                networkManager.state.value.networkState.reachablePeerCount,
            ),
        )
        if (mergedNetwork != mutableNetworkState.value) {
            mutableNetworkState.value = mergedNetwork
            mutableEvents.tryEmit(CommEventEnvelope.NetworkChanged(mergedNetwork))
        }

        if (currentScope == null || rnsEnvelopes.isEmpty()) {
            if (mutableMessages.value.isNotEmpty()) mutableMessages.value = emptyList()
            return
        }
        mutableMessages.value = rnsEnvelopes
        val newIds = rnsEnvelopes.mapTo(mutableSetOf()) { it.id }
        rnsEnvelopes.filter { it.id !in lastEmittedIds && !it.isMine }.forEach {
            mutableEvents.tryEmit(CommEventEnvelope.MessageReceived(it))
        }
        lastEmittedIds = newIds
    }

    private fun evaluateNetworkManager(state: CommNetworkState) {
        val awareDiag = RnsRuntimeService.awareDiagnostics.value
        val rnsStatus = state.links[CommTransportKind.RNS_MESH]
        val topo = topologyDiagnostics.snapshot.value
        networkManager.evaluate(
            RnsNetworkInputs(
                hasInternet = state.hasInternet,
                validatedInternet = state.validatedInternet,
                serverReachable = state.serverReachable,
                lanRnsAvailable = rnsStatus?.reachability == CommReachability.CONNECTED,
                lanRnsRttMs = topo.bestLinkRttMs,
                lanRnsPeerCount = maxOf(rnsStatus?.peerCount ?: 0, topo.reachableDestinations),
                wifiAwareStatus = state.links[CommTransportKind.WIFI_AWARE],
                wifiAwarePathCost = awareDiag.bestPathCostByRadio[RnsRadioKind.WIFI_AWARE],
                bleStatus = state.links[CommTransportKind.BLE_L2CAP],
                blePathCost = awareDiag.bestPathCostByRadio[RnsRadioKind.BLE_L2CAP],
                rnsMeshStatus = rnsStatus,
                topology = topo,
            ),
        )
    }

    private fun currentCredential(scope: CommScope?): NearbyConfig? {
        val s = scope?.takeIf { it.channelKind == "group" && it.nearbyScopeKey.isNotEmpty() } ?: return null
        cachedCredential?.takeIf { it.scope == s.nearbyScopeKey && it.live() }?.let { return it }
        return settings?.read(s.accountKey, s.eventId, s.channelId, s.channelName)
            ?.also { cachedCredential = it }
    }
}
