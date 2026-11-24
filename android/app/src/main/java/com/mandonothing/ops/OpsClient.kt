package com.mandonothing.ops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class OpsClient(address: String, private val accessCode: String, allowLocalHttp: Boolean, private val callsign: String) {
    private val base = URI(address.trim()).also { uri ->
        val local = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.scheme == "https" || (allowLocalHttp && local && uri.scheme == "http"))
    }.toString().trimEnd('/')

    val origin: String get() = base
    val bound: Boolean get() = accessCode.startsWith("pxd-")
    val localSessionKey: String by lazy { fingerprint(origin + "|" + accessCode) }
    var channelFeedSupported = true
        private set

    suspend fun inspectInvite(code: String) = request("/api/field/invitation", JSONObject().put("code", code))
    suspend fun bind(code: String) = request("/api/field/bind", JSONObject().put("code", code))
    suspend fun passkeyStatus() = request("/api/field/passkeys")
    suspend fun passkeyOptions(invite: String?) = if (invite == null)
        request("/api/field/passkeys/login/options", JSONObject())
    else request("/api/field/passkeys/register/options", JSONObject().put("code", invite))

    suspend fun verifyPasskey(ceremony: String, credential: JSONObject, invite: String?) =
        request(if (invite == null) "/api/field/passkeys/login/verify" else "/api/field/passkeys/register/verify",
            JSONObject().put("ceremony", ceremony).put("credential", credential).apply {
                if (invite != null) put("invite_code", invite)
            })

    suspend fun fieldLogout() = request("/api/field/logout", JSONObject())

    suspend fun directory(eventId: String) = request(fieldPath(eventId, "directory")).also {
        privateCallsEnabled = it.optBoolean("private_calls_enabled", privateCallsEnabled)
    }
    suspend fun nearbyCredentials(event: String, publicKey: String, agreementPublicKey: String? = null): JSONObject {
        val path = fieldPath(event, "nearby")
        val body = JSONObject().put("public_key", publicKey)
        if (agreementPublicKey != null) body.put("agreement_key", agreementPublicKey)
        return try {
            request(path, body, timeoutMs = 5_000)
        } catch (error: OpsException) {
            if (agreementPublicKey == null || error.status != 422 || !error.unknownAgreementKey) throw error
            body.remove("agreement_key")
            request(path, body, timeoutMs = 5_000)
        }
    }

    suspend fun createChannel(eventId: String, name: String) = request(fieldPath(eventId, "channels"), JSONObject().put("name", name))
    suspend fun channelAccess(eventId: String, channel: String) =
        request(fieldPath(eventId, "channels/${segment(channel)}/access"))

    suspend fun setIndividualAccess(eventId: String, channel: String, actor: String, access: String) =
        request(fieldPath(eventId, "channels/${segment(channel)}/access/individual"),
            JSONObject().put("person_id", actor).put("access", access))

    suspend fun setTeamAccess(eventId: String, channel: String, source: String, enabled: Boolean) =
        request(fieldPath(eventId, "channels/${segment(channel)}/access/team"),
            JSONObject().put("source_id", source).put("enabled", enabled))

    suspend fun addChannelMember(eventId: String, id: String, actor: String) =
        request(fieldPath(eventId, "channels/${segment(id)}/members"), JSONObject().put("person_id", actor))
    suspend fun importPeople(eventId: String, channel: String, people: org.json.JSONArray) =
        request(fieldPath(eventId, "people/import"), JSONObject().put("channel_id", channel).put("people", people))
    suspend fun createPerson(eventId: String, person: JSONObject) = request(fieldPath(eventId, "people"), person)
    suspend fun invitePerson(eventId: String, actor: String) =
        request(fieldPath(eventId, "people/${segment(actor)}/invite"), JSONObject())
    suspend fun revokePerson(eventId: String, actor: String) =
        request(fieldPath(eventId, "people/${segment(actor)}/revoke"), JSONObject())
    suspend fun setAccountStatus(eventId: String, actor: String, suspended: Boolean) =
        request(fieldPath(eventId, "people/${segment(actor)}/account-status"), JSONObject().put("suspended", suspended))

    suspend fun direct(eventId: String, actor: String) =
        request(fieldPath(eventId, "direct"), JSONObject().put("person_id", actor))
    suspend fun inbox(eventId: String) = request(fieldPath(eventId, "inbox"))
    suspend fun markReceived(eventId: String, taskId: String) =
        request(fieldPath(eventId, "received"), JSONObject().put("task_id", taskId))
    suspend fun requestAssistance(eventId: String, task: JSONObject, requestId: String, note: String) =
        request(eventPath(eventId, "qrf/assistance"), JSONObject().put("task", task)
            .put("request_id", requestId).put("note", note))
    suspend fun acknowledgeAssistance(eventId: String, reportId: String) =
        request(eventPath(eventId, "qrf/reports/${segment(reportId)}/acknowledge"), JSONObject())
    suspend fun incomingPrivateCall(event: String) =
        request(fieldPath(event, "private-calls/incoming"))

    suspend fun callPerson(event: String, actor: String, mode: String, requestId: String) =
        request(fieldPath(event, "private-calls"), JSONObject().put("person_id", actor)
            .put("mode", mode).put("request_id", requestId))

    suspend fun openConference(event: String, target: RadioTarget) =
        request(fieldPath(event, "channels/${segment(target.id)}/conferences"))
    suspend fun offerConference(event: String, target: RadioTarget, mode: String, requestId: String) =
        request(fieldPath(event, "channels/${segment(target.id)}/conferences"),
            JSONObject().put("mode", mode).put("request_id", requestId))
    suspend fun conferenceStatus(event: String, id: String) = request(conferencePath(event, id))
    suspend fun conferenceDecision(event: String, id: String, action: String) =
        request(conferencePath(event, id) + "/decision", JSONObject().put("action", action))
    suspend fun joinConference(event: String, id: String): MediaSession {
        val result = request(conferencePath(event, id) + "/join", JSONObject())
        return MediaSession(result.getString("session_id"), result.getString("url"), result.getString("token"))
    }
    suspend fun authorizeConference(event: String, id: String, session: MediaSession) =
        request(conferencePath(event, id) + "/publish", JSONObject().put("session_id", session.id))
    suspend fun dispatch(eventId: String, task: JSONObject) = request(fieldPath(eventId, "dispatch"), task)

    suspend fun dispatchArrival(eventId: String, taskId: String, version: Int, key: String) =
        request(fieldPath(eventId, "dispatch/arrival"), JSONObject().put("task_id", taskId)
            .put("expected_version", version).put("request_id", key))

    suspend fun history(eventId: String, target: RadioTarget, before: String = "") =
        request(fieldPath(eventId, "channels/${segment(target.id)}/history?before=${segment(before)}"))

    suspend fun channelChanges(
        eventId: String, target: RadioTarget, cursor: String?, wait: Int = 0, audio: Boolean = true,
    ): JSONObject? {
        if (!channelFeedSupported) return null
        return try {
            val query = "cursor=${segment(cursor.orEmpty())}&wait=$wait&audio=$audio"
            request(fieldPath(eventId, "channels/${segment(target.id)}/changes?$query"), timeoutMs = (wait + 8) * 1_000)
        } catch (error: OpsException) {
            if (error.status != 405 && !(error.status == 404 && error.message == "Not Found")) throw error
            channelFeedSupported = false
            null
        }
    }

    suspend fun channelAttachments(event: String, channel: String, before: String) =
        request(attachmentPath(event, channel) + "?before=${segment(before)}")

    suspend fun publishChannelAttachment(entry: ChannelAttachmentFiles.Entry) = withContext(Dispatchers.IO) {
        val draft = entry.draft
        val posterSize = draft.poster.length()
        val path = attachmentPath(entry.event, entry.channel, entry.id) + "?poster_bytes=$posterSize"
        val http = connection(path, 35_000)
        try {
            http.requestMethod = "PUT"
            http.doOutput = true
            http.setRequestProperty("Content-Type", draft.type)
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("X-Channel-Caption",
                java.util.Base64.getUrlEncoder().encodeToString(entry.caption.encodeToByteArray()))
            http.setFixedLengthStreamingMode(posterSize + draft.content.length())
            http.outputStream.use { output ->
                draft.poster.inputStream().use { input ->
                    copyChannelMedia(input, output, CHANNEL_POSTER_BYTES, "附件封面太大")
                }
                draft.content.inputStream().use { input ->
                    val limit = if (draft.type == CHANNEL_VIDEO_TYPE) CHANNEL_VIDEO_BYTES else CHANNEL_PHOTO_BYTES
                    copyChannelMedia(input, output, limit, "附件太大")
                }
            }
            val status = http.responseCode
            if (status !in 200..299) throw responseError(http, status)
            http.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            http.disconnect()
        }
    }

    suspend fun channelPoster(event: String, channel: String, id: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            readChannelAttachment(event, channel, id, CHANNEL_PHOTO_TYPE, poster = true, output = bytes)
            bytes.toByteArray()
        }

    suspend fun downloadChannelAttachment(event: String, channel: String, id: String, type: String, file: File) =
        withContext(Dispatchers.IO) {
            file.outputStream().use { readChannelAttachment(event, channel, id, type, poster = false, output = it) }
        }

    private suspend fun readChannelAttachment(event: String, channel: String, id: String, type: String,
                                              poster: Boolean, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val path = attachmentPath(event, channel, id) + if (poster) "?poster=true" else ""
        val http = connection(path, 25_000)
        try {
            val status = http.responseCode
            if (status != 200) throw responseError(http, status)
            check(http.contentType?.substringBefore(';') == type) { "附件格式不符" }
            val limit = when {
                poster -> CHANNEL_POSTER_BYTES
                type == CHANNEL_VIDEO_TYPE -> CHANNEL_VIDEO_BYTES
                else -> CHANNEL_PHOTO_BYTES
            }
            http.inputStream.use { copyChannelMedia(it, output, limit, "附件太大") }
        } finally {
            http.disconnect()
        }
    }

    private fun attachmentPath(event: String, channel: String, id: String? = null): String {
        val prefix = fieldPath(event, "channels/${segment(channel)}/attachments")
        return if (id == null) prefix else "$prefix/${segment(id)}"
    }

    suspend fun photos(eventId: String, taskId: String) = request(photoPath(eventId, taskId))
    suspend fun uploadPhoto(entry: PhotoOutbox.Entry) = request(photoPath(entry.event, entry.task),
        JSONObject().put("id", entry.id).put("jpeg", entry.jpeg), timeoutMs = 20_000)

    suspend fun photo(eventId: String, taskId: String, photoId: String): ByteArray =
        readImage(photoPath(eventId, taskId) + "/" + segment(photoId), setOf("image/jpeg"))

    suspend fun arrivalPhoto(eventId: String, planId: String, playerId: String): ByteArray =
        readImage(eventPath(eventId, "arrival-plans/${segment(planId)}/players/${segment(playerId)}/photo"),
            setOf("image/jpeg", "image/png"))

    suspend fun streamArrivalNotices(after: Long, onNotice: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val http = connection("/api/arrival-flight-notices/stream?after=$after", 15_000).apply {
            readTimeout = 40_000
            useCaches = false
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val status = http.responseCode
            if (status != 200) throw OpsException(status, "航班通知通道不可用")
            require(http.contentType?.substringBefore(';') == "text/event-stream")
            http.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                while (true) {
                    ensureActive()
                    val line = input.readLine() ?: break
                    if (line.startsWith("data: ")) onNotice(JSONObject(line.substring(6)))
                }
            }
        } finally {
            http.disconnect()
        }
    }

    private suspend fun readImage(path: String, formats: Set<String>): ByteArray = withContext(Dispatchers.IO) {
        val http = connection(path, 12_000).apply { useCaches = false }
        try {
            val status = http.responseCode
            if (status != 200) throw OpsException(status, "照片暂不可用")
            require(http.contentType?.substringBefore(';') in formats)
            http.inputStream.use { source ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 1_000_000)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } finally {
            http.disconnect()
        }
    }

    var profile: JSONObject = JSONObject()
        private set
    var privateCallsEnabled: Boolean = false
        private set
    var fieldEnabled: Boolean = false
        private set

    suspend fun bootstrap() = request("/api/bootstrap").also(::applyBootstrap)

    fun applyBootstrap(bootstrap: JSONObject) {
        profile = bootstrap.getJSONObject("identity")
        fieldEnabled = bootstrap.optBoolean("field_binding_enabled")
        privateCallsEnabled = bootstrap.optBoolean("private_calls_enabled")
    }

    suspend fun workspace(eventId: String) = request(eventPath(eventId, "workspace"))

    suspend fun workspaceRevision(eventId: String, after: String?): String {
        val query = after?.let { "?after=" + segment(it) }.orEmpty()
        return request(eventPath(eventId, "workspace/changes") + query, timeoutMs = 25_000)
            .getString("revision")
    }

    suspend fun report(eventId: String, text: String, subject: JSONObject?, key: String) =
        request(eventPath(eventId, "messages"), JSONObject().put("text", text).apply {
            if (subject != null) put("subject_ref", subject)
        }, idempotencyKey = key)

    suspend fun setChannelVoice(event: String, target: RadioTarget, mode: String, enabled: Boolean, key: String) =
        request(fieldPath(event, "channels/${segment(target.id)}/voice/settings"),
            JSONObject().put("mode", mode).put("enabled", enabled).put("request_id", key))

    suspend fun channelVoiceLog(event: String, target: RadioTarget, before: String = "") =
        request(fieldPath(event, "channels/${segment(target.id)}/voice/logs?before=${segment(before)}"))

    suspend fun channelDispatch(eventId: String, target: RadioTarget) =
        request(fieldPath(eventId, "channels/${segment(target.id)}/dispatch"))

    suspend fun sendInstruction(eventId: String, target: RadioTarget, incident: String, text: String, version: Int, key: String) =
        request(fieldPath(eventId, "channels/${segment(target.id)}/incidents/${segment(incident)}/dispatch"),
            JSONObject().put("text", text).put("task_version", version).put("request_id", key))

    private fun scenarioPath(event: String, target: RadioTarget, incident: String) =
        fieldPath(event, "channels/${segment(target.id)}/incidents/${segment(incident)}/scenario")

    suspend fun pickupScenario(event: String, target: RadioTarget, incident: String, alternatePoint: String) =
        request(scenarioPath(event, target, incident), JSONObject().put("alternate_point", alternatePoint))

    suspend fun approvePickupScenario(event: String, target: RadioTarget, incident: String, scenario: String, option: String, key: String) =
        request(scenarioPath(event, target, incident) + "/${segment(scenario)}/approve",
            JSONObject().put("option", option).put("request_id", key))

    suspend fun receiveInstruction(eventId: String, id: String) =
        request(fieldPath(eventId, "dispatch/${segment(id)}/receive"), JSONObject())

    suspend fun action(
        eventId: String,
        actionType: String,
        ref: JSONObject,
        version: Int,
        key: String,
        parameters: JSONObject,
        proposalId: String? = null,
    ) =
        request(eventPath(eventId, "actions"), JSONObject()
            .put("action_type", actionType)
            .put("target_ref", ref)
            .put("expected_version", version)
            .put("idempotency_key", key)
            .put("parameters", parameters).apply { if (proposalId != null) put("proposal_id", proposalId) })

    suspend fun join(eventId: String, channel: String = "ptt", target: RadioTarget? = null): MediaSession {
        val result = request(mediaPath(eventId, "$channel/join", target), JSONObject().put("callsign", callsign))
        return MediaSession(result.getString("session_id"), result.getString("url"), result.getString("token"))
    }

    suspend fun floor(
        eventId: String, sessionId: String, pressId: String, operation: String,
        channel: String = "ptt", target: RadioTarget? = null,
    ): Long = request(mediaPath(eventId, "$channel/floor/$operation", target), JSONObject()
            .put("session_id", sessionId)
            .put("press_id", pressId), timeoutMs = 3_000).getLong("lease_ms")

    suspend fun streamOriginal(eventId: String, trackSid: String, target: RadioTarget?,
                               play: suspend (java.io.InputStream) -> Unit) = withContext(Dispatchers.IO) {
        require(trackSid.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val http = connection(mediaPath(eventId, "voice/${segment(trackSid)}/audio", target), 6_000)
        try {
            http.setRequestProperty("Accept", "audio/wav")
            if (http.responseCode != 200) throw OpsException(http.responseCode, "原音暂不可用")
            require(http.contentType?.substringBefore(';') == "audio/wav")
            http.inputStream.buffered().use { play(it) }
        } finally {
            http.disconnect()
        }
    }

    suspend fun downloadCaptionAsset(asset: CaptionAsset, partial: java.io.File, progress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val offset = partial.length()
            val http = connection("/api/voice-models/sensevoice-v1/${segment(asset.name)}", 20_000)
            try {
                if (offset > 0) http.setRequestProperty("Range", "bytes=$offset-")
                val status = http.responseCode
                val resume = offset > 0 && status == 206 &&
                    http.getHeaderField("Content-Range") == "bytes $offset-${asset.bytes - 1}/${asset.bytes}"
                if (!resume && status != 200) throw OpsException(status, "离线字幕包暂不可下载")
                progress(if (resume) offset else 0L)
                http.inputStream.use { input ->
                    java.io.FileOutputStream(partial, resume).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = if (resume) offset else 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            ensureActive()
                            require(total + read <= asset.bytes)
                            output.write(buffer, 0, read)
                            total += read
                            progress(total)
                        }
                        require(total == asset.bytes)
                    }
                }
            } finally {
                http.disconnect()
            }
        }

    private fun segment(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun eventPath(eventId: String, suffix: String) = "/api/events/${segment(eventId)}/$suffix"
    private fun fieldPath(eventId: String, suffix: String) = "/api/field/events/${segment(eventId)}/$suffix"
    private fun conferencePath(eventId: String, id: String) = fieldPath(eventId, "conferences/${segment(id)}")
    private fun photoPath(eventId: String, taskId: String) = fieldPath(eventId, "tasks/${segment(taskId)}/photos")
    private fun mediaPath(eventId: String, suffix: String, target: RadioTarget?) =
        if (target == null) eventPath(eventId, suffix) else fieldPath(eventId, "channels/${segment(target.id)}/$suffix")

    private suspend fun request(
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = 8_000,
        idempotencyKey: String? = null,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val http = connection(path, timeoutMs)
            try {
                http.setRequestProperty("Accept", "application/json")
                if (idempotencyKey != null) http.setRequestProperty("Idempotency-Key", idempotencyKey)
                if (body != null) {
                    http.requestMethod = "POST"
                    http.doOutput = true
                    http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                    http.setFixedLengthStreamingMode(bytes.size)
                    http.outputStream.use { it.write(bytes) }
                }
                val status = http.responseCode
                if (status !in 200..299) throw responseError(http, status)
                http.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            } finally {
                http.disconnect()
            }
        }

    private fun responseError(http: HttpURLConnection, status: Int): OpsException {
        val error = http.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        val failure = error?.let { runCatching { JSONObject(it) }.getOrNull() }
        val detail = failure?.optString("detail")
        val message = detail?.takeIf { it.isNotBlank() && !it.startsWith("[") }
        val unknownAgreementKey = if (status == 422) {
            val errors = failure?.optJSONArray("detail")
            val field = errors?.optJSONObject(0)
            val location = field?.optJSONArray("loc")
            errors?.length() == 1 && field?.optString("type") == "extra_forbidden" &&
                location?.length() == 2 && location.optString(0) == "body" &&
                location.optString(1) == "agreement_key"
        } else false
        return OpsException(status, message ?: "请求失败（HTTP $status）", unknownAgreementKey)
    }

    private fun connection(path: String, timeoutMs: Int) = (URI(base + path).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = false
        if (accessCode.isNotEmpty()) setRequestProperty("Authorization", "Bearer $accessCode")
    }
}

internal data class RadioTarget(val id: String, val name: String, val kind: String) {
    constructor(o: JSONObject) : this(o.getString("id"), o.getString("name"), o.getString("kind"))
}

internal data class MediaSession(val id: String, val url: String, val token: String)

internal class OpsException(val status: Int, message: String, val unknownAgreementKey: Boolean = false) : IOException(message)

internal fun validateMediaUrl(address: String, allowLocal: Boolean) {
    val url = URI(address)
    val local = url.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
    require(url.host != null && url.userInfo == null && (url.scheme == "wss" || (allowLocal && local && url.scheme == "ws"))) {
        "现场频道需要 WSS 地址"
    }
}
