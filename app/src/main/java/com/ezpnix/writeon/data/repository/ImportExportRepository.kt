package com.ezpnix.writeon.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.util.AutoBackupCrypto
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteFileReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.InvalidKeyException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

sealed class BackupResult {
    data class Success(val message: String) : BackupResult()
    data class Error(val message: String) : BackupResult()
    object BadPassword : BackupResult()
}

/** Carries a non-null result out of the `withLock` block; null means "carry on and report success". */
private class ImportFailure(val result: BackupResult?)

/**
 * Local export/import through the Storage Access Framework.
 *
 * Both directions now share [BackupArchiveCrypto] with the WebDAV workers and [AttachmentArchive]
 * with every other backup producer, so a package written by any path can be restored by any other.
 * The export no longer calls `provider.close()`: it only reads through the live connection to build
 * a portable copy, and closing the process-wide Room instance there made every screen collecting a
 * Flow crash with "attempt to re-open an already-closed object".
 */
class ImportExportRepository(
    private val provider: NoteDatabaseProvider,
    private val context: Context,
    private val mutex: Mutex,
    private val dispatcher: CoroutineDispatcher,
) : NoteFileReader {

    /**
     * Writes an export to [uri]. The old version returned success even when
     * `openOutputStream` handed back null, which told users their backup was saved while nothing
     * had been written anywhere; the null case is now surfaced as an error.
     */
    suspend fun exportBackup(uri: Uri, password: String?): BackupResult {
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: return@withLock ImportFailure(
                            BackupResult.Error(context.getString(R.string.export_failed, ""))
                        )

                    outputStream.use { stream ->
                        val portableFile = File.createTempFile("portable", ".db", context.cacheDir)
                        val tempZipFile = File.createTempFile("backup", ".zip", context.cacheDir)
                        try {
                            provider.writePortableDatabaseCopy(portableFile)
                            val attachments = provider.attachmentDao().getAll()

                            ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOutputStream ->
                                FileInputStream(portableFile).use { inputStream ->
                                    zipOutputStream.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                                    inputStream.copyTo(zipOutputStream)
                                    zipOutputStream.closeEntry()
                                }
                                AttachmentArchive.writeTo(zipOutputStream, attachments)
                            }

                            // Streamed end to end: the zip is copied into the cipher stream, which
                            // writes into the SAF handle. The old readBytes() held the whole
                            // archive - database plus every recording - in memory at once.
                            FileInputStream(tempZipFile).use { zipInput ->
                                if (password != null) {
                                    BackupArchiveCrypto.encryptStream(zipInput, stream, password)
                                } else {
                                    zipInput.copyTo(stream)
                                }
                            }
                            stream.flush()
                        } finally {
                            portableFile.delete()
                            tempZipFile.delete()
                        }
                    }
                    ImportFailure(null)
                }
            }.let { failure ->
                failure.result ?: BackupResult.Success(context.getString(R.string.export_successful))
            }
        } catch (e: Exception) {
            BackupResult.Error(context.getString(R.string.export_failed, e.message ?: ""))
        }
    }

    suspend fun importBackup(uri: Uri, password: String?): BackupResult {
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withLock ImportFailure(BackupResult.Error(context.getString(R.string.import_stream_error)))

                    try {
                        // Decryption streams straight into the extractor: no full-archive ByteArray,
                        // no second copy on disk. A GCM tag failure surfaces while the stream is
                        // read, so a tampered archive aborts before anything is installed.
                        val decrypted = if (password != null) {
                            BackupArchiveCrypto.decryptStream(inputStream, password)
                        } else {
                            inputStream
                        }
                        restoreFromZipStream(decrypted)
                    } finally {
                        inputStream.close()
                    }
                }
                ImportFailure(null)
            }.let { failure ->
                failure.result ?: BackupResult.Success(context.getString(R.string.import_successful))
            }
        } catch (e: BadPaddingException) {
            // GCM/CBC authentication failed: the key is wrong or the archive was tampered with.
            BackupResult.BadPassword
        } catch (e: IllegalBlockSizeException) {
            // The length is wrong before any padding check, so this is a damaged file, not a typo.
            BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
        } catch (e: InvalidKeyException) {
            BackupResult.BadPassword
        } catch (e: IOException) {
            // A wrong passphrase on the streaming path surfaces as an IOException wrapping the
            // crypto failure (CipherInputStream rethrows it); report it as a password problem
            // rather than "import failed" so the user retypes instead of blaming the file.
            val cause = e.cause
            if (cause is BadPaddingException || cause is IllegalBlockSizeException) {
                BackupResult.BadPassword
            } else {
                BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
            }
        } catch (e: Exception) {
            BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
        }
    }

    /**
     * Restores one of the daily automatic backups ([com.ezpnix.writeon.core.util.AutoBackupCrypto]
     * format) from [archiveFile].
     *
     * These archives are encrypted with a device-bound key, so this only works on the install that
     * wrote them - it exists so a corrupted or emptied database can be put back without the user
     * having thought about backups at all. Everything after the decryption is the same staged,
     * atomic restore the SAF path uses.
     */
    suspend fun importAutoBackup(archiveFile: File): BackupResult {
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    if (!archiveFile.isFile) {
                        return@withLock ImportFailure(
                            BackupResult.Error(context.getString(R.string.import_failed, "backup file missing"))
                        )
                    }
                    val tempZip = File.createTempFile("auto-backup", ".zip", context.cacheDir)
                    try {
                        val authenticated = archiveFile.inputStream().use { encrypted ->
                            FileOutputStream(tempZip).use { plain ->
                                AutoBackupCrypto.decryptTo(context, encrypted, plain)
                            }
                        }
                        if (!authenticated) {
                            return@withLock ImportFailure(
                                BackupResult.Error(context.getString(R.string.auto_backup_decrypt_failed))
                            )
                        }
                        FileInputStream(tempZip).use { zip -> restoreFromZipStream(zip) }
                    } finally {
                        tempZip.delete()
                    }
                }
                ImportFailure(null)
            }.let { failure ->
                failure.result ?: BackupResult.Success(context.getString(R.string.import_successful))
            }
        } catch (e: Exception) {
            BackupResult.Error(context.getString(R.string.import_failed, e.message ?: ""))
        }
    }

    /**
     * Extracts a plain (already decrypted) zip stream, rewrites the staged database's attachment
     * paths and swaps the database in - the shared tail of every restore path.
     */
    private fun restoreFromZipStream(zipStream: java.io.InputStream) {
        // Keep the extracted files alive until the atomic replacement has completed. The
        // previous finally block deleted tempDir before replaceDatabaseAtomically read it.
        val tempDir = File(context.cacheDir, "restore-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val restored = java.util.zip.ZipInputStream(zipStream).use { zip ->
                DatabaseRestore.extractAll(zip, tempDir, DatabaseConst.NOTES_DATABASE_FILE_NAME)
            }
            // Rewrite the staged database's attachment paths before it becomes live, so a
            // restore on another device no longer leaves every image and recording pointing
            // at the previous device's file system.
            AttachmentArchive.restore(context, tempDir, restored)
            val dbFile = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
            // A truncated or corrupt archive can still carry a valid 100-byte header. quick_check
            // walks the real pages, so a database that would open but lose rows is rejected here -
            // while the live file is still untouched, so a failed check means "nothing changed".
            if (!DatabaseRestore.quickCheckPasses(restored)) {
                throw IOException(context.getString(R.string.import_failed, "backup database failed integrity check"))
            }
            // Room holds the old file open; it must be closed before the rename. This is
            // deliberately the only remaining close() on the export/import path.
            provider.close()
            DatabaseRestore.replaceDatabaseAtomically(dbFile, restored)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    override fun importFile(uri: Uri): Note {
        val content = context.contentResolver.openInputStream(uri)?.use { fileInputStream ->
            fileInputStream.reader().buffered().readText()
        } ?: throw IOException(context.getString(R.string.import_stream_error))

        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME), null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getStringOrNull(0) else ""
        } ?: ""

        return Note(
            name = name,
            description = content,
        )
    }
}
