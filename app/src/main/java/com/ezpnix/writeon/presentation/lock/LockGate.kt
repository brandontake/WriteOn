package com.ezpnix.writeon.presentation.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Wraps the app content and replaces it with [LockScreen] while the app is locked.
 *
 * The app locks on cold start when a credential is configured, is locked again as soon as it goes
 * to the background and only auto unlocks when the user returns within the grace period handled by
 * [LockManager.onForeground].
 *
 * SEC-6: this gate is visibility only. The other half of the lock lives in [LockManager]: an
 * explicit lock and a background visit that outlasts the grace period clear the vault password,
 * the derived keys, `NoteCrypto`'s plaintext cache and the note list, so "locked" also means "not
 * decryptable in memory", not merely "covered by a screen" (V-3: the gate still goes up on every
 * ON_STOP, but a visit shorter than 20 seconds keeps the Vault session). The collectors are
 * started again from the unlock path, which is why re-entering here shows content without a
 * manual refresh.
 *
 * The thumbnail claim used to rest on timing alone: the framework decides whether the recents
 * snapshot catches [LockScreen] or the note behind it. Now that a configured lock also forces
 * `FLAG_SECURE` onto the window (see `LeafNotesTheme`), the overview thumbnail and any screenshot
 * are blocked by the window manager whenever a credential exists, so the promise no longer
 * depends on ON_STOP ordering. With no credential configured there is nothing to guard, which
 * matches the gate staying open.
 */
@Composable
fun LockGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val lockManager = rememberLockManager()
    val isLocked by lockManager.isLocked.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()

    DisposableEffect(lifecycleOwner, lockManager, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // A configuration change (rotation, theme, font scale) also delivers ON_STOP, but
                // it is not "the user left the app". Evicting the vault password here would make a
                // rotation silently lock every vault note while the UI itself stays unlocked: the
                // grace path reopens the content but cannot re-derive the password the user never
                // asked to drop. Real backgrounding hides immediately and hands the timing to
                // onBackground, which evicts only after the grace period (V-3).
                Lifecycle.Event.ON_STOP ->
                    if (activity?.isChangingConfigurations != true) lockManager.onBackground()
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

/** Unwraps the Activity from a compose [Context] without assuming it is the direct context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
