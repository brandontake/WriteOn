package com.ezpnix.writeon.presentation.screens.settings.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SettingActionType {
    NAVIGATE,
    LINK
}

@Composable
fun SettingCategory(
    title: String,
    subTitle: String = "",
    icon: ImageVector,
    shape: RoundedCornerShape,
    isLast: Boolean = false,
    smallSetting: Boolean = false,
    actionType: SettingActionType = SettingActionType.NAVIGATE,
    linkClicked: () -> Unit = {},
    action: () -> Unit = {},
    composableAction: @Composable (() -> Unit) -> Unit = {},
) {
    var showCustomAction by remember { mutableStateOf(false) }
    if (showCustomAction) composableAction { showCustomAction = !showCustomAction }
    ElevatedCard(
        shape = shape,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(shape)
            .clickable {
                showCustomAction = !showCustomAction
                when (actionType) {
                    SettingActionType.LINK -> linkClicked()
                    SettingActionType.NAVIGATE -> action()
                }
            },
        colors = if (smallSetting)
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        else
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = if (smallSetting) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (smallSetting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RenderCategoryTitle(title = title, smallSetting = true)
                    Spacer(modifier = Modifier.weight(1f))
                    RenderCategoryDescription(subTitle = subTitle, smallSetting = true)
                    RenderCategoryIcon(icon, isHighlighted = true)
                }
            } else {
                Column {
                    RenderCategoryTitle(title = title, smallSetting = false)
                    RenderCategoryDescription(subTitle = subTitle, smallSetting = false)
                }
                Spacer(modifier = Modifier.weight(1f))
                RenderCategoryIcon(icon, isHighlighted = false)
            }
        }
    }
    Spacer(modifier = Modifier.height(if (isLast) 16.dp else 4.dp))
}

@Composable
private fun RenderCategoryTitle(title: String, smallSetting: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (smallSetting) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun RenderCategoryDescription(subTitle: String, smallSetting: Boolean) {
    if (subTitle.isNotBlank()) {
        Text(
            color = if (smallSetting) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
            text = subTitle,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RenderCategoryIcon(icon: ImageVector, isHighlighted: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(50)
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isHighlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .scale(if (isHighlighted) 0.85f else 1f)
                .padding(6.dp)
        )
    }
}