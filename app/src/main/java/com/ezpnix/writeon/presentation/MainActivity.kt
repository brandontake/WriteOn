package com.ezpnix.writeon.presentation

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.presentation.components.GalleryObserver
import com.ezpnix.writeon.presentation.components.registerGalleryObserver
import com.ezpnix.writeon.presentation.components.unregisterGalleryObserver
import com.ezpnix.writeon.presentation.lock.LockGate
import com.ezpnix.writeon.presentation.navigation.AppNavHost
import com.ezpnix.writeon.presentation.navigation.HomeOverlayState
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.navigation.PredictiveBackHost
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.share.ShareIntake
import com.ezpnix.writeon.presentation.share.readShareIntent
import com.ezpnix.writeon.presentation.theme.LeafNotesTheme
import com.ezpnix.writeon.presentation.theme.LocalCornerRadius
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import com.ezpnix.writeon.presentation.theme.NoteFontManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var settingsRepositoryImpl: SettingsRepositoryImpl
    private val settingsViewModel: SettingsViewModel by viewModels()

    // The observer outlives a single composition but not the activity: it used to be registered
    // from inside setContent on every recomposition, which piled up duplicate observers that each
    // held the activity and could delete files after it was gone.
    private var galleryObserver: GalleryObserver? = null

    override fun onStart() {
        super.onStart()
        if (galleryObserver == null) {
            galleryObserver = registerGalleryObserver(this)
        }
    }

    override fun onStop() {
        galleryObserver?.let { unregisterGalleryObserver(this, it) }
        galleryObserver = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask delivers later shares here instead of creating a second activity, so the
        // payload is parked and the running composition is asked to open a new note.
        setIntent(intent)
        readShareIntent(this, intent)?.let { ShareIntake.offer(it, navigate = true) }
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

        val noteId = intent?.getIntExtra("noteId", -1) ?: -1
        val isNewNoteShortcut = intent?.action == "com.ezpnix.writeon.action.NEW_NOTE" ||
                intent?.getBooleanExtra("new_note", false) == true

        // A share that starts the process: park the payload and land straight in the editor. The
        // editor asks ShareIntake for it when it is created, so a cold start needs no extra
        // navigation - the start destination below already is the editor.
        val sharedAtLaunch = readShareIntent(this, intent)
        if (sharedAtLaunch != null) {
            ShareIntake.offer(sharedAtLaunch, navigate = false)
        }

        lifecycleScope.launch {
            val termsAccepted = settingsRepositoryImpl.getBoolean("termsOfService") ?: false
            startup.complete(termsAccepted)

            val startDestination = when {
                !termsAccepted -> NavRoutes.Terms.route
                sharedAtLaunch != null -> NavRoutes.Edit.createRoute(0, false)
                isNewNoteShortcut -> NavRoutes.Edit.createRoute(0, false)
                noteId == -1 -> NavRoutes.Home.route
                else -> NavRoutes.Edit.route
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
                val predictiveBackEnabled = settingsViewModel.settings.value.predictiveBackEnabled
                // One provider for the whole app: every screen and component reads the radius the
                // user chose in Settings > Colours from this local, so no corner stays hard-coded.
                val cornerRadius = settingsViewModel.settings.value.cornerRadius
                val customFontFamily by NoteFontManager.getFontState(this@MainActivity)
                CompositionLocalProvider(
                    LocalCornerRadius provides cornerRadius.dp,
                    LocalNoteFontFamily provides customFontFamily
                ) {
                    LeafNotesTheme(settingsViewModel) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            LockGate {
                                val isAtRoot = backStackEntry?.destination?.route == startDestination
                                PredictiveBackHost(
                                    enabled = !isAtRoot,
                                    showPreview = predictiveBackEnabled,
                                    onBack = { navController.popBackStack() },
                                    // The home quick action bar, drawer, and note selection are overlays
                                    // on the root screen. Only when at the root destination can an overlay
                                    // suppress the predictive back host; when away from the root, sub-screens
                                    // (e.g. settings) must always be popped on system back gestures.
                                    isOverlayOpen = isAtRoot && HomeOverlayState.expanded
                                ) {
                                    AppNavHost(
                                        settingsViewModel = settingsViewModel,
                                        noteId = noteId,
                                        navController = navController,
                                        startDestination = startDestination
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}