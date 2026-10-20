package com.ezpnix.writeon.presentation.screens.settings

import android.content.Context

/**
 * Remembers the name of the archive the last WebDAV backup had to park beside the remote file.
 *
 * When the remote copy changed since this device last uploaded, the worker uploads its own archive
 * under a date-stamped name instead of overwriting. That copy lives on the server, where nobody
 * would look for it, so its URL is stored here and surfaced in the cloud settings screen: a file the
 * user is told about can be downloaded and merged, a file they never hear about is lost in their
 * WebDAV folder.
 */
object WebDavBackupState {

    private const val PREFS = "webdav_backup_state"

    private const val CONFLICT_KEY = "last_conflict_file"

    /** Records that this device's archive was kept at [url] because the remote had changed. */
    fun recordConflict(context: Context, url: String) {
        preferences(context).edit().putString(CONFLICT_KEY, url).apply()
    }

    /** The parked archive's URL, or null when the last backup went through normally. */
    fun conflictUrl(context: Context): String? =
        preferences(context).getString(CONFLICT_KEY, null)?.takeIf { it.isNotBlank() }

    /** Drops the notice once the user has dealt with it. */
    fun clearConflict(context: Context) {
        preferences(context).edit().remove(CONFLICT_KEY).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
