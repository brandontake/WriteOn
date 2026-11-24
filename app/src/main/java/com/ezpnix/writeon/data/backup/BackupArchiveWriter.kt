package com.ezpnix.writeon.data.backup

import android.content.Context
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.domain.model.Attachment
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CQ-03: the one place that decides which members a WriteOn backup archive contains and in what
 * order.
 *
 * The local SAF export, the daily automatic backup and the WebDAV upload used to spell the layout
 * out three times: portable database entry, `attachments/` tree, `flashcards.json`, then
 * `vault-key-salt`. Adding a member meant editing three classes, and a miss produced a backup that
 * reported success while silently omitting something the restore side expects. Every producer now
 * calls [write] and cannot drift; the member names and order are unchanged, so archives written by
 * this build restore in every existing path.
 *
 * The archive is built in this order:
 *  1. [beforeDatabaseEntry], when the caller has metadata that must precede everything else. The
 *     WebDAV producer uses it for [com.ezpnix.writeon.core.util.BackupArchiveCrypto.writeManifestEntry]
 *     so the freshness manifest stays the first entry (W6); the other producers pass `null` and
 *     their first entry remains the database, exactly as before.
 *  2. The portable database under [DatabaseConst.NOTES_DATABASE_FILE_NAME].
 *  3. The attachment tree through [AttachmentArchive.writeTo]'s decrypting overload - `WMV1`
 *     bytes must never reach an archive that is meant to restore on another install.
 *  4. `flashcards.json` ([FlashcardArchive]) so the deck survives a restore (DAT-11).
 *  5. `vault-key-salt` ([VaultSaltArchive]) so `v3:` rows stay readable after a restore (PERF-2).
 *     SEC-03: only when [write]'s `includeVaultSalt` is true - a plaintext archive has no vault
 *     rows by policy and must not carry the salt its ciphertext would be attacked with.
 *
 * This writer only fills a [ZipOutputStream]; it neither opens nor closes it, and it does not
 * encrypt, rename or publish the result. Those steps stay with each producer because they differ
 * on purpose: [BackupWorker] writes a `.part`, authenticates it and renames it; [WebDAVBackupWorker]
 * builds a temporary zip and streams it through its passphrase cipher; the SAF export builds the
 * archive in a private temp file before touching the user's document (DAT-06).
 *
 * The worker call sites cannot be instantiated on the JVM without a WorkManager `WorkerParameters`
 * graph, so their coverage is source-level: they contain no zip-entry code of their own and call
 * this function directly. `BackupArchiveWriterTest` exercises this layout directly, and the SAF
 * path additionally through `BackupExportWriter`.
 *
 * @throws java.io.IOException when the portable copy cannot be read or an attachment cannot be
 *   copied. Callers abort the whole archive, never publish a zip with a truncated member.
 */
object BackupArchiveWriter {

    /**
     * Writes the common members into [zip] (the vault salt only when `includeVaultSalt` is true).
     *
     * @param zip the archive being built; owned and closed by the caller.
     * @param context used by the attachment, flashcard and vault-salt helpers.
     * @param portableDatabase the plaintext database copy produced by the same snapshot whose
     *   rows are in [attachments] (N-5).
     * @param attachments the rows returned by `writePortableDatabaseCopy`, not a fresh query.
     * @param beforeDatabaseEntry optional first-entry hook; the WebDAV manifest uses it. It must
     *   write and close its own entry, and must not throw for a missing optional value.
     * @param includeVaultSalt whether the envelope salt belongs in this archive. SEC-03: a
     *   *plaintext* archive must pass false - it has no vault rows by policy, and carrying the
     *   salt would hand a reader the only extra value needed to attack `v3:` ciphertext from a
     *   different copy of the database. Every encrypted producer keeps the default (true); the
     *   salt has to travel with the rows it opens.
     */
    fun write(
        zip: ZipOutputStream,
        context: Context,
        portableDatabase: File,
        attachments: Collection<Attachment>,
        includeVaultSalt: Boolean = true,
        beforeDatabaseEntry: (() -> Unit)? = null,
    ) {
        beforeDatabaseEntry?.invoke()

        zip.putNextEntry(ZipEntry(DatabaseConst.NOTES_DATABASE_FILE_NAME))
        FileInputStream(portableDatabase).use { database -> database.copyTo(zip) }
        zip.closeEntry()

        // SEC-4: decrypting overload. An unreadable `WMV1` member aborts the run, which is correct:
        // a backup holding device-bound ciphertext restores nothing anywhere but this install, and
        // it would look like a valid restore point.
        AttachmentArchive.writeTo(zip, attachments, context)
        // DAT-11: the deck lives in SharedPreferences, not the database copied above.
        FlashcardArchive.writeToZip(context, zip)
        // PERF-2: so does the `v3:` envelope salt. Without it the restored rows are ciphertext no
        // later session can derive a KEK for. SEC-03: only for an archive that is encrypted.
        if (includeVaultSalt) {
            VaultSaltArchive.writeToZip(context, zip)
        }
    }
}
