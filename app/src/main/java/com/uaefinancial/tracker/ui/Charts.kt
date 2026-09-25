package com.uaefinancial.tracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Short money for axis labels: 1.2K, 35K, 1.4M. */
private val oneDecimal = ThreadLocal.withInitial { DecimalFormat("0.#", DecimalFormatSymbols(Locale.ENGLISH)) }
fun fmtCompact(minor: Long): String {
    val v = minor / 100.0
    val f = oneDecimal.get()!!
    return when {
        kotlin.math.abs(v) >= 1_000_000 -> f.format(v / 1_000_000) + "M"
        kotlin.math.abs(v) >= 1_000 -> f.format(v / 1_000) + "K"
        else -> v.roundToInt().toString()
    }
}

/** A round step above [max] for gridlines: 1, 2, 2.5, 5 × 10ⁿ. */
private fun niceMax(max: Long): Long {
    if (max <= 0) return 100
    val exp = 10.0.pow(kotlin.math.floor(log10(max.toDouble())))
    val f = max / exp
    val nice = when {
        f <= 1 -> 1.0
        f <= 2 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5 -> 5.0
        else -> 10.0
    }
    return ceil(nice * exp).toLong()
}

// ================================================================== donut

data class DonutSlice(val key: Long?, val value: Long, val color: Color, val label: String)

/**
 * Category arc chart: rounded segments with gaps, fixed colour per category, total in the middle.
 * Tap a segment to select it (the centre then shows that category); tap the centre to clear.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    selectedKey: Long?,
    hasSelection: Boolean,
    onSelect: (DonutSlice?) -> Unit,
    centerTitle: String,
    centerValue: String,
    centerSubtitle: String?,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slices.map { it.key to it.value }) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    Box(modifier.fillMaxWidth().aspectRatio(1.15f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .pointerInput(slices) {
                    detectTapGestures { p ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = minOf(size.width, size.height) / 2f
                        val d = hypot(p.x - c.x, p.y - c.y)
                        if (d < r * 0.62f) { onSelect(null); return@detectTapGestures }
                        if (d > r) return@detectTapGestures
                        // angle from 12 o'clock, clockwise
                        var a = (atan2(p.y - c.y, p.x - c.x) * 180 / PI).toFloat() + 90f
                        if (a < 0) a += 360f
                        var acc = 0f
                        for (s in slices) {
                            val sweep = 360f * s.value / total
                            if (a >= acc && a < acc + sweep) { onSelect(s); return@detectTapGestures }
                            acc += sweep
                        }
                    }
                },
        ) {
            val stroke = size.minDimension * 0.105f
            val inset = stroke * 0.75f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val radius = arcSize.width / 2f
            // Round caps stick out by half the stroke: leave that plus a visible gap between segments.
            val capDeg = ((stroke / 2f) / radius * 180f / PI.toFloat())
            val gapDeg = capDeg * 2f + 3.5f
            if (slices.isEmpty()) {
                drawArc(Ink.surfaceHighest, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            val p = progress.value
            for (s in slices) {
                val full = 360f * s.value / total
                val sweep = full * p
                val isSel = hasSelection && s.key == selectedKey
                val dim = hasSelection && !isSel
                val draw = if (slices.size == 1) sweep else (sweep - gapDeg).coerceAtLeast(0.01f)
                val drawStart = if (slices.size == 1) start else start + gapDeg / 2f
                drawArc(
                    color = if (dim) s.color.copy(alpha = 0.28f) else s.color,
                    startAngle = drawStart,
                    sweepAngle = draw,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(if (isSel) stroke * 1.25f else stroke, cap = if (slices.size == 1) StrokeCap.Butt else StrokeCap.Round),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow(centerTitle)
            Text(centerValue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink.text)
            centerSubtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.muted) }
        }
    }
}

// ================================================================== area / line chart

data class ChartPoint(val label: String, val tooltip: String, val value: Long)

/**
 * Line chart with a gradient fill, three gridlines with labels, and a tap/drag guide.
 * The header above the chart shows the touched point (or [idleTitle]/[idleValue] when nothing is touched).
 */
@Composable
fun AreaChart(
    points: List<ChartPoint>,
    idleTitle: String,
    idleValue: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.green,
    height: androidx.compose.ui.unit.Dp = 190.dp,
    valueFormatter: (Long) -> String = { fmtMoney(it) },
) {
    var touched by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Ink.faint, fontSize = 10.sp)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(points.map { it.value }) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }

    Column(modifier.fillMaxWidth()) {
        val t = touched?.let { points.getOrNull(it) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow(t?.tooltip ?: idleTitle)
            Text(
                t?.let { valueFormatter(it.value) } ?: idleValue,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (points.size < 2) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text("Not enough data for this period", color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        val maxV = niceMax(points.maxOf { it.value }.coerceAtLeast(0))
        val minV = points.minOf { it.value }.coerceAtMost(0)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    detectTapGestures(
                        onPress = { p ->
                            val left = 40.dp.toPx()
                            val w = size.width - left
                            if (w <= 0f) return@detectTapGestures
                            touched = (((p.x - left) / w) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                            tryAwaitRelease()
                        },
                        onTap = { },
                        onDoubleTap = { touched = null },
                    )
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(onDragEnd = { }) { change, _ ->
                        val left = 40.dp.toPx()
                        val w = size.width - left
                        if (w <= 0f) return@detectHorizontalDragGestures
                        touched = (((change.position.x - left) / w) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                    }
                },
        ) {
            val left = 40.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val chartW = size.width - left
            val chartH = size.height - bottomPad
            val range = (maxV - minV).coerceAtLeast(1).toFloat()
            fun x(i: Int) = left + chartW * i / (points.size - 1)
            fun y(v: Long) = chartH - chartH * (v - minV) / range

            // gridlines + labels
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (g in 0..3) {
                val v = minV + (maxV - minV) * g / 3
                val yy = y(v)
                drawLine(Ink.border, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = dash)
                val lbl = measurer.measure(fmtCompact(v), labelStyle)
                drawText(lbl, topLeft = Offset(0f, (yy - lbl.size.height / 2f).coerceIn(0f, (chartH - lbl.size.height).coerceAtLeast(0f))))
            }
            // x labels: first, middle, last
            listOf(0, (points.size - 1) / 2, points.size - 1).distinct().forEach { i ->
                val lbl = measurer.measure(points[i].label, labelStyle)
                val xx = (x(i) - lbl.size.width / 2f).coerceIn(left, (size.width - lbl.size.width).coerceAtLeast(left))
                drawText(lbl, topLeft = Offset(xx, chartH + 4.dp.toPx()))
            }

            // smooth path (horizontal-tangent cubic segments never overshoot between points)
            val p = progress.value
            val line = Path()
            points.forEachIndexed { i, pt ->
                val yy = chartH - (chartH - y(pt.value)) * p
                if (i == 0) line.moveTo(x(i), yy) else {
                    val px = x(i - 1)
                    val py = chartH - (chartH - y(points[i - 1].value)) * p
                    val mx = (px + x(i)) / 2f
                    line.cubicTo(mx, py, mx, yy, x(i), yy)
                }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(x(points.size - 1), chartH)
                lineTo(x(0), chartH)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.0f)), startY = 0f, endY = chartH))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

            touched?.let { i ->
                val xx = x(i)
                val yy = y(points[i].value)
                drawLine(Ink.muted.copy(alpha = 0.6f), Offset(xx, 0f), Offset(xx, chartH), strokeWidth = 1.dp.toPx())
                drawCircle(Ink.bg, 7.dp.toPx(), Offset(xx, yy))
                drawCircle(color, 5.dp.toPx(), Offset(xx, yy))
            }
        }
    }
}

// ================================================================== bars

/**
 * Vertical bars (one hue: magnitude only). The selected bar is bright, others dimmed; tap one to select it.
 * The selected value is printed above the chart.
 */
@Composable
fun BarChart(
    data: List<Pair<String, Long>>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ink.violet,
    headline: String? = null,
) {
    val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Column(modifier.fillMaxWidth()) {
        headline?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            data.forEachIndexed { i, (label, v) ->
                val sel = i == selectedIndex
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val frac = (v.toFloat() / max).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (v > 0) (0.03f + 0.8f * frac) else 0.015f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (sel) Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)))
                                else Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.15f))),
                            ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = if (sel) Ink.text else Ink.faint,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Horizontal share bar with 4dp rounded ends (by-card, top merchants, utilisation). */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.surfaceHighest),
    ) {
        if (f > 0f) {
            Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
        }
    }
}
