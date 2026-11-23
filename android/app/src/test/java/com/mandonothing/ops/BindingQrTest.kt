package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingQrTest {
    @Test
    fun acceptsOnlyProtocolXBindingPayloads() {
        val code = "qa-123456789012345678901234567890"
        assertEquals(code, BindingQr.codeFrom("protocol-x:bind:" + code))
        assertNull(BindingQr.codeFrom("https://example.test/bind?code=" + code))
        assertNull(BindingQr.codeFrom("protocol-x:bind:short"))
    }
}
