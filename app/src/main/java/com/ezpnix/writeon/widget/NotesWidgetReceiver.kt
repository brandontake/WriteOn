package com.ezpnix.writeon.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.Flow

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()

    companion object {
        const val WIDGET_PREFERENCE = "widgetNote_"
    }
}

/**
 * Glance's state adapter over the existing settings DataStore. Widget selections are kept as
 * settings keys so the configuration screen and widget rendering share one serialized store.
 */
class NotesDataStore(private val context: Context) : DataStore<List<Pair<Int, Int>>> {
    private val settingsRepository = SettingsRepositoryImpl(context)

    override val data: Flow<List<Pair<Int, Int>>>
        get() = settingsRepository.observeEveryNotesWidget()

    override suspend fun updateData(
        transform: suspend (List<Pair<Int, Int>>) -> List<Pair<Int, Int>>
    ): List<Pair<Int, Int>> = settingsRepository.updateEveryNotesWidget(transform)
}
