package com.ezpnix.writeon.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.ezpnix.writeon.core.widget.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The real [WidgetRefresher]: redraws every placed home-screen widget.
 *
 * Kept out of the domain layer so that observing notes - which calls this on every change - does
 * not drag Glance (or a Context) into a unit test.
 */
class GlanceWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context
) : WidgetRefresher {

    override suspend fun refreshAll() {
        NotesWidget().updateAll(context)
    }
}
