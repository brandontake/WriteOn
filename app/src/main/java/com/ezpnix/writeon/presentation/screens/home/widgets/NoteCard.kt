package com.ezpnix.writeon.presentation.screens.home.widgets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.markdown.MarkdownText
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.theme.appCornerShape
import com.ezpnix.writeon.presentation.theme.CornerFraction
import androidx.compose.ui.res.stringResource

/**
 * UX-09: where Home publishes the card checkbox handler.
 *
 * A CompositionLocal instead of another parameter on `NotesGrid`: the recycle bin reuses that grid
 * with `onNoteUpdate = {}`, and a trashed card must not edit anything. Home provides the handler
 * (see NoteFilter) and the bin leaves it null, which keeps the old no-op behaviour there.
 */
internal val LocalNoteCheckboxToggle =
    compositionLocalOf<((Int, Int, String, Boolean) -> Unit)?> { null }

/**
 * UX-09: where Home publishes the card title handler.
 *
 * A title edit is also a read-modify-write: the card must not send `note.copy(name = ...)` back,
 * because [note] is the body the card captured when it was composed. The handler's second argument
 * is the whole new title text; Home's implementation reads the latest stored row and changes only
 * its name. Null (the recycle bin, the widget chooser) keeps the old `onNoteUpdate` behaviour.
 */
internal val LocalNoteTitleUpdate =
    compositionLocalOf<((Int, String) -> Unit)?> { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    settingsViewModel: SettingsViewModel,
    containerColor: Color,
    note: Note,
    isBorderEnabled: Boolean,
    shape: RoundedCornerShape,
    onShortClick: () -> Unit,
    onLongClick: () -> Unit,
    onNoteUpdate: (Note) -> Unit,
    /**
     * Invoked with an `attachment://` id when the recording or picture block on the card offers
     * delete. Home passes its handler; the recycle bin leaves it null so trashed cards cannot
     * delete anything.
     */
    onAttachmentDelete: ((String) -> Unit)? = null,
    /**
     * Applied to the card itself. Home uses it to publish pin / tags / delete as custom
     * accessibility actions, because those only exist behind a left swipe (U7).
     */
    modifier: Modifier = Modifier,
    /**
     * UX-09: the home feed's checkbox handler, which reads the latest stored body and toggles the
     * requested line there.
     *
     * Home provides it through [LocalNoteCheckboxToggle] (see NoteFilter), so the grid keeps its
     * call shape and the recycle bin - which shares the same grid but must not edit anything -
     * keeps the old no-op behaviour. Passing a lambda here keeps the widget testable.
     */
    onCheckboxToggle: ((Int, Int, String, Boolean) -> Unit)? = null
) {
    val sharedToggle = LocalNoteCheckboxToggle.current
    val toggleCheckbox: ((Int, Int, String, Boolean) -> Unit)? = onCheckboxToggle ?: sharedToggle
    val updateTitle = LocalNoteTitleUpdate.current
    // One snapshot read per card instead of one per settings lookup: every `settings.value`
    // read subscribes this card to the settings state, so re-reading it six times made each
    // card recompose on unrelated settings writes.
    val settings = settingsViewModel.settings.value
    // The body is cut down to a summary *before* it reaches the layout: the card used to compose
    // the whole note into a Column and merely clip it with `heightIn`, so a 20 000 character note
    // still measured and drew every line on every visible card (P-2).
    val body = remember(note.description) { buildCardBody(note.description) }
    ElevatedCard(
        modifier = modifier
            .padding(bottom = 12.dp)
            .padding(4.dp)
            .clip(shape)
            .combinedClickable(
                // Neither label is inferable from the card, so without them TalkBack announced a
                // plain "double tap to activate" for both gestures (U8). Long pressing is what
                // starts selection, hence a different wording for each.
                onClickLabel = stringResource(R.string.cd_note_open_note),
                onLongClickLabel = stringResource(R.string.cd_note_select_note),
                onClick = { onShortClick() },
                onLongClick = { onLongClick() }
            )
            .then(
                if (isBorderEnabled) Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = shape
                ) else Modifier
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (containerColor != Color.Black) containerColor else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp, 16.dp)
                .fillMaxWidth()
        ) {
            if (note.name.isNotBlank()) {
                MarkdownText(
                    isPreview = false,
                    // Cards render into a grid item, so the body must not nest a second lazy
                    // list inside the grid's: a plain column renders the same elements without
                    // a scroll container (and without swallowing the grid's drag gestures).
                    useLazyColumn = false,
                    isEnabled = settings.isMarkdownEnabled,
                    markdown = note.name,
                    containerColor = containerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .heightIn(max = dimensionResource(R.dimen.max_name_height)),
                    weight = FontWeight.Bold,
                    spacing = 0.dp,
                    onContentChange = { text ->
                        // UX-09: a title checkbox tap rewrites the title, not the body this card
                        // captured. Home reads the latest row and writes only `name`; without a
                        // handler (the recycle bin) this stays a no-op through `onNoteUpdate`.
                        if (updateTitle != null) {
                            updateTitle(note.id, text)
                        } else {
                            onNoteUpdate(note.copy(name = text))
                        }
                    },
                    fontSize = 20.sp,
                    radius = settings.cornerRadius,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onAttachmentDelete = onAttachmentDelete
                )
            }
            if (note.name.isNotBlank() && note.description.isNotBlank() && !settings.showOnlyTitle) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (note.description.isNotBlank() && !settings.showOnlyTitle) {
                MarkdownText(
                    isPreview = false,
                    useLazyColumn = false,
                    // The summary, not the whole note - see buildCardBody.
                    markdown = body.text,
                    isEnabled = settings.isMarkdownEnabled,
                    spacing = 0.dp,
                    containerColor = containerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .heightIn(max = dimensionResource(R.dimen.max_description_height)),
                    // UX-09: the summary is a bounded, possibly truncated view of the body, so a
                    // checkbox tick must not rebuild the note from it. Home's handler reads the
                    // latest stored body and toggles that line there; without one (the recycle
                    // bin) the tap stays a no-op, exactly as before.
                    onCheckboxToggle = toggleCheckbox?.let { toggle ->
                        { lineNumber: Int, text: String, checked: Boolean ->
                            toggle(note.id, lineNumber, text, checked)
                        }
                    },
                    fontSize = 14.sp,
                    radius = settings.cornerRadius,
                    onAttachmentDelete = onAttachmentDelete
                )
            }
            if (note.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = appCornerShape(CornerFraction.TINY)
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Label,
                        contentDescription = stringResource(R.string.tagged_note),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = note.tags.joinToString(", ", limit = 2, truncated = "..."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 100.dp)
                    )
                }
            }
        }
    }
}