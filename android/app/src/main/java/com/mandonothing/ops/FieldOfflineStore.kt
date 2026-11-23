package com.mandonothing.ops

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal class FieldOfflineStore(context: Context, private val vault: FieldSessionStore) {
    private val root = File(context.filesDir, "field_offline")

    data class Queued(val id: String, val owner: String, val origin: String, val event: String,
                      val ref: JSONObject, val version: Int, val action: String, val parameters: JSONObject,
                      val conflict: String = "") {
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

    fun stage(client: OpsClient, event: String, ref: JSONObject, version: Int,
              action: String, parameters: JSONObject): Queued {
        val entry = Queued(UUID.randomUUID().toString(), client.profile.getString("actor"), client.origin, event,
            JSONObject(ref.toString()), version, action, JSONObject(parameters.toString()))
        persist(entry)
        return entry
    }

    fun pending(client: OpsClient): List<Queued> = root.listFiles().orEmpty()
        .filter { it.name.startsWith("pending-") && it.extension == "dat" }
        .sortedBy { it.lastModified() }
        .mapNotNull { read(it.nameWithoutExtension) }
        .map {
            Queued(it.getString("id"), it.getString("owner"), it.getString("origin"), it.getString("event"),
                it.getJSONObject("ref"), it.getInt("version"), it.getString("action"), it.getJSONObject("parameters"),
                it.optString("conflict"))
        }.filter { it.belongsTo(client) }

    fun conflict(entry: Queued, message: String) = persist(entry.copy(conflict = message))
    fun complete(entry: Queued) = file("pending-" + entry.id).delete()
    fun clearCache(client: OpsClient) {
        file("login-" + client.localSessionKey).delete()
        root.listFiles().orEmpty().filter {
            it.name.startsWith("work-" + client.localSessionKey + "-") ||
                it.name.startsWith("directory-" + client.localSessionKey + "-")
        }
            .forEach { file(it.nameWithoutExtension).delete() }
    }

    private fun persist(entry: Queued) = write("pending-" + entry.id, JSONObject()
        .put("id", entry.id).put("owner", entry.owner).put("origin", entry.origin).put("event", entry.event)
        .put("ref", entry.ref).put("version", entry.version).put("action", entry.action)
        .put("parameters", entry.parameters).put("conflict", entry.conflict))

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
