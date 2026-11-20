package com.ezpnix.writeon.presentation.screens.edit

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ezpnix.writeon.BuildConfig
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.components.CalButton
import com.ezpnix.writeon.presentation.components.CalculatorButton
import com.ezpnix.writeon.presentation.components.CopyButton
import com.ezpnix.writeon.presentation.components.LocalAppSnackbar
import com.ezpnix.writeon.presentation.components.MoreButton
import com.ezpnix.writeon.presentation.components.NavigationIcon
import com.ezpnix.writeon.presentation.components.NotesScaffold
import com.ezpnix.writeon.presentation.components.PreviewButton
import com.ezpnix.writeon.presentation.components.RedoButton
import com.ezpnix.writeon.presentation.components.SaveButton
import com.ezpnix.writeon.presentation.components.TxtButton
import com.ezpnix.writeon.presentation.components.UndoButton
import com.ezpnix.writeon.presentation.components.markdown.MarkdownText
import com.ezpnix.writeon.presentation.navigation.BackGestureRouter
import com.ezpnix.writeon.presentation.screens.edit.components.CustomIconButton
import com.ezpnix.writeon.presentation.screens.edit.components.CustomTextField
import com.ezpnix.writeon.presentation.screens.edit.components.NoteImageSaveHost
import com.ezpnix.writeon.presentation.screens.edit.components.ScrollableNoteField
import com.ezpnix.writeon.presentation.screens.edit.components.SegmentedEditor
import com.ezpnix.writeon.presentation.screens.edit.components.TextFormattingToolbar
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
import com.ezpnix.writeon.presentation.screens.edit.model.SaveState
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager
import com.ezpnix.writeon.presentation.screens.settings.widgets.ActionType
import com.ezpnix.writeon.presentation.screens.settings.widgets.SettingsBox
import com.ezpnix.writeon.presentation.screens.settings.widgets.copyToClipboard
import com.ezpnix.writeon.presentation.theme.appCornerShape
import com.ezpnix.writeon.presentation.theme.CornerFraction
import com.ezpnix.writeon.presentation.theme.LocalCornerRadius
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Compiled once; `split` on it is both cheaper and identical to the previous `"\\s+".toRegex()`. */
private val WHITESPACE = "\\s+".toRegex()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditNoteView(
    id: Int,
    settingsViewModel: SettingsViewModel,
    encrypted: Boolean = false,
    onClickBack: () -> Unit
) {
    val viewModel: EditViewModel = hiltViewModel()
    val context = LocalContext.current

    // Both of these used to run on every recomposition, and setupNoteData launches a collector in
    // viewModelScope, so each keystroke that changed composition state started another collector on
    // the same note. They all raced to write the same text fields, and none of them were ever
    // cancelled. Keying the work on what the screen is actually showing means a recomposition does
    // nothing at all, while opening a different note still reloads it. Moving the call here also
    // stops it from overwriting the encrypted flag that syncNote just set from the stored note.
    LaunchedEffect(id, encrypted) {
        viewModel.updateIsEncrypted(encrypted)
        viewModel.setupNoteData(id)
    }
    ObserveLifecycleEvents(viewModel)

    val pagerState = rememberPagerState(initialPage = if (id == 0) 0 else 1, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    // Leaving the editor while a text field still holds focus keeps the blinking caret on screen for
    // the whole exit animation. Dropping focus first makes it disappear immediately; the system back
    // gesture is handled the same way in PredictiveBackHost.
    val focusManager = LocalFocusManager.current

    // The global snackbar outlives this destination, which is what makes it usable for the save
    // that happens on the way out (U2).
    val snackbar = LocalAppSnackbar.current
    val undoMessage = stringResource(R.string.msg_note_saved)
    val trashMessage = stringResource(R.string.editor_fix_moved_to_trash)
    val undoLabel = stringResource(R.string.cd_undo)

    // Save-then-leave (UX-1/DAT-9): every explicit navigation callback waits for the write before
    // popping the destination, so the last paragraph is committed while the indicator is still on
    // screen. A failed write keeps the editor open with a retryable error instead of leaving
    // silently. The back *gesture* reaches this same lambda through [BackGestureRouter] (see
    // below), so it no longer falls back to the host's bare pop; even if it did, that write is
    // NonCancellable and no longer runs on viewModelScope, so the teardown could not cancel it.
    var isLeaving by remember { mutableStateOf(false) }
    // Set when the user actually tried to leave and the save was refused by a locked vault. The
    // unlock dialog then knows that a successful unlock should finish the exit instead of merely
    // closing. A dialog that appeared from an ON_STOP save leaves this false, so "Unlock and save"
    // keeps the editor open, which is what the user asked for by unlocking.
    var exitAfterUnlock by remember { mutableStateOf(false) }
    val exitEditor: () -> Unit = {
        if (!isLeaving) {
            isLeaving = true
            focusManager.clearFocus(force = true)
            coroutineScope.launch {
                val tokenBefore = viewModel.undoToken
                if (viewModel.saveBeforeExit()) {
                    // The undo snackbar has to be shown before the pop: the LaunchedEffect below is
                    // disposed with this destination, so a token change observed only after
                    // navigation would race the pop and usually lose the snackbar entirely.
                    if (viewModel.undoToken != tokenBefore && viewModel.canUndoSave) {
                        snackbar?.show(
                            message = if (viewModel.lastSaveMovedToTrash) trashMessage else undoMessage,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Long,
                            onAction = { PendingSaveUndo.undo() },
                        )
                    }
                    onClickBack()
                } else {
                    // Stay on the note. A locked vault is resolved in place through the dialog
                    // below; a write failure stays visible in the status line with Retry.
                    exitAfterUnlock = viewModel.saveState.value == SaveState.VaultLocked
                    isLeaving = false
                }
            }
        }
    }
    // Three-button back on destinations the predictive host does not own still needs this screen's
    // save-then-leave path rather than the navigation callback's bare pop.
    BackHandler(onBack = exitEditor)

    // V-3: the *gesture* never reaches the BackHandler above. PredictiveBackHost owns the gesture
    // and used to pop this destination directly, which skipped saveBeforeExit - the same lost-last-
    // paragraph window that save-then-leave exists to close, and for a vault note (no draft, SEC-7)
    // the only copy. Publishing this screen's exit through the router makes the gesture take the
    // normal path, including the vault unlock dialog. Updated on every recomposition because the
    // lambda closes over the current save-state; the slot is cleared on dispose so a gesture after
    // this destination is gone cannot call back into it.
    val gestureHandlerSlot = remember { mutableStateOf<(() -> Unit)?>(null) }
    SideEffect {
        gestureHandlerSlot.value = exitEditor
        BackGestureRouter.handler = exitEditor
    }
    DisposableEffect(Unit) {
        onDispose {
            // Identity check: when another editor has already registered during a transition,
            // clearing unconditionally would strip the new destination's gesture handler.
            if (BackGestureRouter.handler === gestureHandlerSlot.value) {
                BackGestureRouter.handler = null
            }
        }
    }

    val undoToken = viewModel.undoToken
    val movedToTrash = viewModel.lastSaveMovedToTrash
    LaunchedEffect(undoToken) {
        // `isLeaving` skips the exit save, which shows its own snackbar in exitEditor; without it
        // the same save could pop the snackbar twice (dismiss + re-show).
        if (!isLeaving && undoToken > 0 && viewModel.canUndoSave) {
            snackbar?.show(
                // UX-2: clearing the body moves the note to the recycle bin. Reusing "Note Saved!"
                // there made a deliberate move look like a silent delete.
                message = if (movedToTrash) trashMessage else undoMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
                onAction = { PendingSaveUndo.undo() },
            )
        }
    }

    CompositionLocalProvider(
        LocalSplitPreview provides (
            shouldSplitPreview() && settingsViewModel.settings.value.isMarkdownEnabled
            )
    ) {
        NotesScaffold(
            topBar = { if (!settingsViewModel.settings.value.minimalisticMode) TopBar(pagerState, exitEditor, viewModel, settingsViewModel) },
            content = { PagerContent(pagerState, coroutineScope, viewModel, settingsViewModel, exitEditor) }
        )
    }

    // V-3: shown over the pager, not inside the editor page, so a lock that happens while the
    // preview page is on screen cannot hide the one action that keeps the text.
    VaultUnlockDialog(
        viewModel = viewModel,
        coroutineScope = coroutineScope,
        onSaved = {
            if (exitAfterUnlock) {
                exitAfterUnlock = false
                // Reuse the normal exit: the text is committed by now, so saveBeforeExit returns
                // immediately and the usual focus/snackbar handling still applies.
                exitEditor()
            }
        },
        onDiscard = {
            exitAfterUnlock = false
            // Same focus handling as the normal exit: leaving a focused field keeps a blinking
            // caret over the page for the whole pop animation.
            focusManager.clearFocus(force = true)
            // Pop without saving. discardVaultLockedSave already made the fields look saved to the
            // ON_STOP that follows this pop, so the discarded text cannot be written later.
            onClickBack()
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopBarActions(pagerState: PagerState, onClickBack: () -> Unit, viewModel: EditViewModel, cornerRadius: Int = 32) {
    val context = LocalContext.current
    val isPinned = viewModel.isPinned.value

    fun triggerPinNotification() {
        NoteNotificationManager.showPinnedNotification(
            context,
            viewModel.noteName.value.text.ifBlank { context.getString(R.string.notif_untitled_note) },
            viewModel.noteDescription.value.text.ifBlank { context.getString(R.string.notif_no_content) }
        )
        viewModel.toggleNotePin(true)
        Toast.makeText(context, context.getString(R.string.notif_pinned_to), Toast.LENGTH_SHORT).show()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerPinNotification()
        } else {
            Toast.makeText(context, context.getString(R.string.notif_pin_denied), Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPinWithPermissionCheck() {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context, context.getString(R.string.notif_pin_denied), Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                triggerPinNotification()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            triggerPinNotification()
        }
    }

    when (pagerState.currentPage) {

        0 -> {
            Row {
                if (viewModel.isDescriptionInFocus.value) {
                    RedoButton { viewModel.redo() }
                }
                SaveButton { onClickBack() }
            }
        }
        1 -> {
            Row {
                MoreButton {
                    viewModel.toggleEditMenuVisibility(true)
                }
                DropdownMenu(
                    expanded = viewModel.isEditMenuVisible.value,
                    onDismissRequest = { viewModel.toggleEditMenuVisibility(false) },
                    shape = RoundedCornerShape((cornerRadius / 2).dp)
                ) {
                    if (viewModel.noteId.value != 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pin_to_notification)) },
                            leadingIcon = {
                                Icon(
                                    if (viewModel.isPinned.value) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = stringResource(R.string.pin_to_notification)
                                )
                            },
                            onClick = {
                                if (viewModel.isPinned.value) {
                                    NoteNotificationManager.cancelPinnedNotification(context)
                                    viewModel.toggleNotePin(false)
                                    Toast.makeText(context, context.getString(R.string.notif_unpinned), Toast.LENGTH_SHORT).show()
                                } else {
                                    requestPinWithPermissionCheck()
                                }
                                viewModel.toggleEditMenuVisibility(false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.note_details)) },
                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.cd_details)) },
                            onClick = {
                                viewModel.toggleEditMenuVisibility(false)
                                viewModel.toggleNoteInfoVisibility(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.save_as_image)) },
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = stringResource(R.string.save_as_image)) },
                            onClick = {
                                viewModel.requestImageSave()
                                viewModel.toggleEditMenuVisibility(false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagerContent(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    viewModel: EditViewModel,
    settingsViewModel: SettingsViewModel,
    onClickBack: () -> Unit
) {
    if (viewModel.isImageSaveRequested.value) {
        val settings = settingsViewModel.settings.value
        NoteImageSaveHost(
            noteTitle = viewModel.noteName.value.text.ifBlank { stringResource(R.string.notif_untitled_note) },
            noteBody = viewModel.noteDescription.value.text,
            createdAt = viewModel.noteCreatedTime.value,
            isMarkdownEnabled = settings.isMarkdownEnabled,
            fontSize = settings.fontSize,
            containerColor = if (settings.extremeAmoledMode) Color.Black else MaterialTheme.colorScheme.surface,
            cornerRadius = settings.cornerRadius.dp,
            onFinished = { viewModel.clearImageSaveRequest() }
        )
    }

    if (isSplitPreviewActive()) {
        // Tablet in landscape: the editor and its rendering are both permanent, so the preview is
        // always the current text instead of a page you swipe to. The pager is not removed - the
        // rest of the screen still reads `pagerState` - it is simply never shown, and page 0 stays
        // current, which is what the top bar and the undo button already expect.
        Row(modifier = Modifier.fillMaxSize().imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                EditScreen(viewModel, coroutineScope, settingsViewModel, pagerState, onClickBack)
            }
            VerticalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LivePreviewPane(viewModel, settingsViewModel)
            }
        }
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.imePadding(),
        ) { page ->
            when (page) {
                0 -> EditScreen(viewModel, coroutineScope, settingsViewModel, pagerState, onClickBack)
                1 -> PreviewScreen(viewModel, settingsViewModel, pagerState, onClickBack)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TopBar(pagerState: PagerState, onClickBack: () -> Unit, viewModel: EditViewModel, settingsViewModel: SettingsViewModel) {
    val titleShape = RoundedCornerShape((settingsViewModel.settings.value.cornerRadius / 2).dp)
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        title = {
            CustomTextField(
                value = viewModel.noteName.value,
                onValueChange = { viewModel.updateNoteName(it) },
                placeholder = stringResource(R.string.untitled),
                shape = titleShape,
                enabled = pagerState.currentPage == 0 || isSplitPreviewActive(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        navigationIcon = {
            Row {
                NavigationIcon(onClickBack)
                if (pagerState.currentPage == 0 && viewModel.isDescriptionInFocus.value) {
                    UndoButton { viewModel.undo() }
                }
            }
        },
        actions = { TopBarActions(pagerState, onClickBack, viewModel, settingsViewModel.settings.value.cornerRadius) }
    )
}

@Composable
fun ObserveLifecycleEvents(viewModel: EditViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNote(viewModel.noteId.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomModal(viewModel: EditViewModel, settingsViewModel: SettingsViewModel) {
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = { viewModel.toggleNoteInfoVisibility(false) }
    ) {
        // Both of these used to be rebuilt on every recomposition: SimpleDateFormat allocates a
        // calendar clone each time and is not thread safe, and the regex was recompiled for the
        // whole note body on every frame.
        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        val description = viewModel.noteDescription.value.text
        // Same tokenisation as before (`split` on runs of whitespace), but the compiled regex is
        // now shared and the split only reruns when the text actually changes.
        val wordCount = remember(description) {
            if (description.isEmpty()) 0 else description.split(WHITESPACE).size
        }

        Column(
            modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 20.dp)
        ) {
            SettingsBox(
                isBig = false,
                title = stringResource(R.string.created_time),
                icon = Icons.Rounded.Numbers,
                actionType = ActionType.TEXT,
                radius = shapeManager(isFirst = true, radius = settingsViewModel.settings.value.cornerRadius),
                customText = sdf.format(viewModel.noteCreatedTime.value).toString()
            )
            val updatedTime = viewModel.noteUpdatedTime.value
            SettingsBox(
                isBig = false,
                title = stringResource(R.string.updated_time),
                icon = Icons.Rounded.Numbers,
                actionType = ActionType.TEXT,
                radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius),
                customText = if (updatedTime > 0L) sdf.format(updatedTime).toString() else "-"
            )
            SettingsBox(
                isBig = false,
                title = stringResource(R.string.words),
                icon = Icons.Rounded.Numbers,
                radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius),
                actionType = ActionType.TEXT,
                customText = wordCount.toString()
            )
            SettingsBox(
                isBig = false,
                title = stringResource(R.string.characters),
                icon = Icons.Rounded.Numbers,
                actionType = ActionType.TEXT,
                radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isLast = true),
                customText = viewModel.noteDescription.value.text.length.toString()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MinimalisticMode(
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    viewModel: EditViewModel,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    isEnabled: Boolean,
    pagerState: PagerState,
    isExtremeAmoled: Boolean,
    showOnlyDescription: Boolean = false,
    onClickBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    Row(
        verticalAlignment = alignment,
        modifier = modifier
            .fillMaxWidth()
            .then(if (showOnlyDescription) Modifier.padding(top = 8.dp) else Modifier)
    ) {
        if (!showOnlyDescription) {
            if (isEnabled) NavigationIcon(onClickBack)
            if (isEnabled && viewModel.isDescriptionInFocus.value) UndoButton { viewModel.undo() }
            content()
            if (isEnabled) TopBarActions(pagerState, onClickBack, viewModel)
            if (isEnabled) ModeButton(pagerState, coroutineScope, isMinimalistic = true, isExtremeAmoled = isExtremeAmoled)
        } else {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEnabled) NavigationIcon(onClickBack)
                    Spacer(modifier = Modifier.weight(1f))
                    if (isEnabled) ModeButton(pagerState, coroutineScope, isMinimalistic = true, isExtremeAmoled = isExtremeAmoled)
                    if (isEnabled) TopBarActions(pagerState, onClickBack, viewModel)
                }
                content()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    coroutineScope: CoroutineScope,
    settingsViewModel: SettingsViewModel,
    pagerState: PagerState,
    onClickBack: () -> Unit
) {
    val settings = settingsViewModel.settings.value
    // The formatting bar follows the keyboard: it only matters while the body has focus, and on a
    // phone the lines it costs are worth reclaiming the rest of the time.
    val showFormattingBar = viewModel.isDescriptionInFocus.value && settings.isMarkdownEnabled

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                // A tablet should not stretch a paragraph across the whole window: the readable
                // line length stays put and the editor is centred instead.
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(16.dp)
        ) {
        // The save state sits directly above the writing surface so "Saving…", "Saved" and a
        // retryable failure are visible without leaving the page (UX-1). It renders nothing in the
        // idle state for a plain note, so it costs no height in the common case.
        SaveStatusIndicator(viewModel)

        // The five quick actions used to hide behind a floating "Options" button that covered the
        // bottom of the note. They now sit on one always-visible row above the note - the place the
        // formatting toolbar used to occupy - and the formatting toolbar moved down to the bottom,
        // where the options button used to be.
        QuickActionRow(viewModel, coroutineScope, pagerState)

        // The roadmap's two gates: the code has to be compiled in and the user has to opt in. The
        // preference now defaults to on (2.0.2): attachments render as blocks unless the user
        // turned the toggle off, and the BuildConfig flag stays as the kill switch.
        val useSegmentedEditor = BuildConfig.FEATURE_ATTACHMENT_LIVE_RENDER &&
            settings.liveAttachmentPreview
        val bodyModifier = Modifier
            .weight(1f)
            .padding(bottom = 8.dp)

        MarkdownBox(
            isExtremeAmoled = settingsViewModel.settings.value.extremeAmoledMode,
            shape = shapeManager(
                radius = settingsViewModel.settings.value.cornerRadius,
                isFirst = true
            ),
            modifier = bodyModifier,
            content = {
                if (useSegmentedEditor) {
                    SegmentedEditor(
                        viewModel = viewModel,
                        fontSize = settings.fontSize.sp,
                        fontFamily = LocalNoteFontFamily.current,
                        radius = settings.cornerRadius,
                        placeholder = stringResource(R.string.empty_description),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ScrollableNoteField(
                        value = viewModel.noteDescription.value,
                        onValueChange = { viewModel.updateNoteDescription(it) },
                        placeholder = stringResource(R.string.empty_description),
                        textStyle = TextStyle(
                            fontSize = settings.fontSize.sp,
                            fontFamily = LocalNoteFontFamily.current
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onFocusChanged = { viewModel.toggleIsDescriptionInFocus(it) }
                    )
                }
            }
        )

        AnimatedVisibility(
            visible = showFormattingBar,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = appCornerShape(CornerFraction.MEDIUM),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                TextFormattingToolbar(
                    viewModel = viewModel,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        }
    }
}

/**
 * Compact save status for the editor (UX-1).
 *
 * The meaningful states are rendered as text next to the note instead of a Toast, so a write that
 * is still in flight, one that finished, one that failed with a retry, and one that was refused by
 * a locked vault all stay legible. [SaveState.Error] is not dismissible: leaving with a failed
 * write is exactly the data loss this indicator exists to prevent, so Retry is the only action it
 * offers. [SaveState.VaultLocked] needs the dialog instead of a Retry, because no number of retries
 * can produce the missing passphrase.
 */
@Composable
private fun SaveStatusIndicator(viewModel: EditViewModel) {
    val state = viewModel.saveState.value
    val movedToTrash = viewModel.lastSaveMovedToTrash

    if (state == SaveState.Idle) {
        // SEC-7: vault notes deliberately have no SavedStateHandle draft, so a process death
        // cannot recover them. Saying so where the save state normally appears is better than
        // letting the user discover it after the text is gone.
        if (viewModel.isEncrypted.value) {
            Text(
                text = stringResource(R.string.editor_fix_vault_draft_disabled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
        return
    }
    // "Saved" must not survive the next keystroke: the fields no longer match the stored row.
    if (state == SaveState.Saved && viewModel.isDirty) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 4.dp)
    ) {
        when (state) {
            SaveState.Saving -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.editor_fix_saving),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SaveState.Saved -> Text(
                // UX-2: the note was saved and moved to the recycle bin; naming only the save
                // would hide the second, surprising half of the operation.
                text = stringResource(
                    if (movedToTrash) R.string.editor_fix_moved_to_trash else R.string.editor_fix_saved
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (movedToTrash) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            is SaveState.Error -> {
                Text(
                    text = stringResource(state.messageRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false)
                )
                TextButton(onClick = { viewModel.retrySave() }) {
                    Text(text = stringResource(R.string.editor_fix_retry))
                }
            }
            // V-3: a Retry cannot succeed without the passphrase, so the indicator names the
            // problem and leaves the resolving action to the dialog. It stays visible under the
            // dialog so the state is not implied only by a modal.
            SaveState.VaultLocked -> Text(
                text = stringResource(R.string.editor_lock_fix_vault_locked_status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            SaveState.Idle -> Unit
        }
    }
}

/**
 * V-3: the save was refused because the vault passphrase is no longer loaded.
 *
 * Shown while [SaveState.VaultLocked] is the current state and deliberately not dismissible: a tap
 * outside or a back press would otherwise close it and leave the user on an editor whose text has
 * nowhere to go - the exact silent loss this exists to stop (a vault note has no draft, SEC-7). The
 * two honest exits are an unlock that verifies, and an explicit discard.
 *
 * The password is Compose state only, never [androidx.lifecycle.SavedStateHandle], so a
 * configuration change clears it instead of putting the vault key in the saved-instance bundle.
 *
 * For a brand-new vault note there is no stored ciphertext to check a password against, so the
 * dialog explains that the vault must be unlocked from Home and offers only the discard (see
 * [EditViewModel.unlockVaultAndRetry]).
 */
@Composable
private fun VaultUnlockDialog(
    viewModel: EditViewModel,
    coroutineScope: CoroutineScope,
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (viewModel.saveState.value != SaveState.VaultLocked) return

    val cannotVerify = viewModel.vaultUnlockUnverifiable
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        // No dismiss path: see the KDoc. Android back and a scrim tap both land here.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.editor_lock_fix_vault_locked_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (cannotVerify) {
                            R.string.editor_lock_fix_vault_new_note
                        } else {
                            R.string.editor_lock_fix_vault_locked_message
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!cannotVerify) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.editor_lock_fix_vault_password)) },
                        singleLine = true,
                        enabled = !submitting,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (viewModel.vaultUnlockError.value) {
                    Text(
                        text = stringResource(R.string.editor_lock_fix_vault_wrong_password),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (!cannotVerify) {
                TextButton(
                    enabled = password.isNotEmpty() && !submitting,
                    onClick = {
                        submitting = true
                        // The destination's scope, not one created here: the dialog leaves
                        // composition as soon as the retry save reports Saving, and a
                        // dialog-scoped coroutine would be cancelled before it could navigate.
                        coroutineScope.launch {
                            val saved = viewModel.unlockVaultAndRetry(password)
                            submitting = false
                            password = ""
                            if (saved) onSaved()
                        }
                    }
                ) {
                    Text(stringResource(R.string.editor_lock_fix_unlock_and_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.discardVaultLockedSave()
                    onDiscard()
                }
            ) {
                Text(stringResource(R.string.editor_lock_fix_discard_and_exit))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickActionRow(
    viewModel: EditViewModel,
    coroutineScope: CoroutineScope,
    pagerState: PagerState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CopyButton(viewModel) {}
        CalButton()
        // In the split layout the rendering is already on screen, so the button that used to swipe
        // to it would do nothing visible.
        if (!isSplitPreviewActive()) {
            PreviewButton(pagerState, coroutineScope) {}
        }
        CalculatorButton()
        TxtButton(currentText = viewModel.noteDescription.value.text)
    }
}

/**
 * Whether the tablet split view is on. Resolved once per screen ([shouldSplitPreview] plus the
 * Markdown preference) and published through a local, because it is read by the canvas, the top bar
 * and the quick-action row: a half-width duplicate of a plain-text editor would just be the same
 * text twice, so the preference has to take part in the decision, not only the screen shape.
 */
internal val LocalSplitPreview = compositionLocalOf { false }

/** Geometry only: a tablet held in landscape, where a permanent second column fits. */
@Composable
internal fun shouldSplitPreview(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= 600 &&
        configuration.screenWidthDp > configuration.screenHeightDp
}

@Composable
internal fun isSplitPreviewActive(): Boolean = LocalSplitPreview.current

/**
 * The right half of the tablet layout: the same renderer the preview page and the image export use,
 * fed from the editor's own text.
 *
 * The text is sampled with a short debounce because [MarkdownText] parses the whole body: without it
 * every keystroke of a long note would reparse the entire document, and typing would compete with
 * layout for the main thread. The initial value is the current text, so opening the screen shows the
 * note immediately instead of waiting for the first pause.
 */
@Composable
private fun LivePreviewPane(viewModel: EditViewModel, settingsViewModel: SettingsViewModel) {
    val settings = settingsViewModel.settings.value
    val body = viewModel.noteDescription.value.text
    val previewScrollState = rememberScrollState()
    var rendered by remember { mutableStateOf(body) }
    LaunchedEffect(body) {
        delay(LIVE_PREVIEW_DEBOUNCE_MS)
        rendered = body
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        MarkdownBox(
            isExtremeAmoled = settings.extremeAmoledMode,
            shape = shapeManager(radius = settings.cornerRadius, isFirst = true),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    if (rendered.isBlank()) {
                        Text(
                            text = stringResource(R.string.empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MarkdownText(
                        radius = settings.cornerRadius,
                        markdown = rendered,
                        isEnabled = settings.isMarkdownEnabled,
                        // A selection handle that was dragged across a lazy item whose composable
                        // has since been disposed can crash in SelectionManager. The preview is
                        // one note, not a feed, so keeping every element composed and scrolling a
                        // plain column is both safer and cheap enough.
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(previewScrollState),
                        fontSize = settings.fontSize.sp,
                        onContentChange = { viewModel.updateNoteDescriptionFromRendered(it) },
                        onAttachmentDelete = { viewModel.trashAttachmentById(it) },
                        useLazyColumn = false
                    )
                }
            }
        )
    }
}

private const val LIVE_PREVIEW_DEBOUNCE_MS = 120L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewScreen(
    viewModel: EditViewModel,
    settingsViewModel: SettingsViewModel,
    pagerState: PagerState,
    onClickBack: () -> Unit
) {
    if (viewModel.isNoteInfoVisible.value) BottomModal(viewModel, settingsViewModel)

    val focusManager = LocalFocusManager.current
    focusManager.clearFocus()
    val coroutineScope = rememberCoroutineScope()
    val previewScrollState = rememberScrollState()

    // Same readable line length as the editor: on a wide screen the preview keeps a capped column
    // instead of stretching a short note across the whole tablet.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val horizontalPadding = if (screenWidthDp > 880) ((screenWidthDp - 840) / 2).dp else 16.dp

    Column(
        modifier = Modifier
            .padding(horizontal = horizontalPadding, vertical = 16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        MarkdownBox(
            isExtremeAmoled = settingsViewModel.settings.value.extremeAmoledMode,
            shape = shapeManager(
                radius = settingsViewModel.settings.value.cornerRadius,
                isFirst = true
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            content = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    if (viewModel.noteDescription.value.text.isBlank()) {
                        Text(
                            text = stringResource(R.string.empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MarkdownText(
                        radius = settingsViewModel.settings.value.cornerRadius,
                        markdown = viewModel.noteDescription.value.text,
                        isEnabled = settingsViewModel.settings.value.isMarkdownEnabled,
                        // See LivePreviewPane: no lazy list under SelectionContainer, so dragging
                        // a handle after scrolling never targets a disposed text anchor.
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(previewScrollState),
                        fontSize = settingsViewModel.settings.value.fontSize.sp,
                        onContentChange = { viewModel.updateNoteDescriptionFromRendered(it) },
                        onAttachmentDelete = { viewModel.trashAttachmentById(it) },
                        useLazyColumn = false
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(appCornerShape(CornerFraction.MEDIUM))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button) {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.edit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}


@Composable
fun MarkdownBox(
    isExtremeAmoled: Boolean,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp),
    maxHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        shape = shape,
        modifier = modifier
            .clip(shape)
            // Only cap the height when the caller did not already give us one. Both call sites pass
            // weight(1f), and an unconditional heightIn(max = 128.dp) was applied after it, so the
            // editor body never grew past about six lines no matter how much room the screen had.
            // When no cap is wanted the card fills exactly what its parent gives it.
            .let { base ->
                if (maxHeight != null) base.heightIn(max = maxHeight) else base
            }
            .background(MaterialTheme.colorScheme.surface),
        // The editor body used to float above the page on a 6dp shadow. A flat surface with the
        // surrounding container colour reads as one sheet, which is the Material 3 intent.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isExtremeAmoled) Color.Black else MaterialTheme.colorScheme.surface
        )
    ) {
        content()
    }
    Spacer(modifier = Modifier.height(3.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModeButton(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    isMinimalistic: Boolean = false,
    isExtremeAmoled: Boolean = false,
) {
    Row {
        if (!isMinimalistic) {
            RenderButton(
                pagerState,
                coroutineScope,
                0,
                Icons.Rounded.Edit,
                false,
                isExtremeAmoled
            )
            RenderButton(
                pagerState,
                coroutineScope,
                1,
                Icons.Rounded.Description,
                false,
                isExtremeAmoled
            )
        } else {
            val currentPage = pagerState.currentPage
            val icon = if (currentPage == 1) Icons.Rounded.Edit else Icons.Rounded.RemoveRedEye
            RenderButton(pagerState, coroutineScope, if (currentPage == 1) 0 else 1, icon, true, isExtremeAmoled)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RenderButton(
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    pageIndex: Int,
    icon: ImageVector,
    isMinimalistic: Boolean,
    isExtremeAmoled: Boolean
) {
    CustomIconButton(
        shape = if (isMinimalistic) {
            // A pill by design: once the radius passes half the button a larger value changes
            // nothing, so a fixed large radius keeps the floating minimalistic button round at
            // every radius setting.
            RoundedCornerShape(100)
        } else {
            val radius = LocalCornerRadius.current
            if (pageIndex == 0) RoundedCornerShape(topStart = radius, bottomStart = radius)
            else RoundedCornerShape(bottomEnd = radius, topEnd = radius)
        },
        onClick = {
            coroutineScope.launch {
                pagerState.animateScrollToPage(pageIndex)
            }
        },
        icon = icon,
        // Depth is expressed by the tonal container the button sits on; a per-page shadow made the
        // three page buttons look like a pile of floating chips.
        elevation = 0.dp
    )
}