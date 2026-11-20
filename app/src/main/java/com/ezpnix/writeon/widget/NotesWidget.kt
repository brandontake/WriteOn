package com.ezpnix.writeon.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.state.GlanceStateDefinition
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.lock.LockEntryPoint
import com.ezpnix.writeon.widget.ui.SelectedNote
import com.ezpnix.writeon.widget.ui.ZeroState
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.flow.first

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
            override fun getLocation(context: Context, fileKey: String): File =
                File(context.filesDir, "glance/widget-state/$fileKey.preferences_pb")
        }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val noteUseCase = getNoteUseCase(context)
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        // PERF-6: resolve this widget's note before reading anything, so one refresh touches one
        // row instead of decrypting the whole library. The id lives in the same NotesDataStore the
        // configuration screen writes; reading it here (rather than inside the composition) is what
        // lets the load happen exactly once, outside Glance's recomposition.
        val configuredNoteId = NotesDataStore(context).data
            .first()
            .firstOrNull { it.first == widgetId }
            ?.second

        // SEC-6: a widget sits outside the lock gate. When a credential is configured, do not
        // query or decrypt anything - the preview must not put plaintext in memory (or on the
        // launcher) just because the launcher decided to refresh. The zero state is the same
        // surface the widget already shows for "no note selected"; tapping it still opens the
        // configuration screen, which is behind LockGate.
        val lockManager = EntryPointAccessors
            .fromApplication(context.applicationContext, LockEntryPoint::class.java)
            .lockManager()
        val selectedNote = if (lockManager.anyLockEnabled()) {
            null
        } else {
            configuredNoteId?.let { noteUseCase.loadNoteOnce(it) }
        }

        provideContent {
            GlanceTheme {
                if (selectedNote == null) {
                    ZeroState(widgetId = widgetId)
                } else {
                    SelectedNote(selectedNote, noteUseCase, widgetId = widgetId)
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