package com.ezpnix.writeon.presentation.screens.edit.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REG-06: the editor may only clear its plaintext for a Vault note.
 *
 * The bug was that `LockManager.evictVaultSession()` dropped the passphrase while the open editor
 * kept the body on screen (there was no app lock to put a gate up). The fix observes the session
 * from the ViewModel, but the clear must be scoped: a plain note's text does not depend on the
 * passphrase and must never be wiped when the Vault locks or the app is locked.
 */
class VaultEditorLockPolicyTest {

    @Test
    fun `a vault note locks when the session is gone`() {
        assertTrue(vaultEditorMustLock(encrypted = true, sessionActive = false))
    }

    @Test
    fun `a vault note stays open while the session is active`() {
        assertFalse(vaultEditorMustLock(encrypted = true, sessionActive = true))
    }

    @Test
    fun `a plain note is never wiped`() {
        assertFalse(vaultEditorMustLock(encrypted = false, sessionActive = true))
        assertFalse(vaultEditorMustLock(encrypted = false, sessionActive = false))
    }
}
