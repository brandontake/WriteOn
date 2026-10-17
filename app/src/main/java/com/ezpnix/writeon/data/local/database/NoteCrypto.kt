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
 * Both directions fail soft: if the Keystore misbehaves, [seal] stores the text unchanged and
 * [open] returns the stored value, so a hardware hiccup can never cost the user their notes.
 */
class NoteCrypto {

    /** Encrypts [plaintext] for storage. Empty values are left alone. */
    fun seal(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            MARKER + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not encrypt note text, storing it unencrypted", t)
            plaintext
        }
    }

    /** Reads a value produced by [seal]; plain text values pass through unchanged. */
    fun open(stored: String): String {
        if (!stored.startsWith(MARKER)) return stored

        cache[stored]?.let { return it }

        val plaintext = decrypt(stored)
        cache[stored] = plaintext
        return plaintext
    }

    private fun decrypt(stored: String): String {
        return try {
            val payload = Base64.decode(stored.substring(MARKER.length), Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "ciphertext is too short" }

            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not decrypt note text, showing the stored value", t)
            stored
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

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
