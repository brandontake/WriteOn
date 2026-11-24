package com.ezpnix.writeon.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentIndex
import com.ezpnix.writeon.core.security.EncryptionHelper
import com.ezpnix.writeon.core.widget.WidgetRefresher
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.repository.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAT-01: the vault sweep must not write its older snapshot over a save that landed in between.
 *
 * The repository fake models the race explicitly: the first compare-and-swap call first applies
 * an editor save (a different body), then performs the same predicate check the SQL performs. A
 * sweep that passes the bytes it read gets 0 back and leaves the saved body alone; a sweep that
 * ignored its affected-row count would still report an upgrade here.
 */
@RunWith(RobolectricTestRunner::class)
class VaultUpgradeRaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a save between the read and the write survives and the sweep reports zero upgrades`() =
        runBlocking {
            val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
            val legacyBody = legacySeal("old body", PASSWORD)
            val repository = RacingRepository(
                initialNote = Note(
                    id = NOTE_ID,
                    name = legacySeal("old name", PASSWORD),
                    description = legacyBody,
                    encrypted = true,
                ),
                raceOnFirstWrite = true,
            )
            val useCase = noteUseCase(repository, encryption)

            val upgraded = useCase.upgradeLegacyVaultRows()

            assertEquals("a CAS miss is skipped, not upgraded", 0, upgraded)
            assertEquals(
                "the editor's save must keep the database",
                "v3:body-written-by-the-editor",
                repository.note.description
            )
            assertEquals(
                "the sweep must offer the bytes it read as the CAS predicate",
                legacyBody,
                repository.lastPreviousDescription
            )
        }

    @Test
    fun `an unchanged row is resealed into the envelope`() = runBlocking {
        val encryption = EncryptionHelper(StringBuilder()).apply { setPassword(PASSWORD) }
        val repository = RacingRepository(
            initialNote = Note(
                id = NOTE_ID,
                name = legacySeal("old name", PASSWORD),
                description = legacySeal("old body", PASSWORD),
                encrypted = true,
            ),
            raceOnFirstWrite = false,
        )
        val useCase = noteUseCase(repository, encryption)

        val upgraded = useCase.upgradeLegacyVaultRows()

        assertEquals(1, upgraded)
        assertTrue(repository.note.description.startsWith("v3:"))
        assertTrue(repository.note.name.startsWith("v3:"))
    }

    private fun noteUseCase(repository: NoteRepository, encryption: EncryptionHelper): NoteUseCase =
        NoteUseCase(
            noteRepository = repository,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            encryptionHelper = encryption,
            attachmentIndex = object : AttachmentIndex {
                override fun put(attachments: Collection<Attachment>) = Unit
                override fun put(attachment: Attachment) = Unit
                override fun remove(ids: Collection<String>) = Unit
            },
            widgetRefresher = object : WidgetRefresher {
                override suspend fun refreshAll() = Unit
            },
            context = context,
        )

    /**
     * The pre-PBKDF2 legacy layout: `base64(iv):base64(ciphertext)`, keyed by one SHA-256 pass.
     * Sealing here keeps the fixture in the format `needsEnvelopeMigration` flags, so the sweep
     * has real work to do.
     */
    private fun legacySeal(plain: String, password: String): String {
        val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)),
            "AES"
        )
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv) + ":" +
            Base64.getEncoder().encodeToString(sealed)
    }

    private class RacingRepository(
        initialNote: Note,
        private val raceOnFirstWrite: Boolean,
    ) : NoteRepository {

        var note: Note = initialNote
            private set
        var lastPreviousDescription: String? = null
            private set
        private var casCalls = 0

        override suspend fun updateVaultFields(
            id: Int,
            previousName: String,
            previousDescription: String,
            name: String,
            description: String,
        ): Int {
            casCalls++
            lastPreviousDescription = previousDescription
            if (raceOnFirstWrite && casCalls == 1) {
                // The user saved the note from the editor after the sweep read its snapshot.
                note = note.copy(
                    name = "v3:name-written-by-the-editor",
                    description = "v3:body-written-by-the-editor",
                    updatedAt = 2,
                )
            }
            val current = note
            val matches = current.id == id &&
                current.encrypted &&
                current.name == previousName &&
                current.description == previousDescription
            if (!matches) return 0
            note = current.copy(name = name, description = description)
            return 1
        }

        override fun getAllNotes(): Flow<List<Note>> = flowOf(listOf(note))

        override suspend fun getAllNotesSync(): List<Note> = listOf(note)

        override suspend fun addNote(note: Note) {
            this.note = note
        }

        override suspend fun updateNote(note: Note) {
            this.note = note
        }

        override suspend fun deleteNote(note: Note) = Unit

        override suspend fun saveNoteWithAttachments(
            note: Note,
            upsertAttachments: List<Attachment>,
            reviveAttachmentIds: List<String>,
            trashAttachmentIds: List<String>,
        ): Int {
            this.note = note
            return note.id
        }

        override suspend fun deleteNoteWithAttachments(note: Note): List<Attachment> = emptyList()

        override suspend fun updatePinned(id: Int, pinned: Boolean) = Unit

        override suspend fun updateTags(id: Int, tags: List<String>) = Unit

        override suspend fun updateTrashed(id: Int, trashed: Boolean) = Unit

        override suspend fun updatePlainDescription(id: Int, description: String) = Unit

        override fun getNoteById(id: Int): Flow<Note> = flowOf(note)

        override fun getTrashedNotes(): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesByTag(tag: String): Flow<List<Note>> = flowOf(emptyList())

        override suspend fun getAllTags(): List<String> = emptyList()

        override suspend fun batchUpdateNotes(notes: List<Note>) = Unit

        override suspend fun sealLegacyPlaintextRows(): Int = 0

        override suspend fun sealLegacyCaptions(): Int = 0

        override suspend fun getAllAttachments(): List<Attachment> = emptyList()

        override suspend fun getAttachmentsForNote(noteId: Int): List<Attachment> = emptyList()

        override suspend fun upsertAttachments(attachments: List<Attachment>) = Unit

        override suspend fun assignAttachmentsToNote(noteId: Int, ids: List<String>) = Unit

        override suspend fun deleteAttachments(ids: List<String>) = Unit

        override suspend fun getAttachmentById(id: String): Attachment? = null

        override fun observeAttachmentsByType(type: String): Flow<List<Attachment>> =
            flowOf(emptyList())

        override fun observeTrashedAttachments(): Flow<List<Attachment>> = flowOf(emptyList())

        override suspend fun setAttachmentTrashed(ids: List<String>, trashedAt: Long?) = Unit
    }

    private companion object {
        const val NOTE_ID = 73
        const val PASSWORD = "correct horse battery staple"
    }
}
