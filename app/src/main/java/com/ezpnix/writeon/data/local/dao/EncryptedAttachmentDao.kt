package com.ezpnix.writeon.data.local.dao

import com.ezpnix.writeon.data.local.database.NoteCrypto
import com.ezpnix.writeon.data.local.database.NoteSealFormat
import com.ezpnix.writeon.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SEC-4: seals the free-text `caption` of an attachment with the same Keystore-backed [NoteCrypto]
 * that protects a note body, without changing the attachment table or its schema.
 *
 * Only the caption is touched here. `path` stays plaintext because the media layer has to open the
 * file with it, and `id`/`note-id`/`type`/timestamps are lookup data the queries filter on - sealing
 * any of those would break the library's SQL without protecting user-authored text. The image and
 * audio bytes are encrypted separately by `MediaVault` as a `WMV1` stream; this class only covers
 * the label the user types, and the two layers are deliberately independent so a media decryption
 * failure cannot make the attachment row unreadable (or vice versa).
 *
 * Like [com.ezpnix.writeon.data.local.database.EncryptedNoteDao], the wrapper never writes back on a
 * read. A caption whose `w1:` payload cannot be opened (key reset, database copied from another
 * install) reads as an empty caption - not as the ciphertext, and not as a placeholder that a later
 * save would persist over the only recoverable copy. The stored bytes are left exactly as they are.
 *
 * The seal/open functions are constructor parameters rather than a [NoteCrypto] field so the policy
 * above can be pinned by plain JVM tests; the secondary constructor is the production seam.
 */
class EncryptedAttachmentDao(
    private val delegate: AttachmentDao,
    private val seal: (String) -> String,
    private val openOrNull: (String) -> String?,
) : AttachmentDao {

    /** Production: the one [NoteCrypto] instance every other note read and write already uses. */
    constructor(delegate: AttachmentDao, crypto: NoteCrypto) : this(
        delegate = delegate,
        seal = crypto::seal,
        openOrNull = crypto::openOrNull,
    )

    override suspend fun getAll(): List<Attachment> =
        withContext(Dispatchers.IO) { delegate.getAll().map(::openCaption) }

    override suspend fun getById(id: String): Attachment? =
        withContext(Dispatchers.IO) { delegate.getById(id)?.let(::openCaption) }

    override suspend fun getByNoteId(noteId: Int): List<Attachment> =
        withContext(Dispatchers.IO) { delegate.getByNoteId(noteId).map(::openCaption) }

    override suspend fun getByIds(ids: List<String>): List<Attachment> =
        withContext(Dispatchers.IO) { delegate.getByIds(ids).map(::openCaption) }

    /**
     * Seals the caption, then writes the row.
     *
     * A real `w1:` value is passed through untouched even when this process cannot open it: it is
     * already the only copy of that caption, and re-sealing would replace it with different
     * ciphertext. The map is built before [AttachmentDao.upsert] runs, so a seal that throws writes
     * none of the batch instead of leaving half of it readable.
     */
    override suspend fun upsert(item: Attachment) {
        delegate.upsert(sealCaption(item))
    }

    override suspend fun upsert(items: List<Attachment>) {
        delegate.upsert(items.map(::sealCaption))
    }

    /** DAT-07: the caption-only compare-and-swap is a raw write; no seal may be re-applied. */
    override suspend fun updateCaptionIfMatches(
        id: String,
        previousCaption: String,
        newCaption: String,
    ): Int = delegate.updateCaptionIfMatches(id, previousCaption, newCaption)

    /**
     * Passed straight through: assigning a note or moving a row to/from the recycle bin does not
     * change the caption, and a seal here would rewrite the same text as fresh ciphertext for no
     * protection gain (and could fail a trash operation for a Keystore the row does not need).
     */
    override suspend fun assignNote(noteId: Int, ids: List<String>) =
        delegate.assignNote(noteId, ids)

    override fun observeActive(): Flow<List<Attachment>> =
        // distinctUntilChanged before the map: Room re-emits on every attachment write and decrypting
        // an unchanged list is one Keystore round trip per row for nothing.
        delegate.observeActive()
            .distinctUntilChanged()
            .map { rows -> rows.map(::openCaption) }
            .flowOn(Dispatchers.IO)

    override fun observeActiveByType(type: String): Flow<List<Attachment>> =
        delegate.observeActiveByType(type)
            .distinctUntilChanged()
            .map { rows -> rows.map(::openCaption) }
            .flowOn(Dispatchers.IO)

    override fun observeTrashed(): Flow<List<Attachment>> =
        delegate.observeTrashed()
            .distinctUntilChanged()
            .map { rows -> rows.map(::openCaption) }
            .flowOn(Dispatchers.IO)

    override suspend fun setTrashedAt(ids: List<String>, trashedAt: Long?) =
        delegate.setTrashedAt(ids, trashedAt)

    override suspend fun deleteByNoteId(noteId: Int) =
        delegate.deleteByNoteId(noteId)

    override suspend fun deleteByIds(ids: List<String>) =
        delegate.deleteByIds(ids)

    /**
     * SEC-4 follow-up: seals captions that were restored from a portable archive (or written before
     * this wrapper existed) without waiting for each row's next save.
     *
     * A portable archive intentionally carries plaintext captions - its own password protects the
     * file, and a Keystore seal would not survive the trip to another install - so every restored
     * row starts readable. This is the proactive half of [sealCaption]'s lazy upgrade.
     *
     * Raw rows are read through [delegate]: this wrapper's own `getAll` would open the captions and
     * hand back plaintext, making "is this stored value already sealed" impossible to answer. Each
     * row is written back **through the delegate** too, so a successful seal is not re-sealed and a
     * failure leaves the original plaintext (the only readable copy) exactly where it was.
     *
     * DAT-07: the write is a single-column compare-and-swap, not a full-row `REPLACE`. A row the
     * user trashes or deletes while this sweep runs no longer matches, so the sweep can never
     * resurrect it with a stale `trashed_at`; zero rows changed is a skip, not a failure.
     *
     * @return how many rows are still plain because their seal or write failed; 0 means the sweep
     *   finished and the caller may record it as done. Failed rows are retried on the next launch.
     */
    suspend fun sealLegacyCaptions(): Int = withContext(Dispatchers.IO) {
        var failures = 0
        delegate.getAll().forEach { row ->
            val caption = row.caption
            if (caption.isEmpty() || NoteSealFormat.isSealedValue(caption)) return@forEach
            try {
                val sealed = seal(caption)
                // 0 = the row was deleted or its caption changed under the sweep. Both mean there
                // is nothing left to seal; counting it as a failure would retry forever.
                delegate.updateCaptionIfMatches(row.id, caption, sealed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelling the sweep is not a seal failure: rethrow so the caller leaves its
                // "done" flag unwritten and the next launch retries the whole batch.
                throw e
            } catch (e: Exception) {
                // Deliberately no fallback write: a Keystore that cannot seal right now must not
                // turn the row into a different plaintext or lose it. Leaving it readable is what
                // makes the next launch's retry possible.
                failures++
            }
        }
        failures
    }

    /**
     * The write-side policy, kept in one place so both upsert overloads cannot drift.
     *
     * Empty is not "plain text to protect" - [NoteCrypto.seal] returns it unchanged anyway - and a
     * structurally valid seal is never re-sealed. Everything else is user text still stored in the
     * clear (a row restored from a portable archive, or written before this wrapper existed) and is
     * upgraded in place on the next save.
     */
    private fun sealCaption(item: Attachment): Attachment {
        val caption = item.caption
        if (caption.isEmpty() || NoteSealFormat.isSealedValue(caption)) return item
        return item.copy(caption = seal(caption))
    }

    /**
     * The read-side policy: a failed open degrades to an empty caption, never to the ciphertext.
     *
     * The empty string is the model's own "no caption" value, so every caller - list rows, the audio
     * label fallback, the editor - already handles it; showing ciphertext would leak payload bytes
     * into the UI, and handing it back to a save path would invite it being written somewhere else.
     */
    private fun openCaption(item: Attachment): Attachment {
        val caption = item.caption
        if (caption.isEmpty()) return item
        return item.copy(caption = openOrNull(caption) ?: "")
    }
}
