package com.ezpnix.writeon.presentation.screens.settings

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.AutoBackupCrypto
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "BackupWorker"

/**
 * Daily automatic backup.
 *
 * It used to write an unencrypted full database to `getExternalFilesDir`, where MTP, file managers
 * and adb could read it - and Android's own cloud backup picked the folder up as well. The schedule
 * never passed a password, so the "encrypt database" setting had no effect here. Every archive is
 * now encrypted with a device-bound key ([AutoBackupCrypto]), lives in internal storage, and only
 * the newest [MAX_ARCHIVES] copies are kept.
 *
 * The archives are restored from Settings -> Cloud ("Restore automatic backup"): the same device
 * and install can read them back, which is what makes them a self-heal path rather than a write-only
 * feature. Moving to a new phone remains the job of the WebDAV and local SAF backups.
 */
class BackupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        return try {
            val backupFolder = File(context.filesDir, BACKUP_DIRECTORY)
            if (!backupFolder.exists()) backupFolder.mkdirs()

            migrateLegacyPlaintextBackups(context, backupFolder)

            val backupFileName = "${DatabaseConst.NOTES_DATABASE_BACKUP_NAME}-${currentDateTime()}.zip.enc"
            val backupFile = File(backupFolder, backupFileName)

            backupDatabase(backupFile)
            rotate(backupFolder)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure()
        }
    }

    private suspend fun backupDatabase(backupFile: File) {
        withContext(Dispatchers.IO) {
            val tempZipFile = File.createTempFile("backup", ".zip", applicationContext.cacheDir)
            val portableFile = File.createTempFile("portable", ".db", applicationContext.cacheDir)
            val provider = NoteDatabaseProvider(applicationContext as Application)

            try {
                provider.writePortableDatabaseCopy(portableFile)
                val attachments = provider.attachmentDao().getAll()

                ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOut ->
                    FileInputStream(portableFile).use { fis ->
                        val entry = ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME)
                        zipOut.putNextEntry(entry)
                        fis.copyTo(zipOut)
                        zipOut.closeEntry()
                    }
                    AttachmentArchive.writeTo(zipOut, attachments)
                }

                // Streamed: the zip is encrypted as it is written, so an archive with a gigabyte of
                // recordings never needs a matching byte array in memory.
                FileOutputStream(backupFile).use { output ->
                    FileInputStream(tempZipFile).use { zipInput ->
                        AutoBackupCrypto.encryptStream(applicationContext, zipInput, output)
                    }
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
                FileOutputStream(target).use { output ->
                    legacy.inputStream().use { input ->
                        AutoBackupCrypto.encryptStream(context, input, output)
                    }
                }
                legacy.delete()
            }.onFailure { error ->
                Log.w(TAG, "Could not migrate a legacy plaintext backup; leaving it in place", error)
            }
        }
    }

    private fun rotate(folder: File) {
        val archives = folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip.enc") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        archives.drop(MAX_ARCHIVES).forEach { it.delete() }
    }

    private companion object {
        const val BACKUP_DIRECTORY = "BackupFolder"
        const val MAX_ARCHIVES = 7

        fun currentDateTime(): String {
            val format = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return format.format(java.util.Date())
        }
    }
}
