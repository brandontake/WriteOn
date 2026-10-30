package com.ezpnix.writeon.data.local.database

import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
 */
class EncryptedNoteDao(
    private val delegate: NoteDao,
    private val crypto: NoteCrypto
) : NoteDao {

    override fun getAllNotes(): Flow<List<Note>> =
        // distinctUntilChanged first: Room re-emits on any table write, and mapping an unchanged
        // list through the Keystore again is pure waste (one round trip per row per emission).
        delegate.getAllNotes()
            .distinctUntilChanged()
            .map { notes -> notes.map(::decryptNote) }
            .flowOn(Dispatchers.IO)

    override suspend fun getAllNotesSync(): List<Note> =
        withContext(Dispatchers.IO) { delegate.getAllNotesSync().map(::decryptNote) }

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
        delegate.getNoteById(id).map(::decryptNote).flowOn(Dispatchers.IO)

    override fun getLastNoteId(): Long? = delegate.getLastNoteId()

    override fun getTrashedNotes(): Flow<List<Note>> =
        delegate.getTrashedNotes().map { notes -> notes.map(::decryptNote) }.flowOn(Dispatchers.IO)

    private fun encryptNote(note: Note): Note = if (note.encrypted) {
        note
    } else {
        note.copy(name = crypto.seal(note.name), description = crypto.seal(note.description))
    }

    private fun decryptNote(note: Note): Note = if (note.encrypted) {
        note
    } else {
        note.copy(name = crypto.open(note.name), description = crypto.open(note.description))
    }
}
