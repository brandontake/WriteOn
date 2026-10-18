package com.ezpnix.writeon.presentation.screens.settings.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.constant.DatabaseConst
import com.ezpnix.writeon.presentation.screens.edit.components.CustomTextField
import com.ezpnix.writeon.presentation.screens.settings.SettingsScaffold
import com.ezpnix.writeon.presentation.screens.settings.TxtBackupHelper
import com.ezpnix.writeon.presentation.screens.settings.WebDavPrefs
import com.ezpnix.writeon.presentation.screens.settings.WebDavPrefsCipher
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.webDavDataStore
import com.ezpnix.writeon.presentation.theme.appCornerShape
import com.jakewharton.processphoenix.ProcessPhoenix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Unique work name shared by the WebDAV upload and download workers.
 *
 * Both used to be enqueued anonymously, so two taps started two workers at once: two uploads racing
 * on the same remote file, or - worse - a restore rewriting the local database while a backup was
 * still reading it. Sharing one name with [ExistingWorkPolicy.KEEP] serialises them instead, and
 * makes a double tap a no-op rather than a second job.
 */
private const val WEBDAV_SYNC_WORK = "WebDAVSync"

@Composable
@Suppress("DEPRECATION")
fun CloudScreen(navController: NavController, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    val aead = remember {
        WebDavPrefsCipher.aead(context)
    }

    val webdavUrlKey = stringPreferencesKey(WebDavPrefs.URL)
    val webdavUsernameKey = stringPreferencesKey(WebDavPrefs.USERNAME)
    val webdavPasswordKey = stringPreferencesKey(WebDavPrefs.PASSWORD)
    val backupPassphraseKey = stringPreferencesKey(WebDavPrefs.BACKUP_PASSPHRASE)

    var webdavUrl by remember { mutableStateOf("") }
    var webdavUsername by remember { mutableStateOf("") }
    var webdavPassword by remember { mutableStateOf("") }

    /**
     * Passphrase for the archive uploaded to WebDAV. It is never shown back after being saved —
     * only a marker of whether one exists — because the settings store keeps it encrypted rather
     * than in a retrievable field, and re-prompting on every visit would defeat that.
     */
    var backupPassphrase by remember { mutableStateOf("") }
    var hasBackupPassphrase by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        context.webDavDataStore.data.first().let { prefs ->
            webdavUrl = prefs[webdavUrlKey] ?: ""
            webdavUsername = prefs[webdavUsernameKey] ?: ""
            webdavPassword = prefs[webdavPasswordKey]?.let { encryptedPassword ->
                WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword) ?: ""
            } ?: ""
            hasBackupPassphrase = !prefs[backupPassphraseKey].isNullOrBlank()
        }
    }

    var txtBackupUri by remember { mutableStateOf<Uri?>(null) }

    val exportTxtBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> txtBackupUri = uri }
    )

    val importTxtBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    try {
                        TxtBackupHelper
                            .restoreNotesFromZipStream(context, uri)
                        Toast.makeText(context, context.getString(R.string.cloud_imported_restart), Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(1000)
                        ProcessPhoenix.triggerRebirth(context)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.cloud_restore_txt_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    LaunchedEffect(txtBackupUri) {
        txtBackupUri?.let { uri ->
            TxtBackupHelper
                .writeNotesToZipStream(context, uri)
            Toast.makeText(context, context.getString(R.string.cloud_txt_backup_saved), Toast.LENGTH_SHORT).show()
            txtBackupUri = null
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> if (uri != null) settingsViewModel.onExportBackup(uri, context) }
    )

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    try {
                        settingsViewModel.onImportBackup(uri, context)
                        Toast.makeText(context, context.getString(R.string.cloud_restored_restart), Toast.LENGTH_LONG).show()
                        kotlinx.coroutines.delay(1000)
                        ProcessPhoenix.triggerRebirth(context)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.cloud_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    var showBackupPasswordPrompt by remember { mutableStateOf(false) }
    var showRestorePasswordPrompt by remember { mutableStateOf(false) }

    SettingsScaffold(
        settingsViewModel = settingsViewModel,
        title = context.getString(R.string.cloud_backup_restore_title),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
                shape = appCornerShape()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.cloud_experimental),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.cloud_experimental_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.cloud_webdav_settings), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = webdavUrl,
                onValueChange = { webdavUrl = it },
                label = { Text(stringResource(R.string.cloud_webdav_url)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = webdavUsername,
                onValueChange = { webdavUsername = it },
                label = { Text(stringResource(R.string.cloud_username)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = webdavPassword,
                onValueChange = { webdavPassword = it },
                label = { Text(context.getString(R.string.cloud_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = backupPassphrase,
                onValueChange = { backupPassphrase = it },
                label = { Text(context.getString(R.string.cloud_backup_passphrase)) },
                supportingText = { Text(context.getString(R.string.cloud_backup_passphrase_desc)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                coroutineScope.launch {
                    context.webDavDataStore.edit { prefs ->
                        prefs[webdavUrlKey] = webdavUrl
                        prefs[webdavUsernameKey] = webdavUsername
                        if (webdavPassword.isNotBlank()) {
                            prefs[webdavPasswordKey] = WebDavPrefsCipher.encrypt(aead, webdavPassword)
                        } else {
                            prefs.remove(webdavPasswordKey)
                        }
                        if (backupPassphrase.isNotBlank()) {
                            prefs[backupPassphraseKey] = WebDavPrefsCipher.encrypt(aead, backupPassphrase)
                            hasBackupPassphrase = true
                        } else {
                            prefs.remove(backupPassphraseKey)
                            hasBackupPassphrase = false
                        }
                    }
                    backupPassphrase = ""
                    Toast.makeText(context, context.getString(R.string.cloud_webdav_saved), Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(context.getString(R.string.cloud_save_webdav_info))
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.cloud_backup_options), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            BackupOptionCard(
                icon = Icons.Rounded.Cloud,
                title = context.getString(R.string.cloud_backup_to_webdav),
                description = context.getString(R.string.cloud_backup_to_webdav_desc),
                primaryButtonText = context.getString(R.string.cloud_backup_to_cloud),
                secondaryButtonText = context.getString(R.string.restore),
                onPrimaryClick = {
                    when {
                        webdavUrl.isBlank() || webdavUsername.isBlank() || webdavPassword.isBlank() -> {
                            Toast.makeText(context, context.getString(R.string.cloud_enter_webdav_first), Toast.LENGTH_LONG).show()
                        }
                        !hasBackupPassphrase -> {
                            Toast.makeText(context, context.getString(R.string.cloud_backup_passphrase_missing), Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            coroutineScope.launch {
                                context.webDavDataStore.edit { prefs ->
                                    prefs[webdavUrlKey] = webdavUrl
                                    prefs[webdavUsernameKey] = webdavUsername
                                    prefs[webdavPasswordKey] = WebDavPrefsCipher.encrypt(aead, webdavPassword)
                                }
                                workManager.enqueueUniqueWork(
                                    WEBDAV_SYNC_WORK,
                                    ExistingWorkPolicy.KEEP,
                                    OneTimeWorkRequestBuilder<com.ezpnix.writeon.presentation.screens.settings.WebDAVBackupWorker>()
                                        .build()
                                )
                                Toast.makeText(context, context.getString(R.string.cloud_webdav_backup_started), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onSecondaryClick = {
                    when {
                        webdavUrl.isBlank() || webdavUsername.isBlank() || webdavPassword.isBlank() -> {
                            Toast.makeText(context, context.getString(R.string.cloud_enter_webdav_first), Toast.LENGTH_LONG).show()
                        }
                        !hasBackupPassphrase -> {
                            Toast.makeText(context, context.getString(R.string.cloud_backup_passphrase_missing), Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            coroutineScope.launch {
                                context.webDavDataStore.edit { prefs ->
                                    prefs[webdavUrlKey] = webdavUrl
                                    prefs[webdavUsernameKey] = webdavUsername
                                    prefs[webdavPasswordKey] = WebDavPrefsCipher.encrypt(aead, webdavPassword)
                                }
                                workManager.enqueueUniqueWork(
                                    WEBDAV_SYNC_WORK,
                                    ExistingWorkPolicy.KEEP,
                                    OneTimeWorkRequestBuilder<com.ezpnix.writeon.presentation.screens.settings.WebDAVRestoreWorker>()
                                        .build()
                                )
                                Toast.makeText(context, context.getString(R.string.cloud_webdav_restore_started), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            BackupOptionCard(
                icon = Icons.Rounded.ArrowCircleUp,
                title = stringResource(id = R.string.database_backup),
                description = stringResource(id = R.string.backup_description),
                primaryButtonText = stringResource(R.string.backup),
                secondaryButtonText = stringResource(R.string.restore),
                onPrimaryClick = {
                    if (settingsViewModel.settings.value.encryptBackup) {
                        showBackupPasswordPrompt = true
                    } else {
                        exportBackupLauncher.launch("${DatabaseConst.NOTES_DATABASE_BACKUP_NAME}-${currentDateTime()}.zip")
                    }
                },
                onSecondaryClick = {
                    if (settingsViewModel.settings.value.encryptBackup) {
                        showRestorePasswordPrompt = true
                    } else {
                        settingsViewModel.password = null
                        importBackupLauncher.launch(arrayOf("application/zip"))
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            BackupOptionCard(
                icon = Icons.Rounded.Description,
                title = stringResource(R.string.cloud_backup_as_txt),
                description = stringResource(R.string.cloud_backup_as_txt_desc),
                primaryButtonText = stringResource(R.string.cloud_export),
                secondaryButtonText = stringResource(R.string.file_import_title),
                onPrimaryClick = {
                    exportTxtBackupLauncher.launch("notes-backup-${currentDateTime()}.zip")
                },
                onSecondaryClick = {
                    importTxtBackupLauncher.launch(arrayOf("application/zip"))
                }
            )

            Spacer(Modifier.height(16.dp))

            BackupOptionCard(
                icon = Icons.Rounded.Backup,
                title = stringResource(id = R.string.auto_backup),
                description = stringResource(id = R.string.auto_backup_description),
                primaryButtonText = null,
                onPrimaryClick = {},
                switchState = settingsViewModel.settings.value.autoBackupEnabled,
                onSwitchToggle = { isEnabled ->
                    settingsViewModel.update(settingsViewModel.settings.value.copy(autoBackupEnabled = isEnabled))
                    val msg = if (isEnabled) context.getString(R.string.cloud_auto_backup_enabled) else context.getString(R.string.cloud_auto_backup_disabled)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showBackupPasswordPrompt) {
        PasswordPrompt(
            context = context,
            text = stringResource(id = R.string.database_backup),
            settingsViewModel = settingsViewModel,
            onExit = { password ->
                showBackupPasswordPrompt = false
                if (password != null) {
                    settingsViewModel.password = password.text
                    exportBackupLauncher.launch("${DatabaseConst.NOTES_DATABASE_BACKUP_NAME}-${currentDateTime()}.zip")
                }
            }
        )
    }

    if (showRestorePasswordPrompt) {
        PasswordPrompt(
            context = context,
            text = stringResource(id = R.string.database_restore),
            settingsViewModel = settingsViewModel,
            onExit = { password ->
                showRestorePasswordPrompt = false
                if (password != null) {
                    settingsViewModel.password = password.text
                    importBackupLauncher.launch(arrayOf("application/zip"))
                }
            }
        )
    }
}

@Composable
fun BackupOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    primaryButtonText: String? = null,
    secondaryButtonText: String? = null,
    onPrimaryClick: () -> Unit = {},
    onSecondaryClick: (() -> Unit)? = null,
    switchState: Boolean? = null,
    onSwitchToggle: ((Boolean) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = appCornerShape(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(description, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row {
                when {
                    switchState != null && onSwitchToggle != null -> {
                        Switch(
                            checked = switchState,
                            onCheckedChange = onSwitchToggle,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                    primaryButtonText != null -> {
                        Button(onClick = onPrimaryClick) {
                            Text(primaryButtonText)
                        }
                    }
                }
                if (secondaryButtonText != null && onSecondaryClick != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onSecondaryClick) {
                        Text(secondaryButtonText)
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordPrompt(
    context: Context,
    text: String,
    settingsViewModel: SettingsViewModel,
    onExit: (TextFieldValue?) -> Unit,
) {
    var password by remember { mutableStateOf(TextFieldValue("")) }
    Dialog(
        onDismissRequest = { onExit(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = shapeManager(
                        isBoth = true,
                        radius = settingsViewModel.settings.value.cornerRadius
                    )
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CustomTextField(
                hideContent = true,
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(id = R.string.password_prompt),
                modifier = Modifier.padding(12.dp)
            )
            Button(
                modifier = Modifier
                    .wrapContentWidth()
                    .align(Alignment.End)
                    .padding(12.dp),
                onClick = {
                    if (password.text.isNotBlank()) {
                        onExit(password)
                    } else {
                        Toast.makeText(context, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Text(text)
            }
        }
    }
}