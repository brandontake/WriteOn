package com.ezpnix.writeon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ezpnix.writeon.domain.repository.SettingsRepository
import com.ezpnix.writeon.widget.NotesWidgetReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val PREFERENCES_NAME = "settings updated"
private val TERMS_OF_SERVICE_KEY = booleanPreferencesKey("termsOfService")
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_NAME,
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, PREFERENCES_NAME)) }
)

class SettingsRepositoryImpl (private val context: Context) : SettingsRepository {
    override suspend fun putString(key: String, value: String) {
        val preferencesKey = stringPreferencesKey(key)
        context.dataStore.edit { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun putInt(key: String, value: Int) {
        val preferencesKey = intPreferencesKey(key)
        context.dataStore.edit { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun getString(key: String): String? {
        val preferencesKey = stringPreferencesKey(key)
        val preferences = context.dataStore.data.first()
        return preferences[preferencesKey]
    }

    override suspend fun getInt(key: String): Int? {
        val preferencesKey = intPreferencesKey(key)
        val preferences = context.dataStore.data.first()
        return preferences[preferencesKey]
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        val preferencesKey = booleanPreferencesKey(key)
        context.dataStore.edit { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun getBoolean(key: String): Boolean? {
        val preferencesKey = booleanPreferencesKey(key)
        val preferences = context.dataStore.data.first()
        return preferences[preferencesKey]
    }

    suspend fun saveTermsOfService(accepted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TERMS_OF_SERVICE_KEY] = accepted
        }
    }

    fun getTermsOfService(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[TERMS_OF_SERVICE_KEY] ?: false
        }
    }

    override suspend fun putFloat(key: String, value: Float) {
        val preferencesKey = floatPreferencesKey(key)
        context.dataStore.edit { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun getFloat(key: String): Float? {
        val preferencesKey = floatPreferencesKey(key)
        val preferences = context.dataStore.data.first()
        return preferences[preferencesKey]
    }

    fun observeEveryNotesWidget(): Flow<List<Pair<Int, Int>>> =
        context.dataStore.data.map { it.widgetNotes() }

    override suspend fun getEveryNotesWidget(): List<Pair<Int, Int>> {
        val preferences = context.dataStore.data.first()
        return preferences.widgetNotes()
    }

    /** Applies a widget mapping change inside the settings DataStore transaction. */
    suspend fun updateEveryNotesWidget(
        transform: suspend (List<Pair<Int, Int>>) -> List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> {
        var updated = emptyList<Pair<Int, Int>>()
        context.dataStore.edit { preferences ->
            updated = transform(preferences.widgetNotes())
            preferences.asMap().keys
                .filter { it.name.startsWith(NotesWidgetReceiver.WIDGET_PREFERENCE) }
                .forEach { preferences.remove(it) }
            updated.forEach { (widgetId, noteId) ->
                preferences[intPreferencesKey("${NotesWidgetReceiver.WIDGET_PREFERENCE}$widgetId")] = noteId
            }
        }
        return updated
    }

    private fun Preferences.widgetNotes(): List<Pair<Int, Int>> = asMap()
        .mapNotNull { (key, value) ->
            if (!key.name.startsWith(NotesWidgetReceiver.WIDGET_PREFERENCE)) return@mapNotNull null
            val widgetId = key.name.substringAfter(NotesWidgetReceiver.WIDGET_PREFERENCE).toIntOrNull()
                ?: return@mapNotNull null
            (value as? Int)?.let { widgetId to it }
        }
}