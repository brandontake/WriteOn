package com.ezpnix.writeon.presentation.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Wraps the app content and replaces it with [LockScreen] while the app is locked.
 *
 * The app locks on cold start when a credential is configured, is locked again as soon as it goes
 * to the background (so the overview thumbnail never shows note content) and only auto unlocks when
 * the user returns within the grace period handled by [LockManager.onForeground].
 */
@Composable
fun LockGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val lockManager = rememberLockManager()
    val isLocked by lockManager.isLocked.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, lockManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> lockManager.onBackground()
                Lifecycle.Event.ON_START -> lockManager.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isLocked) {
        LockScreen(lockManager = lockManager, modifier = modifier)
    } else {
        content()
    }
}
