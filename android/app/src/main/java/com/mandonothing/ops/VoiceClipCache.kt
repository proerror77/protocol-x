package com.mandonothing.ops

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

internal class VoiceClipCache private constructor(context: Context, private val vault: FieldSessionStore) {
    private val directory = File(context.filesDir, "ptt-originals")
    private val legacyDirectory = File(context.cacheDir, "ptt-originals")

    fun key(client: OpsClient, event: String, channel: RadioTarget?, track: String) =
        client.localSessionKey + "-" + fingerprint("$event|${channel?.id.orEmpty()}|$track")

    @Synchronized fun read(key: String): ByteArray? {
        val file = File(directory, "$key.bin").takeIf(File::isFile) ?: File(legacyDirectory, "$key.bin")
        if (!file.isFile) return null
        if (expired(file) || file.length() !in 45..(MAX_CLIP_BYTES + 28L)) {
            AtomicFile(file).delete()
            return null
        }
        return runCatching { vault.decrypt(AtomicFile(file).readFully(), key) }
            .getOrElse { AtomicFile(file).delete(); null }
    }

    @Synchronized fun save(key: String, data: ByteArray) {
        if (data.size !in 45..MAX_CLIP_BYTES) return
        if (!directory.isDirectory && !directory.mkdirs()) return
        val target = AtomicFile(File(directory, "$key.bin"))
        val encrypted = vault.encrypt(data, key)
        val output = target.startWrite()
        try {
            output.write(encrypted)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
        val retained = directory.listFiles().orEmpty().filter { it.extension == "bin" }.sortedBy { it.lastModified() }
        var bytes = retained.sumOf(File::length)
        for (file in retained) {
            if (bytes <= TOTAL_BYTES && !expired(file)) continue
            bytes -= file.length()
            AtomicFile(file).delete()
        }
    }

    @Synchronized fun clear(client: OpsClient) {
        val prefix = client.localSessionKey + "-"
        for (dir in listOf(directory, legacyDirectory)) {
            dir.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.extension == "bin" }
                .forEach { AtomicFile(it).delete() }
        }
    }

    private fun expired(file: File) = System.currentTimeMillis() - file.lastModified() > RETENTION_MS

    companion object {
        const val MAX_CLIP_BYTES = 44 + 16_000 * 2 * 90
        private const val TOTAL_BYTES = 24L * 1024 * 1024
        @Volatile private var shared: VoiceClipCache? = null
        fun forApp(context: Context) = shared ?: synchronized(this) {
            shared ?: VoiceClipCache(context.applicationContext, FieldSessionStore(context.applicationContext)).also { shared = it }
        }
        private const val RETENTION_MS = 24L * 60 * 60 * 1000
    }
}

internal class CapturedClip(source: InputStream) : FilterInputStream(source) {
    private val copy = ByteArrayOutputStream()

    override fun read(): Int = super.read().also {
        if (it >= 0) {
            check(copy.size() < VoiceClipCache.MAX_CLIP_BYTES)
            copy.write(it)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = super.read(buffer, offset, count).also {
        if (it > 0) {
            check(copy.size() + it <= VoiceClipCache.MAX_CLIP_BYTES)
            copy.write(buffer, offset, it)
        }
    }

    fun bytes() = copy.toByteArray()
}
