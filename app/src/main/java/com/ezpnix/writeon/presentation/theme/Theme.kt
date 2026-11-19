package com.ezpnix.writeon.presentation.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ezpnix.writeon.presentation.lock.rememberLockManager
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel

private fun getColorScheme(context: Context, isDarkTheme: Boolean, isDynamicTheme: Boolean, isAmoledTheme: Boolean): ColorScheme {
    if (isDynamicTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDarkTheme) {
            if (isAmoledTheme) {
                return dynamicDarkColorScheme(context).copy(surfaceContainerLow = Color.Black, surface = Color.Black)
            }

            return dynamicDarkColorScheme(context)
        } else {
            return dynamicLightColorScheme(context)
        }
    } else if (isDarkTheme) {
         if (isAmoledTheme) {
             return darkScheme.copy(surfaceContainerLow = Color.Black, surface = Color.Black)
         }

        return darkScheme
    } else {
        return lightScheme
    }
}

@Composable
fun LeafNotesTheme(
    settingsModel: SettingsViewModel,
    content: @Composable () -> Unit
) {
    val settings = settingsModel.settings.value
    val systemInDarkTheme = isSystemInDarkTheme()
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 真正生效的深色与动态色彩：当开启跟随系统时，直接以系统主题实时派生，保证首帧与冷启动绝对精准，无需异步写库等待
    val effectiveDarkTheme = if (settings.automaticTheme) systemInDarkTheme else settings.darkTheme
    val effectiveDynamicTheme = if (settings.automaticTheme) supportsDynamic else settings.dynamicTheme

    // These two used to be written straight from the composable body. `update()` also persists the
    // settings and (re)schedules the auto-backup work, so every recomposition — a drawer slide, a
    // FAB animation — wrote to disk. Writing only when the derived value actually differs keeps
    // the setting in step with the system without the side effect.
    LaunchedEffect(settings.automaticTheme, systemInDarkTheme, supportsDynamic) {
        if (!settings.automaticTheme) return@LaunchedEffect
        val target = settings.copy(dynamicTheme = supportsDynamic, darkTheme = systemInDarkTheme)
        if (target.dynamicTheme != settings.dynamicTheme || target.darkTheme != settings.darkTheme) {
            settingsModel.update(target)
        }
    }

    val context = LocalContext.current
    val activity = LocalView.current.context as Activity
    // A3: FLAG_SECURE must follow the app lock, not just the independent "screen protection"
    // switch, which defaults to false - a user who set a PIN still had screenshots, screen
    // recording and the recents thumbnail available. rememberLockManager() returns the Hilt
    // singleton; LockGate wraps this content in both activities that use this theme, so the one
    // time it touches disk (EncryptedSharedPreferences creation) is already paid in this same
    // composition either way. The three enabled flags are in-memory StateFlows, so subscribing
    // to them here costs no further IO and reacts when a lock is added or removed.
    val lockManager = rememberLockManager()
    val biometricEnabled by lockManager.biometricEnabled.collectAsStateWithLifecycle()
    val pinEnabled by lockManager.pinEnabled.collectAsStateWithLifecycle()
    val patternEnabled by lockManager.patternEnabled.collectAsStateWithLifecycle()
    val lockConfigured = biometricEnabled || pinEnabled || patternEnabled
    // Both of these touch the window, so they also belong in an effect rather than in the body:
    // recomposition can run this function many times per frame.
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !effectiveDarkTheme
            isAppearanceLightNavigationBars = !effectiveDarkTheme
        }
        // A configured lock always wins over the privacy switch, so the thumbnail/screenshot
        // protection cannot be silently absent while the lock screen is guarding the content.
        if (settings.screenProtection || lockConfigured) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    MaterialTheme(
        colorScheme = getColorScheme(context, effectiveDarkTheme, effectiveDynamicTheme, settings.amoledTheme),
        typography = Typography(),
        content = content
    )
}
