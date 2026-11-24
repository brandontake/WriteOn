package com.ezpnix.writeon.presentation.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-02: a failed Vault guess may only remove the passphrase it installed.
 *
 * Home's banner and the editor's unlock dialog share one in-memory passphrase. The old failure
 * path called `EncryptionHelper.removePassword()` unconditionally, so a wrong guess that finished
 * after the editor had verified and saved with the right one deleted a working session. Ownership
 * is tracked by value; this test pins the compare-and-remove and the "restore what you replaced"
 * transition that the lock manager now runs under its unlock mutex.
 */
class VaultSessionOwnershipTest {

    @Test
    fun `a candidate owns exactly its own passphrase`() {
        val ownership = VaultSessionOwnership()
        assertNull(ownership.begin("correct horse"))
        assertTrue(ownership.owns("correct horse"))
        assertFalse(ownership.owns("correct hors"))
        assertFalse(ownership.owns("correct horsf"))
        assertFalse("a locked vault owns nothing", VaultSessionOwnership().owns("anything"))
    }

    @Test
    fun `compare-and-remove leaves another session alone`() {
        val ownership = VaultSessionOwnership()
        ownership.begin("editor-pass")

        assertFalse(
            "a failed Home guess must not remove the editor's passphrase",
            ownership.removeIfOwned("home-typo"),
        )
        assertTrue(ownership.owns("editor-pass"))
        assertTrue("the owner can remove its own session", ownership.removeIfOwned("editor-pass"))
        assertFalse(ownership.owns("editor-pass"))
    }

    @Test
    fun `a failed attempt restores the session it replaced`() {
        val ownership = VaultSessionOwnership()
        ownership.begin("editor-pass")

        val previous = ownership.begin("home-typo")
        assertEquals("editor-pass", previous)
        assertTrue(ownership.owns("home-typo"))

        ownership.restore(previous!!)
        assertTrue("the editor's session is back", ownership.owns("editor-pass"))
        assertFalse(ownership.owns("home-typo"))
    }

    @Test
    fun `sameSecret compares the whole value`() {
        assertTrue(sameSecret("", ""))
        assertTrue(sameSecret("vault-pass", "vault-pass"))
        assertFalse(sameSecret("vault-pass", "vault-pas"))
        assertFalse(sameSecret("vault-pass", "vault-poss"))
    }
}
