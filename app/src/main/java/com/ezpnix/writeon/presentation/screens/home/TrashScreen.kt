package com.ezpnix.writeon.presentation.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.components.markdown.AudioPlayerCard
import com.ezpnix.writeon.presentation.components.markdown.MissingAttachment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ezpnix.writeon.presentation.screens.home.viewmodel.HomeViewModel
import com.ezpnix.writeon.presentation.screens.home.widgets.NotesGrid
import com.ezpnix.writeon.presentation.screens.home.widgets.filterNotes
import com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager
import com.ezpnix.writeon.presentation.theme.appCornerShape
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    settingsViewModel: SettingsViewModel,
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val trashedNotes by viewModel.noteUseCase.getTrashedNotesFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val selectedNotes = remember { mutableStateListOf<Note>() }
    // Only the ids survive a configuration change: Note is a plain data class and is neither
    // Parcelable nor Serializable, so it cannot go into a Bundle.
    var longPressedNoteId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingPermanentDeleteIds by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 2.0.2: the same screen empties the attachment bins. Notes stay tab 0, and the two attachment
    // tabs mirror the library so a picture or recording deleted from anywhere can come back.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var longPressedAttachmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermanentDeleteAttachmentId by rememberSaveable { mutableStateOf<String?>(null) }
    val trashedAttachments by viewModel.noteUseCase.getTrashedAttachmentsFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val trashedImages = remember(trashedAttachments) {
        trashedAttachments.filter { it.type == Attachment.TYPE_IMAGE }
    }
    val trashedAudios = remember(trashedAttachments) {
        trashedAttachments.filter { it.type == Attachment.TYPE_AUDIO }
    }
    val longPressedAttachment = trashedAttachments.firstOrNull { it.id == longPressedAttachmentId }
    // Search narrows the list, so every selection decision is made against what the user can see.
    // Comparing sizes against the unfiltered list - as this screen used to - reported "all selected"
    // as soon as one note was hidden by the query, and flipped the toggle to "deselect" instead.
    val visibleNotes = remember(trashedNotes, searchQuery) { filterNotes(trashedNotes, searchQuery) }
    val allSelected = visibleNotes.isNotEmpty() && visibleNotes.all { note ->
        selectedNotes.any { it.id == note.id }
    }
    val longPressedNote = trashedNotes.firstOrNull { it.id == longPressedNoteId }

    BackHandler(enabled = true) {
        if (selectedNotes.isNotEmpty()) {
            selectedNotes.clear()
        } else {
            navController.navigateUp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.trash),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.trash_tab_notes)) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.library_images)) }
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(stringResource(R.string.library_audio)) }
                )
            }

            if (tab != 0) {
                if (tab == 1) {
                    TrashAttachmentsGrid(
                        attachments = trashedImages,
                        cornerRadius = settingsViewModel.settings.value.cornerRadius,
                        emptyText = stringResource(R.string.library_empty_images),
                        onLongPress = { longPressedAttachmentId = it }
                    )
                } else {
                    TrashAttachmentsList(
                        attachments = trashedAudios,
                        cornerRadius = settingsViewModel.settings.value.cornerRadius,
                        emptyText = stringResource(R.string.library_empty_audio),
                        onLongPress = { longPressedAttachmentId = it }
                    )
                }
            } else {
            // Material 3 search bar: a 56dp pill filled with the container colour instead of an
            // outlined field. The outline version read as a form entry, which a filter is not.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cancel)
                                )
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }

            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            selectedNotes.forEach { viewModel.restoreNote(it) }
                            selectedNotes.clear()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedNotes.isNotEmpty()
                    ) {
                        Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore))
                    }

                    OutlinedButton(
                        onClick = {
                            // Permanent deletion cannot be undone and is not covered by any backup,
                            // so the user confirms before anything is removed.
                            pendingPermanentDeleteIds = selectedNotes.map { it.id }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        enabled = selectedNotes.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_forever))
                    }

                    // Select-all toggle: compact icon button so the two labelled actions
                    // keep enough room for their icon + text on narrow screens.
                    FilledTonalIconButton(
                        onClick = {
                            // Only what the query left visible: selecting hidden notes too would
                            // delete notes the user cannot see. Tapping again deselects that set.
                            selectedNotes.removeAll { selected ->
                                visibleNotes.any { it.id == selected.id }
                            }
                            if (!allSelected) {
                                selectedNotes.addAll(visibleNotes)
                            }
                        },
                        enabled = visibleNotes.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = if (allSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                            contentDescription = stringResource(
                                if (allSelected) R.string.deselect_all else R.string.select_all
                            )
                        )
                    }
                }
            }

            if (trashedNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.trash_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else if (visibleNotes.isEmpty()) {
                // The trash has notes, the query matched none of them: say so instead of showing an
                // empty grid, which reads as if the trash had just been emptied.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                NotesGrid(
                    settingsViewModel = settingsViewModel,
                    containerColor = getContainerColor(settingsViewModel),
                    onNoteClicked = { noteId ->
                        visibleNotes.find { it.id == noteId }?.let { note ->
                            if (selectedNotes.contains(note)) selectedNotes.remove(note)
                            else selectedNotes.add(note)
                        }
                    },
                    shape = shapeManager(
                        radius = settingsViewModel.settings.value.cornerRadius / 2,
                        isBoth = true
                    ),
                    notes = visibleNotes,
                    onNoteUpdate = {},
                    selectedNotes = selectedNotes,
                    viewMode = true,
                    isDeleteClicked = false,
                    animationFinished = {},
                    swipeEnabled = false
                )
            }
            }
        }
    }

    if (longPressedNote != null) {
        ModalBottomSheet(onDismissRequest = { longPressedNoteId = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = longPressedNote.name,
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        viewModel.restoreNote(longPressedNote)
                        longPressedNoteId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape()
                ) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.restore))
                }

                OutlinedButton(
                    onClick = {
                        pendingPermanentDeleteIds = listOf(longPressedNote.id)
                        longPressedNoteId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_forever))
                }
            }
        }
    }

    pendingPermanentDeleteIds?.let { ids ->
        val notes = trashedNotes.filter { it.id in ids }
        // A rotation while the dialog was open can leave ids for notes that have since been
        // restored or deleted elsewhere; showing an empty "delete?" prompt would be worse than
        // closing it.
        if (notes.isNotEmpty()) {
            val description = if (notes.size == 1) {
                notes.first().name
            } else {
                stringResource(R.string.confirm_delete)
            }
        AlertDialog(
                onDismissRequest = { pendingPermanentDeleteIds = null },
                title = { Text(stringResource(R.string.delete_forever)) },
                text = { Text(description) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            notes.forEach { viewModel.permanentlyDeleteNote(it) }
                            selectedNotes.clear()
                            pendingPermanentDeleteIds = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete_forever))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPermanentDeleteIds = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    if (longPressedAttachment != null) {
        ModalBottomSheet(onDismissRequest = { longPressedAttachmentId = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = longPressedAttachment.caption.ifBlank {
                        File(longPressedAttachment.path).name
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        viewModel.restoreAttachment(longPressedAttachment.id)
                        longPressedAttachmentId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape()
                ) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.restore))
                }

                OutlinedButton(
                    onClick = {
                        pendingPermanentDeleteAttachmentId = longPressedAttachment.id
                        longPressedAttachmentId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.delete_forever))
                }
            }
        }
    }

    pendingPermanentDeleteAttachmentId?.let { id ->
        // A rotation while the dialog is open can leave an id for a row that has since been
        // restored or emptied elsewhere; closing beats an empty prompt.
        if (trashedAttachments.any { it.id == id }) {
            AlertDialog(
                onDismissRequest = { pendingPermanentDeleteAttachmentId = null },
                title = { Text(stringResource(R.string.delete_forever)) },
                text = { Text(stringResource(R.string.attachment_delete_forever_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.permanentlyDeleteAttachment(id)
                            pendingPermanentDeleteAttachmentId = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete_forever))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingPermanentDeleteAttachmentId = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

/**
 * The picture half of the attachment trash: the same adaptive tile grid the library uses, with the
 * long-press behaviour the note grid has. Restoring or emptying one goes through the sheet on the
 * screen itself, so both attachment tabs behave identically.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashAttachmentsGrid(
    attachments: List<Attachment>,
    cornerRadius: Int,
    emptyText: String,
    onLongPress: (String) -> Unit
) {
    if (attachments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(attachments, key = { it.id }) { attachment ->
            val path = AttachmentRegistry.path(attachment.id)
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape((cornerRadius / 2).coerceAtLeast(8).dp))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onLongPress(attachment.id) }
                    )
            ) {
                if (path == null) {
                    MissingAttachment(radius = cornerRadius / 2, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = path,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** The recording half: full-width player cards, long-press for the same sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashAttachmentsList(
    attachments: List<Attachment>,
    cornerRadius: Int,
    emptyText: String,
    onLongPress: (String) -> Unit
) {
    if (attachments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(attachments, key = { it.id }) { attachment ->
            val path = AttachmentRegistry.path(attachment.id)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onLongPress(attachment.id) }
                    )
            ) {
                if (path == null) {
                    MissingAttachment(
                        radius = cornerRadius,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                } else {
                    AudioPlayerCard(
                        audioUri = path,
                        radius = cornerRadius,
                        caption = attachment.caption.takeIf { it.isNotBlank() }
                    )
                }
            }
        }
    }
}
