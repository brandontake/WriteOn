package com.ezpnix.writeon.data.local.database

import com.ezpnix.writeon.data.local.dao.NoteDao
import com.ezpnix.writeon.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Wraps the Room generated [NoteDao] so that note text is encrypted at rest without any caller
 * having to know about it.
 *
 * Only rows that are not already password protected by the Vault feature (`encrypted == false`) are
 * touched - vault rows hold ciphertext derived from the user's password and must be stored verbatim.
 * The `tags` column is intentionally left in plaintext because [NoteDao.getNotesByTag] filters on it.
 *
 * Keystore round trips are kept off the main thread: the flows are mapped on [Dispatchers.IO].
 */
class EncryptedNoteDao(
    private val delegate: NoteDao,
    private val crypto: NoteCrypto
) : NoteDao {

    override fun getAllNotes(): Flow<List<Note>> =
        delegate.getAllNotes().map { notes -> notes.map(::decryptNote) }.flowOn(Dispatchers.IO)

    override suspend fun getAllNotesSync(): List<Note> =
        withContext(Dispatchers.IO) { delegate.getAllNotesSync().map(::decryptNote) }

    override suspend fun addNote(note: Note) {
        delegate.addNote(encryptNote(note))
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

    override fun getNoteById(id: Int): Flow<Note> =
        delegate.getNoteById(id).map(::decryptNote).flowOn(Dispatchers.IO)

    override fun getLastNoteId(): Long? = delegate.getLastNoteId()

    override fun getTrashedNotes(): Flow<List<Note>> =
        delegate.getTrashedNotes().map { notes -> notes.map(::decryptNote) }.flowOn(Dispatchers.IO)

    override fun getNotesByTag(tag: String): Flow<List<Note>> =
        delegate.getNotesByTag(tag).map { notes -> notes.map(::decryptNote) }.flowOn(Dispatchers.IO)

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
