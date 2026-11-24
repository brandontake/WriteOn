package com.ezpnix.writeon.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.ezpnix.writeon.presentation.components.AppSnackbarController
import com.ezpnix.writeon.presentation.components.AppSnackbarHost
import com.ezpnix.writeon.presentation.components.LocalAppSnackbar
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.presentation.screens.edit.EditNoteView
import com.ezpnix.writeon.presentation.screens.home.HomeView
import com.ezpnix.writeon.presentation.screens.library.LibraryScreen
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.terms.TermsScreen

@Composable
fun AppNavHost(
    settingsViewModel: SettingsViewModel,
    navController: NavHostController = rememberNavController(),
    startDestination: String
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbar = remember(snackbarHostState, snackbarScope) {
        AppSnackbarController(snackbarHostState, snackbarScope)
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbar) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = startDestination) {
                animatedComposable(NavRoutes.Home.route) {
                    HomeView(
                        onSettingsClicked = { navController.navigate(NavRoutes.Settings.route) },
                        onNoteClicked = { id, encrypted -> navController.navigate(NavRoutes.Edit.createRoute(id, encrypted)) },
                        settingsModel = settingsViewModel,
                        navController = navController
                    )
                }

                animatedComposable(NavRoutes.Terms.route) {
                    TermsScreen(
                        settingsViewModel = settingsViewModel,
                        navController = navController
                    )
                }

                animatedComposable(NavRoutes.Edit.route) { backStackEntry ->
                    // REG-02: every Edit destination resolves its own route argument. The launch
                    // intent's noteId is only used for the start destination (which encodes it in
                    // this same route), so opening another note later cannot keep loading the
                    // notification's note.
                    val id = editRouteNoteId(backStackEntry.arguments?.getString("id"))
                    val encrypted = backStackEntry.arguments?.getString("encrypted").toBoolean()
                    EditNoteView(
                        settingsViewModel = settingsViewModel,
                        id = id,
                        encrypted = encrypted
                    ) {
                        // A share or a notification can launch straight into the editor, leaving no
                        // entry underneath: popBackStack() then returns false. Finishing the activity
                        // there made back exit the app instead of returning to the note list, so the
                        // editor - the one screen that can be the root - now falls back to Home.
                        if (!navController.popBackStack()) {
                            navController.navigate(NavRoutes.Home.route) {
                                popUpTo(NavRoutes.Edit.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }

                animatedComposable(NavRoutes.Library.route) { backStackEntry ->
                    LibraryScreen(
                        settingsViewModel = settingsViewModel,
                        navController = navController,
                        type = backStackEntry.arguments?.getString(NavRoutes.Library.ARG_TYPE)
                            ?: Attachment.TYPE_IMAGE
                    )
                }

                settingScreens.forEach { (route, screen) ->
                    if (route == NavRoutes.Settings.route) {
                        slideInComposable(route) {
                            screen(settingsViewModel, navController)
                        }
                    } else {
                        animatedComposable(route) {
                            screen(settingsViewModel, navController)
                        }
                    }
                }
            }
            AppSnackbarHost(
                controller = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * REG-02: the note id a filled Edit route carries.
 *
 * The route is the only source of the destination's id. Keeping the parse here makes the
 * "notification cold start must not leak into later editors" rule testable without a
 * NavController: `edit/7/false` always resolves 7, regardless of which note launched the app.
 */
internal fun editRouteNoteId(idArgument: String?): Int = idArgument?.toIntOrNull() ?: 0
