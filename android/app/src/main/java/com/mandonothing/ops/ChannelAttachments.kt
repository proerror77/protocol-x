package com.mandonothing.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal const val CHANNEL_PHOTO_BYTES = 750 * 1024
internal const val CHANNEL_VIDEO_BYTES = 16 * 1024 * 1024
internal const val CHANNEL_POSTER_BYTES = 48 * 1024
internal const val CHANNEL_PHOTO_TYPE = "image/jpeg"
internal const val CHANNEL_VIDEO_TYPE = "video/mp4"
private const val SHORT_VIDEO_MS = 30_000

internal class ChannelAttachmentFiles(private val context: Context) {
    private val root = File(context.filesDir, "channel-attachments")

    data class Draft(val directory: File, val type: String, val durationMs: Int? = null) {
        val content: File get() = File(directory, "content")
        val poster: File get() = File(directory, "poster.jpg")
    }

    data class Entry(val id: String, val event: String, val channel: String, val caption: String,
                     val draft: Draft, val createdAt: String, val failure: String) {
        fun item() = JSONObject().put("id", id).put("created_at", createdAt).put("caption", caption)
            .put("media_type", draft.type).put("duration_ms", draft.durationMs)
    }

    suspend fun prepare(uri: Uri, video: Boolean): Draft {
        check(root.isDirectory || root.mkdirs()) { "无法保存频道附件" }
        val folder = File(root, "draft-" + UUID.randomUUID())
        check(folder.mkdir()) { "无法保存频道附件" }
        val draft = Draft(folder, if (video) CHANNEL_VIDEO_TYPE else CHANNEL_PHOTO_TYPE)
        try {
            return if (video) prepareVideo(uri, draft) else draft.also { preparePhoto(uri, it) }
        } catch (failure: Throwable) {
            folder.deleteRecursively()
            throw failure
        }
    }

    private fun preparePhoto(uri: Uri, draft: Draft) {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val (width, height) = fitMedia(size.width, size.height, 1600)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else FieldImages.read({ context.contentResolver.openInputStream(uri) }, 1600)
        checkNotNull(bitmap) { "无法读取这张照片" }
        val (width, height) = fitMedia(bitmap.width, bitmap.height, 1600)
        val photo = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            writeJpeg(photo, draft.content, CHANNEL_PHOTO_BYTES)
            writePoster(photo, draft.poster)
        } finally {
            if (photo !== bitmap) photo.recycle()
            bitmap.recycle()
        }
    }

    private suspend fun prepareVideo(uri: Uri, draft: Draft): Draft {
        val stream = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取这段影片" }
        stream.use { input ->
            draft.content.outputStream().use { output ->
                copyChannelMedia(input, output, CHANNEL_VIDEO_BYTES, "短影片最多 16 MB")
            }
        }
        draft.content.inputStream().use {
            val header = ByteArray(8)
            java.io.DataInputStream(it).readFully(header)
            check(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "请选择 MP4 短影片" }
        }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(draft.content.absolutePath)
            val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
            check(duration != null && duration in 1..SHORT_VIDEO_MS) { "短影片最多 30 秒" }
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            check(width > 0 && height > 0) { "请选择可播放的 MP4 短影片" }
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val (posterWidth, posterHeight) = if (rotation == "90" || rotation == "270") fitMedia(height, width, 480)
                else fitMedia(width, height, 480)
            val frame = checkNotNull(if (Build.VERSION.SDK_INT >= 27)
                metadata.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, posterWidth, posterHeight)
                else metadata.frameAtTime) { "无法读取影片封面" }
            try {
                writePoster(frame, draft.poster)
            } finally {
                frame.recycle()
            }
            return draft.copy(durationMs = duration)
        } finally {
            metadata.release()
        }
    }

    fun queue(draft: Draft, owner: String, origin: String, event: String, channel: String, caption: String): Entry {
        val id = draft.directory.name.removePrefix("draft-")
        val ready = File(root, id)
        val created = java.time.Instant.now().toString()
        writeMetadata(draft.directory, JSONObject().put("id", id).put("actor", owner).put("origin", origin)
            .put("event", event).put("channel", channel).put("caption", caption).put("type", draft.type)
            .put("duration_ms", draft.durationMs).put("created_at", created))
        check(draft.directory.renameTo(ready)) { "无法加入发送队列，请重试" }
        return Entry(id, event, channel, caption, draft.copy(directory = ready), created, "")
    }

    fun pending(owner: String, origin: String): List<Entry> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith("draft-") }
        .mapNotNull { directory ->
            runCatching {
                val item = metadata(directory)
                if (item.getString("actor") != owner || item.getString("origin") != origin) return@mapNotNull null
                val draft = Draft(directory, item.getString("type"), item.optInt("duration_ms").takeIf { it > 0 })
                check(draft.content.isFile && draft.poster.isFile)
                Entry(directory.name, item.getString("event"), item.getString("channel"), item.getString("caption"),
                    draft, item.getString("created_at"), item.optString("failure"))
            }.getOrNull()
        }.sortedBy(Entry::createdAt)

    fun setFailure(entry: Entry, message: String) {
        writeMetadata(entry.draft.directory, metadata(entry.draft.directory).put("failure", message))
    }

    fun remove(draft: Draft) = draft.directory.deleteRecursively()

    private fun metadata(directory: File) =
        JSONObject(AtomicFile(File(directory, "message.json")).readFully().decodeToString())

    private fun writeMetadata(directory: File, item: JSONObject) {
        val file = AtomicFile(File(directory, "message.json"))
        val stream = file.startWrite()
        try {
            stream.write(item.toString().toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun writePoster(bitmap: Bitmap, destination: File) {
        val (width, height) = fitMedia(bitmap.width, bitmap.height, 480)
        val thumb = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            writeJpeg(thumb, destination, CHANNEL_POSTER_BYTES)
        } finally {
            if (thumb !== bitmap) thumb.recycle()
        }
    }

    private fun writeJpeg(bitmap: Bitmap, destination: File, limit: Int) {
        val output = ByteArrayOutputStream()
        for (quality in listOf(84, 72, 58, 44, 32)) {
            output.reset()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "无法压缩图片" }
            if (output.size() <= limit) {
                destination.outputStream().use { output.writeTo(it) }
                return
            }
        }
        error("压缩后的图片仍然太大，请换一张")
    }
}

private fun fitMedia(width: Int, height: Int, limit: Int): Pair<Int, Int> {
    val max = maxOf(width, height)
    if (max <= limit) return width to height
    return (width.toLong() * limit / max).toInt().coerceAtLeast(1) to
        (height.toLong() * limit / max).toInt().coerceAtLeast(1)
}

internal suspend fun copyChannelMedia(input: InputStream, output: OutputStream, limit: Int, tooLarge: String) {
    val buffer = ByteArray(64 * 1024)
    var size = 0
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        size += count
        check(size <= limit) { tooLarge }
        output.write(buffer, 0, count)
    }
    check(size > 0) { "附件为空" }
}
