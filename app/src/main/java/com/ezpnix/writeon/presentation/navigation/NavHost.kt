package com.ezpnix.writeon.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
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
    noteId: Int,
    startDestination: String
) {
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
            val id = backStackEntry.arguments?.getString("id")?.toIntOrNull() ?: 0
            val encrypted = backStackEntry.arguments?.getString("encrypted").toBoolean()
            EditNoteView(
                settingsViewModel = settingsViewModel,
                id = if (noteId == -1) id else noteId,
                encrypted = encrypted
            ) {
                if (noteId == -1) {
                    // A share or a notification can launch straight into the editor, leaving no
                    // entry underneath: popBackStack() then returns false. Finishing the activity
                    // there made back exit the app instead of returning to the note list, so the
                    // editor - the one screen that can be the root - now falls back to Home.
                    if (!navController.popBackStack()) {
                        navController.navigate(NavRoutes.Home.route) {
                            popUpTo(NavRoutes.Edit.route) { inclusive = true }
                        }
                    }
                } else {
                    navController.navigate(NavRoutes.Home.route) {
                        popUpTo(NavRoutes.Edit.route) { inclusive = true }
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
}