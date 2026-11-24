package com.ezpnix.writeon.data.backup

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.data.local.database.NoteDatabase
import com.ezpnix.writeon.data.local.database.NoteDatabaseProvider
import com.ezpnix.writeon.data.repository.BackupResult
import com.ezpnix.writeon.data.repository.ImportExportRepository
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.model.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-03: encryption of the local database backup is mandatory for new installs and for any
 * library with vault content.
 *
 * The policy is a pure OR, but the inputs are what the audit found wrong: the default was `false`
 * and nothing inspected the library. These tests pin the new default, the truth table, the SQL
 * probe against a real (in-memory) database, and the repository's refusal path - a passwordless
 * export of a vault library must return the typed `passwordRequired` error before the destination
 * document is touched.
 */
@RunWith(RobolectricTestRunner::class)
class BackupEncryptionPolicyTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var provider: NoteDatabaseProvider? = null

    @After
    fun tearDown() {
        provider?.close()
        provider = null
        context.deleteDatabase(DatabaseConst.NOTES_DATABASE_FILE_NAME)
    }

    @Test
    fun `new installs default to encrypted backups`() {
        assertTrue(
            "SEC-03: a fresh Settings() must ask for an encrypted database backup",
            Settings().encryptBackup
        )
    }

    @Test
    fun `encryption is required by the switch or by vault content`() {
        assertFalse(
            "plaintext is allowed only when the user turned the switch off and no vault row exists",
            BackupEncryptionPolicy.required(encryptBackupSetting = false, hasVaultContent = false)
        )
        assertTrue(BackupEncryptionPolicy.required(encryptBackupSetting = true, hasVaultContent = false))
        assertTrue(BackupEncryptionPolicy.required(encryptBackupSetting = false, hasVaultContent = true))
        assertTrue(BackupEncryptionPolicy.required(encryptBackupSetting = true, hasVaultContent = true))
    }

    @Test
    fun `vault content is detected from the notes table`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            database.noteDao().addNote(Note(name = "plain", description = "body", encrypted = false))
            assertFalse(
                "plain notes must not force encryption",
                BackupEncryptionPolicy.hasVaultContent(database)
            )

            database.noteDao().addNote(
                Note(name = "vault", description = "v2:sealed", encrypted = true)
            )
            assertTrue(
                "one encrypted=1 row must force encryption for the whole archive",
                BackupEncryptionPolicy.hasVaultContent(database)
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `a passwordless export of a vault library is refused with the typed signal`() = runBlocking {
        provider = NoteDatabaseProvider(context)
        provider!!.close()
        context.deleteDatabase(DatabaseConst.NOTES_DATABASE_FILE_NAME)

        // A vault row is enough; the stored encryptBackup switch is irrelevant to the refusal.
        provider!!.instance().noteDao().addNote(
            Note(name = "vault", description = "v2:sealed", encrypted = true)
        )

        val destination = File(context.cacheDir, "sec03-typed-error-${System.nanoTime()}.zip")
        val repository = ImportExportRepository(
            provider = provider!!,
            context = context,
            mutex = Mutex(),
            dispatcher = Dispatchers.IO,
        )

        val result = repository.exportBackup(Uri.fromFile(destination), password = null)

        assertTrue("the export must fail instead of writing a plaintext archive", result is BackupResult.Error)
        assertTrue(
            "the error must be the typed password signal, not a generic failure",
            (result as BackupResult.Error).passwordRequired
        )
        assertFalse(
            "the destination must not be created for a refused export",
            destination.exists()
        )
    }
}
