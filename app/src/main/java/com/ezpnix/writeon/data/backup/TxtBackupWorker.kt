package com.ezpnix.writeon.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.ezpnix.writeon.core.security.DecryptFailureRegistry
import com.ezpnix.writeon.core.util.BackupRestoreLock
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.NoteSealFormat
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R

private const val TAG = "TxtBackupHelper"

object TxtBackupHelper {

    data class ExportNote(val title: String, val content: String)

    /**
     * The rows a `.txt` package may contain, plus why the others were left out (DAT-3/DAT-13).
     *
     * A TXT package has no encryption and no attachment channel, so exporting a vault row would
     * write its `v2:` ciphertext as body text, and exporting a failed row would write the
     * localized "cannot decrypt" placeholder. Both turn a readable archive into misleading
     * garbage, so they are skipped and counted instead.
     */
    internal data class ExportSelection(
        val notes: List<ExportNote>,
        val skippedVault: Int,
        val skippedUndecryptable: Int,
        val skippedSealedText: Int,
    ) {
        val skippedTotal: Int get() = skippedVault + skippedUndecryptable + skippedSealedText
    }

    /** One `.txt` member as read back from a package: its text plus whether the cap cut it (D12). */
    internal data class EntryContent(val text: String, val truncated: Boolean)

    /** Prefix of a vault value sealed with the user's password (see EncryptionHelper). */
    private const val VAULT_MARKER = "v2:"

    /**
     * D12: hard ceiling on a single imported entry, in bytes of UTF-8.
     *
     * The reader used to `copyTo` the whole entry into a `ByteArrayOutputStream`, so one corrupt
     * or hostile archive entry of a few hundred MB was enough to OOM the restore. 1 MiB is far
     * more text than a note body realistically holds, so honest backups never notice the cap; a
     * longer entry is truncated, counted and logged instead of killing the process.
     */
    internal const val MAX_ENTRY_BYTES = 1 * 1024 * 1024

    suspend fun writeNotesToZipStream(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException(context.getString(R.string.export_failed, ""))
        // DAT-8: the decrypted note snapshot and the zip written from it are one unit; a restore
        // that closes and replaces the database must not land between the query and the writes.
        // The SAF stream was opened before the lock so waiting for a restore never leaves a
        // half-claimed document behind.
        BackupRestoreLock.mutex.withLock {
            ZipOutputStream(outputStream).use { zipOut ->
                getAllNotes(context).forEachIndexed { index, note ->
                    val baseName = note.title.ifBlank { context.getString(R.string.untitled) }
                    val filename = sanitizeFileName("${index + 1}_$baseName") + ".txt"
                    zipOut.putNextEntry(ZipEntry(filename))
                    zipOut.write(note.content.toByteArray())
                    zipOut.closeEntry()
                }
            }
        }
    }

    /**
     * Imports every `.txt` member of [uri] as a note, skipping entries that are already in the
     * library (D12).
     *
     * Idempotency: the TXT format carries no note id and no timestamp - a member is only
     * `<index>_<sanitized title>.txt` plus the body - so the strongest identity it can express is
     * the (sanitized title, body) pair, which is exactly what [noteKey] builds on both sides. The
     * numeric index prefix is deliberately *not* part of the key: it only encodes export order and
     * shifts whenever the library is reordered or grows, so including it would defeat the check for
     * every re-export. See [noteKey] for why sanitizing and blank-title normalisation are needed to
     * make the two sides line up.
     *
     * The dedupe set is a snapshot taken **before** the first insert and is never mutated while
     * importing: two members that are byte-identical (two genuinely distinct empty notes with the
     * same title) must both survive the first import, and the second import of the same package
     * skips both because their key is in the snapshot.
     */
    suspend fun restoreNotesFromZipStream(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext
            // DAT-8: the read and the inserts are one unit relative to any database close/swap.
            // Without this lock a restore could close the shared Room connection (or replace the
            // file underneath it) between two entries, which imported half the archive into the
            // outgoing database. Same order as the export above: the SAF stream is opened before
            // the lock, so waiting for a restore never leaves a half-claimed document behind.
            BackupRestoreLock.mutex.withLock {
                val dbProvider = BackupDatabase.provider(context)
                val dao = dbProvider.noteDao()
                val untitled = context.getString(R.string.untitled)
                val existing = dao.getAllNotesSync()
                    .mapTo(HashSet()) { note -> noteKey(note.name, note.description, untitled) }

                var imported = 0
                var skipped = 0
                var truncated = 0

                java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".txt")) {
                            val read = readCurrentZipEntry(zipIn)
                            if (read.truncated) truncated++

                            val fileName = entry.name.removeSuffix(".txt")
                            val title = fileName.substringAfter("_").ifBlank { untitled }

                            if (isDuplicate(existing, title, read.text, untitled)) {
                                skipped++
                            } else {
                                dao.addNote(Note(id = 0, name = title, description = read.text))
                                imported++
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }

                // Counts go to logcat only: the restore screen keeps its single generic
                // confirmation and the caller restarts the process right after, so there is no
                // surface to report on yet.
                Log.i(
                    TAG,
                    "TXT restore: $imported imported, $skipped skipped as duplicates, " +
                        "$truncated entries truncated at $MAX_ENTRY_BYTES bytes"
                )
            }
        }
    }

    /**
     * The identity of a note as far as a TXT package can express it (D12).
     *
     * Both sides run the same normalisation so they meet in the middle:
     *  - **Title**: the package only preserves `sanitizeFileName(title)`, so the library's name is
     *    sanitised too (`"a/b"` exported as `a_b` matches the note it came from); blank titles were
     *    exported as the localised "Untitled" placeholder, so both sides substitute it first.
     *  - **Body**: compared after [clipToLimit] so a body longer than the import cap matches its
     *    already-truncated copy instead of being re-imported as a "new" note on every restore.
     *
     * A `Pair` rather than a concatenated string: no delimiter can appear in a title or body that
     * would let two different notes collide into one key.
     */
    internal fun noteKey(name: String, body: String, untitled: String): Pair<String, String> =
        sanitizeFileName(name.ifBlank { untitled }) to clipToLimit(body, MAX_ENTRY_BYTES)

    /** True when an incoming entry's [noteKey] is already present in [existing]. */
    internal fun isDuplicate(
        existing: Collection<Pair<String, String>>,
        title: String,
        content: String,
        untitled: String,
    ): Boolean = noteKey(title, content, untitled) in existing

    /**
     * Reads one zip entry, never holding more than [maxBytes] of it (D12).
     *
     * When the cap stops the read, one probe byte tells "entry ended exactly at the cap" from
     * "there was more"; the probe is discarded either way because we are over budget by
     * definition. The remaining bytes are skipped by the caller's `closeEntry()`.
     */
    internal fun readCurrentZipEntry(
        zipIn: ZipInputStream,
        maxBytes: Int = MAX_ENTRY_BYTES,
    ): EntryContent {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = zipIn.read(chunk, 0, minOf(chunk.size, maxBytes - total))
            if (read == -1) break
            buffer.write(chunk, 0, read)
            total += read
        }
        val truncated = total >= maxBytes && zipIn.read() != -1
        val bytes = buffer.toByteArray()
        // Only our own cut can leave a partial code point; a complete entry keeps the exact
        // bytes it has always decoded, malformed ones included.
        val text = if (truncated) decodeEntry(bytes) else String(bytes, StandardCharsets.UTF_8)
        return EntryContent(text, truncated)
    }

    /**
     * Decodes [bytes] as UTF-8 after dropping an incomplete code point at the end.
     *
     * The byte cap (and [clipToLimit]) can slice a multi-byte character in half; decoding that
     * as-is plants a U+FFFD replacement character in the restored note. The tail is examined for
     * a lead byte whose continuation bytes are missing, and cut before it. Bytes that are already
     * invalid on their own are passed through untouched - that corruption came from the file, not
     * from us.
     */
    internal fun decodeEntry(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var start = bytes.size - 1
        while (start >= 0 && (bytes[start].toInt() and 0xC0) == 0x80) start--
        if (start < 0) return String(bytes, StandardCharsets.UTF_8) // only continuation bytes: not ours to fix
        val lead = bytes[start].toInt() and 0xFF
        val expected = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return String(bytes, StandardCharsets.UTF_8)
        }
        val end = if (bytes.size - start < expected) start else bytes.size
        return String(bytes, 0, end, StandardCharsets.UTF_8)
    }

    /**
     * The body clipped to [maxBytes] of UTF-8, cutting on a code-point boundary (D12).
     *
     * Applied to **both** sides of [noteKey] so an over-long library body compares equal to the
     * truncated text a previous import stored - otherwise every restore would duplicate it.
     */
    internal fun clipToLimit(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        return decodeEntry(bytes.copyOf(maxBytes))
    }

    private suspend fun getAllNotes(context: Context): List<ExportNote> {
        val dbProvider = BackupDatabase.provider(context)
        val dao = dbProvider.noteDao()

        // getAllNotesSync decrypts through EncryptedNoteDao, which also refreshes the active
        // DecryptFailureRegistry scope from exactly this snapshot. Reading the registry right
        // after therefore names the rows whose body is currently a placeholder; the marker scan
        // inside selectExportableNotes only adds defence in depth for a sealed value that somehow
        // reached a non-vault row.
        val allNotes = dao.getAllNotesSync()
        val selection = selectExportableNotes(allNotes, DecryptFailureRegistry.failedIds.value)
        if (selection.skippedTotal > 0) {
            Log.w(
                TAG,
                "TXT export skipped ${selection.skippedTotal} of ${allNotes.size} rows: " +
                    "${selection.skippedVault} vault, ${selection.skippedUndecryptable} " +
                    "undecryptable, ${selection.skippedSealedText} still sealed. A .txt package " +
                    "never receives placeholder text or ciphertext."
            )
        }
        return selection.notes
    }

    /**
     * DAT-3/DAT-13: decides which rows may be written as readable `.txt` bodies.
     *
     * Pure so the policy is testable without a Context or a database. [failedIds] is the
     * [DecryptFailureRegistry] snapshot taken after the same read; a row in it holds placeholder
     * text and must not be exported. Vault rows (`encrypted`) hold `v2:` ciphertext sealed with
     * the user's password and are skipped because a TXT package cannot carry the key. The final
     * marker check is defence in depth for a sealed value that reached a non-vault row.
     */
    internal fun selectExportableNotes(notes: List<Note>, failedIds: Set<Int>): ExportSelection {
        val exportable = ArrayList<ExportNote>(notes.size)
        var skippedVault = 0
        var skippedUndecryptable = 0
        var skippedSealedText = 0

        notes.forEach { note ->
            when {
                note.encrypted -> skippedVault++
                note.id in failedIds -> skippedUndecryptable++
                hasStoredCiphertext(note) -> skippedSealedText++
                else -> exportable += ExportNote(note.name, note.description)
            }
        }

        return ExportSelection(exportable, skippedVault, skippedUndecryptable, skippedSealedText)
    }

    /**
     * True when either stored column still carries a marker that only a ciphertext can have.
     *
     * V-11: the `w1:` prefix alone is not proof. A legacy plain note can legitimately start with
     * those three characters ("w1:hello"), and skipping it made a readable note silently missing
     * from the TXT export. [NoteSealFormat] requires a base64 payload longer than the IV, which is
     * the smallest shape the sealer can produce; a truncated payload still fails later in
     * [NoteCrypto.openOrNull] and is caught by the decrypt-failure registry or this structural
     * filter.
     */
    private fun hasStoredCiphertext(note: Note): Boolean =
        note.name.startsWith(VAULT_MARKER) ||
            note.description.startsWith(VAULT_MARKER) ||
            NoteSealFormat.isSealedValue(note.name) ||
            NoteSealFormat.isSealedValue(note.description)

    internal fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}
