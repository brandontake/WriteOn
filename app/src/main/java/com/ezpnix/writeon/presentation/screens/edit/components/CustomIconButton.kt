package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R

@Composable
fun CustomIconButton(
    shape: RoundedCornerShape,
    elevation: Dp,
    icon: ImageVector,
    onClick: () -> Unit,
    /**
     * Accessible name of the button.
     *
     * The button is a card with an icon and no visible text, so the icon is the only thing a screen
     * reader had to go on - and the icon's own description was the empty string, which TalkBack
     * announces as an unlabelled button (U1). Callers may pass the name they want; when they do not
     * it is derived from the icon, so the two icons this button actually shows ("edit" and
     * "preview") are never silent. Keeping it a defaulted parameter means existing call sites keep
     * compiling unchanged.
     */
    contentDescription: String? = null,
) {
    val label = contentDescription ?: when (icon) {
        Icons.Rounded.Edit -> stringResource(R.string.edit)
        Icons.Rounded.RemoveRedEye -> stringResource(R.string.cd_preview)
        // Unknown icon and no explicit name: leave it unlabelled rather than invent a wrong one.
        // Any new call site should pass `contentDescription` instead of relying on this branch.
        else -> null
    }

    ElevatedCard(
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .clip(shape)
            .clickable(
                onClickLabel = label,
                role = Role.Button,
                onClick = { onClick() }
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.padding(16.dp),
        )
    }
}
