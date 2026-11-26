package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommDeliveryState
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommEventEnvelope
import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommMessageEnvelope
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportAdapter
import com.mandonothing.ops.comm.CommTransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * RNS-02 [CommTransportAdapter] implementation backed by [RnsRuntimeCoordinator] / LXMF.
 * Surfaces DELIVERED / FAILED / PROPAGATED receipts and Resource-backed payloads through RNS-02 contracts.
 */
internal class RnsLxmfTransportAdapter(
    scope: CoroutineScope,
    private val coordinatorProvider: () -> RnsRuntimeCoordinator?,
    private val snapshotFlow: StateFlow<RnsRuntimeSnapshot> = RnsRuntimeService.snapshot,
    private val inboundFlow: StateFlow<List<LxmfInboundRecord>> = RnsRuntimeService.inboundMessages,
    private val receiptFlow: SharedFlow<LxmfDeliveryReceipt> = RnsRuntimeService.deliveryReceipts,
    private val peerFlow: StateFlow<List<RnsPeerRecord>> = RnsRuntimeService.peers,
    private val destinationResolver: (CommOutboundMessage, List<RnsPeerRecord>) -> String? = ::defaultResolveDestination,
) : CommTransportAdapter {
    override val kind: CommTransportKind = CommTransportKind.RNS_MESH

    private val mutableLinkStatus = MutableStateFlow(
        CommLinkStatus(kind = CommTransportKind.RNS_MESH, reachability = CommReachability.UNAVAILABLE, statusLabel = "未启动"),
    )
    private val mutableEvents = MutableSharedFlow<CommEventEnvelope>(extraBufferCapacity = 64)
    private val mutableMessages = MutableStateFlow<List<CommMessageEnvelope>>(emptyList())
    private var activeScope: CommScope? = null

    override val linkStatus: StateFlow<CommLinkStatus> = mutableLinkStatus.asStateFlow()
    val events: SharedFlow<CommEventEnvelope> = mutableEvents.asSharedFlow()
    val messages: StateFlow<List<CommMessageEnvelope>> = mutableMessages.asStateFlow()

    init {
        scope.launch {
            launch {
                snapshotFlow.collect { snapshot ->
                    mutableLinkStatus.value = mapSnapshotToLinkStatus(snapshot)
                }
            }
            launch {
                inboundFlow.collect { records ->
                    val currentScope = activeScope ?: return@collect
                    val mapped = records
                        .filter { it.eventId == currentScope.eventId && it.channelId == currentScope.channelId }
                        .map { RnsEnvelopeCodec.inboundToEnvelope(it, currentScope) }
                    mutableMessages.value = mapped
                    mapped.lastOrNull()?.let { mutableEvents.tryEmit(CommEventEnvelope.MessageReceived(it)) }
                }
            }
            launch {
                receiptFlow.collect { receipt ->
                    mutableEvents.tryEmit(
                        CommEventEnvelope.DeliveryUpdated(
                            messageId = receipt.messageId,
                            state = RnsEnvelopeCodec.toDeliveryState(receipt.status),
                            transport = CommTransportKind.RNS_MESH,
                        ),
                    )
                }
            }
        }
    }

    fun selectScope(scope: CommScope?) {
        activeScope = scope
        val currentScope = scope ?: run {
            mutableMessages.value = emptyList()
            return
        }
        mutableMessages.value = inboundFlow.value
            .filter { it.eventId == currentScope.eventId && it.channelId == currentScope.channelId }
            .map { RnsEnvelopeCodec.inboundToEnvelope(it, currentScope) }
    }

    override fun supports(outbound: CommOutboundMessage): Boolean =
        outbound.kind in SUPPORTED_KINDS

    override suspend fun send(outbound: CommOutboundMessage): CommSendResult {
        val coordinator = coordinatorProvider()
            ?: return CommSendResult.Rejected("RNS 运行时服务尚未就绪", retryable = true)
        val destination = destinationResolver(outbound, peerFlow.value)
            ?: return CommSendResult.Rejected("未发现可达的 RNS 目标节点", retryable = true)
        return coordinator.enqueueAndSend(outbound, destination)
    }

    override suspend fun acknowledgeRead(scope: CommScope, messageId: String): Boolean {
        val outbound = CommOutboundMessage(
            scope = scope,
            kind = CommEnvelopeKind.CONTROL_ACK,
            text = "confirm:$messageId",
            idempotencyKey = "rns-ack-$messageId-${scope.actorId}",
        )
        return send(outbound) !is CommSendResult.Rejected
    }

    companion object {
        private val SUPPORTED_KINDS = setOf(
            CommEnvelopeKind.CHANNEL_TEXT,
            CommEnvelopeKind.PRIVATE_ENCRYPTED,
            CommEnvelopeKind.ASSISTANCE_ALERT,
            CommEnvelopeKind.VOICE_NOTE,
            CommEnvelopeKind.ATTACHMENT_MEDIA,
            CommEnvelopeKind.TASK_ACTION,
            CommEnvelopeKind.CONTROL_ACK,
            CommEnvelopeKind.DELIVERY_RECEIPT,
        )

        fun mapSnapshotToLinkStatus(snapshot: RnsRuntimeSnapshot): CommLinkStatus {
            val reachability = when {
                !snapshot.nativeAvailable -> CommReachability.UNAVAILABLE
                !snapshot.running -> CommReachability.UNAVAILABLE
                snapshot.activeLinksCount > 0 || snapshot.knownPeersCount > 0 -> CommReachability.CONNECTED
                else -> CommReachability.DISCOVERING
            }
            val label = when (reachability) {
                CommReachability.CONNECTED -> "已连接 (${snapshot.knownPeersCount} 节点)"
                CommReachability.DISCOVERING -> "正在发现 RNS 节点"
                CommReachability.DEGRADED -> "仅队列排队"
                CommReachability.UNAVAILABLE -> if (!snapshot.nativeAvailable) "未加载原生内核" else "未启动"
            }
            return CommLinkStatus(
                kind = CommTransportKind.RNS_MESH,
                reachability = reachability,
                statusLabel = label,
                peerCount = snapshot.knownPeersCount,
            )
        }

        fun defaultResolveDestination(outbound: CommOutboundMessage, peers: List<RnsPeerRecord>): String? {
            if (outbound.recipientActor.matches(Regex("[0-9a-fA-F]{32}"))) {
                return outbound.recipientActor.lowercase()
            }
            if (outbound.recipientActor.isNotBlank()) {
                peers.firstOrNull { it.appDataText == outbound.recipientActor }
                    ?.let { return it.destinationHashHex }
            }
            return peers.firstOrNull { it.appDataText == outbound.scope.channelId }?.destinationHashHex
                ?: peers.firstOrNull()?.destinationHashHex
        }
    }
}
