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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.StrikethroughS
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
fun TextFormattingToolbar(viewModel: EditViewModel) {
    val colorArrow = MaterialTheme.colorScheme.outlineVariant
    val colorIcon = MaterialTheme.colorScheme.inverseSurface
    var currentIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val savedUri = saveImageToAppStorage(context, it)
            // An empty result means the copy failed; inserting "!()" would leave a broken image
            // insertion in the note body.
            if (savedUri.isNotEmpty()) {
                viewModel.insertText("!($savedUri)")
            }
        }
    }

    val toolbarSets = remember {
        listOf(
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {

                },
                ToolbarItem(Icons.Rounded.CheckBox, context.getString(R.string.fmt_checkbox), color = colorIcon) {
                    viewModel.insertText("[x] \n[ ] ")
                },
                ToolbarItem(
                    Icons.Rounded.FormatListNumbered, context.getString(R.string.fmt_order_list), color = colorIcon) {
                    viewModel.insertNumberedText()
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ViewList, context.getString(R.string.fmt_bullet_list), color = colorIcon) {
                    viewModel.insertText("- ")
                },
                        ToolbarItem(Icons.Rounded.Image, context.getString(R.string.fmt_insert_image), color = colorIcon) {
                    launcher.launch("image/*")
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {
                        currentIndex++
                },
            ),
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.FormatBold, context.getString(R.string.fmt_bold), color = colorIcon) {
                    viewModel.insertText("**", offset = 2, newLine = false)
                },
                ToolbarItem(Icons.Rounded.FormatUnderlined, context.getString(R.string.fmt_underline), color = colorIcon) {
                    viewModel.insertText("__", -1 , newLine = false)
                },
                ToolbarItem(Icons.Rounded.StrikethroughS, context.getString(R.string.fmt_strikethrough), color = colorIcon) {
                    viewModel.insertText("~~~~", -2 , newLine = false)
                },
                ToolbarItem(Icons.Rounded.AutoFixHigh, context.getString(R.string.fmt_highlight), color = colorIcon) {
                    viewModel.insertText("====", offset = -2, newLine = false)
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {
                    currentIndex++
                },
            ),
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.FormatItalic, context.getString(R.string.fmt_italic), color = colorIcon) {
                    viewModel.insertText("***", offset = -2, newLine = false)
                },
                ToolbarItem(Icons.Rounded.Code, context.getString(R.string.fmt_code_block), color = colorIcon) {
                    viewModel.insertText("```\n\n```", -4)
                },
                ToolbarItem(Icons.Rounded.FormatQuote, context.getString(R.string.fmt_quote), color = colorIcon) {
                    viewModel.insertText("> ", newLine = true)
                },
                ToolbarItem(Icons.Rounded.AccessTime, context.getString(R.string.fmt_insert_datetime), color = colorIcon) {
                    val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    viewModel.insertText(dateTime, newLine = false)
                },
                        ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos,context.getString(R.string.fmt_bullet_list), color = colorArrow) {
                },
            ),
        )
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        toolbarSets.getOrNull(currentIndex)?.let { toolbarItems ->
            toolbarItems.forEach { item ->
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