package com.ezpnix.writeon.domain.usecase

import android.util.Log
import com.ezpnix.writeon.core.constant.ConnectionConst
import com.ezpnix.writeon.domain.model.Settings
import com.ezpnix.writeon.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Reads and writes every setting by name.
 *
 * This used to walk `Settings::class.java.declaredFields` with `isAccessible = true`, which broke
 * as soon as the class gained a synthetic field (Kotlin adds one for `var` properties in some
 * configurations) and made a typo in a field name silently read the wrong key. The explicit list
 * below is the schema: adding a setting means adding it here, and the compiler checks the type.
 * T9.
 */
class SettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    private companion object {
        const val TAG = "SettingsUseCase"

        /**
         * Version of the persisted settings layout. It is stored next to the settings but is not a
         * [Settings] field, so a normal save (which writes every field) cannot reset it.
         */
        const val CURRENT_SETTINGS_SCHEMA = 1
        const val SETTINGS_SCHEMA_KEY = "settingsSchemaVersion"
    }

    suspend fun loadSettingsFromRepository(): Settings {
        val defaults = Settings()
        val settings = Settings(
            viewMode = readBoolean("viewMode", defaults.viewMode),
            automaticTheme = readBoolean("automaticTheme", defaults.automaticTheme),
            darkTheme = readBoolean("darkTheme", defaults.darkTheme),
            dynamicTheme = readBoolean("dynamicTheme", defaults.dynamicTheme),
            amoledTheme = readBoolean("amoledTheme", defaults.amoledTheme),
            minimalisticMode = readBoolean("minimalisticMode", defaults.minimalisticMode),
            extremeAmoledMode = readBoolean("extremeAmoledMode", defaults.extremeAmoledMode),
            isMarkdownEnabled = readBoolean("isMarkdownEnabled", defaults.isMarkdownEnabled),
            renderLatex = readBoolean("renderLatex", defaults.renderLatex),
            liveAttachmentPreview = readBoolean(
                "liveAttachmentPreview",
                defaults.liveAttachmentPreview
            ),
            screenProtection = readBoolean("screenProtection", defaults.screenProtection),
            encryptBackup = readBoolean("encryptBackup", defaults.encryptBackup),
            sortDescending = readBoolean("sortDescending", defaults.sortDescending),
            sortBy = readInt("sortBy", defaults.sortBy),
            vaultSettingEnabled = readBoolean("vaultSettingEnabled", defaults.vaultSettingEnabled),
            vaultEnabled = readBoolean("vaultEnabled", defaults.vaultEnabled),
            showOnlyTitle = readBoolean("showOnlyTitle", defaults.showOnlyTitle),
            termsOfService = readBoolean("termsOfService", defaults.termsOfService),
            cornerRadius = readInt("cornerRadius", defaults.cornerRadius),
            predictiveBackEnabled = readBoolean(
                "predictiveBackEnabled",
                defaults.predictiveBackEnabled
            ),
            isBiometricEnabled = readBoolean("isBiometricEnabled", defaults.isBiometricEnabled),
            autoBackupEnabled = readBoolean("autoBackupEnabled", defaults.autoBackupEnabled),
            fontSize = readFloat("fontSize", defaults.fontSize),
            columnsCount = readInt("columnsCount", defaults.columnsCount),
            hideVisibilityButtonWhenKeyboard = readBoolean(
                "hideVisibilityButtonWhenKeyboard",
                defaults.hideVisibilityButtonWhenKeyboard
            ),
        )
        return migrateStoredSettings(settings)
    }

    private suspend fun readBoolean(name: String, fallback: Boolean): Boolean = try {
        settingsRepository.getBoolean(name) ?: fallback
    } catch (e: ClassCastException) {
        handleCorruptedPreference(name, e)
        fallback
    }

    private suspend fun readInt(name: String, fallback: Int): Int = try {
        settingsRepository.getInt(name) ?: fallback
    } catch (e: ClassCastException) {
        handleCorruptedPreference(name, e)
        fallback
    }

    private suspend fun readFloat(name: String, fallback: Float): Float = try {
        settingsRepository.getFloat(name) ?: fallback
    } catch (e: ClassCastException) {
        handleCorruptedPreference(name, e)
        fallback
    }

    /**
     * One-time reinterpretation of values that older versions wrote with a different meaning.
     *
     * 2.0.1 defaulted `liveAttachmentPreview` to false, and [saveSettingsToRepository] writes every
     * field at once, so almost every 2.0.1 user ended up with an explicit `false` they never chose.
     * Raising the default in 2.0.2 could not help those installs. The first launch after this
     * migration treats a stored `false` as "never chosen" and turns the preview on once; from then
     * on the stored value is the user's real choice and is left alone.
     */
    private suspend fun migrateStoredSettings(settings: Settings): Settings {
        val schema = settingsRepository.getInt(SETTINGS_SCHEMA_KEY) ?: 0
        if (schema >= CURRENT_SETTINGS_SCHEMA) return settings

        if (!settings.liveAttachmentPreview) {
            settings.liveAttachmentPreview = true
            settingsRepository.putBoolean("liveAttachmentPreview", true)
        }
        settingsRepository.putInt(SETTINGS_SCHEMA_KEY, CURRENT_SETTINGS_SCHEMA)
        return settings
    }

    private fun handleCorruptedPreference(settingName: String, e: Exception) {
        // These used to be println, which wrote to stdout on release builds too - on Android that
        // lands in the system log, where any app with READ_LOGS (or an adb session) can read it. The
        // setting name is not user data, but the stack trace is noise that never needed to ship.
        Log.w(TAG, "Could not read setting '$settingName', using default. Contact support: ${ConnectionConst.SUPPORT_MAIL}", e)
    }

    suspend fun saveSettingsToRepository(settings: Settings) {
        settingsRepository.putBoolean("viewMode", settings.viewMode)
        settingsRepository.putBoolean("automaticTheme", settings.automaticTheme)
        settingsRepository.putBoolean("darkTheme", settings.darkTheme)
        settingsRepository.putBoolean("dynamicTheme", settings.dynamicTheme)
        settingsRepository.putBoolean("amoledTheme", settings.amoledTheme)
        settingsRepository.putBoolean("minimalisticMode", settings.minimalisticMode)
        settingsRepository.putBoolean("extremeAmoledMode", settings.extremeAmoledMode)
        settingsRepository.putBoolean("isMarkdownEnabled", settings.isMarkdownEnabled)
        settingsRepository.putBoolean("renderLatex", settings.renderLatex)
        settingsRepository.putBoolean("liveAttachmentPreview", settings.liveAttachmentPreview)
        settingsRepository.putBoolean("screenProtection", settings.screenProtection)
        settingsRepository.putBoolean("encryptBackup", settings.encryptBackup)
        settingsRepository.putBoolean("sortDescending", settings.sortDescending)
        settingsRepository.putInt("sortBy", settings.sortBy)
        settingsRepository.putBoolean("vaultSettingEnabled", settings.vaultSettingEnabled)
        settingsRepository.putBoolean("vaultEnabled", settings.vaultEnabled)
        settingsRepository.putBoolean("showOnlyTitle", settings.showOnlyTitle)
        settingsRepository.putBoolean("termsOfService", settings.termsOfService)
        settingsRepository.putInt("cornerRadius", settings.cornerRadius)
        settingsRepository.putBoolean("predictiveBackEnabled", settings.predictiveBackEnabled)
        settingsRepository.putBoolean("isBiometricEnabled", settings.isBiometricEnabled)
        settingsRepository.putBoolean("autoBackupEnabled", settings.autoBackupEnabled)
        settingsRepository.putFloat("fontSize", settings.fontSize)
        settingsRepository.putInt("columnsCount", settings.columnsCount)
        settingsRepository.putBoolean(
            "hideVisibilityButtonWhenKeyboard",
            settings.hideVisibilityButtonWhenKeyboard
        )
    }
}
