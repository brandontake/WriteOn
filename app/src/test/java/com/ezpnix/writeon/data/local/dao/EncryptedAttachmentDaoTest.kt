package com.ezpnix.writeon.data.local.dao

import com.ezpnix.writeon.data.local.database.NoteSealFormat
import com.ezpnix.writeon.domain.model.Attachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-4: the caption policy of [EncryptedAttachmentDao].
 *
 * These are plain JVM tests. The seal/open pair is injected through the constructor, so nothing here
 * touches the Android Keystore or Room: a fake cipher (`w1:` marker + base64 of a fixed IV and a
 * `seal:`-prefixed payload) lets the tests assert what is stored, what is read back, and - the part
 * that matters for data safety - that an unreadable payload is never handed to a caller or written
 * over.
 *
 * The real cipher's `w1:` behaviour is already pinned by `NoteCryptoFormatTest`; what still needs a
 * device is a real seal/open round trip and the Room query wiring behind the fake delegate.
 */
class EncryptedAttachmentDaoTest {

    @Test
    fun `upsert seals a plain caption`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)

        dao.upsert(attachment(caption = "intro"))

        assertEquals(sealCaption("intro"), raw.rows.single().caption)
        assertEquals("intro", openCaption(raw.rows.single().caption))
    }

    @Test
    fun `a structurally sealed caption is not sealed again`() = runBlocking {
        val raw = FakeAttachmentDao()
        val stored = sealCaption("intro")
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)

        dao.upsert(attachment(caption = stored))

        // Re-sealing a real `w1:` value would replace the only copy of that caption with different
        // ciphertext, so the wrapper must spend zero seal calls on it.
        assertEquals(0, sealCalls)
        assertEquals(stored, raw.rows.single().caption)
    }

    @Test
    fun `upsert leaves id path type note and timestamps untouched`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        val original = attachment(
            id = "att-9",
            noteId = 42,
            type = Attachment.TYPE_AUDIO,
            path = "/filesDir/audios/recording.m4a",
            caption = "interview",
            createdAt = 1_700_000_000_000L,
            trashedAt = 123L,
        )

        dao.upsert(original)

        assertEquals(original.copy(caption = sealCaption("interview")), raw.rows.single())
    }

    @Test
    fun `an empty caption is not sealed`() = runBlocking {
        val raw = FakeAttachmentDao()
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)

        dao.upsert(attachment(caption = ""))

        assertEquals(0, sealCalls)
        assertEquals("", raw.rows.single().caption)
    }

    @Test
    fun `a list upsert seals only the captions that need it`() = runBlocking {
        val raw = FakeAttachmentDao()
        val alreadySealed = sealCaption("done")
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)

        dao.upsert(
            listOf(
                attachment(id = "plain", caption = "new"),
                attachment(id = "sealed", caption = alreadySealed),
                attachment(id = "empty", caption = ""),
            )
        )

        assertEquals(1, sealCalls)
        assertEquals(
            listOf(sealCaption("new"), alreadySealed, ""),
            raw.rows.map { it.caption },
        )
    }

    @Test
    fun `a seal failure in a batch writes none of the rows`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(
            raw,
            { throw IllegalStateException("Keystore unavailable") },
            ::openCaption,
        )

        runCatching {
            dao.upsert(listOf(attachment(id = "a", caption = "one"), attachment(id = "b", caption = "two")))
        }

        // Nothing may fall back to plaintext just because the batch could not be completed.
        assertTrue(raw.rows.isEmpty())
    }

    @Test
    fun `getAll opens every caption`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        raw.rows += attachment(id = "a", caption = sealCaption("first"))
        raw.rows += attachment(id = "b", caption = sealCaption("second"))

        assertEquals(listOf("first", "second"), dao.getAll().map { it.caption })
    }

    @Test
    fun `getById getByNoteId and getByIds open captions`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        raw.rows += attachment(id = "a", noteId = 1, caption = sealCaption("one"))
        raw.rows += attachment(id = "b", noteId = 2, caption = sealCaption("two"))

        assertEquals("one", dao.getById("a")?.caption)
        assertEquals(listOf("one"), dao.getByNoteId(1).map { it.caption })
        assertEquals(listOf("one", "two"), dao.getByIds(listOf("a", "b")).map { it.caption })
    }

    @Test
    fun `observeActive opens captions`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        raw.active.value = listOf(attachment(caption = sealCaption("library")))

        assertEquals(listOf("library"), dao.observeActive().first().map { it.caption })
    }

    @Test
    fun `observeActiveByType and observeTrashed open captions`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        raw.activeByType.value = listOf(attachment(caption = sealCaption("audio")))
        raw.trashed.value = listOf(attachment(caption = sealCaption("deleted")))

        assertEquals(listOf("audio"), dao.observeActiveByType(Attachment.TYPE_AUDIO).first().map { it.caption })
        assertEquals(listOf("deleted"), dao.observeTrashed().first().map { it.caption })
    }

    @Test
    fun `an unreadable caption reads as empty and is not written back`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)
        raw.rows += attachment(caption = UNREADABLE_CAPTION)

        val read = dao.getAll().single()

        assertEquals("", read.caption)
        // The stored bytes must survive for a future key, and a save of the visible row must not be
        // able to replace them with an empty seal.
        assertEquals(UNREADABLE_CAPTION, raw.rows.single().caption)
        assertNull(openCaption(UNREADABLE_CAPTION))
    }

    @Test
    fun `assignNote passes through without sealing`() = runBlocking {
        val raw = FakeAttachmentDao()
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)
        raw.rows += attachment(caption = "still plain")

        dao.assignNote(noteId = 77, ids = listOf("att-1"))

        assertEquals(77 to listOf("att-1"), raw.assigned)
        assertEquals(0, sealCalls)
        assertEquals("still plain", raw.rows.single().caption)
    }

    @Test
    fun `setTrashedAt passes through without sealing`() = runBlocking {
        val raw = FakeAttachmentDao()
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)
        raw.rows += attachment(caption = "still plain")

        dao.setTrashedAt(ids = listOf("att-1"), trashedAt = 999L)

        assertEquals(listOf("att-1") to 999L, raw.trashUpdate)
        assertEquals(0, sealCalls)
        assertEquals("still plain", raw.rows.single().caption)
    }

    @Test
    fun `deleteByNoteId and deleteByIds pass through`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)

        dao.deleteByNoteId(5)
        dao.deleteByIds(listOf("a", "b"))

        assertEquals(5, raw.deletedNoteId)
        assertEquals(listOf("a", "b"), raw.deletedIds)
    }

    @Test
    fun `sealLegacyCaptions seals only plain captions and reports none left`() = runBlocking {
        val raw = FakeAttachmentDao()
        val alreadySealed = sealCaption("done")
        var sealCalls = 0
        val dao = EncryptedAttachmentDao(raw, { sealCalls++; sealCaption(it) }, ::openCaption)
        raw.rows += attachment(id = "plain", caption = "new")
        raw.rows += attachment(id = "sealed", caption = alreadySealed)
        raw.rows += attachment(id = "unreadable", caption = UNREADABLE_CAPTION)
        raw.rows += attachment(id = "empty", caption = "")

        val failures = dao.sealLegacyCaptions()

        assertEquals(0, failures)
        assertEquals(1, sealCalls)
        // Assert by id: the CAS replaces the caption in place, and only the plain one is written.
        val stored = raw.rows.associate { it.id to it.caption }
        assertEquals(sealCaption("new"), stored["plain"])
        assertEquals(alreadySealed, stored["sealed"])
        // Structurally sealed but not openable here: re-sealing would replace the only copy.
        assertEquals(UNREADABLE_CAPTION, stored["unreadable"])
        assertEquals("", stored["empty"])
    }

    @Test
    fun `sealLegacyCaptions leaves a row untouched when sealing fails and counts it`() = runBlocking {
        val raw = FakeAttachmentDao()
        val dao = EncryptedAttachmentDao(
            raw,
            { plain ->
                if (plain == "boom") throw IllegalStateException("Keystore unavailable")
                sealCaption(plain)
            },
            ::openCaption,
        )
        raw.rows += attachment(id = "good", caption = "ok")
        raw.rows += attachment(id = "bad", caption = "boom")

        val failures = dao.sealLegacyCaptions()

        assertEquals(1, failures)
        assertEquals(sealCaption("ok"), raw.rows.first { it.id == "good" }.caption)
        // The readable plaintext is the only copy of that caption; a failed seal must not replace
        // it, so the next launch can retry.
        assertEquals("boom", raw.rows.first { it.id == "bad" }.caption)
    }

    @Test
    fun `sealLegacyCaptions counts a row whose write fails and leaves it plain`() = runBlocking {
        val raw = FakeAttachmentDao()
        raw.rows += attachment(id = "a", caption = "one")
        raw.failUpsertFor = "a"
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)

        val failures = dao.sealLegacyCaptions()

        assertEquals(1, failures)
        assertEquals("one", raw.rows.single().caption)
    }

    @Test
    fun `DAT-07 a row trashed during the sweep keeps its trashed stamp and updated caption`() = runBlocking {
        val raw = FakeAttachmentDao()
        raw.rows += attachment(id = "a", caption = "one")
        // The sweep has already read the row; trash "a" just before the write. The old full-row
        // REPLACE would have written the stale entity back with trashed_at = null.
        raw.beforeCaptionUpdate = {
            raw.beforeCaptionUpdate = null
            raw.rows[0] = raw.rows[0].copy(trashedAt = 777L)
        }
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)

        val failures = dao.sealLegacyCaptions()

        assertEquals(0, failures)
        val stored = raw.rows.single()
        assertEquals("the trash stamp must survive the sweep", 777L, stored.trashedAt)
        assertEquals("the caption is still sealed in place", sealCaption("one"), stored.caption)
    }

    @Test
    fun `DAT-07 a row deleted during the sweep is not resurrected`() = runBlocking {
        val raw = FakeAttachmentDao()
        raw.rows += attachment(id = "a", caption = "one")
        raw.beforeCaptionUpdate = {
            raw.beforeCaptionUpdate = null
            raw.rows.clear()
        }
        val dao = EncryptedAttachmentDao(raw, ::sealCaption, ::openCaption)

        val failures = dao.sealLegacyCaptions()

        assertEquals("a zero-row CAS is a skip, not a seal failure", 0, failures)
        assertTrue("the deleted row must stay deleted", raw.rows.isEmpty())
    }

    private fun attachment(
        id: String = "att-1",
        noteId: Int = 7,
        type: String = Attachment.TYPE_AUDIO,
        path: String = "/filesDir/audios/recording.m4a",
        caption: String = "",
        createdAt: Long = 1_000L,
        trashedAt: Long? = null,
    ): Attachment = Attachment(
        id = id,
        noteId = noteId,
        type = type,
        path = path,
        caption = caption,
        createdAt = createdAt,
        trashedAt = trashedAt,
    )

    /**
     * A stand-in for `NoteCrypto.seal` that produces a structurally valid `w1:` value.
     *
     * The 12-byte "IV" keeps the payload above [NoteSealFormat.IV_BYTES], so [NoteSealFormat] and
     * this test agree on what looks sealed; the `seal:` prefix is what [openCaption] recognises, and
     * a payload without it (see [UNREADABLE_CAPTION]) stands in for ciphertext from another install.
     */
    private fun sealCaption(plaintext: String): String =
        NoteSealFormat.MARKER + java.util.Base64.getEncoder().encodeToString(
            ByteArray(NoteSealFormat.IV_BYTES) +
                (FAKE_SEAL_PREFIX + plaintext).toByteArray(Charsets.UTF_8)
        )

    /** Mirrors `NoteCrypto.openOrNull`: plain text passes through, an unknown payload returns null. */
    private fun openCaption(stored: String): String? {
        if (!NoteSealFormat.isSealedValue(stored)) return stored
        val payload = java.util.Base64.getDecoder()
            .decode(stored.substring(NoteSealFormat.MARKER.length))
        val text = String(
            payload.copyOfRange(NoteSealFormat.IV_BYTES, payload.size),
            Charsets.UTF_8,
        )
        return text.takeIf { it.startsWith(FAKE_SEAL_PREFIX) }?.removePrefix(FAKE_SEAL_PREFIX)
    }

    private class FakeAttachmentDao : AttachmentDao {

        val rows = mutableListOf<Attachment>()
        val active = MutableStateFlow<List<Attachment>>(emptyList())
        val activeByType = MutableStateFlow<List<Attachment>>(emptyList())
        val trashed = MutableStateFlow<List<Attachment>>(emptyList())

        var assigned: Pair<Int, List<String>>? = null
        var trashUpdate: Pair<List<String>, Long?>? = null
        var deletedNoteId: Int? = null
        var deletedIds: List<String>? = null

        /** Row id whose write should fail, to exercise the sweep's write-failure branch. */
        var failUpsertFor: String? = null

        /** Runs once just before the first CAS write, so a test can mutate the table mid-sweep. */
        var beforeCaptionUpdate: (() -> Unit)? = null

        override suspend fun getAll(): List<Attachment> = rows.toList()

        override suspend fun getById(id: String): Attachment? = rows.firstOrNull { it.id == id }

        override suspend fun getByNoteId(noteId: Int): List<Attachment> = rows.filter { it.noteId == noteId }

        override suspend fun getByIds(ids: List<String>): List<Attachment> = rows.filter { it.id in ids }

        override suspend fun upsert(items: List<Attachment>) {
            items.forEach { upsert(it) }
        }

        override suspend fun upsert(item: Attachment) {
            if (item.id == failUpsertFor) throw IllegalStateException("write failed")
            rows.removeAll { it.id == item.id }
            rows += item
        }

        override suspend fun updateCaptionIfMatches(
            id: String,
            previousCaption: String,
            newCaption: String,
        ): Int {
            beforeCaptionUpdate?.invoke()
            if (id == failUpsertFor) throw IllegalStateException("write failed")
            val index = rows.indexOfFirst { it.id == id && it.caption == previousCaption }
            if (index < 0) return 0
            rows[index] = rows[index].copy(caption = newCaption)
            return 1
        }

        override suspend fun assignNote(noteId: Int, ids: List<String>) {
            assigned = noteId to ids
        }

        override fun observeActive(): Flow<List<Attachment>> = active

        override fun observeActiveByType(type: String): Flow<List<Attachment>> = activeByType

        override fun observeTrashed(): Flow<List<Attachment>> = trashed

        override suspend fun setTrashedAt(ids: List<String>, trashedAt: Long?) {
            trashUpdate = ids to trashedAt
        }

        override suspend fun deleteByNoteId(noteId: Int) {
            deletedNoteId = noteId
        }

        override suspend fun deleteByIds(ids: List<String>) {
            deletedIds = ids
        }
    }

    private companion object {
        const val FAKE_SEAL_PREFIX = "seal:"

        /** Structurally a seal, but not one this fake cipher wrote: `openCaption` returns null. */
        val UNREADABLE_CAPTION: String =
            NoteSealFormat.MARKER + java.util.Base64.getEncoder()
                .encodeToString(ByteArray(NoteSealFormat.IV_BYTES + 16))
    }
}
