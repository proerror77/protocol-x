package com.mandonothing.ops

import android.content.Context
import android.util.AtomicFile
import com.mandonothing.ops.comm.rns.RnsEnvelopeCodec
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal class FieldOfflineStore(context: Context, private val vault: FieldSessionStore) {
    private val root = File(context.filesDir, "field_offline")

    data class Queued(
        val id: String,
        val owner: String,
        val origin: String,
        val event: String,
        val ref: JSONObject,
        val version: Int,
        val action: String,
        val parameters: JSONObject,
        val conflict: String = "",
        val clientSeq: Long = 0L,
        val baseVersion: Int = version,
        val idempotencyKey: String = id,
        val fingerprint: String = "",
        val envelopeId: String = id,
        val deliveryState: String = "LOCAL_STAGED",
        val conflictDetailJson: String = "",
    ) {
        fun belongsTo(client: OpsClient) = owner == client.profile.optString("actor") && origin == client.origin
    }

    fun saveBootstrap(client: OpsClient, bootstrap: JSONObject) {
        write("login-" + client.localSessionKey, bootstrap)
    }

    fun bootstrap(client: OpsClient) = read("login-" + client.localSessionKey)

    fun saveDirectory(client: OpsClient, event: String, directory: JSONObject) =
        write("directory-" + client.localSessionKey + "-" + fingerprint(event), directory)

    fun directory(client: OpsClient, event: String) =
        read("directory-" + client.localSessionKey + "-" + fingerprint(event))

    fun saveWork(client: OpsClient, event: String, workspace: JSONObject) {
        write(workName(client, event), JSONObject().put("saved_at", Instant.now().toString())
            .put("tasks", workspace.getJSONArray("tasks")).put("receipts", workspace.getJSONArray("receipts")))
    }

    fun work(client: OpsClient, event: String) = read(workName(client, event))

    fun stage(
        client: OpsClient,
        event: String,
        ref: JSONObject,
        version: Int,
        action: String,
        parameters: JSONObject,
    ): Queued {
        val owner = client.profile.getString("actor")
        val existing = pending(client).filter {
            it.event == event &&
                RnsEnvelopeCodec.canonicalJsonDump(it.ref) == RnsEnvelopeCodec.canonicalJsonDump(ref) &&
                it.action != "help"
        }
        val nextSeq = (existing.maxOfOrNull { it.clientSeq } ?: 0L) + 1L
        val chainedVersion = if (action == "help" || existing.isEmpty()) version
        else maxOf(version, existing.maxOf { it.version } + 1)
        val id = UUID.randomUUID().toString()
        val fp = RnsEnvelopeCodec.sha256Hex(
            RnsEnvelopeCodec.canonicalJsonDump(
                JSONObject()
                    .put("event", event)
                    .put("owner", owner)
                    .put("ref", ref)
                    .put("version", chainedVersion)
                    .put("action", action)
                    .put("parameters", parameters),
            ),
        )
        val entry = Queued(
            id = id,
            owner = owner,
            origin = client.origin,
            event = event,
            ref = JSONObject(ref.toString()),
            version = chainedVersion,
            action = action,
            parameters = JSONObject(parameters.toString()),
            clientSeq = nextSeq,
            baseVersion = version,
            idempotencyKey = id,
            fingerprint = fp,
            envelopeId = id,
            deliveryState = "LOCAL_STAGED",
        )
        persist(entry)
        return entry
    }

    fun pending(client: OpsClient): List<Queued> = root.listFiles().orEmpty()
        .filter { it.name.startsWith("pending-") && it.extension == "dat" }
        .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        .mapNotNull { read(it.nameWithoutExtension) }
        .map {
            val id = it.getString("id")
            val ver = it.getInt("version")
            Queued(
                id = id,
                owner = it.getString("owner"),
                origin = it.getString("origin"),
                event = it.getString("event"),
                ref = it.getJSONObject("ref"),
                version = ver,
                action = it.getString("action"),
                parameters = it.getJSONObject("parameters"),
                conflict = it.optString("conflict"),
                clientSeq = it.optLong("client_seq", 0L),
                baseVersion = it.optInt("base_version", ver),
                idempotencyKey = it.optString("idempotency_key", id).ifBlank { id },
                fingerprint = it.optString("fingerprint"),
                envelopeId = it.optString("envelope_id", id).ifBlank { id },
                deliveryState = it.optString("delivery_state", "LOCAL_STAGED").ifBlank { "LOCAL_STAGED" },
                conflictDetailJson = it.optString("conflict_detail_json"),
            )
        }.filter { it.belongsTo(client) }
        .sortedWith(compareBy<Queued>({ it.clientSeq }, { it.version }, { it.id }))

    fun updateDeliveryState(entry: Queued, state: String): Queued =
        entry.copy(deliveryState = state).also(::persist)

    fun conflict(entry: Queued, message: String, conflictDetailJson: String = "") =
        persist(entry.copy(conflict = message, deliveryState = "CONFLICT_HOLD", conflictDetailJson = conflictDetailJson))

    fun complete(entry: Queued) = file("pending-" + entry.id).delete()

    fun saveCheckpoints(
        client: OpsClient,
        event: String,
        workspaceRevision: String? = null,
        channelCursor: String? = null,
        flightNoticeSeq: Long? = null,
    ) {
        val existing = checkpoints(client, event) ?: JSONObject()
        if (workspaceRevision != null) existing.put("workspace_revision", workspaceRevision)
        if (channelCursor != null) existing.put("channel_cursor", channelCursor)
        if (flightNoticeSeq != null) existing.put("flight_notice_seq", flightNoticeSeq)
        existing.put("saved_at", Instant.now().toString())
        write("ckpt-" + client.localSessionKey + "-" + fingerprint(event), existing)
    }

    fun checkpoints(client: OpsClient, event: String): JSONObject? =
        read("ckpt-" + client.localSessionKey + "-" + fingerprint(event))

    fun clearCache(client: OpsClient) {
        file("login-" + client.localSessionKey).delete()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("work-" + client.localSessionKey + "-") ||
                it.name.startsWith("directory-" + client.localSessionKey + "-") ||
                it.name.startsWith("ckpt-" + client.localSessionKey + "-")
        }
            .forEach { file(it.nameWithoutExtension).delete() }
    }

    private fun persist(entry: Queued) = write(
        "pending-" + entry.id,
        JSONObject()
            .put("id", entry.id)
            .put("owner", entry.owner)
            .put("origin", entry.origin)
            .put("event", entry.event)
            .put("ref", entry.ref)
            .put("version", entry.version)
            .put("action", entry.action)
            .put("parameters", entry.parameters)
            .put("conflict", entry.conflict)
            .put("client_seq", entry.clientSeq)
            .put("base_version", entry.baseVersion)
            .put("idempotency_key", entry.idempotencyKey)
            .put("fingerprint", entry.fingerprint)
            .put("envelope_id", entry.envelopeId)
            .put("delivery_state", entry.deliveryState)
            .put("conflict_detail_json", entry.conflictDetailJson),
    )

    private fun workName(client: OpsClient, event: String) = "work-" + client.localSessionKey + "-" + fingerprint(event)
    private fun file(name: String) = AtomicFile(File(root, "$name.dat"))

    private fun write(name: String, data: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        val bytes = vault.encrypt(data.toString().toByteArray(), name)
        val target = file(name)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun read(name: String): JSONObject? = runCatching {
        JSONObject(vault.decrypt(file(name).readFully(), name).decodeToString())
    }.getOrNull()
}

internal fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
