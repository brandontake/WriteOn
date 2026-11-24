package com.ezpnix.writeon.data.backup

import android.content.Context
import com.ezpnix.writeon.data.local.database.BackupDatabase
import com.ezpnix.writeon.data.local.database.NoteDatabase
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.domain.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SEC-03: one rule for "must this database backup be password-encrypted?".
 *
 * The local "Database Backup" used to write a plaintext zip unless the user had found the
 * `encryptBackup` switch. That zip carries the portable database (readable note bodies), the
 * decrypted attachment tree - including the media of vault notes, because
 * [com.ezpnix.writeon.core.attachment.AttachmentArchive] writes portable bytes - and the
 * `vault-key-salt`. Leaving it unencrypted contradicts the app's privacy premise, so:
 *
 *  - new installs default `encryptBackup` to `true` (see `Settings`), while a stored preference
 *    from an older install still wins, so nobody's deliberate choice is silently flipped;
 *  - a library with at least one `encrypted = 1` note always forces encryption. Attachments follow
 *    their note, so there is no separate attachment flag to inspect;
 *  - a plaintext archive therefore remains possible only when the user explicitly turned
 *    encryption off **and** the library holds no vault rows.
 *
 * The decision is split into a pure function ([required]) and the two reads the callers need, so
 * the Cloud screen can decide whether to prompt before it starts the SAF picker and the repository
 * can enforce the same rule at export time.
 */
object BackupEncryptionPolicy {

    /** The persisted key `SettingsUseCase` uses for the `encryptBackup` switch. */
    const val ENCRYPT_BACKUP_SETTING = "encryptBackup"

    /**
     * True when the archive must be password-encrypted.
     *
     * Vault content wins over the user's switch: turning `encryptBackup` off is a choice about the
     * user's own plain notes, not a licence to write decrypted vault media and the vault salt to
     * disk in the clear.
     */
    fun required(encryptBackupSetting: Boolean, hasVaultContent: Boolean): Boolean =
        encryptBackupSetting || hasVaultContent

    /**
     * The stored switch, falling back to the current [Settings] default (true for new installs).
     *
     * Read through [SettingsRepositoryImpl] rather than a constructor dependency so the repository
     * and the settings screen share the one DataStore instance (a second `preferencesDataStore`
     * over the same file is illegal).
     */
    suspend fun encryptBackupSetting(context: Context): Boolean =
        SettingsRepositoryImpl(context).getBoolean(ENCRYPT_BACKUP_SETTING)
            ?: Settings().encryptBackup

    /**
     * Whether any note row is a vault note (`encrypted = 1`). Queried at export time, so a vault
     * note created after the settings screen opened still forces the archive to be encrypted.
     */
    suspend fun hasVaultContent(context: Context): Boolean = withContext(Dispatchers.IO) {
        hasVaultContent(BackupDatabase.provider(context).instance())
    }

    /**
     * The SQL half, kept on [NoteDatabase] so the policy can be tested with an in-memory database.
     *
     * `EXISTS` stops at the first vault row instead of counting the library; the table name and
     * column come from the entity, and the query runs through the open helper so it sees exactly
     * the database the export is about to copy.
     */
    internal fun hasVaultContent(database: NoteDatabase): Boolean =
        database.openHelper.readableDatabase.query(
            "SELECT EXISTS(SELECT 1 FROM `notes-table` WHERE encrypted = 1)"
        ).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        }
}
