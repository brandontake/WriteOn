package com.ezpnix.writeon.data.backup

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.AutoBackupCrypto
import com.ezpnix.writeon.core.util.BackupRestoreLock
import com.ezpnix.writeon.data.local.database.BackupDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipOutputStream

private const val TAG = "BackupWorker"

/**
 * Daily automatic backup.
 *
 * It used to write an unencrypted full database to `getExternalFilesDir`, where MTP, file managers
 * and adb could read it - and Android's own cloud backup picked the folder up as well. The schedule
 * never passed a password, so the "encrypt database" setting had no effect here. Every archive is
 * now encrypted with a device-bound key ([AutoBackupCrypto]), lives in internal storage, and is
 * kept under two budgets: at most [BackupRotation.MAX_ARCHIVES] copies, at most
 * [BackupRotation.MAX_TOTAL_BYTES] in total, and never fewer than
 * [BackupRotation.MIN_ARCHIVES] restore points (see [BackupRotation.rotate], D6).
 *
 * The archives are restored from Settings -> Cloud ("Restore automatic backup"): the same device
 * and install can read them back, which is what makes them a self-heal path rather than a write-only
 * feature. Moving to a new phone remains the job of the WebDAV and local SAF backups.
 */
open class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        return try {
            val backupFolder = File(context.filesDir, BACKUP_DIRECTORY)
            if (!backupFolder.exists()) backupFolder.mkdirs()

            // A run killed mid-write leaves a `.part` behind. It is never a restore point, so it
            // must not survive forever and be mistaken for one; a day-old part cannot belong to a
            // live process, so remove those before writing anything new.
            BackupRotation.deleteStaleParts(backupFolder)

            migrateLegacyPlaintextBackups(context, backupFolder)

            val backupFileName = "${DatabaseConst.NOTES_DATABASE_BACKUP_NAME}-${currentDateTime()}.zip.enc"
            val backupFile = File(backupFolder, backupFileName)

            // DAT-2: written as `.part`, fsynced, read back through the AEAD tag, and only then
            // renamed. A kill or a full disk leaves a `.part` (deleted by the helper), never a
            // truncated file under the final name that rotation would count as a restore point.
            backupDatabase(backupFile)
            BackupRotation.rotate(backupFolder)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure()
        }
    }

    private suspend fun backupDatabase(backupFile: File) {
        withContext(Dispatchers.IO) {
            // DAT-8: the portable copy, the attachment rows and the zip encrypted from them form
            // one snapshot and must hold the same restore mutex the import/restore paths take.
            // Without it a restore could close and swap the database between the copy and the
            // attachment query, producing an archive whose notes and media disagree. Rotation is
            // outside the lock on purpose: it touches only this folder, never the database.
            BackupRestoreLock.mutex.withLock {
                val tempZipFile = File.createTempFile("backup", ".zip", applicationContext.cacheDir)
                val portableFile = File.createTempFile("portable", ".db", applicationContext.cacheDir)
                val provider = BackupDatabase.provider(applicationContext)

                try {
                    // N-5: rows and files come from the same portable-copy transaction.
                    val attachments = provider.writePortableDatabaseCopy(portableFile)

                    ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOut ->
                        // CQ-03: the shared writer owns the archive layout (portable database,
                        // attachment tree, flashcard deck, vault salt), so this producer cannot
                        // drift from the SAF export and the WebDAV upload. The `.part`, fsync,
                        // authenticate and rename sequence below stays here because it is specific
                        // to the device-bound automatic backup format.
                        BackupArchiveWriter.write(
                            zip = zipOut,
                            context = applicationContext,
                            portableDatabase = portableFile,
                            attachments = attachments,
                        )
                    }

                    // Streamed: the zip is encrypted as it is written, so an archive with a gigabyte of
                    // recordings never needs a matching byte array in memory. The helper owns the
                    // `.part` -> validate -> rename sequence so the final name only ever appears on a
                    // file that authenticated end to end.
                    FileInputStream(tempZipFile).use { zipInput ->
                        encryptIntoVerifiedPart(applicationContext, zipInput, backupFile)
                    }
                } finally {
                    // No close() here on purpose: NoteDatabaseProvider's connection is process-wide (see
                    // its companion), and closing it while the UI was collecting a Flow crashed the app
                    // with "attempt to re-open an already-closed object". The auto backup only reads
                    // through the shared connection, so there is nothing to release.
                    portableFile.delete()
                    tempZipFile.delete()
                }
            }
        }
    }

    /**
     * DAT-2: writes [destination] through a sibling `.part`, proves the ciphertext authenticates,
     * and only then renames it into place.
     *
     * The part is in the same directory, so the rename is a same-filesystem atomic move. Reading
     * the finished stream back is the real integrity check: Tink's streaming AEAD only lets a
     * stream authenticate against its final tag, so a truncated or torn write makes
     * [AutoBackupCrypto.decryptTo] return false instead of leaving a half archive that rotation
     * and the restore picker would treat as a valid restore point.
     */
    private fun encryptIntoVerifiedPart(context: Context, source: InputStream, destination: File) {
        val part = File(destination.parentFile, destination.name + BackupRotation.PART_SUFFIX)
        var published = false
        try {
            FileOutputStream(part).use { output ->
                // encryptStream wraps `output` so Tink's close() cannot close it; the stream is
                // still live here, which is what makes the fsync below legal.
                AutoBackupCrypto.encryptStream(context, source, output)
                output.flush()
                // close() flushes the stream's own buffer but does not ask the kernel to persist
                // the bytes; sync before the file is treated as a completed archive.
                output.fd.sync()
            }

            val authentic = FileInputStream(part).use { written ->
                AutoBackupCrypto.decryptTo(context, written, OutputStream.nullOutputStream())
            }
            check(authentic) { "The finished automatic backup did not authenticate" }

            if (!part.renameTo(destination)) {
                throw IOException("Could not publish ${destination.name} from ${part.name}")
            }
            published = true
        } finally {
            // The part is the only file a failure may leave behind; the final name must never
            // exist until the archive has been proven readable. `finally` also covers
            // cancellation and errors, not just the exceptions checked above.
            if (!published) part.delete()
        }
    }

    /**
     * Old builds put unencrypted zips in `Android/data/.../files/BackupFolder`. They are encrypted
     * into the new location on the first run and the readable originals are removed.
     */
    private fun migrateLegacyPlaintextBackups(context: Context, targetFolder: File) {
        val legacyFolder = context.getExternalFilesDir(null)?.let { File(it, BACKUP_DIRECTORY) }
            ?: return
        if (!legacyFolder.isDirectory || legacyFolder.canonicalPath == targetFolder.canonicalPath) return

        legacyFolder.listFiles().orEmpty().forEach { legacy ->
            if (!legacy.isFile || !legacy.name.endsWith(".zip")) return@forEach
            runCatching {
                val name = legacy.name.removeSuffix(".zip") + ".zip.enc"
                val target = File(targetFolder, name)
                legacy.inputStream().use { input ->
                    encryptIntoVerifiedPart(context, input, target)
                }
                legacy.delete()
            }.onFailure { error ->
                Log.w(TAG, "Could not migrate a legacy plaintext backup; leaving it in place", error)
            }
        }
    }

    private companion object {
        const val BACKUP_DIRECTORY = "BackupFolder"

        fun currentDateTime(): String {
            val format = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return format.format(java.util.Date())
        }
    }
}

/**
 * The archive folder's retention and cleanup policy, split out of [BackupWorker] so it can be
 * exercised with plain files on the JVM.
 *
 * DAT-2: only names ending in `.zip.enc` are restore points. A `*.part` belongs to a write that
 * never finished and must never be counted, protected by [MIN_ARCHIVES], or offered for restore;
 * zero-length `.zip.enc` files are debris from the old direct-write implementation and are removed
 * rather than allowed to occupy a retention slot.
 */
internal object BackupRotation {

    const val ARCHIVE_SUFFIX = ".zip.enc"
    const val PART_SUFFIX = ".part"

    /** Upper bound on archive *count* - the historical "newest seven" rule. */
    const val MAX_ARCHIVES = 7

    /**
     * Never delete the newest N archives, whatever they weigh: the size budget may shrink the
     * folder, but it may never empty it - a restore point that is always present is the whole
     * point of the daily run.
     */
    const val MIN_ARCHIVES = 3

    /**
     * Total-size budget for `BackupFolder`, counted over every `.zip.enc` on disk (D6). Each
     * archive embeds the full attachment set, so this - not the count - is what actually caps
     * the media multiplier; without it 7 copies of a 500 MB library meant ~3.5 GB private.
     * 200 MiB keeps several restore points while leaving the volume to Room and attachment
     * writes. When exceeded, the oldest archives are deleted first (see [rotate]).
     */
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** A part older than a day cannot belong to a live write and is safe to remove. */
    const val STALE_PART_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Removes abandoned `.part` files from earlier runs (DAT-2).
     *
     * The current run deletes its own part on failure; this only catches the one a process kill
     * left behind. It intentionally ignores every other suffix so it can never touch an archive.
     */
    fun deleteStaleParts(
        folder: File,
        now: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = STALE_PART_MILLIS,
    ) {
        folder.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.name.endsWith(PART_SUFFIX)) return@forEach
            if (now - file.lastModified() <= maxAgeMillis) return@forEach
            if (file.delete()) {
                Log.i(TAG, "Removed stale partial backup ${file.name}")
            } else {
                Log.w(TAG, "Could not remove stale partial backup ${file.name}")
            }
        }
    }

    /**
     * Keeps the archive folder inside [maxTotalBytes] while always retaining the newest
     * [minArchives] valid copies (D6).
     *
     * The old rule was "keep the newest [maxArchives]" and nothing else. Every archive contains
     * the *full* attachment set, so a user with 500 MB of recordings paid 7x that in
     * `filesDir/BackupFolder` - enough to fill the volume, fail the backup silently and, worse,
     * make the next Room write die with `SQLITE_FULL` and lose the edit being saved.
     *
     * Deletion walks oldest -> newest and stops as soon as both budgets are met; [minArchives]
     * is a floor the size budget may not cut below, so a single oversized backup can never leave
     * the user with nothing to restore from. Zero-length archives are not restore points and are
     * deleted before they can consume a slot.
     */
    fun rotate(
        folder: File,
        maxArchives: Int = MAX_ARCHIVES,
        minArchives: Int = MIN_ARCHIVES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ) {
        val survivors = ArrayList<File>()
        folder.listFiles().orEmpty().forEach { file ->
            // `.zip.enc.part` does not end with `.zip.enc`, but spell the check out so a future
            // suffix change cannot quietly make an unverified file eligible for retention.
            if (!file.isFile || !file.name.endsWith(ARCHIVE_SUFFIX) || file.name.endsWith(PART_SUFFIX)) {
                return@forEach
            }
            if (file.length() == 0L) {
                if (file.delete()) {
                    Log.w(TAG, "Removed empty backup ${file.name}")
                } else {
                    Log.w(TAG, "Could not remove empty backup ${file.name}")
                }
                return@forEach
            }
            survivors += file
        }

        val archives = survivors.sortedWith(
            compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }
        )

        var totalBytes = archives.sumOf { it.length() }
        var kept = archives.size
        for (archive in archives.asReversed()) { // oldest first
            if (kept <= minArchives) break
            if (kept <= maxArchives && totalBytes <= maxTotalBytes) break
            val size = archive.length()
            if (archive.delete()) {
                totalBytes -= size
                kept--
            } else {
                // A locked/undeletable file must not turn the loop into a spin over newer archives
                // the budget asked us to keep; log and try again on the next run.
                Log.w(TAG, "Could not delete old backup ${archive.name}")
                break
            }
        }
    }
}
