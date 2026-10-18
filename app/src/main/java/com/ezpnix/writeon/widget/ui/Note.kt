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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.MainActivity
import com.ezpnix.writeon.presentation.components.markdown.WidgetText
import com.ezpnix.writeon.presentation.lock.LockEntryPoint
import com.ezpnix.writeon.presentation.lock.LockManager
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
                    onContentChange = { updated ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val lines = updated.lines()
                            val hasHeading = lines.firstOrNull()?.startsWith("# ") == true
                            val newName = if (hasHeading) lines.first().removePrefix("# ").trim() else note.name
                            val newDesc = if (hasHeading) {
                                lines.drop(1).joinToString("\n").trimStart('\n')
                            } else {
                                updated
                            }
                            noteUseCase.addNote(note.copy(name = newName, description = newDesc))
                            noteUseCase.observe()
                        }
                    }
                )
            }
        }
    }
}
