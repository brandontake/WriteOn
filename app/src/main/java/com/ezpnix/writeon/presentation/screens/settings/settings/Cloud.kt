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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import com.ezpnix.writeon.presentation.screens.settings.WebDavBackupState
import com.ezpnix.writeon.presentation.screens.settings.WebDavPrefs
import com.ezpnix.writeon.presentation.screens.settings.WebDavPrefsCipher
import com.ezpnix.writeon.presentation.screens.settings.DATA_RESTORE_URL
import com.ezpnix.writeon.presentation.screens.settings.DATA_FORCE_OVERWRITE
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
 * still reading it. Sharing one name keeps them on one chain.
 *
 * The policy is [ExistingWorkPolicy.APPEND_OR_REPLACE], not KEEP: KEEP *silently drops* the new
 * request whenever the chain is still running, so tapping "restore" during a backup did nothing at
 * all - no error, no queue, no toast. APPEND_OR_REPLACE queues the restore behind the running
 * backup instead, and replaces a chain that already failed or was cancelled.
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
     * The URL the stored ETag / "we wrote the remote" flags belong to.
     *
     * They are global keys, so carrying them over to a different server would make the upload
     * treat a stranger's file as "ours" - the exact silent-overwrite bug the ETag check exists to
     * prevent. Changing the URL therefore clears both.
     */
    var savedWebDavUrl by remember { mutableStateOf<String?>(null) }

    /**
     * Passphrase for the archive uploaded to WebDAV. It is never shown back after being saved —
     * only a marker of whether one exists — because the settings store keeps it encrypted rather
     * than in a retrievable field, and re-prompting on every visit would defeat that.
     */
    var backupPassphrase by remember { mutableStateOf("") }
    var hasBackupPassphrase by remember { mutableStateOf(false) }

    // Where the last backup had to park this device's archive because the remote had changed, if it
    // had to at all. The worker writes it in the background, so it is read when the screen opens and
    // again whenever the app comes back to the foreground: the upload usually finishes while the
    // user is in another app, and a one-shot read would miss the copy it just kept.
    var conflictBackupUrl by remember { mutableStateOf(WebDavBackupState.conflictUrl(context)) }

    LaunchedEffect(Unit) {
        context.webDavDataStore.data.first().let { prefs ->
            webdavUrl = prefs[webdavUrlKey] ?: ""
            webdavUsername = prefs[webdavUsernameKey] ?: ""
            savedWebDavUrl = prefs[webdavUrlKey]
            webdavPassword = prefs[webdavPasswordKey]?.let { encryptedPassword ->
                WebDavPrefsCipher.decryptOrNull(aead, encryptedPassword) ?: ""
            } ?: ""
            hasBackupPassphrase = !prefs[backupPassphraseKey].isNullOrBlank()
        }
        conflictBackupUrl = WebDavBackupState.conflictUrl(context)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        conflictBackupUrl = WebDavBackupState.conflictUrl(context)
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
            try {
                TxtBackupHelper.writeNotesToZipStream(context, uri)
                Toast.makeText(context, context.getString(R.string.cloud_txt_backup_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                txtBackupUri = null
            }
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
                        // Awaiting the real result: the old fire-and-forget call returned before the
                        // archive was even decrypted, so the fixed one-second delay restarted the
                        // process mid-restore - "restored, restarting" for a restore that had not
                        // begun, or a database without its attachments. Restart only on success.
                        val success = settingsViewModel.importBackupAndWait(uri, context)
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.cloud_restored_restart), Toast.LENGTH_LONG).show()
                            kotlinx.coroutines.delay(1000)
                            ProcessPhoenix.triggerRebirth(context)
                        }
                        // A failure already showed its own toast (bad password, damaged file); no
                        // restart, so the user can retry without losing the running app.
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.cloud_restore_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    var showBackupPasswordPrompt by remember { mutableStateOf(false) }
    var showRestorePasswordPrompt by remember { mutableStateOf(false) }
    var showWebDavRestoreConfirm by remember { mutableStateOf(false) }
    var showLocalRestoreConfirm by remember { mutableStateOf(false) }

    var showAutoBackupPicker by remember { mutableStateOf(false) }
    /** Automatic backups this device can actually read back, newest first. */
    val autoBackups = remember(showAutoBackupPicker) { settingsViewModel.autoBackupFiles() }
    /** The archive the user picked; cleared once the confirmation is answered. */
    var pickedAutoBackup by remember { mutableStateOf<java.io.File?>(null) }
    /** Conflict-copy restore awaiting confirmation; null when none. */
    var conflictRestoreUrl by remember { mutableStateOf<String?>(null) }
    /** "Take over the remote" awaiting confirmation. */
    var showForceOverwriteConfirm by remember { mutableStateOf(false) }

    /**
     * Warns when the WebDAV server is reached over cleartext `http`.
     *
     * The Basic-auth header carries the username and password, so on `http` they cross the network
     * readable by anyone listening. Many self-hosted servers run on a LAN over plain http, so this
     * warns rather than blocks - but the user should know the trade-off they are making. Called from
     * [persistWebDavConfig], the single point every path writes the URL through.
     */
    fun warnIfCleartext(url: String) {
        val scheme = url.trim().substringBefore("://").lowercase()
        if (scheme == "http") {
            Toast.makeText(context, context.getString(R.string.cloud_webdav_cleartext_warning), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Writes the URL/credentials the workers read, clearing the remote validator when the server
     * changed.
     *
     * Every path that touches the URL used to write it inline, and only the Save button cleared the
     * stored ETag / remote-written flag. Editing the address and tapping "Backup" directly therefore
     * judged conflicts with a validator that belonged to the *old* server - a false conflict, or a
     * silent overwrite on a server that reports no ETag. One function, one rule: a new URL means a
     * new file, so nothing about the old one carries over.
     */
    suspend fun persistWebDavConfig() {
        warnIfCleartext(webdavUrl)
        context.webDavDataStore.edit { prefs ->
            val previousUrl = prefs[webdavUrlKey]
            val serverChanged = previousUrl != null && previousUrl != webdavUrl
            prefs[webdavUrlKey] = webdavUrl
            prefs[webdavUsernameKey] = webdavUsername
            if (webdavPassword.isNotBlank()) {
                prefs[webdavPasswordKey] = WebDavPrefsCipher.encrypt(aead, webdavPassword)
            } else {
                prefs.remove(webdavPasswordKey)
            }
            if (serverChanged) {
                prefs.remove(stringPreferencesKey(WebDavPrefs.ETAG))
                prefs.remove(booleanPreferencesKey(WebDavPrefs.REMOTE_WRITTEN))
            }
        }
    }

    /**
     * Both restore paths replace the whole local database. A single tap used to start that
     * immediately - the download runs in the background, so even "cancel" on a toast would not
     * have stopped it. The dialog is the only point where the user can still say no, so it states
     * exactly what is lost instead of asking "continue?".
     */
    fun startWebDavRestore() {
        coroutineScope.launch {
            persistWebDavConfig()
            workManager.enqueueUniqueWork(
                WEBDAV_SYNC_WORK,
                // APPEND_OR_REPLACE, not KEEP: KEEP dropped this restore outright whenever a
                // backup was still running - the user saw "restore started" and nothing happened.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<com.ezpnix.writeon.presentation.screens.settings.WebDAVRestoreWorker>()
                    .build()
            )
            Toast.makeText(context, context.getString(R.string.cloud_webdav_restore_started), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Restores the conflict copy at [url] instead of the canonical archive.
     *
     * The copy was kept precisely so it could be recovered from; this is that recovery. The URL is
     * passed as worker input, and the worker deliberately does not store its validator as the
     * canonical file's - the canonical file still holds the other device's backup.
     */
    fun startConflictRestore(url: String) {
        coroutineScope.launch {
            persistWebDavConfig()
            workManager.enqueueUniqueWork(
                WEBDAV_SYNC_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<com.ezpnix.writeon.presentation.screens.settings.WebDAVRestoreWorker>()
                    .setInputData(androidx.work.Data.Builder().putString(DATA_RESTORE_URL, url).build())
                    .build()
            )
            Toast.makeText(context, context.getString(R.string.cloud_webdav_restore_started), Toast.LENGTH_SHORT).show()
        }
    }

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
                    // URL, username and password go through the shared helper, which clears the
                    // stored validator when the server address changed.
                    persistWebDavConfig()
                    // The archive passphrase is written here because only this button can change it
                    // (the operation paths reuse whatever is already saved).
                    context.webDavDataStore.edit { prefs ->
                        if (backupPassphrase.isNotBlank()) {
                            prefs[backupPassphraseKey] = WebDavPrefsCipher.encrypt(aead, backupPassphrase)
                            hasBackupPassphrase = true
                        } else {
                            prefs.remove(backupPassphraseKey)
                            hasBackupPassphrase = false
                        }
                    }
                    savedWebDavUrl = webdavUrl
                    backupPassphrase = ""
                    Toast.makeText(context, context.getString(R.string.cloud_webdav_saved), Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(context.getString(R.string.cloud_save_webdav_info))
            }

            // The conflict copy only exists as a file on the server, so nothing in the app would
            // ever mention it: this card is the only place the user learns a backup did not
            // replace the remote one, and where the copy they can download lives.
            conflictBackupUrl?.let { conflictUrl ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.cloud_conflict_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.cloud_conflict_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = conflictUrl,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        context.getString(R.string.cloud_conflict_title),
                                        conflictUrl
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.msg_link_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }) {
                                Text(stringResource(R.string.cloud_conflict_copy))
                            }
                            // A conflict copy was unreachable from inside the app: the only way to
                            // get those notes back was to open the URL by hand and hope the file
                            // could be decrypted. Restoring it is the same destructive operation
                            // as a normal restore, so it gets the same confirmation.
                            TextButton(onClick = {
                                if (!hasBackupPassphrase) {
                                    Toast.makeText(context, context.getString(R.string.cloud_backup_passphrase_missing), Toast.LENGTH_LONG).show()
                                } else {
                                    conflictRestoreUrl = conflictUrl
                                }
                            }) {
                                Text(stringResource(R.string.cloud_conflict_restore))
                            }
                            // The escape hatch: a device that restored a conflict copy clears its
                            // validator, so without this it would keep making conflict copies
                            // forever and never reclaim the canonical file. This makes this
                            // device's notes the truth, overwriting the remote.
                            TextButton(onClick = { showForceOverwriteConfirm = true }) {
                                Text(stringResource(R.string.cloud_conflict_take_over))
                            }
                            TextButton(onClick = {
                                WebDavBackupState.clearConflict(context)
                                conflictBackupUrl = null
                            }) {
                                Text(stringResource(R.string.cloud_conflict_dismiss))
                            }
                        }
                    }
                }
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
                                persistWebDavConfig()
                                workManager.enqueueUniqueWork(
                                    WEBDAV_SYNC_WORK,
                                    ExistingWorkPolicy.APPEND_OR_REPLACE,
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
                        else -> showWebDavRestoreConfirm = true
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
                onSecondaryClick = { showLocalRestoreConfirm = true }
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

            // The daily archives live in private internal storage, so without this entry the
            // feature wrote files nothing in the app (or on the phone) could ever read back.
            // Restoring one is a same-device operation: the encryption key never leaves the
            // Keystore, which is what keeps the archives useless to anyone who copies them out.
            Spacer(Modifier.height(16.dp))
            BackupOptionCard(
                icon = Icons.Rounded.Restore,
                title = stringResource(R.string.auto_backup_restore),
                description = stringResource(R.string.auto_backup_restore_desc),
                primaryButtonText = stringResource(R.string.restore),
                onPrimaryClick = { showAutoBackupPicker = true }
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

    if (conflictRestoreUrl != null) {
        AlertDialog(
            onDismissRequest = { conflictRestoreUrl = null },
            title = { Text(stringResource(R.string.cloud_webdav_restore_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_webdav_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    val url = conflictRestoreUrl
                    conflictRestoreUrl = null
                    url?.let { startConflictRestore(it) }
                }) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { conflictRestoreUrl = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showForceOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = { showForceOverwriteConfirm = false },
            title = { Text(stringResource(R.string.cloud_conflict_take_over_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_conflict_take_over_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForceOverwriteConfirm = false
                    workManager.enqueueUniqueWork(
                        WEBDAV_SYNC_WORK,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<com.ezpnix.writeon.presentation.screens.settings.WebDAVBackupWorker>()
                            .setInputData(androidx.work.Data.Builder().putBoolean(DATA_FORCE_OVERWRITE, true).build())
                            .build()
                    )
                    Toast.makeText(context, context.getString(R.string.cloud_webdav_backup_started), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.cloud_conflict_take_over))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceOverwriteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showWebDavRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showWebDavRestoreConfirm = false },
            title = { Text(stringResource(R.string.cloud_webdav_restore_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_webdav_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showWebDavRestoreConfirm = false
                    startWebDavRestore()
                }) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebDavRestoreConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLocalRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showLocalRestoreConfirm = false },
            title = { Text(stringResource(R.string.cloud_local_restore_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_local_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocalRestoreConfirm = false
                    if (settingsViewModel.settings.value.encryptBackup) {
                        showRestorePasswordPrompt = true
                    } else {
                        settingsViewModel.password = null
                        importBackupLauncher.launch(arrayOf("application/zip"))
                    }
                }) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocalRestoreConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAutoBackupPicker) {
        AlertDialog(
            onDismissRequest = { showAutoBackupPicker = false },
            title = { Text(stringResource(R.string.auto_backup_restore_pick)) },
            text = {
                if (autoBackups.isEmpty()) {
                    Text(stringResource(R.string.auto_backup_none))
                } else {
                    // Seven daily archives at large type overrun a fixed dialog, clipping the
                    // oldest entries where they could not be tapped. Cap the height and scroll.
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        autoBackups.forEach { file ->
                            TextButton(onClick = {
                                pickedAutoBackup = file
                                showAutoBackupPicker = false
                            }) {
                                Text(
                                    // The file name carries the timestamp (notes-backup-YYYYMMDD_HHMMSS.zip.enc),
                                    // so it is both the label and the disambiguator for multiple archives.
                                    text = file.name.removeSuffix(".zip.enc"),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (autoBackups.isEmpty()) {
                    TextButton(onClick = { showAutoBackupPicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
            dismissButton = if (autoBackups.isEmpty()) null else {
                {
                    TextButton(onClick = { showAutoBackupPicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    pickedAutoBackup?.let { file ->
        val stamp = remember(file) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(file.lastModified()))
        }
        AlertDialog(
            onDismissRequest = { pickedAutoBackup = null },
            title = { Text(stringResource(R.string.auto_backup_restore_confirm_title)) },
            text = {
                Text(stringResource(R.string.auto_backup_restore_confirm_message, stamp))
            },
            confirmButton = {
                TextButton(onClick = {
                    pickedAutoBackup = null
                    settingsViewModel.onRestoreAutoBackup(file, context) { success ->
                        if (success) {
                            // The replaced database is only picked up by a fresh process, exactly
                            // like the SAF and WebDAV restore paths.
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(1000)
                                ProcessPhoenix.triggerRebirth(context)
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickedAutoBackup = null }) {
                    Text(stringResource(R.string.cancel))
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