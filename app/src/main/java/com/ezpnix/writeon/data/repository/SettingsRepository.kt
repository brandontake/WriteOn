package com.ezpnix.writeon.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ezpnix.writeon.domain.repository.SettingsRepository
import com.ezpnix.writeon.widget.NotesWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "SettingsRepository"
private const val PREFERENCES_NAME = "settings updated"
private val TERMS_OF_SERVICE_KEY = booleanPreferencesKey("termsOfService")

/**
 * D11: a preferences file that fails its checksum (the process was killed mid-write) used to make
 * every read throw [androidx.datastore.core.CorruptionException] forever - SettingsViewModel.init
 * failed on first collection and the settings screen never recovered without clearing app data.
 * The corruption handler runs instead: it resets the store to [emptyPreferences] (every setting
 * falls back to its documented default) and records why. The handler is a factory parameter of
 * [preferencesDataStore], so it is bound before the singleton DataStore is ever constructed and
 * the one-DataStore-per-property semantics are unchanged.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
        Log.e(TAG, "Settings DataStore was corrupted; resetting to defaults", error)
        emptyPreferences()
    },
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, PREFERENCES_NAME)) }
)

class SettingsRepositoryImpl (private val context: Context) : SettingsRepository {

    /**
     * D11: one [edit] that cannot take the app down with it.
     *
     * `DataStore.edit` hits the disk: a full or read-only volume makes it throw
     * [java.io.IOException], which used to travel up through `saveSettingsToRepository` into an
     * unhandled `viewModelScope.launch` and crash the app - over a font size. The write is now
     * logged and dropped; the setting keeps its previous persisted value and the caller carries on.
     * Cancellation still propagates so structured shutdown is not swallowed by the catch.
     *
     * @return true when the write reached the store. R2-11: the Terms acceptance is the one caller
     *   that must not treat a failed write as success, because the next cold start would show the
     *   agreement again.
     */
    private suspend fun editPreferences(
        label: String,
        transform: suspend (MutablePreferences) -> Unit,
    ): Boolean = try {
        context.dataStore.edit(transform)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Could not persist '$label'; keeping the previous value", e)
        false
    }

    override suspend fun putString(key: String, value: String) {
        val preferencesKey = stringPreferencesKey(key)
        editPreferences(key) { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun putInt(key: String, value: Int) {
        val preferencesKey = intPreferencesKey(key)
        editPreferences(key) { preferences ->
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
        editPreferences(key) { preferences ->
            preferences[preferencesKey] = value
        }
    }

    override suspend fun getBoolean(key: String): Boolean? {
        val preferencesKey = booleanPreferencesKey(key)
        val preferences = context.dataStore.data.first()
        return preferences[preferencesKey]
    }

    /**
     * R2-11: reports whether the acceptance reached the store, so the caller can keep the user on
     * the Terms screen instead of navigating away from a flag that will be false on next launch.
     */
    suspend fun saveTermsOfService(accepted: Boolean): Boolean =
        editPreferences(TERMS_OF_SERVICE_KEY.name) { preferences ->
            preferences[TERMS_OF_SERVICE_KEY] = accepted
        }

    fun getTermsOfService(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[TERMS_OF_SERVICE_KEY] ?: false
        }
    }

    override suspend fun putFloat(key: String, value: Float) {
        val preferencesKey = floatPreferencesKey(key)
        editPreferences(key) { preferences ->
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
        editPreferences(NotesWidgetReceiver.WIDGET_PREFERENCE) { preferences ->
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