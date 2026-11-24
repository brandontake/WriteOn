package com.ezpnix.writeon.data.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
 * (they are still files on the server, just no longer advertised; the cloud screen says so), and a
 * successful canonical upload deliberately **keeps** every notice (SYN-04): the upload proves the
 * canonical file is this device's again, not that a parked copy was merged. Only an explicit
 * [dismissConflict] or restoring that copy resolves a notice.
 */
object WebDavBackupState {

    private const val PREFS = "webdav_backup_state"

    /** Key used by the one-slot version; read once so an upgrade does not drop the notice. */
    private const val LEGACY_CONFLICT_KEY = "last_conflict_file"

    private const val CONFLICTS_KEY = "conflict_files"

    /**
     * SYN-3: maps each parked copy's URL to the remote validator it was made for, so a retry of
     * the same conflict does not re-encrypt and re-upload the whole archive (every recording
     * included) again. Values are strings produced by [conflictFingerprint].
     */
    private const val CONFLICT_VALIDATORS_KEY = "conflict_validators"

    /**
     * How many parked copies are remembered.
     *
     * Conflicts are rare (they need a second device or a restored backup), so ten covers any
     * realistic history. The cap exists so this preference file cannot grow without bound if
     * something is repeatedly classified as a conflict. The cloud screen says when the list is
     * full: the older copies stay on the server even after their notice falls off.
     */
    internal const val MAX_REMEMBERED_CONFLICTS = 10

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
    fun recordConflict(context: Context, url: String, validator: String? = null) {
        if (url.isBlank()) return
        val current = conflicts(context).toMutableList()
        val validators = conflictValidators(context)
        if (!current.contains(url)) current += url
        if (!validator.isNullOrBlank()) validators[url] = validator
        val kept = current.takeLast(MAX_REMEMBERED_CONFLICTS)
        val keptSet = kept.toSet()
        write(context, kept, validators.filterKeys { it in keptSet })
    }

    /**
     * SYN-3: a stable token for the remote state a conflict copy was made for.
     *
     * Only validators are used - a length alone cannot tell two different archives apart, and a
     * skipped upload is only acceptable when the remote provably did not change.
     *
     * R2-01/SYN-03: [localContent] is a cheap fingerprint of the local library (database and WAL
     * size/mtime). Without it the dedupe key described only the remote, so once a copy was parked
     * every later run skipped the upload *even after the user wrote new notes* - the local archive
     * had changed while the conflict stayed unresolved. A changed local state now gets its own
     * copy instead of being folded into the old one.
     */
    internal fun conflictFingerprint(
        etag: String?,
        lastModified: String?,
        localContent: String? = null,
    ): String? {
        val remote = when {
            !etag.isNullOrBlank() -> "etag:$etag"
            !lastModified.isNullOrBlank() -> "modified:$lastModified"
            else -> return null
        }
        return if (localContent.isNullOrBlank()) remote else "$remote|local:$localContent"
    }

    /**
     * SYN-3: the still-listed copy that was already made for [validator], or null.
     *
     * A dismissed copy no longer counts: the notice is the user's handle on the copy, and a new
     * conflict after a dismissal should produce a new file.
     */
    fun conflictUrlForValidator(context: Context, validator: String): String? {
        // The stored map is URL -> validator, so the lookup has to reverse it: the caller asks by
        // the remote state, not by the copy it does not know yet.
        val url = conflictValidators(context).entries
            .firstOrNull { it.value == validator }
            ?.key
            ?: return null
        return url.takeIf { conflicts(context).contains(it) }
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

    /**
     * SYN-04: drops one notice once the user has acknowledged (or merged) that specific copy.
     *
     * This is the only operation that resolves a notice - a successful canonical upload
     * deliberately does not (SYN-04), because it proves nothing about whether the parked copy
     * was merged. The copy itself is never deleted here: it is on the user's server.
     */
    fun dismissConflict(context: Context, url: String) {
        val remaining = conflicts(context).filterNot { it == url }
        // R2-10: the map is URL -> validator, so the dismissed *URL* is the key to remove.
        // Filtering the values compared a validator against a URL - never equal - and left a dead
        // entry in the preference file after the notice was gone.
        val validators = conflictValidators(context).filterKeys { it != url }
        write(context, remaining, validators)
    }

    /** Drops every notice. The copies themselves are not deleted: they are on the user's server. */
    fun clearConflicts(context: Context) {
        preferences(context).edit()
            .remove(CONFLICTS_KEY)
            .remove(LEGACY_CONFLICT_KEY)
            .remove(CONFLICT_VALIDATORS_KEY)
            .apply()
    }

    /**
     * SYN-04: kept for callers that want to mark a successful canonical upload.
     *
     * It intentionally does **nothing** to the conflict list. The canonical file being this
     * device's again does not prove that a copy parked before the conflict was merged; clearing
     * the notices here was exactly how a copy became unreachable from inside the app. Only
     * [dismissConflict] (the user's "got it") or a restore/merge of that copy removes an entry.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recordSuccess(context: Context) {
    }

    private fun conflictValidators(context: Context): MutableMap<String, String> {
        val stored = preferences(context).getString(CONFLICT_VALIDATORS_KEY, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(stored)
            buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun write(
        context: Context,
        urls: List<String>,
        validators: Map<String, String> = emptyMap(),
    ) {
        val editor = preferences(context).edit().remove(LEGACY_CONFLICT_KEY)
        if (urls.isEmpty()) {
            editor.remove(CONFLICTS_KEY)
        } else {
            editor.putString(CONFLICTS_KEY, JSONArray(urls).toString())
        }
        if (validators.isEmpty()) {
            editor.remove(CONFLICT_VALIDATORS_KEY)
        } else {
            editor.putString(CONFLICT_VALIDATORS_KEY, JSONObject(validators).toString())
        }
        editor.apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
