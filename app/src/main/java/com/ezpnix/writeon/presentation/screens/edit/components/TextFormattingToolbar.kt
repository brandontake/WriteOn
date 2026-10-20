package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.Context

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StrikethroughS
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.presentation.components.getExternalStorageDir
import com.ezpnix.writeon.presentation.components.getImageName
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R

private const val TAG = "TextFormattingToolbar"

data class ToolbarItem(
    val icon: ImageVector,
    val contentDescription: String,
    val color: Color,
    val onClickAction: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextFormattingToolbar(viewModel: EditViewModel, modifier: Modifier = Modifier) {
    val colorArrow = MaterialTheme.colorScheme.outlineVariant
    val colorIcon = MaterialTheme.colorScheme.inverseSurface
    var currentIndex by remember { mutableIntStateOf(0) }
    var showRecordAudioDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val savedUri = saveImageToAppStorage(context, it)
            // An empty result means the copy failed; inserting a reference to nothing would leave a
            // permanently unavailable attachment in the note body.
            if (savedUri.isNotEmpty()) {
                viewModel.insertImageAttachment(savedUri)
            }
        }
    }

    if (showRecordAudioDialog) {
        AudioRecordDialog(
            onDismiss = { showRecordAudioDialog = false },
            onAudioRecorded = { audioFile ->
                viewModel.insertAudioAttachment(audioFile.absolutePath, audioFile.nameWithoutExtension)
            }
        )
    }

    val toolbarSets = remember {
        listOf(
            // Page 0: Common Actions (5 items: Checkbox, Numbered, Bullet, Image, Mic)
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, "", color = colorArrow) {
                },
                ToolbarItem(Icons.Rounded.CheckBox, context.getString(R.string.fmt_checkbox), color = colorIcon) {
                    viewModel.insertText("[x] \n[ ] ")
                },
                ToolbarItem(Icons.Rounded.FormatListNumbered, context.getString(R.string.fmt_order_list), color = colorIcon) {
                    viewModel.insertNumberedText()
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ViewList, context.getString(R.string.fmt_bullet_list), color = colorIcon) {
                    viewModel.insertText("- ")
                },
                ToolbarItem(Icons.Rounded.Image, context.getString(R.string.fmt_insert_image), color = colorIcon) {
                    launcher.launch("image/*")
                },
                ToolbarItem(Icons.Rounded.Mic, context.getString(R.string.fmt_record_audio), color = colorIcon) {
                    showRecordAudioDialog = true
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, "", color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 1: Structure & Typography (5 items: Heading, Link, Divider, Quote, Code)
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, "", color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.Title, context.getString(R.string.fmt_heading), color = colorIcon) {
                    viewModel.insertText("## ", newLine = true)
                },
                ToolbarItem(Icons.Rounded.Link, context.getString(R.string.fmt_link), color = colorIcon) {
                    viewModel.insertText("[](url)", offset = -5, newLine = false)
                },
                ToolbarItem(Icons.Rounded.HorizontalRule, context.getString(R.string.fmt_divider), color = colorIcon) {
                    viewModel.insertText("\n---\n", newLine = false)
                },
                ToolbarItem(Icons.Rounded.FormatQuote, context.getString(R.string.fmt_quote), color = colorIcon) {
                    viewModel.insertText("> ", newLine = true)
                },
                ToolbarItem(Icons.Rounded.Code, context.getString(R.string.fmt_code_block), color = colorIcon) {
                    viewModel.insertText("```\n\n```", -4)
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, "", color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 2: Inline styles (5 items: Bold, Italic, Strikethrough, Highlight, Inline code).
            // These act on the selection - see EditViewModel.applyFormat - so a highlighted word is
            // wrapped rather than having markers typed in front of it. Underline left the toolbar:
            // it was the one style the renderer never agreed with, and inline code is more useful.
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, "", color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.FormatBold, context.getString(R.string.fmt_bold), color = colorIcon) {
                    viewModel.applyFormat("**")
                },
                ToolbarItem(Icons.Rounded.FormatItalic, context.getString(R.string.fmt_italic), color = colorIcon) {
                    viewModel.applyFormat("*")
                },
                ToolbarItem(Icons.Rounded.StrikethroughS, context.getString(R.string.fmt_strikethrough), color = colorIcon) {
                    viewModel.applyFormat("~~")
                },
                ToolbarItem(Icons.Rounded.AutoFixHigh, context.getString(R.string.fmt_highlight), color = colorIcon) {
                    viewModel.applyFormat("==")
                },
                ToolbarItem(Icons.Rounded.Code, context.getString(R.string.fmt_inline_code), color = colorIcon) {
                    viewModel.applyFormat("`")
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, "", color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 3: Additional Tools (DateTime)
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, "", color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.AccessTime, context.getString(R.string.fmt_insert_datetime), color = colorIcon) {
                    val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    viewModel.insertText(dateTime, newLine = false)
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, "", color = colorArrow) {
                },
            ),
        )
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        toolbarSets.getOrNull(currentIndex)?.let { toolbarItems ->
            toolbarItems.forEach { item ->
                val isArrow = item.color == colorArrow
                if (isArrow) {
                    IconButton(
                        onClick = { item.onClickAction.invoke() },
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.contentDescription,
                            modifier = Modifier.size(20.dp),
                            tint = item.color,
                        )
                    }
                } else {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = {
                            PlainTooltip {
                                Text(item.contentDescription)
                            }
                        },
                        state = rememberTooltipState()
                    ) {
                        IconButton(
                            onClick = { item.onClickAction.invoke() },
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.contentDescription,
                                modifier = Modifier.size(20.dp),
                                tint = item.color,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun saveImageToAppStorage(context: Context, uri: Uri): String {
    val appStorageDir = getExternalStorageDir(context)
    if (!appStorageDir.exists()) {
        appStorageDir.mkdirs()
    }
    val imageFile = File(appStorageDir, getImageName(uri))

    // `use` already closes the stream; the extra close() below was a double close. If the resolver
    // cannot hand back a stream the file is never written, so report that instead of returning a
    // URI that points at nothing.
    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(imageFile).use { output ->
            input.copyTo(output)
        }
        true
    } ?: false

    if (!copied || !imageFile.exists()) {
        android.util.Log.e(TAG, "Could not save the picked image to app storage")
        return ""
    }
    return Uri.fromFile(imageFile).toString()
}