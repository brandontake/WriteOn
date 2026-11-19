package com.ezpnix.writeon.presentation.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One snackbar host for the whole navigation graph (U2).
 *
 * The editor's save-undo bar used to be a composable inside `EditScreen`: it disappeared with the
 * screen, so a save that happened on the way out could no longer be undone, and deleting a note
 * from the home screen had no feedback at all. Hoisting the host above the `NavHost` means a
 * message can outlive the screen that posted it - exactly the requirement for "saved, undo?" and
 * "moved to trash, undo?".
 *
 * [show] dismisses any previous message first, so rapid actions do not queue behind each other.
 */
class AppSnackbarController(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (suspend () -> Unit)? = null,
    ) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/**
 * Null outside the app host (previews, widget entry points). Screens degrade to no feedback
 * instead of crashing when the local is absent.
 */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarController?> { null }

@Composable
fun AppSnackbarHost(
    controller: AppSnackbarController,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = controller.hostState,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
