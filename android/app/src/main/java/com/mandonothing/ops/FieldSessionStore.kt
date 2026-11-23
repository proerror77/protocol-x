package com.mandonothing.ops

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class FieldSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("field_session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(address: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = cipher.doFinal(JSONObject().put("address", address).put("token", token).toString().toByteArray())
        check(prefs.edit().putString("value", Base64.encodeToString(encoded, Base64.NO_WRAP))
            .putString("nonce", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }

    fun read(): Pair<String, String>? {
        val value = prefs.getString("value", null) ?: return null
        return runCatching {
            val nonce = Base64.decode(prefs.getString("nonce", "").orEmpty(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            }
            val record = JSONObject(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).decodeToString())
            record.getString("address") to record.getString("token")
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    fun encrypt(data: ByteArray, context: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(cipher.iv.size == 12)
        cipher.updateAAD(context.toByteArray())
        return cipher.iv + cipher.doFinal(data)
    }

    fun decrypt(data: ByteArray, context: String): ByteArray {
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        cipher.updateAAD(context.toByteArray())
        return cipher.doFinal(data, 12, data.size - 12)
    }

    private companion object {
        const val ALIAS = "protocol-x-field-session-v1"
    }
}
