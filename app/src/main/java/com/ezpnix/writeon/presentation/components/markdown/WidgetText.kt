package com.ezpnix.writeon.presentation.components.markdown

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ezpnix.writeon.R
import java.io.File

@Composable
fun WidgetText(
    modifier: GlanceModifier,
    markdown: String,
    weight: FontWeight = FontWeight.Normal,
    fontSize: TextUnit = 16.sp,
    color: ColorProvider,
    onContentChange: (String) -> Unit = {}
) {
    val lines = markdown.lines()
    // Same pipeline as MarkdownText. This used to be a copy of the list, which meant every new
    // element had to be added twice and the widget silently fell behind the note list.
    val markdownBuilder = MarkdownBuilder(lines, defaultMarkdownLineProcessors())
    markdownBuilder.parse()
    MarkdownWidgetContent(
        modifier = modifier,
        content = markdownBuilder.content,
        weight = weight,
        lines = lines,
        fontSize = fontSize,
        color = color,
        onContentChange = onContentChange
    )
}

@Composable
fun MarkdownWidgetContent(
    modifier: GlanceModifier,
    content: List<MarkdownElement>,
    weight: FontWeight,
    lines: List<String>,
    color: ColorProvider,
    fontSize: TextUnit,
    onContentChange: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier
    ) {
        items(content.size) { index ->
            WidgetMarkdownElement(
                modifier = modifier,
                index = index,
                color = color,
                content = content,
                weight = weight,
                fontSize = fontSize,
                lines = lines,
                onContentChange = onContentChange
            )
        }
    }
}


@Composable
fun WidgetMarkdownElement(
    modifier: GlanceModifier,
    lines: List<String>,
    content: List<MarkdownElement>,
    index: Int,
    color: ColorProvider,
    weight: FontWeight,
    fontSize: TextUnit,
    onContentChange: (String) -> Unit
) {
    val element = content[index]
    val context = LocalContext.current

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)
    ) {
        when (element) {
            is Heading -> {
                Text(
                    text = element.text,
                    style = TextStyle(
                        fontSize = when (element.level) {
                            in 1..6 -> (28 - (2 * element.level)).sp
                            else -> fontSize
                        },
                        fontWeight = weight,
                        color = color
                    )
                )
            }
            is ImageInsertion -> {
                // The body carries an id; the registry knows the file on this device. A Glance
                // widget cannot show a "tap to reload" placeholder, so an unresolved or unreadable
                // image simply renders nothing rather than an error box the user cannot act on.
                val path = AttachmentRegistry.resolve(element.photoUri)
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(path)
                        val maxFileSize = 15552000
                        if (bitmap != null && bitmap.byteCount < maxFileSize) {
                            Image(
                                provider = ImageProvider(bitmap),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = GlanceModifier
                                    .padding(6.dp)
                                    .wrapContentHeight()
                            )
                        } else {
                            Text(
                                text = context.getString(R.string.unsupported_image_size),
                                style = TextStyle(
                                    fontSize = fontSize,
                                    fontWeight = weight,
                                    color = GlanceTheme.colors.error
                                )
                            )
                        }
                    }
                }
            }
            is AudioInsertion -> {
                val caption = element.audioUri
                    .let { com.ezpnix.writeon.core.attachment.AttachmentSyntax.idOf(it) }
                    ?.let { AttachmentRegistry.attachment(it)?.caption }
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.audio_recording_label)
                Text(
                    text = "[$caption]",
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color
                    )
                )
            }

            is HorizontalRule -> {
                Text(
                    text = "──────────",
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color
                    )
                )
            }
            is NormalText -> {
                Text(
                    text = element.text,
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color
                    )
                )
            }
            is CheckboxItem -> {
                MarkdownWidgetCheck(
                    content = {
                        Text(
                            text = element.text,
                            style = TextStyle(
                                fontSize = fontSize,
                                fontWeight = weight,
                                color = color
                            )
                        )
                    },
                    checked = element.checked,
                    onCheckedChange = { newChecked ->
                        val newMarkdown = lines.toMutableList().apply {
                            this[element.lineNumber] = if (newChecked) {
                                "[x] ${element.text}"
                            } else {
                                "[ ] ${element.text}"
                            }
                        }
                        onContentChange(newMarkdown.joinToString("\n"))
                    }
                )
            }
            is ListItem -> {
                Text(
                    text = "• ${element.text}",
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = color
                    )
                )
            }
            is Quote -> {
                Row(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .height(22.dp)
                            .width(6.dp)
                            .background(GlanceTheme.colors.tertiary)
                            .cornerRadius(12.dp)
                    ) {}
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = element.text,
                        style = TextStyle(
                            fontSize = fontSize,
                            fontWeight = weight,
                            color = color
                        )
                    )
                }
            }
            is CodeBlock -> {
                if (element.isEnded) {
                    MarkdownWidgetCodeBlock(color = GlanceTheme.colors.widgetBackground) {
                        Text(
                            text = element.code.dropLast(1),
                            style = TextStyle(
                                fontSize = fontSize,
                                fontWeight = weight,
                                fontFamily = FontFamily.Monospace,
                                color = color
                            )
                        )
                    }
                } else {
                    Text(
                        text = element.firstLine,
                        style = TextStyle(
                            fontSize = fontSize,
                            fontWeight = weight,
                            color = color
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownWidgetCheck(
    content: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CheckBox(
            checked = checked,
            onCheckedChange = { onCheckedChange(!checked) })

        content()
    }
}

@Composable
fun MarkdownWidgetCodeBlock(
    color: ColorProvider,
    text: @Composable () -> Unit
) {
    Box(
        modifier = GlanceModifier.padding(top = 6.dp),
        content = {
            Box(
                modifier = GlanceModifier
                    .padding(6.dp)
                    .cornerRadius(6.dp)
                    .background(color)
                    .fillMaxWidth(),
            ) {
                text()
            }
        }
    )
}
