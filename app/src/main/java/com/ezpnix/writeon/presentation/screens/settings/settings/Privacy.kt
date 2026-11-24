package com.ezpnix.writeon.presentation.screens.settings.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Pattern
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.presentation.lock.BiometricAvailability
import com.ezpnix.writeon.presentation.lock.PatternSetupDialog
import com.ezpnix.writeon.presentation.lock.PatternVerifyDialog
import com.ezpnix.writeon.presentation.lock.PinSetupDialog
import com.ezpnix.writeon.presentation.lock.PinVerifyDialog
import com.ezpnix.writeon.presentation.lock.biometricAvailability
import com.ezpnix.writeon.presentation.lock.rememberLockManager
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.screens.edit.components.CustomTextField
import com.ezpnix.writeon.presentation.screens.settings.SettingsScaffold
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.widgets.ActionType
import com.ezpnix.writeon.presentation.screens.settings.widgets.SettingsBox
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun PrivacyScreen(navController: NavController, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val lockManager = rememberLockManager()
    val locked by lockManager.isLocked.collectAsStateWithLifecycle()
    val biometricLockEnabled by lockManager.biometricEnabled.collectAsStateWithLifecycle()
    val patternLockEnabled by lockManager.patternEnabled.collectAsStateWithLifecycle()
    val pinLockEnabled by lockManager.pinEnabled.collectAsStateWithLifecycle()

    var showPinSetup by remember { mutableStateOf(false) }
    var showPinVerify by remember { mutableStateOf(false) }
    var showPatternSetup by remember { mutableStateOf(false) }
    var showPatternVerify by remember { mutableStateOf(false) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/.zip"),
        onResult = { uri ->
            if (uri != null) settingsViewModel.onExportBackup(uri, context)
        }
    )
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) settingsViewModel.onImportBackup(uri, context)
        }
    )

    /** SEC-03: true while the export password dialog is open. */
    var showBackupPasswordPrompt by remember { mutableStateOf(false) }

    /**
     * SEC-03: the destination whose export the policy refused for a missing password.
     *
     * The repository publishes it as a typed event; this screen consumes it and re-opens the
     * password dialog for that exact URI, so confirming resumes the export instead of asking the
     * user to pick the file again. This is the same flow Cloud.kt implements; the event is kept on
     * SettingsViewModel so both entry points agree.
     */
    var pendingPasswordExportUri by remember { mutableStateOf<Uri?>(null) }
    val passwordRequiredExportUri by settingsViewModel.passwordRequiredExportUri
        .collectAsStateWithLifecycle()
    LaunchedEffect(passwordRequiredExportUri) {
        val uri = passwordRequiredExportUri ?: return@LaunchedEffect
        pendingPasswordExportUri = uri
        showBackupPasswordPrompt = true
        // Consume last: clearing the key this effect observes cancels it.
        settingsViewModel.consumePasswordRequiredExport()
    }

    // SEC-09: the password dialog keeps the typed value in composition-local state, which survives
    // behind LockGate. An app lock ends the session that may hold it, so dismiss the dialog (and
    // drop its pending destination) instead of leaving the plaintext field visible after unlocking.
    LaunchedEffect(locked) {
        if (locked) {
            showBackupPasswordPrompt = false
            pendingPasswordExportUri = null
        }
    }

    SettingsScaffold(
        settingsViewModel = settingsViewModel,
        title = stringResource(id = R.string.privacy),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        LazyColumn {
            item {
                SettingsBox(
                    title = stringResource(id = R.string.biometric_authentication),
                    description = stringResource(id = R.string.lock_biometric_description),
                    icon = Icons.Filled.Fingerprint,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isFirst = true),
                    actionType = ActionType.SWITCH,
                    variable = biometricLockEnabled,
                    switchEnabled = { isEnabled ->
                        if (isEnabled) {
                            // UX-04: this switch asks for BIOMETRIC_STRONG or DEVICE_CREDENTIAL,
                            // so on a device with only a screen lock it would succeed without any
                            // fingerprint - and once that device credential is removed nothing
                            // could satisfy the prompt. An app PIN or pattern is therefore required
                            // first, so there is always something that can open the app.
                            //
                            // REG-07: the availability check distinguishes a temporary sensor
                            // problem (retry later, the lock stays as it is) from a device with no
                            // usable authenticator, instead of treating both as "not available".
                            val availability = biometricAvailability(context)
                            when {
                                !pinLockEnabled && !patternLockEnabled -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.lock_biometric_requires_credential),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }

                                availability == BiometricAvailability.AVAILABLE -> {
                                    lockManager.setBiometricEnabled(true)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.lock_biometric_enabled),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }

                                availability == BiometricAvailability.TRANSIENT_ERROR -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.lock2_biometric_retry),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }

                                else -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.lock_biometric_unavailable),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else {
                            lockManager.setBiometricEnabled(false)
                            Toast.makeText(context, context.getString(R.string.lock_biometric_disabled), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item {
                SettingsBox(
                    title = stringResource(id = R.string.pattern_authentication),
                    description = stringResource(id = R.string.lock_pattern_description),
                    icon = Icons.Filled.Pattern,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isFirst = false),
                    actionType = ActionType.SWITCH,
                    variable = patternLockEnabled,
                    switchEnabled = { isEnabled ->
                        if (isEnabled) {
                            showPatternSetup = true
                        } else {
                            showPatternVerify = true
                        }
                    }
                )
            }
            item {
                SettingsBox(
                    title = stringResource(id = R.string.pin_authentication),
                    description = stringResource(id = R.string.lock_pin_description),
                    icon = Icons.Filled.Pin,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isLast = true),
                    actionType = ActionType.SWITCH,
                    variable = pinLockEnabled,
                    switchEnabled = { isEnabled ->
                        if (isEnabled) {
                            showPinSetup = true
                        } else {
                            showPinVerify = true
                        }
                    }
                )
                Spacer(modifier = Modifier.height(18.dp))
            }
            item {
                SettingsBox(
                    title = stringResource(id = R.string.screen_protection),
                    description = stringResource(id = R.string.screen_protection_description),
                    icon = Icons.Filled.RemoveRedEye,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isFirst = true),
                    actionType = ActionType.SWITCH,
                    variable = settingsViewModel.settings.value.screenProtection,
                    switchEnabled = { isEnabled ->
                        settingsViewModel.update(settingsViewModel.settings.value.copy(screenProtection = isEnabled))
                        val message = if (isEnabled) {
                            context.getString(R.string.privacy_screen_protected)
                        } else {
                            context.getString(R.string.privacy_screen_unprotected)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }
            item {
                SettingsBox(
                    title = stringResource(id = R.string.encrypt_database),
                    description = stringResource(id = R.string.encrypt_database_description),
                    icon = Icons.Rounded.Security,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isLast = true),
                    variable = settingsViewModel.settings.value.encryptBackup,
                    actionType = ActionType.SWITCH,
                    switchEnabled = { isEnabled ->
                        settingsViewModel.update(settingsViewModel.settings.value.copy(encryptBackup = isEnabled))
                        val message = if (isEnabled) {
                            context.getString(R.string.privacy_db_encryption_enabled)
                        } else {
                            context.getString(R.string.privacy_db_encryption_disabled)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(18.dp))
            }
            item {
                SettingsBox(
                    isBig = true,
                    title = stringResource(id = R.string.homepage),
                    icon = Icons.Rounded.Home,
                    actionType = ActionType.CUSTOM,
                    radius = shapeManager(isBoth = true, radius = settingsViewModel.settings.value.cornerRadius),
                    customAction = {
                        navController.popBackStack(NavRoutes.Home.route, inclusive = false)
                    }
                )
            }
        }
    }

    if (showPinSetup) {
        PinSetupDialog(
            lockManager = lockManager,
            onDismiss = { showPinSetup = false },
            onEnabled = {
                showPinSetup = false
                Toast.makeText(context, context.getString(R.string.lock_pin_enabled), Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showPinVerify) {
        PinVerifyDialog(
            lockManager = lockManager,
            onDismiss = { showPinVerify = false },
            onVerified = {
                showPinVerify = false
                // REG-07: clearing the last PIN must not leave a biometric-only lock behind; the
                // lock manager turns biometric off in that case and this message says so, so the
                // switch below cannot show a combination the user did not choose.
                val biometricCleared = lockManager.clearPin()
                Toast.makeText(
                    context,
                    context.getString(
                        if (biometricCleared) {
                            R.string.lock2_pin_disabled_biometric_cleared
                        } else {
                            R.string.lock_pin_disabled
                        }
                    ),
                    if (biometricCleared) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        )
    }
    if (showPatternSetup) {
        PatternSetupDialog(
            lockManager = lockManager,
            onDismiss = { showPatternSetup = false },
            onEnabled = {
                showPatternSetup = false
                Toast.makeText(context, context.getString(R.string.lock_pattern_enabled), Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showPatternVerify) {
        PatternVerifyDialog(
            lockManager = lockManager,
            onDismiss = { showPatternVerify = false },
            onVerified = {
                showPatternVerify = false
                val biometricCleared = lockManager.clearPattern()
                Toast.makeText(
                    context,
                    context.getString(
                        if (biometricCleared) {
                            R.string.lock2_pattern_disabled_biometric_cleared
                        } else {
                            R.string.lock_pattern_disabled
                        }
                    ),
                    if (biometricCleared) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        )
    }
    if (showBackupPasswordPrompt) {
        // SEC-03: a download started from this screen used to end as a toast when the policy
        // required a password, leaving the picked file empty. The dialog is re-opened for the
        // refused URI; confirming sets the password and retries that same destination.
        PasswordPrompt(
            context = context,
            text = stringResource(id = R.string.database_backup),
            settingsViewModel = settingsViewModel,
            onExit = { password ->
                showBackupPasswordPrompt = false
                val retryUri = pendingPasswordExportUri
                pendingPasswordExportUri = null
                if (password != null) {
                    settingsViewModel.password = password.text
                    if (retryUri != null) {
                        settingsViewModel.onExportBackup(retryUri, context)
                    } else {
                        exportBackupLauncher.launch(
                            "${DatabaseConst.NOTES_DATABASE_BACKUP_NAME}-${currentDateTime()}.zip"
                        )
                    }
                }
            },
            // Named argument picks this file's overload (Cloud.kt has one with allowBlank/hint).
            onBackup = {},
        )
    }
}

fun currentDateTime(): String {
    val currentDateTime = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("MM-dd-HH-mm-ms")
    val formattedDateTime = currentDateTime.format(formatter)
    return formattedDateTime
}

@Composable
fun PasswordPrompt(context: Context, text: String, settingsViewModel: SettingsViewModel, onExit: (TextFieldValue?) -> Unit, onBackup: () -> Unit = {}) {
    var password by remember { mutableStateOf(TextFieldValue("")) }
    // R2-05/SEC-09: same rule as Cloud's prompt - a typed backup password must not outlive the
    // session that authorized the settings screen.
    val vaultActive by settingsViewModel.vaultSessionActive.collectAsStateWithLifecycle()
    val appLocked by settingsViewModel.appLocked.collectAsStateWithLifecycle()
    var sessionWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(vaultActive, appLocked) {
        if (vaultActive && !appLocked) {
            sessionWasActive = true
            return@LaunchedEffect
        }
        if (!sessionWasActive && !appLocked) return@LaunchedEffect
        sessionWasActive = false
        password = TextFieldValue("")
        onExit(null)
    }
    Dialog(
        onDismissRequest = { onExit(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LazyColumn {
            item {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .fillMaxHeight(0.2f)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = shapeManager(
                                isBoth = true,
                                radius = settingsViewModel.settings.value.cornerRadius
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = shapeManager(
                                    isBoth = true,
                                    radius = settingsViewModel.settings.value.cornerRadius
                                )
                            )
                    ) {
                        CustomTextField(
                            hideContent = true,
                            value = password,
                            onValueChange = { password = it },
                            placeholder = stringResource(id = R.string.password_prompt)
                        )
                    }
                    Button(
                        modifier = Modifier
                            .padding(12.dp)
                            .wrapContentWidth()
                            .align(Alignment.End),
                        onClick = {
                            if (password.text.isNotBlank()) {
                                onExit(password)
                                onBackup()
                            } else {
                                Toast.makeText(context, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                            }
                        },
                        content = {
                            Text(text)
                        }
                    )
                }
            }
        }
    }
}