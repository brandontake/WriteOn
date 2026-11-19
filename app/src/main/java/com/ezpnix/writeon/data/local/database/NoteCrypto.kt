package com.ezpnix.writeon.data.local.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.Collections
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
 * Values are sealed as `w1:<base64 iv + ciphertext>`; anything without that marker is treated as
 * plain text, so rows written before this class existed (and rows restored from an old backup) stay
 * readable, and saved rows are re-encrypted the next time they are written.
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
        if (!stored.startsWith(MARKER)) return stored

        cache[stored]?.let { return it }

        val plaintext = openOrNull(stored)
            ?: throw IllegalStateException("Could not decrypt stored note text; refusing to replace it")
        cache[stored] = plaintext
        return plaintext
    }

    /** True when [value] was produced by [seal] rather than stored as plain text. */
    fun isSealed(value: String): Boolean = value.startsWith(MARKER)

    /**
     * Like [open], but returns null when a sealed value cannot be decrypted, so a caller that
     * rewrites data (the 7 -> 8 body migration) can tell "plain text" from "ciphertext we must not
     * touch" instead of writing garbage back over an unreadable note.
     */
    fun openOrNull(stored: String): String? {
        if (!stored.startsWith(MARKER)) return stored

        cache[stored]?.let { return it }

        return try {
            withSecretKey { key -> decryptPayload(stored, key) }
                .also { cache[stored] = it }
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
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /** Guards the one-time load and the invalidation path; never held while a cipher runs. */
    private val keyLock = Any()

    /**
     * Recently read values, so re-reading an unchanged note is not a Keystore round trip. Sealing
     * is randomised, which means a freshly saved note misses the cache while everything else hits.
     */
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(CACHE_INITIAL_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

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
        const val MAX_CACHE_ENTRIES = 512
    }
}
