package com.ezpnix.writeon.data.repository

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.attachment.AttachmentSnapshots
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.core.util.AutoBackupCrypto
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.core.util.DatabaseRestore
import com.ezpnix.writeon.core.util.RestoreCoordinator
import com.ezpnix.writeon.data.backup.BackupArchiveWriter
import com.ezpnix.writeon.data.backup.BackupEncryptionPolicy
import com.ezpnix.writeon.data.backup.FlashcardArchive
import com.ezpnix.writeon.data.backup.RestoreJournal
import com.ezpnix.writeon.data.backup.SnapshotSidecar
import com.ezpnix.writeon.data.backup.VaultSaltArchive
import com.ezpnix.writeon.data.local.database.LegacyCaptionBackfillFlag
import com.ezpnix.writeon.data.local.database.LegacyPlaintextBackfillFlag
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteFileReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.InvalidKeyException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

sealed class BackupResult {
    data class Success(val message: String) : BackupResult()

    /**
     * [passwordRequired] is the SEC-03 typed signal: the export was refused because the policy
     * requires encryption (the toggle or vault content) and the caller supplied no password. The
     * message is already user-facing; the flag lets a caller reopen the password dialog instead of
     * presenting it as a generic failure.
     */
    data class Error(val message: String, val passwordRequired: Boolean = false) : BackupResult()

    object BadPassword : BackupResult()
}

/** Carries a non-null result out of the `withLock` block; null means "carry on and report success". */
private class ImportFailure(val result: BackupResult?)

/**
 * DAT-06: builds a complete export archive before it touches the user's chosen document.
 *
 * The previous pipeline opened the SAF destination first, which truncates an existing backup; any
 * later failure - the portable copy, an undecryptable attachment, a full volume - left a partial
 * file where the previous good backup had been. This writer assembles the zip and the encrypted
 * (or plain) stream in private temp files, verifies the result is non-empty, and only then opens
 * [destination] and stream-copies. The copies are streamed, so a library with hours of recordings
 * never needs a matching byte array in memory.
 *
 * The `writeArchiveMembers` seam is what the failing-writer test drives: an exception thrown
 * there must leave the destination exactly as it was. The production caller hands it
 * [com.ezpnix.writeon.data.backup.BackupArchiveWriter], so the SAF export shares the archive layout
 * with the automatic and WebDAV backups (CQ-03) while keeping this temp-first SEC-03/DAT-06 order.
 */
internal object BackupExportWriter {

    /**
     * @return null on success, or the user-facing failure that kept the destination untouched.
     *   An I/O error thrown after the final copy started is propagated to the caller, which owns
     *   the result mapping and the temp-file cleanup in its `finally`.
     */
    suspend fun write(
        context: Context,
        destination: Uri,
        password: String?,
        writePortableCopy: suspend (File) -> List<Attachment>,
        writeArchiveMembers: (ZipOutputStream, List<Attachment>, File) -> Unit,
    ): BackupResult? {
        // Registered as they are created: a failure while creating the second or third temp file
        // must still remove the ones that already exist.
        val tempFiles = ArrayList<File>(3)
        try {
            val portableFile = File.createTempFile("portable", ".db", context.cacheDir)
                .also { tempFiles.add(it) }
            val tempZipFile = File.createTempFile("backup", ".zip", context.cacheDir)
                .also { tempFiles.add(it) }
            val completeArchive = File.createTempFile("writeon-export", ".archive", context.cacheDir)
                .also { tempFiles.add(it) }
            val attachments = writePortableCopy(portableFile)

            ZipOutputStream(FileOutputStream(tempZipFile)).use { zipOutputStream ->
                // CQ-03: the shared writer writes the portable database, the attachment tree, the
                // flashcard deck and the vault salt; this class only decides *when* the archive is
                // built (private temp files first, destination last).
                writeArchiveMembers(zipOutputStream, attachments, portableFile)
            }

            // Same single streaming pass the direct export used, but into a temp file: the SAF
            // handle is not touched while anything can still fail.
            FileInputStream(tempZipFile).use { zipInput ->
                FileOutputStream(completeArchive).use { archive ->
                    if (password != null) {
                        BackupArchiveCrypto.encryptStream(zipInput, archive, password)
                    } else {
                        zipInput.copyTo(archive)
                    }
                    archive.flush()
                }
            }
            if (completeArchive.length() == 0L) {
                // Same shape as the "no output stream" refusal below: no untranslated detail is
                // spliced into the localized message.
                return BackupResult.Error(context.getString(R.string.export_failed, ""))
            }

            // Every member and every byte exists now. Only this final copy may change what the
            // user chose to overwrite.
            val outputStream = context.contentResolver.openOutputStream(destination)
                ?: return BackupResult.Error(context.getString(R.string.export_failed, ""))
            outputStream.use { stream ->
                FileInputStream(completeArchive).use { input -> input.copyTo(stream) }
                stream.flush()
            }
            return null
        } finally {
            // The destination is deliberately absent here: a failure deletes temp files only.
            tempFiles.forEach { it.delete() }
        }
    }
}

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
     *
     * DAT-06: the destination is opened only after the complete archive exists in a temp file.
     * It used to be opened - and therefore truncated - before the zip was built, so any failure
     * left an invalid file where the user's previous backup had been. A failure before the final
     * copy now leaves the destination byte-for-byte untouched.
     *
     * SEC-03: encryption is no longer optional when the library contains vault rows. A plaintext
     * archive would carry the decrypted bodies, the decrypted attachments (including those of
     * vault notes, because [AttachmentArchive] writes portable media) and the vault salt. The
     * policy is `encryptBackup || hasVaultContent`; when it requires a password and none was
     * supplied, the export is refused before any temp file is built and the typed
     * [BackupResult.Error.passwordRequired] result tells the caller to re-prompt instead of
     * blaming the file. A plaintext export therefore remains possible only after the user
     * explicitly turned `encryptBackup` off *and* the library has no vault rows.
     */
    suspend fun exportBackup(uri: Uri, password: String?): BackupResult {
        return try {
            withContext(dispatcher) {
                mutex.withLock {
                    // Queried at export time, not from the cached Settings snapshot: a vault note
                    // may have been created after the settings screen was opened. The `||` also
                    // short-circuits the preference read when vault content already decides the
                    // outcome.
                    val hasVaultContent = BackupEncryptionPolicy.hasVaultContent(context)
                    val encryptionRequired = BackupEncryptionPolicy.required(
                        encryptBackupSetting = hasVaultContent ||
                            BackupEncryptionPolicy.encryptBackupSetting(context),
                        hasVaultContent = hasVaultContent,
                    )
                    if (encryptionRequired && password.isNullOrEmpty()) {
                        BackupResult.Error(
                            context.getString(R.string.backup_vault_requires_password),
                            passwordRequired = true,
                        )
                    } else {
                        BackupExportWriter.write(
                            context = context,
                            destination = uri,
                            password = password,
                            // N-5: the list comes from the portable-copy transaction, so the
                            // archive's rows and files describe the same instant.
                            writePortableCopy = provider::writePortableDatabaseCopy,
                        ) { zipOutputStream, attachments, portableDatabase ->
                            // CQ-03: the shared writer owns the four common members, so this path
                            // cannot produce an archive that is missing something the automatic
                            // and WebDAV producers include.
                            BackupArchiveWriter.write(
                                zip = zipOutputStream,
                                context = context,
                                portableDatabase = portableDatabase,
                                attachments = attachments,
                                // SEC-03: a plaintext archive (no password was supplied, so the
                                // policy proved there is no vault content and the user disabled
                                // encryption) must not carry the vault salt. An encrypted archive
                                // keeps it so `v3:` rows survive a restore.
                                includeVaultSalt = password != null,
                            )
                        }
                    }
                }
            }.let { failure ->
                failure ?: BackupResult.Success(context.getString(R.string.export_successful))
            }
        } catch (e: CancellationException) {
            // CQ-04: a cancelled export is not an export failure. Rethrowing keeps structured
            // concurrency intact; the caller never sees a delivered result for work it stopped.
            throw e
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
        } catch (e: CancellationException) {
            // CQ-04: cancellation must not be converted into "import failed". Rethrowing lets the
            // caller see the job as cancelled instead of receiving a delivered result.
            throw e
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
        } catch (e: CancellationException) {
            // CQ-04: a cancelled automatic restore is not a failed one; keep the cancellation.
            throw e
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
        // DAT-05: the attachment members are staged in the durable attachment-restore tree, not
        // in cacheDir. AttachmentArchive.install's journal records these as its source paths and
        // survives a process kill on purpose; a cache eviction would have deleted the sources and
        // made recovery mistake "source missing" for "already installed".
        // REG-04: this is this operation's own child of the staging root, so a concurrent cloud
        // restore can never delete it. Both imports already run inside the shared restore mutex,
        // which is where this directory is created and removed.
        val tempDir = AttachmentArchive.newStagingDirectory(context).apply {
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
            var attachmentSnapshotId: String? = null
            var snapshotPaired = false
            // REG-04: keep this transition's ownership token; a stale failure path must not clear
            // the overlay of a newer restore.
            val restoreToken = RestoreCoordinator.begin()
            try {
                // Room holds the old file open; it must be closed before the rename. This is
                // deliberately the only remaining close() on the export/import path.
                provider.close()
                // N-1: capture the attachment bytes after Room closed, so no committed save can
                // add a row between the database snapshot and this file walk. The snapshot is
                // hard-linked, not copied, and is paired with `.bak` in the callback below.
                attachmentSnapshotId = AttachmentSnapshots.capture(context)
                    ?: throw IOException(
                        context.getString(R.string.import_failed, "could not snapshot attachments")
                    )
                // N-1 restart half: record the database hash and the new prefs state before the
                // move. A kill after the move but before the salt/deck apply is finished at next
                // launch; a kill before it is recognised by the hash and changes nothing. The
                // deck is sealed, never stored as plaintext.
                val newSalt = VaultSaltArchive.readStaged(tempDir)
                if (newSalt != null && !VaultSaltArchive.isValidEncoded(newSalt)) {
                    throw IOException(
                        context.getString(R.string.import_failed, "backup vault salt is invalid")
                    )
                }
                // DAT-04: a deck member that exists but cannot be read/re-sealed is not "no deck
                // to apply". Abort before the swap instead of writing a journal that would later
                // leave the restored database with the old deck (or clear it).
                val stagedDeck = File(tempDir, FlashcardArchive.ENTRY_NAME)
                val newDeck = FlashcardArchive.readStagedPlainJson(tempDir)
                    ?.let(FlashcardArchive::sealForRecovery)
                if (stagedDeck.isFile && newDeck == null) {
                    throw IOException(
                        context.getString(
                            R.string.import_failed,
                            "backup flashcard deck could not be prepared"
                        )
                    )
                }
                if (!RestoreJournal.write(
                        context,
                        restored,
                        newSalt,
                        newDeck,
                        attachmentStaging = tempDir.absolutePath,
                        // R5-01: stamp the staged database *before* the journal hashes it.
                        // AttachmentArchive.install reuses this token after the swap, so the live
                        // file keeps exactly the bytes this journal describes.
                        stampGeneration = true,
                    )
                ) {
                    throw IOException(
                        context.getString(R.string.import_failed, "could not write the restore journal")
                    )
                }
                // N-1/N-2: stamp `.bak` *before* the final move. A kill after this callback leaves
                // a database, salt, deck and attachment tree that all describe the same state;
                // the old order swapped first and wrote metadata after, which is the window this
                // closes. A failed stamp aborts the swap while the live database is still intact.
                val previousBackup = DatabaseRestore.replaceDatabaseAtomically(dbFile, restored) { backup ->
                    if (!SnapshotSidecar.write(backup, oldSalt, oldDeck, attachmentSnapshotId)) {
                        throw IOException(
                            context.getString(R.string.import_failed, "could not record snapshot metadata")
                        )
                    }
                    snapshotPaired = true
                }
                if (previousBackup == null) {
                    // Fresh install: no `.bak`, so the captured tree has no snapshot to belong to.
                    AttachmentSnapshots.delete(context, attachmentSnapshotId)
                    attachmentSnapshotId = null
                } else {
                    AttachmentSnapshots.pruneExcept(context, attachmentSnapshotId)
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
                    AttachmentArchive.install(
                        context,
                        preparedAttachments,
                    )
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
                // Every prefs/file side effect is complete; a crash from here on is a normal
                // "restart with the restored database", not an interrupted transition.
                RestoreJournal.clear(context)
            } catch (e: Throwable) {
                // Leave the journal in place: startup compares the live database bytes with the
                // staged hash. A successful rollback is a mismatch and is discarded; a failed
                // rollback means the move landed and the new prefs must be finished.
                // If `.bak` was never stamped, the candidate snapshot belongs to no database and
                // must not outlive this failed attempt. Once stamped, it is the pairing for the
                // current `.bak` (the database a rollback puts back) and is kept.
                if (!snapshotPaired) AttachmentSnapshots.delete(context, attachmentSnapshotId)
                // Every failure/rollback path lands here: the caller reports it and the app must
                // become usable again. Success deliberately falls through with the flag still set;
                // the caller restarts the process for the database that was just installed.
                // REG-04: with the token, a failure that belongs to an older transition cannot
                // clear a newer restore's overlay.
                RestoreCoordinator.end(restoreToken)
                throw e
            }
        } finally {
            // REG-04: this operation's own tree, removed while the shared restore mutex is still
            // held. DAT-10: a failed install whose rollback was incomplete keeps its journal, and
            // that journal still names these sources; keep the tree so startup can finish it.
            AttachmentArchive.deleteStagingUnlessReferenced(context, tempDir)
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
