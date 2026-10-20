package com.ezpnix.writeon.presentation.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.domain.model.Note
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
}
