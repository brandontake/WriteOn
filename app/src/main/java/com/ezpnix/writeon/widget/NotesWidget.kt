package com.ezpnix.writeon.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.widget.ui.SelectedNote
import com.ezpnix.writeon.widget.ui.ZeroState
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetModelRepositoryEntryPoint {
    fun noteUseCase() : NoteUseCase
}

class NotesWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<List<Pair<Int, Int>>>
        get() = object: GlanceStateDefinition<List<Pair<Int, Int>>> {
            override suspend fun getDataStore(
                context: Context,
                fileKey: String
            ): DataStore<List<Pair<Int, Int>>> {
                return NotesDataStore(context)
            }
            override fun getLocation(context: Context, fileKey: String): File {
                throw NotImplementedError("Not implemented")
            }
        }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val noteUseCase = getNoteUseCase(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        // Which note this widget shows lives in the state DataStore, so it has to be read inside the
        // composition. Everything else is read here, once, before the composition opens.
        //
        // Calling observe() inside provideContent used to start a collector that itself called
        // updateAll, so refreshing the widget re-triggered the refresh. loadNotesOnce ends that loop.
        noteUseCase.loadNotesOnce()

        provideContent {
            GlanceTheme {
                val state = currentState<List<Pair<Int, Int>>>()
                val noteId = state.firstOrNull { it.first == widgetId }?.second
                val selectedNote = noteId?.let { id -> noteUseCase.notes.firstOrNull { it.id == id } }
                when {
                    selectedNote == null -> ZeroState(widgetId = widgetId)
                    else -> SelectedNote(selectedNote, noteUseCase, widgetId = widgetId)
                }
            }
        }
    }
}

fun getNoteUseCase(applicationContext: Context): NoteUseCase {
    var widgetModelRepositoryEntrypoint: WidgetModelRepositoryEntryPoint = EntryPoints.get(
        applicationContext,
        WidgetModelRepositoryEntryPoint::class.java,
    )
    return widgetModelRepositoryEntrypoint.noteUseCase()
}