package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RnsRuntimeCoordinatorTest {
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

    private val config = RnsRuntimeConfig(
        accountKey = "acc-1",
        eventId = "evt-golf",
        channelId = "ch-alpha",
        channelName = "调度一组",
        actorId = "person-1",
        propagationNodeHashHex = "99".repeat(16),
    )

    @Test
    fun runtimePersistsIdentityRestartsCleanlyAndRetriesQueuedOutbox() {
        val dir = Files.createTempDirectory("rns-coord-").toFile()
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine, clockMs = { 1_000L })

            assertTrue(coordinator.start(config))
            assertTrue(coordinator.snapshot.value.running)
            assertEquals("id-hash-01", coordinator.snapshot.value.identityHashHex)

            // Queue message while send fails transiently -> remains QUEUED
            engine.failNextSend = true
            val queued = coordinator.enqueueAndSend(
                CommOutboundMessage(id = "msg-1", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "到场"),
                destinationHashHex = "aa".repeat(16),
            )
            assertTrue(queued is CommSendResult.Queued)
            assertEquals(1, coordinator.snapshot.value.pendingOutboxCount)

            // Stop & restore across simulated Android service restart
            coordinator.stop(clearPersistedConfig = false)
            assertFalse(coordinator.snapshot.value.running)

            engine.failNextSend = false
            val coordinatorRestarted = RnsRuntimeCoordinator(store, engine, clockMs = { 2_000L })
            assertTrue(coordinatorRestarted.restoreIfConfigured())
            assertTrue(coordinatorRestarted.snapshot.value.running)
            assertEquals(1, engine.sentRequests.size)
            assertEquals("msg-1", engine.sentRequests.single().messageId)
            assertEquals(1, engine.reusedSecretCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingNativeLibraryDegradesGracefullyWithoutCrash() {
        val dir = Files.createTempDirectory("rns-unavailable-").toFile()
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val unavailableEngine = FakeRnsCoreEngine(isAvailable = false)
            val coordinator = RnsRuntimeCoordinator(store, unavailableEngine)

            assertFalse(coordinator.start(config))
            assertFalse(coordinator.snapshot.value.running)
            assertFalse(coordinator.snapshot.value.nativeAvailable)

            val queued = coordinator.enqueueAndSend(
                CommOutboundMessage(id = "msg-offline", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "test"),
                destinationHashHex = "bb".repeat(16),
            )
            assertTrue(queued is CommSendResult.Queued)
            assertEquals(1, coordinator.snapshot.value.pendingOutboxCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deliveryReceiptsAndInboundSignatureValidationAreEnforced() = runTest {
        val dir = Files.createTempDirectory("rns-receipts-").toFile()
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine)
            assertTrue(coordinator.start(config))

            val receipts = mutableListOf<LxmfDeliveryReceipt>()
            val collector = launch {
                coordinator.deliveryReceipts.collect { receipts += it }
            }
            kotlinx.coroutines.yield()

            val sendResult = coordinator.enqueueAndSend(
                CommOutboundMessage(
                    id = "msg-attach",
                    scope = scope,
                    kind = CommEnvelopeKind.VOICE_NOTE,
                    audioBytes = ByteArray(2_048) { 7 },
                ),
                destinationHashHex = "cc".repeat(16),
            )
            assertTrue(sendResult is CommSendResult.Accepted)
            val sentHash = engine.lastGeneratedHash

            // Emit DELIVERED, PROPAGATED, FAILED receipts
            engine.emit(RnsCoreEvent.DeliveryUpdated("msg-attach", sentHash, LxmfDeliveryStatus.PROPAGATED, "stored on relay", 10L))
            engine.emit(RnsCoreEvent.DeliveryUpdated("msg-attach", sentHash, LxmfDeliveryStatus.DELIVERED, "acked", 20L))
            kotlinx.coroutines.yield()

            assertEquals(listOf(LxmfDeliveryStatus.PROPAGATED, LxmfDeliveryStatus.DELIVERED), receipts.map { it.status })
            assertEquals(LxmfDeliveryStatus.DELIVERED, coordinator.allOutbox("acc-1", "evt-golf").single().status)

            // Reject forged/invalid signature inbound LXMF
            engine.emit(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "bad-sig-hash",
                    sourceHashHex = "cc".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = "{}",
                    contentText = "forged",
                    signatureValid = false,
                ),
            )
            assertTrue(coordinator.inboundMessages.first().isEmpty())

            // Accept valid signature inbound LXMF
            engine.emit(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "good-sig-hash",
                    sourceHashHex = "cc".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "in-99", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "收到"),
                    ),
                    contentText = "收到",
                    signatureValid = true,
                ),
            )
            assertEquals(1, coordinator.inboundMessages.value.size)
            assertEquals("in-99", coordinator.inboundMessages.value.single().envelopeId)

            collector.cancel()
        } finally {
            dir.deleteRecursively()
        }
    }

    internal class FakeRnsCoreEngine(
        override val isAvailable: Boolean = true,
    ) : RnsCoreEngine {
        override val unavailableReason: String? = if (isAvailable) null else "missing native .so"
        private var running = false
        private var listener: ((RnsCoreEvent) -> Unit)? = null
        private var secret = ByteArray(64) { 1 }
        var failNextSend = false
        var reusedSecretCount = 0
        var lastGeneratedHash = ""
        val sentRequests = mutableListOf<LxmfSendRequest>()
        val directLinks = mutableSetOf<String>()

        override fun start(config: RnsRuntimeConfig, identitySecret: ByteArray?, listener: (RnsCoreEvent) -> Unit): RnsCoreStatus {
            if (identitySecret != null) {
                secret = identitySecret
                reusedSecretCount++
            }
            this.listener = listener
            running = true
            return status()
        }

        override fun stop() {
            running = false
        }

        override fun identitySecret(): ByteArray? = if (isAvailable) secret else null

        override fun announce(appData: ByteArray) {}

        override fun sendLxmf(request: LxmfSendRequest): LxmfSendOutcome {
            if (failNextSend) error("transient link loss")
            sentRequests += request
            lastGeneratedHash = "hash-${request.messageId}"
            return LxmfSendOutcome(request.messageId, lastGeneratedHash, LxmfDeliveryStatus.SENT)
        }

        override fun hasDirectLink(destinationHashHex: String): Boolean = destinationHashHex in directLinks

        override fun status(): RnsCoreStatus = RnsCoreStatus(
            running = running,
            identityHashHex = "id-hash-01",
            destinationHashHex = "dest-hash-01",
            lxmfDestinationHashHex = "lxmf-dest-01",
            knownPeersCount = 1,
            activeLinksCount = directLinks.size,
        )

        fun emit(event: RnsCoreEvent) {
            listener?.invoke(event)
        }
    }
}
