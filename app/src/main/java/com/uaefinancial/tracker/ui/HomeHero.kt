package com.uaefinancial.tracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The top of Home: one glowing card with the total, change versus the last period, a smooth spending curve,
 * a one-line split by category and the three key figures. Everything is drawn from numbers the screen already has.
 */
@Composable
fun HomeHero(
    o: OverviewState,
    categoryNames: Map<Long, String>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val a = Ink.green
    val b = Ink.violet
    val glow = if (Ink.isLight) 0.16f else 0.22f
    Column(
        modifier
            .clip(shape)
            .background(Ink.surface)
            .drawBehind {
                drawRect(Brush.linearGradient(listOf(a.copy(alpha = glow), b.copy(alpha = glow * 0.55f), Color.Transparent)))
                drawCircle(
                    Brush.radialGradient(listOf(a.copy(alpha = glow * 1.6f), Color.Transparent), center = Offset(size.width * 0.9f, 0f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f, center = Offset(size.width * 0.9f, 0f),
                )
            }
            .border(1.dp, Ink.border, shape)
            .padding(top = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Eyebrow("Total spent")
            Text(fmtMoney(o.spentMinor), style = heroNumberStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val prev = o.previous
            if (prev != null && o.previousMinor > 0) {
                val diff = o.spentMinor - o.previousMinor
                val pct = kotlin.math.abs(diff) * 100 / o.previousMinor
                val up = diff > 0
                val c = if (up) Ink.red else Ink.green
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null, Modifier.size(14.dp), tint = c)
                        Spacer(Modifier.width(4.dp))
                        Text("$pct%", style = MaterialTheme.typography.labelLarge, color = c, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (up) "more" else "less"} than ${prev.label()}",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Sparkline(o.timeline.map { it.amountMinor }, a, Modifier.fillMaxWidth().height(86.dp))

        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
            val cats = o.byCategory.filter { it.second > 0 }
            if (cats.isNotEmpty()) {
                SplitBar(cats.map { it.second to CategoryStyle.color(it.first) })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    cats.take(3).forEach { (id, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(CategoryStyle.color(id)))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                (id?.let { categoryNames[it] } ?: "Other") + " " + (if (o.spentMinor > 0) "${v * 100 / o.spentMinor}%" else ""),
                                style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("Per day", fmtCompactMoney(o.avgPerDayMinor), Modifier.weight(1f))
                HeroStat("Spends", o.txnCount.toString(), Modifier.weight(1f))
                HeroStat("Money in", fmtCompactMoney(o.moneyInMinor), Modifier.weight(1f), Ink.green)
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier, color: Color = Ink.text) {
    val s = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(s).background(if (Ink.isLight) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.22f)).border(1.dp, Ink.border, s)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Eyebrow(label)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Smooth filled curve edge to edge, drawn in on first show, with a dot on the latest value. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        if (values.size < 2) {
            drawLine(color.copy(alpha = 0.35f), Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.75f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            return@Canvas
        }
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val top = size.height * 0.14f
        val h = size.height - top - 6.dp.toPx()
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, top + h * (1f - v / max)) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]; val p1 = pts[i]
                val mx = (p0.x + p1.x) / 2f
                cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        val reveal = progress.value
        androidx.compose.ui.graphics.drawscope.clipRect(right = size.width * reveal) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (reveal >= 0.99f) {
            val last = pts.last()
            drawCircle(color.copy(alpha = 0.25f), 9.dp.toPx(), Offset(last.x - 6.dp.toPx(), last.y))
            drawCircle(color, 4.5.dp.toPx(), Offset(last.x - 6.dp.toPx(), last.y))
        }
    }
}

/** One rounded bar split into category-coloured segments. */
@Composable
fun SplitBar(parts: List<Pair<Long, Color>>, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(parts) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val total = parts.sumOf { it.first }.coerceAtLeast(1L).toFloat()
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (parts.size - 1).coerceAtLeast(0)
        var x = 0f
        parts.forEachIndexed { i, (v, c) ->
            val w = (usable * v / total * progress.value).coerceAtLeast(0f)
            if (w > 0f) drawRoundRect(c, Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2f))
            x += w + gap * progress.value
        }
    }
}
