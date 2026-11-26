package com.mandonothing.ops.comm.rns

import android.content.Context
import android.util.AtomicFile
import com.mandonothing.ops.FieldSessionStore
import com.mandonothing.ops.comm.CommEnvelopeKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Symmetric encryption abstraction allowing AndroidKeyStore AES-GCM in prod and pure JVM ciphers in tests. */
internal interface RnsPayloadCipher {
    fun encrypt(data: ByteArray, context: String): ByteArray
    fun decrypt(data: ByteArray, context: String): ByteArray
}

internal class FieldSessionRnsCipher(
    private val vault: FieldSessionStore,
) : RnsPayloadCipher {
    override fun encrypt(data: ByteArray, context: String): ByteArray = vault.encrypt(data, context)
    override fun decrypt(data: ByteArray, context: String): ByteArray = vault.decrypt(data, context)
}

internal data class StoredRnsIdentity(
    val accountKey: String,
    val secretBytes: ByteArray,
    val identityHashHex: String,
    val destinationHashHex: String,
    val createdAtMs: Long,
)

/**
 * Encrypted local persistence for RNS identity material, active runtime config,
 * peer announces, LXMF outbound queue, and deduplicated LXMF inbound records.
 *
 * Uses atomic writes (write-temp-then-rename) so crashes never corrupt persisted queues.
 */
internal class RnsLocalStore(
    private val rootDir: File,
    private val cipher: RnsPayloadCipher,
) {
    constructor(context: Context) : this(
        rootDir = File(context.filesDir, "rns-runtime"),
        cipher = FieldSessionRnsCipher(FieldSessionStore(context.applicationContext)),
    )

    private val identityDir = File(rootDir, "identities")
    private val outboxDir = File(rootDir, "outbox")
    private val inboxDir = File(rootDir, "inbox")
    private val ifacDir = File(rootDir, "ifac")
    private val bindingsDir = File(rootDir, "bindings")
    private val checkpointsDir = File(rootDir, "checkpoints")
    private val transfersDir = File(rootDir, "transfers")
    private val receiptsDir = File(rootDir, "read_receipts")
    private val configFile = File(rootDir, "runtime-config.dat")
    private val peersFile = File(rootDir, "peers.dat")

    @Synchronized
    fun loadIdentity(accountKey: String): StoredRnsIdentity? {
        val file = File(identityDir, "${safeKey(accountKey)}.dat")
        val json = readEncryptedJson(file, "rns-identity|$accountKey") ?: return null
        return runCatching {
            val secretHex = json.getString("secret_hex")
            StoredRnsIdentity(
                accountKey = json.getString("account_key"),
                secretBytes = hexToBytes(secretHex),
                identityHashHex = json.getString("identity_hash_hex"),
                destinationHashHex = json.getString("destination_hash_hex"),
                createdAtMs = json.getLong("created_at_ms"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun saveIdentity(identity: StoredRnsIdentity) {
        val json = JSONObject()
            .put("account_key", identity.accountKey)
            .put("secret_hex", bytesToHex(identity.secretBytes))
            .put("identity_hash_hex", identity.identityHashHex)
            .put("destination_hash_hex", identity.destinationHashHex)
            .put("created_at_ms", identity.createdAtMs)
        writeEncryptedJson(
            File(identityDir, "${safeKey(identity.accountKey)}.dat"),
            "rns-identity|${identity.accountKey}",
            json,
        )
    }

    @Synchronized
    fun saveRuntimeConfig(config: RnsRuntimeConfig) {
        val ifacObj = config.ifac?.let {
            JSONObject()
                .put("network_name", it.networkName)
                .put("passphrase", it.passphrase)
                .put("ifac_size_bytes", it.ifacSizeBytes)
                .put("require_private_ifac", it.requirePrivateIfac)
        }
        val json = JSONObject()
            .put("account_key", config.accountKey)
            .put("event_id", config.eventId)
            .put("channel_id", config.channelId)
            .put("channel_name", config.channelName)
            .put("actor_id", config.actorId)
            .put("tcp_listen_addr", config.tcpListenAddr)
            .put("tcp_connect_addrs", JSONArray(config.tcpConnectAddrs))
            .put("udp_bind_addr", config.udpBindAddr)
            .put("udp_peer_addr", config.udpPeerAddr)
            .put("propagation_node_hash_hex", config.propagationNodeHashHex)
            .put("enable_transport_relay", config.enableTransportRelay)
            .put("ifac", ifacObj ?: JSONObject.NULL)
            .put("revoked_identity_hashes", JSONArray(config.revokedIdentityHashes))
        writeEncryptedJson(configFile, "rns-runtime-config", json)
    }

    @Synchronized
    fun loadRuntimeConfig(): RnsRuntimeConfig? {
        val json = readEncryptedJson(configFile, "rns-runtime-config") ?: return null
        return runCatching {
            val connectArray = json.optJSONArray("tcp_connect_addrs")
            val connects = List(connectArray?.length() ?: 0) { idx -> connectArray!!.getString(idx) }
            val revokedArr = json.optJSONArray("revoked_identity_hashes")
            val revoked = List(revokedArr?.length() ?: 0) { idx -> revokedArr!!.getString(idx) }
            val ifacObj = json.optJSONObject("ifac")
            val ifac = ifacObj?.let {
                RnsIfacPolicy(
                    networkName = it.optString("network_name"),
                    passphrase = it.optString("passphrase"),
                    ifacSizeBytes = it.optInt("ifac_size_bytes", 16),
                    requirePrivateIfac = it.optBoolean("require_private_ifac", true),
                )
            }
            RnsRuntimeConfig(
                accountKey = json.getString("account_key"),
                eventId = json.getString("event_id"),
                channelId = json.getString("channel_id"),
                channelName = json.getString("channel_name"),
                actorId = json.optString("actor_id"),
                tcpListenAddr = json.optString("tcp_listen_addr").takeIf { it.isNotBlank() && it != "null" },
                tcpConnectAddrs = connects,
                udpBindAddr = json.optString("udp_bind_addr").takeIf { it.isNotBlank() && it != "null" },
                udpPeerAddr = json.optString("udp_peer_addr").takeIf { it.isNotBlank() && it != "null" },
                propagationNodeHashHex = json.optString("propagation_node_hash_hex").takeIf { it.isNotBlank() && it != "null" },
                enableTransportRelay = json.optBoolean("enable_transport_relay"),
                ifac = ifac,
                revokedIdentityHashes = revoked,
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearRuntimeConfig() {
        configFile.delete()
    }

    @Synchronized
    fun enqueueOutbox(record: LxmfOutboxRecord): LxmfOutboxRecord {
        val target = File(outboxDir, "${safeKey(record.id)}.dat")
        if (target.isFile) {
            loadOutboxRecord(target, record.id)?.let { return it }
        }
        persistOutbox(record)
        return record
    }

    @Synchronized
    fun updateOutboxStatus(
        id: String,
        status: LxmfDeliveryStatus,
        messageHashHex: String? = null,
        error: String = "",
        incrementAttempt: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): LxmfOutboxRecord? {
        val file = File(outboxDir, "${safeKey(id)}.dat")
        val existing = loadOutboxRecord(file, id) ?: return null
        val updated = existing.copy(
            status = status,
            messageHashHex = messageHashHex ?: existing.messageHashHex,
            lastError = error,
            attemptCount = if (incrementAttempt) existing.attemptCount + 1 else existing.attemptCount,
            updatedAtMs = nowMs,
        )
        persistOutbox(updated)
        return updated
    }

    @Synchronized
    fun findOutboxByMessageHash(messageHashHex: String): LxmfOutboxRecord? =
        allOutbox().firstOrNull { it.messageHashHex == messageHashHex }

    @Synchronized
    fun pendingOutbox(accountKey: String, eventId: String): List<LxmfOutboxRecord> =
        allOutbox()
            .filter {
                it.accountKey == accountKey &&
                    it.eventId == eventId &&
                    it.status != LxmfDeliveryStatus.DELIVERED &&
                    it.status != LxmfDeliveryStatus.FAILED
            }
            .sortedBy { it.createdAtMs }

    @Synchronized
    fun allOutbox(accountKey: String? = null, eventId: String? = null): List<LxmfOutboxRecord> =
        outboxDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".dat") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".dat")
                loadOutboxRecord(file, id)
            }
            .filter { (accountKey == null || it.accountKey == accountKey) && (eventId == null || it.eventId == eventId) }
            .sortedBy { it.createdAtMs }

    @Synchronized
    fun saveInboundIfNew(record: LxmfInboundRecord): Boolean {
        val target = File(inboxDir, "${safeKey(record.messageHashHex)}.dat")
        if (target.isFile) return false
        val json = JSONObject()
            .put("message_hash_hex", record.messageHashHex)
            .put("envelope_id", record.envelopeId)
            .put("source_hash_hex", record.sourceHashHex)
            .put("destination_hash_hex", record.destinationHashHex)
            .put("event_id", record.eventId)
            .put("channel_id", record.channelId)
            .put("sender_actor", record.senderActor)
            .put("sender_name", record.senderName)
            .put("envelope_kind", record.envelopeKind.name)
            .put("text", record.text)
            .put("attachment_kind", record.attachmentKind.name)
            .put("attachment_bytes_b64", record.attachmentBytesB64)
            .put("assistance", record.assistance)
            .put("is_private", record.isPrivate)
            .put("received_at_ms", record.receivedAtMs)
            .put("is_propagated", record.isPropagated)
            .put("hop_count", record.hopCount)
            .put("read_by_names", JSONArray(record.readByNames))
            .put("synced_to_server", record.syncedToServer)
            .put("target_message_id", record.targetMessageId)
        writeEncryptedJson(target, "rns-inbox|${record.messageHashHex}", json)
        return true
    }

    @Synchronized
    fun inboundMessages(eventId: String? = null, channelId: String? = null): List<LxmfInboundRecord> =
        inboxDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".dat") }
            .mapNotNull { file ->
                val key = file.name.removeSuffix(".dat")
                val json = readEncryptedJson(file, "rns-inbox|$key") ?: return@mapNotNull null
                runCatching {
                    val readArr = json.optJSONArray("read_by_names")
                    val readers = List(readArr?.length() ?: 0) { idx -> readArr!!.getString(idx) }
                    val envId = json.getString("envelope_id")
                    val persistedReceipts = loadReadReceipts(envId)
                    LxmfInboundRecord(
                        messageHashHex = json.getString("message_hash_hex"),
                        envelopeId = envId,
                        sourceHashHex = json.getString("source_hash_hex"),
                        destinationHashHex = json.getString("destination_hash_hex"),
                        eventId = json.getString("event_id"),
                        channelId = json.getString("channel_id"),
                        senderActor = json.optString("sender_actor"),
                        senderName = json.optString("sender_name"),
                        envelopeKind = CommEnvelopeKind.valueOf(json.getString("envelope_kind")),
                        text = json.optString("text"),
                        attachmentKind = LxmfAttachmentKind.valueOf(json.optString("attachment_kind", LxmfAttachmentKind.NONE.name)),
                        attachmentBytesB64 = json.optString("attachment_bytes_b64"),
                        assistance = json.optBoolean("assistance"),
                        isPrivate = json.optBoolean("is_private"),
                        receivedAtMs = json.getLong("received_at_ms"),
                        isPropagated = json.optBoolean("is_propagated"),
                        hopCount = json.optInt("hop_count", 1),
                        readByNames = (readers + persistedReceipts).distinct(),
                        syncedToServer = json.optBoolean("synced_to_server"),
                        targetMessageId = json.optString("target_message_id"),
                    )
                }.getOrNull()
            }
            .filter { (eventId == null || it.eventId == eventId) && (channelId == null || it.channelId == channelId) }
            .sortedBy { it.receivedAtMs }

    @Synchronized
    fun markInboundSyncedToServer(envelopeId: String): Boolean {
        var updatedAny = false
        inboxDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".dat") }
            .forEach { file ->
                val key = file.name.removeSuffix(".dat")
                val json = readEncryptedJson(file, "rns-inbox|$key") ?: return@forEach
                if (json.optString("envelope_id") == envelopeId && !json.optBoolean("synced_to_server")) {
                    json.put("synced_to_server", true)
                    writeEncryptedJson(file, "rns-inbox|$key", json)
                    updatedAny = true
                }
            }
        return updatedAny
    }

    @Synchronized
    fun appendReadReceipt(targetEnvelopeId: String, readerName: String) {
        if (targetEnvelopeId.isBlank() || readerName.isBlank()) return
        val file = File(receiptsDir, "${safeKey(targetEnvelopeId)}.dat")
        val current = loadReadReceipts(targetEnvelopeId).toMutableSet()
        if (current.add(readerName)) {
            writeEncryptedJson(file, "rns-read|$targetEnvelopeId", JSONObject().put("readers", JSONArray(current.toList())))
        }
    }

    @Synchronized
    fun loadReadReceipts(targetEnvelopeId: String): List<String> {
        val file = File(receiptsDir, "${safeKey(targetEnvelopeId)}.dat")
        val json = readEncryptedJson(file, "rns-read|$targetEnvelopeId") ?: return emptyList()
        val arr = json.optJSONArray("readers") ?: return emptyList()
        return List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
    }

    @Synchronized
    fun saveIfacLease(lease: RnsIfacLease) {
        val all = loadIfacLeases(lease.eventId).associateBy { it.channelId }.toMutableMap()
        all[lease.channelId] = lease
        val arr = JSONArray()
        all.values.forEach { item ->
            arr.put(
                JSONObject()
                    .put("event_id", item.eventId)
                    .put("channel_id", item.channelId)
                    .put("network_name", item.networkName)
                    .put("passphrase", item.passphrase)
                    .put("ifac_size_bytes", item.ifacSizeBytes)
                    .put("require_private_ifac", item.requirePrivateIfac)
                    .put("generation", item.generation)
                    .put("expires_at_ms", item.expiresAtMs)
                    .put("gateway_dest_hash_hex", item.gatewayDestinationHashHex)
                    .put("revoked_device_hashes", JSONArray(item.revokedDeviceHashes.toList()))
                    .put("revoked_actors", JSONArray(item.revokedActors.toList())),
            )
        }
        writeEncryptedJson(File(ifacDir, "${safeKey(lease.eventId)}.dat"), "rns-ifac|${lease.eventId}", JSONObject().put("leases", arr))
    }

    @Synchronized
    fun loadIfacLease(eventId: String, channelId: String): RnsIfacLease? =
        loadIfacLeases(eventId).firstOrNull { it.channelId == channelId }

    @Synchronized
    fun loadIfacLeases(eventId: String): List<RnsIfacLease> {
        val json = readEncryptedJson(File(ifacDir, "${safeKey(eventId)}.dat"), "rns-ifac|$eventId") ?: return emptyList()
        val arr = json.optJSONArray("leases") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            runCatching {
                val obj = arr.getJSONObject(idx)
                val revDevArr = obj.optJSONArray("revoked_device_hashes")
                val revActArr = obj.optJSONArray("revoked_actors")
                RnsIfacLease(
                    eventId = obj.getString("event_id"),
                    channelId = obj.getString("channel_id"),
                    networkName = obj.getString("network_name"),
                    passphrase = obj.getString("passphrase"),
                    ifacSizeBytes = obj.optInt("ifac_size_bytes", 16),
                    requirePrivateIfac = obj.optBoolean("require_private_ifac", true),
                    generation = obj.optString("generation"),
                    expiresAtMs = obj.getLong("expires_at_ms"),
                    gatewayDestinationHashHex = obj.optString("gateway_dest_hash_hex").takeIf { it.isNotBlank() && it != "null" },
                    revokedDeviceHashes = List(revDevArr?.length() ?: 0) { revDevArr!!.getString(it) }.toSet(),
                    revokedActors = List(revActArr?.length() ?: 0) { revActArr!!.getString(it) }.toSet(),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun revokeInEvent(eventId: String, deviceHash: String? = null, actorId: String? = null) {
        val leases = loadIfacLeases(eventId)
        if (leases.isEmpty()) return
        leases.forEach { lease ->
            saveIfacLease(
                lease.copy(
                    revokedDeviceHashes = if (!deviceHash.isNullOrBlank()) lease.revokedDeviceHashes + deviceHash.lowercase() else lease.revokedDeviceHashes,
                    revokedActors = if (!actorId.isNullOrBlank()) lease.revokedActors + actorId else lease.revokedActors,
                ),
            )
        }
    }

    @Synchronized
    fun clearIfacLeases(eventId: String) {
        File(ifacDir, "${safeKey(eventId)}.dat").delete()
    }

    @Synchronized
    fun saveIdentityBinding(binding: RnsIdentityBinding) {
        val key = "${binding.accountKey}|${binding.eventId}"
        val json = JSONObject()
            .put("account_key", binding.accountKey)
            .put("event_id", binding.eventId)
            .put("actor_id", binding.actorId)
            .put("device_token_hash", binding.deviceTokenHash)
            .put("rns_identity_hash_hex", binding.rnsIdentityHashHex)
            .put("rns_destination_hash_hex", binding.rnsDestinationHashHex)
            .put("lxmf_destination_hash_hex", binding.lxmfDestinationHashHex)
            .put("ed25519_pub_b64", binding.ed25519PubB64)
            .put("x25519_pub_b64", binding.x25519PubB64)
            .put("ed25519_pub_hex", binding.ed25519PubHex)
            .put("binding_digest_hex", binding.bindingDigestHex)
            .put("bound_at_ms", binding.boundAtMs)
        writeEncryptedJson(File(bindingsDir, "${safeKey(key)}.dat"), "rns-binding|$key", json)
    }

    @Synchronized
    fun loadIdentityBinding(accountKey: String, eventId: String): RnsIdentityBinding? {
        val key = "$accountKey|$eventId"
        val json = readEncryptedJson(File(bindingsDir, "${safeKey(key)}.dat"), "rns-binding|$key") ?: return null
        return runCatching {
            RnsIdentityBinding(
                accountKey = json.getString("account_key"),
                eventId = json.getString("event_id"),
                actorId = json.getString("actor_id"),
                deviceTokenHash = json.getString("device_token_hash"),
                rnsIdentityHashHex = json.getString("rns_identity_hash_hex"),
                rnsDestinationHashHex = json.getString("rns_destination_hash_hex"),
                lxmfDestinationHashHex = json.optString("lxmf_destination_hash_hex"),
                ed25519PubB64 = json.optString("ed25519_pub_b64"),
                x25519PubB64 = json.optString("x25519_pub_b64"),
                ed25519PubHex = json.optString("ed25519_pub_hex"),
                bindingDigestHex = json.optString("binding_digest_hex"),
                boundAtMs = json.optLong("bound_at_ms"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearIdentityBinding(accountKey: String, eventId: String) {
        val key = "$accountKey|$eventId"
        File(bindingsDir, "${safeKey(key)}.dat").delete()
    }

    @Synchronized
    fun saveSyncCheckpoints(checkpoints: RnsSyncCheckpoints) {
        val key = "${checkpoints.accountKey}|${checkpoints.eventId}"
        val cursorsObj = JSONObject()
        checkpoints.channelCursors.forEach { (ch, cur) -> cursorsObj.put(ch, cur) }
        val json = JSONObject()
            .put("account_key", checkpoints.accountKey)
            .put("event_id", checkpoints.eventId)
            .put("workspace_revision", checkpoints.workspaceRevision)
            .put("channel_cursors", cursorsObj)
            .put("flight_notice_seq", checkpoints.flightNoticeSeq)
            .put("updated_at_ms", checkpoints.updatedAtMs)
        writeEncryptedJson(File(checkpointsDir, "${safeKey(key)}.dat"), "rns-checkpoints|$key", json)
    }

    @Synchronized
    fun loadSyncCheckpoints(accountKey: String, eventId: String): RnsSyncCheckpoints? {
        val key = "$accountKey|$eventId"
        val json = readEncryptedJson(File(checkpointsDir, "${safeKey(key)}.dat"), "rns-checkpoints|$key") ?: return null
        return runCatching {
            val cursorsObj = json.optJSONObject("channel_cursors")
            val cursors = buildMap {
                cursorsObj?.keys()?.forEach { k -> put(k, cursorsObj.optString(k)) }
            }
            RnsSyncCheckpoints(
                accountKey = json.getString("account_key"),
                eventId = json.getString("event_id"),
                workspaceRevision = json.optString("workspace_revision").takeIf { it.isNotBlank() && it != "null" },
                channelCursors = cursors,
                flightNoticeSeq = json.optLong("flight_notice_seq", 0L),
                updatedAtMs = json.optLong("updated_at_ms", 0L),
            )
        }.getOrNull()
    }

    @Synchronized
    fun saveResourceTransfer(state: RnsAttachmentTransferState) {
        val json = JSONObject()
            .put("attachment_id", state.attachmentId)
            .put("account_key", state.accountKey)
            .put("event_id", state.eventId)
            .put("channel_id", state.channelId)
            .put("task_id", state.taskId)
            .put("target_kind", state.targetKind)
            .put("media_type", state.mediaType)
            .put("caption", state.caption)
            .put("total_bytes", state.totalBytes)
            .put("poster_bytes", state.posterBytes)
            .put("duration_ms", state.durationMs ?: JSONObject.NULL)
            .put("sha256_content", state.sha256Content)
            .put("sha256_combined", state.sha256Combined)
            .put("chunk_size", state.chunkSize)
            .put("chunk_count", state.chunkCount)
            .put("acked_chunks", JSONArray(state.ackedChunks.sorted()))
            .put("status", state.status.name)
            .put("synced_to_server", state.syncedToServer)
            .put("updated_at_ms", state.updatedAtMs)
        writeEncryptedJson(
            File(transfersDir, "${safeKey(state.attachmentId)}.dat"),
            "rns-transfer|${state.attachmentId}",
            json,
        )
    }

    @Synchronized
    fun loadResourceTransfer(attachmentId: String): RnsAttachmentTransferState? {
        val json = readEncryptedJson(
            File(transfersDir, "${safeKey(attachmentId)}.dat"),
            "rns-transfer|$attachmentId",
        ) ?: return null
        return parseTransferJson(json)
    }

    @Synchronized
    fun allResourceTransfers(eventId: String? = null, channelId: String? = null): List<RnsAttachmentTransferState> =
        transfersDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".dat") }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".dat")
                readEncryptedJson(file, "rns-transfer|$id")?.let(::parseTransferJson)
            }
            .filter { (eventId == null || it.eventId == eventId) && (channelId == null || it.channelId == channelId) }
            .sortedBy { it.updatedAtMs }

    @Synchronized
    fun saveInboundChunk(attachmentId: String, chunkIndex: Int, data: ByteArray) {
        val dir = File(transfersDir, "chunks-${safeKey(attachmentId)}")
        check(dir.isDirectory || dir.mkdirs())
        val encrypted = cipher.encrypt(data, "rns-chunk|$attachmentId|$chunkIndex")
        File(dir, "$chunkIndex.bin").writeBytes(encrypted)
    }

    @Synchronized
    fun loadInboundChunk(attachmentId: String, chunkIndex: Int): ByteArray? {
        val file = File(File(transfersDir, "chunks-${safeKey(attachmentId)}"), "$chunkIndex.bin")
        if (!file.isFile) return null
        return runCatching { cipher.decrypt(file.readBytes(), "rns-chunk|$attachmentId|$chunkIndex") }.getOrNull()
    }

    @Synchronized
    fun clearInboundChunks(attachmentId: String) {
        File(transfersDir, "chunks-${safeKey(attachmentId)}").deleteRecursively()
    }

    private fun parseTransferJson(json: JSONObject): RnsAttachmentTransferState? = runCatching {
        val ackArr = json.optJSONArray("acked_chunks")
        val acked = List(ackArr?.length() ?: 0) { ackArr!!.getInt(it) }.toSet()
        RnsAttachmentTransferState(
            attachmentId = json.getString("attachment_id"),
            accountKey = json.optString("account_key"),
            eventId = json.getString("event_id"),
            channelId = json.optString("channel_id"),
            taskId = json.optString("task_id"),
            targetKind = json.getString("target_kind"),
            mediaType = json.getString("media_type"),
            caption = json.optString("caption"),
            totalBytes = json.getLong("total_bytes"),
            posterBytes = json.optInt("poster_bytes", 0),
            durationMs = if (json.isNull("duration_ms")) null else json.optInt("duration_ms"),
            sha256Content = json.getString("sha256_content"),
            sha256Combined = json.getString("sha256_combined"),
            chunkSize = json.optInt("chunk_size", 16_384),
            chunkCount = json.getInt("chunk_count"),
            ackedChunks = acked,
            status = LxmfDeliveryStatus.valueOf(json.optString("status", LxmfDeliveryStatus.QUEUED.name)),
            syncedToServer = json.optBoolean("synced_to_server"),
            updatedAtMs = json.optLong("updated_at_ms"),
        )
    }.getOrNull()

    @Synchronized
    fun upsertPeer(peer: RnsPeerRecord) {
        val existing = loadPeers().associateBy { it.destinationHashHex }.toMutableMap()
        existing[peer.destinationHashHex] = peer
        val arr = JSONArray()
        existing.values.sortedByDescending { it.lastHeardAtMs }.take(256).forEach { item ->
            arr.put(
                JSONObject()
                    .put("destination_hash_hex", item.destinationHashHex)
                    .put("identity_hash_hex", item.identityHashHex)
                    .put("app_data_text", item.appDataText)
                    .put("hops", item.hops)
                    .put("last_heard_at_ms", item.lastHeardAtMs),
            )
        }
        writeEncryptedJson(peersFile, "rns-peers", JSONObject().put("peers", arr))
    }

    @Synchronized
    fun loadPeers(): List<RnsPeerRecord> {
        val json = readEncryptedJson(peersFile, "rns-peers") ?: return emptyList()
        val arr = json.optJSONArray("peers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            runCatching {
                val obj = arr.getJSONObject(index)
                RnsPeerRecord(
                    destinationHashHex = obj.getString("destination_hash_hex"),
                    identityHashHex = obj.optString("identity_hash_hex"),
                    appDataText = obj.optString("app_data_text"),
                    hops = obj.optInt("hops", 1),
                    lastHeardAtMs = obj.optLong("last_heard_at_ms"),
                )
            }.getOrNull()
        }
    }

    private fun persistOutbox(record: LxmfOutboxRecord) {
        val json = JSONObject()
            .put("id", record.id)
            .put("account_key", record.accountKey)
            .put("event_id", record.eventId)
            .put("channel_id", record.channelId)
            .put("destination_hash_hex", record.destinationHashHex)
            .put("envelope_kind", record.envelopeKind.name)
            .put("title", record.title)
            .put("text", record.text)
            .put("recipient_actor", record.recipientActor)
            .put("assistance", record.assistance)
            .put("attachment_kind", record.attachmentKind.name)
            .put("attachment_bytes_b64", record.attachmentBytesB64)
            .put("delivery_mode", record.deliveryMode.name)
            .put("status", record.status.name)
            .put("attempt_count", record.attemptCount)
            .put("created_at_ms", record.createdAtMs)
            .put("updated_at_ms", record.updatedAtMs)
            .put("idempotency_key", record.idempotencyKey)
            .put("message_hash_hex", record.messageHashHex)
            .put("last_error", record.lastError)
        writeEncryptedJson(File(outboxDir, "${safeKey(record.id)}.dat"), "rns-outbox|${record.id}", json)
    }

    private fun loadOutboxRecord(file: File, expectedKey: String): LxmfOutboxRecord? {
        val json = readEncryptedJson(file, "rns-outbox|$expectedKey") ?: return null
        return runCatching {
            LxmfOutboxRecord(
                id = json.getString("id"),
                accountKey = json.getString("account_key"),
                eventId = json.getString("event_id"),
                channelId = json.getString("channel_id"),
                destinationHashHex = json.getString("destination_hash_hex"),
                envelopeKind = CommEnvelopeKind.valueOf(json.getString("envelope_kind")),
                title = json.optString("title"),
                text = json.optString("text"),
                recipientActor = json.optString("recipient_actor"),
                assistance = json.optBoolean("assistance"),
                attachmentKind = LxmfAttachmentKind.valueOf(json.optString("attachment_kind", LxmfAttachmentKind.NONE.name)),
                attachmentBytesB64 = json.optString("attachment_bytes_b64"),
                deliveryMode = LxmfDeliveryMode.valueOf(json.optString("delivery_mode", LxmfDeliveryMode.OPPORTUNISTIC.name)),
                status = LxmfDeliveryStatus.valueOf(json.getString("status")),
                attemptCount = json.optInt("attempt_count", 0),
                createdAtMs = json.getLong("created_at_ms"),
                updatedAtMs = json.getLong("updated_at_ms"),
                idempotencyKey = json.optString("idempotency_key", json.getString("id")),
                messageHashHex = json.optString("message_hash_hex"),
                lastError = json.optString("last_error"),
            )
        }.getOrNull()
    }

    private fun readEncryptedJson(file: File, aadContext: String): JSONObject? {
        if (!file.isFile) return null
        return runCatching {
            val encrypted = file.readBytes()
            val plain = cipher.decrypt(encrypted, aadContext).decodeToString()
            JSONObject(plain)
        }.getOrNull()
    }

    private fun writeEncryptedJson(file: File, aadContext: String, json: JSONObject) {
        val parent = file.parentFile
        if (parent != null) check(parent.isDirectory || parent.mkdirs()) { "Unable to create ${parent.path}" }
        val bytes = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), aadContext)
        if (isAndroidRuntime()) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        } else {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        }
    }

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true ||
            System.getProperty("java.vendor")?.contains("Android", ignoreCase = true) == true

    private fun safeKey(raw: String): String = raw.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
