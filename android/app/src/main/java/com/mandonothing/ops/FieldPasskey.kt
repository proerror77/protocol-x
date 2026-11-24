package com.mandonothing.ops

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import org.json.JSONObject

internal object FieldPasskey {
    suspend fun signIn(activity: Activity, service: OpsClient, invite: String? = null): JSONObject? {
        val manager = CredentialManager.create(activity)
        val challenge = service.passkeyOptions(invite)
        val options = challenge.getJSONObject("options").toString()
        val credential = try {
            if (invite == null) {
                val response = manager.getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options))))
                (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            } else {
                val response = manager.createCredential(activity, CreatePublicKeyCredentialRequest(options))
                (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            }
        } catch (_: CreateCredentialCancellationException) {
            return null
        } catch (_: GetCredentialCancellationException) {
            return null
        } catch (_: NoCredentialException) {
            throw IllegalStateException("本机找不到 Protocol X 的 Passkey，请使用保存了该 Passkey 的设备或联系调度员")
        }
        checkNotNull(credential) { "设备没有返回有效的 Passkey" }
        return service.verifyPasskey(challenge.getString("ceremony"), JSONObject(credential), invite)
    }
}
