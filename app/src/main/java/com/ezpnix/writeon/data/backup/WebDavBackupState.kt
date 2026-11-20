package com.ezpnix.writeon.data.backup

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Remembers the archives the last WebDAV backups had to park beside the remote file.
 *
 * When the remote copy changed since this device last uploaded, the worker uploads its own archive
 * under a date-stamped name instead of overwriting. That copy lives on the server, where nobody
 * would look for it, so its URL is stored here and surfaced in the cloud settings screen: a file the
 * user is told about can be downloaded and merged, a file they never hear about is lost in their
 * WebDAV folder.
 *
 * W18: this used to be **one** string, so a second conflict silently forgot the first - while the
 * first copy stayed on the server, unreachable from inside the app. It is a bounded list now: every
 * parked copy stays listed, the oldest entries fall off once [MAX_REMEMBERED_CONFLICTS] is reached
 * (they are still files on the server, just no longer advertised), and a *successful* canonical
 * upload resets the list because the canonical file is this device's again (see [recordSuccess]).
 */
object WebDavBackupState {

    private const val PREFS = "webdav_backup_state"

    /** Key used by the one-slot version; read once so an upgrade does not drop the notice. */
    private const val LEGACY_CONFLICT_KEY = "last_conflict_file"

    private const val CONFLICTS_KEY = "conflict_files"

    /**
     * How many parked copies are remembered.
     *
     * Conflicts are rare (they need a second device or a restored backup), so ten covers any
     * realistic history. The cap exists so this preference file cannot grow without bound if
     * something is repeatedly classified as a conflict.
     */
    private const val MAX_REMEMBERED_CONFLICTS = 10

    /** SYN-4: the install-scoped part of the staging name. */
    private const val INSTALL_ID_KEY = "install_id"

    /**
     * Kept apart from [PREFS] on purpose: the conflict list is user-facing state, this is a
     * machine identity that must survive every "dismiss"/"clear" operation.
     */
    private const val INSTALL_PREFS = "webdav_install"

    private val installIdLock = Any()

    /**
     * SYN-4: a random id for *this install*, stable across runs and never derived from the
     * account or the device.
     *
     * The staging file used to be `<canonical>.uploading` on every device, so two devices backing
     * up at the same time shared one name: each run deleted the other's in-flight upload before
     * PUTting over it. Appending this id makes the staging name private to the install, which is
     * what makes it safe to clean up our own leftovers without touching anyone else's. A UUID
     * (rather than an Android id) also keeps two installs of the same account on the same phone
     * apart, and the `sharedpref` domain is excluded from auto backup/device transfer (see
     * `backup_rules.xml`), so the id never travels to a second device.
     */
    fun installId(context: Context): String = synchronized(installIdLock) {
        val prefs = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        prefs.getString(INSTALL_ID_KEY, null)?.takeIf { it.isNotBlank() } ?: run {
            val generated = UUID.randomUUID().toString()
            // commit(), not apply(): a run that starts before the async write lands would generate
            // a second id, and its staging file could then never be recognised as this install's.
            prefs.edit().putString(INSTALL_ID_KEY, generated).commit()
            generated
        }
    }

    /** Records that this device's archive was kept at [url] because the remote had changed. */
    fun recordConflict(context: Context, url: String) {
        if (url.isBlank()) return
        val current = conflicts(context).toMutableList()
        if (current.contains(url)) return
        current += url
        write(context, current.takeLast(MAX_REMEMBERED_CONFLICTS))
    }

    /** Every parked archive still worth telling the user about, oldest first. */
    fun conflicts(context: Context): List<String> {
        val prefs = preferences(context)
        val stored = prefs.getString(CONFLICTS_KEY, null)
        if (stored != null) {
            return runCatching {
                val array = JSONArray(stored)
                (0 until array.length())
                    .map { array.optString(it) }
                    .filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        // Upgrade path: the previous build stored a single URL under its own key.
        val legacy = prefs.getString(LEGACY_CONFLICT_KEY, null)
        return listOfNotNull(legacy?.takeIf { it.isNotBlank() })
    }

    /** The most recent parked archive, or null when the last backup went through normally. */
    fun latestConflictUrl(context: Context): String? = conflicts(context).lastOrNull()

    /** Drops one notice once the user has dealt with that copy. */
    fun dismissConflict(context: Context, url: String) {
        write(context, conflicts(context).filterNot { it == url })
    }

    /** Drops every notice. The copies themselves are not deleted: they are on the user's server. */
    fun clearConflicts(context: Context) {
        preferences(context).edit()
            .remove(CONFLICTS_KEY)
            .remove(LEGACY_CONFLICT_KEY)
            .apply()
    }

    /**
     * W18: called after the canonical archive was uploaded successfully.
     *
     * The canonical file is this device's own again, so the parked copies no longer represent an
     * unresolved conflict and their notices must not keep reappearing forever. (Without this the
     * card stayed until the user dismissed it by hand - which also let a stale notice outlive the
     * conflict it described.)
     */
    fun recordSuccess(context: Context) {
        clearConflicts(context)
    }

    private fun write(context: Context, urls: List<String>) {
        val editor = preferences(context).edit().remove(LEGACY_CONFLICT_KEY)
        if (urls.isEmpty()) {
            editor.remove(CONFLICTS_KEY).apply()
            return
        }
        editor.putString(CONFLICTS_KEY, JSONArray(urls).toString()).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
