package com.ezpnix.writeon.data.local.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * N-3: both backfill markers must be cleared after a restore before the process restarts.
 *
 * The distinction this pins is the observable one: `reset()` is the method every restore path
 * calls, and the next launch's `isDone()` must see false. The implementation uses `commit()`
 * rather than `apply()` because the restart can beat an asynchronous disk write; that is verified
 * by code review, while this test keeps the key names and the mark/reset pair from drifting.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyBackfillFlagTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBothMarkers() {
        LegacyPlaintextBackfillFlag.reset(context)
        LegacyCaptionBackfillFlag.reset(context)
    }

    @Test
    fun `the note marker can be marked and reset`() {
        LegacyPlaintextBackfillFlag.markDone(context)
        assertTrue(LegacyPlaintextBackfillFlag.isDone(context))

        LegacyPlaintextBackfillFlag.reset(context)
        assertFalse(LegacyPlaintextBackfillFlag.isDone(context))
    }

    @Test
    fun `the caption marker is independent and resettable`() {
        LegacyCaptionBackfillFlag.markDone(context)
        assertTrue(LegacyCaptionBackfillFlag.isDone(context))
        assertFalse("marking captions must not mark notes", LegacyPlaintextBackfillFlag.isDone(context))

        LegacyCaptionBackfillFlag.reset(context)
        assertFalse(LegacyCaptionBackfillFlag.isDone(context))
    }
}
