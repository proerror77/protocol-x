package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommDeliveryState
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommEventEnvelope
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommReachability
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import com.mandonothing.ops.comm.DefaultCommGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RnsLxmfTransportAdapterTest {
    private val passThroughCipher = object : RnsPayloadCipher {
        override fun encrypt(data: ByteArray, context: String): ByteArray = data
        override fun decrypt(data: ByteArray, context: String): ByteArray = data
    }

    private val scope = CommScope(
        eventId = "evt-golf",
        channelId = "ch-alpha",
        channelName = "调度一组",
        accountKey = "acc-1",
        actorId = "person-1",
    )

    @Test
    fun commGatewayRoutesTextAndMultiKbAttachmentThroughRnsAdapterAndMapsReceipts() = runTest(UnconfinedTestDispatcher()) {
        val dir = Files.createTempDirectory("rns-adapter-").toFile()
        val adapterScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = RnsRuntimeCoordinatorTest.FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine)
            val peerDest = "ab".repeat(16)
            engine.directLinks += peerDest

            coordinator.start(
                RnsRuntimeConfig(
                    accountKey = "acc-1",
                    eventId = "evt-golf",
                    channelId = "ch-alpha",
                    channelName = "调度一组",
                    actorId = "person-1",
                    propagationNodeHashHex = "99".repeat(16),
                ),
            )
            coordinator.handleCoreEvent(RnsCoreEvent.AnnounceHeard(peerDest, "id", "ch-alpha".toByteArray(), hops = 2))

            val adapter = RnsLxmfTransportAdapter(
                scope = adapterScope,
                coordinatorProvider = { coordinator },
                snapshotFlow = coordinator.snapshot,
                inboundFlow = coordinator.inboundMessages,
                receiptFlow = coordinator.deliveryReceipts,
                peerFlow = coordinator.peers,
            )
            adapter.selectScope(scope)

            assertEquals(CommReachability.CONNECTED, adapter.linkStatus.value.reachability)

            val events = mutableListOf<CommEventEnvelope>()
            val collector = launch { adapter.events.collect { events += it } }

            val gateway = DefaultCommGateway(context = null, scope = adapterScope, observeNearby = false)
            gateway.registerAdapter(adapter)

            // Multi-KB resource attachment routes via RNS_MESH
            val photoBytes = ByteArray(12_000) { (it % 251).toByte() }
            val result = gateway.send(
                CommOutboundMessage(
                    id = "photo-msg-1",
                    scope = scope,
                    kind = CommEnvelopeKind.ATTACHMENT_MEDIA,
                    text = "车辆到场照片",
                    photoBytes = photoBytes,
                ),
            )
            assertEquals(CommSendResult.Accepted("photo-msg-1", CommTransportKind.RNS_MESH), result)
            val sent = engine.sentRequests.single()
            assertEquals(LxmfDeliveryMode.DIRECT_LINK, sent.deliveryMode)
            assertEquals(LxmfAttachmentKind.PHOTO_JPEG, sent.attachmentKind)
            assertEquals(12_000, sent.attachmentBytes?.size)

            // Surface DELIVERED, PROPAGATED, FAILED receipts through RNS-02 DeliveryUpdated events
            engine.emit(RnsCoreEvent.DeliveryUpdated("photo-msg-1", engine.lastGeneratedHash, LxmfDeliveryStatus.PROPAGATED))
            engine.emit(RnsCoreEvent.DeliveryUpdated("photo-msg-1", engine.lastGeneratedHash, LxmfDeliveryStatus.DELIVERED))
            engine.emit(RnsCoreEvent.DeliveryUpdated("photo-msg-1", engine.lastGeneratedHash, LxmfDeliveryStatus.FAILED, "timeout"))

            val deliveryStates = events.filterIsInstance<CommEventEnvelope.DeliveryUpdated>().map { it.state }
            assertEquals(
                listOf(CommDeliveryState.AWAITING_RECEIPT, CommDeliveryState.DELIVERED, CommDeliveryState.FAILED),
                deliveryStates,
            )

            // Inbound multi-hop LXMF message maps into CommMessageEnvelope
            engine.emit(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "in-hash-1",
                    sourceHashHex = peerDest,
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "in-env-1", scope = scope, kind = CommEnvelopeKind.ASSISTANCE_ALERT, assistance = true),
                    ),
                    contentText = "需要备用车",
                    attachmentKind = LxmfAttachmentKind.NONE,
                    signatureValid = true,
                    hopCount = 3,
                ),
            )
            assertEquals(1, adapter.messages.value.size)
            val inboundEnvelope = adapter.messages.value.single()
            assertEquals("in-env-1", inboundEnvelope.id)
            assertTrue(inboundEnvelope.assistance)
            assertEquals(3, inboundEnvelope.route.hopCount)
            assertEquals(CommTransportKind.RNS_MESH, inboundEnvelope.route.deliveredTransport)

            collector.cancel()
        } finally {
            adapterScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun deliveryModeSelectsOpportunisticForSmallTextAndDirectOrPropagatedForAttachments() {
        val smallText = CommOutboundMessage(scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "ok")
        assertEquals(
            LxmfDeliveryMode.OPPORTUNISTIC,
            RnsEnvelopeCodec.selectDeliveryMode(smallText, hasDirectLink = false, hasPropagationNode = true),
        )
        assertEquals(
            LxmfDeliveryMode.DIRECT_LINK,
            RnsEnvelopeCodec.selectDeliveryMode(smallText, hasDirectLink = true, hasPropagationNode = true),
        )

        val largeText = CommOutboundMessage(
            scope = scope,
            kind = CommEnvelopeKind.CHANNEL_TEXT,
            text = "A".repeat(RnsEnvelopeCodec.MAX_OPPORTUNISTIC_BYTES),
        )
        assertEquals(
            LxmfDeliveryMode.PROPAGATED,
            RnsEnvelopeCodec.selectDeliveryMode(largeText, hasDirectLink = false, hasPropagationNode = true),
        )
    }

    @Test
    fun nativeLoaderDegradesGracefullyOnHostJvmWithoutNativeLibrary() {
        assertFalse(RnsNativeLoader.ensureLoaded())
        assertFalse(RnsNativeLoader.isNativeAvailable)
        assertTrue(!RnsNativeLoader.loadError.isNullOrBlank())
    }
}
