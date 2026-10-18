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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.components.CalButton
import com.ezpnix.writeon.presentation.components.CalculatorButton
import com.ezpnix.writeon.presentation.components.CopyButton
import com.ezpnix.writeon.presentation.components.MoreButton
import com.ezpnix.writeon.presentation.components.NavigationIcon
import com.ezpnix.writeon.presentation.components.NotesScaffold
import com.ezpnix.writeon.presentation.components.PreviewButton
import com.ezpnix.writeon.presentation.components.RedoButton
import com.ezpnix.writeon.presentation.components.SaveButton
import com.ezpnix.writeon.presentation.components.TxtButton
import com.ezpnix.writeon.presentation.components.UndoButton
import com.ezpnix.writeon.presentation.components.markdown.MarkdownText
import com.ezpnix.writeon.presentation.screens.edit.components.CustomIconButton
import com.ezpnix.writeon.presentation.screens.edit.components.CustomTextField
import com.ezpnix.writeon.presentation.screens.edit.components.NoteImageSaveHost
import com.ezpnix.writeon.presentation.screens.edit.components.TextFormattingToolbar
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false) ||
            !permissions.getOrDefault(Manifest.permission.WRITE_EXTERNAL_STORAGE, false)) {
            Toast.makeText(context, context.getString(R.string.msg_storage_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    val pagerState = rememberPagerState(initialPage = if (id == 0) 0 else 1, pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    // Leaving the editor while a text field still holds focus keeps the blinking caret on screen for
    // the whole exit animation. Dropping focus first makes it disappear immediately; the system back
    // gesture is handled the same way in PredictiveBackHost.
    val focusManager = LocalFocusManager.current
    val exitEditor = {
        focusManager.clearFocus(force = true)
        onClickBack()
    }

    NotesScaffold(
        topBar = { if (!settingsViewModel.settings.value.minimalisticMode) TopBar(pagerState, exitEditor, viewModel, settingsViewModel) },
        content = { PagerContent(pagerState, coroutineScope, viewModel, settingsViewModel, exitEditor) }
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
                enabled = pagerState.currentPage == 0,
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
    val context = LocalContext.current
    val showButtons = rememberSaveable { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hideOnKeyboard = settingsViewModel.settings.value.hideVisibilityButtonWhenKeyboard
    var fabVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(imeVisible, hideOnKeyboard) {
        fabVisible = if (hideOnKeyboard) !imeVisible else true
    }

    // The options panel is a full-screen overlay; back should dismiss it, not leave the editor.
    BackHandler(enabled = showButtons.value) { showButtons.value = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!imeVisible) fabVisible = true
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (viewModel.isDescriptionInFocus.value && settingsViewModel.settings.value.isMarkdownEnabled) {
                TextFormattingToolbar(viewModel)
            }

            MarkdownBox(
                isExtremeAmoled = settingsViewModel.settings.value.extremeAmoledMode,
                shape = shapeManager(
                    radius = settingsViewModel.settings.value.cornerRadius,
                    isFirst = true
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { viewModel.toggleIsDescriptionInFocus(it.isFocused) }
                    .padding(bottom = 8.dp),
                content = {
                    CustomTextField(
                        value = viewModel.noteDescription.value,
                        onValueChange = { viewModel.updateNoteDescription(it) },
                        modifier = Modifier.fillMaxSize(),
                        placeholder = stringResource(R.string.empty_description),
                        textStyle = TextStyle(
                            fontSize = settingsViewModel.settings.value.fontSize.sp,
                            fontFamily = LocalNoteFontFamily.current
                        )
                    )
                }
            )
        }

        if (showButtons.value) {
            val overlayColor = if (isSystemInDarkTheme()) {
                Color.Black.copy(alpha = 0.5f)
            } else {
                Color.Black.copy(alpha = 0.3f)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            showButtons.value = false
                        }
                    }
            )
        }

        AnimatedVisibility(
            visible = fabVisible && !showButtons.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(appCornerShape(CornerFraction.MEDIUM))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button) { showButtons.value = !showButtons.value }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = stringResource(R.string.cd_show_options),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.cd_options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(
            visible = showButtons.value,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 125.dp)
        ) {
            Card(
                shape = appCornerShape(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .wrapContentHeight()
                    .fillMaxWidth(0.95f)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CopyButton(viewModel) {
                        copyToClipboard(context, viewModel.noteDescription.value.text)
                        showButtons.value = false
                    }
                    CalButton()
                    PreviewButton(pagerState, coroutineScope) {
                        showButtons.value = false
                    }
                    CalculatorButton()
                    TxtButton(currentText = viewModel.noteDescription.value.text)
//                    BrowserButton()
//                    TranslateButton(viewModel) {
//                        copyToClipboard(context, viewModel.noteDescription.value.text)
//                        openTranslateApp(context, viewModel.noteDescription.value.text)
//                        showButtons.value = false
                }
            }
        }
    }
}


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

    Column(
        modifier = Modifier
            .padding(16.dp)
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
                        modifier = Modifier.fillMaxSize(),
                        fontSize = settingsViewModel.settings.value.fontSize.sp,
                        onContentChange = { viewModel.updateNoteDescription(TextFieldValue(text = it)) }
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
        elevation = CardDefaults.cardElevation(defaultElevation = if (!isExtremeAmoled) 6.dp else 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
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
        elevation = when {
            isExtremeAmoled || isMinimalistic -> 0.dp
            pagerState.currentPage != pageIndex -> 6.dp
            else -> 12.dp
        }
    )
}