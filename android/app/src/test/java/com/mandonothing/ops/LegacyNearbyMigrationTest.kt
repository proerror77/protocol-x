package com.mandonothing.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyNearbyMigrationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cleanupRemovesLegacyMeshJournalsAndAgreementPrefsWhilePreservingRnsAndCredentials() {
        val filesDir = tempFolder.newFolder("files")
        val prefsDir = tempFolder.newFolder("shared_prefs")

        // Obsolete v0.27.x custom Mesh artifacts
        val legacyDir = File(filesDir, LegacyNearbyMigration.LEGACY_JOURNAL_DIR).apply { mkdirs() }
        val legacyJournal = File(legacyDir, "history-scope1-protocolx.abc").apply { writeText("old-mesh") }
        val legacyBackup = File(legacyDir, "history-scope1-protocolx.abc.bak").apply { writeText("old-bak") }
        val legacyAgreement = File(prefsDir, "${LegacyNearbyMigration.LEGACY_AGREEMENT_PREFS}.xml")
            .apply { writeText("<map/>") }

        // Active RNS, offline store, and credential files that MUST NOT be deleted
        val rnsDir = File(filesDir, "rns-runtime").apply { mkdirs() }
        val rnsIdentity = File(rnsDir, "identity.bin").apply { writeText("rns-id") }
        val offlineDir = File(filesDir, "field_offline").apply { mkdirs() }
        val offlineFile = File(offlineDir, "actions.dat").apply { writeText("offline") }
        val channelPrefs = File(prefsDir, "nearby_channel_v1.xml").apply { writeText("<map/>") }
        val sessionPrefs = File(prefsDir, "field_session.xml").apply { writeText("<map/>") }

        val result = LegacyNearbyMigration.cleanupFiles(filesDir = filesDir, prefsDir = prefsDir)

        assertEquals(0, result.errors)
        assertTrue(result.removedAgreementPrefs)
        assertEquals(3, result.removedFiles) // 2 journal files + legacyDir
        assertFalse(legacyDir.exists())
        assertFalse(legacyJournal.exists())
        assertFalse(legacyBackup.exists())
        assertFalse(legacyAgreement.exists())

        assertTrue(rnsIdentity.exists())
        assertTrue(offlineFile.exists())
        assertTrue(channelPrefs.exists())
        assertTrue(sessionPrefs.exists())

        // Running cleanup a second time is an idempotent no-op
        val second = LegacyNearbyMigration.cleanupFiles(filesDir = filesDir, prefsDir = prefsDir)
        assertEquals(0, second.errors)
        assertEquals(0, second.removedFiles)
        assertFalse(second.removedAgreementPrefs)
    }

    @Test
    fun cleanupSucceedsWhenLegacyDirectoriesAreAbsent() {
        val filesDir = tempFolder.newFolder("clean_files")
        val prefsDir = tempFolder.newFolder("clean_prefs")

        val result = LegacyNearbyMigration.cleanupFiles(filesDir = filesDir, prefsDir = prefsDir)

        assertEquals(0, result.errors)
        assertEquals(0, result.removedFiles)
        assertFalse(result.removedAgreementPrefs)
    }
}
