package com.ezpnix.writeon.domain.usecase

import com.ezpnix.writeon.domain.model.Settings
import com.ezpnix.writeon.domain.repository.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T9: the settings schema is now an explicit list instead of reflection.
 *
 * The test uses a non-default value for every field, saves through [SettingsUseCase], and reads it
 * back. A field added to `Settings` but forgotten in the explicit list fails here instead of
 * silently reverting to its default on the next launch.
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
            hideVisibilityButtonWhenKeyboard = false,
        )

        useCase.saveSettingsToRepository(settings)
        val loaded = useCase.loadSettingsFromRepository()

        assertEquals(settings, loaded)
    }

    private class InMemorySettingsRepository : SettingsRepository {
        private val values = mutableMapOf<String, Any>()

        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }

        override suspend fun getString(key: String): String? = values[key] as? String

        override suspend fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override suspend fun getInt(key: String): Int? = values[key] as? Int

        override suspend fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override suspend fun getBoolean(key: String): Boolean? = values[key] as? Boolean

        override suspend fun putFloat(key: String, value: Float) {
            values[key] = value
        }

        override suspend fun getFloat(key: String): Float? = values[key] as? Float

        override suspend fun getEveryNotesWidget(): List<Pair<Int, Int>> = emptyList()
    }
}
