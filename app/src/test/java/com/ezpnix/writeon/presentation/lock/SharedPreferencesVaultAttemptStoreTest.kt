package com.ezpnix.writeon.presentation.lock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R3-07: the production store must issue exactly one synchronous commit per transition.
 *
 * The throttle test could only count a fake store's batches. This exercises the real
 * [SharedPreferencesVaultAttemptStore] with the production write path and counts the commits.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesVaultAttemptStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a whole lockout transition is one commit and every change lands`() {
        val prefs = context.getSharedPreferences("vault-attempt-store-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var commits = 0
        val store = SharedPreferencesVaultAttemptStore(prefs) { editor ->
            commits++
            editor.commit()
        }

        store.apply(
            listOf(
                VaultAttemptChange.PutInt("failures", 5),
                VaultAttemptChange.PutInt("lockouts", 1),
                VaultAttemptChange.PutLong("wait", 10_000L),
                VaultAttemptChange.PutLong("elapsed", 1_234L),
                VaultAttemptChange.Remove("stale"),
            )
        )

        assertEquals("one transaction, one synchronous disk write", 1, commits)
        assertEquals(5, store.getInt("failures", 0))
        assertEquals(1, store.getInt("lockouts", 0))
        assertEquals(10_000L, store.getLong("wait", 0L))
        assertEquals(1_234L, store.getLong("elapsed", 0L))
    }

    @Test
    fun `an empty batch writes nothing`() {
        val prefs = context.getSharedPreferences("vault-attempt-store-empty", Context.MODE_PRIVATE)
        var commits = 0
        val store = SharedPreferencesVaultAttemptStore(prefs) { editor ->
            commits++
            editor.commit()
        }

        store.apply(emptyList())

        assertEquals(0, commits)
    }
}
