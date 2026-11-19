package com.ezpnix.writeon.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Format and rejection tests for the device-bound automatic-backup cipher (T7).
 *
 * A full encrypt/decrypt round trip cannot be a JVM test: the key lives in the Android Keystore
 * (`AndroidKeysetManager` + `android-keystore://writeon_auto_backup`), and the archive is
 * deliberately unreadable on another install. Robolectric has no real Keystore, so this suite
 * pins what is testable without one - the on-disk magic and the "reject instead of guessing"
 * paths. The round trip is exercised on-device by the automatic-backup restore flow.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBackupCryptoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `streaming and legacy magic values are frozen`() {
        assertEquals(
            "WAB2",
            AutoBackupCrypto.MAGIC_V2.toString(StandardCharsets.US_ASCII)
        )
        assertEquals(
            "WAB1",
            AutoBackupCrypto.MAGIC_V1.toString(StandardCharsets.US_ASCII)
        )
        assertNotEquals(
            "the current and legacy formats must stay distinguishable",
            AutoBackupCrypto.MAGIC_V1.toList(),
            AutoBackupCrypto.MAGIC_V2.toList()
        )
    }

    @Test
    fun `an empty archive is rejected without writing output`() {
        val output = ByteArrayOutputStream()

        val restored = AutoBackupCrypto.decryptTo(
            context,
            ByteArrayInputStream(ByteArray(0)),
            output
        )

        assertFalse("an empty file is not an automatic backup", restored)
        assertEquals("nothing may be restored from an empty file", 0, output.size())
    }

    @Test
    fun `a stream with a foreign header is rejected without writing output`() {
        val output = ByteArrayOutputStream()
        val foreign = "NOT-A-WRITEON-ARCHIVE".toByteArray(StandardCharsets.UTF_8)

        val restored = AutoBackupCrypto.decryptTo(
            context,
            ByteArrayInputStream(foreign),
            output
        )

        assertFalse("a foreign file must never be treated as a WAB1/WAB2 archive", restored)
        assertEquals("a rejected archive must not leak bytes into the output", 0, output.size())
    }
}
