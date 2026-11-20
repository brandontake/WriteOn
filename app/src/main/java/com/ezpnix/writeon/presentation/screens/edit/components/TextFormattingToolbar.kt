package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.Context

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatIndentIncrease
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StrikethroughS
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.core.attachment.AttachmentFiles
import com.ezpnix.writeon.core.attachment.MediaVault
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.presentation.components.getImageName
import com.ezpnix.writeon.presentation.screens.edit.model.EditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R

private const val TAG = "TextFormattingToolbar"

/** 100 MB: matches the share-path cap, far above any photo, far below "fills the partition". */
private const val MAX_PICKED_IMAGE_BYTES = 100L * 1024 * 1024

data class ToolbarItem(
    val icon: ImageVector,
    val contentDescription: String,
    val color: Color,
    /**
     * False for the two arrows that sit at the ends of the strip and have no page to go to. They
     * used to stay enabled with an empty click body: the only feedback was a slightly different
     * icon tint, so a tap looked broken and TalkBack still announced a working button (U12).
     *
     * Declared before [onClickAction] on purpose: every call site passes the click as a trailing
     * lambda, and Kotlin only binds a trailing lambda to the *last* parameter. Putting this last
     * made the trailing lambda land on a Boolean.
     */
    val enabled: Boolean = true,
    val onClickAction: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextFormattingToolbar(viewModel: EditViewModel, modifier: Modifier = Modifier) {
    // outlineVariant is a border colour: on the toolbar surface the arrows were effectively
    // invisible. onSurfaceVariant keeps them legible in both themes while staying secondary.
    val colorArrow = MaterialTheme.colorScheme.onSurfaceVariant
    val colorIcon = MaterialTheme.colorScheme.inverseSurface
    var currentIndex by remember { mutableIntStateOf(0) }
    var showRecordAudioDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // A picked photo is tens of megabytes, and the copy used to run inside this result
            // callback on the main thread: the editor froze for the whole copy (P-11). The read is
            // started here, where the grant on `uri` is still valid, and finished on Dispatchers.IO.
            scope.launch {
                val savedUri = withContext(Dispatchers.IO) {
                    // The size guard throws; catching keeps an oversized pick from crashing the app
                    // out of the result callback and turns it into the same "failed" path as I/O.
                    runCatching { saveImageToAppStorage(context, uri) }.getOrDefault("")
                }
                // An empty result means the copy failed; inserting a reference to nothing would
                // leave a permanently unavailable attachment in the note body.
                if (savedUri.isNotEmpty()) {
                    viewModel.insertImageAttachment(savedUri)
                }
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
                ToolbarItem(
                    icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
                    contentDescription = context.getString(R.string.cd_toolbar_prev_page),
                    color = colorArrow,
                    enabled = false,
                ) {},
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
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, context.getString(R.string.cd_toolbar_next_page), color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 1: Structure & Typography (5 items: Heading, Link, Divider, Quote, Code)
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, context.getString(R.string.cd_toolbar_prev_page), color = colorArrow) {
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
                ToolbarItem(Icons.Rounded.Terminal, context.getString(R.string.fmt_code_block), color = colorIcon) {
                    viewModel.insertText("```\n\n```", -4)
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, context.getString(R.string.cd_toolbar_next_page), color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 2: Inline styles (5 items: Bold, Italic, Underline, Strikethrough, Highlight).
            // These act on the selection - see EditViewModel.applyFormat - so a highlighted word is
            // wrapped rather than having markers typed in front of it. Inline code moved to page 3:
            // it shares a page with the other block-level insertions, and its icon no longer has to
            // double as the code-block button.
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, context.getString(R.string.cd_toolbar_prev_page), color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.FormatBold, context.getString(R.string.fmt_bold), color = colorIcon) {
                    viewModel.applyFormat("**")
                },
                ToolbarItem(Icons.Rounded.FormatItalic, context.getString(R.string.fmt_italic), color = colorIcon) {
                    viewModel.applyFormat("*")
                },
                ToolbarItem(Icons.Rounded.FormatUnderlined, context.getString(R.string.fmt_underline), color = colorIcon) {
                    viewModel.applyFormat("_")
                },
                ToolbarItem(Icons.Rounded.StrikethroughS, context.getString(R.string.fmt_strikethrough), color = colorIcon) {
                    viewModel.applyFormat("~~")
                },
                ToolbarItem(Icons.Rounded.AutoFixHigh, context.getString(R.string.fmt_highlight), color = colorIcon) {
                    viewModel.applyFormat("==")
                },
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowForwardIos, context.getString(R.string.cd_toolbar_next_page), color = colorArrow) {
                    currentIndex++
                },
            ),
            // Page 3: Blocks and literal text (inline code, table, indent, escape, date/time).
            listOf(
                ToolbarItem(Icons.AutoMirrored.Rounded.ArrowBackIos, context.getString(R.string.cd_toolbar_prev_page), color = colorArrow) {
                    currentIndex--
                },
                ToolbarItem(Icons.Rounded.Code, context.getString(R.string.fmt_inline_code), color = colorIcon) {
                    viewModel.applyFormat("`")
                },
                ToolbarItem(Icons.Rounded.TableChart, context.getString(R.string.fmt_table), color = colorIcon) {
                    viewModel.insertTable()
                },
                ToolbarItem(Icons.Rounded.FormatIndentIncrease, context.getString(R.string.fmt_indent), color = colorIcon) {
                    viewModel.applyIndent()
                },
                ToolbarItem(Icons.Rounded.DataObject, context.getString(R.string.fmt_escape), color = colorIcon) {
                    viewModel.applyEscape()
                },
                ToolbarItem(Icons.Rounded.AccessTime, context.getString(R.string.fmt_insert_datetime), color = colorIcon) {
                    val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    viewModel.insertText(dateTime, newLine = false)
                },
                ToolbarItem(
                    icon = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = context.getString(R.string.cd_toolbar_next_page),
                    color = colorArrow,
                    enabled = false,
                ) {},
            ),
        )
    }
    // A page of this strip is seven 48dp targets. On a 320dp-wide phone that is wider than the
    // screen, so the last target used to be pushed off the edge with no way to reach it (U12). The
    // strip scrolls horizontally now. `widthIn(min = viewportWidth)` keeps the old edge-to-edge
    // spacing on windows that are wide enough in the first place, because SpaceBetween needs the
    // Row itself to be as wide as the viewport, not just the scroll container.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        val viewportWidth = maxWidth
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = viewportWidth)
        ) {
            toolbarSets.getOrNull(currentIndex)?.let { toolbarItems ->
                toolbarItems.forEach { item ->
                    val isArrow = item.color == colorArrow
                    // An explicit tint overrides LocalContentColor, so the disabled state has to be
                    // dimmed by hand; IconButton(enabled = false) alone would leave the icon opaque.
                    val tint = if (item.enabled) item.color else item.color.copy(alpha = 0.38f)
                    if (isArrow) {
                        IconButton(
                            onClick = { item.onClickAction.invoke() },
                            enabled = item.enabled,
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.contentDescription,
                                modifier = Modifier.size(20.dp),
                                tint = tint,
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
                                enabled = item.enabled,
                            ) {
                                Icon(
                                    item.icon,
                                    contentDescription = item.contentDescription,
                                    modifier = Modifier.size(20.dp),
                                    tint = tint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun saveImageToAppStorage(context: Context, uri: Uri): String {
    // Internal storage, not getExternalFilesDir(): a picked picture is user content and must stay
    // behind the same filesystem boundary as the (encrypted) note database.
    val appStorageDir = AttachmentFiles.directoryFor(context, Attachment.TYPE_IMAGE)
    if (!appStorageDir.exists() && !appStorageDir.mkdirs()) {
        android.util.Log.e(TAG, "Could not create the attachment directory")
        return ""
    }
    val imageFile = File(appStorageDir, getImageName(uri))

    // `use` already closes the stream; the extra close() below was a double close. If the resolver
    // cannot hand back a stream the file is never written, so report that instead of returning a
    // URI that points at nothing.
    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(imageFile).use { output ->
            // Same guard the share path has: a mistaken pick (a video offered as an image) must
            // not be able to fill the private partition, and a partial copy is removed below.
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PICKED_IMAGE_BYTES) {
                    throw java.io.IOException("picked image exceeds $MAX_PICKED_IMAGE_BYTES bytes")
                }
                output.write(buffer, 0, read)
            }
        }
        true
    } ?: false

    if (!copied || !imageFile.exists()) {
        android.util.Log.e(TAG, "Could not save the picked image to app storage")
        runCatching { imageFile.delete() }
        return ""
    }

    // SEC-4: encrypt before the path is returned, on this same IO dispatcher. Returning "" instead
    // of a plaintext path means the caller never inserts an attachment row for it, so the rest of
    // the app cannot reference an unencrypted file.
    if (!MediaVault.encryptInPlace(context, imageFile)) {
        android.util.Log.e(TAG, "Could not encrypt the picked image; dropping the plaintext copy")
        runCatching { imageFile.delete() }
        return ""
    }
    // A filesystem path, not a file:// Uri: the attachment row is read with File(path) by the
    // exporter and by AttachmentFiles, both of which would treat a Uri string as a literal name.
    return imageFile.absolutePath
}