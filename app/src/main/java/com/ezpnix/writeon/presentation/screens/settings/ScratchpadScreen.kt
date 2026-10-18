package com.ezpnix.writeon.presentation.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.navigation.NavController
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.theme.appCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScratchpadScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val originalNote = remember { settingsViewModel.loadNote() }
    var textState by remember { mutableStateOf(TextFieldValue(originalNote)) }
    val hasChanges = textState.text != originalNote

    val saveToFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(textState.text.toByteArray())
                    }
                    Toast.makeText(context, context.getString(R.string.msg_text_saved), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.msg_error_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    var showInfoPopup by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (hasChanges) showSaveDialog = true
        else navController.navigateUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(stringResource(R.string.quick_action_scratchpad))
                        IconButton(onClick = { showInfoPopup = !showInfoPopup }) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = stringResource(R.string.cd_info),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) showSaveDialog = true
                        else navController.navigateUp()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (undoStack.isNotEmpty()) {
                            redoStack.add(textState)
                            textState = undoStack.removeLast()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = context.getString(R.string.cd_undo), tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(onClick = {
                        Toast.makeText(context, context.getString(R.string.msg_exporting), Toast.LENGTH_SHORT).show()
                        saveToFileLauncher.launch("note.txt")
                    }) {
                        Icon(Icons.Rounded.AddComment, contentDescription = context.getString(R.string.save_as_txt), tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(onClick = {
                        if (redoStack.isNotEmpty()) {
                            undoStack.add(textState)
                            textState = redoStack.removeLast()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.cd_redo), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        content = { padding ->
            Box(modifier = Modifier.padding(padding)) {
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                ) {
                    BasicTextField(
                        value = textState,
                        onValueChange = {
                            undoStack.add(textState)
                            textState = it
                        },
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxSize(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (textState.text.isEmpty()) {
                                    Text(
                                        "Type here...",
                                        style = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }

                if (showInfoPopup) {
                    Popup(alignment = Alignment.TopCenter) {
                        Card(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            shape = appCornerShape(),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.scratchpad_about),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                if (showSaveDialog) {
                    AlertDialog(
                        onDismissRequest = { showSaveDialog = false },
                        title = { Text(context.getString(R.string.unsaved_changes_title)) },
                        text = { Text(context.getString(R.string.unsaved_changes_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                settingsViewModel.saveNote(textState.text)
                                Toast.makeText(context, context.getString(R.string.msg_note_saved), Toast.LENGTH_SHORT).show()
                                showSaveDialog = false
                                navController.navigateUp()
                            }) {
                                Text(context.getString(R.string.action_save))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showSaveDialog = false
                                navController.navigateUp()
                            }) {
                                Text(stringResource(R.string.action_discard))
                            }
                        }
                    )
                }
            }
        }
    )
}
