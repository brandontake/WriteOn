package com.ezpnix.writeon.presentation.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.unit.dp
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

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
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
