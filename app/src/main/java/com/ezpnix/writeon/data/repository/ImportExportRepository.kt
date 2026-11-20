package com.ezpnix.writeon.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.core.util.AutoBackupCrypto
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.core.util.RestoreCoordinator
import com.ezpnix.writeon.data.backup.FlashcardArchive
import com.ezpnix.writeon.data.backup.SnapshotSidecar
import com.ezpnix.writeon.data.backup.VaultSaltArchive
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
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
                            // N-5: the list comes from the portable-copy transaction, so the
                            // archive's rows and files describe the same instant.
                            val attachments = provider.writePortableDatabaseCopy(portableFile)

                            ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOutputStream ->
                                FileInputStream(portableFile).use { inputStream ->
                                    zipOutputStream.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
                                    inputStream.copyTo(zipOutputStream)
                                    zipOutputStream.closeEntry()
                                }
                                // SEC-4: the decrypting overload, so an encrypted attachment is
                                // stored as plaintext the restoring device can open. A member that
                                // cannot be decrypted aborts the export instead of shipping `WMV1`.
                                AttachmentArchive.writeTo(zipOutputStream, attachments, context)
                                // DAT-11: the deck lives in SharedPreferences, outside the
                                // database copy above; without this member an export/import round
                                // trip lost all cards while reporting success.
                                FlashcardArchive.writeToZip(context, zipOutputStream)
                                // PERF-2: so does the `v3:` envelope salt. A portable archive
                                // restored on another install has no other source for it.
                                VaultSaltArchive.writeToZip(context, zipOutputStream)
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
     * Extracts a plain (already decrypted) zip stream, validates it, prepares the attachment
     * plan, swaps the database in and only then installs the attachment bytes - the shared tail
     * of every restore path. The ordering is the DAT-4 fix: a failed swap never touches a live
     * image or recording.
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
            val dbFile = context.getDatabasePath(DatabaseConst.NOTES_DATABASE_FILE_NAME)
            // quick_check walks the real pages while the live file is still untouched: a
            // database that would open but lose rows is rejected here, before any attachment
            // file is moved over the live ones (the old order overwrote files for a restore
            // that then failed).
            if (!DatabaseRestore.quickCheckPasses(restored)) {
                throw IOException(context.getString(R.string.import_failed, "backup database failed integrity check"))
            }
            // D8: refuse a newer or unrecognised schema before the first side effect.
            DatabaseRestore.assertArchiveCompatible(restored)

            // DAT-4: prepare rewrites the staged database's attachment paths but deliberately
            // writes nothing into filesDir. A rejected archive or a failed swap therefore
            // cannot overwrite a single live image or recording. Pre-2.0 archives without an
            // `attachments/` directory return an empty plan, which is a no-op below.
            val preparedAttachments = AttachmentArchive.prepare(context, tempDir, restored)
            // PERF-2: capture the live salt before Room closes. A failed attachment install rolls
            // the database back, and the salt has to follow it or the old `v3:` rows lose the KEK
            // they were sealed with.
            val oldSalt = SharedPreferencesVaultKeyStore(context).encodedSalt()
            // N-2: same reasoning for the flashcard deck. The archive may overwrite it below; the
            // `.bak` snapshot of the current database needs the deck that belonged to it.
            val oldDeck = FlashcardArchive.readRaw(context)
            // DAT-7: everything validated and staged; the next statement closes the shared Room
            // connection. Publishing the restore now covers the app and stops the note collectors
            // *before* the connection dies, instead of leaving them to hit a closed database in
            // the restart window. A rejected archive never reaches here, so it cannot blank the UI.
            RestoreCoordinator.begin()
            try {
                // Room holds the old file open; it must be closed before the rename. This is
                // deliberately the only remaining close() on the export/import path.
                provider.close()
                val previousBackup = DatabaseRestore.replaceDatabaseAtomically(dbFile, restored)
                // N-1/N-2: `.bak` is now the replaced database. Stamp it with the salt and deck
                // that database expects, so "restore the previous database" can put all three
                // back together. A failed stamp rolls the swap back: an undo that silently
                // applies the archive's salt to the old rows is exactly the bug this fixes.
                if (previousBackup != null &&
                    !SnapshotSidecar.write(previousBackup, oldSalt, oldDeck)
                ) {
                    val rolledBack = DatabaseRestore.rollbackFromBackup(dbFile)
                    if (rolledBack) {
                        VaultSaltArchive.restoreEncoded(context, oldSalt)
                        FlashcardArchive.restoreRaw(context, oldDeck)
                    }
                    throw IOException(
                        context.getString(R.string.import_failed, "could not record snapshot metadata")
                    )
                }
                // The archive's salt must be live before any failure-prone attachment work: from
                // here on the restored database is the one Room will open. A present-but-invalid
                // member is refused like a corrupt database rather than left to make every `v3:`
                // row unreadable.
                val saltAccepted = VaultSaltArchive.restoreFromStaging(context, tempDir)
                if (File(tempDir, VaultSaltArchive.ENTRY_NAME).isFile && !saltAccepted) {
                    val rolledBack = DatabaseRestore.rollbackFromBackup(dbFile)
                    if (rolledBack) VaultSaltArchive.restoreEncoded(context, oldSalt)
                    throw IOException(
                        context.getString(R.string.import_failed, "backup vault salt is invalid")
                    )
                }
                // Only now may the bytes land: the file every row points at is the restored database.
                try {
                    AttachmentArchive.install(context, preparedAttachments)
                } catch (e: IOException) {
                    // The database was already swapped but the attachments could not follow it. Put
                    // the previous database (whose files install() has just restored) back in place
                    // rather than leave rows pointing at a half-installed set of files.
                    val rolledBack = DatabaseRestore.rollbackFromBackup(dbFile)
                    // The salt belongs to the database that is live again; keeping the archive's
                    // would make the rolled-back rows unreadable even though their file was restored.
                    if (rolledBack) VaultSaltArchive.restoreEncoded(context, oldSalt)
                    throw IOException(
                        if (rolledBack) {
                            "Attachments could not be installed; the previous database was restored"
                        } else {
                            "Attachments could not be installed and the previous database could not be restored"
                        },
                        e
                    )
                }
                // SEC-5: a restored old database may contain plaintext rows again. Clear the
                // install-wide "already sealed" marker so the next launch sweeps the restored copy
                // instead of trusting a flag written against the database that was just replaced.
                LegacyPlaintextBackfillFlag.reset(context)
                // SEC-4: captions travel plaintext inside the archive for the same reason the deck
                // does, so the caption sweep needs the same reset; otherwise the flag from the
                // replaced database would leave restored captions readable.
                LegacyCaptionBackfillFlag.reset(context)
                // DAT-11: the deck travels beside the database (extractAll already unpacked it into
                // tempDir). It is reapplied here, after the swap, so a rejected archive never
                // rewrites the stored deck; pre-DAT-11 archives have no member and keep the
                // current one.
                FlashcardArchive.restoreFromStaging(context, tempDir)
                // Same post-swap reconciliation as the cloud path: rows without files are
                // reported, files without rows go to quarantine (no-op for archives that
                // predate the attachments table).
                AttachmentArchive.reconcileAfterRestore(context, dbFile)
            } catch (e: Throwable) {
                // Every failure/rollback path lands here: the caller reports it and the app must
                // become usable again. Success deliberately falls through with the flag still set;
                // the caller restarts the process for the database that was just installed.
                RestoreCoordinator.end()
                throw e
            }
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
