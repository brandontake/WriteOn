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
 * [EncryptionHelper] uses java.util.Base64 instead of android.util.Base64, which is what makes
 * these paths testable without a device: the encoding (standard alphabet, padding, no line breaks)
 * is unchanged, so ciphertext written by earlier builds still decrypts. Envelope-specific coverage
 * - one KEK per session, the v3 layout, migration - lives in [VaultEnvelopeTest].
 */
class EncryptionHelperTest {

    /**
     * A fresh helper whose derived-key and KEK caches are empty.
     *
     * The v2 PBKDF2 cache is static and keyed by salt alone (one passphrase per process in the
     * app), so a test that decrypts the same salt under a different password must not see the
     * previous derivation - setPassword is the public operation that resets it. Tests that need the
     * envelope salt to survive into a "later session" pass the same [keyStore] to both helpers.
     */
    private fun helper(
        password: String = CORRECT_PASSWORD,
        keyStore: VaultKeyStore = InMemoryVaultKeyStore(),
    ) = EncryptionHelper(StringBuilder(), keyStore).apply { setPassword(password) }

    @Test
    fun `encrypt round-trips through the current v3 envelope`() {
        val store = InMemoryVaultKeyStore()
        val sealed = helper(keyStore = store).encrypt(PLAINTEXT)
        assertTrue("expected v3: prefix, got $sealed", sealed.startsWith("v3:"))
        // Five colon-separated parts: prefix, wrap iv, wrapped DEK, body iv, body ciphertext.
        assertEquals(5, sealed.split(":").size)

        val (plain, status) = helper(keyStore = store).decrypt(sealed)
        assertEquals(DecryptionResult.SUCCESS, status)
        assertEquals(PLAINTEXT, plain)
    }

    @Test
    fun `a different passphrase is rejected instead of returning garbage`() {
        val store = InMemoryVaultKeyStore()
        val sealed = helper(keyStore = store).encrypt(PLAINTEXT)
        val (plain, status) = helper(password = "wrong", keyStore = store).decrypt(sealed)
        assertNull(plain)
        assertEquals(DecryptionResult.BAD_PASSWORD, status)
    }

    @Test
    fun `an empty passphrase reports LOADING rather than failing`() {
        val store = InMemoryVaultKeyStore()
        val sealed = helper(keyStore = store).encrypt(PLAINTEXT)
        val (plain, status) = helper(password = "", keyStore = store).decrypt(sealed)
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
