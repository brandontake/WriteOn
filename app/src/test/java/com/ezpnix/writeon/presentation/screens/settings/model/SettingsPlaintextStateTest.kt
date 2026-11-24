package com.ezpnix.writeon.presentation.screens.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-09: the settings screen's plaintext copy must not outlive the session that authorized it.
 *
 * The ViewModel cannot be constructed on a plain JVM (Context, WorkManager, the note stack), so the
 * clearing rule is covered here directly; the ViewModel only feeds it the two lock flows.
 */
class SettingsPlaintextStateTest {

    @Test
    fun `an unlocked app with an active vault session keeps the password`() {
        val state = SettingsPlaintextState().apply { backupPassword = "secret" }

        assertFalse(state.shouldClear(isLocked = false, vaultSessionActive = true))
        assertEquals("secret", state.backupPassword)
    }

    @Test
    fun `locking the app drops the password`() {
        val state = SettingsPlaintextState().apply { backupPassword = "secret" }

        assertTrue(state.shouldClear(isLocked = true, vaultSessionActive = true))
        assertTrue(state.clear())
        assertNull("the password must not survive the gate", state.backupPassword)
        assertFalse("clearing twice is a no-op", state.clear())
    }

    @Test
    fun `a vault eviction drops the password even without an app lock`() {
        val state = SettingsPlaintextState().apply { backupPassword = "secret" }

        assertTrue(
            "no-app-lock installs evict the Vault session on background; that is a session end",
            state.shouldClear(isLocked = false, vaultSessionActive = false)
        )
        state.clear()
        assertNull(state.backupPassword)
    }

    @Test
    fun `a fresh view model on an install without a vault clears nothing it did not have`() {
        val state = SettingsPlaintextState()

        assertTrue("the steady state is 'no vault session'", state.shouldClear(isLocked = false, vaultSessionActive = false))
        assertFalse("there was no password to lose", state.clear())
        assertNull(state.backupPassword)
    }
}
