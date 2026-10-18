package com.ezpnix.writeon.presentation.screens.home.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.getNoteEnterAnimation
import com.ezpnix.writeon.presentation.components.getNoteExitAnimation
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel

@Composable
fun NotesGrid(
    settingsViewModel: SettingsViewModel,
    containerColor: Color,
    onNoteClicked: (Int) -> Unit,
    shape : RoundedCornerShape,
    notes: List<Note>,
    onNoteUpdate: (Note) -> Unit,
    selectedNotes: MutableList<Note>,
    viewMode: Boolean,
    isDeleteClicked: Boolean,
    animationFinished: (Int) -> Unit,
    onPinNote: (Note) -> Unit = {},
    onTagNote: (Note) -> Unit = {},
    swipeEnabled: Boolean = true
) {
    // Splitting the note list is pure work over the whole list: keep it tied to `notes` instead of
    // redoing the partition on every recomposition (selection change, animation frame, FAB, ...).
    val (pinnedNotes, otherNotes) = remember(notes) { notes.partition { it.pinned } }
    // Membership tests are used while rendering every card; a Set lookup keeps that O(1) instead of
    // O(selection) per card per frame.
    val selectedNoteIds = selectedNotes.mapTo(HashSet()) { it.id }
    // `rememberSaveable` (which rememberLazyStaggeredGridState builds on) is kept per
    // NavBackStackEntry, so the scroll offset survives both a rotation and a trip through the trash
    // screen, instead of snapping back to the first note every time the grid comes back.
    val gridState = rememberLazyStaggeredGridState()

    // A note row with pin/trash actions that slide out from behind the card on a
    // left swipe. Only the card's x offset animates, so staggered grid measurements
    // never change; the actions row is painted underneath and revealed by the offset.
    @Composable
    fun SwipeableNoteItem(note: Note) {
        val isRevealed = remember { mutableStateOf(false) }
        // Width of the action buttons plus their end inset, measured after the first
        // layout so the card slides exactly far enough to uncover both buttons instead
        // of relying on a hard-coded distance that could leave one of them covered.
        var actionsWidthPx by remember { mutableStateOf(0) }
        val actionsWidth = with(LocalDensity.current) { actionsWidthPx.toDp() }
        // Collapse a revealed row as soon as selection mode starts anywhere.
        LaunchedEffect(selectedNoteIds.isNotEmpty()) {
            if (selectedNoteIds.isNotEmpty()) isRevealed.value = false
        }
        val revealOffset by animateDpAsState(
            targetValue = if (isRevealed.value) -actionsWidth else 0.dp,
            animationSpec = tween(250),
            label = "NoteSwipeReveal"
        )
        Box {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .onSizeChanged { actionsWidthPx = it.width }
                    .padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalIconButton(
                    onClick = {
                        isRevealed.value = false
                        onPinNote(note)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = stringResource(R.string.pin)
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        isRevealed.value = false
                        onTagNote(note)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Label,
                        contentDescription = stringResource(R.string.nav_tags)
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        isRevealed.value = false
                        animationFinished(note.id)
                    },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = revealOffset)
                    .pointerInput(selectedNoteIds.isEmpty()) {
                        if (selectedNoteIds.isNotEmpty()) return@pointerInput
                        val revealThresholdPx = 64.dp.toPx()
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                if (totalDrag < -revealThresholdPx) isRevealed.value = true
                                else if (totalDrag > revealThresholdPx) isRevealed.value = false
                            },
                            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
                        )
                    }
            ) {
                NoteCard(
                    settingsViewModel = settingsViewModel,
                    containerColor = containerColor,
                    note = note,
                    shape = shape,
                    isBorderEnabled = note.id in selectedNoteIds,
                    onShortClick = {
                        if (isRevealed.value) {
                            isRevealed.value = false
                        } else {
                            handleShortClick(selectedNotes, note, onNoteClicked)
                        }
                    },
                    onNoteUpdate = onNoteUpdate,
                    onLongClick = { handleLongClick(selectedNotes, note) }
                )
            }
        }
    }

    @Composable
    fun NoteItem(note: Note, slideDirection: Int) {
        val isAnimationVisible = rememberTransitionState()
        AnimatedVisibility(
            visibleState = isAnimationVisible,
            enter = getNoteEnterAnimation(),
            exit = getNoteExitAnimation(slideDirection)
        ) {
            if (swipeEnabled) {
                SwipeableNoteItem(note)
            } else {
                NoteCard(
                    settingsViewModel = settingsViewModel,
                    containerColor = containerColor,
                    note = note,
                    shape = shape,
                    isBorderEnabled = note.id in selectedNoteIds,
                    onShortClick = { handleShortClick(selectedNotes, note, onNoteClicked) },
                    onNoteUpdate = onNoteUpdate,
                    onLongClick = { handleLongClick(selectedNotes, note) }
                )
            }
            if (isDeleteClicked && note.id in selectedNoteIds) {
                isAnimationVisible.targetState = false
            }
        }
        handleDeleteAnimation(selectedNotes, note, isAnimationVisible, animationFinished)
    }

    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = when(viewMode) {
            true  -> StaggeredGridCells.Fixed(settingsViewModel.settings.value.columnsCount)
            false -> StaggeredGridCells.Fixed(1)
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = {
            if (pinnedNotes.isNotEmpty()) {
                item(key = "pinned-header", contentType = "header", span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        modifier = Modifier.padding(bottom = 16.dp),
                        text = stringResource(id = R.string.pinned).uppercase(),
                        style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                    )
                }
                // Stable ids keep each card's state and its enter/exit animation attached to the note
                // itself while the list is filtered, sorted or reordered.
                itemsIndexed(
                    items = pinnedNotes,
                    key = { _, note -> note.id },
                    contentType = { _, _ -> "note" }
                ) { index, note ->
                    NoteItem(note, slideDirection = calculateSlideDirection(index))
                }
                if (otherNotes.isNotEmpty()) {
                    item(key = "others-header", contentType = "header", span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            modifier = Modifier.padding(vertical = 16.dp),
                            text = stringResource(id = R.string.others).uppercase(),
                            style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
            }
            itemsIndexed(
                items = otherNotes,
                key = { _, note -> note.id },
                contentType = { _, _ -> "note" }
            ) { index, note ->
                NoteItem(note, slideDirection = calculateSlideDirection(index))
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}


private fun calculateSlideDirection(index: Int): Int {
    return if (index % 2 == 0) -1 else 1
}

@Composable
private fun rememberTransitionState(): MutableTransitionState<Boolean> {
    return remember { MutableTransitionState(false).apply { targetState = true } }
}

private fun handleShortClick(
    selectedNotes: MutableList<Note>,
    note: Note,
    onNoteClicked: (Int) -> Unit
) {
    if (selectedNotes.isNotEmpty()) {
        if (selectedNotes.contains(note)) {
            selectedNotes.remove(note)
        } else {
            selectedNotes.add(note)
        }
    } else {
        onNoteClicked(note.id)
    }
}

private fun handleLongClick(selectedNotes: MutableList<Note>, note: Note) {
    if (!selectedNotes.contains(note)) {
        selectedNotes.add(note)
    }
}

private fun handleDeleteAnimation(
    selectedNotes: MutableList<Note>,
    note: Note,
    isAnimationVisible: MutableTransitionState<Boolean>,
    animationFinished: (Int) -> Unit
) {
    if (!isAnimationVisible.targetState && !isAnimationVisible.currentState && selectedNotes.contains(note)) {
        selectedNotes.remove(note)
        isAnimationVisible.targetState = true
        animationFinished(note.id)
    }
}
