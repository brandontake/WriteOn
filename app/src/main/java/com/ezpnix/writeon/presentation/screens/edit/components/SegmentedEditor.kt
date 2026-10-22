@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
import com.ezpnix.writeon.presentation.screens.edit.segments.AttachmentSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.TextSegment
import com.ezpnix.writeon.presentation.screens.edit.segments.segmentize
import kotlin.math.roundToInt

/**
 * The experimental editor described by the attachment-live-preview roadmap.
 *
 * Only attachment lines become blocks. Everything between them stays one ordinary text field, so
 * typing, the IME, selection and the formatting toolbar behave exactly as before. The whole body is
 * still the single Markdown string in [EditViewModel]; this composable only writes ranges of it
 * back. That is also why the feature can be switched off without any migration: the legs of the
 * feature are the setting and the BuildConfig flag, not the database.
 */
@Composable
fun SegmentedEditor(
    viewModel: EditViewModel,
    fontSize: TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    radius: Int,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val body = viewModel.noteDescription.value.text
    val segments = remember(body) { segmentize(body) }
    val listState = rememberLazyListState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    val focusedSegments = remember { mutableStateMapOf<String, Boolean>() }
    val anyFocused = focusedSegments.isNotEmpty()
    val focusRequest = viewModel.focusRequestOffset.value

    val textStyle = TextStyle(
        fontSize = fontSize,
        fontFamily = fontFamily
    )

    DisposableEffect(Unit) {
        viewModel.setSegmentedEditing(true)
        onDispose {
            viewModel.setSegmentedEditing(false)
            viewModel.toggleIsDescriptionInFocus(false)
        }
    }

    LaunchedEffect(anyFocused) {
        viewModel.toggleIsDescriptionInFocus(anyFocused)
    }

    // A structural edit can leave the caret in a text segment that was just created. Scroll the
    // list close to it first; the field itself then reveals the exact caret line through
    // BringIntoView, even when the segment is taller than the viewport.
    LaunchedEffect(focusRequest, segments) {
        val offset = focusRequest ?: return@LaunchedEffect
        // Only a text segment owns a field; the focus helper already snapped block-only positions
        // to nearby text, but keep the scroll target aligned with that rule.
        val index = segments.indexOfFirst {
            it is TextSegment && offset in it.startOffset..it.endOffset
        }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    var editingSegment by remember { mutableStateOf<AttachmentSegment?>(null) }
    var deletingSegment by remember { mutableStateOf<AttachmentSegment?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeight = it.height },
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(segments, key = { _, segment -> segment.key }) { index, segment ->
            when (segment) {
                is TextSegment -> SegmentTextField(
                    segment = segment,
                    viewModel = viewModel,
                    textStyle = textStyle,
                    placeholder = placeholder,
                    showPlaceholder = body.isEmpty() && index == 0,
                    viewportHeight = viewportHeight,
                    focusRequestOffset = focusRequest,
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedSegments[segment.key] = true
                        } else {
                            focusedSegments.remove(segment.key)
                        }
                    },
                    onDisposed = { focusedSegments.remove(segment.key) }
                )

                is AttachmentSegment -> AttachmentBlockPreview(
                    segment = segment,
                    radius = radius,
                    onEditSource = { editingSegment = segment },
                    onDelete = { deletingSegment = segment },
                    onRename = { viewModel.renameAudioAttachment(segment, it) }
                )
            }
        }
    }

    editingSegment?.let { segment ->
        var draft by remember(segment.key) {
            mutableStateOf(segment.raw.trimEnd('\r'))
        }
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text(stringResource(R.string.attachment_edit_source_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 1,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.replaceAttachmentRaw(segment, draft)
                        editingSegment = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSegment = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    deletingSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { deletingSegment = null },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.attachment_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAttachment(segment)
                        deletingSegment = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSegment = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SegmentTextField(
    segment: TextSegment,
    viewModel: EditViewModel,
    textStyle: TextStyle,
    placeholder: String,
    showPlaceholder: Boolean,
    viewportHeight: Int,
    focusRequestOffset: Int?,
    onFocusChanged: (Boolean) -> Unit,
    onDisposed: () -> Unit
) {
    val value = viewModel.segmentValue(segment)
    val density = LocalDensity.current
    val caretMarginPx = with(density) { 8.dp.toPx() }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val caretAnchor = remember(layoutResult, value.selection, caretMarginPx) {
        val layout = layoutResult
        if (layout == null) {
            0
        } else {
            val offset = value.selection.max.coerceIn(0, layout.layoutInput.text.length)
            (layout.getCursorRect(offset).bottom + caretMarginPx).roundToInt()
        }
    }

    val fieldMinHeight = if (value.text.isEmpty()) 40.dp else 8.dp

    DisposableEffect(Unit) {
        onDispose(onDisposed)
    }

    LaunchedEffect(focused, caretAnchor, viewportHeight) {
        if (!focused) return@LaunchedEffect
        withFrameNanos { }
        bringIntoViewRequester.bringIntoView()
    }

    LaunchedEffect(focusRequestOffset, segment.startOffset, segment.endOffset) {
        val offset = focusRequestOffset ?: return@LaunchedEffect
        if (offset in segment.startOffset..segment.endOffset) {
            // A structural edit can add this item in the same frame the request is published.
            // Let it be measured and attached before asking for focus; requesting on a field that
            // has not been placed yet can leave the keyboard closed (or throw on old versions).
            withFrameNanos { }
            focusRequester.requestFocus()
            viewModel.consumeFocusRequest(offset)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = { viewModel.onSegmentChange(segment, it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = fieldMinHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                },
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            onTextLayout = { layoutResult = it },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight)
                ) {
                    if (showPlaceholder) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            }
        )
        // Outside the text field on purpose: the field's own scroller is not the scroll
        // container here. As a sibling, the request reaches the LazyColumn, which can scroll
        // this item even when the whole field is taller than the viewport.
        Box(
            modifier = Modifier
                .offset { IntOffset(0, caretAnchor) }
                .size(1.dp)
                .bringIntoViewRequester(bringIntoViewRequester)
        )
    }
}
