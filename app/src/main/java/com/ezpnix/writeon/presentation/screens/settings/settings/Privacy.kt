package com.ezpnix.writeon.presentation.screens.settings.settings

import android.content.Context
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
import com.ezpnix.writeon.presentation.lock.PatternSetupDialog
import com.ezpnix.writeon.presentation.lock.PatternVerifyDialog
import com.ezpnix.writeon.presentation.lock.PinSetupDialog
import com.ezpnix.writeon.presentation.lock.PinVerifyDialog
import com.ezpnix.writeon.presentation.lock.isBiometricAvailable
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
                            if (isBiometricAvailable(context)) {
                                lockManager.setBiometricEnabled(true)
                                Toast.makeText(context, context.getString(R.string.lock_biometric_enabled), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.lock_biometric_unavailable), Toast.LENGTH_SHORT).show()
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
                lockManager.clearPin()
                Toast.makeText(context, context.getString(R.string.lock_pin_disabled), Toast.LENGTH_SHORT).show()
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
                lockManager.clearPattern()
                Toast.makeText(context, context.getString(R.string.lock_pattern_disabled), Toast.LENGTH_SHORT).show()
            }
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