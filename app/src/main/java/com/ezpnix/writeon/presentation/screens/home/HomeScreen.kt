package com.ezpnix.writeon.presentation.screens.home

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Note
import com.ezpnix.writeon.presentation.components.CalculatorUI
import com.ezpnix.writeon.presentation.components.CloseButton
import com.ezpnix.writeon.presentation.components.DeleteButton
import com.ezpnix.writeon.presentation.components.MainButton
import com.ezpnix.writeon.presentation.components.NotesScaffold
import com.ezpnix.writeon.presentation.components.PinButton
import com.ezpnix.writeon.presentation.components.SelectAllButton
import com.ezpnix.writeon.presentation.components.TitleText
import com.ezpnix.writeon.presentation.components.defaultScreenEnterAnimation
import com.ezpnix.writeon.presentation.components.defaultScreenExitAnimation
import com.ezpnix.writeon.presentation.navigation.HomeOverlayState
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.screens.home.viewmodel.HomeViewModel
import com.ezpnix.writeon.presentation.screens.home.widgets.NoteFilter
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager
import com.ezpnix.writeon.presentation.theme.appCornerShape
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.calendar.CalendarDialog
import com.maxkeppeler.sheets.calendar.models.CalendarConfig
import com.maxkeppeler.sheets.calendar.models.CalendarSelection
import com.maxkeppeler.sheets.calendar.models.CalendarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("DEPRECATION")
fun HomeView(
    viewModel: HomeViewModel = hiltViewModel(),
    settingsModel: SettingsViewModel,
    onSettingsClicked: () -> Unit,
    onNoteClicked: (Int, Boolean) -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    var showDimBackground by rememberSaveable { mutableStateOf(false) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // While notes are selected, the system back gesture should only exit the
    // selection, not navigate away from the home screen.
    BackHandler(enabled = viewModel.selectedNotes.isNotEmpty()) {
        viewModel.selectedNotes.clear()
    }
    // An expanded FAB is a modal overlay: back collapses it rather than leaving the screen. This
    // handler is declared after the selection one so it is the innermost while both are enabled.
    BackHandler(enabled = fabExpanded && viewModel.selectedNotes.isEmpty()) {
        fabExpanded = false
        showDimBackground = false
    }
    // A back *gesture* never reaches the two handlers above, so the expanded state is also published
    // to the activity's PredictiveBackHost. That handler is registered after the navigation library's
    // and therefore wins, which is what stops a swipe from finishing the activity at the root route.
    LaunchedEffect(fabExpanded) { HomeOverlayState.publish(fabExpanded) }
    DisposableEffect(Unit) {
        // Leaving the home screen (navigating into settings, the editor, ...) must not leave the
        // overlay marked as open, or the next back gesture on any screen would collapse nothing.
        onDispose { HomeOverlayState.collapse() }
    }
    // Filtering + sorting the whole note list is a derived value: it is recomputed only when one of
    // its inputs changes, instead of on every recomposition of HomeView (drawer open/close,
    // FAB expand, search focus, ...). `derivedStateOf` also skips recomposition when the result is
    // equal to the previous one.
    val displayedNotes by remember {
        derivedStateOf {
            viewModel.noteUseCase.notes
                .filter {
                    it.encrypted == viewModel.isVaultMode.value &&
                            (if (viewModel.currentFilter.value == "all") true
                            else if (viewModel.currentFilter.value == "pinned") it.pinned
                            else viewModel.currentFilter.value in it.tags)
                }
                .sortedWith(sorter(settingsModel.settings.value.sortDescending))
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Material 3 drawer: a shaped ModalDrawerSheet whose end corners follow the user's radius
        // setting (the same value the settings screen uses), with standard NavigationDrawerItem
        // rows so the active filter gets the MD3 selection highlight.
        drawerContent = {
            val drawerCornerRadius = settingsModel.settings.value.cornerRadius
            ModalDrawerSheet(
                modifier = Modifier.width(250.dp),
                drawerShape = RoundedCornerShape(
                    topEnd = drawerCornerRadius.dp,
                    bottomEnd = drawerCornerRadius.dp
                ),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    DrawerSectionHeader(context.getString(R.string.nav_homepage))
                    DrawerItem(
                        text = context.getString(R.string.nav_all_notes),
                        icon = Icons.Default.NoteAlt,
                        selected = viewModel.currentFilter.value == "all",
                        cornerRadius = drawerCornerRadius
                    ) {
                        viewModel.currentFilter.value = "all"
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem(
                        text = context.getString(R.string.nav_pinned_notes),
                        icon = Icons.Rounded.PushPin,
                        selected = viewModel.currentFilter.value == "pinned",
                        cornerRadius = drawerCornerRadius
                    ) {
                        viewModel.currentFilter.value = "pinned"
                        scope.launch { drawerState.close() }
                    }
                    DrawerItem(
                        text = context.getString(R.string.nav_trash),
                        icon = Icons.Default.Delete,
                        selected = false,
                        cornerRadius = drawerCornerRadius
                    ) {
                        navController.navigate(NavRoutes.Trash.route)
                        scope.launch { drawerState.close() }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    DrawerSectionHeader(context.getString(R.string.nav_tags))
                    if (viewModel.noteUseCase.tags.isEmpty()) {
                        Text(
                            text = context.getString(R.string.tag_none_yet),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                        )
                    } else {
                        viewModel.noteUseCase.tags.forEach { tag ->
                            DeletableDrawerItem(
                                text = tag,
                                selected = viewModel.currentFilter.value == tag,
                                cornerRadius = drawerCornerRadius,
                                onClick = {
                                    viewModel.currentFilter.value = tag
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteClick = {
                                    viewModel.deleteTag(tag)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        },
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                NotesScaffold(
                    topBar = {
                        AnimatedVisibility(
                            visible = viewModel.selectedNotes.isNotEmpty(),
                            enter = defaultScreenEnterAnimation(),
                            exit = defaultScreenExitAnimation()
                        ) {
                            SelectedNotesTopAppBar(
                                selectedNotes = viewModel.selectedNotes,
                                allNotes = displayedNotes,
                                settingsModel = settingsModel,
                                onPinClick = { viewModel.pinOrUnpinNotes() },
                                onMoveToTrashClick = { viewModel.moveSelectedNotesToTrash() },
                                onSelectAllClick = { selectAllNotes(viewModel, displayedNotes) },
                                onCloseClick = { viewModel.selectedNotes.clear() },
                                onTagClick = { viewModel.showTagDialog.value = true },
                                onRemoveTagClick = { viewModel.showRemoveTagDialog.value = true }
                            )
                        }
                        AnimatedVisibility(
                            visible = viewModel.selectedNotes.isEmpty(),
                            enter = defaultScreenEnterAnimation(),
                            exit = defaultScreenExitAnimation()
                        ) {
                            HomeViewTopBarWithSearch(
                                query = viewModel.searchQuery.value,
                                onQueryChange = { viewModel.changeSearchQuery(it) },
                                onClearClick = { viewModel.changeSearchQuery("") },
                                onSettingsClick = onSettingsClicked,
                                navController = navController,
                                onCloseSearch = { viewModel.changeSearchQuery("") },
                                cornerRadius = settingsModel.settings.value.cornerRadius,
                                onMenuClick = {
                                    scope.launch {
                                        if (drawerState.isClosed) drawerState.open()
                                        else drawerState.close()
                                    }
                                }
                            )
                        }
                    },
                    content = {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            NoteFilter(
                                modifier = Modifier.weight(1f),
                                navController = navController,
                                settingsViewModel = settingsModel,
                                containerColor = getContainerColor(settingsModel),
                                shape = shapeManager(
                                    radius = settingsModel.settings.value.cornerRadius / 2,
                                    isBoth = true
                                ),
                                onNoteClicked = { onNoteClicked(it, viewModel.isVaultMode.value) },
                                notes = displayedNotes,
                                selectedNotes = viewModel.selectedNotes,
                                viewMode = settingsModel.settings.value.viewMode,
                                searchText = viewModel.searchQuery.value.ifBlank { null },
                                isDeleteMode = viewModel.isDeleteMode.value,
                                onNoteUpdate = { note ->
                                    scope.launch(Dispatchers.IO) {
                                        viewModel.noteUseCase.addNote(
                                            note
                                        )
                                    }
                                },
                                onDeleteNote = {
                                    val note = displayedNotes.find { note -> note.id == it }
                                    note?.let { n -> viewModel.noteUseCase.moveToTrash(n) }
                                },
                                onPinNote = { note -> viewModel.pinOrUnpinNote(note) }
                            )
                        }
                    }
                )
                if (showDimBackground) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable {
                                showDimBackground = false
                                fabExpanded = false
                            }
                    )
                }
                FloatingBottomButtons(
                    navController = navController,
                    onNoteClicked = { onNoteClicked(it, viewModel.isVaultMode.value) },
                    expanded = fabExpanded,
                    onExpandedChange = { fabExpanded = it },
                    onDimToggle = { showDimBackground = it },
                    settingsModel = settingsModel
                )
            }
            if (viewModel.showTagDialog.value) {
                AlertDialog(
                    onDismissRequest = { viewModel.showTagDialog.value = false },
                    title = { Text(stringResource(R.string.tag_add)) },
                    text = {
                        TextField(
                            value = viewModel.newTagName.value,
                            onValueChange = { viewModel.newTagName.value = it },
                            label = { Text(stringResource(R.string.tag_name_hint)) }
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.addTagToSelectedNotes()
                            viewModel.showTagDialog.value = false
                            viewModel.newTagName.value = ""
                        }) {
                            Text(stringResource(R.string.action_add))
                        }
                    },
                    dismissButton = {
                        Button(onClick = { viewModel.showTagDialog.value = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
            if (viewModel.showRemoveTagDialog.value) {
                val uniqueTags = viewModel.selectedNotes.flatMap { it.tags }.distinct()
                if (uniqueTags.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = {
                            viewModel.showRemoveTagDialog.value = false
                            viewModel.selectedTagsToRemove.clear()
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (viewModel.selectedTagsToRemove.isNotEmpty()) {
                                    viewModel.removeTagsFromSelectedNotes()
                                    viewModel.showRemoveTagDialog.value = false
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tag_select_at_least_one),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) {
                                Text(stringResource(R.string.action_remove))
                            }
                        },
                        dismissButton = {
                            Button(onClick = {
                                viewModel.showRemoveTagDialog.value = false
                                viewModel.selectedTagsToRemove.clear()
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                        title = { Text(context.getString(R.string.tag_remove_many)) },
                        text = {
                            Column {
                                uniqueTags.forEach { tag ->
                                    var isChecked by remember(tag) {
                                        mutableStateOf(
                                            viewModel.selectedTagsToRemove.contains(
                                                tag
                                            )
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            // toggleable (not clickable) so TalkBack announces
                                            // the row as checked/unchecked rather than as a button.
                                            .toggleable(
                                                value = isChecked,
                                                role = Role.Checkbox,
                                                onValueChange = { checked ->
                                                    isChecked = checked
                                                    viewModel.selectedTagsToRemove.apply {
                                                        if (checked && tag !in this) add(tag)
                                                        else if (!checked && tag in this) remove(tag)
                                                    }
                                                }
                                            )
                                            .padding(vertical = 4.dp)
                                    ) {
                                        // The row carries the toggle semantics; this box only mirrors
                                        // the state, so it must not be a second focusable control.
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null
                                        )
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                } else {
                    viewModel.showRemoveTagDialog.value = false
                    Toast.makeText(context, context.getString(R.string.tag_none_to_remove), Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
}

@Composable
fun DeletableDrawerItem(
    text: String,
    selected: Boolean,
    cornerRadius: Int,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = text.replaceFirstChar { it.uppercase() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        selected = selected,
        onClick = onClick,
        icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
        badge = {
            IconButton(onClick = { onDeleteClick() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.tag_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        shape = RoundedCornerShape(cornerRadius.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
    )
    HorizontalDivider(
        modifier = Modifier.padding(top = 4.dp),
        thickness = DividerDefaults.Thickness,
        color = DividerDefaults.color
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun DrawerItem(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    cornerRadius: Int,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        shape = RoundedCornerShape(cornerRadius.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeViewTopBarWithSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearClick: () -> Unit,
    onSettingsClick: () -> Unit,
    navController: NavController,
    onCloseSearch: () -> Unit,
    onMenuClick: () -> Unit,
    cornerRadius: Int
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Back closes the search bar instead of leaving the screen. Registered after HomeView's own
    // selection handler, so it wins while it is enabled.
    BackHandler(enabled = searchActive) {
        searchActive = false
        onCloseSearch()
        focusManager.clearFocus()
    }
    // Focusing the field activates the bar on its own (see the onFocusChanged below); the magnifier is
    // only an alternative way in, and stays for users who prefer an explicit target.
    // Material3's SearchBar (material3 1.3.0) lays its 56.dp input field out at a fixed offset from
    // the top of the box it is given (topPadding = unconsumedTopInset + 8.dp), reports
    // constraints.maxHeight once fully active and snaps between its two container heights, so the
    // text could never stay centred and the state switch was abrupt. The bar is therefore
    // hand-composed from a Surface + BasicTextField: the field fills the bar and centres its own
    // content, and the height, container colour and icons all animate smoothly.
    val barHeight by animateDpAsState(
        targetValue = if (searchActive) 72.dp else 56.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "SearchBarHeight"
    )
    val barColor by animateColorAsState(
        targetValue = if (searchActive) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(durationMillis = 250),
        label = "SearchBarColor"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        // The gap to the title row collapses as the bar grows, so activating the search visibly
        // lifts the bar towards the status bar instead of only changing its colour.
        verticalArrangement = Arrangement.spacedBy(if (searchActive) 0.dp else 8.dp)
    ) {
        AnimatedVisibility(
            visible = !searchActive,
            enter = fadeIn(tween(200)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { onMenuClick() }) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.open_drawer)
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                IconButton(onClick = { onSettingsClick() }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.screen_settings)
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(cornerRadius.dp),
            color = barColor,
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = searchActive,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.85f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(targetScale = 0.85f))
                    },
                    label = "SearchBarLeadingIcon"
                ) { active ->
                    if (active) {
                        IconButton(onClick = {
                            searchActive = false
                            onCloseSearch()
                            focusManager.clearFocus()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    } else {
                        MainButton { navController.navigate(NavRoutes.ColorStyles.route) }
                    }
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(focusRequester)
                        // Tapping the field is the same gesture as tapping the magnifier: focusing it
                        // activates the bar (grows it, swaps the leading icon, hides the button), so
                        // the user no longer has to find the icon to start searching.
                        .onFocusChanged { if (it.isFocused) searchActive = true },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                AnimatedVisibility(
                    visible = query.isNotBlank(),
                    enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f)
                ) {
                    CloseButton(
                        contentDescription = stringResource(R.string.action_clear),
                        onCloseClicked = onClearClick
                    )
                }
                AnimatedVisibility(
                    visible = !searchActive,
                    enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f)
                ) {
                    IconButton(onClick = { searchActive = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.activate_search)
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus() else focusManager.clearFocus()
    }
}

@Composable
fun getContainerColor(settingsModel: SettingsViewModel): Color {
    return if (settingsModel.settings.value.extremeAmoledMode) Color.Black
    else MaterialTheme.colorScheme.surfaceContainerHigh
}

@SuppressLint("RememberReturnType")
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun FloatingBottomButtons(
    navController: NavController,
    onNoteClicked: (Int) -> Unit,
    onDimToggle: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    settingsModel: SettingsViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var textState by rememberSaveable { mutableStateOf("") }
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "ScaleAnimation"
    )
    val calendarState = rememberUseCaseState()
    val selectedDates = remember { mutableStateOf<List<LocalDate>>(listOf()) }
    val disabledDates = listOf(LocalDate.now())
    // The stable ids below are what gets persisted; the labels are display only, so they are free to
    // change with the app language (or an update) without losing the user's selection.
    val allFabItems = listOf(
        FabItem("browser", Icons.Default.Search, context.getString(R.string.quick_action_browser)) {
            val url = "https://www.startpage.com"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            try {
                context.startActivity(intent)
                Toast.makeText(context, context.getString(R.string.msg_opening_browser), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.msg_no_browser_found), Toast.LENGTH_SHORT).show()
            }
        },
        FabItem("flashcard", Icons.Default.Dataset, context.getString(R.string.quick_action_flashcard)) { navController.navigate(NavRoutes.Flashback.route) },
        FabItem("save_txt", Icons.Default.AddComment, context.getString(R.string.quick_action_save_txt)) { showDialog = true },
        FabItem("scratchpad", Icons.Default.NoteAlt, context.getString(R.string.quick_action_scratchpad)) { navController.navigate(NavRoutes.Scratchpad.route) },
        FabItem("calculator", Icons.Default.Calculate, context.getString(R.string.quick_action_calculator)) { showCalculator = true },
        FabItem("calendar", Icons.Default.CalendarMonth, stringResource(R.string.quick_action_calendar)) { calendarState.show() },
        FabItem("trash", Icons.Default.Delete, stringResource(R.string.trash)) { navController.navigate(NavRoutes.Trash.route) },
        FabItem("add_note", Icons.Default.Edit, stringResource(R.string.add_note)) { onNoteClicked(0) }
    )
    val visibleItems by settingsModel.visibleFabItems.collectAsStateWithLifecycle()
    val fabItems = allFabItems.filter { visibleItems.contains(it.id) } +
            FabItem("modify", Icons.Default.Settings, stringResource(R.string.quick_action_modify)) { showEditDialog = true }
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(textState.toByteArray())
                    }
                    Toast.makeText(context, context.getString(R.string.msg_text_saved), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.msg_error_with_message, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
    CalendarDialog(
        state = calendarState,
        config = CalendarConfig(
            yearSelection = true,
            monthSelection = true,
            style = CalendarStyle.MONTH,
            disabledDates = disabledDates
        ),
        selection = CalendarSelection.Dates { selectedDates.value = it }
    )
    if (showCalculator) {
        AlertDialog(
            onDismissRequest = { showCalculator = false },
            title = { Text(stringResource(R.string.quick_action_calculator)) },
            text = { CalculatorUI() },
            confirmButton = {
                Button(onClick = { showCalculator = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.quick_export)) },
            text = {
                TextField(
                    value = textState,
                    onValueChange = { textState = it },
                    label = { Text(stringResource(R.string.enter_text)) }
                )
            },
            confirmButton = {
                Button(onClick = {
                    openFileLauncher.launch("rename.txt")
                    showDialog = false
                }) {
                    Text(stringResource(R.string.save_as_txt))
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.customize_quick_actions)) },
            text = {
                Column {
                    allFabItems.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = item.id in visibleItems,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        settingsModel.updateVisibleFabItems(
                                            if (checked) visibleItems + item.id else visibleItems - item.id
                                        )
                                    }
                                )
                                .padding(8.dp)
                        ) {
                            // The row above is already toggleable, so this one only mirrors the state visually; a nested
                            // control would otherwise be announced twice by TalkBack.
                            Checkbox(
                                checked = item.id in visibleItems,
                                onCheckedChange = null
                            )
                            Text(
                                text = item.label,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The pill's padding used to be 8.dp, which left the two 56.dp buttons almost touching the edge of
        // the capsule and 12.dp apart from each other: the icons looked cramped inside it. The padding
        // is now generous enough for the shape to read as a pill, and the row gap has grown with it.
        val fabSize = 56.dp
        val pillPadding = 18.dp
        val spacing = 16.dp
        val totalWidth = fabSize * 2 + spacing + pillPadding * 2
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(totalWidth)
        ) {
            fabItems.forEach { item ->
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    SmallFAB(
                        icon = item.icon,
                        description = item.label,
                        onClick = {
                            onExpandedChange(false)
                            onDimToggle(false)
                            item.action()
                        }
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(appCornerShape())
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    .padding(pillPadding)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    FloatingActionButton(
                        onClick = { onNoteClicked(0) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(fabSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.add_note),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    FloatingActionButton(
                        onClick = {},
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(fabSize)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .pointerInteropFilter {
                                when (it.action) {
                                    MotionEvent.ACTION_DOWN -> isPressed = true
                                    MotionEvent.ACTION_UP -> {
                                        isPressed = false
                                        onExpandedChange(!expanded)
                                        onDimToggle(!expanded)
                                    }
                                    MotionEvent.ACTION_CANCEL -> isPressed = false
                                }
                                true
                            }
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * One entry of the floating button bar.
 *
 * [id] is the stable value that gets persisted (SettingsModel.visibleFabItems) and [label] is only
 * shown to the user, so a translation change can never invalidate a saved selection.
 */
data class FabItem(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val action: () -> Unit
)

@Composable
fun SmallFAB(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = appCornerShape(),
        color = MaterialTheme.colorScheme.primary,
        tonalElevation = 6.dp,
        modifier = Modifier
            .height(48.dp)
            .fillMaxWidth()
            .clip(appCornerShape())
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectedNotesTopAppBar(
    selectedNotes: List<Note>,
    allNotes: List<Note>,
    settingsModel: SettingsViewModel,
    onPinClick: () -> Unit,
    onMoveToTrashClick: () -> Unit,
    onSelectAllClick: () -> Unit,
    onCloseClick: () -> Unit,
    onTagClick: () -> Unit,
    onRemoveTagClick: () -> Unit
) {
    var deleteAlert by rememberSaveable { mutableStateOf(false) }
    if (deleteAlert) {
        AlertDialog(
            onDismissRequest = { deleteAlert = false },
            title = { Text(text = stringResource(id = R.string.alert_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAlert = false
                        onMoveToTrashClick()
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.yes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAlert = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            }
        )
    }
    TopAppBar(
        modifier = Modifier
            .padding(bottom = 36.dp)
            .clip(
                RoundedCornerShape(
                    bottomStart = (settingsModel.settings.value.cornerRadius / 2).dp,
                    bottomEnd = (settingsModel.settings.value.cornerRadius / 2).dp
                )
            ),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        title = { TitleText(titleText = selectedNotes.size.toString()) },
        navigationIcon = {
            // CloseButton hardcodes its tint, so an inline IconButton keeps the
            // icon inheriting the bar's onSecondaryContainer color.
            IconButton(onClick = onCloseClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close)
                )
            }
        },
        actions = {
            Row {
                DeleteButton(onClick = { deleteAlert = true })
                Spacer(modifier = Modifier.width(5.dp))
                PinButton(isPinned = selectedNotes.all { it.pinned }, onClick = onPinClick)
                Spacer(modifier = Modifier.width(5.dp))
                IconButton(onClick = onTagClick) {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.tag_add))
                }
                Spacer(modifier = Modifier.width(5.dp))
                if (selectedNotes.any { it.tags.isNotEmpty() }) {
                    IconButton(onClick = onRemoveTagClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.LabelOff,
                            contentDescription = stringResource(R.string.tag_remove_one)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(5.dp))
                SelectAllButton(enabled = selectedNotes.size != allNotes.size, onClick = onSelectAllClick)
            }
        }
    )
}

private fun selectAllNotes(viewModel: HomeViewModel, allNotes: List<Note>) {
    val updatedSelection = allNotes.filterNot { it in viewModel.selectedNotes }
    viewModel.selectedNotes.addAll(updatedSelection)
}

fun sorter(descending: Boolean): Comparator<Note> {
    return if (descending) {
        compareByDescending { it.createdAt }
    } else {
        compareBy { it.createdAt }
    }
}