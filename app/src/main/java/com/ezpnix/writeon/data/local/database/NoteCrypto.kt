package com.ezpnix.writeon.data.local.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Encrypts note text so the Room database file does not hold readable notes.
 *
 * The AES key lives in the Android Keystore (alias [KEY_ALIAS]) and therefore never leaves the
 * device: a database copied to another device - or restored from a backup taken on the old one -
 * cannot be read. That is why the backup feature stores note text in portable plain text and why
 * the note database is excluded from Android's auto backup (see res/xml/backup_rules.xml).
 *
 * Values are sealed as `w1:<base64 iv + ciphertext>`; anything that does not carry that marker *and*
 * decode to an IV plus at least one ciphertext block is treated as plain text, so rows written
 * before this class existed (and rows restored from an old backup) stay readable - including one
 * that merely starts with `w1:`. Saved rows are re-encrypted the next time they are written.
 *
 * Encryption failures are surfaced to the caller; note text is never silently written in plaintext.
 */
class NoteCrypto @Inject constructor() {

    /** Encrypts [plaintext] for storage. Empty values are left alone. */
    fun seal(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext

        return try {
            // A seal is a write, so creating the key when the Keystore has none is allowed here;
            // the read path below never does that.
            withSecretKey(createIfMissing = true) { key ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                MARKER + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not encrypt note text; refusing to store it", e)
            throw IllegalStateException("Could not encrypt note text", e)
        }
    }

    /** Reads a value produced by [seal]; plain text values pass through unchanged. */
    fun open(stored: String): String {
        // PERF-03: the cache is checked first. `isSealed` decodes the entire base64 payload just
        // to answer "does this look like ciphertext", and the list flows ask that question for
        // every row on every Room emission. A hit here never allocates the decoded bytes.
        cacheGet(stored)?.let { return it }

        if (!isSealed(stored)) return stored

        val plaintext = openOrNull(stored)
            ?: throw IllegalStateException("Could not decrypt stored note text; refusing to replace it")
        cachePut(stored, plaintext)
        return plaintext
    }

    /**
     * PERF-03: opens both columns of one row through the row cache.
     *
     * The string cache above is keyed by the full ciphertext, so a hit still hashes and compares a
     * whole body. The row cache is keyed by `(id, updatedAt)` and validated against the stored
     * lengths, which is what lets an unchanged row skip decryption entirely when Room re-emits the
     * whole table after an unrelated write. [EncryptedNoteDao] invalidates the id on every text
     * write; [clearCache] drops the whole map on lock.
     *
     * Mixed rows work too: a legacy row can still have one plaintext column next to a sealed one.
     * Values are reported for the row as stored, so a caller cannot mistake a plain number for a
     * decrypted body.
     */
    fun openRow(id: Int, updatedAt: Long, name: String, description: String): Pair<String, String> {
        rowCache.get(id, updatedAt, name, description)?.let { return it }
        val decryptedName = open(name)
        val decryptedDescription = open(description)
        rowCache.put(
            id = id,
            updatedAt = updatedAt,
            name = name,
            description = description,
            plaintextName = decryptedName,
            plaintextDescription = decryptedDescription
        )
        return decryptedName to decryptedDescription
    }

    /** PERF-03: forget one row's decrypted text; called by [EncryptedNoteDao] on every write. */
    fun invalidateRow(id: Int) {
        rowCache.invalidate(id)
    }

    /**
     * True when [value] was produced by [seal] rather than stored as plain text.
     *
     * V-11: the `w1:` marker alone is not proof. A note written before this class existed can start
     * with `w1:` of its own accord ("w1:hello"); the read paths used to treat it as ciphertext, fail
     * to decrypt it and show the undecryptable placeholder over a perfectly readable note. Only a
     * value whose payload decodes to an IV plus at least one GCM block can be a seal, and that
     * structural test is what every read path now uses.
     */
    fun isSealed(value: String): Boolean = NoteSealFormat.isSealedValue(value)

    /**
     * The decoded payload of a [stored] value shaped like `w1:` ciphertext, null for plain text.
     *
     * The length test is "longer than the IV", not "not empty": [seal] always writes a 12 byte IV
     * followed by the GCM ciphertext, so anything that does not decode as base64 or that fits in
     * the IV alone is structurally impossible and must read back as the plain text it is. A value
     * that passes this test but still fails to open is the other case - real ciphertext this
     * process cannot decrypt - and [openOrNull] keeps returning null for it so a caller never
     * overwrites the only copy with garbage.
     */
    private fun sealedPayload(stored: String): ByteArray? {
        if (!stored.startsWith(MARKER)) return null
        val payload = try {
            Base64.decode(stored.substring(MARKER.length), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return payload.takeIf { it.size > IV_BYTES }
    }

    /**
     * Like [open], but returns null when a sealed value cannot be decrypted, so a caller that
     * rewrites data (the 7 -> 8 body migration) can tell "plain text" from "ciphertext we must not
     * touch" instead of writing garbage back over an unreadable note.
     */
    fun openOrNull(stored: String): String? {
        // Same structural test as isSealed: a malformed `w1:` value is plain text, and the caller
        // gets it back unchanged so no write path can "repair" it into something else.
        // PERF-03: cache first, exactly like [open] - a hit skips the base64 decode.
        cacheGet(stored)?.let { return it }

        if (!isSealed(stored)) return stored

        return try {
            withSecretKey { key -> decryptPayload(stored, key) }
                .also { cachePut(stored, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt note text, leaving the row untouched", e)
            null
        }
    }

    /** Decrypts one `w1:` payload with [key]; shared by the read paths so they cannot drift. */
    private fun decryptPayload(stored: String, key: SecretKey): String {
        val payload = Base64.decode(stored.substring(MARKER.length), Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "ciphertext is too short" }

        val iv = payload.copyOfRange(0, IV_BYTES)
        val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Loads the AES key, keeping it in memory after the first successful Keystore read.
     *
     * [KeyStore.load] and the provider round trip are the expensive part of every seal/open, and
     * before this cache each cache miss paid for them again. The key itself never changes unless
     * the Keystore invalidates it, which [withSecretKey] handles by dropping the cache and reloading
     * once. The D3 contract stays intact: when the key is missing, only a write
     * (`createIfMissing = true`) may generate a replacement; a read throws instead.
     */
    private fun secretKey(createIfMissing: Boolean = false): SecretKey {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            val key = loadKey(createIfMissing)
            cachedKey = key
            return key
        }
    }

    private fun loadKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        check(createIfMissing) { "The note encryption key is missing; refusing to create a replacement" }

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Runs [block] with the cached key and, if the cipher rejects it, reloads from the Keystore once.
     *
     * A key that was valid when cached can be invalidated later (the lock screen changes, the
     * Keystore is reset, a restored backup belongs to another device). Retrying with the same
     * cached object would fail forever, so the cache is dropped first. The retry keeps
     * [createIfMissing] as the caller passed it: a read still throws when the entry is gone rather
     * than silently minting a new key that cannot open the existing rows.
     */
    private fun <T> withSecretKey(createIfMissing: Boolean = false, block: (SecretKey) -> T): T {
        val key = secretKey(createIfMissing)
        return try {
            block(key)
        } catch (first: Exception) {
            synchronized(keyLock) {
                if (cachedKey === key) cachedKey = null
            }
            try {
                block(secretKey(createIfMissing))
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    /**
     * The Keystore key, kept after the first successful load so the list does not re-open the
     * provider for every row. Volatile because the decrypting DAO maps flows on IO while a save can
     * seal on another thread.
     *
     * Unlike the plaintext cache this stays per instance: it is only a reference to the same
     * Keystore entry, so nothing secret is shared, and each instance's invalidation retry then
     * reloads independently instead of one caller's stale key forcing every other caller to reload.
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** Guards the one-time load and the invalidation path; never held while a cipher runs. */
    private val keyLock = Any()

    /**
     * Drops every cached plaintext value, for this instance and every other one in the process.
     *
     * PERF-1 keeps decrypted bodies in memory so an unchanged list is not decrypted again on every
     * Room emission. That is exactly what must not survive a lock: the app calls this when it is
     * locked or backgrounded (see `NoteDatabaseProvider.clearCryptoCache`) so plaintext does not
     * sit in the heap behind the lock screen.
     *
     * V-5: the cache lives in the companion, so this call reaches all of it. `NoteCrypto` is
     * injected in several places and constructed directly in others (the Hilt provider behind the
     * list's DAO, `BackupDatabase.provider()`, the migration wrapper, `SettingsViewModel` for
     * flashcards/scratchpad). With a per-instance cache, `LockManager` could only clear the
     * instance it happened to hold while Settings kept decrypted scratchpad text across a lock.
     */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            cachedPlaintextBytes = 0L
        }
        // PERF-03: the row cache holds the same plaintext under a different key; a lock that
        // cleared one map and left the other would keep every body in memory.
        rowCache.clear()
    }

    private fun cacheGet(key: String): String? = synchronized(cacheLock) { cache[key] }

    private fun cachePut(key: String, plaintext: String) {
        synchronized(cacheLock) {
            cache.put(key, plaintext)?.let { cachedPlaintextBytes -= plaintextBytes(it) }
            cachedPlaintextBytes += plaintextBytes(plaintext)

            // Evict least-recently-used entries until both budgets fit. The newest entry is kept
            // even when it alone is larger than the budget: dropping it immediately would just
            // force the same decrypt on the next read.
            val eldest = cache.entries.iterator()
            while (
                (cachedPlaintextBytes > MAX_CACHE_BYTES || cache.size > MAX_CACHE_ENTRIES) &&
                cache.size > 1 &&
                eldest.hasNext()
            ) {
                val entry = eldest.next()
                eldest.remove()
                cachedPlaintextBytes -= plaintextBytes(entry.value)
            }
        }
    }

    /** UTF-8 is the on-disk form of a note body, so this tracks what the cache actually holds. */
    private fun plaintextBytes(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

    private companion object {
        const val TAG = "NoteCrypto"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        const val KEY_ALIAS = "writeon_notes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MARKER = "w1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val CACHE_INITIAL_CAPACITY = 64

        /**
         * Recently read values, so re-reading an unchanged note is not a Keystore round trip. Sealing
         * is randomised, which means a freshly saved note misses the cache while everything else hits.
         *
         * PERF-1: the old bound was 512 *entries*, which allowed long bodies to retain tens of
         * megabytes while short notes could not. The bound is now the total plaintext bytes (plus a
         * generous entry cap for tiny notes), evicting in access order - the least recently read body
         * goes first. Both map and byte counter are guarded by [cacheLock]; the map is deliberately
         * access-ordered so `get` is also a write and needs the same lock.
         *
         * V-5: the cache is in the companion because it is process-wide state, not a per-instance
         * memo. Every `NoteCrypto` opens the same Keystore entry and encrypts the same note text;
         * duplicating the cache per instance only meant that locking could clear one copy (the
         * list's DAO) while the copy Settings uses for flashcards and the scratchpad stayed behind.
         * Keeping it shared also means two instances cannot hold two different plaintext budgets.
         */
        val cache = LinkedHashMap<String, String>(CACHE_INITIAL_CAPACITY, 0.75f, true)
        var cachedPlaintextBytes = 0L
        val cacheLock = Any()

        /**
         * PERF-03: decrypted text per row, so an unchanged row skips decryption on a whole-table
         * emission. Process-wide for the same reason [cache] is: the DAO wrapper that reads is
         * constructed per call, and one map per wrapper would never hit.
         */
        val rowCache = NoteRowTextCache()

        /**
         * A hard cap for tiny notes that never reach the byte budget; large bodies are bounded by
         * [MAX_CACHE_BYTES] long before this.
         */
        const val MAX_CACHE_ENTRIES = 4_096

        /**
         * PERF-1: plaintext retained for repeat reads, not ciphertext entries. ~28 MB holds a
         * large but sane working set on a low-end phone while leaving the heap to Room, Compose
         * and the editor; the previous 512-entry cache could hold far more when bodies were long.
         */
        const val MAX_CACHE_BYTES = 28L * 1024 * 1024
    }
}

/**
 * PERF-03: decrypted text for one note row, keyed by id and validated against the stored bytes.
 *
 * The list flows re-decrypt every row Room emits, and Room emits the whole table after any write.
 * An entry is reused only while the row's `updatedAt` and both stored lengths match, so a version
 * that cannot prove it describes the current bytes always decrypts. [EncryptedNoteDao] also drops
 * the entry on every write it performs, which covers edits that keep both lengths and the
 * timestamp (a checkbox toggle on a home card does exactly that).
 *
 * Access-ordered and bounded, so a huge library cannot retain every body forever. The lock is its
 * own: reads happen on IO while a save can invalidate from another thread, and it must never be
 * held while a cipher runs.
 */
internal class NoteRowTextCache(private val maxEntries: Int = MAX_ENTRIES) {

    private data class Entry(
        val updatedAt: Long,
        val nameLength: Int,
        val descriptionLength: Int,
        val name: String,
        val description: String,
        /** UTF-16 bytes this entry pins, so the row cache has a memory bound and not just a count. */
        val bytes: Long = (name.length.toLong() + description.length.toLong()) * 2L,
    )

    private val map = object : LinkedHashMap<Int, Entry>(
        INITIAL_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean {
            if (size <= maxEntries) return false
            eldest?.value?.let { totalBytes -= it.bytes }
            return true
        }
    }
    private val lock = Any()

    /** Plaintext bytes currently pinned; guarded by [lock]. */
    private var totalBytes = 0L

    fun get(id: Int, updatedAt: Long, name: String, description: String): Pair<String, String>? =
        synchronized(lock) {
            val entry = map[id] ?: return null
            if (entry.updatedAt != updatedAt ||
                entry.nameLength != name.length ||
                entry.descriptionLength != description.length
            ) {
                // Same id, different bytes: the stored row moved on and this entry would hide it.
                map.remove(id)?.let { totalBytes -= it.bytes }
                return null
            }
            entry.name to entry.description
        }

    fun put(
        id: Int,
        updatedAt: Long,
        name: String,
        description: String,
        plaintextName: String,
        plaintextDescription: String,
    ) {
        synchronized(lock) {
            val entry = Entry(
                updatedAt = updatedAt,
                nameLength = name.length,
                descriptionLength = description.length,
                name = plaintextName,
                description = plaintextDescription,
            )
            map.put(id, entry)?.let { totalBytes -= it.bytes }
            totalBytes += entry.bytes
            evictOverBudget()
        }
    }

    private fun evictOverBudget() {
        val eldest = map.entries.iterator()
        while (totalBytes > MAX_BYTES && map.size > 1 && eldest.hasNext()) {
            val entry = eldest.next()
            totalBytes -= entry.value.bytes
            eldest.remove()
        }
    }

    fun invalidate(id: Int) {
        synchronized(lock) {
            map.remove(id)?.let { totalBytes -= it.bytes }
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
            totalBytes = 0L
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val MAX_ENTRIES = 1_024

        /**
         * PERF-03: the row cache holds the same strings the string cache does, so its budget is
         * deliberately below that cache's 28 MB - it may not become the reason a large library
         * stays resident after the string cache has evicted the bodies.
         */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Structural test shared by the note read paths and the TXT export filter.
 *
 * It is deliberately outside [NoteCrypto] and uses `java.util.Base64` (available from API 26) so a
 * plain-JVM unit test can call it without Robolectric or an Android Keystore. A value is sealed
 * only when its payload decodes to more than the 12-byte IV: that is the smallest shape `seal`
 * can produce, and anything shorter is either old plain text or a truncated payload. The latter
 * still fails authentication later; this helper only answers "does it look like ciphertext".
 */
internal object NoteSealFormat {

    const val MARKER = "w1:"
    const val IV_BYTES = 12

    fun isSealedValue(value: String): Boolean {
        if (!value.startsWith(MARKER)) return false
        val payload = try {
            java.util.Base64.getDecoder().decode(value.substring(MARKER.length))
        } catch (e: IllegalArgumentException) {
            return false
        }
        return payload.size > IV_BYTES
    }
}
