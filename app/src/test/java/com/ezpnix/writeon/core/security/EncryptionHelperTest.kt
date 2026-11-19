package com.ezpnix.writeon.core.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the vault passphrase layer (report item T2).
 *
 * [EncryptionHelper] now uses java.util.Base64 instead of android.util.Base64, which is what
 * makes these paths testable without a device: the on-disk format (standard alphabet, padding,
 * no line breaks) is unchanged, so ciphertext written by earlier builds still decrypts.
 */
class EncryptionHelperTest {

    /**
     * A fresh helper whose derived-key cache is cleared.
     *
     * The PBKDF2 cache is static and keyed by salt alone (one passphrase per process in the app),
     * so a test that decrypts the same salt under a different password must not see the previous
     * derivation - setPassword is the public operation that resets it.
     */
    private fun helper(password: String = CORRECT_PASSWORD) =
        EncryptionHelper(StringBuilder()).apply { setPassword(password) }

    @Test
    fun `encrypt round-trips through the current v2 format`() {
        val sealed = helper().encrypt(PLAINTEXT)
        assertTrue("expected v2: prefix, got $sealed", sealed.startsWith("v2:"))
        // Four colon-separated parts: prefix, salt, iv, ciphertext.
        assertEquals(4, sealed.split(":").size)

        val (plain, status) = helper().decrypt(sealed)
        assertEquals(DecryptionResult.SUCCESS, status)
        assertEquals(PLAINTEXT, plain)
    }

    @Test
    fun `a different passphrase is rejected instead of returning garbage`() {
        val sealed = helper().encrypt(PLAINTEXT)
        val (plain, status) = helper(password = "wrong").decrypt(sealed)
        assertNull(plain)
        assertEquals(DecryptionResult.BAD_PASSWORD, status)
    }

    @Test
    fun `an empty passphrase reports LOADING rather than failing`() {
        val sealed = helper().encrypt(PLAINTEXT)
        val (plain, status) = helper(password = "").decrypt(sealed)
        assertNull(plain)
        assertEquals(DecryptionResult.LOADING, status)
    }

    @Test
    fun `blank ciphertext reports BLANK_DATA`() {
        val (plain, status) = helper().decrypt("   ")
        assertNull(plain)
        assertEquals(DecryptionResult.BLANK_DATA, status)
    }

    @Test
    fun `malformed ciphertext reports INVALID_DATA`() {
        val (plain, status) = helper().decrypt("v2:not-enough-parts")
        assertNull(plain)
        assertEquals(DecryptionResult.INVALID_DATA, status)
    }

    @Test
    fun `legacy single SHA-256 ciphertext still decrypts`() {
        // The pre-PBKDF2 layout: `<b64 iv>:<b64 ciphertext>` with the key derived from a single
        // SHA-256 pass over the password. Built here with the same recipe so the read path is
        // pinned: upgrading must not lock older vault notes out (report A4/T2).
        val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest(CORRECT_PASSWORD.toByteArray(StandardCharsets.UTF_8)),
            "AES"
        )
        val iv = ByteArray(12).also { it[0] = 7 } // fixed, non-zero for determinism
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(PLAINTEXT.toByteArray(StandardCharsets.UTF_8))
        val legacy = "${java.util.Base64.getEncoder().encodeToString(iv)}:" +
            java.util.Base64.getEncoder().encodeToString(ciphertext)

        val (plain, status) = helper().decrypt(legacy)
        assertEquals(DecryptionResult.SUCCESS, status)
        assertEquals(PLAINTEXT, plain)
    }

    @Test
    fun `a legacy ciphertext under the wrong password is rejected`() {
        val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest(CORRECT_PASSWORD.toByteArray(StandardCharsets.UTF_8)),
            "AES"
        )
        val iv = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(PLAINTEXT.toByteArray(StandardCharsets.UTF_8))
        val legacy = "${java.util.Base64.getEncoder().encodeToString(iv)}:" +
            java.util.Base64.getEncoder().encodeToString(ciphertext)

        val (plain, status) = helper(password = "wrong").decrypt(legacy)
        assertNull(plain)
        assertEquals(DecryptionResult.BAD_PASSWORD, status)
    }

    private companion object {
        const val CORRECT_PASSWORD = "correct horse battery staple"
        const val PLAINTEXT = "vault note body with 中文 and 🎉"
    }
}
