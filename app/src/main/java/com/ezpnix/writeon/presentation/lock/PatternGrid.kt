package com.ezpnix.writeon.presentation.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R
import kotlin.math.max

private const val GRID_SIZE = 3
private const val CELL_COUNT = GRID_SIZE * GRID_SIZE
private val DOT_RADIUS = 6.dp
private val HIT_RADIUS = 24.dp
private val STROKE_WIDTH = 4.dp

/**
 * 3x3 pattern lock grid. Cells are numbered 0..8 row-major; a cell can only be used once and the
 * selected cells are reported through [onPatternChange] as they are drawn, then once more through
 * [onPatternFinished] when the finger is lifted.
 *
 * UX-03: the drawn grid is a bare `Canvas`, which a screen reader cannot operate at all. An
 * invisible 3x3 overlay of semantic buttons sits on top of it and selects the same cells on
 * activation, so TalkBack can build a pattern dot by dot; the drag gesture is untouched because
 * the overlay has no pointer input of its own, only a click semantics action. Selecting the last
 * dot again removes it, which is the only correction a screen-reader user has before submitting.
 */
@Composable
fun PatternGrid(
    pattern: List<Int>,
    onPatternChange: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    onPatternFinished: (List<Int>) -> Unit = {},
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { HIT_RADIUS.toPx() }
    val dotRadiusPx = with(density) { DOT_RADIUS.toPx() }
    val strokeWidthPx = with(density) { STROKE_WIDTH.toPx() }

    val currentOnPatternChange by rememberUpdatedState(onPatternChange)
    val currentOnPatternFinished by rememberUpdatedState(onPatternFinished)

    var pointerPosition by remember { mutableStateOf<Offset?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline
    val gridLabel = stringResource(R.string.lock_a11y_pattern_hint)
    val progressLabel = stringResource(R.string.lock_a11y_pattern_progress, pattern.size, CELL_COUNT)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = gridLabel
                stateDescription = progressLabel
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(hitRadiusPx) {
                    val geometry = PatternGeometry(size.width.toFloat(), size.height.toFloat())
                    // The spec asks for a ~24dp hit radius; widening it to half a cell keeps the
                    // neighbouring dots reachable on small screens without overlapping.
                    val cellHitRadius = max(hitRadiusPx, geometry.cell / 2f)
                    var drawing = emptyList<Int>()

                    fun cellAt(position: Offset): Int? {
                        for (index in 0 until CELL_COUNT) {
                            if ((geometry.center(index) - position).getDistance() <= cellHitRadius) return index
                        }
                        return null
                    }

                    detectDragGestures(
                        onDragStart = { start ->
                            drawing = listOfNotNull(cellAt(start))
                            pointerPosition = start
                            currentOnPatternChange(drawing)
                        },
                        onDrag = { change, _ ->
                            pointerPosition = change.position
                            val cell = cellAt(change.position)
                            if (cell != null && cell !in drawing) {
                                drawing = drawing + cell
                                currentOnPatternChange(drawing)
                            }
                        },
                        onDragEnd = {
                            pointerPosition = null
                            currentOnPatternFinished(drawing)
                        },
                        onDragCancel = {
                            pointerPosition = null
                            currentOnPatternFinished(drawing)
                        },
                    )
                }
        ) {
            val geometry = PatternGeometry(size.width, size.height)

            for (index in 0 until pattern.size - 1) {
                drawLine(
                    color = lineColor,
                    start = geometry.center(pattern[index]),
                    end = geometry.center(pattern[index + 1]),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
            pointerPosition?.let { position ->
                pattern.lastOrNull()?.let { last ->
                    drawLine(
                        color = lineColor,
                        start = geometry.center(last),
                        end = position,
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                    )
                }
            }

            for (index in 0 until CELL_COUNT) {
                val center = geometry.center(index)
                val selected = index in pattern
                if (selected) {
                    drawCircle(
                        color = lineColor,
                        radius = dotRadiusPx * 2f,
                        center = center,
                        style = Stroke(width = strokeWidthPx / 2f),
                    )
                }
                drawCircle(
                    color = if (selected) lineColor else idleColor,
                    radius = dotRadiusPx,
                    center = center,
                )
            }
        }

        PatternAccessibilityOverlay(pattern = pattern, onPatternChange = onPatternChange)
    }
}

/**
 * UX-03: one semantic button per dot, aligned with the drawn grid.
 *
 * Deliberately no `clickable`/`pointerInput`: those would steal the drag from the Canvas below.
 * A semantics `onClick` is enough for TalkBack (and switch access) to reveal and activate the
 * node, and the per-dot [stateDescription] reports whether it is already part of the sequence.
 */
@Composable
private fun PatternAccessibilityOverlay(
    pattern: List<Int>,
    onPatternChange: (List<Int>) -> Unit,
) {
    val selectedDescription = stringResource(R.string.lock_a11y_pattern_selected)
    val unselectedDescription = stringResource(R.string.lock_a11y_pattern_not_selected)
    val selectActionLabel = stringResource(R.string.lock_a11y_pattern_select_dot)

    Column(modifier = Modifier.fillMaxSize()) {
        repeat(GRID_SIZE) { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                repeat(GRID_SIZE) { column ->
                    val index = row * GRID_SIZE + column
                    val selected = index in pattern
                    val dotLabel = stringResource(R.string.lock_a11y_pattern_dot, index + 1)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                role = Role.Button
                                contentDescription = dotLabel
                                stateDescription = if (selected) selectedDescription else unselectedDescription
                                onClick(label = selectActionLabel) {
                                    val updated = when {
                                        // Tapping the last dot again walks the sequence back; there
                                        // is no drag-cancel gesture a screen reader could use.
                                        pattern.lastOrNull() == index -> pattern.dropLast(1)
                                        index in pattern -> pattern
                                        else -> pattern + index
                                    }
                                    if (updated != pattern) onPatternChange(updated)
                                    true
                                }
                            }
                    )
                }
            }
        }
    }
}

/** Maps cell indices to pixel centers inside the drawn (possibly non square) canvas. */
private class PatternGeometry(width: Float, height: Float) {
    private val side = minOf(width, height)
    val cell = side / GRID_SIZE
    private val originX = (width - side) / 2f
    private val originY = (height - side) / 2f

    fun center(index: Int): Offset = Offset(
        x = originX + (index % GRID_SIZE + 0.5f) * cell,
        y = originY + (index / GRID_SIZE + 0.5f) * cell,
    )
}
