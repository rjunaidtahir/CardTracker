package com.junaid.cardtracker.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.toArgb
import com.junaid.cardtracker.core.BudgetStatus
import com.junaid.cardtracker.core.Csv
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.parser.Money
import com.junaid.cardtracker.parser.TxnType
import com.junaid.cardtracker.ui.CategoryStyle
import com.junaid.cardtracker.ui.OverviewState
import com.junaid.cardtracker.ui.fmtMoney
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReportLine(val label: String, val amountMinor: Long, val color: Int = 0, val share: Int = 0)
data class ReportTxn(val date: String, val merchant: String, val category: String, val card: String, val type: String, val amount: String, val aedMinor: Long?)

data class ReportData(
    val title: String,
    val periodLabel: String,
    val generated: String,
    val spentMinor: Long,
    val previousLabel: String?,
    val previousMinor: Long,
    val moneyInMinor: Long,
    val txnCount: Int,
    val avgPerDayMinor: Long,
    val categories: List<ReportLine>,
    val cards: List<ReportLine>,
    val merchants: List<ReportLine>,
    val budgets: List<Pair<String, BudgetStatus>>,
    val txns: List<ReportTxn>,
)

/** Monthly (or any period) report: a printable PDF and a CSV that opens in Excel. */
object ReportBuilder {
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val shortDate = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH)

    fun build(
        overview: OverviewState,
        txns: List<TransactionEntity>,
        categories: Map<Long, String>,
        cardNames: Map<String, String>,
        budgets: List<BudgetStatus>,
        zone: ZoneId,
    ): ReportData {
        val spent = overview.spentMinor
        return ReportData(
            title = "RJ's Financials report",
            periodLabel = overview.period.label(),
            generated = LocalDateTime.now(zone).format(dateTime),
            spentMinor = spent,
            previousLabel = overview.previous?.label(),
            previousMinor = overview.previousMinor,
            moneyInMinor = overview.moneyInMinor,
            txnCount = overview.txnCount,
            avgPerDayMinor = overview.avgPerDayMinor,
            categories = overview.byCategory.map { (id, v) ->
                ReportLine(id?.let { categories[it] } ?: "Uncategorised", v, CategoryStyle.color(id).toArgb(), if (spent > 0) (v * 100 / spent).toInt() else 0)
            },
            cards = overview.byCard.map { ReportLine(cardNames[it.key] ?: it.key, it.amountMinor) },
            merchants = overview.topMerchants.map { ReportLine(it.first, it.second) },
            budgets = budgets.map { (categories[it.categoryId] ?: "Category") to it },
            txns = txns.sortedBy { it.timestamp }.map { t ->
                val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
                ReportTxn(
                    date = Instant.ofEpochMilli(t.timestamp).atZone(zone).format(shortDate),
                    merchant = t.merchant,
                    category = if (type == TxnType.PURCHASE || type == TxnType.REFUND) (t.categoryId?.let { categories[it] } ?: "") else "",
                    card = t.cardKey?.let { cardNames[it] ?: it } ?: "Typed",
                    type = when (type) {
                        TxnType.PURCHASE -> "Spend"
                        TxnType.REFUND -> "Refund"
                        TxnType.PAYMENT -> "Card payment"
                        TxnType.TRANSFER_IN -> "Money in"
                        TxnType.TRANSFER_OUT -> "Money out"
                    },
                    amount = (if (type == TxnType.PURCHASE || type == TxnType.TRANSFER_OUT) "-" else "+") +
                        fmtMoney(t.amountMinor, t.currency),
                    aedMinor = t.amountAedMinor,
                )
            },
        )
    }

    // ------------------------------------------------------------------ CSV

    fun csv(d: ReportData): String {
        val sb = StringBuilder()
        fun row(vararg v: String?) { sb.append(v.joinToString(",") { Csv.escape(it) }).append("\r\n") }
        fun aed(m: Long) = Money.fromMinor(m).toPlainString()
        row(d.title); row("Period", d.periodLabel); row("Generated", d.generated)
        row("Total spent (AED)", aed(d.spentMinor))
        d.previousLabel?.let { row("Previous period", it, aed(d.previousMinor)) }
        row("Money in (AED)", aed(d.moneyInMinor)); row("Spends", d.txnCount.toString()); row("Average per day (AED)", aed(d.avgPerDayMinor))
        row()
        row("Category", "Amount (AED)", "Share %")
        d.categories.forEach { row(it.label, aed(it.amountMinor), it.share.toString()) }
        row()
        if (d.budgets.isNotEmpty()) {
            row("Budget (this month)", "Spent (AED)", "Limit (AED)", "Used %")
            d.budgets.forEach { (n, b) -> row(n, aed(b.spentMinor), aed(b.limitMinor), b.percent.toString()) }
            row()
        }
        row("Card", "Spent (AED)")
        d.cards.forEach { row(it.label, aed(it.amountMinor)) }
        row()
        row("Date", "Merchant", "Category", "Card", "Type", "Amount", "Amount (AED)")
        d.txns.forEach { row(it.date, it.merchant, it.category, it.card, it.type, it.amount, it.aedMinor?.let { a -> aed(a) }) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ PDF

    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 40f

    fun writePdf(d: ReportData, out: OutputStream) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var c: Canvas
        var y = 0f

        val ink = 0xFF0F172A.toInt()
        val muted = 0xFF475569.toInt()
        val line = 0xFFE2E8F0.toInt()
        val accent = 0xFF0E8A4A.toInt()
        fun paint(size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val body = paint(10f)
        val bodyMuted = paint(9f, muted)
        val bold = paint(10f, bold = true)
        val h2 = paint(13f, bold = true)

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page!!.canvas
            y = M
            if (pageNo > 1) {
                c.drawText("${d.title} · ${d.periodLabel}", M, y, bodyMuted)
                c.drawText("Page $pageNo", W - M - bodyMuted.measureText("Page $pageNo"), y, bodyMuted)
                y += 18f
            }
        }
        fun ensure(space: Float) { if (y + space > H - M) newPage() }
        fun right(text: String, x: Float, yy: Float, p: Paint) = c.drawText(text, x - p.measureText(text), yy, p)
        fun clip(text: String, width: Float, p: Paint): String {
            if (p.measureText(text) <= width) return text
            var t = text
            while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
            return "$t…"
        }
        fun section(title: String) { ensure(40f); y += 14f; c.drawText(title, M, y, h2); y += 10f }

        newPage()
        // ---- header band
        val band = Paint().apply { color = accent }
        c.drawRoundRect(RectF(M, y, W - M, y + 92f), 14f, 14f, band)
        c.drawText(d.title, M + 18f, y + 26f, paint(12f, 0xFFD7FFE6.toInt(), true))
        c.drawText(d.periodLabel, M + 18f, y + 44f, paint(11f, 0xFFFFFFFF.toInt()))
        c.drawText(fmtMoney(d.spentMinor), M + 18f, y + 76f, paint(24f, 0xFFFFFFFF.toInt(), true))
        right("Generated ${d.generated}", W - M - 18f, y + 26f, paint(9f, 0xFFD7FFE6.toInt()))
        d.previousLabel?.takeIf { d.previousMinor > 0 }?.let {
            val diff = d.spentMinor - d.previousMinor
            val pct = kotlin.math.abs(diff) * 100 / d.previousMinor
            right("$pct% ${if (diff > 0) "more" else "less"} than $it", W - M - 18f, y + 76f, paint(9f, 0xFFFFFFFF.toInt()))
        }
        y += 110f
        // ---- quick stats
        val stats = listOf("Spends" to d.txnCount.toString(), "Per day" to fmtMoney(d.avgPerDayMinor), "Money in" to fmtMoney(d.moneyInMinor))
        val sw = (W - 2 * M - 20f) / 3
        stats.forEachIndexed { i, (k, v) ->
            val x = M + i * (sw + 10f)
            c.drawRoundRect(RectF(x, y, x + sw, y + 44f), 10f, 10f, Paint().apply { color = 0xFFF1F5F9.toInt() })
            c.drawText(k.uppercase(), x + 10f, y + 16f, paint(8f, muted, true))
            c.drawText(v, x + 10f, y + 34f, paint(12f, ink, true))
        }
        y += 60f

        // ---- categories: donut + table
        if (d.categories.isNotEmpty()) {
            section("Spending by category")
            val top = y
            val size = 150f
            val rect = RectF(M + 6f, top + 6f, M + 6f + size, top + 6f + size)
            val total = d.categories.sumOf { it.amountMinor }.coerceAtLeast(1)
            var start = -90f
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 22f; strokeCap = Paint.Cap.BUTT }
            d.categories.forEach { l ->
                val sweep = 360f * l.amountMinor / total
                arc.color = l.color
                c.drawArc(rect, start + 1f, (sweep - 2f).coerceAtLeast(0.5f), false, arc)
                start += sweep
            }
            val cx = rect.centerX()
            val t1 = "TOTAL"; val t2 = fmtMoney(d.spentMinor).substringBeforeLast('.')
            c.drawText(t1, cx - paint(8f, muted, true).measureText(t1) / 2, rect.centerY() - 4f, paint(8f, muted, true))
            val p2 = paint(11f, ink, true)
            c.drawText(t2, cx - p2.measureText(t2) / 2, rect.centerY() + 12f, p2)

            var ty = top + 12f
            val tx = M + size + 30f
            val tableRight = W - M
            d.categories.take(14).forEach { l ->
                c.drawCircle(tx + 4f, ty - 3f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.color })
                c.drawText(clip(l.label, 150f, body), tx + 14f, ty, body)
                right("${l.share}%", tableRight - 90f, ty, bodyMuted)
                right(fmtMoney(l.amountMinor), tableRight, ty, bold)
                ty += 15f
            }
            y = maxOf(top + size + 16f, ty + 4f)
        }

        // ---- budgets
        if (d.budgets.isNotEmpty()) {
            section("Budgets (this month)")
            d.budgets.forEach { (name, b) ->
                ensure(26f)
                c.drawText(name, M, y + 10f, body)
                right("${fmtMoney(b.spentMinor)} of ${fmtMoney(b.limitMinor)} · ${b.percent}%", W - M, y + 10f, bodyMuted)
                val barY = y + 15f
                c.drawRoundRect(RectF(M, barY, W - M, barY + 6f), 3f, 3f, Paint().apply { color = line })
                val frac = (b.percent / 100f).coerceIn(0f, 1f)
                val col = when { b.percent >= 100 -> 0xFFC62828.toInt(); b.percent >= 80 -> 0xFFA35F00.toInt(); else -> accent }
                c.drawRoundRect(RectF(M, barY, M + (W - 2 * M) * frac, barY + 6f), 3f, 3f, Paint().apply { color = col })
                y += 28f
            }
        }

        // ---- cards and merchants side by side
        if (d.cards.isNotEmpty() || d.merchants.isNotEmpty()) {
            section("By card and top merchants")
            val colW = (W - 2 * M - 20f) / 2
            var ly = y + 10f
            var ry = y + 10f
            d.cards.take(10).forEach { l ->
                c.drawText(clip(l.label, colW - 90f, body), M, ly, body); right(fmtMoney(l.amountMinor), M + colW, ly, bold); ly += 15f
            }
            val rx = M + colW + 20f
            d.merchants.forEachIndexed { i, l ->
                c.drawText(clip("${i + 1}. ${l.label}", colW - 90f, body), rx, ry, body); right(fmtMoney(l.amountMinor), W - M, ry, bold); ry += 15f
            }
            y = maxOf(ly, ry)
        }

        // ---- transactions
        section("Transactions (${d.txns.size})")
        val cols = floatArrayOf(M, M + 58f, M + 250f, M + 350f, W - M)
        fun header() {
            ensure(20f)
            y += 8f
            c.drawText("DATE", cols[0], y, paint(8f, muted, true))
            c.drawText("MERCHANT", cols[1], y, paint(8f, muted, true))
            c.drawText("CATEGORY", cols[2], y, paint(8f, muted, true))
            c.drawText("CARD", cols[3], y, paint(8f, muted, true))
            right("AMOUNT", cols[4], y, paint(8f, muted, true))
            y += 4f
            c.drawLine(M, y, W - M, y, Paint().apply { color = line })
            y += 12f
        }
        header()
        d.txns.forEach { t ->
            if (y + 14f > H - M) { newPage(); header() }
            c.drawText(t.date, cols[0], y, bodyMuted)
            c.drawText(clip(t.merchant, cols[2] - cols[1] - 8f, body), cols[1], y, body)
            c.drawText(clip(t.category.ifEmpty { t.type }, cols[3] - cols[2] - 8f, bodyMuted), cols[2], y, bodyMuted)
            c.drawText(clip(t.card, 110f, bodyMuted), cols[3], y, bodyMuted)
            right(t.amount, cols[4], y, if (t.amount.startsWith("+")) paint(10f, accent, true) else bold)
            y += 14f
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }
}
