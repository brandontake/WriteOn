package com.ezpnix.writeon.presentation.screens.settings.model

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import com.ezpnix.writeon.R

/**
 * The values below (column count, home subtitle, quick-action selection) use their own DataStore
 * file, which is why they need their own migration: without it a value written by a build that still
 * used SharedPreferences would be invisible and the user's choice would silently fall back to the
 * default on every launch. Migrating is a no-op when no such file exists.
 */
private val Context.dataStore by preferencesDataStore(
    name = "user_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "user_settings"))
    }
)

class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val PLACEHOLDER_KEY = stringPreferencesKey("search_placeholder")
        private val COLUMNS_COUNT_KEY = intPreferencesKey("columns_count")
        private val VISIBLE_FAB_ITEMS_KEY = stringSetPreferencesKey("visible_fab_items")
        private const val DEFAULT_COLUMNS_COUNT = 2

        /**
         * CQ-06: every locale the app actually publishes, derived from the same list as
         * `res/xml/locales_config.xml` and the `resourceConfigurations` in `build.gradle.kts`.
         *
         * The legacy quick-action migration has to know all of them: a label written by an older
         * build in a locale that is missing here would not map back to its id and the button would
         * silently disappear on upgrade. Japanese was exactly that gap.
         */
        internal val PUBLISHED_LOCALES = listOf(
            Locale.ENGLISH,
            Locale.SIMPLIFIED_CHINESE,
            Locale.JAPANESE,
        )

        /**
         * Stable ids of the home quick actions, paired with the label each one shows. The ids - not
         * the labels - are what gets persisted.
         */
        internal val FAB_ITEM_IDS = listOf(
            "browser" to R.string.quick_action_browser,
            "flashcard" to R.string.quick_action_flashcard,
            "save_txt" to R.string.quick_action_save_txt,
            "scratchpad" to R.string.quick_action_scratchpad,
            "calculator" to R.string.quick_action_calculator,
            "calendar" to R.string.quick_action_calendar,
            "trash" to R.string.trash,
            "add_note" to R.string.add_note,
        )

        /**
         * Builds the label -> id map for every published locale.
         *
         * Kept as an internal function (rather than inlined into [legacyFabLabelToId]) so the
         * migration can be tested for all locales without exposing the map itself.
         */
        internal fun legacyFabLabelMap(context: Context): Map<String, String> = buildMap {
            FAB_ITEM_IDS.forEach { (id, stringRes) ->
                PUBLISHED_LOCALES.forEach { locale ->
                    val configuration = Configuration(context.resources.configuration)
                    configuration.setLocale(locale)
                    val label = context.createConfigurationContext(configuration).getString(stringRes)
                    put(label, id)
                }
            }
        }
    }

    val dynamicPlaceholder: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PLACEHOLDER_KEY] ?: context.getString(R.string.app_subtitle)
        }

    suspend fun savePlaceholder(placeholder: String) {
        context.dataStore.edit { preferences ->
            preferences[PLACEHOLDER_KEY] = placeholder
        }
    }

    val columnsCount: Flow<Int> = context.dataStore.data
        .map { preferences ->
            val raw = preferences[COLUMNS_COUNT_KEY] ?: DEFAULT_COLUMNS_COUNT
            if (raw > 5) DEFAULT_COLUMNS_COUNT else raw
        }

    val visibleFabItems: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val stored = prefs[VISIBLE_FAB_ITEMS_KEY] ?: emptySet()
            stored.mapTo(mutableSetOf()) { legacyFabLabelToId[it] ?: it }
        }

    suspend fun saveVisibleFabItems(items: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[VISIBLE_FAB_ITEMS_KEY] = items
        }
    }

    suspend fun saveColumnsCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[COLUMNS_COUNT_KEY] = count
        }
    }

    /**
     * The quick-action buttons used to be persisted by their *localised label*, so as soon as the
     * app text changed - after an update, or after switching language - none of the stored labels
     * matched the list any more and every button the user had added disappeared. Entries are now
     * stable ids; this map turns labels written by older builds back into their id, in every
     * language the app ships (CQ-06: [PUBLISHED_LOCALES], English/Simplified Chinese/Japanese).
     */
    private val legacyFabLabelToId: Map<String, String> by lazy {
        legacyFabLabelMap(context)
    }
}
