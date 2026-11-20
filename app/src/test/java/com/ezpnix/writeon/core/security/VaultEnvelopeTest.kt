package com.ezpnix.writeon.core.security

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERF-2: the vault envelope, at the level the cost model lives on.
 *
 * The audit's number is the thing to pin: unlocking a library of N notes must not perform N PBKDF2
 * derivations. [EncryptionHelper.pbkdf2Derivations] counts them directly, so these tests can assert
 * "one derivation for the session" without timing a 120k-iteration KDF, and the rest cover the two
 * properties a migration must never break - old rows stay readable, and a wrong password or a lost
 * salt fails instead of overwriting anything.
 */
class VaultEnvelopeTest {

    @Test
    fun `two notes in one session derive the KEK once`() {
        val store = InMemoryVaultKeyStore()
        val helper = helper(store)

        val first = helper.encrypt("first note body")
        val second = helper.encrypt("second note body")

        assertEquals("one PBKDF2 for the whole session, not one per note", 1, helper.pbkdf2Derivations)
        assertNotNull("the first write must persist the library salt", store.salt())
        assertTrue(first.startsWith("v3:"))
        assertTrue(second.startsWith("v3:"))
        assertEquals("first note body", decrypted(first, helper))
        assertEquals("second note body", decrypted(second, helper))
    }

    @Test
    fun `a hundred notes unlock with one derivation`() {
        val store = InMemoryVaultKeyStore()
        val writer = helper(store)
        val sealed = (1..100).map { "body $it" to writer.encrypt("body $it") }
        assertEquals(1, writer.pbkdf2Derivations)

        // The audit's 100-row scenario: one PBKDF2 for the session, then one AES body decrypt and
        // one small DEK unwrap per row. Linear in rows, but the expensive part is paid once.
        val session = helper(store)
        sealed.forEach { (plain, value) -> assertEquals(plain, decrypted(value, session)) }
        assertEquals(1, session.pbkdf2Derivations)
    }

    @Test
    fun `identical plaintexts do not produce identical ciphertext`() {
        val helper = helper(InMemoryVaultKeyStore())

        // A fixed body encrypted twice must still differ: each row gets its own DEK and IVs.
        assertFalse(helper.encrypt(PLAINTEXT) == helper.encrypt(PLAINTEXT))
    }

    @Test
    fun `a later session with the same store and passphrase opens the rows`() {
        val store = InMemoryVaultKeyStore()
        val sealed = helper(store).encrypt(PLAINTEXT)

        // A new helper instance is what a process restart looks like: only the persisted salt and
        // the user's password survive. It must derive once and then read every row.
        val laterSession = helper(store)

        assertEquals(PLAINTEXT, decrypted(sealed, laterSession))
        assertEquals(1, laterSession.pbkdf2Derivations)
    }

    @Test
    fun `a wrong passphrase fails and never returns plaintext`() {
        val store = InMemoryVaultKeyStore()
        val sealed = helper(store).encrypt(PLAINTEXT)

        val (plain, status) = helper(store, password = "not the passphrase").decrypt(sealed)

        assertNull(plain)
        assertEquals(DecryptionResult.BAD_PASSWORD, status)
        // The row itself is untouched: a failed unlock must never be able to re-seal over it.
        assertEquals(PLAINTEXT, decrypted(sealed, helper(store)))
    }

    @Test
    fun `a v3 value without a stored salt is reported as invalid, not thrown`() {
        val sealed = helper(InMemoryVaultKeyStore()).encrypt(PLAINTEXT)

        // Fresh install or a restored database whose salt is gone: there is nothing to derive the
        // KEK from, and no password can help, so the result must be INVALID_DATA - not a crash and
        // not BAD_PASSWORD, which would keep the user retyping.
        val (plain, status) = helper(InMemoryVaultKeyStore()).decrypt(sealed)

        assertNull(plain)
        assertEquals(DecryptionResult.INVALID_DATA, status)
    }

    @Test
    fun `v2 rows still decrypt and migrate to the envelope idempotently`() {
        val v2 = sealV2(PLAINTEXT)
        val helper = helper(InMemoryVaultKeyStore())

        val (plain, status) = helper.decrypt(v2)
        assertEquals(DecryptionResult.SUCCESS, status)
        assertEquals(PLAINTEXT, plain)

        assertTrue("a v2 row is what the unlock sweep must pick up", helper.needsEnvelopeMigration(v2))
        assertFalse(helper.isEnvelopeFormat(v2))

        val migrated = helper.encrypt(plain!!)
        assertTrue(migrated.startsWith("v3:"))
        assertFalse("once migrated, the row must not be rewritten again", helper.needsEnvelopeMigration(migrated))
        assertEquals(PLAINTEXT, decrypted(migrated, helper))
    }

    @Test
    fun `legacy rows are re-sealed too`() {
        val legacy = sealLegacy(PLAINTEXT)
        val helper = helper(InMemoryVaultKeyStore())

        val (plain, status) = helper.decrypt(legacy)
        assertEquals(DecryptionResult.SUCCESS, status)
        assertEquals(PLAINTEXT, plain)
        assertTrue(helper.needsEnvelopeMigration(legacy))
        assertTrue(helper.isLegacyFormat(legacy))

        val migrated = helper.encrypt(plain!!)
        assertTrue(helper.isEnvelopeFormat(migrated))
        assertFalse(helper.needsEnvelopeMigration(migrated))
        assertFalse(helper.isLegacyFormat(migrated))
    }

    @Test
    fun `a blank field needs no migration`() {
        val helper = helper(InMemoryVaultKeyStore())

        // `needsEnvelopeMigration` is the unlock sweep's predicate. A blank column is not an old
        // ciphertext, and encrypting one would replace whatever the row holds with a new value
        // nobody asked for.
        assertFalse(helper.needsEnvelopeMigration(""))
        assertFalse(helper.needsEnvelopeMigration("   "))
        assertFalse(helper.isEnvelopeFormat(""))
    }

    @Test
    fun `setting a new password drops the cached KEK`() {
        val store = InMemoryVaultKeyStore()
        val helper = helper(store)
        val sealed = helper.encrypt(PLAINTEXT)
        assertEquals(1, helper.pbkdf2Derivations)

        helper.setPassword(OTHER_PASSWORD)

        // A different password must not be able to reuse the previous KEK, and it must not open
        // rows sealed under the old one. The derivation count moving proves the cache was dropped.
        val (plain, status) = helper.decrypt(sealed)
        assertNull(plain)
        assertEquals(DecryptionResult.BAD_PASSWORD, status)
        assertEquals(2, helper.pbkdf2Derivations)

        helper.removePassword()
        assertEquals(DecryptionResult.LOADING, helper.decrypt(sealed).second)
    }

    private fun helper(
        store: VaultKeyStore,
        password: String = CORRECT_PASSWORD,
    ): EncryptionHelper = EncryptionHelper(StringBuilder(), store).apply { setPassword(password) }

    private fun decrypted(sealed: String, helper: EncryptionHelper): String? {
        val (plain, status) = helper.decrypt(sealed)
        assertEquals(DecryptionResult.SUCCESS, status)
        return plain
    }

    /**
     * A `v2` row built the way the previous build wrote it: per-row random salt, PBKDF2-HMAC-SHA256
     * with 120,000 iterations, AES/GCM with a 12-byte IV. Pinned here rather than produced by
     * production code because `encrypt` now always writes `v3` - the compatibility guarantee has to
     * be tested against the old format, not against the new writer.
     */
    private fun sealV2(plain: String, password: String = CORRECT_PASSWORD): String {
        val salt = randomBytes(16)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = SecretKeySpec(
            factory.generateSecret(
                PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
            ).encoded,
            "AES"
        )
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return "v2:${b64.encodeToString(salt)}:${b64.encodeToString(iv)}:${b64.encodeToString(ciphertext)}"
    }

    /** The pre-PBKDF2 shape: same as [sealV2] but keyed by one SHA-256 pass and without a salt. */
    private fun sealLegacy(plain: String, password: String = CORRECT_PASSWORD): String {
        val key = SecretKeySpec(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(StandardCharsets.UTF_8)),
            "AES"
        )
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return "${b64.encodeToString(iv)}:${b64.encodeToString(ciphertext)}"
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).apply { SecureRandom().nextBytes(this) }

    private companion object {
        const val CORRECT_PASSWORD = "correct horse battery staple"
        const val OTHER_PASSWORD = "a different passphrase"
        const val PLAINTEXT = "vault note body with 中文 and 🎉"
        const val PBKDF2_ITERATIONS = 120_000
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
    }
}
