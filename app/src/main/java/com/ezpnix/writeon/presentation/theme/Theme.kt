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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
    // Both of these touch the window, so they also belong in an effect rather than in the body:
    // recomposition can run this function many times per frame.
    SideEffect {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !effectiveDarkTheme
            isAppearanceLightNavigationBars = !effectiveDarkTheme
        }
        if (settings.screenProtection) {
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
