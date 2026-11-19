package com.ezpnix.writeon.presentation.screens.home.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R

/**
 * Tells the user that vault notes exist but are locked, and offers the session unlock.
 *
 * The vault rows were filtered out of the list with no indicator at all, so a locked vault looked
 * exactly like deleted notes. This banner is the missing "there is something here" signal; the
 * dialog loads the passphrase into memory and re-reads the list, after which the banner disappears
 * on its own (or turns into a wrong-password hint if the rows still will not decrypt).
 *
 * Rendered only while [lockedCount] > 0: showing it unconditionally parked "Locked vault notes: 0"
 * on the first screen of every healthy library.
 */
@Composable
fun VaultLockBanner(
    lockedCount: Int,
    unlockAttempted: Boolean,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    if (lockedCount <= 0) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.vault_locked_notes, lockedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (unlockAttempted) {
                    Text(
                        text = stringResource(R.string.vault_wrong_password),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = { dialogOpen = true }) {
                Text(stringResource(R.string.vault_unlock))
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.vault_unlock)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (password.isNotBlank()) {
                            onUnlock(password)
                            password = ""
                            dialogOpen = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.password_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialogOpen = false
                    password = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
