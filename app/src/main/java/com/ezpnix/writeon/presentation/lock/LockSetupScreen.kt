package com.ezpnix.writeon.presentation.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ezpnix.writeon.R
import kotlinx.coroutines.delay

private const val LOCKOUT_TICK_MS = 1_000L

/**
 * Full screen dialog that asks for the new credential twice before it is persisted. Nothing is
 * written unless both entries match, so a mistyped first attempt can never lock the user out.
 */
@Composable
fun PinSetupDialog(
    lockManager: LockManager,
    onDismiss: () -> Unit,
    onEnabled: () -> Unit,
) {
    val context = LocalContext.current
    var firstPin by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val confirm: () -> Unit = {
        val entered = pin
        val first = firstPin
        when {
            entered.length < LockManager.MIN_PIN_LENGTH ->
                errorMessage = context.getString(R.string.lock_setup_pin_length)

            first == null -> {
                firstPin = entered
                pin = ""
                errorMessage = null
            }

            first == entered -> {
                lockManager.setPin(entered)
                onEnabled()
            }

            else -> {
                firstPin = null
                pin = ""
                errorMessage = context.getString(R.string.lock_setup_pin_mismatch)
            }
        }
    }

    LockDialogScaffold(
        titleRes = if (firstPin != null) R.string.lock_setup_pin_confirm else R.string.lock_setup_pin_title,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
    ) {
        PinKeypad(value = pin, onValueChange = { pin = it })
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = confirm,
            enabled = pin.length >= LockManager.MIN_PIN_LENGTH,
        ) {
            Text(text = stringResource(id = R.string.lock_confirm))
        }
    }
}

/**
 * Draws the new pattern twice; only a matching pair of at least [LockManager.MIN_PATTERN_CELLS]
 * cells is persisted.
 */
@Composable
fun PatternSetupDialog(
    lockManager: LockManager,
    onDismiss: () -> Unit,
    onEnabled: () -> Unit,
) {
    val context = LocalContext.current
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val onFinished: (List<Int>) -> Unit = { drawn ->
        val first = firstPattern
        when {
            drawn.size < LockManager.MIN_PATTERN_CELLS ->
                if (drawn.isNotEmpty()) {
                    errorMessage = context.getString(R.string.lock_setup_pattern_short)
                }

            first == null -> {
                firstPattern = drawn
                errorMessage = null
            }

            first == drawn -> {
                lockManager.setPattern(drawn)
                onEnabled()
            }

            else -> {
                firstPattern = null
                errorMessage = context.getString(R.string.lock_setup_pattern_mismatch)
            }
        }
        pattern = emptyList()
    }

    LockDialogScaffold(
        titleRes = if (firstPattern != null) {
            R.string.lock_setup_pattern_confirm
        } else {
            R.string.lock_setup_pattern_title
        },
        errorMessage = errorMessage,
        onDismiss = onDismiss,
    ) {
        PatternGrid(
            pattern = pattern,
            onPatternChange = { pattern = it },
            onPatternFinished = onFinished,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .widthIn(max = 320.dp),
        )
    }
}

/** Asks for the current PIN before it is removed. A failed attempt never clears anything. */
@Composable
fun PinVerifyDialog(
    lockManager: LockManager,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val confirm: () -> Unit = {
        if (lockManager.verifyPin(pin)) {
            onVerified()
        } else {
            pin = ""
            errorMessage = context.getString(R.string.lock_wrong_pin)
        }
    }

    LockDialogScaffold(
        titleRes = R.string.lock_verify_pin_title,
        errorMessage = lockoutAwareMessage(lockManager, errorMessage),
        onDismiss = onDismiss,
    ) {
        PinKeypad(value = pin, onValueChange = { pin = it })
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = confirm,
            enabled = pin.length >= LockManager.MIN_PIN_LENGTH,
        ) {
            Text(text = stringResource(id = R.string.lock_confirm))
        }
    }
}

/** Asks for the current pattern before it is removed. A failed attempt never clears anything. */
@Composable
fun PatternVerifyDialog(
    lockManager: LockManager,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val context = LocalContext.current
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val onFinished: (List<Int>) -> Unit = { drawn ->
        if (drawn.size >= LockManager.MIN_PATTERN_CELLS) {
            if (lockManager.verifyPattern(drawn)) {
                onVerified()
            } else {
                errorMessage = context.getString(R.string.lock_wrong_pattern)
            }
        }
        pattern = emptyList()
    }

    LockDialogScaffold(
        titleRes = R.string.lock_verify_pattern_title,
        errorMessage = lockoutAwareMessage(lockManager, errorMessage),
        onDismiss = onDismiss,
    ) {
        PatternGrid(
            pattern = pattern,
            onPatternChange = { pattern = it },
            onPatternFinished = onFinished,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun LockDialogScaffold(
    titleRes: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(id = titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    content()
                    if (errorMessage != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(id = R.string.lock_cancel))
                    }
                }
            }
        }
    }
}

/** Keeps the lockout countdown live while the dialog stays open. */
@Composable
private fun lockoutAwareMessage(lockManager: LockManager, errorMessage: String?): String? {
    val remaining by produceState(0, lockManager.lockoutUntil) {
        while (true) {
            value = lockManager.lockoutSecondsRemaining()
            if (value <= 0) break
            delay(LOCKOUT_TICK_MS)
        }
    }
    return if (remaining > 0) stringResource(id = R.string.lock_locked_out, remaining) else errorMessage
}
