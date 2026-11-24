package com.ezpnix.writeon.domain.usecase

import com.ezpnix.writeon.domain.model.Settings
import com.ezpnix.writeon.domain.repository.SettingsRepository
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * T9/TST-03: the settings schema is an explicit list instead of reflection.
 *
 * The round-trip test alone is not enough: it constructs [Settings] explicitly and a newly added
 * field with a default value compares equal whether or not [SettingsUseCase] persists it. The three
 * schema tests below close that gap:
 * - every [Settings] member must be named in [PERSISTED_SETTING_KEYS] (or the documented exclusion
 *   set), so a new field cannot be forgotten;
 * - the save path must write exactly that set, so a key added to the set but not to the writer is
 *   caught;
 * - the load path must read exactly that set, so a key that is written but never read is caught.
 */
class SettingsUseCaseTest {

    @Test
    fun `every setting round trips through the explicit schema`() = runBlocking {
        val repository = InMemorySettingsRepository()
        val useCase = SettingsUseCase(repository)
        val settings = Settings(
            viewMode = true,
            automaticTheme = false,
            darkTheme = true,
            dynamicTheme = true,
            amoledTheme = true,
            minimalisticMode = true,
            extremeAmoledMode = true,
            isMarkdownEnabled = false,
            renderLatex = true,
            liveAttachmentPreview = true,
            screenProtection = true,
            encryptBackup = true,
            sortDescending = false,
            sortBy = 2,
            vaultSettingEnabled = true,
            vaultEnabled = true,
            showOnlyTitle = true,
            termsOfService = true,
            cornerRadius = 11,
            predictiveBackEnabled = true,
            isBiometricEnabled = true,
            autoBackupEnabled = true,
            fontSize = 21.5f,
            columnsCount = 4,
        )

        useCase.saveSettingsToRepository(settings)
        // termsOfService is owned by SettingsRepository.saveTermsOfService, not the bulk writer.
        repository.putBoolean("termsOfService", true)
        val loaded = useCase.loadSettingsFromRepository()

        assertEquals(settings, loaded)
    }

    @Test
    fun `every Settings member is in the persisted schema or an explicit exclusion`() {
        val members = Settings::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        val unaccounted = members - PERSISTED_SETTING_KEYS - SETTINGS_EXCLUDED_KEYS
        assertTrue(
            "Settings members missing from PERSISTED_SETTING_KEYS (add the key to SettingsUseCase's " +
                "read and write paths, or document it in SETTINGS_EXCLUDED_KEYS): $unaccounted",
            unaccounted.isEmpty()
        )

        val stale = PERSISTED_SETTING_KEYS - members
        assertTrue(
            "PERSISTED_SETTING_KEYS names members Settings no longer has: $stale",
            stale.isEmpty()
        )
    }

    @Test
    fun `the save path writes exactly the persisted schema`() = runBlocking {
        val repository = InMemorySettingsRepository()
        val useCase = SettingsUseCase(repository)

        useCase.saveSettingsToRepository(Settings())

        assertEquals(
            "every key in the schema must actually be written; a missing key silently keeps the " +
                "old value on every save",
            PERSISTED_SETTING_KEYS,
            repository.writtenKeys
        )
    }

    @Test
    fun `the load path reads exactly the persisted schema`() = runBlocking {
        val repository = InMemorySettingsRepository()
        val useCase = SettingsUseCase(repository)

        useCase.loadSettingsFromRepository()

        // The schema version is deliberately not a Settings field; migrateStoredSettings reads it
        // to decide whether the one-time reinterpretation already ran.
        val settingsReads = repository.readKeys - "settingsSchemaVersion"
        assertEquals(
            "every key in the schema must actually be read; a missing read silently reverts the " +
                "setting to its default on every launch",
            PERSISTED_SETTING_KEYS + SEPARATELY_MANAGED_SETTING_KEYS,
            settingsReads
        )
    }

    @Test
    fun `a bulk settings save cannot revoke terms acceptance`() = runBlocking {
        val repository = InMemorySettingsRepository()
        val useCase = SettingsUseCase(repository)

        repository.putBoolean("termsOfService", true)
        useCase.saveSettingsToRepository(Settings(termsOfService = false))

        assertEquals(
            "an unrelated settings save must not write the stale in-memory terms flag",
            true,
            repository.getBoolean("termsOfService")
        )
    }

    private class InMemorySettingsRepository : SettingsRepository {
        private val values = mutableMapOf<String, Any>()

        /** Keys the use case wrote, for the "save uses the declared schema" test. */
        val writtenKeys = linkedSetOf<String>()

        /** Keys the use case read, for the "load uses the declared schema" test. */
        val readKeys = linkedSetOf<String>()

        override suspend fun putString(key: String, value: String) {
            writtenKeys += key
            values[key] = value
        }

        override suspend fun getString(key: String): String? {
            readKeys += key
            return values[key] as? String
        }

        override suspend fun putInt(key: String, value: Int) {
            writtenKeys += key
            values[key] = value
        }

        override suspend fun getInt(key: String): Int? {
            readKeys += key
            return values[key] as? Int
        }

        override suspend fun putBoolean(key: String, value: Boolean) {
            writtenKeys += key
            values[key] = value
        }

        override suspend fun getBoolean(key: String): Boolean? {
            readKeys += key
            return values[key] as? Boolean
        }

        override suspend fun putFloat(key: String, value: Float) {
            writtenKeys += key
            values[key] = value
        }

        override suspend fun getFloat(key: String): Float? {
            readKeys += key
            return values[key] as? Float
        }

        override suspend fun getEveryNotesWidget(): List<Pair<Int, Int>> = emptyList()
    }
}
