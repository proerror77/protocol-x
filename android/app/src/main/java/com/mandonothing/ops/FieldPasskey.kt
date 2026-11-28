package com.mandonothing.ops

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.ConstraintError
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NetworkError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.domerrors.TimeoutError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
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
        } catch (error: CreateCredentialException) {
            throw IllegalStateException(describeError(error))
        } catch (error: GetCredentialException) {
            throw IllegalStateException(describeError(error))
        }
        checkNotNull(credential) { "设备没有返回有效的 Passkey" }
        return service.verifyPasskey(challenge.getString("ceremony"), JSONObject(credential), invite)
    }

    fun describeError(error: Exception): String = when (error) {
        is NoCredentialException ->
            "本机找不到 Protocol X 的 Passkey，请使用保存了该 Passkey 的设备或联系调度员"
        is CreateCredentialNoCreateOptionException ->
            "此设备当前无法保存 Passkey，请确认已设置锁屏密码并启用系统凭据服务"
        is CreateCredentialProviderConfigurationException,
        is GetCredentialProviderConfigurationException ->
            "系统 Passkey 服务不可用，请检查 Google Play 服务与凭据提供程序配置"
        is CreateCredentialUnsupportedException,
        is GetCredentialUnsupportedException ->
            "此设备或系统暂不支持 Passkey"
        is CreateCredentialInterruptedException,
        is GetCredentialInterruptedException ->
            "Passkey 操作被系统中断，请重试"
        is CreatePublicKeyCredentialDomException ->
            describeDomError(error.domError, error.errorMessage)
        is GetPublicKeyCredentialDomException ->
            describeDomError(error.domError, error.errorMessage)
        is CreateCredentialUnknownException,
        is GetCredentialUnknownException ->
            describeUnknownCredentialError(
                (error as? CreateCredentialException)?.errorMessage
                    ?: (error as? GetCredentialException)?.errorMessage,
            )
        is CreateCredentialException -> describeUnknownCredentialError(error.errorMessage)
        is GetCredentialException -> describeUnknownCredentialError(error.errorMessage)
        else -> "本机 Passkey 处理失败，请重试"
    }

    private fun describeDomError(domError: DomError, detail: CharSequence?): String = when (domError) {
        is SecurityError ->
            "Passkey 域名或应用签名校验未通过，请确认使用正式安装包并检查站点配置"
        is InvalidStateError ->
            "本机已存在该 Passkey，请直接使用 Passkey 登录"
        is NotSupportedError ->
            "此设备不支持所需的 Passkey 验证方式"
        is ConstraintError ->
            "设备不满足 Passkey 安全要求，请确认已启用锁屏密码或生物识别"
        is NotAllowedError ->
            describeUnknownCredentialError(
                detail,
                fallback = "Passkey 验证未获允许，请确认设备已设置锁屏并在系统提示时完成验证",
            )
        is NetworkError ->
            "Passkey 验证服务通信失败，请检查网络后重试"
        is TimeoutError ->
            "Passkey 验证超时，请重试"
        else ->
            describeUnknownCredentialError(detail, fallback = "Passkey 操作失败，请确认设备支持并已开启锁屏密码后重试")
    }

    private fun describeUnknownCredentialError(
        detail: CharSequence?,
        fallback: String = "本机 Passkey 调用失败，请确认已开启锁屏密码、Google Play 服务正常且应用签名与站点配置一致",
    ): String {
        val text = detail?.toString()?.lowercase().orEmpty()
        return when {
            listOf(
                "lock screen",
                "screen lock",
                "keyguard",
                "device lock",
                "no screen lock",
                "pin or",
                "biometric",
                "user not authenticated",
            ).any(text::contains) ->
                "请先在系统设置中开启锁屏密码或生物识别后再使用 Passkey"
            listOf(
                "assetlinks",
                "relying party",
                "rp id",
                "origin",
                "signature",
                "fingerprint",
                "sha-256",
                "sha256",
                "caller",
                "not verified",
                "cannot be verified",
                "unable to validate",
            ).any(text::contains) ->
                "Passkey 域名或应用签名校验未通过，请确认使用正式安装包并检查站点配置"
            listOf(
                "play services",
                "gms",
                "provider",
                "credential manager",
                "not available",
                "api_not_connected",
                "outdated",
            ).any(text::contains) ->
                "系统 Passkey 服务不可用，请更新 Google Play 服务并检查凭据设置"
            else -> fallback
        }
    }
}
