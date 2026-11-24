package com.ezpnix.writeon.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.util.RestoreCoordinator
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.presentation.components.markdown.LocalLatexEnabled
import com.ezpnix.writeon.presentation.lock.LockGate
import com.ezpnix.writeon.presentation.navigation.AppNavHost
import com.ezpnix.writeon.presentation.navigation.BackGestureRouter
import com.ezpnix.writeon.presentation.navigation.HomeOverlayState
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.navigation.PredictiveBackHost
import com.ezpnix.writeon.presentation.screens.edit.NoteNotificationManager
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.share.ShareIntake
import com.ezpnix.writeon.presentation.share.readShareIntent
import com.ezpnix.writeon.presentation.theme.LeafNotesTheme
import com.ezpnix.writeon.presentation.theme.LocalCornerRadius
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import com.ezpnix.writeon.presentation.theme.NoteFontManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var settingsRepositoryImpl: SettingsRepositoryImpl
    private val settingsViewModel: SettingsViewModel by viewModels()

    /**
     * UX-13: a pinned-note tap that arrives while the activity is alive (singleTask).
     *
     * The cold-start case needs no state - its intent already selects the start destination - but a
     * warm tap has to be handed to the running composition, which owns the NavController. The value
     * is consumed by the effect that navigates, so a recomposition cannot reopen the note twice.
     */
    private val pendingNoteOpen = MutableStateFlow<Int?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask delivers later shares here instead of creating a second activity, so the
        // payload is parked and the running composition is asked to open a new note.
        setIntent(intent)
        // UX-13: a tap on a pinned note's notification. This is not a share: the share read below
        // would return null anyway, but returning early keeps the two paths visibly separate.
        val pinnedNoteId = intent.getIntExtra(NoteNotificationManager.EXTRA_NOTE_ID, -1)
        if (pinnedNoteId > 0) {
            pendingNoteOpen.value = pinnedNoteId
            return
        }
        // P-10: importing a shared image copies up to 100 MB out of the sender's URI. Doing that
        // in onNewIntent blocked the main thread for the whole copy; the cold-start path already
        // moved it to IO, and the warm path has to do the same.
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                readShareIntent(this@MainActivity, intent)
            }
            payload?.let { ShareIntake.offer(it, navigate = true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

        // Reading the persisted terms flag goes through DataStore (file read + protobuf parse).
        // It used to run inside runBlocking on the main thread before the first frame, so every
        // cold start paid that disk latency. The read now happens on the main dispatcher's
        // coroutine queue, and the splash screen is held until the value arrives, which keeps the
        // first composed frame from showing the wrong start destination.
        val startup = CompletableDeferred<Boolean>()
        splashScreen.setKeepOnScreenCondition { !startup.isCompleted }

        // UX-13: a pinned notification carries the note id (the same extra the widgets use), so a
        // cold start can land straight in that note. The extra is consumed here: a configuration
        // change re-runs onCreate with the same intent, and the start destination below must not
        // keep pointing at a note the user has long left.
        //
        // REG-02: this value is only read to build the start destination. The nav graph encodes the
        // id in the route, so every later Edit destination uses its own argument instead of
        // inheriting the notification's note (which used to make every note opened after the cold
        // start load the one from the notification).
        val noteId = intent?.getIntExtra(NoteNotificationManager.EXTRA_NOTE_ID, -1) ?: -1
        if (noteId > 0) intent?.removeExtra(NoteNotificationManager.EXTRA_NOTE_ID)
        val isNewNoteShortcut = intent?.action == "com.ezpnix.writeon.action.NEW_NOTE" ||
                intent?.getBooleanExtra("new_note", false) == true

        // A share that starts the process: park the payload and land straight in the editor. The
        // editor asks ShareIntake for it when it is created, so a cold start needs no extra
        // navigation - the start destination below already is the editor.
        //
        // The read is also the copy: every shared image is pulled out of the sender's content URI
        // into our own files dir (up to 100 MB). That copy used to run here on the main thread
        // before the first frame, so a photo share froze the launch and a large one could ANR. It
        // now runs on IO, and the splash screen covers it because `startup` is not completed until
        // the start destination is decided - so the cold-start share still opens the editor and
        // nothing arrives after the window is already on screen.
        val launchIntent = intent

        lifecycleScope.launch {
            val sharedAtLaunch = withContext(Dispatchers.IO) {
                readShareIntent(this@MainActivity, launchIntent)
            }
            if (sharedAtLaunch != null) {
                ShareIntake.offer(sharedAtLaunch, navigate = false)
            }

            val termsAccepted = settingsRepositoryImpl.getBoolean("termsOfService") ?: false
            startup.complete(termsAccepted)

            val startDestination = when {
                !termsAccepted -> NavRoutes.Terms.route
                sharedAtLaunch != null -> NavRoutes.Edit.createRoute(0, false)
                isNewNoteShortcut -> NavRoutes.Edit.createRoute(0, false)
                noteId == -1 -> NavRoutes.Home.route
                // A concrete route, not the "edit/{id}/{encrypted}" template. The template bound no
                // arguments, so a notification deep-link started on a route that could never match
                // and the editor showed nothing. The encrypted flag is corrected by syncNote as
                // soon as the row is read; false is only the pre-load value.
                else -> NavRoutes.Edit.createRoute(noteId, false)
            }

            setContent {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                // A share that arrives while the activity is already alive cannot move the start
                // destination, so ShareIntake counts those and this effect opens one new note per
                // share. Cold-start shares never bump the counter, so they do not land here twice.
                val shareRequests = ShareIntake.navigationRequest
                LaunchedEffect(shareRequests) {
                    if (shareRequests > 0) {
                        navController.navigate(NavRoutes.Edit.createRoute(0, false))
                    }
                }
                // UX-13: a warm tap on a pinned-note notification is handled by the effect inside
                // LockGate below: a locked cold start has no NavHost yet, and a navigation there
                // would throw instead of waiting for the unlock.
                val predictiveBackEnabled = settingsViewModel.settings.value.predictiveBackEnabled
                // One provider for the whole app: every screen and component reads the radius the
                // user chose in Settings > Colours from this local, so no corner stays hard-coded.
                val cornerRadius = settingsViewModel.settings.value.cornerRadius
                val renderLatex = settingsViewModel.settings.value.renderLatex
                val customFontFamily by NoteFontManager.getFontState(this@MainActivity)
                CompositionLocalProvider(
                    LocalCornerRadius provides cornerRadius.dp,
                    LocalNoteFontFamily provides customFontFamily,
                    LocalLatexEnabled provides renderLatex
                ) {
                    LeafNotesTheme(settingsViewModel) {
                        // DAT-7: a restore closes the process-wide Room connection before it
                        // swaps the database file, so every screen's Flow is about to query a
                        // closed connection. This covers the whole app - editor and settings
                        // included - from the moment the swap starts until the process restarts
                        // (or the restore fails and the coordinator reopens the app).
                        val restoring by RestoreCoordinator.restoring.collectAsStateWithLifecycle()
                        // System back must not leave the overlay: there is no screen to go back
                        // to while the database underneath it is being replaced.
                        BackHandler(enabled = restoring) {
                            // Consumed on purpose.
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                LockGate {
                                    // UX-13: a warm tap on a pinned-note notification navigates to
                                    // that note. Declared inside the gate so it only runs once the
                                    // NavHost exists (a locked cold start has none yet) and so the
                                    // request waits behind the lock screen instead of bypassing it.
                                    val pendingNote by pendingNoteOpen.collectAsStateWithLifecycle()
                                    LaunchedEffect(pendingNote) {
                                        val id = pendingNote ?: return@LaunchedEffect
                                        navController.navigate(NavRoutes.Edit.createRoute(id, false))
                                        pendingNoteOpen.value = null
                                    }
                                    // Compare destination ids, not route strings: a start destination built
                                    // by createRoute() is a filled path ("edit/0/false"), while the graph
                                    // stores the template ("edit/{id}/{encrypted}"), so the old comparison
                                    // was never true for the share/shortcut entry points and left them on
                                    // the predictive-back root path (where back does nothing).
                                    val rootDestinationId = runCatching {
                                        navController.graph.startDestinationId
                                    }.getOrNull()
                                    val isAtRoot = rootDestinationId != null &&
                                        backStackEntry?.destination?.id == rootDestinationId
                                    PredictiveBackHost(
                                        enabled = !isAtRoot,
                                        showPreview = predictiveBackEnabled,
                                        // V-3: a destination that owns a save-before-exit path (the
                                        // editor) publishes it through the router, so the gesture runs
                                        // the same save-then-leave as the toolbar and hardware back
                                        // instead of a bare pop that loses the last unsaved paragraph.
                                        // Nothing registered means the normal navigation pop.
                                        onBack = {
                                            if (!BackGestureRouter.handle() &&
                                                !navController.popBackStack()
                                            ) {
                                                finish()
                                            }
                                        },
                                        // The home quick action bar, drawer, and note selection are overlays
                                        // on the root screen. Only when at the root destination can an overlay
                                        // suppress the predictive back host; when away from the root, sub-screens
                                        // (e.g. settings) must always be popped on system back gestures.
                                        isOverlayOpen = isAtRoot && HomeOverlayState.expanded
                                    ) {
                                        AppNavHost(
                                            settingsViewModel = settingsViewModel,
                                            navController = navController,
                                            startDestination = startDestination
                                        )
                                    }
                                }
                            }
                            if (restoring) {
                                RestoreInProgressOverlay()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * DAT-7: the full-screen block shown while the database is closed and replaced.
 *
 * It is a sibling of the navigation content, not a decoration inside a screen: a restore is
 * triggered from Settings but the screen underneath it (and every Flow it collects) is exactly
 * what must not run a query until the process restarts. The pointer-input loop consumes every
 * gesture so taps and scrolls cannot reach the editor behind an opaque Surface.
 *
 * The overlay plus `NoteUseCase`'s own collector pause cover the normal Settings-triggered
 * restore. An editor that is somehow already composed collects its note flow directly, outside
 * the shared list, and is not cancelled by the coordinator - it is covered visually here, but it
 * is not the screen a restore starts from.
 */
@Composable
private fun RestoreInProgressOverlay() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.restore_fix_restoring),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}