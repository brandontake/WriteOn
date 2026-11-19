package com.ezpnix.writeon.presentation.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage for the A7 reset policy (report item A7, LockManager.encryptedPreferences).
 *
 * The bug was behavioural, not cryptographic: an unreadable encrypted store was deleted with
 * only a logcat line, so the app lock vanished while the user still believed the notes were
 * protected. The decision itself is a three-way branch that needs no device, so it lives in
 * [planLockReset] and is pinned here:
 *
 *  - store opens                -> keep it, say nothing (the normal path);
 *  - open fails, no file stored -> delete is a no-op, stay quiet (nothing was lost);
 *  - open fails, file stored    -> delete AND announce. This third case is the whole point of
 *    A7: it used to be indistinguishable from the second one outside of logcat.
 */
class LockResetPolicyTest {

    @Test
    fun `an available store is kept and nobody is told`() {
        assertEquals(LockResetAction.KEEP, planLockReset(encryptedStoreAvailable = true, storedLockFileExists = true))
        assertEquals(LockResetAction.KEEP, planLockReset(encryptedStoreAvailable = true, storedLockFileExists = false))
    }

    @Test
    fun `a failed open with no stored file resets quietly`() {
        assertEquals(
            LockResetAction.DELETE_QUIETLY,
            planLockReset(encryptedStoreAvailable = false, storedLockFileExists = false),
        )
    }

    @Test
    fun `a failed open that destroys a stored lock must announce it`() {
        assertEquals(
            LockResetAction.DELETE_AND_NOTIFY,
            planLockReset(encryptedStoreAvailable = false, storedLockFileExists = true),
        )
    }
}
