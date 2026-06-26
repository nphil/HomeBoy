package com.homeboy.app.ui.statistics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.homeboy.app.ui.formatMoney
import com.homeboy.app.ui.formatMoneyCompact
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Distinct, theme-agnostic but pleasant palette via golden-angle hue rotation. */
fun chartPalette(n: Int): List<Color> =
    List(n.coerceAtLeast(1)) { i -> Color.hsl((i * 137.508f) % 360f, 0.55f, 0.58f) }

data class ChartPoint(val label: String, val value: Float)
data class BarDatum(val label: String, val value: Float, val color: Color)
data class DonutDatum(val label: String, val value: Float, val color: Color)

// ---------------------------------------------------------------------------
// Value-over-time: animated area + line with a draggable scrubber
// ---------------------------------------------------------------------------

@Composable
fun ValueAreaChart(
    points: List<ChartPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Not enough data yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val progress = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { progress.animateTo(1f, tween(900)) }
    var selected by remember(points) { mutableIntStateOf(-1) }

    val minV = points.minOf { it.value }
    val maxV = points.maxOf { it.value }
    val range = (maxV - minV).takeIf { it > 0f } ?: 1f

    BoxWithConstraints(modifier) {
        val maxWidthDp = maxWidth
        fun idxFor(x: Float, w: Float): Int =
            ((x / w) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { o -> selected = idxFor(o.x, size.width.toFloat()) }
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> selected = idxFor(o.x, size.width.toFloat()) },
                        onDragEnd = { },
                        onHorizontalDrag = { change, _ -> selected = idxFor(change.position.x, size.width.toFloat()) }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val padTop = 14.dp.toPx()
            val padBottom = 10.dp.toPx()
            val chartH = h - padTop - padBottom
            fun xAt(i: Int) = w * i / (points.size - 1)
            fun yAt(v: Float) = padTop + chartH * (1f - (v - minV) / range)

            val line = Path()
            val area = Path()
            points.forEachIndexed { i, p ->
                val x = xAt(i); val y = yAt(p.value)
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, h - padBottom); area.lineTo(x, y)
                } else {
                    line.lineTo(x, y); area.lineTo(x, y)
                }
            }
            area.lineTo(xAt(points.lastIndex), h - padBottom)
            area.close()

            clipRect(right = w * progress.value) {
                drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0f))))
                drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            if (selected in points.indices) {
                val x = xAt(selected); val y = yAt(points[selected].value)
                drawLine(lineColor.copy(alpha = 0.4f), Offset(x, padTop), Offset(x, h - padBottom), 1.2.dp.toPx())
                drawCircle(lineColor, 5.dp.toPx(), Offset(x, y))
                drawCircle(Color.White, 2.dp.toPx(), Offset(x, y))
            }
        }

        if (selected in points.indices) {
            val p = points[selected]
            val xFrac = selected.toFloat() / (points.size - 1)
            val tooltipW = 96.dp
            val xDp = (maxWidthDp * xFrac - tooltipW / 2)
                .coerceIn(0.dp, (maxWidthDp - tooltipW).coerceAtLeast(0.dp))
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.width(tooltipW).offset(x = xDp, y = 0.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(formatMoney(p.value.toDouble()),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.inverseOnSurface)
                    Text(p.label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Horizontal bars (value by location) — animated grow, value labels
// ---------------------------------------------------------------------------

@Composable
fun HorizontalBars(data: List<BarDatum>, modifier: Modifier = Modifier) {
    val maxV = data.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f
    val progress by animateFloatAsState(
        targetValue = if (data.isEmpty()) 0f else 1f,
        animationSpec = tween(900), label = "bars"
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        data.forEach { d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.label, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, modifier = Modifier.weight(1f))
                    Text(formatMoney(d.value.toDouble()), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction = ((d.value / maxV) * progress).coerceIn(0f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(d.color)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Donut (value by tag) — animated sweep, tap a slice to inspect
// ---------------------------------------------------------------------------

@Composable
fun DonutChart(
    data: List<DonutDatum>,
    modifier: Modifier = Modifier
) {
    val total = data.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    val sweep = remember(data) { Animatable(0f) }
    LaunchedEffect(data) { sweep.animateTo(1f, tween(1000)) }
    var selected by remember(data) { mutableIntStateOf(-1) }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(data) {
                detectTapGestures { o ->
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val dist = hypot(o.x - cx, o.y - cy)
                    val diameter = minOf(size.width, size.height).toFloat() - 28.dp.toPx()
                    val radius = diameter / 2f
                    val band = 28.dp.toPx()
                    if (dist < radius - band || dist > radius + band) { selected = -1; return@detectTapGestures }
                    val raw = Math.toDegrees(atan2((o.y - cy).toDouble(), (o.x - cx).toDouble())).toFloat()
                    val ang = (raw + 90f + 360f) % 360f
                    var acc = 0f
                    var hit = -1
                    for (i in data.indices) {
                        val frac = data[i].value / total * 360f
                        if (ang >= acc && ang < acc + frac) { hit = i; break }
                        acc += frac
                    }
                    selected = if (hit == selected) -1 else hit
                }
            }
        ) {
            val stroke = 28.dp.toPx()
            val diameter = minOf(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f
            data.forEachIndexed { i, d ->
                val full = d.value / total * 360f
                val drawn = full * sweep.value
                val isSel = i == selected
                drawArc(
                    color = if (selected == -1 || isSel) d.color else d.color.copy(alpha = 0.35f),
                    startAngle = startAngle,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = if (isSel) stroke * 1.18f else stroke, cap = StrokeCap.Butt)
                )
                startAngle += drawn
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (selected in data.indices) {
                val d = data[selected]
                Text(formatMoneyCompact(d.value.toDouble()), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(d.label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("${(d.value / total * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(formatMoneyCompact(total.toDouble()), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Total", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
