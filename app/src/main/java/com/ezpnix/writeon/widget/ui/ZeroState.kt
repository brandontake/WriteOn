package com.ezpnix.writeon.widget.ui

import android.appwidget.AppWidgetManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.ezpnix.writeon.R
import com.ezpnix.writeon.widget.NotesWidgetActivity

@Composable
fun ZeroState(widgetId: Int) {
    val widgetIdKey = ActionParameters.Key<Int>(AppWidgetManager.EXTRA_APPWIDGET_ID)
    val context = LocalContext.current

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center) {
        Button(
            text = context.getString(R.string.select_note),
            onClick = actionStartActivity<NotesWidgetActivity>(
                parameters = actionParametersOf(widgetIdKey to widgetId),
            ),
        )
    }
}