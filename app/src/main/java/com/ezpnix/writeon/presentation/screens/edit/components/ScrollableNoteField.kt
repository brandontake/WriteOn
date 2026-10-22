@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ezpnix.writeon.presentation.screens.edit.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The note body, as a text field that is as tall as its content inside an outer vertical scroll.
 *
 * Material's [BasicTextField] normally scrolls internally and only re-coerces its scroll offset
 * when the cursor rectangle changes. When the keyboard opens the window shrinks, the text field is
 * remeasured shorter, but the cursor rectangle stays at the same coordinates, so the caret can end
 * up below the visible area and the field never scrolls it back (this was the "keyboard covers the
 * cursor" bug). Owning the scroll here fixes that: the field gets unbounded height, the scroll
 * container is the only scroller, and a zero-size anchor at the caret is revealed whenever the
 * caret, the text layout or the viewport changes. The scroll happens only when the anchor is not
 * already visible, so a caret in the upper half keeps the current scroll position.
 *
 * The field is deliberately built from [BasicTextField] instead of Material3's TextField: the
 * latter hides its internal scroller and does not expose `onTextLayout`, which is what we need to
 * know where the caret is.
 */
@Composable
fun ScrollableNoteField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var focused by remember { mutableStateOf(false) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val density = LocalDensity.current
    val horizontalPadding = 16.dp
    val topPadding = 16.dp
    val bottomPadding = 28.dp
    val caretMargin = 8.dp
    val topPaddingPx = with(density) { topPadding.toPx() }
    val caretMarginPx = with(density) { caretMargin.toPx() }

    val caretAnchor = remember(layoutResult, value.selection, topPaddingPx, caretMarginPx) {
        val layout = layoutResult
        if (layout == null) {
            0
        } else {
            val offset = value.selection.max.coerceIn(0, layout.layoutInput.text.length)
            (topPaddingPx + layout.getCursorRect(offset).bottom + caretMarginPx).roundToInt()
        }
    }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // Re-run when the caret moves, when the text is relaid out and when the available height
    // changes (the keyboard opening/closing, or the formatting toolbar appearing). `withFrameNanos`
    // lets the new constraints be measured before asking the scroll container to reveal the caret.
    LaunchedEffect(focused, caretAnchor, viewportSize, scrollState.maxValue) {
        if (!focused) return@LaunchedEffect
        withFrameNanos { }
        bringIntoViewRequester.bringIntoView()
    }

    BoxWithConstraints(modifier = modifier) {
        // A short note still fills the card, so tapping anywhere in it focuses the field. A long
        // note is allowed to grow past this, which is what turns off the internal scroller.
        val minHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .verticalScroll(scrollState)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChanged(it.isFocused)
                    },
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layoutResult = it },
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHeight)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = horizontalPadding,
                                    top = topPadding,
                                    end = horizontalPadding,
                                    bottom = bottomPadding
                                )
                        ) {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                }
            )
            // The anchor must be a sibling of the text field, not part of its decoration box.
            // Otherwise the field's own internal scroller is the nearest BringIntoViewResponder;
            // that scroller has no range in an unbounded field, so it can swallow the request and
            // the outer vertical scroll never moves. Here the outer scroll is the nearest
            // responder, and the offset is measured from the same top as the text layout.
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, caretAnchor) }
                    .size(1.dp)
                    .bringIntoViewRequester(bringIntoViewRequester)
            )
        }
    }
}
