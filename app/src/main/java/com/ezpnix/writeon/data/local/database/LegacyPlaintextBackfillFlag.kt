package com.ezpnix.writeon.data.local.database

import android.annotation.SuppressLint
import android.content.Context

/**
 * SEC-5: the install-wide "legacy plaintext rows have been sealed" marker.
 *
 * It is deliberately outside `NoteUseCase`: restoring an old database in `ImportExportRepository`,
 * `CloudRestoreWorker` or `RestoreSnapshots` brings plaintext rows back, and those paths must be
 * able to clear the marker so the next launch runs the sweep again. Keeping the key here means the
 * producer and the two consumers cannot drift.
 */
object LegacyPlaintextBackfillFlag {

    private const val PREFS_NAME = "notes_prefs"
    private const val KEY = "legacy_plaintext_backfill_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, true)
            .apply()
    }

    /**
     * Clears the marker after a database restore. The caller is normally about to restart the
     * process (all three restore paths do); the next launch then re-runs the sweep against the
     * restored rows before the user sees them as "already encrypted".
     */
    @SuppressLint("ApplySharedPref")
    fun reset(context: Context) {
        // commit(), not apply(): every caller is on a restore path's IO thread and immediately
        // triggers a process rebirth. An apply() lost to that kill leaves "done" set, so the next
        // launch trusts a flag written for the database that was just replaced and skips the
        // backfill of the restored plaintext rows.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .commit()
    }
}
