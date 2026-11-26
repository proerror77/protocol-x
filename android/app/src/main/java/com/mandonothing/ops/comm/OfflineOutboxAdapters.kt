package com.mandonothing.ops.comm

import android.net.Uri
import com.mandonothing.ops.ChannelAttachmentFiles
import com.mandonothing.ops.FieldOfflineStore
import com.mandonothing.ops.OpsClient
import com.mandonothing.ops.PhotoOutbox
import org.json.JSONObject

/**
 * Outbox/offline-store adapters exposing existing persistent queues through RNS-02 contracts
 * without changing encrypted storage formats.
 */
internal class FieldOfflineQueueAdapter(
    private val store: FieldOfflineStore,
) {
    fun saveBootstrap(client: OpsClient, bootstrap: JSONObject) = store.saveBootstrap(client, bootstrap)
    fun bootstrap(client: OpsClient): JSONObject? = store.bootstrap(client)
    fun saveDirectory(client: OpsClient, event: String, directory: JSONObject) =
        store.saveDirectory(client, event, directory)

    fun directory(client: OpsClient, event: String): JSONObject? = store.directory(client, event)
    fun saveWork(client: OpsClient, event: String, workspace: JSONObject) =
        store.saveWork(client, event, workspace)

    fun work(client: OpsClient, event: String): JSONObject? = store.work(client, event)

    fun stage(
        client: OpsClient,
        event: String,
        ref: JSONObject,
        version: Int,
        action: String,
        parameters: JSONObject,
    ): FieldOfflineStore.Queued = store.stage(client, event, ref, version, action, parameters)

    fun pending(client: OpsClient): List<FieldOfflineStore.Queued> = store.pending(client)
    fun updateDeliveryState(entry: FieldOfflineStore.Queued, state: String): FieldOfflineStore.Queued =
        store.updateDeliveryState(entry, state)

    fun conflict(entry: FieldOfflineStore.Queued, message: String, conflictDetailJson: String = "") =
        store.conflict(entry, message, conflictDetailJson)

    fun complete(entry: FieldOfflineStore.Queued) = store.complete(entry)
    fun saveCheckpoints(
        client: OpsClient,
        event: String,
        workspaceRevision: String? = null,
        channelCursor: String? = null,
        flightNoticeSeq: Long? = null,
    ) = store.saveCheckpoints(client, event, workspaceRevision, channelCursor, flightNoticeSeq)

    fun checkpoints(client: OpsClient, event: String): JSONObject? = store.checkpoints(client, event)
    fun clearCache(client: OpsClient) = store.clearCache(client)
}

internal class PhotoOutboxAdapter(
    private val outbox: PhotoOutbox,
) : CommOfflineQueue<PhotoOutbox.Entry> {
    fun add(owner: String, origin: String, event: String, task: String, bytes: ByteArray): PhotoOutbox.Entry =
        outbox.add(owner, origin, event, task, bytes)

    override fun pending(owner: String, origin: String): List<PhotoOutbox.Entry> =
        outbox.pending(owner, origin)

    override fun complete(entry: PhotoOutbox.Entry): Boolean {
        outbox.complete(entry)
        return true
    }
}

internal class ChannelAttachmentOutboxAdapter(
    private val files: ChannelAttachmentFiles,
) : CommOfflineQueue<ChannelAttachmentFiles.Entry> {
    suspend fun prepare(uri: Uri, video: Boolean): ChannelAttachmentFiles.Draft = files.prepare(uri, video)

    fun queue(
        draft: ChannelAttachmentFiles.Draft,
        owner: String,
        origin: String,
        event: String,
        channel: String,
        caption: String,
    ): ChannelAttachmentFiles.Entry = files.queue(draft, owner, origin, event, channel, caption)

    fun setFailure(entry: ChannelAttachmentFiles.Entry, message: String) = files.setFailure(entry, message)

    fun remove(draft: ChannelAttachmentFiles.Draft): Boolean = files.remove(draft)

    override fun pending(owner: String, origin: String): List<ChannelAttachmentFiles.Entry> =
        files.pending(owner, origin)

    override fun complete(entry: ChannelAttachmentFiles.Entry): Boolean =
        files.remove(entry.draft)

    fun pendingEnvelopes(owner: String, origin: String): List<CommAttachmentEnvelope> =
        files.pending(owner, origin).map { entry ->
            CommAttachmentEnvelope(
                id = entry.id,
                eventId = entry.event,
                channelId = entry.channel,
                mediaType = entry.draft.type,
                caption = entry.caption,
                durationMs = entry.draft.durationMs,
                createdAtIso = entry.createdAt,
                deliveryState = if (entry.failure.isBlank()) CommDeliveryState.QUEUED else CommDeliveryState.FAILED,
                failureMessage = entry.failure,
                localPosterPath = entry.draft.poster.absolutePath,
                localContentPath = entry.draft.content.absolutePath,
                isLocalPending = true,
            )
        }
}
