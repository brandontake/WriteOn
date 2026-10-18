package com.ezpnix.writeon.presentation.screens.edit.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.components.markdown.MarkdownText
import com.ezpnix.writeon.presentation.theme.LocalNoteFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logical width of the exported picture; the physical size follows the device density. */
private val EXPORT_WIDTH = 360.dp
private val EXPORT_PADDING = 20.dp

/** Frames to let pass so the captured layer is guaranteed to have recorded its content. */
private const val CAPTURE_FRAMES = 3

/** Far enough off screen that the capture is never visible, while still being drawn. */
private const val OFF_SCREEN_X = -200_000

/**
 * Renders a note exactly the way the preview shows it and records it into an [ImageBitmap].
 *
 * The content is laid out at its natural height (the caller positions it off screen), so the
 * recorded layer holds the *whole* note instead of the visible 128.dp preview viewport.
 * [onCaptured] is invoked once, after [CAPTURE_FRAMES] frames have been drawn.
 */
@Composable
fun NoteImageCapture(
    noteTitle: String,
    noteBody: String,
    createdAt: Long,
    isMarkdownEnabled: Boolean,
    fontSize: Float,
    containerColor: Color,
    cornerRadius: Dp,
    onCaptured: (ImageBitmap) -> Unit,
    onFailed: (Throwable) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val graphicsLayer = rememberGraphicsLayer()
    val customFont = LocalNoteFontFamily.current
    val timestamp = remember(createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
    }
    val signatureDate = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    Box(
        modifier = modifier
            .width(EXPORT_WIDTH)
            .wrapContentHeight(unbounded = true)
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .drawWithContent {
                // Capture the content into a layer whose pixels live in regular memory. The default
                // strategy renders through a RenderNode, and reading a RenderNode back gives a
                // hardware bitmap, which the software canvas used for JPEG encoding cannot draw
                // (IllegalArgumentException: Software rendering doesn't support hardware bitmaps).
                // CompositingStrategy.Offscreen is the documented way to record into a bitmap backed
                // layer instead. NoteImageSaver still converts defensively, because whether a given
                // layer comes back as software memory also depends on the graphics layer version.
                graphicsLayer.compositingStrategy = CompositingStrategy.Offscreen
                graphicsLayer.record { this@drawWithContent.drawContent() }
                drawLayer(graphicsLayer)
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(EXPORT_PADDING)) {
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
                fontSize = (fontSize - 3).sp,
                fontFamily = customFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            MarkdownText(
                radius = 0,
                markdown = noteBody,
                isEnabled = isMarkdownEnabled,
                containerColor = containerColor,
                modifier = Modifier.fillMaxWidth(),
                fontSize = fontSize.sp,
                useLazyColumn = false
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
                    fontSize = (fontSize - 3).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = customFont,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "  ·  $signatureDate",
                    fontSize = (fontSize - 3).sp,
                    fontFamily = customFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    LaunchedEffect(noteTitle, noteBody) {
        repeat(CAPTURE_FRAMES) { withFrameNanos { } }
        // Previously the failure was swallowed here: getOrNull() returned null, onCaptured never
        // ran, and the save host sat in its "saving" state forever with no message. Report it so the
        // host can tell the user what actually went wrong.
        runCatching { graphicsLayer.toImageBitmap() }
            .onSuccess { onCaptured(it) }
            .onFailure { onFailed(it) }
    }
}

/**
 * Hosts an off screen [NoteImageCapture] for one shot and writes the result into the picture
 * library. Compose measures the capture with unbounded constraints but reports a zero sized node,
 * so showing it never moves the real UI.
 */
@Composable
fun NoteImageSaveHost(
    noteTitle: String,
    noteBody: String,
    createdAt: Long,
    isMarkdownEnabled: Boolean,
    fontSize: Float,
    containerColor: Color,
    cornerRadius: Dp,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alreadySaved = remember { mutableStateOf(false) }

    // Report the outcome exactly once and always leave the saving state, whether the capture
    // worked, the capture threw, or the write failed.
    fun finish(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onFinished()
    }

    Box(
        modifier = Modifier.layout { measurable, constraints ->
            // `Constraints()` resolves to minWidth = maxWidth = minHeight = maxHeight = 0, which
            // measured the capture into a 0 x 0 node: the bitmap was empty and every later step
            // failed. The capture is laid out at its own natural height, so it is measured with
            // unbounded height and the width this host is given, then reported as a zero sized node
            // placed off screen so the real UI never moves.
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity
                )
            )
            layout(0, 0) { placeable.place(IntOffset(OFF_SCREEN_X, 0)) }
        }
    ) {
        NoteImageCapture(
            noteTitle = noteTitle,
            noteBody = noteBody,
            createdAt = createdAt,
            isMarkdownEnabled = isMarkdownEnabled,
            fontSize = fontSize,
            containerColor = containerColor,
            cornerRadius = cornerRadius,
            onCaptured = { bitmap ->
                if (!alreadySaved.value) {
                    alreadySaved.value = true
                    scope.launch {
                        val location = NoteImageSaver.save(
                            context = context,
                            bitmap = bitmap,
                            baseName = NoteImageSaver.fileBaseName(noteTitle),
                            backgroundColor = containerColor.toArgb()
                        )
                        if (location != null) {
                            finish(context.getString(R.string.msg_image_saved, location))
                        } else {
                            // Surface why: the old code reported a bare "could not save" that gave the
                            // user nothing to act on, and hid the reason the write had failed.
                            val reason = NoteImageSaver.lastFailure
                            finish(
                                if (reason != null) {
                                    context.getString(R.string.msg_image_save_failed_reason, reason)
                                } else {
                                    context.getString(R.string.msg_image_save_failed)
                                }
                            )
                        }
                    }
                }
            },
            onFailed = { error ->
                if (!alreadySaved.value) {
                    alreadySaved.value = true
                    finish(
                        context.getString(R.string.msg_image_save_failed_reason, error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        )
    }
}

/**
 * Stores a captured note as a JPEG in the user's picture library.
 *
 * Android Q and later go through MediaStore, older releases into the app's own external pictures
 * folder (no storage permission is requested either way). Both return a human readable location.
 */
object NoteImageSaver {

    private const val RELATIVE_DIR = "Pictures/WriteOn"
    private const val SUB_DIR = "WriteOn"
    private const val MIME_TYPE = "image/jpeg"
    private const val JPEG_QUALITY = 90

    /** A file name stem derived from the note title, safe for the file system. */
    fun fileBaseName(title: String): String {
        val sanitized = title.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return sanitized.ifEmpty { "note" }
    }

    /**
     * Writes [bitmap] as a JPEG; returns the location it was written to, or null when it failed.
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
        backgroundColor: Int
    ): String? =
        withContext(Dispatchers.IO) {
            val fileName = "$baseName-${System.currentTimeMillis()}.jpg"
            lastFailure = null
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(context, bitmap, fileName, backgroundColor)
                } else {
                    saveToAppPictures(context, bitmap, fileName, backgroundColor)
                }
            }.fold(
                onSuccess = { it },
                onFailure = {
                    lastFailure = it.message ?: it.javaClass.simpleName
                    null
                }
            )
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(
        context: Context,
        bitmap: ImageBitmap,
        fileName: String,
        backgroundColor: Int
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
            stream.use { writeJpeg(bitmap, backgroundColor, it) }
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return "$RELATIVE_DIR/$fileName"
    }

    private fun saveToAppPictures(
        context: Context,
        bitmap: ImageBitmap,
        fileName: String,
        backgroundColor: Int
    ): String {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), SUB_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create ${directory.absolutePath}")
        }

        val file = File(directory, fileName)
        FileOutputStream(file).use { writeJpeg(bitmap, backgroundColor, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(MIME_TYPE), null)

        return file.absolutePath
    }

    /**
     * JPEG has no alpha channel, so the capture is flattened onto [backgroundColor] first.
     *
     * A captured layer can come back as a hardware bitmap. A hardware bitmap can only be drawn on
     * a hardware accelerated canvas, so feeding one to the software canvas used for encoding raises
     * `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`.
     *
     * The conversion therefore has to happen through `Bitmap.copy`, which is the only public path
     * that reads a hardware bitmap on the CPU. The previous version tried to do it with a software
     * `Canvas` instead — which is precisely the thing that cannot read a hardware bitmap — and
     * swallowed the resulting exception, so the hardware bitmap was passed straight back to the
     * caller and the very same error was thrown again one line later.
     */
    private fun writeJpeg(bitmap: ImageBitmap, backgroundColor: Int, output: OutputStream) {
        val raw = bitmap.asAndroidBitmap()
        val source = if (raw.config == Bitmap.Config.HARDWARE) raw.toArgb8888() else raw
        val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        flattened.eraseColor(backgroundColor)
        Canvas(flattened).drawBitmap(source, 0f, 0f, null)
        flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        output.flush()
    }

    /**
     * Copies a hardware bitmap into a CPU readable ARGB_8888 bitmap.
     *
     * `Bitmap.copy` is the only public API that pulls a hardware bitmap back into software memory;
     * anything routed through `Canvas` hits the same "Software rendering doesn't support hardware
     * bitmaps" rejection. When even that copy fails the bitmap cannot be encoded at all, so it is
     * reported instead of silently falling back to something the encoder would reject anyway.
     */
    private fun Bitmap.toArgb8888(): Bitmap =
        copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException(
            "Could not convert a ${width}x$height hardware bitmap to software memory"
        )
}
