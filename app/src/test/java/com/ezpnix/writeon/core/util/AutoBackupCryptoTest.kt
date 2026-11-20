package com.ezpnix.writeon.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
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
 * paths. The primitive/template and streaming close contracts are covered by
 * [AutoBackupStreamTest] with a generated keyset; the full round trip is exercised on-device by
 * the automatic-backup restore flow.
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
    fun `the wab2 and wab1 keysets are pinned to different templates`() {
        assertEquals(
            "V-1: WAB2 must be created with a streaming key type, or Tink cannot produce a " +
                "StreamingAead from it and every automatic backup fails",
            StreamingAeadKeyTemplates.AES256_GCM_HKDF_4KB.typeUrl,
            AutoBackupTemplates.streamingAead().typeUrl
        )
        assertEquals(
            "WAB1's already-created keyset keeps its original AEAD template",
            AeadKeyTemplates.AES256_GCM.typeUrl,
            AutoBackupTemplates.legacyAead().typeUrl
        )
        assertNotEquals(
            "the two formats must never share a template: AndroidKeysetManager reuses an " +
                "existing keyset, so one template change would strand the other format",
            AutoBackupTemplates.streamingAead().typeUrl,
            AutoBackupTemplates.legacyAead().typeUrl
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
