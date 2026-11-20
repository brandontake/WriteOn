package com.ezpnix.writeon.data.backup

import android.content.Context
import android.util.Log
import com.ezpnix.writeon.data.local.database.NoteCrypto
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DAT-11: carries the flashcard deck through the app's own backup archives.
 *
 * The deck never lived in the database: `SettingsModel` keeps it in `flashcards_prefs` as one JSON
 * array, sealed with the same Keystore-backed [NoteCrypto] as a note body (SEC-4). Every backup
 * path - SAF export, WebDAV, the automatic daily archive - copied only the database and the
 * `attachments/` tree, so an uninstall or a device swap silently dropped the cards while the user
 * believed "all notes" had been saved.
 *
 * The archive member is [ENTRY_NAME] and holds **plain** JSON on purpose: the archive itself is
 * already encrypted (passphrase or device key), and a Keystore seal inside it could not be opened
 * on the destination device. The seal is reapplied by [restoreFromStaging] when the deck lands in
 * `flashcards_prefs` again. TXT export stays text-only and deliberately does not call this class.
 *
 * Restores never write a plaintext deck: a value that cannot be sealed is skipped and the stored
 * deck is left exactly as it was.
 */
object FlashcardArchive {

    /** Archive member name. Absent in archives written before DAT-11; that is a supported no-op. */
    const val ENTRY_NAME = "flashcards.json"

    private const val TAG = "FlashcardArchive"

    /** The deck's storage, exactly as `SettingsModel` names it. */
    private const val PREFS_NAME = "flashcards_prefs"
    private const val KEY_DECK = "flashcards_key"

    /**
     * Sanity cap on a deck read from an archive. A deck is text (front/back pairs), so anything
     * near this size is a corrupt or hostile member; refusing it keeps a 2 GB extraction budget
     * from turning into a 2 GB String.
     */
    private const val MAX_DECK_BYTES = 8L * 1024 * 1024

    /**
     * The deck's read/write pair, pulled out of the Android `Context` so the restore logic (and
     * its failure rules) are testable without SharedPreferences or a Keystore.
     */
    internal interface DeckStore {
        /** The raw stored value, sealed or legacy plaintext; null when no deck was ever saved. */
        fun read(): String?

        /** Replaces the stored value; false when the write did not reach disk. */
        fun write(sealed: String): Boolean

        /** Removes the stored value; false when the removal did not reach disk. */
        fun clear(): Boolean
    }

    /**
     * The sealing half of the storage seam. Production is [NoteCrypto]; tests use a fake so the
     * JVM unit tests never touch the Android Keystore. [openOrNull] follows the same contract as
     * [NoteCrypto.openOrNull]: plaintext passes through, unreadable ciphertext returns null.
     */
    internal interface Sealer {
        fun seal(plaintext: String): String
        fun openOrNull(stored: String): String?
    }

    /**
     * The deck as plain JSON, or null when there is nothing trustworthy to export.
     *
     * Null covers all three "do not back this up" cases: no deck at all, a sealed value this
     * install cannot decrypt (Keystore reset, deck copied from another device), and stored bytes
     * that are not a JSON array. Exporting the last two would replace a user's recoverable deck
     * with garbage the next time this archive is restored.
     */
    fun readPlainJson(context: Context): String? =
        readPlainJson(prefsStore(context), NoteCryptoSealer())

    /**
     * The exact bytes `flashcards_prefs` holds, sealed or legacy plaintext.
     *
     * N-2: the `.bak` sidecar needs a reversible copy of the deck, not a plaintext export. This
     * raw value is written back verbatim by [restoreRaw] and never interpreted, so a plaintext
     * legacy deck stays exactly as the user had it and a `w1:` deck keeps its Keystore seal.
     */
    fun readRaw(context: Context): String? = readRaw(prefsStore(context))

    internal fun readRaw(store: DeckStore): String? = try {
        store.read()
    } catch (e: Exception) {
        // A sidecar is a safety net; failing to capture it must not fail the restore itself. The
        // caller records null, which means "this snapshot had no deck", so the undo leaves the
        // restored archive's deck in place rather than guessing at unreadable bytes.
        runCatching { Log.w(TAG, "Could not capture the flashcard deck for the snapshot", e) }
        null
    }

    /**
     * Restores a value produced by [readRaw], including "there was no deck".
     *
     * Only valid for a same-install snapshot: the value may be Keystore-sealed and this method
     * deliberately does not open or re-seal it. Returns false when the write did not commit.
     */
    fun restoreRaw(context: Context, raw: String?): Boolean = restoreRaw(prefsStore(context), raw)

    internal fun restoreRaw(store: DeckStore, raw: String?): Boolean = try {
        if (raw == null) store.clear() else store.write(raw)
    } catch (e: Exception) {
        runCatching { Log.w(TAG, "Could not restore the snapshot's flashcard deck", e) }
        false
    }

    /**
     * The staged archive member as plain JSON, without touching `flashcards_prefs`.
     *
     * N-1's restart journal needs the deck before the database swap so it can seal it for crash
     * recovery. Reuses the same size/array validation as [restoreFromStaging]; a malformed member
     * returns null and the normal restore path will leave the stored deck untouched.
     */
    fun readStagedPlainJson(stagingDir: File): String? =
        readStagedDeck(File(stagingDir, ENTRY_NAME))

    /** Seals a staged deck for the crash journal with the same Keystore key as the live deck. */
    fun sealForRecovery(json: String): String? = runCatching {
        NoteCryptoSealer().seal(json)
    }.onFailure {
        runCatching { Log.w(TAG, "Could not seal flashcards for restore recovery", it) }
    }.getOrNull()

    /** Opens a journal-sealed deck; null means this install cannot read it and must keep its own. */
    fun openForRecovery(sealed: String): String? = runCatching {
        NoteCryptoSealer().openOrNull(sealed)
    }.getOrNull()

    /**
     * Re-seals a raw `flashcards_prefs` value for the undo journal.
     *
     * The sidecar stores whatever the live deck held - `w1:` ciphertext or legacy plain JSON - so
     * this opens it first (plaintext passes through) and seals the JSON again. A null result means
     * the journal must not clear or replace the current deck; leaving it is safer than writing an
     * unreadable value during crash recovery.
     */
    fun resealRawForRecovery(raw: String): String? =
        openForRecovery(raw)?.let(::sealForRecovery)

    internal fun readPlainJson(store: DeckStore, sealer: Sealer): String? {
        val stored = try {
            store.read()
        } catch (e: Exception) {
            // A wrong type under the key (an old build, a manual edit) must not fail the backup.
            Log.e(TAG, "Could not read the flashcard deck; leaving it out of the archive", e)
            return null
        }
        if (stored.isNullOrBlank()) return null

        // NoteCrypto.openOrNull reads both forms: `w1:` ciphertext is opened, legacy plain JSON is
        // returned unchanged. A null here means real ciphertext this process cannot open, and the
        // deck must be skipped rather than exported as its own sealed blob.
        val json = sealer.openOrNull(stored)
        if (json == null) {
            Log.w(TAG, "Stored flashcards could not be decrypted; leaving them out of the archive")
            return null
        }
        if (!isJsonArray(json)) {
            Log.w(TAG, "Stored flashcards are not a JSON array; leaving them out of the archive")
            return null
        }
        return json
    }

    /**
     * Adds [ENTRY_NAME] to an archive. A no-op when [readPlainJson] found nothing readable, so an
     * empty install (or an undecryptable deck) produces archives that look exactly like the ones
     * older builds wrote.
     */
    fun writeToZip(context: Context, zip: ZipOutputStream) {
        writeDeckEntry(zip, readPlainJson(context))
    }

    /** The zip half of [writeToZip], split out so the entry format is testable without a Context. */
    internal fun writeDeckEntry(zip: ZipOutputStream, json: String?) {
        if (json == null) return
        zip.putNextEntry(ZipEntry(ENTRY_NAME))
        zip.write(json.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Applies a deck extracted from an archive to `flashcards_prefs`, sealed again with the local
     * Keystore key. Called after the database swap, so a restore that fails earlier never rewrites
     * the deck either.
     *
     * Returns true only when a sealed value was written. An archive without the member (everything
     * written before DAT-11) is a no-op that keeps the current deck; so is an unreadable member or
     * a seal failure - in both cases the stored deck stays untouched rather than degrading to a
     * plaintext write.
     */
    fun restoreFromStaging(context: Context, stagingDir: File): Boolean =
        restoreFromStaging(stagingDir, prefsStore(context), NoteCryptoSealer())

    internal fun restoreFromStaging(
        stagingDir: File,
        store: DeckStore,
        sealer: Sealer,
    ): Boolean {
        val staged = File(stagingDir, ENTRY_NAME)
        if (!staged.isFile) return false

        val json = readStagedDeck(staged) ?: return false

        val sealed = try {
            sealer.seal(json)
        } catch (e: Exception) {
            // SEC-4: never fall back to plaintext. Skipping the deck is recoverable (the archive
            // still holds it); writing readable cards into prefs is not.
            Log.e(TAG, "Could not seal the restored flashcards; keeping the stored deck untouched", e)
            return false
        }

        return try {
            store.write(sealed).also { written ->
                if (!written) Log.w(TAG, "Restored flashcards could not be persisted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not store the restored flashcards; keeping the stored deck untouched", e)
            false
        }
    }

    /** Null when the staged member is too large, unreadable, or not a JSON array. */
    private fun readStagedDeck(staged: File): String? {
        if (staged.length() > MAX_DECK_BYTES) {
            Log.w(TAG, "Archived flashcards exceed the ${MAX_DECK_BYTES} byte limit; skipping them")
            return null
        }
        val json = try {
            staged.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read archived flashcards; keeping the stored deck untouched", e)
            return null
        }
        if (!isJsonArray(json)) {
            Log.w(TAG, "Archived flashcards are not a JSON array; keeping the stored deck untouched")
            return null
        }
        return json
    }

    /**
     * The stored deck's format gate, shared by export and restore. Gson is used only to look at the
     * root node: the deck is stored as a JSON array, and a member that merely *contains* the word
     * flashcards (or a half-written file) must not be allowed to replace the real one.
     */
    private fun isJsonArray(json: String): Boolean =
        runCatching { JsonParser.parseString(json).isJsonArray }.getOrDefault(false)

    /**
     * `flashcards_prefs` behind the [DeckStore] seam.
     *
     * `commit()` rather than `apply()`: a restore reports its result to the caller (and, in the
     * cloud path, to a toast) right after this call, and an apply() write lost to a process kill
     * would report a deck that is not there on the next launch. The commit runs on the restore
     * path's own IO thread, never on the main thread.
     */
    private fun prefsStore(context: Context): DeckStore {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return object : DeckStore {
            override fun read(): String? = prefs.getString(KEY_DECK, null)
            override fun write(sealed: String): Boolean =
                prefs.edit().putString(KEY_DECK, sealed).commit()
            override fun clear(): Boolean =
                prefs.edit().remove(KEY_DECK).commit()
        }
    }

    /**
     * Production [Sealer]: the JVM tests use a fake, so the archive logic never has to touch the
     * Android Keystore. `NoteCrypto` is final, hence the adapter rather than implementing the
     * internal test interface on the data class.
     */
    private class NoteCryptoSealer(
        private val crypto: NoteCrypto = NoteCrypto(),
    ) : Sealer {
        override fun seal(plaintext: String): String = crypto.seal(plaintext)
        override fun openOrNull(stored: String): String? = crypto.openOrNull(stored)
    }
}
