package com.mandonothing.ops

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val NEARBY_TEXT_LIMIT = 200
internal const val NEARBY_AUDIO_LIMIT = 48_000
private const val NEARBY_FRAME_LIMIT = 82_000
internal const val NEARBY_SEND_WINDOW = 86_400_000L
private const val MAX_INVENTORY = 64
private const val MAX_MESSAGES = 128
private const val MAX_TOMBSTONES = 1_024
private const val MAX_ORDINARY_PENDING = 24
private const val MAX_PRIORITY_PENDING = 16
private const val MAX_CONTROLS_PENDING = 32
private const val PRIVATE_BLE_LIMIT = 3_200
private const val MAX_LIVE_PACKETS = 8
private const val MAX_LIVE_BYTES = 8_192

private val actorPattern = Regex("[A-Za-z0-9_-]{1,96}")
private val sealedPattern = Regex("[A-Za-z0-9+/=]+")
private fun isMessageId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal class NearbyGroup(val code: String, private val event: String) {
    private val secret = Base64.getUrlDecoder().decode(code)
    private fun derive(purpose: String) = MessageDigest.getInstance("SHA-256")
        .digest((purpose + "|" + event + "|").toByteArray() + secret)

    val serviceName = "protocolx." + derive("discovery").take(12).joinToString("") { "%02x".format(it) }
    val wifiPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(derive("wifi"))
    val radioUuid: UUID = UUID.nameUUIDFromBytes(derive("bluetooth"))
    private val frameKey = SecretKeySpec(derive("frames"), "AES")

    fun seal(data: ByteArray, initiator: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, frameKey) }
        cipher.updateAAD(byteArrayOf(if (initiator) 1 else 2))
        return cipher.iv + cipher.doFinal(data)
    }

    fun open(data: ByteArray, initiator: Boolean): ByteArray {
        require(data.size in 28..NEARBY_FRAME_LIMIT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, frameKey, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(byteArrayOf(if (initiator) 1 else 2))
        return cipher.doFinal(data, 12, data.size - 12)
    }

    companion object {
        fun isSecret(value: String): Boolean =
            value.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value
    }
}

internal data class NearbyMessage(
    val id: String,
    val sender: String,
    val name: String,
    val text: String,
    val audio: String,
    val time: Long,
    val assistance: Boolean,
    val publicKey: String,
    val signature: String,
    val certificate: String,
    val received: Boolean = false,
    val synced: Boolean = false,
    val attemptedVia: NearbyRadio? = null,
    val deliveredVia: NearbyRadio? = null,
    val directlyReceived: Boolean = false,
    val confirmationFor: String = "",
    val confirmationRequired: Boolean = false,
    val receiptFor: String = "",
    val recipient: String = "",
    val sealedPayload: String = "",
    val receiptVia: NearbyRadio? = null,
    val actor: String = "",
    val expired: Boolean = false,
) {
    val isControl get() = confirmationFor.isNotEmpty() || receiptFor.isNotEmpty()
    val isPrivate get() = sealedPayload.isNotEmpty()
    val priority get() = isControl || assistance
    private val extended get() = isControl || isPrivate || confirmationRequired || recipient.isNotEmpty()
    val delivery get() = nearbyDelivery(received, attemptedVia, expired)

    fun wire() = JSONObject().put("id", id).put("sender", sender).put("name", name)
        .put("text", text).put("audio", audio).put("time", time).put("assistance", assistance)
        .put("key", publicKey).put("signature", signature).put("certificate", certificate).also {
            if (extended) it.put("confirmation_for", confirmationFor).put("confirmation_required", confirmationRequired)
                .put("receipt_for", receiptFor).put("recipient", recipient).put("sealed_payload", sealedPayload)
                .put("receipt_via", receiptVia?.name)
        }

    fun record() = wire().put("received", received).put("synced", synced)
        .put("attempted_via", attemptedVia?.name).put("delivered_via", deliveredVia?.name)
        .put("directly_received", directlyReceived).put("actor", actor).put("expired", expired)

    fun signedContent(group: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use {
            it.writeUTF(group)
            it.writeUTF(id)
            it.writeUTF(sender)
            it.writeUTF(name)
            it.writeUTF(text)
            it.writeUTF(audio)
            it.writeLong(time)
            it.writeBoolean(assistance)
            it.writeUTF(certificate)
            if (extended) {
                it.writeUTF("protocol-x-nearby-extension-v1")
                it.writeUTF(confirmationFor)
                it.writeBoolean(confirmationRequired)
                it.writeUTF(receiptFor)
                it.writeUTF(recipient)
                it.writeUTF(sealedPayload)
                it.writeUTF(receiptVia?.name.orEmpty())
            }
        }
    }.toByteArray()

    fun hasValidSignature(group: String): Boolean = runCatching {
        val keyBytes = Base64.getDecoder().decode(publicKey)
        if (keyFingerprint(keyBytes) != sender) return false
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(signedContent(group))
        verifier.verify(Base64.getDecoder().decode(signature))
    }.getOrDefault(false)

    internal fun checkEnvelope() {
        require(recipient.isEmpty() || actorPattern.matches(recipient))
        require(confirmationFor.isEmpty() || isMessageId(confirmationFor))
        require(receiptFor.isEmpty() || isMessageId(receiptFor))
        require(confirmationFor.isEmpty() || receiptFor.isEmpty())
        require(receiptVia == null || receiptFor.isNotEmpty())
        require(sealedPayload.isEmpty() ||
            (sealedPayload.length <= NEARBY_PRIVATE_ENVELOPE_LIMIT && sealedPattern.matches(sealedPayload)))
        require((recipient.isNotEmpty()) == (isControl || isPrivate))
        if (isControl) require(!confirmationRequired && !assistance && audio.isEmpty() && !isPrivate)
        if (isPrivate) require(audio.isEmpty() && text == PRIVATE_PLACEHOLDER)
        require(text.isNotBlank() || audio.isNotEmpty())
    }

    companion object {
        private const val PRIVATE_PLACEHOLDER = "加密定向消息"

        fun wireText(text: String, sealed: String, confirmation: String, receipt: String): String = when {
            sealed.isNotEmpty() -> PRIVATE_PLACEHOLDER
            confirmation.isNotEmpty() -> "队员确认"
            receipt.isNotEmpty() -> "设备回执"
            else -> text
        }

        fun decode(value: JSONObject, remote: Boolean): NearbyMessage {
            val id = value.getString("id").also { require(isMessageId(it)) }
            val sender = value.getString("sender").also { require(it.matches(Regex("[0-9a-f]{32}"))) }
            val name = value.getString("name").also { require(it.length in 1..80) }
            val text = value.getString("text").also { require(it.length <= NEARBY_TEXT_LIMIT) }
            val audio = value.optString("audio").also {
                require(it.length <= (NEARBY_AUDIO_LIMIT + 2) / 3 * 4)
                if (it.isNotEmpty()) require(Base64.getDecoder().decode(it).size in 1..NEARBY_AUDIO_LIMIT)
            }
            return NearbyMessage(id, sender, name, text, audio, value.getLong("time"),
                value.optBoolean("assistance"),
                value.getString("key").also { require(it.length in 1..150) },
                value.getString("signature").also { require(it.length in 1..110) },
                value.getString("certificate").also { require(it.length in 1..1_500) },
                !remote && value.optBoolean("received"),
                !remote && value.optBoolean("synced"),
                if (remote) null else nearbyRadio(value.optString("attempted_via")),
                if (remote) null else nearbyRadio(value.optString("delivered_via")),
                !remote && value.optBoolean("directly_received"),
                value.optString("confirmation_for"),
                value.optBoolean("confirmation_required"),
                value.optString("receipt_for"),
                value.optString("recipient"),
                value.optString("sealed_payload"),
                nearbyRadio(value.optString("receipt_via")),
                if (remote) "" else value.optString("actor"),
                !remote && value.optBoolean("expired")).also { it.checkEnvelope() }
        }
    }
}

internal data class NearbyJournal(
    val entries: List<NearbyMessage> = emptyList(),
    val tombstones: Map<String, Long> = emptyMap(),
) {
    fun retain(sender: String, now: Long, arrivals: Set<String> = emptySet()): NearbyJournal {
        val usable = entries.filter {
            val age = if (it.sender == sender && !it.isControl) NEARBY_SEND_WINDOW * 2 else NEARBY_SEND_WINDOW
            it.time > now - age
        }.map {
            if (it.sender == sender && !it.isControl && !it.received &&
                it.time <= now - NEARBY_SEND_WINDOW) it.copy(expired = true) else it
        }
        val retained = usable.sortedWith(
            compareByDescending<NearbyMessage> { it.sender == sender && !it.received && !it.expired && it.time > now - NEARBY_SEND_WINDOW }
                .thenByDescending { it.id in arrivals }
                .thenByDescending { it.priority && it.time > now - NEARBY_SEND_WINDOW }
                .thenByDescending { it.time },
        ).take(MAX_MESSAGES).sortedBy { it.time }
        val ids = retained.mapTo(mutableSetOf()) { it.id }
        val discarded = (tombstones + usable.filter { it.id !in ids }.associate { it.id to it.time })
            .filter { (id, time) -> id !in ids && time > now - NEARBY_SEND_WINDOW }
            .entries.sortedByDescending { it.value }.take(MAX_TOMBSTONES).associate { it.toPair() }
        return NearbyJournal(retained, discarded)
    }

    fun active(now: Long): List<NearbyMessage> = entries.filter { !it.expired && it.time > now - NEARBY_SEND_WINDOW }
}

internal fun nearbyCanQueue(entries: List<NearbyMessage>, sender: String, priority: Boolean,
                            control: Boolean, now: Long): Boolean {
    val limit = when {
        control -> MAX_CONTROLS_PENDING
        priority -> MAX_PRIORITY_PENDING
        else -> MAX_ORDINARY_PENDING
    }
    return entries.count {
        it.sender == sender && !it.received && !it.expired && it.time > now - NEARBY_SEND_WINDOW &&
            it.isControl == control && (control || it.priority == priority)
    } < limit
}

internal fun nearbyRequiresConfirmation(text: String, sealedPayload: String, hasAudio: Boolean, assistance: Boolean) =
    !assistance && !hasAudio && (text.isNotBlank() || sealedPayload.isNotEmpty())

internal class NearbyMessages(context: Context, config: NearbyConfig, private val identity: NearbyIdentity) {
    @Volatile private var credential = config
    val voiceEnabled get() = credential.voiceEnabled
    val group = NearbyGroup(config.secret, config.network)
    val sender = identity.sender
    val actor get() = credential.actor.orEmpty()
    val device = sender.take(8).toLong(16).toInt()
    private val node = "%08x".format(device)
    private val file = AtomicFile(File(context.filesDir, "nearby/history-${config.scope}-${group.serviceName}"))
    private val vault = FieldSessionStore(context)
    private var journal = load().retain(sender, System.currentTimeMillis())
    private val state = MutableStateFlow(journal.entries)
    val messages = state.asStateFlow()
    private val recentlyOffered = linkedSetOf<String>()
    private var lastWifi = 0L

    @Synchronized fun updateCredential(config: NearbyConfig) {
        check(config.sameTransport(credential))
        credential = config
    }

    @Synchronized fun maintenance() {
        val now = System.currentTimeMillis()
        val fresh = journal.retain(sender, now)
        if (fresh != journal) save(fresh, now)
    }

    fun isPrivateAddressedToMe(message: NearbyMessage): Boolean = message.isPrivate && message.recipient == actor

    @Synchronized fun send(text: String, audio: ByteArray? = null, assistance: Boolean = false,
                           id: String = UUID.randomUUID().toString(),
                           recipient: String = "", sealedPayload: String = ""): String {
        val confirmationRequired = nearbyRequiresConfirmation(text, sealedPayload, audio != null, assistance)
        return enqueue(text, audio, assistance, id, confirmationRequired, recipient, sealedPayload,
            "", "", null)
    }

    private fun enqueue(text: String, audio: ByteArray?, assistance: Boolean, id: String,
                        confirmationRequired: Boolean, recipient: String, sealedPayload: String,
                        confirmationFor: String, receiptFor: String, receiptVia: NearbyRadio?): String {
        require(isMessageId(id) && text.length <= NEARBY_TEXT_LIMIT)
        require(audio == null || credential.voiceEnabled && audio.size in 1..NEARBY_AUDIO_LIMIT)
        require(sealedPayload.isEmpty() || text.isEmpty() && audio == null)
        if (journal.entries.any { it.id == id } || journal.tombstones.containsKey(id)) return id
        val now = System.currentTimeMillis()
        val certificate = credential.certificate
        check(credential.live(now))
        val member = checkNotNull(credential.verifiedIdentity(certificate, identity.publicKey, now))
        val message = NearbyMessage(id, sender, member.name,
            NearbyMessage.wireText(text, sealedPayload, confirmationFor, receiptFor),
            audio?.let { Base64.getEncoder().encodeToString(it) }.orEmpty(),
            now, assistance, identity.publicKey, "", certificate,
            confirmationFor = confirmationFor, confirmationRequired = confirmationRequired,
            receiptFor = receiptFor, recipient = recipient, sealedPayload = sealedPayload,
            receiptVia = receiptVia, actor = member.actor)
        message.checkEnvelope()
        check(nearbyCanQueue(journal.entries, sender, message.priority, message.isControl, now))
        val signature = identity.sign(message.signedContent(group.serviceName))
        save(journal.copy(entries = journal.entries + message.copy(signature = signature)), now)
        return id
    }

    @Synchronized fun confirm(id: String): Boolean = acknowledge(id, human = true)

    @Synchronized fun acknowledgePrivate(id: String): Boolean = acknowledge(id, human = false)

    private fun acknowledge(id: String, human: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val target = journal.active(now).firstOrNull { it.id == id } ?: return false
        if (actor.isEmpty() || target.sender == sender || target.actor == actor || target.actor.isBlank() ||
            (human && !target.confirmationRequired) ||
            (!human && !isPrivateAddressedToMe(target)) ||
            (target.isPrivate && !isPrivateAddressedToMe(target))) return false
        val kind = if (human) "confirm" else "receipt"
        val key = UUID.nameUUIDFromBytes("protocol-x-nearby-$kind|${group.serviceName}|$id|$actor".toByteArray()).toString()
        return runCatching {
            enqueue("", null, false, key, false, target.actor, "",
                if (human) id else "", if (human) "" else id,
                if (!human && target.directlyReceived) target.deliveredVia else null)
        }.isSuccess
    }

    @Synchronized fun confirmations(id: String): List<NearbyMessage> {
        val target = journal.entries.firstOrNull { it.id == id && it.confirmationRequired } ?: return emptyList()
        return journal.entries.filter { it.confirmationFor == id && validAcknowledgment(target, it) }
            .distinctBy { it.actor }
    }

    @Synchronized fun syncAssistance(id: String) {
        val entries = journal.entries
        if (entries.any { it.id == id && it.sender == sender && it.assistance && !it.synced }) {
            save(journal.copy(entries = entries.map {
                if (it.id == id && it.sender == sender) it.copy(synced = true) else it
            }), System.currentTimeMillis())
        }
    }

    @Synchronized fun makeFrame(wifi: Boolean, initiator: Boolean, inventory: Set<String>,
                                live: List<NearbyLivePacket> = emptyList(), supportsLive: Boolean = false): OutboundNearbyFrame {
        val now = System.currentTimeMillis()
        check(credential.live(now))
        val entries = journal.active(now)
        val byId = entries.associateBy { it.id }
        val known = (recentlyOffered.filter { it in byId || (journal.tombstones[it] ?: 0) > now - NEARBY_SEND_WINDOW } +
            entries.asReversed().map { it.id } + journal.tombstones.keys).distinct().take(MAX_INVENTORY)
        val routes = JSONObject()
        known.mapNotNull(byId::get).filter { it.directlyReceived && !it.isPrivate && !it.isControl }
            .forEach { routes.put(it.id, it.deliveredVia?.name) }
        val wire = JSONObject().put("node", node).put("inventory", JSONArray(known)).put("direct_routes", routes)
        if (wifi && credential.voiceEnabled && supportsLive) wire.put("live_ptt", true)
        if (wifi && credential.voiceEnabled && live.isNotEmpty()) {
            val packets = mutableListOf<JSONObject>()
            var bytes = 0
            for (packet in live.take(MAX_LIVE_PACKETS)) {
                val encoded = packet.wire()
                bytes += encoded.toString().toByteArray().size + 1
                if (bytes > MAX_LIVE_BYTES) break
                packets += encoded
            }
            if (packets.isNotEmpty()) wire.put("live", JSONArray(packets))
        }
        val preferWifi = !wifi && now - lastWifi < 5_000
        val candidates = if (inventory.isEmpty()) emptyList() else entries.filter {
            it.id !in inventory && ((wifi && credential.voiceEnabled) || it.audio.isEmpty()) &&
                (wifi || it.sealedPayload.length <= PRIVATE_BLE_LIMIT) &&
                (!preferWifi || it.priority || now - it.time >= 10_000)
        }.sortedWith(compareByDescending<NearbyMessage> { it.priority }
            .thenByDescending { it.sender == sender && !it.received }.thenBy { it.time })
        val batch = (candidates.filterNot(::large).take(3) + listOfNotNull(candidates.firstOrNull(::large)))
            .sortedByDescending { it.priority }
        val sent = mutableListOf<NearbyMessage>()
        val records = mutableListOf<JSONObject>()
        val headerSize = wire.toString().toByteArray().size + 20
        var size = headerSize
        for (message in batch) {
            val record = message.wire()
            val bytes = record.toString().toByteArray().size + 1
            if (size + bytes > NEARBY_FRAME_LIMIT - 28) continue
            size += bytes
            sent += message
            records += record
        }
        wire.put("messages", JSONArray(records))
        return OutboundNearbyFrame(group.seal(wire.toString().toByteArray(), initiator),
            sent.filter { it.sender == sender }.mapTo(mutableSetOf()) { it.id })
    }

    private fun large(message: NearbyMessage): Boolean =
        message.audio.isNotEmpty() || message.sealedPayload.length > PRIVATE_BLE_LIMIT

    @Synchronized fun frameSent(ids: Set<String>, via: NearbyRadio) {
        if (ids.isEmpty()) return
        val entries = journal.entries
        val updated = entries.map {
            if (it.sender == sender && !it.received && it.attemptedVia == null && it.id in ids)
                it.copy(attemptedVia = via) else it
        }
        if (updated != entries) save(journal.copy(entries = updated), System.currentTimeMillis())
    }

    @Synchronized fun receive(frame: ByteArray, wifi: Boolean, initiator: Boolean): NearbyFrame {
        val now = System.currentTimeMillis()
        check(credential.live(now))
        val value = JSONObject(group.open(frame, !initiator).decodeToString())
        val peer = value.getString("node")
        require(peer != node && peer.matches(Regex("[0-9a-f]{8}")))
        val packets = if (wifi && credential.voiceEnabled) value.optJSONArray("live") else null
        val voice = (0 until (packets?.length() ?: 0).coerceAtMost(MAX_LIVE_PACKETS))
            .mapNotNull { packets?.optJSONObject(it)?.let(NearbyLivePacket::decode) }
        val messages = value.getJSONArray("messages").also { require(it.length() <= 4) }
        val incoming = (0 until messages.length()).mapNotNull { index ->
            runCatching { NearbyMessage.decode(messages.getJSONObject(index), true) }.getOrNull()
        }.filter { it.sender != sender && it.time in (now - NEARBY_SEND_WINDOW)..(now + 300_000) &&
            ((wifi && credential.voiceEnabled) || it.audio.isEmpty()) &&
            (wifi || it.sealedPayload.length <= PRIVATE_BLE_LIMIT) }
        val ids = value.getJSONArray("inventory").also { require(it.length() <= MAX_INVENTORY) }
        // The marker differentiates an empty inventory from an uninitialized connection.
        val inventory = (0 until ids.length()).mapTo(mutableSetOf(peer)) { ids.getString(it) }
        val routes = value.optJSONObject("direct_routes")
        val previous = journal.entries
        val known = previous.mapTo(mutableSetOf()) { it.id }
        known.addAll(journal.tombstones.keys)
        val arrivingVia = if (wifi) NearbyRadio.WIFI else NearbyRadio.BLE
        val new = incoming.mapNotNull {
            if (it.id in known) {
                rememberOffer(it.id)
                return@mapNotNull null
            }
            val signed = credential.verifiedIdentity(it.certificate, it.publicKey, it.time) ?: return@mapNotNull null
            if (signed.name != it.name || !it.hasValidSignature(group.serviceName) || !known.add(it.id)) {
                return@mapNotNull null
            }
            it.copy(deliveredVia = arrivingVia, directlyReceived = it.sender.startsWith(peer), actor = signed.actor)
        }
        val all = previous + new
        val acknowledgments = all.asSequence().filter { it.isControl }.groupBy {
            it.receiptFor.ifEmpty { it.confirmationFor }
        }
        val entries = all.map { message ->
            if (message.sender != sender) return@map message
            if (!message.isPrivate) {
                if (message.id !in inventory) return@map message
                val delivered = message.deliveredVia ?: nearbyRadio(routes?.optString(message.id).orEmpty())
                return@map message.copy(received = true, deliveredVia = delivered)
            }
            val acknowledged = acknowledgments[message.id]?.firstOrNull {
                validAcknowledgment(message, it)
            } ?: return@map message
            message.copy(received = true, deliveredVia = message.deliveredVia ?: acknowledged.receiptVia)
        }
        val oldDelivered = previous.filter { it.received }.mapTo(mutableSetOf()) { it.id }
        val delivered = entries.filter { it.sender == sender && !it.isControl && it.received && it.id !in oldDelivered }
        val newlyKnown = (new + delivered).mapTo(mutableSetOf()) { it.id }
        val updated = journal.copy(entries = entries).retain(sender, now, newlyKnown)
        if (updated != journal) save(updated, now)
        val accepted = new.filter { item -> journal.entries.any { it.id == item.id } }
        accepted.forEach { rememberOffer(it.id) }
        if (wifi) lastWifi = now
        return NearbyFrame(inventory, accepted, peer, delivered.size, voice, delivered.lastOrNull()?.deliveredVia,
            wifi && credential.voiceEnabled && value.optBoolean("live_ptt"))
    }

    private fun rememberOffer(id: String) {
        recentlyOffered.remove(id)
        recentlyOffered.add(id)
        if (recentlyOffered.size > 16) recentlyOffered.remove(recentlyOffered.first())
    }

    private fun load(): NearbyJournal {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return NearbyJournal() }
        val raw = vault.decrypt(bytes, file.baseFile.name).decodeToString()
        val rows = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).getJSONArray("entries")
        val tombstones = if (raw.startsWith("[")) null else JSONObject(raw).optJSONObject("tombstones")
        return NearbyJournal((0 until rows.length()).map { NearbyMessage.decode(rows.getJSONObject(it), false) },
            tombstones?.keys()?.asSequence()?.associateWith { tombstones.getLong(it) }.orEmpty())
    }

    private fun save(value: NearbyJournal, now: Long) {
        val updated = value.retain(sender, now)
        val data = JSONObject().put("entries", JSONArray(updated.entries.map { it.record() }))
            .put("tombstones", JSONObject(updated.tombstones))
        val bytes = vault.encrypt(data.toString().toByteArray(), file.baseFile.name)
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            journal = updated
            state.value = updated.entries
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal fun validAcknowledgment(message: NearbyMessage, proof: NearbyMessage): Boolean {
    if (proof.actor.isEmpty() || proof.actor == message.actor || proof.sender == message.sender ||
        proof.recipient != message.actor || proof.time < message.time - 300_000) return false
    if (message.isPrivate && message.recipient != proof.actor) return false
    return (message.isPrivate && proof.receiptFor == message.id) ||
        (message.confirmationRequired && proof.confirmationFor == message.id)
}

private fun keyFingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).take(16).joinToString("") { "%02x".format(it) }

internal class NearbyIdentity(scope: String) {
    private val alias = "protocol-x-nearby-" + scope
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keys = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: generate()
    private val keyBytes = keys.certificate.publicKey.encoded
    val sender = keyFingerprint(keyBytes)
    val publicKey = Base64.getEncoder().encodeToString(keyBytes)

    private fun generate(): KeyStore.PrivateKeyEntry {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKeyPair()
        }
        return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    fun sign(content: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(keys.privateKey)
        signature.update(content)
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}

internal data class NearbyFrame(
    val inventory: Set<String>,
    val new: List<NearbyMessage>,
    val peerNode: String = "",
    val newlyDelivered: Int = 0,
    val livePackets: List<NearbyLivePacket> = emptyList(),
    val lastDeliveryRoute: NearbyRadio? = null,
    val supportsLive: Boolean = false,
)
internal data class OutboundNearbyFrame(val data: ByteArray, val ownMessageIds: Set<String>)

internal enum class NearbyDelivery { WAITING, AWAITING_RECEIPT, DELIVERED, EXPIRED }

internal fun nearbyDelivery(received: Boolean, attemptedVia: NearbyRadio?, expired: Boolean = false): NearbyDelivery = when {
    received -> NearbyDelivery.DELIVERED
    expired -> NearbyDelivery.EXPIRED
    attemptedVia != null -> NearbyDelivery.AWAITING_RECEIPT
    else -> NearbyDelivery.WAITING
}

internal fun nearbyRadio(value: String): NearbyRadio? = NearbyRadio.entries.firstOrNull { it.name == value }

internal fun DataOutputStream.writeNearby(frame: ByteArray) {
    require(frame.size in 28..NEARBY_FRAME_LIMIT)
    writeInt(frame.size)
    write(frame)
    flush()
}

internal fun DataInputStream.readNearby(): ByteArray {
    val length = readInt()
    require(length in 28..NEARBY_FRAME_LIMIT)
    return ByteArray(length).also(::readFully)
}
