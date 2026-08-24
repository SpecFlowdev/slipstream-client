package dev.specflow.slipstream.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.specflow.slipstream.net.TunnelService

/**
 * Download and upload over the last minute.
 *
 * Both series share one vertical scale so their heights are comparable — a
 * chart where each line is normalised to itself looks informative and says
 * nothing. The scale is the largest sample on screen, so an idle tunnel does
 * not amplify its own noise into mountains.
 */
@Composable
fun AreaChart(
    samples: List<TunnelService.Sample>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val down = scheme.primary
    val up = scheme.secondary
    val grid = scheme.outline.copy(alpha = 0.35f)

    Box(modifier, contentAlignment = Alignment.Center) {
        if (samples.isEmpty()) {
            Text(
                "Nothing has moved yet",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            return@Box
        }

        val peak = samples.maxOf { maxOf(it.down, it.up) }.coerceAtLeast(1L)

        Canvas(Modifier.fillMaxSize()) {
            for (line in 1..3) {
                val y = size.height * line / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            series(samples.map { it.down }, peak, down, filled = true)
            series(samples.map { it.up }, peak, up, filled = false)
        }

        Box(Modifier.fillMaxSize()) {
            Text(
                formatRate(peak),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * One series, smoothed.
 *
 * The curve is a Catmull-Rom spline expressed as cubic Béziers, which passes
 * through every sample rather than near it — a chart that rounds off the peak
 * it is meant to be reporting is worse than a jagged one.
 */
private fun DrawScope.series(
    values: List<Long>,
    peak: Long,
    color: Color,
    filled: Boolean,
) {
    if (values.size < 2) return
    val step = size.width / (values.size - 1)
    val points = values.mapIndexed { index, value ->
        Offset(index * step, size.height - (value.toFloat() / peak) * size.height * 0.92f)
    }

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 0 until points.size - 1) {
            val p0 = points[maxOf(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[minOf(i + 2, points.size - 1)]
            cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y,
            )
        }
    }

    if (filled) {
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.34f), Color.Transparent)),
        )
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
}
