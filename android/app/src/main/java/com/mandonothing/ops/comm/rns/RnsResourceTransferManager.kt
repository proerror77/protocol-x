package com.mandonothing.ops.comm.rns

import com.mandonothing.ops.comm.CommEnvelopeKind
import com.mandonothing.ops.comm.CommOutboundMessage
import com.mandonothing.ops.comm.CommScope
import com.mandonothing.ops.comm.CommSendResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * RNS-07 resumable LXMF Resource chunk manager for multi-KB task photos,
 * channel JPEG attachments (up to 750 KB), and MP4 short videos (up to 16 MB).
 *
 * Tracks chunk progress in [RnsLocalStore] so interrupted transfers resume from the
 * first unacknowledged chunk instead of re-sending already delivered slices.
 */
internal class RnsResourceTransferManager(
    private val store: RnsLocalStore,
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(chunkSizeBytes >= 256) { "chunkSizeBytes must be >= 256" }
    }

    data class TransferPayload(
        val attachmentId: String,
        val accountKey: String,
        val eventId: String,
        val channelId: String,
        val taskId: String = "",
        val targetKind: String,
        val mediaType: String,
        val caption: String = "",
        val posterBytes: ByteArray = ByteArray(0),
        val contentBytes: ByteArray,
        val durationMs: Int? = null,
    )

    data class ReassembledResource(
        val attachmentId: String,
        val state: RnsAttachmentTransferState,
        val posterBytes: ByteArray,
        val contentBytes: ByteArray,
    )

    /**
     * Registers or resumes a chunked attachment transfer and dispatches all remaining chunks
     * in order through [sendChunk] (typically backed by [RnsRuntimeCoordinator]).
     */
    fun sendOrResume(
        payload: TransferPayload,
        scope: CommScope,
        destinationHashHex: String,
        sendChunk: (CommOutboundMessage, String) -> CommSendResult,
    ): RnsAttachmentTransferState {
        val shaContent = RnsEnvelopeCodec.sha256Hex(payload.contentBytes)
        val combined = ByteArray(payload.posterBytes.size + payload.contentBytes.size)
        System.arraycopy(payload.posterBytes, 0, combined, 0, payload.posterBytes.size)
        System.arraycopy(payload.contentBytes, 0, combined, payload.posterBytes.size, payload.contentBytes.size)
        val shaCombined = "${payload.posterBytes.size}:${RnsEnvelopeCodec.sha256Hex(combined)}"
        val chunkCount = maxOf(1, (payload.contentBytes.size + chunkSizeBytes - 1) / chunkSizeBytes)

        var state = store.loadResourceTransfer(payload.attachmentId)?.takeIf {
            it.sha256Content == shaContent && it.chunkCount == chunkCount
        } ?: RnsAttachmentTransferState(
            attachmentId = payload.attachmentId,
            accountKey = payload.accountKey,
            eventId = payload.eventId,
            channelId = payload.channelId,
            taskId = payload.taskId,
            targetKind = payload.targetKind,
            mediaType = payload.mediaType,
            caption = payload.caption,
            totalBytes = payload.contentBytes.size.toLong(),
            posterBytes = payload.posterBytes.size,
            durationMs = payload.durationMs,
            sha256Content = shaContent,
            sha256Combined = shaCombined,
            chunkSize = chunkSizeBytes,
            chunkCount = chunkCount,
            ackedChunks = emptySet(),
            status = LxmfDeliveryStatus.QUEUED,
            updatedAtMs = clockMs(),
        ).also(store::saveResourceTransfer)

        while (!state.isCompleteTransfer()) {
            val idx = state.nextChunkIndex() ?: break
            val start = idx * chunkSizeBytes
            val end = minOf(payload.contentBytes.size, start + chunkSizeBytes)
            val slice = payload.contentBytes.copyOfRange(start, end)
            val isPhoto = payload.mediaType == "image/jpeg" || payload.targetKind == "task_photo"
            val manifest = JSONObject()
                .put("attachment_id", payload.attachmentId)
                .put("target_kind", payload.targetKind)
                .put("task_id", payload.taskId)
                .put("media_type", payload.mediaType)
                .put("caption", payload.caption)
                .put("total_bytes", payload.contentBytes.size)
                .put("poster_bytes", payload.posterBytes.size)
                .put("duration_ms", payload.durationMs ?: JSONObject.NULL)
                .put("sha256_content", shaContent)
                .put("sha256_combined", shaCombined)
                .put("chunk_index", idx)
                .put("chunk_count", chunkCount)
                .put("chunk_size", chunkSizeBytes)
            if (idx == 0 && payload.posterBytes.isNotEmpty()) {
                manifest.put("poster_b64", Base64.getEncoder().encodeToString(payload.posterBytes))
            }
            val chunkMessageId = "${payload.attachmentId}#chunk:$idx"
            val outbound = CommOutboundMessage(
                id = chunkMessageId,
                scope = scope,
                kind = CommEnvelopeKind.ATTACHMENT_MEDIA,
                text = manifest.toString(),
                photoBytes = if (isPhoto) slice else null,
                audioBytes = if (!isPhoto) slice else null,
                idempotencyKey = chunkMessageId,
            )
            val result = sendChunk(outbound, destinationHashHex)
            if (result is CommSendResult.Accepted) {
                val nextAcked = state.ackedChunks + idx
                val complete = nextAcked.size >= chunkCount
                state = state.copy(
                    ackedChunks = nextAcked,
                    status = if (complete) LxmfDeliveryStatus.SENT else LxmfDeliveryStatus.SENDING,
                    updatedAtMs = clockMs(),
                )
                store.saveResourceTransfer(state)
            } else {
                state = state.copy(
                    status = LxmfDeliveryStatus.QUEUED,
                    updatedAtMs = clockMs(),
                )
                store.saveResourceTransfer(state)
                break
            }
        }
        return state
    }

    /**
     * Persists an inbound chunk frame and reassembles + verifies the full payload once all
     * chunks `[0 .. chunkCount - 1]` have arrived. Returns null until complete (or on digest mismatch).
     */
    fun acceptInboundChunk(manifestJson: String, chunkBytes: ByteArray): ReassembledResource? {
        val manifest = runCatching { JSONObject(manifestJson) }.getOrNull() ?: return null
        val attachmentId = manifest.optString("attachment_id").takeIf { it.isNotBlank() } ?: return null
        val chunkIndex = manifest.optInt("chunk_index", -1)
        val chunkCount = manifest.optInt("chunk_count", 0)
        if (chunkIndex !in 0 until chunkCount) return null

        store.saveInboundChunk(attachmentId, chunkIndex, chunkBytes)
        manifest.optString("poster_b64").takeIf { it.isNotBlank() }?.let { posterB64 ->
            runCatching { Base64.getDecoder().decode(posterB64) }.getOrNull()?.let { poster ->
                store.saveInboundChunk(attachmentId, POSTER_SLOT, poster)
            }
        }

        val existing = store.loadResourceTransfer(attachmentId)
        val updated = (existing ?: RnsAttachmentTransferState(
            attachmentId = attachmentId,
            accountKey = "",
            eventId = manifest.optString("event_id"),
            channelId = manifest.optString("channel_id"),
            taskId = manifest.optString("task_id"),
            targetKind = manifest.optString("target_kind"),
            mediaType = manifest.optString("media_type"),
            caption = manifest.optString("caption"),
            totalBytes = manifest.optLong("total_bytes"),
            posterBytes = manifest.optInt("poster_bytes"),
            durationMs = if (manifest.isNull("duration_ms")) null else manifest.optInt("duration_ms"),
            sha256Content = manifest.optString("sha256_content"),
            sha256Combined = manifest.optString("sha256_combined"),
            chunkSize = manifest.optInt("chunk_size", chunkSizeBytes),
            chunkCount = chunkCount,
            ackedChunks = emptySet(),
            status = LxmfDeliveryStatus.SENDING,
            updatedAtMs = clockMs(),
        )).let { base ->
            val nextAcked = base.ackedChunks + chunkIndex
            base.copy(
                ackedChunks = nextAcked,
                status = if (nextAcked.size >= chunkCount) LxmfDeliveryStatus.DELIVERED else LxmfDeliveryStatus.SENDING,
                updatedAtMs = clockMs(),
            )
        }
        store.saveResourceTransfer(updated)
        if (!updated.isCompleteTransfer()) return null

        val out = ByteArrayOutputStream()
        for (i in 0 until chunkCount) {
            val part = store.loadInboundChunk(attachmentId, i) ?: return null
            out.write(part)
        }
        val assembledContent = out.toByteArray()
        val computedSha = RnsEnvelopeCodec.sha256Hex(assembledContent)
        if (updated.sha256Content.isNotBlank() && computedSha != updated.sha256Content) {
            store.saveResourceTransfer(updated.copy(status = LxmfDeliveryStatus.FAILED, updatedAtMs = clockMs()))
            return null
        }
        val poster = store.loadInboundChunk(attachmentId, POSTER_SLOT) ?: ByteArray(0)
        return ReassembledResource(
            attachmentId = attachmentId,
            state = updated,
            posterBytes = poster,
            contentBytes = assembledContent,
        )
    }

    fun writePosterAndContentFiles(resource: ReassembledResource, destDir: File): Pair<File?, File> {
        check(destDir.isDirectory || destDir.mkdirs())
        val contentFile = File(destDir, "${resource.attachmentId}.content")
        contentFile.writeBytes(resource.contentBytes)
        val posterFile = if (resource.posterBytes.isNotEmpty()) {
            File(destDir, "${resource.attachmentId}.poster.jpg").apply { writeBytes(resource.posterBytes) }
        } else null
        return posterFile to contentFile
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 16_384
        private const val POSTER_SLOT = -1
    }
}
