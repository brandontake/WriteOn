package com.ezpnix.writeon.presentation.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives the lock UI access to the Hilt provided [LockManager] from plain composables, without
 * forcing a ViewModel on top of it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockEntryPoint {
    fun lockManager(): LockManager
}

@Composable
fun rememberLockManager(): LockManager {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, LockEntryPoint::class.java)
            .lockManager()
    }
}

/**
 * Authenticator set the app is allowed to ask for.
 *
 * Mixing biometrics with the device credential is only supported from API 30 on, so older
 * platforms fall back to strong biometrics alone (see [authenticateWithBiometrics]).
 */
internal fun lockAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

internal fun isBiometricAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(lockAuthenticators()) ==
        BiometricManager.BIOMETRIC_SUCCESS
