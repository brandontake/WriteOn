package com.ezpnix.writeon.core.util

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One implementation of the password-protected backup archive format.
 *
 * The export, WebDAV upload and WebDAV restore paths each used to carry their own copy of a
 * PBKDF2 + AES-CBC/PKCS5 routine. CBC has no MAC, so a wrong passphrase and a tampered archive were
 * indistinguishable (only padding occasionally failed), and three copies meant three places to get
 * the next change wrong.
 *
 * New archives use AES-256-GCM with a 128-bit tag and PBKDF2-HMAC-SHA256 at 120,000 iterations.
 * They start with the ASCII magic `WBK2`, so they cannot be confused with the legacy layout:
 * `salt(16) || iv(16) || AES-CBC ciphertext` derived with 65,536 iterations. Legacy archives are
 * still readable, which keeps every backup written by 2.0.2 restorable.
 */
object BackupArchiveCrypto {

    private val MAGIC = "WBK2".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val GCM_IV_BYTES = 12
    private const val CBC_IV_BYTES = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** Current-format KDF cost; the OWASP floor for PBKDF2-HMAC-SHA256. */
    const val PBKDF2_ITERATIONS = 120_000

    /** The cost used by 2.0.2 archives; only read, never written. */
    private const val LEGACY_ITERATIONS = 65_536

    /** Copy buffer for the streaming APIs; 256 KiB keeps the per-read cost negligible. */
    private const val STREAM_BUFFER_BYTES = 256 * 1024

    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(GCM_IV_BYTES).apply { SecureRandom().nextBytes(this) }
        val key = derive(password, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return MAGIC + salt + iv + cipher.doFinal(data)
    }

    /**
     * @throws javax.crypto.BadPaddingException when the password is wrong or the archive was
     *   modified. GCM authenticates the whole payload, so this is a real integrity failure rather
     *   than a coincidental padding error.
     */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < MAGIC.size || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return decryptLegacy(data, password)
        }
        val saltStart = MAGIC.size
        val ivStart = saltStart + SALT_BYTES
        val dataStart = ivStart + GCM_IV_BYTES
        require(data.size > dataStart) { "encrypted archive is too short" }
        val salt = data.copyOfRange(saltStart, ivStart)
        val iv = data.copyOfRange(ivStart, dataStart)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            derive(password, salt, PBKDF2_ITERATIONS),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return cipher.doFinal(data, dataStart, data.size - dataStart)
    }

    private fun decryptLegacy(data: ByteArray, password: String): ByteArray {
        require(data.size > SALT_BYTES + CBC_IV_BYTES) { "encrypted archive is too short" }
        val salt = data.copyOfRange(0, SALT_BYTES)
        val iv = data.copyOfRange(SALT_BYTES, SALT_BYTES + CBC_IV_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            derive(password, salt, LEGACY_ITERATIONS),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(data, SALT_BYTES + CBC_IV_BYTES, data.size - SALT_BYTES - CBC_IV_BYTES)
    }

    /**
     * Streams an encryption of [input] into [output] in the current format.
     *
     * The header (`WBK2` + salt + IV) is written first and the body follows through a
     * `CipherOutputStream`, so a backup carrying gigabytes of recordings never needs more than one
     * buffer of heap. Closing the cipher stream closes [output] as well - callers wrap both in
     * `use {}` anyway, and a second close on an [OutputStream] is a no-op.
     */
    fun encryptStream(input: InputStream, output: OutputStream, password: String) {
        val salt = ByteArray(SALT_BYTES).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(GCM_IV_BYTES).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        output.write(MAGIC)
        output.write(salt)
        output.write(iv)
        val crypto = CipherOutputStream(output, cipher)
        input.copyTo(crypto, STREAM_BUFFER_BYTES)
        // Flushes the GCM tag into the output before the caller continues.
        crypto.close()
    }

    /**
     * Returns a stream that decrypts [input] in whichever format it is stored (new `WBK2` or
     * legacy CBC). The header is consumed here; the caller reads the plaintext zip from the
     * returned stream.
     *
     * Note on integrity: a GCM tag failure surfaces while the stream is read (Android's
     * `CipherInputStream` rethrows it as `IOException`), so a tampered archive aborts extraction
     * instead of restoring unauthenticated bytes.
     */
    fun decryptStream(input: InputStream, password: String): InputStream {
        val magic = readUpTo(input, MAGIC.size)
        if (magic.size == MAGIC.size && magic.contentEquals(MAGIC)) {
            val salt = readUpTo(input, SALT_BYTES)
            val iv = readUpTo(input, GCM_IV_BYTES)
            require(salt.size == SALT_BYTES && iv.size == GCM_IV_BYTES) { "encrypted archive is too short" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
            return CipherInputStream(input, cipher)
        }
        // Legacy layout: salt(16) || iv(16) || CBC ciphertext; the bytes already read start the salt.
        val salt = magic + readUpTo(input, SALT_BYTES - magic.size)
        val iv = readUpTo(input, CBC_IV_BYTES)
        require(salt.size == SALT_BYTES && iv.size == CBC_IV_BYTES) { "encrypted archive is too short" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, LEGACY_ITERATIONS), IvParameterSpec(iv))
        return CipherInputStream(input, cipher)
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val result = input.read(buffer, read, count - read)
            if (result < 0) break
            read += result
        }
        return if (read == count) buffer else buffer.copyOfRange(0, read)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
