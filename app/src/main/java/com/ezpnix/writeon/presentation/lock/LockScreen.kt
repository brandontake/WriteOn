package com.ezpnix.writeon.presentation.lock

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezpnix.writeon.R
import kotlinx.coroutines.delay

private const val TICK_MS = 1_000L

/**
 * Full screen lock. Shows the biometric prompt on first appearance when it is enabled and falls
 * back to the PIN keypad and/or the pattern grid.
 *
 * A forgotten credential cannot be recovered inside the app; clearing the app data is the only way
 * out, which is why setting a credential always asks for confirmation twice.
 */
@Composable
fun LockScreen(lockManager: LockManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val biometricEnabled by lockManager.biometricEnabled.collectAsStateWithLifecycle()
    val pinEnabled by lockManager.pinEnabled.collectAsStateWithLifecycle()
    val patternEnabled by lockManager.patternEnabled.collectAsStateWithLifecycle()
    val lockoutUntil by lockManager.lockoutUntil.collectAsStateWithLifecycle()

    var preferPattern by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }

    val activity = context as? FragmentActivity
    val biometricUnavailable = biometricEnabled && !isBiometricAvailable(context)

    LaunchedEffect(lockoutUntil) {
        while (true) {
            remainingSeconds = lockManager.lockoutSecondsRemaining()
            if (remainingSeconds <= 0) break
            delay(TICK_MS)
        }
    }

    val authenticate: () -> Unit = {
        if (activity == null) {
            errorMessage = context.getString(R.string.lock_biometric_failed)
        } else {
            authenticateWithBiometrics(
                activity = activity,
                context = context,
                onSuccess = { lockManager.unlock() },
                onError = { errorCode, errString ->
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        -> Unit

                        else -> {
                            val reason = errString.toString().takeIf { it.isNotBlank() }
                            errorMessage = reason ?: context.getString(R.string.lock_biometric_failed)
                        }
                    }
                },
            )
        }
    }

    var autoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(biometricEnabled, activity) {
        if (biometricEnabled && !autoPrompted && activity != null) {
            autoPrompted = true
            authenticate()
        }
    }

    val showPatternUi = patternEnabled && (!pinEnabled || preferPattern)
    val showPinUi = pinEnabled && !showPatternUi
    val hasCredentialFallback = pinEnabled || patternEnabled

    val submitPin: () -> Unit = {
        if (lockManager.verifyPin(pin)) {
            errorMessage = null
            lockManager.unlock()
        } else {
            pin = ""
            errorMessage = context.getString(R.string.lock_wrong_pin)
        }
    }

    val submitPattern: (List<Int>) -> Unit = { drawn ->
        if (drawn.size >= LockManager.MIN_PATTERN_CELLS) {
            if (lockManager.verifyPattern(drawn)) {
                errorMessage = null
                lockManager.unlock()
            } else {
                errorMessage = context.getString(R.string.lock_wrong_pattern)
            }
        }
        pattern = emptyList()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(id = subtitleRes(showPatternUi, showPinUi)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))

                when {
                    showPatternUi -> PatternGrid(
                        pattern = pattern,
                        onPatternChange = { pattern = it },
                        onPatternFinished = submitPattern,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .widthIn(max = 320.dp),
                    )

                    showPinUi -> {
                        PinKeypad(value = pin, onValueChange = { pin = it })
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = submitPin,
                            enabled = pin.length >= LockManager.MIN_PIN_LENGTH,
                        ) {
                            Text(text = stringResource(id = R.string.lock_unlock))
                        }
                    }
                }

                val message = if (remainingSeconds > 0) {
                    stringResource(id = R.string.lock_locked_out, remainingSeconds)
                } else {
                    errorMessage
                }
                if (message != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                if (biometricUnavailable) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(id = R.string.lock_biometric_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                if (biometricEnabled) {
                    Spacer(Modifier.height(24.dp))
                    if (hasCredentialFallback) {
                        TextButton(onClick = authenticate) {
                            BiometricButtonLabel()
                        }
                    } else {
                        Button(onClick = authenticate) {
                            BiometricButtonLabel()
                        }
                    }
                }

                if (pinEnabled && patternEnabled) {
                    TextButton(onClick = { preferPattern = !preferPattern; errorMessage = null }) {
                        Text(
                            text = stringResource(
                                id = if (preferPattern) R.string.lock_use_pin else R.string.lock_use_pattern
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricButtonLabel() {
    Icon(
        imageVector = Icons.Filled.Fingerprint,
        contentDescription = null,
    )
    Text(
        text = stringResource(id = R.string.lock_use_biometric),
        modifier = Modifier.padding(start = 8.dp),
    )
}

private fun subtitleRes(showPatternUi: Boolean, showPinUi: Boolean): Int = when {
    showPatternUi -> R.string.lock_subtitle_pattern
    showPinUi -> R.string.lock_subtitle_pin
    else -> R.string.lock_subtitle_biometric
}

internal fun authenticateWithBiometrics(
    activity: FragmentActivity,
    context: Context,
    onSuccess: () -> Unit,
    onError: (Int, CharSequence) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(context),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString)
            }
        },
    )
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.getString(R.string.lock_biometric_prompt_title))
        .setSubtitle(context.getString(R.string.lock_biometric_prompt_subtitle))
        .setAllowedAuthenticators(lockAuthenticators())
    // Device credential may not be combined with a negative button, and pre API 30 platforms
    // cannot mix it with biometrics at all, so they get the app level fallback instead.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        builder.setNegativeButtonText(context.getString(R.string.lock_cancel))
    }
    prompt.authenticate(builder.build())
}
