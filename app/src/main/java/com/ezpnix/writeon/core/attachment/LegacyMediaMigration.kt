package com.ezpnix.writeon.core.attachment

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * SEC-08: one-time encryption of media written before SEC-4 existed.
 *
 * New media is sealed on write ([MediaVault.encryptInPlace]) and an old file is encrypted the next
 * time its note is saved, but a picture or recording that is never touched again would otherwise
 * stay plaintext forever. This startup pass walks the private media roots once and encrypts every
 * regular file that is not already a `WMV1` container.
 *
 * The completion marker is a **dedicated** preference (never the note/caption backfill flags) and is
 * written only when a pass ended with zero failures and nothing deferred. A failure - no Keystore
 * key yet, a full disk, an unreadable file - leaves the marker absent so the next launch retries;
 * files that were already encrypted are skipped and counted instead of being re-encrypted, which
 * keeps the retry cheap. A file that is still being written is deferred rather than touched (see
 * [RECENT_WRITE_WINDOW_MILLIS]), which also keeps the marker absent for the next launch.
 *
 * Scope is deliberately narrow:
 * - the two internal media roots, `filesDir/attachments` and `filesDir/audios`;
 * - the legacy app-specific external root, where 2.0.x kept picked pictures;
 * - only direct children that are regular files. Subdirectories, the quarantine tree
 *   (`filesDir/attachments-quarantine`) and the restore staging/journal trees (under
 *   `noBackupFilesDir`) are never visited, so reconciliation candidates and an in-flight restore
 *   cannot be encrypted or moved by this pass.
 *
 * A restored portable archive writes decrypted attachment members, but its completion needs no
 * marker reset here: [AttachmentArchive.install] re-encrypts every file it installs. That
 * re-encryption is best-effort by design, so a restore that could not reach the media key is the one
 * way a file can become plaintext after this pass finished; SEC-4's lazy write path seals it again
 * the next time the note is saved. The `.bak` undo is different - it moves an older snapshot back
 * unchanged - and calls [reset] before its process restarts.
 */
object LegacyMediaMigration {

    private const val TAG = "LegacyMediaMigration"

    /** Dedicated preferences file; the note/caption backfill markers live elsewhere on purpose. */
    internal const val PREFS_NAME = "media_migration_prefs"

    /** Versioned key so a future format change can run a new pass without reusing an old marker. */
    internal const val COMPLETION_KEY = "legacy_media_migration_v1"

    /**
     * Files whose modification time is newer than this are deferred to a later launch.
     *
     * A recording is written straight into `filesDir/audios` while MediaRecorder holds it open, and
     * a share/pick copy can be landing in `filesDir/attachments` at the same moment. Renaming an
     * encryption temp over such a file would replace the directory entry while its writer still
     * holds the old inode: the writer's bytes would be lost when it closes. Both writers refresh the
     * modification time continuously, so a recently modified file is either still being written or
     * belongs to a completed write that its own path already encrypted. Deferring costs one launch
     * (the marker stays absent) and never loses bytes.
     */
    internal const val RECENT_WRITE_WINDOW_MILLIS = 2 * 60 * 1000L

    /** What one pass did; [complete] is the only condition under which the marker may be set. */
    data class MigrationResult(
        val encrypted: Int,
        val alreadyEncrypted: Int,
        val failures: Int,
        /** Files too recently modified to touch safely; they keep the marker absent. */
        val deferred: Int = 0,
    ) {
        /** True when every file this pass saw is encrypted afterwards (or was already). */
        val complete: Boolean get() = failures == 0 && deferred == 0

        /** How many files the pass actually had to look at, excluding subdirectories. */
        val attempted: Int get() = encrypted + alreadyEncrypted
    }

    /**
     * Pure policy: only a regular file without the `WMV1` header needs this pass.
     *
     * Kept separate from the walk so a plain JVM test can pin the header decision without a Context,
     * a Keystore or media bytes that decrypt.
     */
    fun needsAtRestEncryption(file: File): Boolean =
        file.isFile && !MediaFileCipher.hasEncryptedHeader(file)

    /**
     * The roots this pass owns.
     *
     * The external root is included because 2.0.x wrote picked pictures straight there; it is the
     * app-specific external files directory, not shared storage, and [AttachmentFiles] still treats
     * it as a legacy media root for deletion. A null external root (storage not mounted) simply
     * leaves the internal roots.
     */
    fun mediaDirectories(context: Context): List<File> = buildList {
        add(File(context.filesDir, AttachmentArchive.DIRECTORY))
        add(File(context.filesDir, AttachmentFiles.AUDIO_DIRECTORY))
        context.getExternalFilesDir(null)?.let { external ->
            add(external)
            // 2.0.x also used the media subdirectories below the external root; include them so a
            // plaintext recording or picture left there is migrated too. The scan stays
            // non-recursive, so this adds exactly these known roots.
            add(File(external, AttachmentArchive.DIRECTORY))
            add(File(external, AttachmentFiles.AUDIO_DIRECTORY))
        }
    }

    /** True once an entire pass finished with nothing encrypted-failed and nothing deferred. */
    fun isComplete(context: Context): Boolean =
        prefs(context).getBoolean(COMPLETION_KEY, false)

    /**
     * Runs the pass unless a previous one completed.
     *
     * [encrypt] is injectable so the walk, the counts and the retry semantics can be tested with a
     * fake cipher; production always passes [MediaVault.encryptInPlace]. The marker is committed
     * only after [MigrationResult.complete], and never on an exception: a thrown failure also leaves
     * it absent so the next launch retries.
     */
    fun migrateIfNeeded(
        context: Context,
        encrypt: (File) -> Boolean = { MediaVault.encryptInPlace(context, it) },
    ): MigrationResult = migrateIfNeeded(context, mediaDirectories(context), encrypt)

    /**
     * [migrateIfNeeded] with the roots injectable.
     *
     * The tests use it to keep the scan inside a [java.nio.file.Files]-temporary tree, so an
     * unrelated file in Robolectric's shared external root cannot change the counts. Production
     * always uses [mediaDirectories].
     */
    @Synchronized
    internal fun migrateIfNeeded(
        context: Context,
        directories: List<File>,
        encrypt: (File) -> Boolean,
        recentWindowMillis: Long = RECENT_WRITE_WINDOW_MILLIS,
    ): MigrationResult {
        if (isComplete(context)) return MigrationResult(encrypted = 0, alreadyEncrypted = 0, failures = 0)
        val result = migrateDirectories(directories, encrypt, recentWindowMillis = recentWindowMillis)
        if (result.complete) markComplete(context)
        return result
    }

    /**
     * The Context-free half of [migrateIfNeeded], for tests and for callers that own their tree.
     *
     * Non-recursive by design: only the direct regular files of each root are candidates. A failed
     * encryptor call is counted and the file is left exactly as it was, so the next pass can retry
     * it after the marker stayed absent. A file modified within [recentWindowMillis] is deferred for
     * the writer-race reason documented on [RECENT_WRITE_WINDOW_MILLIS]; [now] is injectable so the
     * decision is testable without sleeping.
     */
    internal fun migrateDirectories(
        directories: List<File>,
        encrypt: (File) -> Boolean,
        now: Long = System.currentTimeMillis(),
        recentWindowMillis: Long = RECENT_WRITE_WINDOW_MILLIS,
    ): MigrationResult {
        var encrypted = 0
        var alreadyEncrypted = 0
        var failures = 0
        var deferred = 0

        directories.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            directory.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                if (!needsAtRestEncryption(file)) {
                    alreadyEncrypted++
                    return@forEach
                }
                val lastModified = file.lastModified()
                if (recentWindowMillis > 0 && lastModified > 0 && now - lastModified < recentWindowMillis) {
                    deferred++
                    return@forEach
                }
                val success = runCatching { encrypt(file) }
                    .onFailure { error ->
                        Log.w(TAG, "Could not encrypt legacy media ${file.name}", error)
                    }
                    .getOrDefault(false)
                if (success) {
                    encrypted++
                } else {
                    failures++
                    Log.w(
                        TAG,
                        "Legacy media ${file.name} stays plaintext; the pass will retry on the next launch"
                    )
                }
            }
        }
        return MigrationResult(encrypted, alreadyEncrypted, failures, deferred)
    }

    @SuppressLint("ApplySharedPref")
    private fun markComplete(context: Context) {
        // commit(), not apply(): a previous test/launch's asynchronous apply could land after a
        // reset (or after the next startup's clear) and falsely mark a plaintext tree as done.
        // This marker gates a security migration, so the write is synchronous.
        prefs(context).edit().putBoolean(COMPLETION_KEY, true).commit()
    }

    /**
     * Clears the completion marker after an attachment tree that may predate this pass was put back.
     *
     * An archive restore does not need this: [AttachmentArchive.install] re-encrypts every member it
     * installs. The `.bak` undo does: [AttachmentSnapshots.install] moves the snapshot's bytes back
     * unchanged, and a snapshot captured by a build before this migration can still be plaintext.
     * `commit()` for the same reason as the note/caption flags - the caller is on a restore path that
     * restarts the process, and an `apply()` lost to that kill would leave "done" set.
     */
    @SuppressLint("ApplySharedPref")
    fun reset(context: Context) {
        prefs(context).edit().remove(COMPLETION_KEY).commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
