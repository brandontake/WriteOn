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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ezpnix.writeon.presentation.navigation.BackGestureRouter
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
    // DAT-10: load once, then ask whether the stored value could be opened at all. An
    // undecryptable scratchpad used to come back as "" - an empty editor whose next save wrote
    // over the only copy of the old text.
    val originalNote = remember { settingsViewModel.loadNote() }
    val unreadable = settingsViewModel.scratchpadUnreadable
    // The in-progress text has to survive a configuration change or process death: an unsaved
    // scratchpad used to live only in a `remember`, so a rotation wiped it without any prompt.
    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(originalNote))
    }
    val hasChanges = textState.text != originalNote

    // R3-03/R4-06: a SAF picker is a separate Activity; navigating away while it is open drops its
    // result. The session-close effect below defers until the picker returns. The flags are
    // `rememberSaveable`: a rotation recreates this composition while the picker is still in
    // front, and a plain `remember` would lose "an export is in flight" and close the screen
    // underneath it.
    var exportInFlight by rememberSaveable { mutableStateOf(false) }
    var sessionEndedDuringExport by rememberSaveable { mutableStateOf(false) }

    val saveToFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            exportInFlight = false
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
            // Re-entering composition after the picker lets the pending session close run; if the
            // session is still alive this flag is ignored.
            if (sessionEndedDuringExport) sessionEndedDuringExport = false
        }
    )

    // R4-06: one picker at a time, and a failed launch must release the flag. A double tap used to
    // start two pickers; the first result then cleared the flag while the second picker was still
    // open, and a `launch()` throw left `exportInFlight` true forever - a Vault session end could
    // then never close this screen.
    val startExport: () -> Unit = export@{
        if (exportInFlight) return@export
        exportInFlight = true
        Toast.makeText(context, context.getString(R.string.msg_exporting), Toast.LENGTH_SHORT).show()
        try {
            saveToFileLauncher.launch(context.getString(R.string.default_txt_file_name))
        } catch (error: Exception) {
            exportInFlight = false
            Toast.makeText(
                context,
                context.getString(R.string.msg_error_with_message, error.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    var showInfoPopup by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    // R2-05/SEC-09: this screen's text is local composable state, so clearing the ViewModel's
    // fields did nothing for it. When a Vault session that was active while this screen was open
    // ends, persist the draft (it is sealed with the Keystore key) and leave instead of keeping
    // the plaintext on screen. The same happens when an app lock engages.
    val vaultActive by settingsViewModel.vaultSessionActive.collectAsStateWithLifecycle()
    val appLocked by settingsViewModel.appLocked.collectAsStateWithLifecycle()
    // R5-06: an activity recreation while the SAF picker is open also recreates this flag. A
    // plain `remember` lost "a session was active here", so the deferred close never ran after
    // the picker returned.
    var sessionWasActive by rememberSaveable { mutableStateOf(false) }
    var closingBySession by remember { mutableStateOf(false) }
    LaunchedEffect(vaultActive, appLocked, exportInFlight, sessionEndedDuringExport) {
        if (vaultActive && !appLocked) {
            sessionWasActive = true
            sessionEndedDuringExport = false
            return@LaunchedEffect
        }
        if (!sessionWasActive && !appLocked) return@LaunchedEffect
        if (exportInFlight) {
            // The user is in the system file picker. Close after its result arrives.
            sessionEndedDuringExport = true
            return@LaunchedEffect
        }
        if (closingBySession) return@LaunchedEffect
        closingBySession = true
        val saved = if (!unreadable && textState.text != originalNote) {
            // R3-03: do not clear the draft unless the write actually landed. A Keystore failure
            // used to leave an empty editor with the user's text gone and no recovery path.
            settingsViewModel.saveNote(textState.text)
        } else {
            true
        }
        if (!saved) {
            // Keep textState/undo/redo. saveNote already showed its error toast; the exit dialog
            // remains available and the text is still in the editor.
            closingBySession = false
            return@LaunchedEffect
        }
        undoStack.clear()
        redoStack.clear()
        textState = TextFieldValue("")
        showSaveDialog = false
        sessionWasActive = false
        navController.navigateUp()
    }

    // The hardware back button and the toolbar arrow share one exit path, so the "Save changes?"
    // dialog is the only way out while the text differs from the stored value.
    val requestExit: () -> Unit = exit@{
        if (closingBySession) return@exit
        if (hasChanges) showSaveDialog = true
        else navController.navigateUp()
    }
    // Hardware back only: a predictive back *gesture* goes to the activity dispatcher, so the
    // screen's BackHandler never sees it. PredictiveBackHost asks BackGestureRouter first, and
    // registering here keeps the same dialog in charge of the gesture instead of a bare pop.
    BackHandler(onBack = requestExit)

    // Same registration pattern as EditScreen: publish on every recomposition so the lambda sees
    // the current hasChanges value, and on dispose restore the previous owner only if this screen
    // still owns the slot (a successor screen may already have registered).
    val previousGestureHandler = remember { BackGestureRouter.handler }
    val gestureHandlerSlot = remember { mutableStateOf<(() -> Unit)?>(null) }
    SideEffect {
        gestureHandlerSlot.value = requestExit
        BackGestureRouter.handler = requestExit
    }
    DisposableEffect(Unit) {
        onDispose {
            if (BackGestureRouter.handler === gestureHandlerSlot.value) {
                BackGestureRouter.handler = previousGestureHandler
            }
        }
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
                    IconButton(onClick = requestExit) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (undoStack.isNotEmpty()) {
                            redoStack.add(textState)
                            textState = undoStack.removeAt(undoStack.lastIndex)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = context.getString(R.string.cd_undo), tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(onClick = startExport) {
                        Icon(Icons.Rounded.AddComment, contentDescription = context.getString(R.string.save_as_txt), tint = MaterialTheme.colorScheme.primary)
                    }

                    IconButton(onClick = {
                        if (redoStack.isNotEmpty()) {
                            undoStack.add(textState)
                            textState = redoStack.removeAt(redoStack.lastIndex)
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
                    // The editor is deliberately still readable (selection and copy work), but it
                    // is read-only and saving is refused: the original ciphertext must survive.
                    if (unreadable) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = appCornerShape()
                        ) {
                            Text(
                                text = stringResource(R.string.restore_fix_scratchpad_unreadable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    BasicTextField(
                        value = textState,
                        onValueChange = {
                            undoStack.add(textState)
                            textState = it
                        },
                        readOnly = unreadable,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxSize(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (textState.text.isEmpty()) {
                                    Text(
                                        stringResource(R.string.scratchpad_placeholder),
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
                            elevation = CardDefaults.cardElevation(0.dp)
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
                            TextButton(
                                enabled = !unreadable,
                                onClick = {
                                    // saveNote refuses while the stored value is unreadable, so
                                    // the confirmation is only claimed for a real write.
                                    if (settingsViewModel.saveNote(textState.text)) {
                                        Toast.makeText(context, context.getString(R.string.msg_note_saved), Toast.LENGTH_SHORT).show()
                                        showSaveDialog = false
                                        navController.navigateUp()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.restore_fix_scratchpad_save_blocked), Toast.LENGTH_LONG).show()
                                    }
                                }
                            ) {
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
