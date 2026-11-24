package com.ezpnix.writeon.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SYN-7: a whole-call budget scaled by archive size instead of a fixed five minutes.
 *
 * The fixed `callTimeout = 300s` cut every attempt of an archive whose upload takes longer than
 * that - 200 MB at 1.5 Mbps is roughly 18 minutes - so a media-heavy library could never finish a
 * WebDAV backup.
 */
class WebDavTransferTimeoutsTest {

    @Test
    fun `an empty transfer still gets the base budget`() {
        assertEquals(
            WebDavHttp.TRANSFER_BASE_SECONDS,
            WebDavTransferTimeouts.secondsFor(0),
        )
    }

    @Test
    fun `a two megabyte upload gets more than the old five minute cap`() {
        val twoMegabytes = 2L * 1024 * 1024

        // The audit's slow link: ~20 KB/s takes about 100 seconds; the budget must cover it.
        assertTrue(WebDavTransferTimeouts.secondsFor(twoMegabytes) > 100L)
    }

    @Test
    fun `a two hundred megabyte archive is not cut off at five minutes`() {
        val twoHundredMegabytes = 200L * 1024 * 1024
        val budget = WebDavTransferTimeouts.secondsFor(twoHundredMegabytes)

        // ~18 minutes on a 1.5 Mbps uplink; the old 300 s cap could never reach it.
        assertTrue("budget $budget s must exceed 18 minutes", budget > 18L * 60L)
        assertTrue("budget $budget s must exceed the fixed old cap", budget > WebDavHttp.CALL_TIMEOUT_SECONDS)
    }

    @Test
    fun `the budget grows with the archive and never shrinks`() {
        val sizes = listOf(0L, 1L, 1024L, 1L shl 20, 1L shl 28, 1_073_741_824L)
        var previous = -1L

        sizes.forEach { size ->
            val budget = WebDavTransferTimeouts.secondsFor(size)
            assertTrue("budget must grow at $size bytes", budget >= previous)
            previous = budget
        }
    }

    @Test
    fun `a negative size is treated as empty instead of shrinking the budget`() {
        assertEquals(
            WebDavHttp.TRANSFER_BASE_SECONDS,
            WebDavTransferTimeouts.secondsFor(-1L),
        )
    }
}
