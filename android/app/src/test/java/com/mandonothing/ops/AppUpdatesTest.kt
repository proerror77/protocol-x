package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private val digest = "a".repeat(64)

    @Test fun updateRequiresAHigherBuildAndUsesTheExactReleaseTag() {
        val next = AppRelease("0.27.2", 46, 180_000_000, digest)
        assertTrue(next.isNewerThan(45))
        assertFalse(next.isNewerThan(46))
        assertFalse(next.isNewerThan(47))
        assertEquals(
            "https://github.com/proerror77/protocol-x/releases/download/v0.27.2/Protocol_X_v0.27.2.apk",
            next.downloadUrl,
        )
    }

    @Test fun rejectsUntrustedVersionComponentsAndInvalidDigests() {
        assertThrows(IllegalArgumentException::class.java) { AppRelease("../0.27.2", 46, 1024, digest) }
        assertThrows(IllegalArgumentException::class.java) { AppRelease("0.27.2", 0, 1024, digest) }
        assertThrows(IllegalArgumentException::class.java) { AppRelease("0.27.2", 46, 0, digest) }
        assertThrows(IllegalArgumentException::class.java) { AppRelease("0.27.2", 46, 1024, "broken") }
    }
}
