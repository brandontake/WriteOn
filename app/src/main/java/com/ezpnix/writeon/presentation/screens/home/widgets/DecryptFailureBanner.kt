package com.ezpnix.writeon.presentation.screens.home.widgets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R

/**
 * Says how many notes this process could not decrypt.
 *
 * `EncryptedNoteDao` degrades an unreadable row instead of dropping it or crashing the list, so
 * without this banner a user would see a note titled "⚠️ cannot decrypt" and no hint that the
 * problem is a lost encryption key rather than their own typing. Same card pattern as
 * [VaultLockBanner], minus the unlock action: there is nothing to press here, the row must only
 * stop being written (see `NoteUseCase.refusesUndecryptable`).
 *
 * Hidden while [failedCount] is 0 - a healthy library must not carry a permanent warning.
 */
@Composable
fun DecryptFailureBanner(
    failedCount: Int,
    modifier: Modifier = Modifier,
) {
    if (failedCount <= 0) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.decrypt_failed_notes, failedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
