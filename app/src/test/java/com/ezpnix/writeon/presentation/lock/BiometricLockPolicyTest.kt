package com.ezpnix.writeon.presentation.lock

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REG-07/UX-04: the two decisions that must never downgrade the app lock on their own.
 *
 * Before this, `biometricUnavailable` was `canAuthenticate(...) == SUCCESS && biometricEnabled`,
 * so any transient `BIOMETRIC_ERROR_HW_UNAVAILABLE` made a biometric-only lock look unrecoverable
 * and [LockManager.disableUnusableBiometricLock] turned the app lock off permanently. The classifier
 * keeps the lock for every error a retry can fix and reserves "unrecoverable" for "there is no
 * enrollment / no sensor". The credential rule is the other half: removing the last PIN or pattern
 * must take biometric with it, or the unusable biometric-only state can be built from the UI.
 *
 * Both helpers are pure; no device, prompt or Keystore is involved.
 */
class BiometricLockPolicyTest {

    @Test
    fun `a successful authenticator set is available`() {
        assertEquals(
            BiometricAvailability.AVAILABLE,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `temporary sensor problems keep the lock and ask for a retry`() {
        // These are the cases that used to call disableUnusableBiometricLock().
        assertEquals(
            BiometricAvailability.TRANSIENT_ERROR,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
        assertEquals(
            BiometricAvailability.TRANSIENT_ERROR,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
        assertEquals(
            BiometricAvailability.TRANSIENT_ERROR,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
        assertEquals(
            BiometricAvailability.TRANSIENT_ERROR,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `only a missing enrollment or hardware is unrecoverable`() {
        assertEquals(
            BiometricAvailability.UNRECOVERABLE,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricAvailability.UNRECOVERABLE,
            classifyBiometricAvailability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        // The pinned biometric library predates the platform's HW_NOT_PRESENT constant (14).
        assertEquals(
            BiometricAvailability.UNRECOVERABLE,
            classifyBiometricAvailability(14),
        )
    }

    @Test
    fun `removing the last app credential also clears biometric`() {
        assertTrue(
            "a biometric-only state must not survive the PIN removal",
            mustClearBiometricWithLastCredential(
                biometricEnabled = true,
                pinEnabled = false,
                patternEnabled = false,
            ),
        )
        assertFalse(
            "a pattern is still a fallback credential",
            mustClearBiometricWithLastCredential(
                biometricEnabled = true,
                pinEnabled = false,
                patternEnabled = true,
            ),
        )
        assertFalse(
            "a PIN is still a fallback credential",
            mustClearBiometricWithLastCredential(
                biometricEnabled = true,
                pinEnabled = true,
                patternEnabled = false,
            ),
        )
        assertFalse(
            "biometric that is already off needs no cleanup",
            mustClearBiometricWithLastCredential(
                biometricEnabled = false,
                pinEnabled = false,
                patternEnabled = false,
            ),
        )
    }
}
