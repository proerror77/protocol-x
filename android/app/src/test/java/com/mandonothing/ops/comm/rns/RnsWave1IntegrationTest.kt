package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.FieldOfflineStore
import com.mandonothing.ops.comm.CommDeliveryState
import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommEventEnvelope
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import com.mandonothing.ops.comm.CommTransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RnsWave1IntegrationTest {
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
    fun ifacLeaseBindingAndRevocationBlockRevokedAndExpiredTraffic() {
        val dir = Files.createTempDirectory("rns-ifac-").toFile()
        try {
            var nowMs = 10_000L
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = RnsRuntimeCoordinatorTest.FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine, clockMs = { nowMs })

            val lease = RnsIfacLease(
                eventId = "evt-golf",
                channelId = "ch-alpha",
                networkName = "protocolx.evt-golf.ch-alpha",
                passphrase = "secret-ifac-passphrase",
                generation = "gen-1",
                expiresAtMs = 60_000L,
                gatewayDestinationHashHex = "aa".repeat(16),
                revokedDeviceHashes = setOf("cc".repeat(16)),
                revokedActors = setOf("person-revoked"),
            )
            store.saveIfacLease(lease)

            val cfg = RnsRuntimeConfig(
                accountKey = "acc-1",
                eventId = "evt-golf",
                channelId = "ch-alpha",
                channelName = "调度一组",
                actorId = "person-1",
            )
            assertTrue(coordinator.start(cfg))
            assertEquals("secret-ifac-passphrase", coordinator.ifacPolicy.value?.passphrase)
            assertEquals("aa".repeat(16), coordinator.activeGatewayDestinationHex())

            val binding = coordinator.bindActorIdentity("acc-1", "evt-golf", "person-1", "dev-hash-01")
            assertNotNull(binding)
            assertEquals("person-1", binding!!.actorId)
            assertEquals(binding, store.loadIdentityBinding("acc-1", "evt-golf"))

            // Revoked source hash is rejected before hitting inbound state.
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "msg-revoked-hash",
                    sourceHashHex = "cc".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "r-1", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "bad"),
                    ),
                    contentText = "bad",
                    signatureValid = true,
                ),
            )
            assertTrue(coordinator.inboundMessages.value.isEmpty())

            // Valid peer succeeds while lease is live.
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "msg-ok",
                    sourceHashHex = "dd".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "ok-1", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "在岗"),
                    ),
                    contentText = "在岗",
                    signatureValid = true,
                ),
            )
            assertEquals(1, coordinator.inboundMessages.value.size)

            // Dynamic revocation rejects subsequent messages from that actor/hash.
            coordinator.revokePeer("evt-golf", deviceOrIdentityHash = "dd".repeat(16), actorId = "person-1")
            assertTrue(coordinator.isPeerRevoked("dd".repeat(16)))
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "msg-after-revoke",
                    sourceHashHex = "dd".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "ok-2", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "blocked"),
                    ),
                    contentText = "blocked",
                    signatureValid = true,
                ),
            )
            assertEquals(1, coordinator.inboundMessages.value.size)

            // Expired lease also rejects inbound traffic.
            nowMs = 65_000L
            assertNull(coordinator.activeGatewayDestinationHex())
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "msg-expired-lease",
                    sourceHashHex = "ee".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "ok-3", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "late"),
                    ),
                    contentText = "late",
                    signatureValid = true,
                ),
            )
            assertEquals(1, coordinator.inboundMessages.value.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resourceTransferChunksResumeAfterInterruptionAndVerifyReassembly() {
        val dir = Files.createTempDirectory("rns-chunk-").toFile()
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val manager = RnsResourceTransferManager(store, chunkSizeBytes = 512, clockMs = { 1_000L })
            val poster = ByteArray(128) { idx -> (idx % 17).toByte() }
            val content = ByteArray(1_400) { idx -> ((idx * 7) % 251).toByte() }
            val payload = RnsResourceTransferManager.TransferPayload(
                attachmentId = "att-video-1",
                accountKey = "acc-1",
                eventId = "evt-golf",
                channelId = "ch-alpha",
                targetKind = "channel_video",
                mediaType = "video/mp4",
                caption = "B2 出口拥堵",
                posterBytes = poster,
                contentBytes = content,
                durationMs = 12_000,
            )

            // Simulate link interruption after 2 of 3 chunks succeed.
            val dispatchedIds = mutableListOf<String>()
            val capturedChunks = mutableListOf<Pair<String, ByteArray>>()
            var sendCount = 0
            val partialState = manager.sendOrResume(payload, scope, "aa".repeat(16)) { outbound, _ ->
                sendCount++
                if (sendCount <= 2) {
                    dispatchedIds += outbound.id
                    capturedChunks += outbound.text to (outbound.audioBytes ?: ByteArray(0))
                    CommSendResult.Accepted(outbound.id, CommTransportKind.RNS_MESH)
                } else {
                    CommSendResult.Rejected("link drop", retryable = true)
                }
            }
            assertEquals(3, partialState.chunkCount)
            assertEquals(setOf(0, 1), partialState.ackedChunks)
            assertFalse(partialState.isCompleteTransfer())

            // Resume transfers ONLY chunk 2 without re-sending chunks 0 or 1.
            val resumedIds = mutableListOf<String>()
            val finalState = manager.sendOrResume(payload, scope, "aa".repeat(16)) { outbound, _ ->
                resumedIds += outbound.id
                capturedChunks += outbound.text to (outbound.audioBytes ?: ByteArray(0))
                CommSendResult.Accepted(outbound.id, CommTransportKind.RNS_MESH)
            }
            assertEquals(listOf("att-video-1#chunk:2"), resumedIds)
            assertTrue(finalState.isCompleteTransfer())
            assertEquals(LxmfDeliveryStatus.SENT, finalState.status)

            // Receiver reassembles chunks and checks poster + content + sha256_combined.
            val receiverDir = Files.createTempDirectory("rns-rx-").toFile()
            val rxManager = RnsResourceTransferManager(RnsLocalStore(receiverDir, passThroughCipher), chunkSizeBytes = 512)
            assertNull(rxManager.acceptInboundChunk(capturedChunks[0].first, capturedChunks[0].second))
            assertNull(rxManager.acceptInboundChunk(capturedChunks[1].first, capturedChunks[1].second))
            val reassembled = rxManager.acceptInboundChunk(capturedChunks[2].first, capturedChunks[2].second)
            assertNotNull(reassembled)
            assertArrayEquals(poster, reassembled!!.posterBytes)
            assertArrayEquals(content, reassembled.contentBytes)
            assertEquals(finalState.sha256Combined, reassembled.state.sha256Combined)
            receiverDir.deleteRecursively()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readReceiptsSyncStateAndGatewayReceiptsUpdateWithoutDuplicateBubbles() = runTest(UnconfinedTestDispatcher()) {
        val dir = Files.createTempDirectory("rns-ack-").toFile()
        val adapterScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = RnsRuntimeCoordinatorTest.FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine)
            val peerDest = "ab".repeat(16)
            assertTrue(
                coordinator.start(
                    RnsRuntimeConfig(
                        accountKey = "acc-1",
                        eventId = "evt-golf",
                        channelId = "ch-alpha",
                        channelName = "调度一组",
                        actorId = "person-1",
                    ),
                ),
            )
            coordinator.handleCoreEvent(RnsCoreEvent.AnnounceHeard(peerDest, "id", "ch-alpha".toByteArray()))

            val adapter = RnsLxmfTransportAdapter(
                scope = adapterScope,
                coordinatorProvider = { coordinator },
                snapshotFlow = coordinator.snapshot,
                inboundFlow = coordinator.inboundMessages,
                receiptFlow = coordinator.deliveryReceipts,
                peerFlow = coordinator.peers,
                gatewayReceiptFlow = coordinator.gatewayReceipts,
            )
            adapter.selectScope(scope)

            val events = mutableListOf<CommEventEnvelope>()
            val collector = launch { adapter.events.collect { events += it } }

            // Inbound text message arrives, followed by a CONTROL_ACK read receipt from peer.
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "hash-m1",
                    sourceHashHex = peerDest,
                    destinationHashHex = "dest-hash-01",
                    title = RnsEnvelopeCodec.encodeTitle(
                        CommOutboundMessage(id = "m-1", scope = scope, kind = CommEnvelopeKind.CHANNEL_TEXT, text = "收到指示"),
                    ),
                    contentText = "收到指示",
                    signatureValid = true,
                ),
            )
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "hash-ack-1",
                    sourceHashHex = peerDest,
                    destinationHashHex = "dest-hash-01",
                    title = JSONObject()
                        .put("id", "ack-1")
                        .put("event", "evt-golf")
                        .put("channel", "ch-alpha")
                        .put("kind", CommEnvelopeKind.CONTROL_ACK.name)
                        .put("actor", "李四")
                        .toString(),
                    contentText = "confirm:m-1",
                    signatureValid = true,
                ),
            )

            // CONTROL_ACK should not add a separate bubble; it should mark m-1 read by 李四.
            assertEquals(1, adapter.messages.value.size)
            assertEquals(listOf("李四"), adapter.messages.value.single().readByNames)

            // HTTP history reconciliation promotes m-1 to SYNCED.
            adapter.markSyncedToServer("m-1")
            assertTrue(adapter.messages.value.single().syncedToServer)
            assertEquals(CommDeliveryState.SYNCED, adapter.messages.value.single().deliveryState)

            // Gateway receipt for offline task action emits DeliveryUpdated(SYNCED).
            coordinator.handleCoreEvent(
                RnsCoreEvent.LxmfReceived(
                    messageHashHex = "hash-gw-rcpt",
                    sourceHashHex = "aa".repeat(16),
                    destinationHashHex = "dest-hash-01",
                    title = JSONObject()
                        .put("id", "gw-rcpt-1")
                        .put("event", "evt-golf")
                        .put("channel", "ch-alpha")
                        .put("kind", CommEnvelopeKind.DELIVERY_RECEIPT.name)
                        .put("actor", "toc-gateway")
                        .toString(),
                    contentText = JSONObject()
                        .put("receipt_kind", "action_committed")
                        .put("target_envelope_id", "task-op-42")
                        .put("status", "committed")
                        .toString(),
                    signatureValid = true,
                ),
            )
            assertTrue(
                events.any {
                    it is CommEventEnvelope.DeliveryUpdated &&
                        it.messageId == "task-op-42" &&
                        it.state == CommDeliveryState.SYNCED
                },
            )
            collector.cancel()
        } finally {
            adapterScope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun offlineSyncReconcilerBuildsCanonicalEnvelopesOrdersCausallyAndClassifies409() {
        val ref = JSONObject().put("ontology_id", "golf.v1").put("object_type", "TransferLeg").put("id", "transfer-001")
        val q2 = FieldOfflineStore.Queued(
            id = "op-2",
            owner = "person-1",
            origin = "https://ops.example",
            event = "evt-golf",
            ref = ref,
            version = 2,
            action = "confirm_progress",
            parameters = JSONObject().put("progress_index", 0),
            clientSeq = 2L,
            baseVersion = 1,
            idempotencyKey = "op-2",
            envelopeId = "op-2",
        )
        val q1 = FieldOfflineStore.Queued(
            id = "op-1",
            owner = "person-1",
            origin = "https://ops.example",
            event = "evt-golf",
            ref = ref,
            version = 1,
            action = "acknowledge",
            parameters = JSONObject(),
            clientSeq = 1L,
            baseVersion = 1,
            idempotencyKey = "op-1",
            envelopeId = "op-1",
        )
        val qHelp = FieldOfflineStore.Queued(
            id = "op-help",
            owner = "person-1",
            origin = "https://ops.example",
            event = "evt-golf",
            ref = ref,
            version = 1,
            action = "help",
            parameters = JSONObject().put("note", "轮胎故障"),
            clientSeq = 3L,
            baseVersion = 1,
        )

        val ordered = RnsOfflineSyncReconciler.drainOrder(listOf(q2, q1, qHelp))
        assertEquals(listOf("op-help", "op-1", "op-2"), ordered.map { it.id })

        val envA = RnsOfflineSyncReconciler.buildTaskEnvelope(q1, actor = "person-1", deviceId = "dev-1")
        val envB = RnsOfflineSyncReconciler.buildTaskEnvelope(q1, actor = "person-1", deviceId = "dev-1")
        assertEquals("protocol-x.rns.envelope/v1", envA.getString("schema"))
        assertEquals("task", envA.getString("kind"))
        assertEquals(envA.getString("fingerprint"), envB.getString("fingerprint"))
        assertEquals("op-1", envA.getString("idempotency_key"))

        // Case A: 409 because peer/gateway already committed confirm_progress[0].
        val caseA = RnsOfflineSyncReconciler.classify409(
            entry = q2,
            conflictPayload = JSONObject()
                .put("detail", "对象版本已变化")
                .put("current_version", 3)
                .put("current_status", "in_progress")
                .put(
                    "current_progress",
                    JSONArray().put(JSONObject().put("status", "completed").put("confirmed_at", "2026-11-26T10:00:00Z")),
                ),
        )
        assertTrue(caseA is Rns409Resolution.AlreadyAchieved)

        // Case B: 409 because dispatcher replaced vehicle (version 1 -> 2), leaving step 0 unconfirmed.
        val caseB = RnsOfflineSyncReconciler.classify409(
            entry = q2,
            conflictPayload = JSONObject()
                .put("detail", "已更换车辆，请刷新后重新确认")
                .put("current_version", 3)
                .put("current_status", "accepted")
                .put("current_progress", JSONArray().put(JSONObject().put("status", "current"))),
        )
        assertTrue(caseB is Rns409Resolution.VersionDrift)
        assertEquals(3, (caseB as Rns409Resolution.VersionDrift).currentVersion)
    }

    @Test
    fun backendNearbyIfacPeersGatewayAndRevocationsSyncCleanly() {
        val dir = Files.createTempDirectory("rns-sync-cred-").toFile()
        try {
            val store = RnsLocalStore(dir, passThroughCipher)
            val engine = RnsRuntimeCoordinatorTest.FakeRnsCoreEngine()
            val coordinator = RnsRuntimeCoordinator(store, engine, clockMs = { 5_000L })
            assertTrue(
                coordinator.start(
                    RnsRuntimeConfig(
                        accountKey = "acc-1",
                        eventId = "evt-golf",
                        channelId = "ch-alpha",
                        channelName = "调度一组",
                        actorId = "person-1",
                    ),
                ),
            )
            val nearbyResp = JSONObject()
                .put("authority", "pk")
                .put("rns_gateway", JSONObject().put("dest_hash", "ff".repeat(16)).put("identity_hash", "ee".repeat(16)))
                .put(
                    "channels",
                    JSONArray().put(
                        JSONObject()
                            .put("channel", "ch-alpha")
                            .put("secret", "abc123")
                            .put("expires", 9_999L)
                            .put(
                                "ifac",
                                JSONObject()
                                    .put("network_name", "px-net-evt-golf")
                                    .put("passphrase", "px-ifac-123456")
                                    .put("generation", "gen-2"),
                            )
                            .put(
                                "rns_peers",
                                JSONArray().put(
                                    JSONObject()
                                        .put("actor", "person-2")
                                        .put("dest_hash", "11".repeat(16))
                                        .put("identity_hash", "22".repeat(16)),
                                ),
                            ),
                    ),
                )
            val leases = RnsCredentialLeaseSync.applyNearbyCredentials("evt-golf", nearbyResp, store, coordinator, nowMs = 5_000L)
            assertEquals(1, leases.size)
            assertEquals("px-net-evt-golf", coordinator.ifacPolicy.value?.networkName)
            assertEquals("px-ifac-123456", coordinator.ifacPolicy.value?.passphrase)
            assertEquals("ff".repeat(16), coordinator.activeGatewayDestinationHex())
            assertEquals("11".repeat(16), store.loadPeers().single().destinationHashHex)

            val revResp = JSONObject().put(
                "revocations",
                JSONArray().put(
                    JSONObject()
                        .put("revoked_actor", "person-2")
                        .put("revoked_device_hash", "33".repeat(16))
                        .put("revoked_dest_hash", "11".repeat(16)),
                ),
            )
            RnsCredentialLeaseSync.applyRevocations("evt-golf", revResp, store, coordinator)
            assertTrue(coordinator.isPeerRevoked("11".repeat(16)))
            assertTrue(coordinator.isPeerRevoked("33".repeat(16)))
            assertTrue(coordinator.isPeerRevoked("person-2"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
