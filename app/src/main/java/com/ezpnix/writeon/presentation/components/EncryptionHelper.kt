package com.ezpnix.writeon.presentation.components

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault encryption for individual notes.
 *
 * Ciphertext layout:
 *  - current: `v2:<b64 salt>:<b64 iv>:<b64 ciphertext+tag>`
 *  - legacy:  `<b64 iv>:<b64 ciphertext+tag>`
 *
 * The legacy layout derived its AES key with a single SHA-256 pass over the password. That is fast,
 * which is the only thing it had going for it: a hash of a short user passphrase is exhaustible in
 * practice, so anyone holding the database file could recover it offline. New writes use PBKDF2 with
 * a random per-note salt. Legacy values are still decrypted on read through the old derivation, so
 * upgrading the app does not lock anybody out of notes written by the previous build.
 */
class EncryptionHelper(private val mutableVaultPassword: StringBuilder) {

    fun isPasswordEmpty(): Boolean {
        return mutableVaultPassword.isEmpty()
    }

    fun removePassword() {
        mutableVaultPassword.setLength(0)
        derivedKeys.clear()
    }

    fun setPassword(newPassword: String) {
        mutableVaultPassword.setLength(0)
        mutableVaultPassword.append(newPassword)
        derivedKeys.clear()
    }

    /**
     * Seals [data] with the currently loaded vault passphrase.
     *
     * Fails fast when no passphrase is loaded. Encrypting with an empty passphrase used to succeed
     * silently and produce a value [decrypt] can never read back, because every read short-circuits
     * to [DecryptionResult.LOADING] while the vault is empty - the note simply vanished from the
     * list. Callers must check [isPasswordEmpty] first and keep the note in plain text instead.
     */
    fun encrypt(data: String): String {
        check(!mutableVaultPassword.isEmpty()) {
            "Refusing to encrypt with an empty vault passphrase"
        }
        val salt = newSalt()
        val secretKey = deriveKey(mutableVaultPassword.toString(), salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ivBytes = cipher.iv
        val encryptedBytes = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return listOf(
            FORMAT_PREFIX,
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(ivBytes, Base64.NO_WRAP),
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
        ).joinToString(SEPARATOR)
    }

    fun decrypt(data: String): Pair<String?, DecryptionResult> {
        if (mutableVaultPassword.isEmpty()) return Pair(null, DecryptionResult.LOADING)
        if (data.isBlank()) return Pair(null, DecryptionResult.BLANK_DATA)

        return try {
            val parts = data.split(SEPARATOR)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            if (parts.firstOrNull() == FORMAT_PREFIX) {
                if (parts.size != 4) return Pair(null, DecryptionResult.INVALID_DATA)
                val salt = Base64.decode(parts[1], Base64.NO_WRAP)
                val ivBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                val encryptedBytes = Base64.decode(parts[3], Base64.NO_WRAP)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(mutableVaultPassword.toString(), salt),
                    GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
                )
                Pair(String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8), DecryptionResult.SUCCESS)
            } else {
                // Notes written before the PBKDF2 change. `Base64.DEFAULT` wrapped the original
                // output across lines, so the surrounding whitespace has to go before decoding.
                if (parts.size != 2) return Pair(null, DecryptionResult.INVALID_DATA)
                val ivBytes = Base64.decode(parts[0].filterNot { it.isWhitespace() }, Base64.NO_WRAP)
                val encryptedBytes = Base64.decode(parts[1].filterNot { it.isWhitespace() }, Base64.NO_WRAP)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveLegacyKey(mutableVaultPassword.toString()),
                    GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
                )
                Pair(String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8), DecryptionResult.SUCCESS)
            }
        } catch (e: Exception) {
            Pair(null, DecryptionResult.BAD_PASSWORD)
        }
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).apply { SecureRandom().nextBytes(this) }

    /**
     * Derives the AES key for one note.
     *
     * PBKDF2 is deliberately expensive, and every note carries its own salt, so the results are kept
     * in a small LRU cache keyed by salt. Decrypting a screen full of notes otherwise pays the full
     * derivation cost once per note on every keystroke of the note list.
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val cacheKey = salt.toList()
        derivedKeys[cacheKey]?.let { return it }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        derivedKeys[cacheKey] = key
        return key
    }

    /** The pre-PBKDF2 key derivation, kept only to read notes written by earlier versions. */
    private fun deriveLegacyKey(password: String): SecretKey {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return SecretKeySpec(sha256.digest(password.toByteArray(StandardCharsets.UTF_8)), "AES")
    }

    private companion object {
        const val FORMAT_PREFIX = "v2"
        const val SEPARATOR = ":"
        const val SALT_BYTES = 16
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256

        /**
         * Iterations for the note vault.
         *
         * Lower than the screen lock's 600_000 because this key is derived per note, and a long
         * list would otherwise stall on every decryption; 120_000 is the OWASP floor for
         * PBKDF2-HMAC-SHA256 and is still far beyond what a single SHA-256 pass gives an attacker.
         */
        const val PBKDF2_ITERATIONS = 120_000

        val derivedKeys = object : LinkedHashMap<List<Byte>, SecretKey>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Byte>, SecretKey>): Boolean =
                size > MAX_CACHED_KEYS
        }

        const val MAX_CACHED_KEYS = 64
    }
}

enum class DecryptionResult {
    EMPTY,
    SUCCESS,
    INVALID_DATA,
    BLANK_DATA,
    BAD_PASSWORD,
    LOADING
}