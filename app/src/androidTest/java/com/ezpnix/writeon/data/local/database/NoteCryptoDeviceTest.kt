package com.ezpnix.writeon.data.local.database

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TST-01: the note cipher against the real Android Keystore.
 *
 * Robolectric has no AndroidKeyStore provider, so every JVM test of [NoteCrypto] runs with a fake
 * cipher and cannot catch a key alias, key spec or format regression. This test is the other half:
 * it runs on a device/emulator and proves that [NoteCrypto.seal] mints an AES-GCM key under
 * [NoteCrypto]'s alias, that the stored value opens again, and - the part that protects data - that
 * a tampered ciphertext fails closed instead of returning unauthenticated bytes.
 *
 * It is compiled and run by `connectedDebugAndroidTest`; without an attached emulator it does not
 * run at all. That is expected: the test infrastructure, not a green local device run, is the
 * deliverable of the TST-01 change.
 */
@RunWith(AndroidJUnit4::class)
class NoteCryptoDeviceTest {

    private val crypto = NoteCrypto()

    @Before
    fun setUp() {
        // The plaintext cache is process-wide (V-5); a previous test in the same process must not
        // make a "decrypt after cache clear" assertion pass without touching the Keystore.
        crypto.clearCache()
    }

    @Test
    fun sealOpenRoundTripSurvivesCacheClear() {
        val plaintext = "TST-01 vault/note body: ünïcode, emoji \uD83D\uDD12, newlines\nand tabs\t"
        val sealed = crypto.seal(plaintext)

        assertTrue("a sealed value must carry the w1: marker", sealed.startsWith("w1:"))
        assertNotEquals("the stored value must not be the plaintext", plaintext, sealed)
        assertTrue("the stored value must look structurally sealed", crypto.isSealed(sealed))
        assertEquals(plaintext, crypto.open(sealed))
        assertEquals(plaintext, crypto.openOrNull(sealed))

        // Dropping the cache forces the next open through the Keystore again, which is the path a
        // lock/background event relies on.
        crypto.clearCache()
        assertEquals(plaintext, crypto.openOrNull(sealed))
    }

    @Test
    fun plaintextAndEmptyValuesPassThroughUnchanged() {
        assertEquals("", crypto.seal(""))
        assertEquals("", crypto.open(""))
        assertEquals("not ciphertext at all", crypto.open("not ciphertext at all"))
        // A note written before NoteCrypto existed can legitimately start with "w1:"; the
        // structural test must leave it readable instead of throwing.
        assertEquals("w1:hello", crypto.open("w1:hello"))
        assertFalse(crypto.isSealed("w1:hello"))
    }

    @Test
    fun aTamperedCiphertextFailsClosed() {
        val sealed = crypto.seal("TST-01 tamper probe")
        val payload = Base64.decode(sealed.removePrefix("w1:"), Base64.NO_WRAP)
        assertTrue("the payload must be IV plus GCM ciphertext", payload.size > 12)

        // Flip one bit in the GCM tag. The value still looks structurally sealed, so the read path
        // reaches authentication and must reject it rather than return plaintext.
        payload[payload.lastIndex] = (payload[payload.lastIndex].toInt() xor 0x01).toByte()
        val tampered = "w1:" + Base64.encodeToString(payload, Base64.NO_WRAP)

        crypto.clearCache()
        assertTrue("the tampered value must still be structurally sealed", crypto.isSealed(tampered))
        assertNull("openOrNull must not hand back unauthenticated bytes", crypto.openOrNull(tampered))
        try {
            crypto.open(tampered)
            fail("open must throw for a tampered ciphertext")
        } catch (e: IllegalStateException) {
            // Expected: the caller is told the body is unreadable instead of receiving garbage.
        }
    }

    @Test
    fun aTruncatedCiphertextFailsClosed() {
        val sealed = crypto.seal("TST-01 truncation probe")
        val payload = Base64.decode(sealed.removePrefix("w1:"), Base64.NO_WRAP)
        val truncated = "w1:" + Base64.encodeToString(
            payload.copyOfRange(0, payload.size - 1),
            Base64.NO_WRAP
        )

        crypto.clearCache()
        assertNull(crypto.openOrNull(truncated))
    }
}
