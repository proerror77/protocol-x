package com.mandonothing.ops

import androidx.credentials.exceptions.CreateCredentialCustomException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.ConstraintError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldAuthTest {
    @Test
    fun configuredServerPrefersSavedPreferenceAndFallsBackToDefault() {
        assertEquals("https://default.example", resolveConfiguredServer(null, "https://default.example"))
        assertEquals("https://default.example", resolveConfiguredServer("   ", "https://default.example"))
        assertEquals(
            "https://staging.example:9443",
            resolveConfiguredServer("  https://staging.example:9443  ", "https://default.example"),
        )
    }

    @Test
    fun emailActivationClientUsesConfiguredServerAndDoesNotFallBackOnInvalidCustomServer() {
        val staging = emailActivationClient("https://staging.example:9443/", "https://prod.example", allowLocalHttp = false)
        assertEquals("https://staging.example:9443", staging.origin)

        val fallback = emailActivationClient(null, "https://prod.example", allowLocalHttp = false)
        assertEquals("https://prod.example", fallback.origin)

        assertThrows(IllegalArgumentException::class.java) {
            emailActivationClient("http://staging.example", "https://prod.example", allowLocalHttp = false)
        }
    }

    @Test
    fun unauthorizedMessagePrefersServerDetailAndUsesAccuratePasskeyFallback() {
        assertEquals(
            "请使用 Passkey 重新登录",
            unauthorizedMessage(OpsException(401, "请使用 Passkey 重新登录"), emailEnrollment = false),
        )
        assertEquals(
            "登录已失效，请使用 Passkey 重新登录",
            unauthorizedMessage(OpsException(401, "请求失败（HTTP 401）"), emailEnrollment = true),
        )
        assertEquals(
            "绑定已失效，请重新绑定",
            unauthorizedMessage(OpsException(401, "请求失败（HTTP 401）"), emailEnrollment = false),
        )
    }

    @Test
    fun passkeyCredentialErrorsProduceLocalDiagnosticMessagesInsteadOfNetworkFallback() {
        val providerMsg = formatErrorMessage(CreateCredentialProviderConfigurationException("missing"))
        assertTrue(providerMsg.contains("Passkey"))
        assertFalse(providerMsg.contains("服务地址和网络"))

        val getProviderMsg = formatErrorMessage(GetCredentialProviderConfigurationException("outdated gms"))
        assertTrue(getProviderMsg.contains("Passkey"))
        assertFalse(getProviderMsg.contains("服务地址和网络"))

        val lockMsg = formatErrorMessage(CreateCredentialUnknownException("User must set a lock screen"))
        assertTrue(lockMsg.contains("锁屏"))
        assertFalse(lockMsg.contains("服务地址和网络"))

        val assetLinksMsg = formatErrorMessage(CreateCredentialUnknownException("Caller signature cannot be verified via assetlinks"))
        assertTrue(assetLinksMsg.contains("签名"))
        assertFalse(assetLinksMsg.contains("服务地址和网络"))

        val domSecurityMsg = formatErrorMessage(CreatePublicKeyCredentialDomException(SecurityError(), "bad rp"))
        assertTrue(domSecurityMsg.contains("签名"))

        val domConstraintMsg = formatErrorMessage(GetPublicKeyCredentialDomException(ConstraintError(), "uv"))
        assertTrue(domConstraintMsg.contains("锁屏"))

        val notAllowedMsg = formatErrorMessage(GetPublicKeyCredentialDomException(NotAllowedError(), null))
        assertTrue(notAllowedMsg.contains("Passkey"))

        val unknownGetMsg = formatErrorMessage(GetCredentialUnknownException())
        assertTrue(unknownGetMsg.contains("Passkey"))
        assertFalse(unknownGetMsg.contains("服务地址和网络"))

        val customMsg = formatErrorMessage(CreateCredentialCustomException("custom.type", "play services unavailable"))
        assertTrue(customMsg.contains("Google Play"))

        val noCredMsg = formatErrorMessage(NoCredentialException())
        assertTrue(noCredMsg.contains("找不到"))
    }
}
