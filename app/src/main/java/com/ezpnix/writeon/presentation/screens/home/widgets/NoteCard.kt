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
    modifier: Modifier = Modifier
) {
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
                    onContentChange = { onNoteUpdate(note.copy(name = it)) },
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
                    // A checkbox toggled on the card edits the note, so the write-back has to
                    // restore the lines the summary dropped. Without this, ticking a box on a long
                    // note would save the summary over the full text.
                    onContentChange = { updated ->
                        onNoteUpdate(note.copy(description = rebuildFullBody(body, updated)))
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

/** Enough of a note to fill the 150dp body area on any density, far less than most notes. */
private const val CARD_BODY_MAX_LINES = 12

/** A single line longer than this (a pasted paragraph, a one-line log) is cut as well. */
private const val CARD_BODY_MAX_LINE_CHARS = 400

/**
 * The part of a note body a home card renders, plus whatever was cut off.
 *
 * [tails] holds the remainder of each line that was too long and [hiddenLines] the lines past
 * [CARD_BODY_MAX_LINES]; both exist so [rebuildFullBody] can put the note back together when a
 * checkbox is ticked from the card. Ticking a checkbox in the summary must never write the summary
 * over the note.
 */
private class CardBody(
    val text: String,
    val isTruncated: Boolean,
    val tails: List<String>,
    val hiddenLines: List<String>
)

private fun buildCardBody(description: String): CardBody {
    val lines = description.lines()
    val fits = lines.size <= CARD_BODY_MAX_LINES &&
        lines.none { it.length > CARD_BODY_MAX_LINE_CHARS }
    if (fits) {
        return CardBody(description, isTruncated = false, tails = emptyList(), hiddenLines = emptyList())
    }
    val head = lines.take(CARD_BODY_MAX_LINES)
    return CardBody(
        text = head.joinToString("\n") { it.take(CARD_BODY_MAX_LINE_CHARS) },
        isTruncated = true,
        tails = head.map { it.drop(CARD_BODY_MAX_LINE_CHARS) },
        hiddenLines = lines.drop(CARD_BODY_MAX_LINES)
    )
}

/**
 * Re-attaches what [buildCardBody] cut off, so [updated] (the summary after the user ticked a
 * checkbox in it) can be saved as the note body without losing the rest of the text.
 */
private fun rebuildFullBody(body: CardBody, updated: String): String {
    if (!body.isTruncated) return updated
    val head = updated.lines().mapIndexed { index, line ->
        line + (body.tails.getOrNull(index) ?: "")
    }
    return (head + body.hiddenLines).joinToString("\n")
}