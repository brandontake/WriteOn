package com.ezpnix.writeon.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.glance.appwidget.updateAll
import androidx.hilt.navigation.compose.hiltViewModel
import com.ezpnix.writeon.R
import com.ezpnix.writeon.data.repository.SettingsRepositoryImpl
import com.ezpnix.writeon.domain.usecase.NoteUseCase
import com.ezpnix.writeon.presentation.components.NotesScaffold
import com.ezpnix.writeon.presentation.lock.LockGate
import com.ezpnix.writeon.presentation.screens.home.getContainerColor
import com.ezpnix.writeon.presentation.screens.home.sorter
import com.ezpnix.writeon.presentation.screens.home.widgets.NoteFilter
import com.ezpnix.writeon.presentation.screens.settings.TopBar
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager
import com.ezpnix.writeon.presentation.theme.LeafNotesTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Picks the note a home-screen widget should display.
 *
 * This screen lists every note, including the ones protected by the app lock or the note vault, so
 * it sits behind [LockGate] exactly like the main app does. Without that gate this exported activity
 * was a way to read all note content without unlocking anything.
 */
@AndroidEntryPoint
class NotesWidgetActivity : ComponentActivity() {
    @Inject
    lateinit var noteUseCase: NoteUseCase

    @Inject
    lateinit var settingsRepository: SettingsRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setContent {
            val settings = hiltViewModel<SettingsViewModel>()
            val coroutineScope = rememberCoroutineScope()

            // Starting the collectors here meant every recomposition cancelled and restarted them.
            // They only need to run for the lifetime of this screen.
            LaunchedEffect(Unit) { noteUseCase.observe() }

            LeafNotesTheme(settingsModel = settings) {
                LockGate {
                    NotesScaffold(
                        topBar = {
                            TopBar(
                                title = stringResource(id = R.string.select_note),
                                onBackNavClicked = { finish() }
                            )
                        },
                        content = {
                            NoteFilter(
                                settingsViewModel = settings,
                                containerColor = getContainerColor(settings),
                                shape = shapeManager(radius = settings.settings.value.cornerRadius / 2, isBoth = true),
                                onNoteClicked = { id ->
                                    // Suspending rather than blocking: the click handler runs on the
                                    // main thread, and runBlocking here froze the UI until the
                                    // preferences write and widget refresh finished.
                                    coroutineScope.launch {
                                        settingsRepository.putInt("${NotesWidgetReceiver.WIDGET_PREFERENCE}${appWidgetId}", id)
                                        NotesWidget().updateAll(this@NotesWidgetActivity)
                                        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                        setResult(RESULT_OK, resultValue)
                                        finish()
                                    }
                                },
                                notes = noteUseCase.notes.sortedWith(sorter(settings.settings.value.sortDescending)),
                                viewMode = false,
                            )
                        }
                    )
                }
            }
        }
    }
}