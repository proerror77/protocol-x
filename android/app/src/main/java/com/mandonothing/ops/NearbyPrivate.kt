package com.mandonothing.ops

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val NEARBY_PRIVATE_PHOTO_LIMIT = 32_000
internal const val NEARBY_PRIVATE_ENVELOPE_LIMIT = 44_000

internal class NearbyPrivateIdentity(context: Context, val account: String) {
    private val keys = loadOrCreate(context, account)
    val publicKey: String = Base64.getEncoder().encodeToString(keys.public.encoded)
    internal val privateKey: PrivateKey get() = keys.private

    companion object {
        private const val STORE = "nearby_agreement_v1"

        @Synchronized private fun loadOrCreate(context: Context, account: String): KeyPair {
            val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val vault = FieldSessionStore(context)
            val aad = "nearby-private-key|$account"
            val existing = prefs.getString(account, null)
            if (existing != null) {
                val raw = vault.decrypt(Base64.getDecoder().decode(existing), aad)
                val record = JSONObject(raw.decodeToString())
                raw.fill(0)
                val privateBytes = Base64.getDecoder().decode(record.getString("private"))
                try {
                    return KeyPair(parsePublic(Base64.getDecoder().decode(record.getString("public"))),
                        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
                } finally {
                    privateBytes.fill(0)
                }
            }
            val created = generateKeyPair()
            val encodedPrivate = created.private.encoded
            val record = JSONObject().put("public", Base64.getEncoder().encodeToString(created.public.encoded))
                .put("private", Base64.getEncoder().encodeToString(encodedPrivate)).toString().toByteArray()
            try {
                val encrypted = Base64.getEncoder().encodeToString(vault.encrypt(record, aad))
                check(prefs.edit().putString(account, encrypted).commit())
            } finally {
                encodedPrivate.fill(0)
                record.fill(0)
            }
            return created
        }

        @Synchronized fun clear(context: Context, account: String) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(account).apply()
        }
    }
}

internal data class NearbyPrivateContent(val text: String, val photo: ByteArray?)

internal object NearbyPrivate {
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val WRAPPED_BYTES = NONCE_BYTES + KEY_BYTES + TAG_BYTES
    private const val TEXT_LIMIT = 200

    fun seal(config: NearbyConfig, recipient: NearbyRecipient, id: String, text: String,
             photo: ByteArray? = null): String {
        val sender = checkNotNull(config.actor)
        val senderKey = checkNotNull(config.ownAgreementKey)
        require(config.live() && recipient in config.recipients)
        val context = metadata(config, sender, recipient.actor, id)
        return seal(context, senderKey, recipient.publicKey, text, photo)
    }

    fun open(config: NearbyConfig, identity: NearbyPrivateIdentity, sender: String, recipient: String,
             id: String, envelope: String): NearbyPrivateContent? = runCatching {
        val actor = checkNotNull(config.actor)
        require(identity.account == config.account && actor in listOf(sender, recipient))
        require(identity.publicKey == config.ownAgreementKey)
        open(metadata(config, sender, recipient, id), identity.privateKey, actor == sender, envelope)
    }.getOrNull()

    internal fun seal(context: ByteArray, senderKey: String, recipientKey: String,
                      text: String, photo: ByteArray?): String {
        require(text.length <= TEXT_LIMIT && (text.isNotBlank() || photo != null))
        if (photo != null) require(validPhoto(photo))
        val ephemeral = generateKeyPair()
        val encodedPublic = ephemeral.public.encoded
        val aad = context + encodedPublic
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val clear = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF(text)
                it.writeInt(photo?.size ?: 0)
                if (photo != null) it.write(photo)
            }
        }.toByteArray()
        try {
            val encoded = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use {
                    it.writeByte(VERSION)
                    it.writeShort(encodedPublic.size)
                    it.write(encodedPublic)
                    it.write(wrap(ephemeral.private, recipientKey, aad, false, key))
                    it.write(wrap(ephemeral.private, senderKey, aad, true, key))
                    it.write(encrypt(key, aad + 3.toByte(), clear))
                }
            }.toByteArray()
            return Base64.getEncoder().encodeToString(encoded).also { require(it.length <= NEARBY_PRIVATE_ENVELOPE_LIMIT) }
        } finally {
            key.fill(0)
            clear.fill(0)
        }
    }

    internal fun open(context: ByteArray, privateKey: PrivateKey, sender: Boolean,
                      envelope: String): NearbyPrivateContent {
        require(envelope.length in 1..NEARBY_PRIVATE_ENVELOPE_LIMIT)
        val reader = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(envelope)))
        require(reader.readUnsignedByte() == VERSION)
        val size = reader.readUnsignedShort()
        require(size in 1..120 && reader.available() >= size + WRAPPED_BYTES * 2 + NONCE_BYTES + TAG_BYTES)
        val encodedPublic = ByteArray(size).also(reader::readFully)
        val publicKey = parsePublic(encodedPublic)
        val aad = context + encodedPublic
        val recipientWrap = ByteArray(WRAPPED_BYTES).also(reader::readFully)
        val senderWrap = ByteArray(WRAPPED_BYTES).also(reader::readFully)
        val binding = aad + if (sender) 2.toByte() else 1.toByte()
        val wrappingKey = derive(privateKey, publicKey, binding)
        val key = try {
            decrypt(wrappingKey, binding, if (sender) senderWrap else recipientWrap)
        } finally {
            wrappingKey.fill(0)
        }
        val clear = try {
            decrypt(key, aad + 3.toByte(), reader.readBytes())
        } finally {
            key.fill(0)
        }
        try {
            val payload = DataInputStream(ByteArrayInputStream(clear))
            val text = payload.readUTF()
            val photoSize = payload.readInt()
            require(text.length <= TEXT_LIMIT && photoSize in 0..NEARBY_PRIVATE_PHOTO_LIMIT &&
                payload.available() == photoSize && (text.isNotBlank() || photoSize > 0))
            val photo = if (photoSize == 0) null else ByteArray(photoSize).also(payload::readFully)
            if (photo != null) require(validPhoto(photo))
            return NearbyPrivateContent(text, photo)
        } finally {
            clear.fill(0)
        }
    }

    private fun metadata(config: NearbyConfig, sender: String, recipient: String, id: String): ByteArray {
        require(sender.isNotBlank() && recipient.isNotBlank() && sender != recipient && id.isNotBlank())
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF("Protocol X private message v1")
                it.writeUTF(config.event)
                it.writeUTF(config.channel)
                it.writeUTF(config.generation)
                it.writeUTF(sender)
                it.writeUTF(recipient)
                it.writeUTF(id)
            }
        }.toByteArray()
    }

    private fun wrap(privateKey: PrivateKey, publicKey: String, aad: ByteArray, sender: Boolean,
                     key: ByteArray): ByteArray {
        val binding = aad + if (sender) 2.toByte() else 1.toByte()
        val wrappingKey = derive(privateKey, parsePublic(Base64.getDecoder().decode(publicKey)), binding)
        return try { encrypt(wrappingKey, binding, key) } finally { wrappingKey.fill(0) }
    }

    private fun derive(privateKey: PrivateKey, publicKey: ECPublicKey, binding: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").apply {
            init(privateKey)
            doPhase(publicKey, true)
        }.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(binding)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val extracted = try { mac.doFinal(shared) } finally { shared.fill(0) }
        return try {
            mac.init(SecretKeySpec(extracted, "HmacSHA256"))
            mac.doFinal("Protocol X private key wrap v1".toByteArray() + 1.toByte())
        } finally { extracted.fill(0) }
    }

    private fun encrypt(key: ByteArray, aad: ByteArray, clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        require(cipher.iv.size == NONCE_BYTES)
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(clear)
    }

    private fun decrypt(key: ByteArray, aad: ByteArray, encrypted: ByteArray): ByteArray {
        require(encrypted.size >= NONCE_BYTES + TAG_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, encrypted, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted, NONCE_BYTES, encrypted.size - NONCE_BYTES)
    }

    private fun validPhoto(photo: ByteArray): Boolean {
        if (photo.size !in 4..NEARBY_PRIVATE_PHOTO_LIMIT ||
            photo[0] != 0xff.toByte() || photo[1] != 0xd8.toByte() ||
            photo[photo.size - 2] != 0xff.toByte() || photo.last() != 0xd9.toByte()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photo, 0, photo.size, bounds)
        return bounds.outWidth in 1..1_024 && bounds.outHeight in 1..1_024
    }
}

private val p256 = ECGenParameterSpec("secp256r1")
private val p256Parameters by lazy {
    AlgorithmParameters.getInstance("EC").run { init(p256); getParameterSpec(ECParameterSpec::class.java) }
}

private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
    initialize(p256)
    generateKeyPair()
}

private fun parsePublic(encoded: ByteArray): ECPublicKey {
    require(encoded.size in 1..120)
    val result = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) as ECPublicKey
    val expected = p256Parameters
    require(result.encoded.contentEquals(encoded) && result.params.curve == expected.curve &&
        result.params.generator == expected.generator && result.params.order == expected.order &&
        result.params.cofactor == expected.cofactor)
    return result
}
