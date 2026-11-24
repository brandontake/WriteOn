package com.ezpnix.writeon.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The small leading cue the system-style snackbar shows for the action that just happened. */
enum class AppSnackbarKind { INFO, SAVED, DELETE }

/**
 * [SnackbarVisuals] with the one extra piece of information the app needs.
 *
 * Material 3's [SnackbarHost] only understands message/action/duration. Carrying the kind here keeps
 * the icon decision in the host (no controller call site has to know about Compose) while still
 * using the platform snackbar queue and its accessibility behaviour.
 */
data class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
    val kind: AppSnackbarKind,
) : SnackbarVisuals

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
        kind: AppSnackbarKind = AppSnackbarKind.INFO,
        onAction: (suspend () -> Unit)? = null,
        /**
         * R4-06: runs when the bar leaves the screen without its action being taken (timeout,
         * swipe, or replacement by a newer message). The save-undo holder uses it to drop a
         * closure that would otherwise keep the saved note's plaintext alive after the only entry
         * point to it is gone.
         */
        onDismissed: (suspend () -> Unit)? = null,
    ) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                AppSnackbarVisuals(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = actionLabel == null,
                    duration = duration,
                    kind = kind,
                )
            )
            when (result) {
                SnackbarResult.ActionPerformed -> onAction?.invoke()
                SnackbarResult.Dismissed -> onDismissed?.invoke()
            }
        }
    }
}

/**
 * Null outside the app host (previews, widget entry points). Screens degrade to no feedback
 * instead of crashing when the local is absent.
 */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbarController?> { null }

/**
 * The app-wide snackbar, rendered with the Material 3 system component.
 *
 * The old host used the default `Snackbar` with no shape/colour overrides, which looked like a
 * pre-Material-You bar. This uses the theme's inverse surface tokens, the large system shape, a
 * leading status icon and the standard right-aligned action, so it matches the rest of the app and
 * the platform's current snackbar design.
 */
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
    ) { data ->
        val visuals = data.visuals as? AppSnackbarVisuals
        val kind = visuals?.kind ?: AppSnackbarKind.INFO
        val icon = when (kind) {
            AppSnackbarKind.SAVED -> Icons.Rounded.CheckCircle
            AppSnackbarKind.DELETE -> Icons.Rounded.DeleteOutline
            AppSnackbarKind.INFO -> Icons.Rounded.Info
        }
        Snackbar(
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            // R2-07: the action and the status icon are information, not decoration. On the
            // manual dark scheme `inversePrimary` used to be a pale mint on a pale grey surface
            // (~1.07:1), so "Undo" was effectively invisible. `inverseOnSurface` is the token
            // guaranteed to pair with `inverseSurface`.
            actionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }) {
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            dismissAction = if (data.visuals.actionLabel == null) {
                {
                    IconButton(onClick = { data.dismiss() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            } else {
                null
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
