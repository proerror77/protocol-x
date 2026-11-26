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
        writeEncryptedJson(configFile, "rns-runtime-config", json)
    }

    @Synchronized
    fun loadRuntimeConfig(): RnsRuntimeConfig? {
        val json = readEncryptedJson(configFile, "rns-runtime-config") ?: return null
        return runCatching {
            val connectArray = json.optJSONArray("tcp_connect_addrs")
            val connects = List(connectArray?.length() ?: 0) { idx -> connectArray!!.getString(idx) }
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
                    LxmfInboundRecord(
                        messageHashHex = json.getString("message_hash_hex"),
                        envelopeId = json.getString("envelope_id"),
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
                    )
                }.getOrNull()
            }
            .filter { (eventId == null || it.eventId == eventId) && (channelId == null || it.channelId == channelId) }
            .sortedBy { it.receivedAtMs }

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
