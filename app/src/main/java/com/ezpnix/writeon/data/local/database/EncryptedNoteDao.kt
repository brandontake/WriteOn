package com.ezpnix.writeon.data.local.database

import android.util.Log
import com.ezpnix.writeon.core.security.DecryptFailureRegistry
import com.ezpnix.writeon.data.local.dao.LegacyNoteRow
import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

/**
 * One page of the portable-database export.
 *
 * [undecryptableIds] are rows whose sealed text could not be opened; [notes] still holds their
 * **original** `w1:` ciphertext, so the caller can count them without ever seeing placeholder text.
 */
internal data class NoteExportPage(
    val notes: List<Note>,
    val undecryptableIds: Set<Int>,
)

/**
 * SEC-5: the outcome of one [EncryptedNoteDao.sealLegacyPlaintextRows] pass.
 *
 * [failedRows] is the number that matters to the caller: the startup sweep only marks the job done
 * when it is zero, so a Keystore that was unavailable during that launch gets another attempt.
 * [alreadySealedRows] counts rows there was nothing to do for - every non-empty column already
 * carries a `w1:` payload, both columns are empty, or the row changed under the sweep (saved or
 * deleted while it ran) and was deliberately not rewritten.
 */
data class LegacySealResult(
    val sealedRows: Int,
    val failedRows: Int,
    val alreadySealedRows: Int,
) {
    val scannedRows: Int get() = sealedRows + failedRows + alreadySealedRows
}

/**
 * Wraps the Room generated [NoteDao] so that note text is encrypted at rest without any caller
 * having to know about it.
 *
 * Only rows that are not already password protected by the Vault feature (`encrypted == false`) are
 * touched - vault rows hold ciphertext derived from the user's password and must be stored verbatim.
 * The `tags` column is intentionally left in plaintext so that tag filtering can run in memory
 * without decrypting every row twice.
 *
 * Keystore round trips are kept off the main thread: the flows are mapped on [Dispatchers.IO].
 *
 * A sealed row that will not open (the Keystore key is gone) is degraded **per row**: the row
 * stays in the list with a localized placeholder body instead of taking the whole flow down with
 * an exception, and its id is reported to [DecryptFailureRegistry] so the UI can show a count and
 * every write path can refuse to put the placeholder back over the ciphertext. The stored bytes
 * are never touched here - this class only decides what a row *reads* as.
 */
class EncryptedNoteDao(
    private val delegate: NoteDao,
    private val crypto: NoteCrypto,
    /**
     * Resolves the localized "cannot decrypt" placeholder for name and description.
     *
     * A lambda rather than a pre-built string so the text follows the active locale at the moment
     * a row actually fails, and so this class stays free of an Android [android.content.Context]
     * it would have to hold for its whole lifetime.
     */
    private val undecryptableText: () -> String
) : NoteDao {

    override fun getAllNotes(): Flow<List<Note>> =
        // distinctUntilChanged first: Room re-emits on any table write, and mapping an unchanged
        // list through the Keystore again is pure waste (one round trip per row per emission).
        delegate.getAllNotes()
            .distinctUntilChanged()
            .map { notes -> decryptList(notes, DecryptFailureRegistry.SCOPE_ACTIVE) }
            .flowOn(Dispatchers.IO)

    override suspend fun getAllNotesSync(): List<Note> =
        withContext(Dispatchers.IO) {
            decryptList(delegate.getAllNotesSync(), DecryptFailureRegistry.SCOPE_ACTIVE)
        }

    /**
     * W12: one page of the streaming export copy.
     *
     * Decrypted like [getAllNotesSync] - the portable database exists to be restored on another
     * device, so it must hold plain text. Deliberately **not** routed through [decryptList]: that
     * helper rewrites [DecryptFailureRegistry]'s active scope on every call, and an export paging
     * through the library would shrink the banner's failure set to whatever the current page held.
     * Registry bookkeeping belongs to list emissions, not to an export read.
     *
     * DAT-3: this is an **export-only** read, so a row that cannot be opened is returned exactly
     * as stored (its `w1:` ciphertext) instead of [placeholderRow]. A placeholder written into a
     * backup replaces the only copy of the ciphertext: a transient Keystore failure would become
     * permanent data loss once that archive is restored. List flows keep using placeholders so the
     * UI can still render and explain the failure; see [getNotesPageForExport] for the failure
     * count the portable writer logs.
     */
    override suspend fun getNotesPage(afterId: Int, limit: Int): List<Note> =
        getNotesPageForExport(afterId, limit).notes

    /**
     * The export path with the ids that had to keep their ciphertext (DAT-3).
     *
     * Kept off [NoteDao] because it is only meaningful to the backup writer: it needs the count
     * for its log/manifest, while callers of [getNotesPage] still get the original rows.
     */
    internal suspend fun getNotesPageForExport(afterId: Int, limit: Int): NoteExportPage =
        withContext(Dispatchers.IO) {
            val failed = HashSet<Int>()
            val notes = delegate.getNotesPage(afterId, limit).map { note ->
                openNote(note) ?: note.also { failed += it.id }
            }
            NoteExportPage(notes, failed)
        }

    override suspend fun addNote(note: Note) {
        delegate.addNote(encryptNote(note))
    }

    override suspend fun addNoteReturningId(note: Note): Long {
        return delegate.addNoteReturningId(encryptNote(note))
    }

    override suspend fun updateNote(note: Note) {
        delegate.updateNote(encryptNote(note))
    }

    override suspend fun batchUpdateNotes(notes: List<Note>) {
        delegate.batchUpdateNotes(notes.map(::encryptNote))
    }

    override suspend fun deleteNote(note: Note) {
        delegate.deleteNote(note)
    }

    override suspend fun updatePinned(id: Int, pinned: Boolean) {
        delegate.updatePinned(id, pinned)
    }

    /**
     * A4: passed straight through, never through [encryptNote].
     *
     * The two values are already sealed with the vault passphrase by the caller; running them
     * through the Keystore path would double-seal them and the note would become unreadable.
     */
    override suspend fun updateVaultFields(id: Int, name: String, description: String): Int =
        delegate.updateVaultFields(id, name, description)

    override suspend fun updateTags(id: Int, tags: List<String>) {
        delegate.updateTags(id, tags)
    }

    override suspend fun updateTrashed(id: Int, trashed: Boolean) {
        delegate.updateTrashed(id, trashed)
    }

    override suspend fun updatePlainDescription(id: Int, description: String) {
        delegate.updatePlainDescription(id, crypto.seal(description))
    }

    /**
     * SEC-5: the raw read, passed straight through - no `crypto.open` anywhere.
     *
     * These two methods exist on [NoteDao] because Room generates them there, so the wrapper has to
     * honour them. They are the *only* deliberately unencrypted path in this class: the sweep has to
     * compare stored bytes against the `w1:` format, and a read that decrypted first would see every
     * legacy row as "plain text" and every sealed row as "needs sealing". Nothing else should call
     * them; everything else uses [getAllNotes] / [getNoteById].
     */
    override suspend fun getLegacyPlaintextRows(): List<LegacyNoteRow> =
        delegate.getLegacyPlaintextRows()

    /**
     * SEC-5: the raw write-back for [sealLegacyPlaintextRows].
     *
     * Values arrive already sealed (that method is the only caller), so routing them through
     * [encryptNote] would seal the fresh ciphertext again and make the note unreadable. The
     * `previous*` values are the bytes the sweep read; they make the write conditional, so a note
     * edited and saved while the sweep was in flight is skipped instead of being overwritten.
     */
    override suspend fun updateLegacyPlaintextFields(
        id: Int,
        previousName: String,
        previousDescription: String,
        name: String,
        description: String,
    ): Int = delegate.updateLegacyPlaintextFields(
        id = id,
        previousName = previousName,
        previousDescription = previousDescription,
        name = name,
        description = description,
    )

    /**
     * SEC-5: seals every non-vault row still stored as plain text, once and proactively.
     *
     * Sealing used to happen only on the next save of a note, so a row carried over from a version
     * without encryption - or restored from an old backup - stayed readable in the database file
     * until its owner happened to edit it. For an untouched library that is never. This pass closes
     * that gap: it reads the raw columns, seals the ones that are plain text and writes the row back
     * through [NoteDao.updateLegacyPlaintextFields], which bypasses [encryptNote] because the
     * values arrive already sealed.
     *
     * The policy lives in [LegacyPlaintextBackfill] and is deliberately conservative:
     * - a structurally valid `w1:` value is never rewritten, even when this process cannot open it.
     *   Re-sealing it would replace the only copy of that ciphertext with something else.
     * - empty columns are left alone; `seal` would return them unchanged anyway.
     * - when *any* seal in a row throws (Keystore unavailable, key invalidated), the whole row is
     *   left byte-for-byte as it was and counted as a failure. A half-sealed row - or worse, a
     *   placeholder - would destroy text that is still readable.
     * - the write-back is conditional on the columns still holding the bytes that were read, so a
     *   note the user edits and saves while the sweep runs keeps the new text instead of the
     *   sweep's older snapshot.
     *
     * Runs on [Dispatchers.IO]: every sealed column is a Keystore round trip.
     */
    suspend fun sealLegacyPlaintextRows(): LegacySealResult = withContext(Dispatchers.IO) {
        var sealedRows = 0
        var failedRows = 0
        var alreadySealedRows = 0

        delegate.getLegacyPlaintextRows().forEach { row ->
            when (val outcome = LegacyPlaintextBackfill.seal(row, crypto::seal)) {
                LegacyRowSeal.NothingToDo -> alreadySealedRows++
                LegacyRowSeal.Failed -> {
                    failedRows++
                    Log.w(
                        TAG,
                        "Could not seal legacy plaintext in note ${row.id}; leaving the row untouched"
                    )
                }
                is LegacyRowSeal.Sealed -> {
                    val changed = delegate.updateLegacyPlaintextFields(
                        id = row.id,
                        previousName = row.name,
                        previousDescription = row.description,
                        name = outcome.name,
                        description = outcome.description,
                    )
                    // 0 means the row was deleted, became a vault row, or was saved by the editor
                    // while the sweep was running. None of those leaves it plain, so nothing was
                    // lost: the caller counts it as "nothing to do".
                    if (changed > 0) sealedRows++ else alreadySealedRows++
                }
            }
        }

        LegacySealResult(sealedRows, failedRows, alreadySealedRows).also { result ->
            Log.i(
                TAG,
                "Legacy plaintext sweep: ${result.sealedRows} sealed, " +
                    "${result.alreadySealedRows} already sealed, ${result.failedRows} failed " +
                    "of ${result.scannedRows} rows"
            )
        }
    }

    override fun getNoteById(id: Int): Flow<Note> =
        delegate.getNoteById(id)
            .mapNotNull { note -> note?.let(::decryptSingle) }
            .flowOn(Dispatchers.IO)

    override fun getTrashedNotes(): Flow<List<Note>> =
        delegate.getTrashedNotes()
            .map { notes -> decryptList(notes, DecryptFailureRegistry.SCOPE_TRASHED) }
            .flowOn(Dispatchers.IO)

    /**
     * Decrypts a whole refresh result and reports the outcome to [DecryptFailureRegistry].
     *
     * The registry is *recomputed* from this emission rather than appended to: rows that no
     * longer exist - or that open again - must leave the count on the next refresh.
     */
    private fun decryptList(notes: List<Note>, scope: String): List<Note> {
        val failed = HashSet<Int>()
        val decrypted = notes.map { note ->
            openNote(note) ?: run {
                failed.add(note.id)
                placeholderRow(note)
            }
        }
        DecryptFailureRegistry.replaceScope(scope, notes.mapTo(HashSet<Int>()) { it.id }, failed)
        return decrypted
    }

    /** Single-row variant used by the editor: also updates the failure registry directly. */
    private fun decryptSingle(note: Note): Note {
        openNote(note)?.let { decrypted ->
            DecryptFailureRegistry.markReadable(note.id)
            return decrypted
        }
        DecryptFailureRegistry.markFailed(note.id)
        return placeholderRow(note)
    }

    /**
     * Returns the row as the user may see it, or null when a sealed value could not be opened.
     *
     * `NoteCrypto.open` throws rather than returning an empty string for undecryptable ciphertext
     * (an empty result would be written back over the real body on the next save), so the throw is
     * caught here - per row, never per list - and turned into a degradation.
     */
    private fun openNote(note: Note): Note? {
        // Vault rows are sealed with the user's password and are handled by NoteUseCase; they are
        // stored verbatim and can never fail the Keystore path.
        if (note.encrypted) return note
        return try {
            note.copy(name = crypto.open(note.name), description = crypto.open(note.description))
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt note ${note.id}; degrading this row", e)
            null
        }
    }

    /** The display-only stand-in for a row whose stored text is unreadable. */
    private fun placeholderRow(note: Note): Note {
        val placeholder = undecryptableText()
        return note.copy(name = placeholder, description = placeholder)
    }

    private fun encryptNote(note: Note): Note = if (note.encrypted) {
        note
    } else {
        note.copy(name = crypto.seal(note.name), description = crypto.seal(note.description))
    }

    private companion object {
        const val TAG = "EncryptedNoteDao"
    }
}

/**
 * SEC-5: what one raw row needs from the legacy plaintext sweep.
 *
 * A sealed outcome carries the full replacement for both columns - the sealed column that changed
 * and the other one exactly as it was read - so the caller writes the row in one statement and can
 * never pair a freshly sealed value with a stale one.
 */
internal sealed interface LegacyRowSeal {

    data class Sealed(val name: String, val description: String) : LegacyRowSeal

    /** Every non-empty column is already sealed, or both are empty: nothing to write. */
    data object NothingToDo : LegacyRowSeal

    /** Sealing threw; the row must keep the bytes it has. */
    data object Failed : LegacyRowSeal
}

/**
 * SEC-5: the pure "does this row need sealing" decision, kept out of [EncryptedNoteDao] so it can
 * be tested without Room or an Android Keystore.
 *
 * It exists because the two failure modes it guards against are both silent data loss:
 * treating a real `w1:` ciphertext as plain text and re-sealing it (the original becomes
 * unreadable), and writing anything at all when the Keystore cannot seal (a placeholder, or the
 * untouched column paired with a failed one).
 */
internal object LegacyPlaintextBackfill {

    /**
     * Decides per column, then seals the columns that need it with [seal].
     *
     * [seal] is a parameter rather than a [NoteCrypto] field so the failure path is testable: the
     * production caller always passes `NoteCrypto::seal`, whose contract is "empty values pass
     * through, anything else comes back sealed or throws". One throwing column fails the whole row
     * - [LegacyRowSeal.Failed] - and the caller writes nothing.
     */
    fun seal(row: LegacyNoteRow, seal: (String) -> String): LegacyRowSeal {
        val sealName = row.name.needsSealing()
        val sealDescription = row.description.needsSealing()
        if (!sealName && !sealDescription) return LegacyRowSeal.NothingToDo

        return try {
            LegacyRowSeal.Sealed(
                name = if (sealName) seal(row.name) else row.name,
                description = if (sealDescription) seal(row.description) else row.description,
            )
        } catch (e: Exception) {
            // No logging here: `NoteCrypto.seal` already logs the cause, and the sweep logs the row
            // it had to skip. Keeping this function free of `android.util.Log` is also what lets
            // the policy be tested on a plain JVM, with no Robolectric.
            LegacyRowSeal.Failed
        }
    }

    /**
     * True only for text this app stored without the marker.
     *
     * Uses the same structural test as every read path ([NoteSealFormat.isSealedValue]): a plain
     * note that merely starts with `w1:` ("w1:hello") has no valid payload and *is* sealed here,
     * while a real but undecryptable `w1:` value is not. Empty is not "plain text to encrypt" -
     * there is nothing to protect and writing a seal of "" would only churn the row.
     */
    private fun String.needsSealing(): Boolean =
        isNotEmpty() && !NoteSealFormat.isSealedValue(this)
}
