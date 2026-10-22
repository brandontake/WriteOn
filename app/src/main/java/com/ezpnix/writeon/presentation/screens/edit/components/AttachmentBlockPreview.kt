package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.components.markdown.AudioAttachmentBlock
import com.ezpnix.writeon.presentation.components.markdown.ImageAttachmentBlock
import com.ezpnix.writeon.presentation.screens.edit.segments.AttachmentSegment

/**
 * One attachment as it appears inside the segmented editor.
 *
 * Tapping a picture opens it full-screen; long-pressing either kind opens the two operations that
 * do not belong in the reader: edit the Markdown line (for repairing a pasted id or changing a
 * caption) and delete the attachment. The overflow button is the discoverable version of the same
 * long press, because a long press on a slider or a play button is easy to miss.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentBlockPreview(
    segment: AttachmentSegment,
    radius: Int,
    onEditSource: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember(segment.key) { mutableStateOf(false) }
    var showFullScreen by remember(segment.key) { mutableStateOf(false) }
    val target = AttachmentSyntax.SCHEME + segment.attachmentId
    val isImage = segment.type == Attachment.TYPE_IMAGE

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isImage && AttachmentRegistry.resolve(target) != null) {
                        showFullScreen = true
                    }
                },
                onLongClick = { menuExpanded = true }
            )
    ) {
        if (isImage) {
            ImageAttachmentBlock(
                target = target,
                radius = radius,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        } else {
            AudioAttachmentBlock(
                target = target,
                radius = radius,
                modifier = Modifier.padding(vertical = 2.dp),
                caption = segment.caption.takeIf { it.isNotBlank() },
                onRename = onRename
            )
        }

        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.padding(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.attachment_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.attachment_edit_source)) },
                    onClick = {
                        menuExpanded = false
                        onEditSource()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.attachment_delete)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }

    if (showFullScreen) {
        val path = AttachmentRegistry.resolve(target)
        Dialog(
            onDismissRequest = { showFullScreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .combinedClickable(
                            onClick = { showFullScreen = false },
                            onLongClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (path != null) {
                        AsyncImage(
                            model = path,
                            contentDescription = stringResource(R.string.attachment_open_image),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}
