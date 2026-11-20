package com.ezpnix.writeon.data.local.database

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * V-11: the `w1:` marker is a hint, not proof of ciphertext.
 *
 * A note written before `NoteCrypto` existed can legitimately start with `w1:` ("w1:hello"). The
 * read paths used to believe the marker alone, fail the decrypt and replace a readable note with
 * the undecryptable placeholder. These tests pin the structural test: only base64 of more than one
 * IV counts as a seal, and a *real* seal that cannot be opened still returns null - it must not be
 * downgraded to plaintext either.
 *
 * Robolectric rather than a device because `android.util.Base64` is real Android code while the
 * Keystore is absent: a structurally valid payload then fails exactly like unreadable ciphertext,
 * which is the failure path these tests need. `NoteDatabaseMigrationTest` relies on the same gap.
 */
@RunWith(RobolectricTestRunner::class)
class NoteCryptoFormatTest {

    private val crypto = NoteCrypto()

    @Test
    fun `a short w1 value reads as plain text`() {
        val stored = "w1:hello"

        assertFalse(crypto.isSealed(stored))
        assertEquals(stored, crypto.open(stored))
        assertEquals(stored, crypto.openOrNull(stored))
    }

    @Test
    fun `an empty payload is plain text`() {
        val stored = "w1:"

        assertFalse(crypto.isSealed(stored))
        assertEquals(stored, crypto.openOrNull(stored))
    }

    @Test
    fun `undecodable base64 is plain text`() {
        val stored = "w1:%%%not-base64%%%"

        assertFalse(crypto.isSealed(stored))
        assertEquals(stored, crypto.openOrNull(stored))
    }

    @Test
    fun `a payload that fits in the IV alone is not a seal`() {
        // 12 bytes is exactly the IV `seal` writes; a seal always has ciphertext after it.
        val stored = "w1:" + Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)

        assertFalse(crypto.isSealed(stored))
        assertEquals(stored, crypto.openOrNull(stored))
    }

    @Test
    fun `a structurally valid w1 value that fails to open stays null`() {
        // 12 byte IV + one 16 byte GCM tag block: shaped exactly like a seal, but no key can open
        // it here, so the real-ciphertext path must report "unreadable", never "plain text".
        val stored = "w1:" + Base64.encodeToString(ByteArray(12 + 16), Base64.NO_WRAP)

        assertTrue(crypto.isSealed(stored))
        assertNull(crypto.openOrNull(stored))
        try {
            crypto.open(stored)
            fail("open() must throw for ciphertext it cannot decrypt")
        } catch (expected: IllegalStateException) {
            // The editor/read path treats the throw as "leave the row untouched".
        }
    }
}
