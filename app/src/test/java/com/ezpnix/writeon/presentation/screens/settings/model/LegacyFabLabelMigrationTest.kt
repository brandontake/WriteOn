package com.ezpnix.writeon.presentation.screens.settings.model

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CQ-06: the legacy quick-action labels have to be recognised in every published language.
 *
 * The migration map used to be built from English and Simplified Chinese only, while the app also
 * ships `values-ja`. A Japanese user upgrading from a build that persisted localised labels lost
 * every quick-action button because "ブラウザ" mapped to nothing. The map is now derived from
 * [SettingsPreferences.PUBLISHED_LOCALES]; this test iterates the same constant and fails when a
 * locale, a translated label or the constant itself is missing.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyFabLabelMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every published locale maps its legacy labels back to their ids`() {
        assertEquals(
            "PUBLISHED_LOCALES must match the shipped values-* resources",
            setOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE),
            SettingsPreferences.PUBLISHED_LOCALES.toSet()
        )

        val mapping = SettingsPreferences.legacyFabLabelMap(context)

        SettingsPreferences.PUBLISHED_LOCALES.forEach { locale ->
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            val localized = context.createConfigurationContext(configuration)

            SettingsPreferences.FAB_ITEM_IDS.forEach { (id, stringRes) ->
                val label = localized.getString(stringRes)
                assertEquals(
                    "legacy label '$label' in $locale must migrate to '$id'",
                    id,
                    mapping[label]
                )
            }
        }

        assertEquals(
            "every locale/item pair must contribute a distinct label",
            SettingsPreferences.FAB_ITEM_IDS.size * SettingsPreferences.PUBLISHED_LOCALES.size,
            mapping.size
        )
    }
}
