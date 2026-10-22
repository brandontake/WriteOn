package com.ezpnix.writeon.presentation.components.markdown

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.theme.appCornerShape
import com.ezpnix.writeon.presentation.theme.CornerFraction
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Upper bound on the bitmap coil is allowed to decode for a note image.
 *
 * Notes draw at full width and never more than 400.dp tall, so decoding much past this wastes memory
 * without changing what the reader sees. 1440 covers a high-density screen at that height with room
 * to spare, and still keeps a 12-megapixel photo from allocating its full size.
 */
internal const val IMAGE_DECODE_MAX_WIDTH_PX = 1440
internal const val IMAGE_DECODE_MAX_HEIGHT_PX = 1440

@Composable
fun MarkdownCodeBlock(
    color: Color,
    text: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.padding(top = 6.dp),
        content = {
            Surface(
                color = color,
                modifier = Modifier
                    .clip(appCornerShape(CornerFraction.TINY))
                    .fillMaxWidth(),
                content = {
                    text()
                }
            )
        }
    )
}

@Composable
fun MarkdownQuote(
    content: String,
    fontSize: TextUnit,
    containerColor: Color = MaterialTheme.colorScheme.surface
) {
    val customFont = LocalNoteFontFamily.current
    Row(horizontalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier
                .height(22.dp)
                .width(6.dp)
                .background(
                    color = if (containerColor == MaterialTheme.colorScheme.surface)
                        MaterialTheme.colorScheme.surfaceContainerLow
                    else
                        MaterialTheme.colorScheme.surfaceContainer,
                    shape = appCornerShape(CornerFraction.MEDIUM)
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = buildString(" $content"),
            fontSize = fontSize,
            fontFamily = customFont
        )
    }
}

@Composable
fun MarkdownCheck(content: @Composable () -> Unit, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Material 3's Checkbox appends `defaultMinSize(48.dp, 48.dp)` after the caller's
        // modifier, so this `.size(20.dp)` only scales the drawn box down; the node stays a 48.dp
        // touch target and keeps its own toggleable semantics for TalkBack.
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(20.dp)
        )
        content()
    }
}

@Composable
fun MissingAttachment(radius: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(radius.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = stringResource(R.string.attachment_missing),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One picture attachment, shared by the reader, the split preview, the image exporter and the new
 * segmented editor. [target] is the element target (`attachment://<id>`), not a file path; the
 * registry resolves it, and an id whose row has not loaded yet becomes the standard placeholder.
 *
 * [onDelete] is only wired up by screens that can edit the note. When it is present a long press
 * opens the two operations a rendered picture needs - save a copy to a folder the user picks, or
 * move the attachment to the recycle bin - and a tap opens the picture full-screen. Without it the
 * block stays a plain image, which is what the note cards want.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageAttachmentBlock(
    target: String,
    radius: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onImageLoaded: () -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val imageContext = LocalContext.current
    val resolvedPath = AttachmentRegistry.resolve(target)
    val scope = rememberCoroutineScope()
    var showMenu by remember(target) { mutableStateOf(false) }
    var showFullScreen by remember(target) { mutableStateOf(false) }
    var showDeleteConfirm by remember(target) { mutableStateOf(false) }

    // The name the system picker starts from. A missing path cannot be saved, so the fallback only
    // keeps the launcher's proposal valid.
    val exportName = remember(resolvedPath) {
        File(resolvedPath ?: "image.jpg").name.ifBlank { "image.jpg" }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        if (uri == null || resolvedPath == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    imageContext.contentResolver.openOutputStream(uri)?.use { output ->
                        File(resolvedPath).inputStream().use { input -> input.copyTo(output) }
                    } ?: error("No output stream for $uri")
                }.isSuccess
            }
            Toast.makeText(
                imageContext,
                if (saved) R.string.attachment_saved_to_folder else R.string.attachment_save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (resolvedPath == null) {
        MissingAttachment(
            radius = radius,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        return
    }

    // Coil decodes at the image's full resolution unless the request says otherwise, and
    // `.heightIn(max = 400.dp)` on the modifier only constrains how the bitmap is drawn, not how
    // much memory it took to produce it. A note with a few photos could allocate hundreds of
    // megabytes of bitmaps and get the process OOM-killed. Capping the decode keeps the on-screen
    // result the same at this size for a fraction of the cost.
    val imageRequest = remember(imageContext, resolvedPath) {
        ImageRequest.Builder(imageContext)
            .data(resolvedPath)
            .size(IMAGE_DECODE_MAX_WIDTH_PX, IMAGE_DECODE_MAX_HEIGHT_PX)
            .build()
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = imageRequest,
            contentDescription = stringResource(R.string.cd_note_image),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .clip(RoundedCornerShape(radius.dp))
                .then(
                    when {
                        onDelete != null -> Modifier.combinedClickable(
                            onClick = { onClick?.invoke() ?: run { showFullScreen = true } },
                            onLongClick = { showMenu = true }
                        )
                        onClick != null -> Modifier.clickable(onClick = onClick)
                        else -> Modifier
                    }
                ),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            onSuccess = { onImageLoaded() },
            onError = {
                android.util.Log.e("MarkdownText", "Failed to load image: $resolvedPath")
                onImageLoaded()
            }
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.audio_save_to_folder)) },
                onClick = {
                    showMenu = false
                    saveLauncher.launch(exportName)
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                }
            )
        }
    }

    if (showFullScreen) {
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
                    AsyncImage(
                        model = resolvedPath,
                        contentDescription = stringResource(R.string.attachment_open_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.attachment_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** One recording attachment, with the same caption fallback the note list uses. */
@Composable
fun AudioAttachmentBlock(
    target: String,
    radius: Int,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val resolvedPath = AttachmentRegistry.resolve(target)
    if (resolvedPath == null) {
        MissingAttachment(
            radius = radius,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        return
    }
    AudioPlayerCard(
        audioUri = resolvedPath,
        radius = radius,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        caption = caption,
        onRename = onRename,
        onDelete = onDelete
    )
}

/** The read-only grid used by every renderer; the editor only changes how it is reached. */
@Composable
fun TableBlock(
    rows: List<List<String>>,
    fontSize: TextUnit,
    weight: FontWeight,
    maxLines: Int,
    overflow: TextOverflow,
    modifier: Modifier = Modifier
) {
    val customFont = LocalNoteFontFamily.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                row.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) Spacer(Modifier.width(12.dp))
                    Text(
                        // Cells share the row's width evenly; the header row is the only one set
                        // in a heavier weight. Use the inline builder again so bold, italics and
                        // escapes inside a cell render exactly as they did before the extraction.
                        text = buildString(
                            cell,
                            if (rowIndex == 0) FontWeight.SemiBold else weight
                        ),
                        fontSize = fontSize,
                        fontFamily = customFont,
                        modifier = Modifier.weight(1f),
                        maxLines = maxLines,
                        overflow = overflow
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    radius: Int,
    markdown: String,
    isPreview: Boolean = false,
    isEnabled: Boolean,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier.fillMaxWidth(),
    weight: FontWeight = FontWeight.Normal,
    fontSize: TextUnit,
    spacing: Dp = 2.dp,
    onContentChange: (String) -> Unit = {},
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    useLazyColumn: Boolean = true,
    onImageLoaded: (String) -> Unit = {},
    /**
     * When set, rendered pictures and recordings offer a delete action that calls this with the
     * `attachment://` id. Screens that can edit the note pass their handler; note cards leave it
     * null so a feed of notes can never delete anything.
     */
    onAttachmentDelete: ((String) -> Unit)? = null
) {
    if (!isEnabled) {
        StaticMarkdownText(
            markdown = markdown,
            modifier = modifier,
            weight = weight,
            fontSize = fontSize,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // Parsed once per text change instead of once per recomposition. This composable is
    // instantiated for every note card in the grid, so an unremembered pipeline re-tokenised
    // the whole note (and allocated the five line processors) on every scroll frame.
    val lines = remember(markdown) { markdown.lines() }
    val content = remember(lines) {
        MarkdownBuilder(
            lines = lines,
            lineProcessors = defaultMarkdownLineProcessors()
        ).apply { parse() }.content
    }

    MarkdownContent(
        radius = radius,
        isPreview = isPreview,
        content = content,
        containerColor = containerColor,
        modifier = modifier,
        spacing = spacing,
        weight = weight,
        fontSize = fontSize,
        lines = lines,
        onContentChange = onContentChange,
        maxLines = maxLines,
        overflow = overflow,
        useLazyColumn = useLazyColumn,
        onImageLoaded = onImageLoaded,
        onAttachmentDelete = onAttachmentDelete
    )
}

@Composable
fun StaticMarkdownText(
    markdown: String,
    modifier: Modifier,
    weight: FontWeight,
    fontSize: TextUnit,
    maxLines: Int,
    overflow: TextOverflow
) {
    val customFont = LocalNoteFontFamily.current
    Text(
        text = markdown,
        fontSize = fontSize,
        fontWeight = weight,
        fontFamily = customFont,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
fun MarkdownContent(
    radius: Int,
    isPreview: Boolean,
    content: List<MarkdownElement>,
    containerColor: Color,
    modifier: Modifier,
    spacing: Dp,
    weight: FontWeight,
    fontSize: TextUnit,
    lines: List<String>,
    onContentChange: (String) -> Unit,
    maxLines: Int,
    overflow: TextOverflow,
    useLazyColumn: Boolean,
    onImageLoaded: (String) -> Unit = {},
    onAttachmentDelete: ((String) -> Unit)? = null
) {
    if (isPreview) {
        Column(modifier = modifier) {
            content.take(4).forEachIndexed { index, _ ->
                RenderMarkdownElement(
                    radius = radius,
                    index = index,
                    content = content,
                    containerColor = containerColor,
                    weight = weight,
                    fontSize = fontSize,
                    lines = lines,
                    isPreview = true,
                    onContentChange = onContentChange,
                    maxLines = maxLines,
                    overflow = overflow,
                    onImageLoaded = onImageLoaded,
                    onAttachmentDelete = onAttachmentDelete
                )
                Spacer(modifier = Modifier.height(spacing))
            }
        }
    } else {
        SelectionContainer {
            if (useLazyColumn) {
                LazyColumn(modifier = modifier) {
                    items(content.size) { index ->
                        Spacer(modifier = Modifier.height(spacing))
                        RenderMarkdownElement(
                            radius = radius,
                            content = content,
                            index = index,
                            containerColor = containerColor,
                            weight = weight,
                            fontSize = fontSize,
                            lines = lines,
                            isPreview = isPreview,
                            onContentChange = onContentChange,
                            maxLines = maxLines,
                            overflow = overflow,
                            onImageLoaded = onImageLoaded,
                            onAttachmentDelete = onAttachmentDelete
                        )
                    }
                }
            } else {
                // A plain column is measured with unbounded height, which is required when the
                // content is captured off screen (a lazily scrolling list may never do that) and
                // preferred inside list/grid items, where a nested lazy list would add a second
                // scroll container to every cell.
                Column(modifier = modifier) {
                    content.forEachIndexed { index, _ ->
                        Spacer(modifier = Modifier.height(spacing))
                        RenderMarkdownElement(
                            radius = radius,
                            content = content,
                            index = index,
                            containerColor = containerColor,
                            weight = weight,
                            fontSize = fontSize,
                            lines = lines,
                            isPreview = isPreview,
                            onContentChange = onContentChange,
                            maxLines = maxLines,
                            overflow = overflow,
                            onImageLoaded = onImageLoaded,
                            onAttachmentDelete = onAttachmentDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RenderMarkdownElement(
    radius: Int,
    content: List<MarkdownElement>,
    index: Int,
    containerColor: Color,
    weight: FontWeight,
    fontSize: TextUnit,
    lines: List<String>,
    isPreview: Boolean,
    onContentChange: (String) -> Unit,
    maxLines: Int,
    overflow: TextOverflow,
    onImageLoaded: (String) -> Unit = {},
    onAttachmentDelete: ((String) -> Unit)? = null
) {
    val element = content[index]
    val customFont = LocalNoteFontFamily.current
    Row {
        when (element) {
            is Heading -> {
                Text(
                    text = buildString(element.text, weight),
                    // Two steps, not six: the toolbar only offers one heading button (`##`), and a
                    // note that carries `#` or `###` asks for a section break rather than a smaller
                    // heading. Scaling also stops overriding the reader's own text size.
                    fontSize = (fontSize.value * if (element.level == 1 || element.level == 3) 1.35f else 1.15f).sp,
                    fontWeight = weight,
                    fontFamily = customFont,
                    modifier = Modifier.padding(vertical = 10.dp),
                    maxLines = maxLines,
                    overflow = overflow
                )
            }
            is CheckboxItem -> {
                MarkdownCheck(
                    content = {
                        Text(
                            text = buildString(element.text, weight),
                            fontSize = fontSize,
                            fontWeight = weight,
                            fontFamily = customFont,
                            maxLines = maxLines,
                            overflow = overflow
                        )
                    },
                    checked = element.checked,
                    onCheckedChange = { newChecked ->
                        val updatedLines = lines.toMutableList()
                        val prefix = if (newChecked) "[x]" else "[ ]"
                        updatedLines[element.lineNumber] = "$prefix ${element.text}"
                        val updatedMarkdown = updatedLines.joinToString("\n")
                        onContentChange(updatedMarkdown)
                    }
                )
            }
            is ListItem -> {
                Text(
                    text = buildString("鈥?${element.text}", weight),
                    fontSize = fontSize,
                    fontWeight = weight,
                    fontFamily = customFont,
                    maxLines = maxLines,
                    overflow = overflow
                )
            }
            is Quote -> {
                MarkdownQuote(
                    content = element.text,
                    fontSize = fontSize,
                    containerColor = containerColor
                )
            }
            is ImageInsertion -> {
                ImageAttachmentBlock(
                    target = element.photoUri,
                    radius = radius,
                    onImageLoaded = {
                        onImageLoaded(
                            com.ezpnix.writeon.core.attachment.AttachmentSyntax.idOf(element.photoUri)
                                ?: element.photoUri
                        )
                    },
                    onDelete = onAttachmentDelete?.let { delete ->
                        {
                            com.ezpnix.writeon.core.attachment.AttachmentSyntax
                                .idOf(element.photoUri)
                                ?.let(delete)
                        }
                    }
                )
            }
            is AudioInsertion -> {
                val caption = element.audioUri.let { target ->
                    com.ezpnix.writeon.core.attachment.AttachmentSyntax.idOf(target)
                        ?.let { AttachmentRegistry.attachment(it)?.caption }
                }
                AudioAttachmentBlock(
                    target = element.audioUri,
                    radius = radius,
                    caption = caption,
                    onDelete = onAttachmentDelete?.let { delete ->
                        {
                            com.ezpnix.writeon.core.attachment.AttachmentSyntax
                                .idOf(element.audioUri)
                                ?.let(delete)
                        }
                    }
                )
            }
            is CodeBlock -> {
                MarkdownCodeBlock(
                    color = if (isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    text = {
                        Text(
                            text = element.code,
                            fontSize = fontSize,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp),
                            maxLines = maxLines,
                            overflow = overflow
                        )
                    }
                )
            }
            is TableElement -> {
                TableBlock(
                    rows = element.rows,
                    fontSize = fontSize,
                    weight = weight,
                    maxLines = maxLines,
                    overflow = overflow
                )
            }
            is HorizontalRule -> {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            is NormalText -> {
                Text(
                    text = buildString(element.text, weight),
                    fontSize = fontSize,
                    fontFamily = customFont,
                    maxLines = maxLines,
                    overflow = overflow
                )
            }
        }
    }
}