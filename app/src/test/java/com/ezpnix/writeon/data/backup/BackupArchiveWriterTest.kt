package com.ezpnix.writeon.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.attachment.AttachmentArchive
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.core.security.SharedPreferencesVaultKeyStore
import com.ezpnix.writeon.core.util.BackupArchiveCrypto
import com.ezpnix.writeon.domain.model.Attachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CQ-03: the shared archive layout, exercised end to end on the JVM.
 *
 * The writer's four common members are the contract every producer relies on: the portable
 * database, the `attachments/` tree, `flashcards.json` and `vault-key-salt`. The Robolectric context
 * provides SharedPreferences for the deck/salt and a real `filesDir/attachments` media root; the
 * attachment is plaintext, so [com.ezpnix.writeon.core.attachment.MediaVault] copies it without
 * touching the Android Keystore.
 *
 * The WebDAV producer passes the manifest through `beforeDatabaseEntry`; the second test pins that
 * the manifest really is first and that the four common members follow it in the unchanged order.
 */
@RunWith(RobolectricTestRunner::class)
class BackupArchiveWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workDir = File(context.cacheDir, "backup-archive-writer-${System.nanoTime()}")
    private val mediaRoot = File(context.filesDir, AttachmentArchive.DIRECTORY)

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        // Only the files this test created; the directory itself may hold other tests' fixtures if
        // Robolectric shares filesDir between classes, so it is not deleted recursively.
        attachmentFile.delete()
        context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE).edit()
            .remove("flashcards_key").commit()
        SharedPreferencesVaultKeyStore(context).clearSalt()
    }

    private val attachmentFile: File
        get() = File(mediaRoot, "tst-cq03-image.jpg")

    @Test
    fun `all four common members are written in the established order`() {
        seedFixture()

        val entries = writeArchive(manifest = false)
        val names = entries.map { it.first }

        assertEquals(
            "the shared writer owns this order: database, attachments, deck, salt",
            listOf(
                DatabaseConst.NOTES_DATABASE_FILE_NAME,
                "attachments/att-cq03.jpg",
                FlashcardArchive.ENTRY_NAME,
                VaultSaltArchive.ENTRY_NAME,
            ),
            names
        )

        val byName = entries.toMap()
        assertEquals(
            "portable database bytes",
            "portable db",
            String(byName.getValue(DatabaseConst.NOTES_DATABASE_FILE_NAME), Charsets.UTF_8)
        )
        assertArrayEquals(
            "the attachment member must be the decrypted/plain media bytes",
            ATTACHMENT_BYTES,
            byName.getValue("attachments/att-cq03.jpg")
        )
        assertEquals("[]", String(byName.getValue(FlashcardArchive.ENTRY_NAME), Charsets.UTF_8))
        assertTrue(
            "the vault salt member must be the encoded salt",
            byName.getValue(VaultSaltArchive.ENTRY_NAME).isNotEmpty()
        )
    }

    @Test
    fun `a before entry hook keeps the manifest first without changing the common members`() {
        seedFixture()

        val entries = writeArchive(manifest = true)
        val names = entries.map { it.first }

        assertEquals(
            "the WebDAV manifest must stay the first entry",
            listOf(
                BackupArchiveCrypto.MANIFEST_ENTRY_NAME,
                DatabaseConst.NOTES_DATABASE_FILE_NAME,
                "attachments/att-cq03.jpg",
                FlashcardArchive.ENTRY_NAME,
                VaultSaltArchive.ENTRY_NAME,
            ),
            names
        )

        val manifest = requireNotNull(
            BackupArchiveCrypto.parseManifest(String(entries.first().second, Charsets.UTF_8))
        ) { "the manifest must be parseable" }
        assertEquals(1, manifest.noteCount)
        assertEquals(1, manifest.attachmentCount)
    }

    @Test
    fun `a plaintext archive can omit the vault salt`() {
        // SEC-03: a plaintext export has no vault rows by policy, so the salt must not travel in
        // it. Encrypted producers keep the default (the salt is required to open `v3:` rows).
        seedFixture()

        val names = writeArchive(manifest = false, includeVaultSalt = false).map { it.first }

        assertEquals(
            listOf(
                DatabaseConst.NOTES_DATABASE_FILE_NAME,
                "attachments/att-cq03.jpg",
                FlashcardArchive.ENTRY_NAME,
            ),
            names
        )
    }

    private fun seedFixture() {
        workDir.mkdirs()
        mediaRoot.mkdirs()
        attachmentFile.writeBytes(ATTACHMENT_BYTES)
        context.getSharedPreferences("flashcards_prefs", Context.MODE_PRIVATE).edit()
            .putString("flashcards_key", "[]")
            .commit()
        assertTrue(
            "the vault salt fixture must be accepted",
            SharedPreferencesVaultKeyStore(context).setEncodedSalt(
                Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
            )
        )
    }

    /** Writes one archive and returns every entry as (name, bytes) in archive order. */
    private fun writeArchive(
        manifest: Boolean,
        includeVaultSalt: Boolean = true,
    ): List<Pair<String, ByteArray>> {
        val portable = File(workDir, "portable.db").apply { writeText("portable db") }
        val attachments = listOf(
            Attachment(
                id = "att-cq03",
                noteId = 1,
                type = Attachment.TYPE_IMAGE,
                path = attachmentFile.absolutePath,
                caption = "",
                createdAt = 1_000L,
            )
        )

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            BackupArchiveWriter.write(
                zip = zip,
                context = context,
                portableDatabase = portable,
                attachments = attachments,
                includeVaultSalt = includeVaultSalt,
                beforeDatabaseEntry = if (manifest) {
                    {
                        BackupArchiveCrypto.writeManifestEntry(
                            zip = zip,
                            noteCount = 1,
                            attachmentCount = 1,
                            createdAtMillis = 1_700_000_000_000L
                        )
                    }
                } else {
                    null
                },
            )
        }

        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += entry.name to zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private companion object {
        val ATTACHMENT_BYTES = "plain media bytes for the archive".toByteArray()
    }
}
