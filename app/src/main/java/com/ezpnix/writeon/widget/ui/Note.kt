package com.ezpnix.writeon.widget.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.util.NoteBodyEditLock
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.MainActivity
import com.ezpnix.writeon.presentation.components.markdown.WidgetText
import com.ezpnix.writeon.presentation.components.markdown.widgetBodyLine
import com.ezpnix.writeon.presentation.lock.LockEntryPoint
import com.ezpnix.writeon.presentation.lock.LockManager
import com.ezpnix.writeon.presentation.screens.home.viewmodel.toggleCheckboxInLatestBody
import com.ezpnix.writeon.widget.NotesWidget
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Resolves the singleton [LockManager] from a Glance (non-Hilt-Activity) composition. */
@Composable
private fun widgetLockManager(context: Context): LockManager = remember(context) {
    EntryPointAccessors
        .fromApplication(context.applicationContext, LockEntryPoint::class.java)
        .lockManager()
}

/**
 * The note preview shown on the home screen widget.
 *
 * When the app lock is configured the preview is deliberately blank: a widget sits outside the app
 * process's lock gate, so rendering the note here would publish its title and body to anyone holding
 * the phone — including the launcher, the overview thumbnails and a screenshot of the home screen.
 * Tapping the widget still opens the app, which then asks for the lock credential as usual.
 */
@Composable
fun SelectedNote(note: Note, noteUseCase: NoteUseCase, widgetId: Int) {
    val context = LocalContext.current
    val lockManager = widgetLockManager(context)

    val glanceModifier = GlanceModifier.clickable {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("noteId", note.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    // Centred rather than pinned to the top. A note that is one or two lines long used to sit at
    // the very top of the widget with a large empty area underneath, which reads as a broken
    // layout on a launcher grid; the short note now sits in the middle of the cell the same way
    // the empty state already does.
    Box(
        modifier = glanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lockManager.anyLockEnabled()) {
            WidgetText(
                modifier = GlanceModifier.fillMaxWidth(),
                markdown = context.getString(R.string.widget_locked_notice),
                weight = FontWeight.Normal,
                fontSize = 12.sp,
                color = GlanceTheme.colors.primary,
            )
        } else {
            val combinedMarkdown = buildString {
                if (note.name.isNotBlank()) {
                    appendLine("# ${note.name}")
                    appendLine()
                }
                if (note.description.isNotBlank()) {
                    append(note.description)
                }
            }

            if (combinedMarkdown.isNotBlank()) {
                WidgetText(
                    modifier = GlanceModifier.fillMaxWidth(),
                    markdown = combinedMarkdown,
                    weight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = GlanceTheme.colors.onSurface,
                    onCheckboxChange = { lineNumber, text, checked ->
                        // R4-05: the offset is derived from the actual title, not a fixed two
                        // lines. A tap on a title continuation line maps to null and is ignored.
                        val bodyLine = widgetBodyLine(lineNumber, note.name)
                        if (bodyLine != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                // R3-04/R2-03: the shared read-modify-write serialises a widget tap with
                                // another widget tap and with Home card edits. It re-reads the live row
                                // itself, so the snapshot captured at composition time is never written
                                // back, and a line whose text changed since the preview is a no-op.
                                val toggled = runCatching {
                                    // R3-06: the same Vault-unlock lock the editor and Home cards use;
                                    // a widget tap must not seal a vault row with a candidate passphrase.
                                    lockManager.withVaultUnlock {
                                        toggleCheckboxInLatestBody(
                                            noteId = note.id,
                                            lineNumber = bodyLine,
                                            text = text,
                                            checked = checked,
                                            mutex = NoteBodyEditLock.mutex,
                                            readLatest = { id -> noteUseCase.getEditableNote(id) },
                                            write = { updated ->
                                                noteUseCase.addNote(
                                                    updated.copy(updatedAt = System.currentTimeMillis())
                                                )
                                            },
                                        )
                                    }
                                }.onFailure { error ->
                                    android.util.Log.e(
                                        "WidgetNote",
                                        "Could not save the widget edit",
                                        error
                                    )
                                }.getOrDefault(false)
                                if (toggled) {
                                    // PERF-3: no observe() here. The old call started (another) full
                                    // library collector from a widget, and every one of those lived for
                                    // the process. A redraw is enough: each placed widget re-enters
                                    // provideGlance and reads only its own note (PERF-6).
                                    runCatching { NotesWidget().updateAll(context) }
                                        .onFailure { error ->
                                            android.util.Log.e(
                                                "WidgetNote",
                                                "Could not refresh the widget",
                                                error
                                            )
                                        }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
