package com.ezpnix.writeon.presentation.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.domain.model.Note
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ezpnix.writeon.presentation.screens.home.viewmodel.HomeViewModel
import com.ezpnix.writeon.presentation.screens.home.widgets.NotesGrid
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
    val allSelected = trashedNotes.isNotEmpty() && selectedNotes.size == trashedNotes.size
    val longPressedNote = trashedNotes.firstOrNull { it.id == longPressedNoteId }

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
                            if (allSelected) {
                                selectedNotes.clear()
                            } else {
                                selectedNotes.clear()
                                selectedNotes.addAll(trashedNotes)
                            }
                        },
                        enabled = trashedNotes.isNotEmpty()
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
            } else {
                NotesGrid(
                    settingsViewModel = settingsViewModel,
                    containerColor = getContainerColor(settingsViewModel),
                    onNoteClicked = { noteId ->
                        trashedNotes.find { it.id == noteId }?.let { note ->
                            if (selectedNotes.contains(note)) selectedNotes.remove(note)
                            else selectedNotes.add(note)
                        }
                    },
                    shape = shapeManager(
                        radius = settingsViewModel.settings.value.cornerRadius / 2,
                        isBoth = true
                    ),
                    notes = trashedNotes,
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
