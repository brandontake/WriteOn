package com.ezpnix.writeon.data.local.database

import android.util.Log
import com.ezpnix.writeon.core.security.DecryptFailureRegistry
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
     */
    override suspend fun getNotesPage(afterId: Int, limit: Int): List<Note> =
        withContext(Dispatchers.IO) {
            delegate.getNotesPage(afterId, limit).map { note -> openNote(note) ?: placeholderRow(note) }
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
            Log.w(TAG, "Could not decrypt note ${note.id}; keeping the row with a placeholder", e)
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
