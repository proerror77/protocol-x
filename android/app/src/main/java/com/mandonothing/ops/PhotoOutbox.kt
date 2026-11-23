package com.mandonothing.ops

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class PhotoOutbox(context: Context) {
    private val directory = File(context.filesDir, "photo-outbox")

    data class Entry(val id: String, val owner: String, val origin: String, val event: String,
                     val task: String, val jpeg: String)

    fun add(owner: String, origin: String, event: String, task: String, bytes: ByteArray): Entry {
        check(directory.isDirectory || directory.mkdirs())
        val entry = Entry(UUID.randomUUID().toString(), owner, origin, event, task,
            Base64.encodeToString(bytes, Base64.NO_WRAP))
        val file = AtomicFile(File(directory, entry.id + ".json"))
        val stream = file.startWrite()
        try {
            val data = JSONObject().put("id", entry.id).put("owner", owner).put("origin", origin)
                .put("event", event).put("task", task).put("jpeg", entry.jpeg)
            stream.write(data.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return entry
    }

    fun pending(owner: String, origin: String): List<Entry> =
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    val data = JSONObject(AtomicFile(file).readFully().decodeToString())
                    if (data.getString("owner") != owner || data.getString("origin") != origin) null
                    else Entry(data.getString("id"), owner, origin, data.getString("event"),
                        data.getString("task"), data.getString("jpeg"))
                }.getOrNull()
            }

    fun complete(entry: Entry) {
        AtomicFile(File(directory, entry.id + ".json")).delete()
    }
}
