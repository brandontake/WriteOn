package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.ContentValues
import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import coil.request.ImageRequest
import com.ezpnix.writeon.R
import com.ezpnix.writeon.core.attachment.AttachmentSyntax
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.components.markdown.IMAGE_DECODE_MAX_HEIGHT_PX
import com.ezpnix.writeon.presentation.components.markdown.IMAGE_DECODE_MAX_WIDTH_PX
import com.ezpnix.writeon.presentation.components.markdown.MarkdownText
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Logical width of the exported picture; the physical size follows the device density. */
private val EXPORT_WIDTH = 360.dp
private val EXPORT_PADDING = 20.dp
private val EXPORT_MARGIN = 12.dp

/**
 * Absolute ceiling on the area of the exported picture, in pixels.
 *
 * The note is exported as one picture, so the natural height of a long note is unbounded. 32 MP is
 * the hard cap (about 128 MB as an ARGB_8888 bitmap); the budget actually used is lower on devices
 * with a small heap, see [exportPixelBudgetForHeap]. Anything above the per-device budget is
 * rendered at a lower density so the whole note still fits.
 */
internal const val MAX_EXPORT_PIXELS = 32_000_000L

/**
 * Per-device pixel budget: one third of the app's heap, expressed as 4-byte pixels.
 *
 * The capture holds one large bitmap and the WebP encoder needs working memory on top of it. A third
 * of the heap leaves room for the rest of the app (and for the defensive software copy of a hardware
 * bitmap) while allowing a 512 MB-heap device the full 32 MP. `memoryClass` is the limit the platform
 * actually enforces for this process; `largeMemoryClass` is only available with `largeHeap`, which
 * this app does not request.
 */
internal fun exportPixelBudgetForHeap(heapMb: Int): Long {
    val megabytes = heapMb.coerceAtLeast(1).toLong()
    val pixels = (megabytes * 1024L * 1024L) / 12L
    return pixels.coerceIn(1L, MAX_EXPORT_PIXELS)
}

private fun contextExportPixelBudget(context: Context): Long {
    val manager = context.getSystemService(ActivityManager::class.java)
    return exportPixelBudgetForHeap(manager?.memoryClass ?: 256)
}

/**
 * Hard ceiling on either side of the bitmap. Very tall bitmaps are rejected by parts of the
 * graphics stack long before they run out of memory, and the density scale below makes this a
 * backstop rather than the usual limit.
 */
internal const val MAX_EXPORT_DIMENSION_PX = 36_000

/**
 * Smallest readable picture width. Below this the text would be too small to read, so an
 * impossibly long note is reported instead of silently writing a sliver.
 */
internal const val MIN_EXPORT_WIDTH_PX = 480

/** Frames to let pass so the captured layer is guaranteed to have recorded its content. */
private const val CAPTURE_FRAMES = 3

/**
 * How many times an empty capture is retried before the export gives up. The card always carries
 * the title, so a fully transparent recording is a timing failure, not an empty note.
 */
private const val CAPTURE_ATTEMPTS = 3

/** Grid used to spot a blank capture without copying the whole page. */
private const val BLANK_SAMPLE_COLUMNS = 32
private const val BLANK_SAMPLE_ROWS = 64

/** Far enough off screen that the capture is never visible, while still being drawn. */
private const val OFF_SCREEN_X = -200_000

/**
 * How long the export waits for the note's images to report loaded before it starts capturing.
 * Waiting keeps the card from being recorded while a photo is still decoding; the timeout keeps a
 * broken attachment from stalling the export forever.
 */
private const val IMAGE_LOAD_TIMEOUT_MS = 5_000L

/** Quality used for the lossy WebP encode (the format stores text well at this level). */
private const val WEBP_QUALITY = 90

/**
 * Density multiplier that keeps a capture inside the pixel and dimension budgets.
 *
 * The note is laid out in dp, so rendering it under a lower [Density] scales the whole card - text,
 * padding and attachments - uniformly. Returns null when even [minWidthPx] would exceed
 * the budget, which the caller reports instead of writing an unreadable picture. Invalid or tiny
 * measurements return 1 so the caller keeps the normal density.
 */
internal fun exportCaptureScale(
    widthPx: Int,
    heightPx: Int,
    maxPixels: Long = MAX_EXPORT_PIXELS,
    maxDimensionPx: Int = MAX_EXPORT_DIMENSION_PX,
    minWidthPx: Int = MIN_EXPORT_WIDTH_PX,
): Float? {
    if (widthPx <= 0 || heightPx <= 0) return 1f
    val pixels = widthPx.toLong() * heightPx.toLong()
    var scale = 1f
    if (maxPixels > 0 && pixels > maxPixels) {
        scale = sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat()
    }
    if (maxDimensionPx > 0 && heightPx * scale > maxDimensionPx) {
        scale = maxDimensionPx.toFloat() / heightPx
    }
    if (scale >= 1f) return 1f
    if (widthPx * scale < minWidthPx) return null
    return scale.coerceAtLeast(0.01f)
}

/**
 * Renders a note as one share card and records the whole thing into an [ImageBitmap].
 *
 * The layout follows the app's Material 3 surfaces: a surfaceContainerLow backdrop, a surface card
 * with the user's corner radius, the note title and date at the top, the rendered note in the middle
 * and the WriteOn signature at the bottom. The note keeps the font size from Settings. The content
 * is laid out at its natural height, so a long note produces one tall picture; the host lowers the
 * density when that picture would exceed the memory budget. [onCaptured] is invoked once, after
 * [CAPTURE_FRAMES] frames have been drawn.
 */
@Composable
fun NoteImageCapture(
    noteTitle: String,
    noteBody: String,
    createdAt: Long,
    isMarkdownEnabled: Boolean,
    fontSize: Float,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    /** False while the host is still measuring or waiting for images; the capture is skipped. */
    captureEnabled: Boolean = true,
    /** Natural height of the card, reported once measured. */
    onMeasuredHeight: (Int) -> Unit = {},
    /** Called instead of [onCaptured] when the card came out empty after every retry. */
    onBlank: () -> Unit = {},
    /** Receives the captured bitmap. The picture is always a single card, never a page. */
    onCaptured: (ImageBitmap) -> Unit,
    onImageLoaded: (String) -> Unit = {},
    onFailed: (Throwable) -> Unit = {},
) {
    val graphicsLayer = rememberGraphicsLayer()
    val customFont = LocalNoteFontFamily.current
    val timestamp = remember(createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
    }
    val signatureDate = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    var contentHeightPx by remember { mutableIntStateOf(0) }
    var captureDone by remember(noteBody, cornerRadius) { mutableStateOf(false) }
    LaunchedEffect(contentHeightPx) {
        if (contentHeightPx > 0) onMeasuredHeight(contentHeightPx)
    }

    val cardColor = MaterialTheme.colorScheme.surface
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val footerSize = (fontSize - 3).coerceAtLeast(10f)

    Box(
        modifier = modifier
            .width(EXPORT_WIDTH)
            .wrapContentHeight(unbounded = true)
            .onSizeChanged { contentHeightPx = it.height }
            .drawWithContent {
                // Capture the content into a layer whose pixels live in regular memory. The default
                // strategy renders through a RenderNode, and reading a RenderNode back gives a
                // hardware bitmap, which the software canvas used for WebP encoding cannot draw.
                // CompositingStrategy.Offscreen is the documented way to record into a bitmap backed
                // layer instead. NoteImageSaver still converts defensively.
                graphicsLayer.compositingStrategy = CompositingStrategy.Offscreen
                graphicsLayer.record { this@drawWithContent.drawContent() }
                drawLayer(graphicsLayer)
            }
    ) {
        // The MD3 surface is a child rather than a `background` modifier on the box above: the
        // recorded layer only contains drawContent(), so a background applied outside it would be
        // transparent in the bitmap.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(containerColor)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(EXPORT_MARGIN)
                .clip(RoundedCornerShape(cornerRadius))
                .background(cardColor)
                .padding(EXPORT_PADDING)
        ) {
            Text(
                text = noteTitle,
                fontSize = (fontSize + 6).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = customFont,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = timestamp,
                fontSize = footerSize.sp,
                fontFamily = customFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            MarkdownText(
                radius = 0,
                markdown = noteBody,
                isEnabled = isMarkdownEnabled,
                containerColor = cardColor,
                modifier = Modifier.fillMaxWidth(),
                fontSize = fontSize.sp,
                useLazyColumn = false,
                onImageLoaded = onImageLoaded
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WriteOn",
                    fontSize = footerSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = customFont,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "  ·  $signatureDate",
                    fontSize = footerSize.sp,
                    fontFamily = customFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    LaunchedEffect(
        noteTitle,
        noteBody,
        isMarkdownEnabled,
        fontSize,
        cornerRadius,
        contentHeightPx,
        captureEnabled
    ) {
        // Nothing to record until the card has a height. A density change re-runs layout, and the
        // host keeps captureEnabled false until the new height has settled.
        if (!captureEnabled || captureDone || contentHeightPx <= 0) return@LaunchedEffect

        var attempt = 0
        while (true) {
            repeat(CAPTURE_FRAMES) { withFrameNanos { } }
            // Previously the failure was swallowed here: getOrNull() returned null, onCaptured never
            // ran, and the save host sat in its "saving" state forever with no message. Report it so
            // the host can tell the user what actually went wrong.
            val captured = runCatching { graphicsLayer.toImageBitmap() }.getOrElse { error ->
                captureDone = true
                onFailed(error)
                return@LaunchedEffect
            }
            attempt++
            if (!captured.looksEmpty()) {
                captureDone = true
                onCaptured(captured)
                return@LaunchedEffect
            }
            if (attempt >= CAPTURE_ATTEMPTS) {
                captureDone = true
                onBlank()
                return@LaunchedEffect
            }
        }
    }
}

/**
 * Whether the recorded layer is completely transparent.
 *
 * The surface background is part of the recorded children, so every drawn pixel is opaque and a
 * capture that never recorded anything is the only way to get an all-transparent bitmap. The
 * original exporter compared pixels against the container colour, which cannot work with a
 * gradient. A hardware backed bitmap refuses pixel access; that is treated as "not empty" so an
 * unreadable layer is still saved rather than dropped.
 */
private fun ImageBitmap.looksEmpty(): Boolean {
    if (width <= 0 || height <= 0) return true
    return try {
        val source = asAndroidBitmap()
        val scaled = Bitmap.createScaledBitmap(
            source,
            BLANK_SAMPLE_COLUMNS,
            BLANK_SAMPLE_ROWS,
            true
        )
        var visible = false
        loop@ for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                if (((scaled.getPixel(x, y) ushr 24) and 0xFF) > 2) {
                    visible = true
                    break@loop
                }
            }
        }
        if (scaled !== source) scaled.recycle()
        !visible
    } catch (_: Throwable) {
        false
    }
}

/**
 * Decodes every image the note refers to before the capture starts.
 *
 * `AsyncImage` loads asynchronously, so a capture taken a few frames after composition recorded the
 * nothing-yet state, which is how photos went missing from exported pictures. The requests use the
 * same size the renderer asks for, so by the time `MarkdownText` draws them they are a memory cache
 * hit.
 */
private suspend fun preloadNoteImages(context: Context, body: String) {
    val loader = context.imageLoader
    AttachmentSyntax.idsIn(body).forEach { id ->
        // SEC-4: preloading the stored path of an encrypted image would feed Coil `WMV1`
        // ciphertext. `playablePath` schedules the vault materialisation when needed, so the
        // decrypted copy is warm by the time the renderer asks for it; null means "not ready
        // yet", which is the same placeholder the renderer shows.
        val path = AttachmentRegistry.playablePath(id) ?: return@forEach
        runCatching {
            loader.execute(
                ImageRequest.Builder(context)
                    .data(path)
                    .size(IMAGE_DECODE_MAX_WIDTH_PX, IMAGE_DECODE_MAX_HEIGHT_PX)
                    .build()
            )
        }
    }
}

/**
 * Hosts an off screen [NoteImageCapture] for one shot and writes the result into the picture
 * library. Compose measures the capture with unbounded constraints but reports a zero sized node,
 * so showing it never moves the real UI.
 *
 * The capture used to be cut into fixed-height pages. Long notes are now one picture: the first
 * measurement decides how much the whole card has to shrink to stay inside the device's pixel budget, and
 * the capture is re-laid out under that density before anything is recorded.
 */
@Composable
fun NoteImageSaveHost(
    noteTitle: String,
    noteBody: String,
    createdAt: Long,
    isMarkdownEnabled: Boolean,
    fontSize: Float,
    cornerRadius: Dp,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The bitmap budget follows the device's heap instead of a single global number: a 512 MB-heap
    // device gets the full 32 MP ceiling, while a smaller device keeps enough headroom to avoid an
    // OOM during the capture/encode.
    val pixelBudget = remember(context) { contextExportPixelBudget(context) }

    // 1 means "render at the device's density". A long note lowers this before capture so the
    // bitmap stays within budget; every dp in the card scales with it.
    var densityScale by remember(noteBody, cornerRadius) { mutableFloatStateOf(1f) }
    val captureDensity = remember(density, densityScale) {
        Density(density.density * densityScale, density.fontScale)
    }
    var measuredHeight by remember(noteBody, cornerRadius, densityScale) { mutableIntStateOf(0) }
    var scaleSettled by remember(noteBody, cornerRadius) { mutableStateOf(false) }

    // Images are decoded before the first capture. The capture only waits a few frames, and an
    // AsyncImage that is still decoding draws nothing, which is how a note whose card holds a photo
    // used to export with an empty frame where the photo should be.
    var preloaded by remember(noteBody) { mutableStateOf(false) }
    LaunchedEffect(noteBody) {
        preloadNoteImages(context, noteBody)
        preloaded = true
    }
    // Decoding into Coil's cache is not the same as the note's AsyncImage having drawn: the capture
    // used to be recorded while a photo was still one frame away. Wait until every image in this
    // note reports loaded (or the timeout expires) before the capture is allowed to start.
    val imageIds = remember(noteBody, isMarkdownEnabled) {
        if (!isMarkdownEnabled) {
            emptySet()
        } else {
            AttachmentSyntax.idsIn(noteBody).filterTo(mutableSetOf()) { id ->
                val attachment = AttachmentRegistry.attachment(id)
                attachment?.type == Attachment.TYPE_IMAGE && attachment.path.isNotBlank()
            }
        }
    }
    var pendingImageIds by remember(imageIds) { mutableStateOf(imageIds) }
    var imagesReady by remember(imageIds) { mutableStateOf(imageIds.isEmpty()) }
    var imagesTimedOut by remember(imageIds) { mutableStateOf(false) }
    LaunchedEffect(imageIds) {
        if (!imagesReady) {
            delay(IMAGE_LOAD_TIMEOUT_MS)
            imagesTimedOut = true
        }
    }

    var finished by remember { mutableStateOf(false) }
    // A recomposition can restart the capture effect (a late image changes the measured height,
    // for example). Only the first callback may be saved.
    var lastCaptured by remember(noteBody, cornerRadius) { mutableStateOf(false) }

    // Report the outcome exactly once and always leave the saving state, whether the capture
    // worked, the capture threw, or the write failed.
    fun finish(message: String) {
        if (finished) return
        finished = true
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onFinished()
    }

    // Decide how much the card has to shrink. The first measurement happens at the normal density;
    // after densityScale changes, the composable is laid out again and this effect runs once more
    // with the new height, which then settles (the scaled dimensions already fit the budget). It
    // deliberately keeps re-checking until the capture starts: a late image can still increase the
    // measured height after the first scale was accepted.
    LaunchedEffect(
        noteBody,
        cornerRadius,
        densityScale,
        measuredHeight,
        preloaded,
        imagesReady,
        imagesTimedOut,
        finished,
        lastCaptured
    ) {
        if (finished || lastCaptured || measuredHeight <= 0) return@LaunchedEffect
        if (!preloaded || !(imagesReady || imagesTimedOut)) return@LaunchedEffect
        val widthPx = with(captureDensity) { EXPORT_WIDTH.roundToPx() }.coerceAtLeast(1)
        val desired = exportCaptureScale(widthPx, measuredHeight, maxPixels = pixelBudget)
        when {
            desired == null -> {
                scaleSettled = true
                finish(context.getString(R.string.image_export_too_long))
            }

            desired >= 0.999f -> scaleSettled = true

            else -> {
                scaleSettled = false
                val next = densityScale * desired
                if (next < 0.05f) {
                    scaleSettled = true
                    finish(context.getString(R.string.image_export_too_long))
                } else {
                    densityScale = next
                }
            }
        }
    }

    Box(
        modifier = Modifier.layout { measurable, constraints ->
            // The card has to be measured at its full natural height; the node itself reports 0 x 0
            // and is placed off screen, so the real UI never moves.
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = Constraints.Infinity,
                    maxHeight = Constraints.Infinity
                )
            )
            layout(0, 0) { placeable.place(IntOffset(OFF_SCREEN_X, 0)) }
        }
    ) {
        CompositionLocalProvider(LocalDensity provides captureDensity) {
            NoteImageCapture(
                noteTitle = noteTitle,
                noteBody = noteBody,
                createdAt = createdAt,
                isMarkdownEnabled = isMarkdownEnabled,
                fontSize = fontSize,
                cornerRadius = cornerRadius,
                captureEnabled = scaleSettled && preloaded && (imagesReady || imagesTimedOut) &&
                    !finished && !lastCaptured,
                onMeasuredHeight = { measuredHeight = it },
                onCaptured = { bitmap ->
                    if (!finished && !lastCaptured) {
                        lastCaptured = true
                        val base = NoteImageSaver.fileBaseName(noteTitle)
                        scope.launch {
                            val location = NoteImageSaver.save(
                                context = context,
                                bitmap = bitmap,
                                baseName = base
                            )
                            if (location == null) {
                                // Surface why: the old code reported a bare "could not save" that gave
                                // the user nothing to act on, and hid the reason the write had failed.
                                val reason = NoteImageSaver.lastFailure
                                finish(
                                    if (reason != null) {
                                        context.getString(R.string.msg_image_save_failed_reason, reason)
                                    } else {
                                        context.getString(R.string.msg_image_save_failed)
                                    }
                                )
                            } else {
                                finish(context.getString(R.string.msg_image_saved, location))
                            }
                        }
                    }
                },
                onImageLoaded = { id ->
                    if (!imagesReady) {
                        pendingImageIds = pendingImageIds - id
                        if (pendingImageIds.isEmpty()) imagesReady = true
                    }
                },
                onBlank = {
                    finish(context.getString(R.string.msg_image_export_blank))
                },
                onFailed = { error ->
                    finish(
                        context.getString(
                            R.string.msg_image_save_failed_reason,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            )
        }
    }
}

/**
 * Stores a captured note as a WebP picture in the user's picture library through MediaStore.
 * No storage permission is requested.
 */
object NoteImageSaver {

    private const val RELATIVE_DIR = "Pictures/WriteOn"
    private const val MIME_TYPE = "image/webp"

    /** A file name stem derived from the note title, safe for the file system. */
    fun fileBaseName(title: String): String {
        val sanitized = title.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return sanitized.ifEmpty { "note" }
    }

    /**
     * Writes [bitmap] as a WebP; returns the location it was written to, or null when it failed.
     *
     * The failure reason is kept in [lastFailure] so the caller can surface it: swallowing it here
     * is what made "save as image" look like it simply never happened.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    suspend fun save(
        context: Context,
        bitmap: ImageBitmap,
        baseName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val fileName = "$baseName-${System.currentTimeMillis()}.webp"
            lastFailure = null
            runCatching {
                saveWithMediaStore(context, bitmap, fileName)
            }.fold(
                onSuccess = { it },
                onFailure = {
                    lastFailure = it.message ?: it.javaClass.simpleName
                    null
                }
            )
        }

    private fun saveWithMediaStore(
        context: Context,
        bitmap: ImageBitmap,
        fileName: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore rejected the image")

        // A failure past this point used to leave a zero byte entry pending forever, which the media
        // scanner then hides, so a retry piled up invisible rows. Delete the partial entry instead.
        try {
            val stream = resolver.openOutputStream(uri)
                ?: error("Could not open $uri for writing")
            stream.use { writeWebp(bitmap, it) }
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return "$RELATIVE_DIR/$fileName"
    }
}

/**
 * Encodes the captured card as lossy WebP.
 *
 * The card is opaque (the MD3 surface is part of the recorded content), so no flattening pass is
 * needed; WebP keeps an alpha channel if one ever appears. A hardware bitmap cannot be compressed,
 * so it is copied into regular memory first.
 */
private fun writeWebp(bitmap: ImageBitmap, output: OutputStream) {
    val source = bitmap.asAndroidBitmap()
    val software = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not read the captured bitmap")
    } else {
        source
    }
    try {
        check(software.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, output)) {
            "WebP encoder rejected the bitmap"
        }
    } finally {
        if (software !== source) software.recycle()
    }
}
