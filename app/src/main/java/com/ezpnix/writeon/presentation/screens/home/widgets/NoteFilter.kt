package com.ezpnix.writeon.presentation.screens.home.widgets

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.screens.home.viewmodel.HomeViewModel
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel

@Composable
fun NoteFilter(
    settingsViewModel: SettingsViewModel,
    containerColor: Color,
    onNoteClicked: (Int) -> Unit,
    shape: RoundedCornerShape,
    notes: List<Note>,
    searchText: String? = null,
    selectedNotes: MutableList<Note> = mutableListOf(),
    viewMode: Boolean = false,
    isDeleteMode: Boolean = false,
    onNoteUpdate: (Note) -> Unit = {},
    onDeleteNote: (Int) -> Unit = {},
    onPinNote: (Note) -> Unit = {},
    onTagNote: (Note) -> Unit = {},
    swipeEnabled: Boolean = true,
    onAttachmentDelete: ((String) -> Unit)? = null,
    onRestore: () -> Unit = {},
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    navController: NavController? = null,
    /**
     * UX-09: the home entry's ViewModel, used only to publish [LocalNoteCheckboxToggle] to the
     * cards. Defaulted so HomeScreen's call does not change; the recycle bin does not use this
     * composable and therefore keeps its no-op checkbox behaviour.
     */
    homeViewModel: HomeViewModel = hiltViewModel(),
    /**
     * UX-09: title edits go through Home's latest-row handler instead of writing the captured
     * card back. Null keeps the old `onNoteUpdate` path for the widget chooser.
     */
    onNoteTitleUpdate: ((Int, String) -> Unit)? = null,
) {
    // PERF-04: `notes` arrives already search-filtered, tag-filtered and sorted. The scan itself
    // moved to HomeViewModel, where a debounce and Dispatchers.Default keep it off the composition
    // thread; `searchText` is only the empty-state message's input now.
    val filteredNotes = notes
    if (filteredNotes.isEmpty()) {
        Placeholder(
            placeholderIcon = {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    if (navController != null) {
                        EmptyLeftButton(
                            navController = navController,
                            shape = shape
                        )
                    }
                    EmptyMiddleButton(
                        onNoteClicked = onNoteClicked,
                        shape = shape
                    )
                    EmptyRightButton(
                        shape = shape
                    )
                }
            },
            placeholderText = getEmptyText(searchText)
        )
    } else {
        // A bound reference is a new object every composition; remembering it keeps the
        // CompositionLocal value stable so unrelated recompositions do not invalidate every card.
        val checkboxToggle = remember(homeViewModel) { homeViewModel::toggleNoteCheckbox }
        // Null on purpose when the caller has no handler: the widget chooser shares this
        // composable and must keep its old read-only card behaviour.
        val titleUpdate = remember(onNoteTitleUpdate) { onNoteTitleUpdate }
        CompositionLocalProvider(
            LocalNoteCheckboxToggle provides checkboxToggle,
            LocalNoteTitleUpdate provides titleUpdate
        ) {
            NotesGrid(
                settingsViewModel = settingsViewModel,
                containerColor = containerColor,
                onNoteClicked = onNoteClicked,
                notes = filteredNotes,
                shape = shape,
                onNoteUpdate = onNoteUpdate,
                selectedNotes = selectedNotes,
                viewMode = viewMode,
                isDeleteClicked = isDeleteMode,
                animationFinished = onDeleteNote,
                onPinNote = onPinNote,
                onTagNote = onTagNote,
                swipeEnabled = swipeEnabled,
                onAttachmentDelete = onAttachmentDelete
            )
        }
    }
}

/**
 * Search state for one snapshot of [notes], memoising the last query's result.
 *
 * [filterNotes] scans the name and the body of every note. That is the right cost for the first
 * keystroke, but not for the ninth: a query that extends the previous one (`"bea"` -> `"bean"`)
 * can only match notes the shorter query already matched, so the next scan runs over that
 * shrinking result set instead of the whole library.
 *
 * Everything else - a backspace, a paste, replacing a selection, clearing the field - falls back
 * to the full pass, so the answer is always exactly what `filterNotes(notes, searchText)` returns.
 *
 * The object is rebuilt whenever the note list changes (HomeViewModel keeps it next to the list
 * snapshot it describes - see `observeSearch` there), so a result set can never outlive the data
 * it was computed from. The scan runs on `Dispatchers.Default`; this class stays a pure helper so
 * both the ViewModel and a JVM test can use it without Compose.
 */
internal class NoteSearchState(private val notes: List<Note>) {
    private var query: String? = null
    private var result: List<Note> = notes

    fun filter(searchText: String?): List<Note> {
        val next = searchText?.takeIf { it.isNotBlank() }
        if (next == null) {
            query = null
            result = notes
            return notes
        }
        if (next == query) return result

        val previous = query
        val canNarrow = previous != null &&
            next.length > previous.length &&
            next.startsWith(previous, ignoreCase = true)
        val scanned = if (canNarrow) result else notes
        query = next
        result = filterNotes(scanned, next)
        return result
    }
}

internal fun filterNotes(notes: List<Note>, searchText: String?): List<Note> {
    return searchText?.takeIf { it.isNotBlank() }?.let { query ->
        notes.filter { note ->
            note.name.contains(query, ignoreCase = true) || note.description.contains(query, ignoreCase = true)
        }
    } ?: notes
}

@Composable
private fun getEmptyText(searchText: String?): String {
    // "No results" only makes sense when something was actually searched: on a genuinely empty
    // library the welcome text is the right answer.
    return if (searchText.isNullOrBlank()) {
        stringResource(R.string.startup)
    } else {
        stringResource(R.string.search_no_results)
    }
}

@Composable
fun EmptyLeftButton(
    navController: NavController,
    shape: RoundedCornerShape
) {
    Button(
        onClick = {
            navController.navigate(NavRoutes.Guide.route)
        },
        shape = shape,
        modifier = Modifier
            .size(80.dp)
            .padding(4.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Help,
            contentDescription = stringResource(R.string.cd_guide),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
fun EmptyMiddleButton(
    onNoteClicked: (Int) -> Unit,
    shape: RoundedCornerShape
) {
    Button(
        onClick = { onNoteClicked(0) },
        shape = shape,
        modifier = Modifier
            .size(100.dp)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.AddCircle,
            contentDescription = stringResource(R.string.add_note),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
fun EmptyRightButton(
    shape: RoundedCornerShape
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Button(
        onClick = {
            clipboardManager.setText(AnnotatedString(context.getString(R.string.note_filter_support)))
            Toast.makeText(context, context.getString(R.string.msg_link_copied), Toast.LENGTH_SHORT).show()
        },
        shape = shape,
        modifier = Modifier
            .size(80.dp)
            .padding(4.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Share,
            contentDescription = stringResource(R.string.share),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp)
        )
    }
}