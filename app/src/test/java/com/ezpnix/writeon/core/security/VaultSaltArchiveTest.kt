package com.ezpnix.writeon.core.security

import com.ezpnix.writeon.data.backup.VaultSaltArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * PERF-2: the archive half of the envelope salt.
 *
 * Plain JVM tests - [InMemoryVaultKeyStore] and the [VaultSaltArchive] zip helpers need neither an
 * Android Context nor a Keystore. What still needs a device is the SharedPreferences variant's
 * `commit()` behaviour; the decode/validation policy exercised here is the same one it uses.
 */
class VaultSaltArchiveTest {

    @Test
    fun `an encoded salt round-trips through the in-memory store`() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val encoded = Base64.getEncoder().encodeToString(salt)
        val store = InMemoryVaultKeyStore()

        assertTrue(store.setEncodedSalt(encoded))

        assertArrayEquals(salt, store.salt()!!)
        assertEquals(encoded, store.encodedSalt())
    }

    @Test
    fun `a malformed or too-short encoded salt is rejected and leaves the current salt`() {
        val original = ByteArray(16) { it.toByte() }
        val store = InMemoryVaultKeyStore(original)

        // Not Base64 at all.
        assertFalse(store.setEncodedSalt("not-base64!!"))
        assertArrayEquals(original, store.salt()!!)

        // Decodable, but shorter than any real KDF salt: a truncated prefix must not replace the
        // only copy of the current KEK's salt.
        assertFalse(store.setEncodedSalt(Base64.getEncoder().encodeToString(ByteArray(4))))
        assertArrayEquals(original, store.salt()!!)
    }

    @Test
    fun `the zip entry carries the salt and restores into a fresh store`() {
        val salt = ByteArray(16) { (it * 3).toByte() }
        val encoded = Base64.getEncoder().encodeToString(salt)
        val zipBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                VaultSaltArchive.writeSaltEntry(zip, encoded)
            }
        }.toByteArray()

        val entryText = ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val entry = zip.nextEntry
            assertEquals(VaultSaltArchive.ENTRY_NAME, entry.name)
            val bytes = ByteArrayOutputStream()
            zip.copyTo(bytes)
            bytes.toString(Charsets.UTF_8.name())
        }
        assertEquals(encoded, entryText)

        val staging = Files.createTempDirectory("vault-salt-zip").toFile()
        try {
            File(staging, VaultSaltArchive.ENTRY_NAME).writeText(entryText, Charsets.UTF_8)
            val store = InMemoryVaultKeyStore()

            assertTrue(VaultSaltArchive.restoreFromStaging(staging, store))
            assertArrayEquals(salt, store.salt()!!)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `a malformed archive entry is rejected and leaves the current salt`() {
        val original = ByteArray(16) { it.toByte() }
        val store = InMemoryVaultKeyStore(original)
        val staging = Files.createTempDirectory("vault-salt-bad").toFile()

        try {
            File(staging, VaultSaltArchive.ENTRY_NAME).writeText("not-base64!!", Charsets.UTF_8)
            assertFalse(VaultSaltArchive.restoreFromStaging(staging, store))
            assertArrayEquals(original, store.salt()!!)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `a missing archive entry leaves the current salt alone`() {
        val original = ByteArray(16) { it.toByte() }
        val store = InMemoryVaultKeyStore(original)
        val staging = Files.createTempDirectory("vault-salt-missing").toFile()

        try {
            assertFalse(VaultSaltArchive.restoreFromStaging(staging, store))
            assertArrayEquals(original, store.salt()!!)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `rollback restores the previous salt and clears when there was none`() {
        val archiveSalt = Base64.getEncoder().encodeToString(ByteArray(16) { 9.toByte() })
        val previous = Base64.getEncoder().encodeToString(ByteArray(16) { 1.toByte() })
        val store = InMemoryVaultKeyStore()
        assertTrue(store.setEncodedSalt(archiveSalt))

        VaultSaltArchive.restoreEncoded(store, previous)
        assertEquals(previous, store.encodedSalt())

        // The database before the restore had never written a salt: leaving the archive's value
        // behind would make its plaintext rows look envelope-sealed to the next session.
        VaultSaltArchive.restoreEncoded(store, null)
        assertNull(store.salt())
    }
}
