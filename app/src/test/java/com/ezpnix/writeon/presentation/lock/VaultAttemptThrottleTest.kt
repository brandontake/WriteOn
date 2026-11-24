package com.ezpnix.writeon.presentation.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-13: the Vault passphrase throttle, with the clock injected.
 *
 * The app-lock verifier already persisted failures and measured the wait on elapsed-realtime; the
 * Vault had nothing, so the unlock dialog and Home's banner were a free oracle as fast as PBKDF2
 * allowed. The schedule is short at first (10s after five misses) and grows to a 30-minute cap.
 * The store is a plain map here, so the persistence rules are observable: a new instance (process
 * death) sees the wait, and a reboot - detected by elapsed-realtime going backwards - falls back
 * to the wall-clock copy exactly like [LockManager.restoreLockoutFromPrefs].
 */
class VaultAttemptThrottleTest {

    private class FakeStore : VaultAttemptStore {
        val ints = HashMap<String, Int>()
        val longs = HashMap<String, Long>()
        var batches = 0

        override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
        override fun getLong(key: String, defaultValue: Long): Long = longs[key] ?: defaultValue
        override fun apply(changes: List<VaultAttemptChange>) {
            batches++
            changes.forEach { change ->
                when (change) {
                    is VaultAttemptChange.PutInt -> ints[change.key] = change.value
                    is VaultAttemptChange.PutLong -> longs[change.key] = change.value
                    is VaultAttemptChange.Remove -> {
                        ints.remove(change.key)
                        longs.remove(change.key)
                    }
                }
            }
        }
    }

    private class Clock(var elapsed: Long = 1_000_000L, var wall: Long = 2_000_000L) {
        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    @Test
    fun `five failures start the short first lockout`() {
        val clock = Clock()
        val throttle = VaultAttemptThrottle(FakeStore(), { clock.elapsed }, { clock.wall })

        repeat(4) {
            throttle.registerFailure()
            assertFalse("four misses are still free", throttle.isLockedOut())
        }
        throttle.registerFailure()

        assertTrue(throttle.isLockedOut())
        assertEquals(10, throttle.remainingSeconds())

        clock.advance(9_000)
        assertEquals(1, throttle.remainingSeconds())
        clock.advance(1_000)
        assertFalse(throttle.isLockedOut())
    }

    @Test
    fun `a lockout transition and its reset are one persisted batch each`() {
        val clock = Clock()
        val store = FakeStore()
        val throttle = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })

        repeat(5) { throttle.registerFailure() }
        // R3-07: four free misses plus the lockout transition - one shared-prefs commit per call.
        assertEquals(5, store.batches)

        throttle.reset()
        assertEquals(6, store.batches)
    }

    @Test
    fun `the next lockout is longer`() {
        val clock = Clock()
        val store = FakeStore()
        val throttle = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })

        repeat(5) { throttle.registerFailure() }
        clock.advance(10_000)
        assertFalse("the first wait expired", throttle.isLockedOut())

        repeat(5) { throttle.registerFailure() }
        // Second lockout step from the shared schedule: one minute.
        assertEquals(60, throttle.remainingSeconds())
    }

    @Test
    fun `a successful unlock clears the streak and the wait`() {
        val clock = Clock()
        val store = FakeStore()
        val throttle = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })

        repeat(5) { throttle.registerFailure() }
        assertTrue(throttle.isLockedOut())
        throttle.reset()

        assertFalse(throttle.isLockedOut())
        repeat(4) {
            throttle.registerFailure()
            assertFalse("the streak started over", throttle.isLockedOut())
        }
    }

    @Test
    fun `the wait survives a process restart`() {
        val clock = Clock()
        val store = FakeStore()
        val first = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })
        repeat(5) { first.registerFailure() }

        clock.advance(4_000)
        // A new process builds a new instance over the same persisted counters.
        val afterDeath = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })
        assertTrue(afterDeath.isLockedOut())
        assertEquals(6, afterDeath.remainingSeconds())
    }

    @Test
    fun `a reboot falls back to the wall clock`() {
        val clock = Clock()
        val store = FakeStore()
        val first = VaultAttemptThrottle(store, { clock.elapsed }, { clock.wall })
        repeat(5) { first.registerFailure() }

        // elapsedRealtime restarts at boot, so it is now far behind the persisted start stamp;
        // the wall clock is five seconds into the ten-second wait.
        clock.wall += 5_000
        val afterReboot = VaultAttemptThrottle(store, { 100L }, { clock.wall })
        assertTrue(afterReboot.isLockedOut())
        assertEquals(5, afterReboot.remainingSeconds())

        clock.wall += 5_000
        val expired = VaultAttemptThrottle(store, { 100L }, { clock.wall })
        assertFalse(expired.isLockedOut())
    }
}
