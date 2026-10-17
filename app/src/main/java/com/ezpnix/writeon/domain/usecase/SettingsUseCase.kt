package com.ezpnix.writeon.domain.usecase

import android.util.Log
import com.ezpnix.writeon.core.constant.ConnectionConst
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.domain.model.Settings
import javax.inject.Inject

class SettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepositoryImpl,

    ) {

    private companion object {
        const val TAG = "SettingsUseCase"
    }

    suspend fun loadSettingsFromRepository(): Settings {
        return Settings().apply {
            Settings::class.java.declaredFields.forEach { field ->
                field.isAccessible = true
                val settingName = field.name
                val defaultValue = field.get(this)
                val settingValue = getSettingValue(field.type, settingName, defaultValue)
                field.set(this, settingValue)
            }
        }
    }

    private suspend fun getSettingValue(fieldType: Class<*>, settingName: String, defaultValue: Any?): Any? {
        return try {
            when (fieldType) {
                Boolean::class.java -> settingsRepository.getBoolean(settingName) ?: defaultValue
                String::class.java -> settingsRepository.getString(settingName) ?: defaultValue
                Int::class.java -> settingsRepository.getInt(settingName) ?: defaultValue
                Float::class.java -> settingsRepository.getFloat(settingName) ?: defaultValue
                else -> {
                    handleCorruptedPreference(
                        settingName,
                        IllegalArgumentException("Unsupported setting type: $fieldType")
                    )
                    defaultValue
                }
            }
        } catch (e: ClassCastException) {
            handleCorruptedPreference(settingName, e)
            defaultValue
        }
    }

    private fun handleCorruptedPreference(settingName: String, e: Exception) {
        // These used to be println, which wrote to stdout on release builds too — on Android that
        // lands in the system log, where any app with READ_LOGS (or an adb session) can read it. The
        // setting name is not user data, but the stack trace is noise that never needed to ship.
        Log.w(TAG, "Could not read setting '$settingName', using default. Contact support: ${ConnectionConst.SUPPORT_MAIL}", e)
    }

    suspend fun saveSettingsToRepository(settings: Settings) {
        Settings::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val settingName = field.name
            val settingValue = field.get(settings)
            saveSettingValue(settingName, settingValue)
        }
    }

    private suspend fun saveSettingValue(settingName: String, settingValue: Any?) {
        when (settingValue) {
            is Boolean -> settingsRepository.putBoolean(settingName, settingValue)
            is String -> settingsRepository.putString(settingName, settingValue)
            is Int -> settingsRepository.putInt(settingName, settingValue)
            is Float -> settingsRepository.putFloat(settingName, settingValue)
            // Throwing here used to abort the whole write, so one field the repository cannot store
            // silently dropped every setting that would have been saved after it. Skip and report.
            else -> handleCorruptedPreference(
                settingName,
                IllegalArgumentException("Unsupported setting type: ${settingValue?.javaClass}")
            )
        }
    }

}
