package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.ezpnix.writeon.R

private const val TAG = "TextFormattingToolbar"

/** 100 MB: matches the share-path cap, far above any photo, far below "fills the partition". */
private const val MAX_PICKED_IMAGE_BYTES = 100L * 1024 * 1024

/**
 * DAT-08: suffix of the same-directory staging file a picked image is copied and encrypted through.
 *
 * It is an attachment-directory sibling so the final publish is a rename on the same filesystem;
 * the name is random (`createTempFile`), so two picks of the same URI cannot share it.
 */
private const val IMAGE_PART_SUFFIX = ".image-part"

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
                // UX-11: every failure used to collapse into an empty string and then into silence,
                // so a pick that could not be read, was too large or could not be encrypted looked
                // like a broken button. The result now carries a reason, and nothing is inserted
                // unless the encrypted file really exists.
                when (val result = withContext(Dispatchers.IO) {
                    saveImageToAppStorage(context, uri)
                }) {
                    is ImageSaveResult.Success -> viewModel.insertImageAttachment(result.path)
                    is ImageSaveResult.Failure -> Toast.makeText(
                        context,
                        context.getString(
                            R.string.msg_image_insert_failed,
                            context.getString(result.reasonRes)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
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

/** The outcome of copying a picked image into app storage, with a localizable failure reason. */
internal sealed interface ImageSaveResult {
    data class Success(val path: String) : ImageSaveResult
    data class Failure(@StringRes val reasonRes: Int) : ImageSaveResult
}

/** Raised when the pick exceeds [MAX_PICKED_IMAGE_BYTES]; kept distinct so the UI can say why. */
private class PickedImageTooLargeException : java.io.IOException(
    "picked image exceeds $MAX_PICKED_IMAGE_BYTES bytes"
)

private fun saveImageToAppStorage(context: Context, uri: Uri): ImageSaveResult {
    // Internal storage, not getExternalFilesDir(): a picked picture is user content and must stay
    // behind the same filesystem boundary as the (encrypted) note database.
    val appStorageDir = AttachmentFiles.directoryFor(context, Attachment.TYPE_IMAGE)
    // A null stream means the resolver could not hand back the bytes, which is a read failure
    // rather than an empty file. The stream is consumed inside saveImageFile and closed by `use`.
    val input = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Could not open the picked image", e)
        return ImageSaveResult.Failure(R.string.image_insert_unreadable)
    } ?: return ImageSaveResult.Failure(R.string.image_insert_unreadable)

    return input.use { stream ->
        saveImageFile(
            directory = appStorageDir,
            fileName = getImageName(uri),
            source = stream,
            // SEC-4: encryption happens before the path is returned, on this same IO dispatcher.
            // A failure returns no path, so the caller never inserts an attachment row for a
            // plaintext file.
            encrypt = { temp -> MediaVault.encryptInPlace(context, temp) },
        )
    }
}

/**
 * Copies [source] into a fresh sibling of [fileName], encrypts it there and only then publishes it.
 *
 * DAT-08: the target used to be opened directly with `FileOutputStream`, which truncates an existing
 * file before the pick has even been read. Re-picking a URI whose name already existed destroyed
 * the live attachment - and, because [AttachmentSnapshots] hard-links the media trees, the `.bak`
 * snapshot sharing that inode as well. The copy now goes to a unique temp in the same directory;
 * [encrypt] runs on the temp, and a same-directory rename publishes the finished bytes. Any failure
 * deletes only the temp, so the previous target stays byte for byte.
 *
 * Size limit, naming and the failure reasons of UX-11 are unchanged. [maxBytes] is a parameter only
 * so the size branch can be tested without writing 100 MB.
 */
internal fun saveImageFile(
    directory: File,
    fileName: String,
    source: InputStream,
    maxBytes: Long = MAX_PICKED_IMAGE_BYTES,
    encrypt: (File) -> Boolean,
): ImageSaveResult {
    if (!directory.exists() && !directory.mkdirs()) {
        android.util.Log.e(TAG, "Could not create the attachment directory")
        return ImageSaveResult.Failure(R.string.image_insert_storage_failed)
    }
    val target = File(directory, fileName)
    val temp = try {
        File.createTempFile("${target.name}.", IMAGE_PART_SUFFIX, directory)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Could not create the image staging file", e)
        return ImageSaveResult.Failure(R.string.image_insert_storage_failed)
    }

    return try {
        FileOutputStream(temp).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                // Same guard the share path has: a mistaken pick (a video offered as an image) must
                // not be able to fill the private partition, and a partial copy is only the temp.
                if (total > maxBytes) throw PickedImageTooLargeException()
                output.write(buffer, 0, read)
            }
        }

        if (!temp.isFile) {
            android.util.Log.e(TAG, "Could not save the picked image to app storage")
            return ImageSaveResult.Failure(R.string.image_insert_unreadable)
        }

        if (!encrypt(temp)) {
            android.util.Log.e(TAG, "Could not encrypt the picked image; dropping the plaintext copy")
            return ImageSaveResult.Failure(R.string.image_insert_encrypt_failed)
        }

        if (!publishImage(temp, target)) {
            android.util.Log.e(TAG, "Could not move the encrypted image into place")
            return ImageSaveResult.Failure(R.string.image_insert_storage_failed)
        }
        // A filesystem path, not a file:// Uri: the attachment row is read with File(path) by the
        // exporter and by AttachmentFiles, both of which would treat a Uri string as a literal name.
        ImageSaveResult.Success(target.absolutePath)
    } catch (e: PickedImageTooLargeException) {
        android.util.Log.e(TAG, "Picked image is too large to import", e)
        ImageSaveResult.Failure(R.string.image_insert_too_large)
    } catch (e: Exception) {
        // A failed read and a failed write (disk full, revoked URI) both end here; the user-facing
        // distinction that matters is "could not be written", not the exception type.
        android.util.Log.e(TAG, "Could not copy the picked image into app storage", e)
        ImageSaveResult.Failure(R.string.image_insert_storage_failed)
    } finally {
        // The rename above consumes the temp on success; every other path must not leave plaintext
        // or a half copy behind. The existing target is never deleted here.
        if (temp.exists()) runCatching { temp.delete() }
    }
}

/**
 * Publishes the encrypted temp over [target] with a same-directory rename.
 *
 * Never delete-then-rename: if the target is hard-linked from an attachment snapshot, deleting it
 * would not harm the snapshot (the inode survives), but a delete followed by a failed rename would
 * destroy the live attachment with nothing to go back to. The rename swaps only the directory
 * entry, so a failure leaves the old bytes in place. A provider that cannot do an atomic move gets
 * the platform's best-effort replace; it never falls back to truncating the target.
 */
private fun publishImage(temp: File, target: File): Boolean = try {
    Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
    )
    true
} catch (e: AtomicMoveNotSupportedException) {
    runCatching {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess
} catch (e: Exception) {
    false
}