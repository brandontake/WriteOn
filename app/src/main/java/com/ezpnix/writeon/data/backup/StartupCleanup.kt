package com.ezpnix.writeon.data.backup

import android.content.Context
import android.util.Log
import java.io.File

/**
 * SEC-11/DAT-05: removes the plaintext temp files a killed export, restore or backup left behind.
 *
 * Every producer writes the database copy, the zip and the encrypted output through
 * `File.createTempFile(..., cacheDir)` and deletes them in a `finally`. A process kill skips that
 * `finally`, and those files hold readable note bodies and decrypted attachments. The OS only
 * evicts `cacheDir` under storage pressure, so without this sweep they can survive indefinitely.
 *
 * The rules are deliberately conservative:
 *  - only `cacheDir` itself is scanned (never recursively, so `cacheDir/media` and subdirectories
 *    are untouched);
 *  - a file must match one of this app's exact producer prefixes, or end with one of its temp
 *    suffixes (`.ziptmp` from the WebDAV upload, `.part` from an interrupted write);
 *  - only files older than [STALE_TEMP_MILLIS] are removed, so a worker that WorkManager starts
 *    right after `Application.onCreate` cannot lose a file it just created.
 *
 * The durable restore journal/work tree under `noBackupFilesDir` is a different tree and is never
 * touched here (staging directories have their own journal-aware cleanup in
 * [com.ezpnix.writeon.core.attachment.AttachmentArchive.cleanupAbandonedStaging]).
 */
object StartupCleanup {

    private const val TAG = "StartupCleanup"

    /** Age guard for a cache temp file; see the class comment. */
    internal const val STALE_TEMP_MILLIS = 5L * 60 * 1000

    /**
     * Exact prefixes of temp files this app's producers create in `cacheDir`.
     *
     * `portable`/`backup` are the long-standing names from [com.ezpnix.writeon.data.repository.BackupExportWriter]
     * and [BackupWorker]; `writeon-portable-` comes from the portable-copy transaction;
     * `webdav-portable` and `notes-backup-` belong to the WebDAV producers/restore download.
     * `unzipped_restore`/`restore-staging` are older layouts that may still be present on an
     * upgrade.
     */
    internal val CACHE_TEMP_PREFIXES = listOf(
        "writeon-portable-",
        "writeon-export",
        "portable",
        "backup",
        "auto-backup",
        "webdav-portable",
        "notes-backup-",
        "unzipped_restore",
        "restore-staging",
    )

    /** Temp suffixes a killed writer can leave behind in `cacheDir`. */
    internal val CACHE_TEMP_SUFFIXES = listOf(".ziptmp", ".part")

    /** Context entry point used by `App.onCreate` on its background IO scope. */
    fun cleanupCacheTemps(
        context: Context,
        now: Long = System.currentTimeMillis(),
    ): Int = cleanupCacheTemps(context.cacheDir, now, STALE_TEMP_MILLIS)

    /**
     * File seam for [cleanupCacheTemps].
     *
     * @return how many files were deleted. Non-matching files, directories and fresh files are
     *   left alone; a delete failure is logged and does not abort the sweep.
     */
    internal fun cleanupCacheTemps(
        cacheDir: File,
        now: Long,
        maxAgeMillis: Long,
    ): Int {
        if (!cacheDir.isDirectory) return 0
        var removed = 0
        cacheDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            if (!isAppTempName(file.name)) return@forEach
            if (now - file.lastModified() < maxAgeMillis) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            } else {
                runCatching { Log.w(TAG, "Could not remove stale temp file ${file.name}") }
            }
        }
        return removed
    }

    private fun isAppTempName(name: String): Boolean =
        CACHE_TEMP_SUFFIXES.any { name.endsWith(it) } ||
            CACHE_TEMP_PREFIXES.any { name.startsWith(it) }
}
