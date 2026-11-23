package com.mandonothing.ops

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64 as WireBase64

internal data class NearbyRecipient(val actor: String, val name: String, val publicKey: String)
internal data class NearbyVerifiedIdentity(val actor: String, val name: String, val agreementKey: String?)

internal data class NearbyConfig(
    val scope: String,
    val account: String,
    val event: String,
    val channel: String,
    val title: String,
    val secret: String,
    val expires: Long,
    val authority: String,
    val certificate: String,
    val voiceEnabled: Boolean,
    val recipients: List<NearbyRecipient> = emptyList(),
) {
    val network get() = "$event|$channel"
    val generation get() = fingerprint(secret)
    private val ownIdentity by lazy { verifyIdentity(certificate, null, System.currentTimeMillis()) }
    val actor get() = ownIdentity?.actor
    val ownAgreementKey get() = ownIdentity?.agreementKey
    fun live(now: Long = System.currentTimeMillis()) = expires > now
    fun sameTransport(other: NearbyConfig?) =
        other != null && scope == other.scope && secret == other.secret && authority == other.authority

    fun verifiedSender(certificate: String, publicKey: String, time: Long): String? =
        verifiedIdentity(certificate, publicKey, time)?.name

    fun verifiedIdentity(certificate: String, publicKey: String, time: Long): NearbyVerifiedIdentity? =
        verifyIdentity(certificate, publicKey, time)

    private fun verifyIdentity(certificate: String, publicKey: String?, time: Long): NearbyVerifiedIdentity? = runCatching {
        val parts = certificate.split('.', limit = 2)
        require(parts.size == 2 && certificate.length <= 1_500)
        val raw = WireBase64.getDecoder().decode(parts[0])
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(WireBase64.getDecoder().decode(authority)))
        val signature = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(raw) }
        require(signature.verify(WireBase64.getDecoder().decode(parts[1])))
        val claim = JSONObject(raw.decodeToString())
        require(claim.getString("channel") == channel && claim.getString("generation") == generation)
        require(claim.getLong("expires") > time / 1_000)
        if (publicKey != null) require(claim.getString("key") == publicKey)
        val actor = claim.getString("actor")
        val name = claim.getString("name")
        require(actor.isNotBlank() && actor.length <= 128 && name.isNotBlank() && name.length <= 80)
        NearbyVerifiedIdentity(actor, name, claim.optString("agreement_key").takeIf { it.length in 1..150 })
    }.getOrNull()
}

internal fun nearbyScope(account: String, event: String, channel: String): String =
    fingerprint(fingerprint(account + "|" + event) + "|" + channel)

internal class NearbySettings(context: Context) {
    private val prefs = context.getSharedPreferences("nearby_channel_v1", Context.MODE_PRIVATE)
    private val vault = FieldSessionStore(context)

    fun read(account: String, event: String, channel: String, title: String): NearbyConfig? {
        val storage = fingerprint(account + "|" + event)
        val raw = prefs.getString(storage, null) ?: return null
        val payload = runCatching {
            JSONObject(vault.decrypt(Base64.decode(raw, Base64.NO_WRAP), storage).decodeToString())
        }.getOrNull() ?: return null
        val credentials = payload.optJSONArray("channels") ?: return null
        val record = (0 until credentials.length()).mapNotNull(credentials::optJSONObject)
            .firstOrNull { it.optString("channel") == channel } ?: return null
        return runCatching {
            val secret = record.getString("secret")
            require(NearbyGroup.isSecret(secret))
            val expires = Math.multiplyExact(record.getLong("expires"), 1_000)
            val roster = record.optJSONArray("recipients")
            val recipients = (0 until (roster?.length() ?: 0)).mapNotNull { index ->
                val entry = roster?.optJSONObject(index) ?: return@mapNotNull null
                val actor = entry.optString("actor")
                val name = entry.optString("name")
                val key = entry.optString("key")
                if (actor.isBlank() || actor.length > 128 || name.isBlank() || name.length > 80 || key.length !in 1..150)
                    null else NearbyRecipient(actor, name, key)
            }.distinctBy(NearbyRecipient::actor)
            NearbyConfig(nearbyScope(account, event, channel), account, event, channel, title, secret, expires,
                payload.getString("authority"), record.getString("certificate"),
                record.optBoolean("voice_enabled"), recipients).takeIf { it.live() }
        }.getOrNull()
    }

    fun save(account: String, event: String, payload: JSONObject) {
        require(payload.optJSONArray("channels") != null && payload.optString("authority").isNotBlank())
        val storage = fingerprint(account + "|" + event)
        val encrypted = Base64.encodeToString(vault.encrypt(payload.toString().toByteArray(), storage), Base64.NO_WRAP)
        check(prefs.edit().putString(storage, encrypted).commit())
    }

    fun clear(account: String, event: String) {
        check(prefs.edit().remove(fingerprint(account + "|" + event)).commit())
    }
}
