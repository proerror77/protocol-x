package com.mandonothing.ops

import android.content.Context
import java.io.File

/**
 * Crash-safe startup migration for RNS-13 (`v0.28.0`) removing obsolete custom Mesh
 * journal and key-agreement files left behind by `v0.27.3`/`v0.27.4` upgrades.
 *
 * Preserves all active RNS runtime stores (`rns-runtime/`), Ops credential leases
 * (`nearby_channel_v1`), session credentials (`field_session`), and audio/media caches.
 */
internal object LegacyNearbyMigration {
    const val LEGACY_JOURNAL_DIR = "nearby"
    const val LEGACY_AGREEMENT_PREFS = "nearby_agreement_v1"
    const val MIGRATION_PREFS = "rns_migration_v1"
    const val MIGRATION_KEY = "rns13_legacy_mesh_retired_v1"

    data class MigrationResult(
        val alreadyMigrated: Boolean = false,
        val removedFiles: Int = 0,
        val removedAgreementPrefs: Boolean = false,
        val errors: Int = 0,
    )

    fun run(context: Context): MigrationResult {
        val prefs = runCatching {
            context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        if (prefs?.getBoolean(MIGRATION_KEY, false) == true) {
            return MigrationResult(alreadyMigrated = true)
        }

        val filesCleanup = cleanupFiles(context.filesDir)
        val agreementDeleted = runCatching {
            context.deleteSharedPreferences(LEGACY_AGREEMENT_PREFS)
        }.getOrElse { false }

        if (filesCleanup.errors == 0) {
            runCatching { prefs?.edit()?.putBoolean(MIGRATION_KEY, true)?.commit() }
        }
        return filesCleanup.copy(removedAgreementPrefs = agreementDeleted || filesCleanup.removedAgreementPrefs)
    }

    /**
     * Pure-file cleanup helper safe for direct JVM unit tests. Only removes legacy custom Mesh
     * artifacts (`filesDir/nearby/` journals and optional `shared_prefs/nearby_agreement_v1.*`).
     */
    internal fun cleanupFiles(filesDir: File, prefsDir: File? = null): MigrationResult {
        var removedFiles = 0
        var removedAgreementPrefs = false
        var errors = 0

        val legacyJournalRoot = File(filesDir, LEGACY_JOURNAL_DIR)
        if (legacyJournalRoot.exists()) {
            val count = deleteRecursivelySafe(legacyJournalRoot)
            if (count < 0) errors++ else removedFiles += count
        }

        if (prefsDir != null && prefsDir.isDirectory) {
            for (suffix in listOf(".xml", ".xml.bak")) {
                val prefFile = File(prefsDir, "$LEGACY_AGREEMENT_PREFS$suffix")
                if (prefFile.exists()) {
                    val ok = runCatching { prefFile.delete() }.getOrElse { false }
                    if (ok) removedAgreementPrefs = true else errors++
                }
            }
        }

        return MigrationResult(
            alreadyMigrated = false,
            removedFiles = removedFiles,
            removedAgreementPrefs = removedAgreementPrefs,
            errors = errors,
        )
    }

    private fun deleteRecursivelySafe(target: File): Int {
        return runCatching {
            var count = 0
            val children = target.listFiles()
            if (children != null) {
                for (child in children) {
                    val sub = deleteRecursivelySafe(child)
                    if (sub < 0) return@runCatching -1
                    count += sub
                }
            }
            if (target.delete()) count + 1 else -1
        }.getOrElse { -1 }
    }
}
