package com.ezpnix.writeon.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The archive format is a compatibility promise: every 2.0.2 backup ever written must still
 * restore, or an upgrade silently destroys the user's only copy of their notes. These tests pin
 * both directions - the current `WBK2` layout round-trips, and a legacy CBC archive produced
 * exactly the way 2.0.2 produced it still decrypts.
 */
class BackupArchiveCryptoTest {

    private val password = "correct horse battery staple"

    @Test
    fun `current format round trips`() {
        val payload = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }

        val encrypted = BackupArchiveCrypto.encrypt(payload, password)

        assertTrue(
            "new archives must carry the WBK2 magic",
            encrypted.copyOfRange(0, 4).contentEquals("WBK2".toByteArray())
        )
        val decrypted = BackupArchiveCrypto.decrypt(encrypted, password)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun `streaming format round trips through the stream APIs`() {
        val payload = ByteArray(300 * 1024).also { SecureRandom().nextBytes(it) }

        val cipherBytes = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.encryptStream(ByteArrayInputStream(payload), out, password)
        }.toByteArray()

        assertTrue(cipherBytes.copyOfRange(0, 4).contentEquals("WBK2".toByteArray()))

        val plain = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.decryptStream(ByteArrayInputStream(cipherBytes), password)
                .use { it.copyTo(out) }
        }.toByteArray()
        assertArrayEquals(payload, plain)
    }

    @Test
    fun `streaming decrypt accepts a legacy CBC archive`() {
        // The exact layout 2.0.2 wrote: salt(16) || iv(16) || AES-CBC ciphertext, PBKDF2 at 65,536.
        val payload = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val legacy = encryptLegacyCbc(payload, password)

        assertFalse(legacy.copyOfRange(0, 4).contentEquals("WBK2".toByteArray()))

        val plain = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.decryptStream(ByteArrayInputStream(legacy), password)
                .use { it.copyTo(out) }
        }.toByteArray()
        assertArrayEquals(payload, plain)
    }

    @Test
    fun `decrypt accepts a legacy CBC archive`() {
        val payload = "plain text body of a 2.0.2 backup".toByteArray()
        val legacy = encryptLegacyCbc(payload, password)

        val plain = BackupArchiveCrypto.decrypt(legacy, password)
        assertArrayEquals(payload, plain)
    }

    @Test
    fun `wrong password fails instead of returning garbage`() {
        val payload = "secret notes".toByteArray()
        val encrypted = BackupArchiveCrypto.encrypt(payload, password)

        val failure = runCatching { BackupArchiveCrypto.decrypt(encrypted, "not the password") }.exceptionOrNull()
        assertTrue(
            "a wrong passphrase must surface as a crypto failure, not as data",
            failure is javax.crypto.BadPaddingException ||
                failure is java.security.GeneralSecurityException
        )
    }

    @Test
    fun `streaming decrypt of a truncated archive fails without trusting partial plaintext`() {
        val payload = ByteArray(8192).also { SecureRandom().nextBytes(it) }
        val cipherBytes = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.encryptStream(ByteArrayInputStream(payload), out, password)
        }.toByteArray()
        val truncated = cipherBytes.copyOf(cipherBytes.size - 16) // strips the GCM tag

        val out = ByteArrayOutputStream()
        val failure = runCatching {
            BackupArchiveCrypto.decryptStream(ByteArrayInputStream(truncated), password).use { it.copyTo(out) }
        }.exceptionOrNull()

        // A truncated GCM archive has no valid tag, so the read must abort with an exception; it
        // must never hand back "most of the note" and let the caller restore it. (The old assertion
        // accepted a partial write, which is exactly the silent-data-loss case this guards.)
        assertNotNull("a truncated archive must abort the read with an exception", failure)
        assertEquals("no plaintext from a truncated archive may be trusted", 0, out.size())
    }

    @Test
    fun `streaming decrypt of a tampered ciphertext byte fails`() {
        val payload = ByteArray(4096).also { SecureRandom().nextBytes(it) }
        val cipherBytes = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.encryptStream(ByteArrayInputStream(payload), out, password)
        }.toByteArray()
        // Flip one bit inside the ciphertext body (past the header, before the trailing tag).
        val tampered = cipherBytes.copyOf()
        val bodyIndex = 4 + 16 + 12 // magic + salt + iv
        tampered[bodyIndex] = (tampered[bodyIndex].toInt() xor 0x01).toByte()

        val out = ByteArrayOutputStream()
        val failure = runCatching {
            BackupArchiveCrypto.decryptStream(ByteArrayInputStream(tampered), password).use { it.copyTo(out) }
        }.exceptionOrNull()
        assertNotNull("a tampered archive must fail the GCM tag check", failure)
        assertEquals("no tampered plaintext may be trusted", 0, out.size())
    }

    @Test
    fun `decrypting an empty file fails instead of returning an empty note set`() {
        val failure = runCatching { BackupArchiveCrypto.decrypt(ByteArray(0), password) }.exceptionOrNull()
        assertNotNull("an empty archive is not a valid backup", failure)
    }

    @Test
    fun `WBK2 header layout and KDF cost are frozen`() {
        val plaintext = byteArrayOf(0x01, 0x02, 0x03)
        val encrypted = BackupArchiveCrypto.encrypt(plaintext, password)

        // magic(4) || salt(16) || GCM IV(12) || ciphertext || GCM tag(16)
        assertEquals(
            "WBK2",
            encrypted.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII)
        )
        assertEquals(4 + 16 + 12 + plaintext.size + 16, encrypted.size)
        assertEquals(120_000, BackupArchiveCrypto.PBKDF2_ITERATIONS)
        assertEquals("backup-manifest.txt", BackupArchiveCrypto.MANIFEST_ENTRY_NAME)
        assertEquals(1, BackupArchiveCrypto.MANIFEST_FORMAT)
    }

    @Test
    fun `manifest entry name and text format are frozen`() {
        val createdAt = 1_700_000_000_000L
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                BackupArchiveCrypto.writeManifestEntry(
                    zip = zip,
                    noteCount = 3,
                    attachmentCount = 2,
                    createdAtMillis = createdAt
                )
            }
        }.toByteArray()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry
            assertEquals(BackupArchiveCrypto.MANIFEST_ENTRY_NAME, entry?.name)
            assertEquals(
                "writeon-backup-manifest\n" +
                    "format=1\n" +
                    "createdAt=1700000000000\n" +
                    "notes=3\n" +
                    "attachments=2\n",
                zip.readBytes().toString(StandardCharsets.UTF_8)
            )
        }
    }

    @Test
    fun `a known small plaintext round-trips with its manifest`() {
        val plaintext = "WriteOn frozen-vector backup\n".toByteArray(StandardCharsets.UTF_8)
        val createdAt = 1_761_872_400_000L
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                BackupArchiveCrypto.writeManifestEntry(
                    zip = zip,
                    noteCount = 1,
                    attachmentCount = 0,
                    createdAtMillis = createdAt
                )
                zip.putNextEntry(ZipEntry("note-list.db"))
                zip.write(plaintext)
                zip.closeEntry()
            }
        }.toByteArray()

        val encrypted = BackupArchiveCrypto.encrypt(zipBytes, password)
        ZipInputStream(
            BackupArchiveCrypto.decryptStream(ByteArrayInputStream(encrypted), password)
        ).use { zip ->
            val manifest = requireNotNull(BackupArchiveCrypto.readManifest(zip)) {
                "the frozen archive must carry its manifest as the first entry"
            }
            assertEquals(BackupArchiveCrypto.MANIFEST_FORMAT, manifest.format)
            assertEquals(createdAt, manifest.createdAtMillis)
            assertEquals(1, manifest.noteCount)
            assertEquals(0, manifest.attachmentCount)

            val databaseEntry = zip.nextEntry
            assertEquals("note-list.db", databaseEntry?.name)
            assertArrayEquals(plaintext, zip.readBytes())
        }
    }

    @Test
    fun `manifest is the first entry and survives the encrypted round trip`() {
        val createdAt = 1_761_872_400_000L
        val dbBytes = "sqlite database bytes".toByteArray()

        // Exactly the layout CloudBackupWorker.writeEncryptedArchive produces (W6).
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                BackupArchiveCrypto.writeManifestEntry(
                    zip = zip,
                    noteCount = 42,
                    attachmentCount = 7,
                    createdAtMillis = createdAt
                )
                zip.putNextEntry(ZipEntry("note-list.db"))
                zip.write(dbBytes)
                zip.closeEntry()
            }
        }.toByteArray()

        val cipherBytes = ByteArrayOutputStream().also { out ->
            BackupArchiveCrypto.encryptStream(ByteArrayInputStream(zipBytes), out, password)
        }.toByteArray()

        ZipInputStream(BackupArchiveCrypto.decryptStream(ByteArrayInputStream(cipherBytes), password)).use { zip ->
            val parsed = requireNotNull(BackupArchiveCrypto.readManifest(zip)) {
                "a fresh archive must carry a manifest"
            }
            assertEquals(BackupArchiveCrypto.MANIFEST_FORMAT, parsed.format)
            assertEquals(createdAt, parsed.createdAtMillis)
            assertEquals(42, parsed.noteCount)
            assertEquals(7, parsed.attachmentCount)

            // The database must still follow the manifest as the very next entry.
            val entry = zip.nextEntry
            assertEquals("note-list.db", entry?.name)
            assertArrayEquals(dbBytes, zip.readBytes())
        }
    }

    @Test
    fun `archive without a manifest is recognized as legacy and still starts with the database`() {
        // The exact layout every pre-W6 build wrote: the database is the first entry.
        val dbBytes = "legacy database bytes".toByteArray()
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("note-list.db"))
                zip.write(dbBytes)
                zip.closeEntry()
            }
        }.toByteArray()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            assertNull(
                "a pre-manifest archive has no manifest - legal, age unknown",
                BackupArchiveCrypto.readManifest(zip)
            )
            // readManifest opened the first entry without consuming it: the extractor must
            // still see the database there. This is the hard compatibility promise for old
            // backups - they must keep restoring after the manifest change.
            assertArrayEquals(dbBytes, zip.readBytes())
        }
    }

    @Test
    fun `text that is not our manifest parses as no manifest instead of failing`() {
        assertNull(BackupArchiveCrypto.parseManifest("some random text"))
        assertNull(BackupArchiveCrypto.parseManifest(""))
        // Right header, missing field: "no usable manifest" rather than an error.
        assertNull(
            BackupArchiveCrypto.parseManifest(
                "writeon-backup-manifest\nformat=1\ncreatedAt=123\n"
            )
        )
        // Unknown extra keys are ignored so a future format stays readable by this build.
        val parsed = requireNotNull(
            BackupArchiveCrypto.parseManifest(
                "writeon-backup-manifest\nformat=1\ncreatedAt=123\nnotes=5\nattachments=0\nfutureField=x\n"
            )
        ) { "a complete manifest must parse" }
        assertEquals(123L, parsed.createdAtMillis)
        assertEquals(5, parsed.noteCount)
    }

    /** Reproduces the 2.0.2 writer byte for byte: salt||iv||CBC, PBKDF2-HMAC-SHA256 at 65,536. */
    private fun encryptLegacyCbc(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return salt + iv + cipher.doFinal(data)
    }
}
