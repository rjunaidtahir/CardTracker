package com.uaefinancial.tracker.core

import kotlin.math.abs

/** One printed character with its position on the page (points, origin top-left), as read from the PDF. */
data class Glyph(val page: Int, val x: Float, val y: Float, val width: Float, val size: Float, val text: String)

/** A printed line: its text with the x position of every character, so columns can be recognised. */
class PrintedLine(val page: Int, val y: Float, val text: String, val xs: FloatArray) {
    fun xAt(i: Int): Float = xs[i.coerceIn(0, xs.size - 1)]
    fun xEnd(endExclusive: Int): Float = if (xs.isEmpty()) 0f else xs[(endExclusive - 1).coerceIn(0, xs.size - 1)] + 4f
    override fun toString() = text
}

/** Everything read from a statement. */
data class StatementAnalysis(
    val summary: StatementSummary,
    val lines: List<StatementLine>,
    /** Sum of spends minus credits read from the transaction list, and whether it agrees with the statement totals. */
    val totalsCheck: String?,
    val totalsAgree: Boolean?,
)

/**
 * Reads bank statements without knowing the bank's layout in advance:
 *  - key figures are found by what they're called (many wordings) and read either to the right of the label
 *    or in the rows below it, in the same column; missing ones are inferred from the others
 *    (credit limit = available + balance, due date follows the statement date, ...);
 *  - the transaction table's columns (debit, credit, balance, amount) are found from its header words, so each
 *    amount is read from the right column; "CR", a trailing minus, or a rising balance mark money in;
 *  - descriptions wrapped onto the line above or below, multi-line transactions (fees on extra lines) and
 *    supplementary-card sections are handled;
 *  - the result is checked against the statement's own totals.
 */
object StatementReader {

    // ------------------------------------------------------------ building lines from glyphs

    private fun isNoise(c: Char): Boolean {
        val code = c.code
        return (code in 0x0590..0x08FF) || (code in 0xFB1D..0xFEFF) || c == '‏' || c == '‎' || c == ' '
    }

    /** Groups glyphs into printed lines (same page, same baseline) and words, dropping Arabic / unreadable glyphs. */
    fun linesFromGlyphs(glyphs: List<Glyph>): List<PrintedLine> {
        val out = mutableListOf<PrintedLine>()
        for ((page, pg) in glyphs.filter { it.text.isNotBlank() }.groupBy { it.page }.entries.sortedBy { it.key }) {
            val sorted = pg.sortedWith(compareBy({ it.y }, { it.x }))
            val rows = mutableListOf<MutableList<Glyph>>()
            for (g in sorted) {
                val row = rows.lastOrNull()
                val tol = maxOf(1.5f, g.size * 0.35f)
                if (row != null && abs(row.first().y - g.y) <= tol) row += g else rows += mutableListOf(g)
            }
            for (row in rows) {
                // Fake-bold PDFs print each character twice at almost the same spot — keep one copy.
                val chars = ArrayList<Glyph>()
                for (g in row.sortedBy { it.x }) {
                    val dup = chars.asReversed().take(3).any { it.text == g.text && abs(it.x - g.x) < 0.5f && abs(it.y - g.y) < 0.5f }
                    if (!dup) chars += g
                }
                val sb = StringBuilder()
                val xs = ArrayList<Float>()
                var prevEnd = Float.NaN
                for (g in chars) {
                    val t = cidText(g.text).filterNot(::isNoise)
                    if (t.isEmpty()) { prevEnd = g.x + g.width; continue }
                    if (!prevEnd.isNaN()) {
                        val gap = g.x - prevEnd
                        if (gap > maxOf(0.6f, g.size * 0.11f) && sb.isNotEmpty() && sb.last() != ' ') { sb.append(' '); xs += prevEnd }
                        if (gap > g.size * 2.5f && sb.isNotEmpty()) { sb.append(' '); xs += prevEnd } // column gap: two spaces
                    }
                    for (ch in t) { sb.append(ch); xs += g.x }
                    prevEnd = g.x + g.width
                }
                val (text, xsArr) = dropJunkWords(sb.toString(), xs.toFloatArray())
                if (text.isNotBlank()) out += PrintedLine(page, row.first().y, text, xsArr)
            }
        }
        return out
    }

    /** Fonts without a character map give raw codes ("(cid:80)"); for many PDFs the code is the Unicode value. */
    fun cidText(t: String): String {
        if (!t.startsWith("(cid:")) return t
        val n = t.removePrefix("(cid:").removeSuffix(")").toIntOrNull() ?: return ""
        return if (n in 32..0xFFFF) n.toChar().toString() else ""
    }

    private val readable = Regex("""[A-Za-z0-9 .,:;/()\-*&'%+@#_=|?!"\[\]]""")

    /** Removes words made mostly of unreadable glyphs (fonts without proper character codes). */
    private fun dropJunkWords(text: String, xs: FloatArray): Pair<String, FloatArray> {
        val sb = StringBuilder(); val out = ArrayList<Float>()
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != ' ') j++
            val w = text.substring(i, j)
            val ok = w.isEmpty() || w.count { readable.matches(it.toString()) } * 10 >= w.length * 8
            if (ok) { for (k in i until j) { sb.append(text[k]); out += xs[k] } }
            // keep the spaces that follow
            while (j < text.length && text[j] == ' ') { if (sb.isNotEmpty() && sb.last() != ' ' || (sb.length >= 1 && text.getOrNull(j - 1) == ' ' && sb.last() == ' ' && ok)) { sb.append(' '); out += xs[j] }; j++ }
            i = j
        }
        return sb.toString().trim() to out.toFloatArray().let { arr -> val lead = sb.length - sb.trimStart().length; arr.copyOfRange(lead, lead + sb.toString().trim().length) }
    }

    /** For plain text (tests, older callers): each character counts as one 5-point column. */
    fun linesFromText(text: String): List<PrintedLine> =
        text.lines().mapIndexedNotNull { i, raw ->
            val t = raw.replace(' ', ' ').trimEnd()
            if (t.isBlank()) null else PrintedLine(0, i * 10f, t, FloatArray(t.length) { it * 5f })
        }

    // ------------------------------------------------------------ tokens

    private const val MON = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private fun monthOf(s: String) = months.indexOf(s.take(3).lowercase()).takeIf { it >= 0 }?.plus(1)
    private fun year(raw: String) = raw.toInt().let { if (it < 100) 2000 + it else it }

    /**
     * Dates anywhere in a line: 05/09/2026, 05-09-26, 05.09.2026, 2026-09-05, 05 Aug 2026, 13-Aug-26, 03-Sept-26,
     * 12 August 26, Aug 05, 2026, 05/09, 05 Aug, Aug 05.
     */
    private val dateRx = Regex(
        """(?<![\d/.\-])(?:(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})|(\d{1,2})[\s\-/]($MON)[\s\-/,]*(\d{4}|\d{2})(?!\d)|($MON)\s+(\d{1,2}),?\s+(\d{4})|(\d{1,2})/(\d{1,2})|(\d{1,2})[\s\-]($MON)(?![a-z]*[\s\-/,]*\d)|""" +
            """(\d{4})-(\d{1,2})-(\d{1,2})|\b($MON)\s+(\d{1,2})(?![\d,]|\s+\d{4}))(?![\d/])""",
        RegexOption.IGNORE_CASE,
    )

    private fun dateOf(m: MatchResult, fallbackYear: Int): CalendarDate? {
        val g = m.groupValues
        return runCatching {
            when {
                g[1].isNotEmpty() -> CalendarDate.of(year(g[3]), g[2].toInt(), g[1].toInt())
                g[4].isNotEmpty() -> CalendarDate.of(year(g[6]), monthOf(g[5])!!, g[4].toInt())
                g[7].isNotEmpty() -> CalendarDate.of(g[9].toInt(), monthOf(g[7])!!, g[8].toInt())
                g[10].isNotEmpty() -> CalendarDate.of(fallbackYear, g[11].toInt(), g[10].toInt())
                g[12].isNotEmpty() -> CalendarDate.of(fallbackYear, monthOf(g[13])!!, g[12].toInt())
                g[14].isNotEmpty() -> CalendarDate.of(g[14].toInt(), g[15].toInt(), g[16].toInt())
                else -> CalendarDate.of(fallbackYear, monthOf(g[17])!!, g[18].toInt())
            }
        }.getOrNull()
    }

    /**
     * Money: 1,234.56 / 1234.56 / (1,234.56) / -1,234.56 / +1,234.56, optionally followed by CR / DR (also in the next
     * column, up to a few spaces away) or a minus sign. Brackets, a leading minus, CR or a trailing minus mean a credit.
     */
    private val amountRx = Regex(
        """(?<![\d.,/])(\(|-|\+)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+\.\d{2}|(?<![\w\-])\d{4,7}(?=\s|$))(?![\d/])\)?(?:\s{0,8}(CR|Cr|cr|DR|Dr|dr)\b|\s?(-)(?!\d))?""",
    )

    private data class Amt(
        val minor: Long, val credit: Boolean, val debitMark: Boolean, val start: Int, val end: Int, val xEnd: Float,
        val integer: Boolean = false, val plus: Boolean = false,
        /** Credit only because of a "CR" mark (on an account balance that means "in credit", i.e. positive). */
        val creditMarkOnly: Boolean = false,
        /** -1: printed with a minus or in brackets; +1: with a plus; 0: no sign (CR/DR marks don't count here). */
        val sign: Int = 0,
    )

    private fun minor(s: String): Long = Decimal(s.replace(",", "")).movePointRight(2).setScale(0).unscaled

    private fun amountsIn(line: PrintedLine, from: Int = 0): List<Amt> =
        amountRx.findAll(line.text, from).mapNotNull { m ->
            val v = runCatching { minor(m.groupValues[2]) }.getOrNull() ?: return@mapNotNull null
            val sign = m.groupValues[1]
            val mark = m.groupValues[3]
            val trailingMinus = m.groupValues[4] == "-"
            val numEnd = m.groups[2]!!.range.last + 1
            val raw = m.groupValues[2]
            val integer = !raw.contains('.') && !raw.contains(',')
            // A card / reference number, not money: "0098", "XXXX 1234", "No. 1234".
            if (integer && (raw.startsWith("0") || Regex("""(?:[X*#•]\s?|No\.?\s?|:\s?)$""", RegexOption.IGNORE_CASE).containsMatchIn(line.text.substring(0, m.range.first)))) return@mapNotNull null
            Amt(
                minor = v,
                credit = sign == "-" || sign == "(" || mark.equals("CR", true) || trailingMinus,
                debitMark = mark.equals("DR", true),
                start = m.range.first, end = m.range.last + 1, xEnd = line.xEnd(numEnd),
                integer = integer,
                plus = sign == "+",
                creditMarkOnly = mark.equals("CR", true) && sign.isEmpty() && !trailingMinus,
                sign = when { sign == "-" || sign == "(" || trailingMinus -> -1; sign == "+" -> 1; else -> 0 },
            )
        }.toList()

    // ------------------------------------------------------------ figures by label

    private enum class Kind { DATE, AMOUNT }

    private data class Found<T>(val value: T, val order: Int)

    /**
     * Value for a label: first to its right on the same line (before another label starts), otherwise in the
     * next rows whose text overlaps the label's column.
     */
    private fun <T> byLabel(lines: List<PrintedLine>, labels: List<Regex>, kind: Kind, year: Int, accept: (T) -> Boolean = { true }): Found<T>? {
        // Labels in sentences ("... will only have a minimum amount due of 5% ...") are explanations, not figures.
        fun prose(l: PrintedLine, at: Int): Boolean {
            val before = l.text.substring(0, at).trim().split(Regex("""\s+""")).takeLast(3)
            val words = l.text.split(Regex("""\s+"""))
            val glue = words.count { it.lowercase() in setOf("the", "you", "your", "will", "of", "if", "to", "for", "and", "is", "are", "be", "only", "have", "an", "a", "this", "that", "on", "we") }
            return glue >= 3 || (before.count { w -> w.lowercase() in setOf("a", "the", "your", "have", "of", "only") } >= 1 && glue >= 2)
        }
        for ((li, lab) in labels.withIndex()) {
            for ((i, l) in lines.withIndex()) {
                val m = lab.find(l.text) ?: continue
                if (prose(l, m.range.first)) continue
                @Suppress("UNCHECKED_CAST")
                fun pick(line: PrintedLine, fromIdx: Int, x0: Float, x1: Float, sameLine: Boolean): T? {
                    val centre = (x0 + x1) / 2f
                    // (value, start x, end x)
                    val cands: List<Triple<T, Float, Float>> = if (kind == Kind.DATE) {
                        dateRx.findAll(line.text, fromIdx).mapNotNull { dm ->
                            val d = dateOf(dm, year) ?: return@mapNotNull null
                            Triple(d as T, line.xAt(dm.range.first), line.xEnd(dm.range.last + 1))
                        }.toList()
                    } else {
                        amountsIn(line, fromIdx).map { a -> Triple((if (a.credit) -a.minor else a.minor) as T, line.xAt(a.start), a.xEnd) }
                    }
                    if (sameLine) {
                        return cands.firstOrNull { it.second - x1 <= 460 && accept(it.first) }?.first
                    }
                    // below: the value whose column is closest to the label's column
                    return cands.filter { it.third >= x0 - 30 && it.second <= x1 + 40 && accept(it.first) }
                        .minByOrNull { abs((it.second + it.third) / 2f - centre) }?.first
                }
                fun rightBelow(x0: Float, x1: Float): T? {
                    for (j in i + 1..minOf(i + 3, lines.lastIndex)) {
                        val n = lines[j]
                        if (n.page != l.page || n.y - l.y > 26f) break
                        val from = n.xs.indexOfFirst { it >= x1 - 5f }.takeIf { it >= 0 } ?: continue
                        pick(n, from, x0, x1, sameLine = true)?.let { return it }
                    }
                    return null
                }
                val x0 = l.xAt(m.range.first); val x1 = l.xEnd(m.range.last + 1)
                // same line, to the right, stopping at the next word that looks like another label
                val rest = l.text.substring(m.range.last + 1)
                val stop = Regex("""\s{2,}[A-Za-z][A-Za-z .()/]{3,}""").findAll(rest).firstOrNull { s ->
                    // Not a label: a currency ("AED 1,234.00"), a date ("Oct 07, 2026"), a CR/DR mark or dotted leaders.
                    val word = s.value.trim()
                    val at = s.range.first + (s.value.length - s.value.trimStart().length)
                    !(Regex("""^(?:AED|USD|EUR|GBP|Dhs?|CR|DR)\b""", RegexOption.IGNORE_CASE).containsMatchIn(word) ||
                        dateRx.find(rest, at)?.range?.first == at)
                }?.let { s ->
                    // only stop if a value comes after that next label, i.e. this label's value isn't on this line
                    val before = rest.substring(0, s.range.first)
                    if (dateRx.containsMatchIn(before) || amountRx.containsMatchIn(before)) null else m.range.last + 1 + s.range.first
                }
                val sameLineText = if (stop != null) PrintedLine(l.page, l.y, l.text.substring(0, stop), l.xs.copyOfRange(0, stop)) else l
                pick(sameLineText, m.range.last + 1, x0, x1, sameLine = true)?.let { return Found(it, li) }
                // Figure printed just BEFORE its label on the same line ("AED 936.00   Total Amount Due"): the value must
                // end right before the label, with only spaces between.
                run {
                    val before = l.text.substring(0, m.range.first)
                    if (before.isBlank() || anyLabel.any { it.containsMatchIn(before) }) return@run
                    val cands: List<Pair<T, Int>> = if (kind == Kind.DATE) {
                        @Suppress("UNCHECKED_CAST")
                        dateRx.findAll(before).mapNotNull { dm -> dateOf(dm, year)?.let { (it as T) to (dm.range.last + 1) } }.toList()
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        amountsIn(PrintedLine(l.page, l.y, before, l.xs.copyOfRange(0, before.length))).map { a -> ((if (a.credit) -a.minor else a.minor) as T) to a.end }
                    }
                    val last = cands.lastOrNull() ?: return@run
                    val gap = l.text.substring(last.second, m.range.first)
                    if (gap.isBlank() && x0 - l.xEnd(last.second) <= 60f && accept(last.first)) return Found(last.first, li)
                }
                // value printed a little above/below the label's baseline, to its right
                for (j in maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)) {
                    val n = lines[j]
                    if (j == i || n.page != l.page || abs(n.y - l.y) > 6f) continue
                    val from = n.xs.indexOfFirst { it >= x1 - 4f }.takeIf { it >= 0 } ?: continue
                    pick(n, from, x0, x1, sameLine = true)?.let { return Found(it, li) }
                }
                // Summary tiles: the figure printed ABOVE its caption ("AED 2,531.60" over "Total Amount Due"). Only when the
                // label's own row has no values and the value row above is closer than any value row below.
                // Not when the column starts with a caption (caption, its value, next caption, its value ...): then the figure
                // above this label belongs to the caption above it ("Credit Card Limit / 10,000 / Available Credit / Card Limit / 5,504").
                fun columnStartsWithCaption(): Boolean {
                    var topIsCaption = false
                    var prevY = l.y
                    for (k in i - 1 downTo maxOf(0, i - 8)) {
                        val n = lines[k]
                        if (n.page != l.page || prevY - n.y > 30f) break
                        val part = columnPart(n, x0 - 30f, x1 + 40f)
                        if (part == null) { prevY = n.y; continue }
                        val hasAmt = amountsIn(part).isNotEmpty()
                        val isLabel = !hasAmt && anyLabel.any { it.containsMatchIn(part.text) }
                        if (!hasAmt && !isLabel) break // a heading or other text: the column starts below it
                        topIsCaption = isLabel
                        prevY = n.y
                    }
                    return topIsCaption
                }
                if (amountsIn(l).isEmpty() && !dateRx.containsMatchIn(l.text) && !columnStartsWithCaption()) {
                    val above = (i - 1 downTo maxOf(0, i - 2)).map { lines[it] }
                        .firstOrNull { n -> n.page == l.page && l.y - n.y in 1f..22f && anyLabel.none { r -> r.containsMatchIn(n.text) } }
                    val v = above?.let { pick(it, 0, x0, x1, sameLine = false) }
                    if (above != null && v != null) {
                        val below = (i + 1..minOf(lines.lastIndex, i + 3)).map { lines[it] }
                            .firstOrNull { n -> n.page == l.page && n.y > l.y && pick(n, 0, x0, x1, sameLine = false) != null }
                        if (below == null || l.y - above.y < below.y - l.y) return Found(v, li)
                    }
                }
                // label continues on the next rows (same left edge) and the value is at the end of one of them
                for (j in i + 1..minOf(i + 4, lines.lastIndex)) {
                    val n = lines[j]
                    if (n.page != l.page || n.y - l.y > 45f) break
                    val left = n.xs.firstOrNull() ?: continue
                    if (abs(left - x0) > 25f || n.text.firstOrNull()?.isLetter() != true) continue
                    // another figure's own row ("Previous Balance 950.00", "Card No 0098"), not this label continuing
                    if (anyLabel.any { it.containsMatchIn(n.text) } || Regex("""^(card|account|a/c)\b""", RegexOption.IGNORE_CASE).containsMatchIn(n.text)) continue
                    val from = n.xs.indexOfFirst { it >= x0 + 20f }.takeIf { it >= 0 } ?: continue
                    pick(n, from, x0, x1, sameLine = true)?.let { return Found(it, li) }
                }
                // rows below, same column
                for (j in i + 1..minOf(i + 5, lines.lastIndex)) {
                    if (lines[j].page != l.page) break
                    pick(lines[j], 0, x0, x1, sameLine = false)?.let { return Found(it, li) }
                }
                // last resort: just below and to the right (label column on the left, values right-aligned)
                rightBelow(x0, x1)?.let { return Found(it, li) }
            }
        }
        return null
    }

    /** The part of [n] printed between [lo] and [hi] (points), or null when nothing is there. */
    private fun columnPart(n: PrintedLine, lo: Float, hi: Float): PrintedLine? {
        val idx = n.xs.indices.filter { n.xs[it] in lo..hi }
        if (idx.isEmpty()) return null
        val a = idx.first(); val b = idx.last()
        return PrintedLine(n.page, n.y, n.text.substring(a, b + 1), n.xs.copyOfRange(a, b + 1))
    }

    private fun rx(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }

    /*
     * Word concepts with the abbreviations banks use, so a label is recognised however it's shortened:
     * "Stmt Dt", "Tot. Amt. Due", "Min. Amt.", "Avl. Cr. Limit", "Prev. Bal.", "Pymt Due Dt". The full-word
     * labels in each list come first; these catch the shortened and re-ordered forms.
     */
    private const val W_STMT = """(?:statement|stmt|stmnt)\.?"""
    private const val W_DATE = """(?:date|dt)\b\.?"""
    private const val W_TOTAL = """(?:total|tot)\b\.?"""
    private const val W_AMT = """(?:amount|amt)\b\.?"""
    private const val W_MIN = """(?:minimum|min)\b\.?"""
    private const val W_BAL = """(?:balance|bal)\b\.?"""
    private const val W_PREV = """(?:previous|prev|prv)\b\.?"""
    private const val W_AVL = """(?:available|avail|avl)\b\.?"""
    private const val W_CR = """(?:credit|cr)\b\.?"""
    private const val W_LIMIT = """(?:limit|lmt)\b\.?"""
    private const val W_PAY = """(?:payment|pymt|pmt)\b\.?"""
    /** Not an "available" figure: blocks "Avl. Cr. Limit" from reading as the credit limit. */
    private const val NOT_AVL = """(?<!avl\.\s)(?<!avl\s)(?<!avl\.)(?<!avail\.\s)(?<!avail\s)(?<!available\s)(?<!available)"""

    /** Not the current figure: "Previous Statement Balance", "Last Month's Closing Balance", "Opening Balance". */
    private const val NOT_PAST = """(?<!previous\s)(?<!previous)(?<!last\s)(?<!opening\s)(?<!prior\s)"""

    private val STATEMENT_DATE = rx(
        """statement\s*(?:closing\s*)?date""", """(?:stmt|statement)\.?\s*date""", """closing\s*date""", """date\s*of\s*statement""",
        """statement\s*generated\s*on""", """statement\s*as\s*(?:on|at|of)""", """billing\s*date""", """cycle\s*(?:end\s*)?date""", """print\s*date""",
        """$W_STMT\s*$W_DATE""",
    )
    private val DUE_DATE = rx(
        """payment\s*due\s*date""", """due\s*date""", """(?:payment\s*)?due\s*by""", """pay(?:ment)?\s*by""", """due\s*on""",
        """last\s*(?:date|day)\s*(?:of|for)\s*payment""", """pay\s*before""", """payment\s*date""",
        """(?:$W_PAY\s*)?due\s*$W_DATE""", """$W_PAY\s*$W_DATE""",
    )
    private val CREDIT_LIMIT = rx(
        """total\s*credit\s*limit""", """combined\s*credit\s*limit""", """(?<!available\s)(?<!over\s)(?<!available)credit\s*(?:card\s*)?limit(?!\s*\))""",
        """card\s*limit""", """(?<!available\s)credit\s*line""", """limit\s*assigned""", """approved\s*limit""",
        """$NOT_AVL$W_CR\s*$W_LIMIT""",
    )
    private val AVAILABLE = rx("""available\s*credit\s*limit""", """available\s*(?:credit\s*)?line""", """available\s*limit""", """available\s*credit(?!\s*cash)""", """available\s*balance""", """open\s*to\s*buy""",
        """$W_AVL\s*(?:$W_CR\s*)?$W_LIMIT""", """$W_AVL\s*$W_CR""", """$W_AVL\s*$W_BAL""",
    )
    private val TOTAL_DUE = rx(
        """total\s*payment\s*due""", """total\s*amount\s*due""", """amount\s*due\s*to\s*avoid""", """total\s*amount\s*payable""",
        """total\s*due""", """${NOT_PAST}statement\s*balance""", """total\s*outstanding(?:\s*(?:balance|amount))?""", """new\s*balance(?:\s*outstanding)?""",
        """${NOT_PAST}closing\s*balance""", """current\s*balance""", """${NOT_PAST}outstanding\s*(?:balance|amount)""",
        """(?<!minimum\s)(?<!min\.\s)(?<!min\s)amount\s*due""", """balance\s*due""", """amount\s*payable""",
        """$W_TOTAL\s*(?:$W_AMT\s*)?due""", """$W_TOTAL\s*$W_AMT\s*payable""", """${NOT_PAST}$W_STMT\s*$W_BAL""",
    )
    private val MIN_DUE = rx(
        """minimum\s*payment\s*due""", """minimum\s*amount\s*(?:due|payable)""", """min\.?\s*amount\s*(?:due|payable)""", """minimum\s*due""",
        """min(?:imum)?\.?\s*payment(?:\s*amount)?""", """min\.?\s*due""", """minimum\s*to\s*pay""",
        """$W_MIN\s*(?:$W_AMT|$W_PAY)(?:\s*due)?""",
    )
    private val PREVIOUS = rx(
        """previous\s*(?:statement\s*)?balance""", """previous\s*statement(?:\s*due)?""", """opening\s*balance""", """balance\s*(?:brought|b)\s*/?\s*(?:forward|f)""",
        """last\s*statement\s*balance""", """prior\s*balance""",
        """$W_PREV\s*(?:$W_STMT\s*)?$W_BAL""", """balance\s*(?:from|of|on|as\s*(?:of|per))\s*(?:the\s*|your\s*)?(?:last|previous|prior)\s*statement""",
        """last\s*month'?s?\s*(?:closing\s*)?balance""",
        """(?:balance\s*at\s*(?:the\s*)?(?:start|beginning)|starting\s*balance|beginning\s*balance)""",
    )

    private const val PROSE_AMT = """(?:AED|Dhs?\.?)?\s?(\d{1,3}(?:,\d{3})*\.\d{2})"""
    private val PROSE_TOTAL = Regex(
        """\b(?:statement|closing|new|outstanding|current)\s+balance\s+(?:is|was|of|stands\s+at)\s+$PROSE_AMT|\btotal\s+(?:amount\s+)?(?:due|outstanding)\s+(?:is|of)\s+$PROSE_AMT|\byou\s+owe\s+$PROSE_AMT""",
        RegexOption.IGNORE_CASE,
    )
    private val PROSE_MIN = Regex(
        """\bpay\s+(?:at\s+least|a\s+minimum\s+of|the\s+minimum\s+(?:amount\s+)?of)\s+$PROSE_AMT|\bminimum\s+(?:payment|amount)(?:\s+due)?\s+(?:is|of)\s+$PROSE_AMT""",
        RegexOption.IGNORE_CASE,
    )
    /** "... by 04 October 2026", "due on 12/10/2026", "before 5 Oct 2026" in a sentence about paying. */
    private val PROSE_DUE = Regex(
        """\b(?:pay|payment|paid)\b(?:[^.]|\.(?=\d)){0,80}?\b(?:by|before|on\s+or\s+before|due\s+on)\s+((?:\d{1,2}|$MON))""",
        RegexOption.IGNORE_CASE,
    )

    /** Every label above, to tell a label row from a value row. */
    private val anyLabel: List<Regex> by lazy { STATEMENT_DATE + DUE_DATE + CREDIT_LIMIT + AVAILABLE + TOTAL_DUE + MIN_DUE + PREVIOUS }
    private val PERIOD = Regex("""(?:period|from)\D{0,30}?($MON|\d)""", RegexOption.IGNORE_CASE)

    private val bankNames = listOf(
        "FAB" to """First Abu Dhabi Bank|bankfab|\bFAB\b""", "Emirates NBD" to """Emirates\s*NBD""", "ADCB" to """\bADCB\b|Abu Dhabi Commercial""",
        "Al Hilal" to """Al\s*Hilal""", "Mashreq" to """Mashreq""", "HSBC" to """\bHSBC\b""", "Emirates Islamic" to """Emirates\s+Islamic""",
        "Dubai Islamic" to """Dubai\s+Islamic|\bDIB\b""", "RAKBANK" to """RAKBANK|RAK\s+Bank""", "ADIB" to """\bADIB\b|Abu Dhabi Islamic""",
        "CBD" to """Commercial Bank of Dubai|\bCBD\b""", "Dubai First" to """Dubai\s+First""", "Citibank" to """Citi\s?bank""",
        "Standard Chartered" to """Standard\s+Chartered""", "NBF" to """National Bank of Fujairah|\bNBF\b""",
    ).map { (n, p) -> n to Regex(p, RegexOption.IGNORE_CASE) }

    private val cardMask = Regex("""\b(\d{4})[\s-]?(?:\d{2}|X{2}|\*{2}|x{2})(?:[X*x\d]{2})?[\s-]?(?:X{4}|\*{4}|x{4}|\d{4})[\s-]?(\d{4})\b|[X*x•]{4,}\s?(\d{4})\b|\b\d{6}[X*x]{6}(\d{4})\b|(?:[X*x•]{4}[\s-]){3}(\d{4})\b""")
    /** "Card ending 2468", "Account number ending in 9021", "card ending with ****4411" */
    private val endingIn = Regex("""\b(?:card|account|a/c)\b[^\n]{0,25}?\bending\s*(?:in|with)?\s*:?\s*(?:[X*x•]+\s?)?(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val accountNo = Regex("""(?:AC-?NUM|Account\s+(?:No|Number)\.?|A/C\s+No\.?)\s*:?\s*([\d][\d\- ]{6,}\d)""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ transactions

    private val skipRow = Regex(
        """^\s*(opening\s+balance|closing\s+balance|balance\s+(brought|carried)\s+forward|sub-?\s?total|total\b|previous\s+balance|new\s+balance|statement\s+balance|payment\s+due|minimum)""",
        RegexOption.IGNORE_CASE,
    )
    private val headerWord = Regex(
        """\b(date|description|details|particulars|narration|merchant|amount|debits?|credits?|balance|withdrawals?|deposits?|posting|charges|payments|money\s+(?:in|out)|paid\s+(?:in|out))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val debitHeader = Regex("""\b(debits?|withdrawals?|charges|purchases|money\s+out|paid\s+out|dr)\b(?!\s*/\s*cr)""", RegexOption.IGNORE_CASE)
    private val creditHeader = Regex("""(?<!dr\s/)(?<!dr/)\b(credits?|deposits?|payments|money\s+in|paid\s+in|receipts|cr)\b(?!\s*(card|limit|line))""", RegexOption.IGNORE_CASE)

    private data class Columns(val debit: Float?, val credit: Float?, val balance: Float?, val amount: Float?, val original: Float?)

    private fun colX(l: PrintedLine, r: Regex): Float? = r.find(l.text)?.let { m -> (l.xAt(m.range.first) + l.xEnd(m.range.last + 1)) / 2f }

    /** Header rows of a transaction table (words like Date / Description / Debit / Credit / Balance / Amount). */
    private fun headerAt(lines: List<PrintedLine>, i: Int): Columns? {
        val l = lines[i]
        val hits = headerWord.findAll(l.text).map { it.value.lowercase() }.toSet()
        if (hits.size < 2 || amountsIn(l).isNotEmpty() || dateRx.containsMatchIn(l.text)) return null
        if (!(hits.any { h -> listOf("amount", "debit", "credit", "balance", "withdraw", "deposit", "charges", "payments", "money", "paid").any { h.startsWith(it) } })) return null
        // Summary lines ("Credit Limit  Available Credit") are not table headers.
        if (anyLabel.any { it.containsMatchIn(l.text) } && !Regex("""\bdate\b.*\b(description|details|particulars|narration)\b""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) return null
        // Some headers are split over 2-3 rows ("UAE Dirham Amount" above "Debit  Credit"): only header-like rows right next to it.
        val band = (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).map { lines[it] }.filter {
            it.page == l.page && abs(it.y - l.y) < 25 && amountsIn(it).isEmpty() && !dateRx.containsMatchIn(it.text) &&
                (it === l || (headerWord.containsMatchIn(it.text) && anyLabel.none { r -> r.containsMatchIn(it.text) }))
        }
        fun find(r: Regex) = band.firstNotNullOfOrNull { colX(it, r) }
        // A single "Dr/Cr" column is a mark after the amount, not two money columns.
        val debit = find(debitHeader)
        val credit = find(creditHeader)
        val balance = find(Regex("""\bbalance\b""", RegexOption.IGNORE_CASE))
        // The AED amount column: prefer "Amount (AED)" / "AED amount"; otherwise the right-most "Amount".
        val aedAmount = band.firstNotNullOfOrNull { colX(it, Regex("""amount\s*\(?AED\)?|AED\s+amount|amount\s+in\s+AED|\(AED\)""", RegexOption.IGNORE_CASE)) }
        val anyAmount = band.flatMap { b -> Regex("""\bamount\b""", RegexOption.IGNORE_CASE).findAll(b.text).map { m -> (b.xAt(m.range.first) + b.xEnd(m.range.last + 1)) / 2f }.toList() }
        val original = band.firstNotNullOfOrNull { colX(it, Regex("""original\s+currency|transaction\s+currency|original\s+amount""", RegexOption.IGNORE_CASE)) }
        val amount = aedAmount ?: anyAmount.filter { original == null || abs(it - original) > 20 }.maxOrNull()
        return Columns(debit, credit.takeIf { debit != null || it != null }, balance, amount, original)
    }

    private fun cleanDescription(s: String): String =
        s.replace(Regex("""\s+\d{7,}\s*$"""), "").replace(Regex("""\s+(AED|USD|EUR|GBP|SAR|INR|PKR)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(ARE|UAE|AE|DXB|SHJ|AJM|AUH|784)\s*$"""), "")
            .replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', ',', ':', '*').trim()

    private data class RowDate(val date: CalendarDate, val end: Int, val count: Int)

    /** Transaction date (and a posting date right after it) at the start of the line. */
    private fun leadingDates(l: PrintedLine, year: Int): RowDate? {
        val t = l.text
        val first = dateRx.find(t) ?: return null
        // tolerate a stray letter or two (vertical side text such as "e-statement")
        if (t.substring(0, first.range.first).trim().length > 2) return null
        val d = dateOf(first, year) ?: return null
        var end = first.range.last + 1
        var count = 1
        dateRx.find(t, end)?.let { s -> if (t.substring(end, s.range.first).isBlank()) { end = s.range.last + 1; count = 2 } }
        return RowDate(d, end, count)
    }

    // ------------------------------------------------------------ main

    fun analyze(lines: List<PrintedLine>, fallbackYear: Int): StatementAnalysis {
        val all = lines.joinToString("\n") { it.text }
        val year = Regex("""\b(20\d{2})\b""").findAll(all).map { it.value.toInt() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: fallbackYear

        // Bank account statement (not a card): "Cr" on a balance then means money you have, not a credit to a card.
        val headText = lines.take(80).joinToString("\n") { it.text }
        val looksAccount = Regex("""account\s*statement|statement\s*of\s*account|account\s*summary""", RegexOption.IGNORE_CASE).containsMatchIn(headText) &&
            !Regex("""minimum\s*(payment|amount)|min\.?\s*amount|credit\s*limit|card\s*limit""", RegexOption.IGNORE_CASE).containsMatchIn(all)
        fun balanceOf(a: Amt): Long = if (looksAccount) (if (a.debitMark || (a.credit && !a.creditMarkOnly)) -a.minor else a.minor) else if (a.credit) -a.minor else a.minor

        // -------- transactions
        val out = mutableListOf<StatementLine>()
        val evidence = mutableListOf<Evidence>()
        var cols: Columns? = null
        var section: String? = null
        var lastBalance: Long? = null
        var pending: String? = null
        var pendingAmts: List<Amt> = emptyList()
        var lastRowDates = 0
        var headerPage = -1
        var headerY = 0f
        // a card heading inside the table: "Supplementary Card Number ... XXXX 3944", or a card number alone on its line
        fun cardHeading(l: PrintedLine): String? {
            if (l.page != headerPage || l.y <= headerY) return null
            val m = cardMask.find(l.text) ?: return null
            val masked = Regex("""[X*x]""").containsMatchIn(m.value)
            val alone = l.text.trim().length <= m.value.length + 2 && Regex("""\d{4}[\s-]\d{4}[\s-]\d{4}[\s-]\d{4}""").matches(m.value.trim())
            return if (alone || (masked && Regex("""card|:""", RegexOption.IGNORE_CASE).containsMatchIn(l.text))) m.groupValues.drop(1).lastOrNull { it.length == 4 } else null
        }
        var firstTxnIndex = -1
        // After a heading such as "Your Installment Plans" or "Your Rewards" the rows belong to another table (with
        // dates and amounts of its own) and are not transactions, until a transaction table's header appears again.
        var otherTable = false
        val otherTableHeading = Regex("""^\s*(?:your\s*)?(?:ins?tall?ments?\s*plans?|reward\s*(?:points|summary)|rewards|loyalty|interest\s*rates?|payment\s*allocation)\b""", RegexOption.IGNORE_CASE)
        val txnHeaderWord = Regex("""\b(?:description|particulars|narration|details|transactions?)\b""", RegexOption.IGNORE_CASE)
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (otherTableHeading.containsMatchIn(l.text) && amountsIn(l).isEmpty()) { otherTable = true; cols = null; pending = null; i++; continue }
            val hdr = headerAt(lines, i)
            if (hdr != null) {
                val band = (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).filter { lines[it].page == l.page && abs(lines[it].y - l.y) < 25 }
                if (otherTable && band.none { txnHeaderWord.containsMatchIn(lines[it].text) }) { i++; continue } // the other table's own header
                otherTable = false
                cols = hdr; pending = null; headerPage = l.page; headerY = l.y; i++; continue
            }
            val row = leadingDates(l, year)
            if (otherTable) { i++; continue }
            if (row == null) {
                // card sections inside the table ("Supplementary Card Number ... 3944")
                cardHeading(l)?.let { section = it }
                // an opening balance helps the balance-change check
                if (Regex("""opening\s+balance|brought\s+forward|previous\s+balance|balance\s+at\s+(?:the\s+)?(?:start|beginning)|starting\s+balance|beginning\s+balance""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) {
                    amountsIn(l).lastOrNull()?.let { lastBalance = balanceOf(it) }
                }
                pending = if (cols != null && l.text.trim().length >= 4 && !skipRow.containsMatchIn(l.text) && headerAt(lines, i) == null) {
                    val a0 = amountsIn(l).firstOrNull { !it.integer }
                    (if (a0 != null) l.text.substring(0, a0.start) else l.text).trim().takeIf { it.length >= 3 && !Regex("""^[A-Z]{3}/[A-Z]{3}""").containsMatchIn(it) }
                } else null
                pendingAmts = if (pending != null) amountsIn(l).filterNot { it.integer } else emptyList()
                i++; continue
            }
            // collect the transaction block: this line plus continuation lines (no date) until the next dated row
            val block = mutableListOf(l)
            var j = i + 1
            while (j < lines.size && j - i <= 8) {
                val n = lines[j]
                if (n.page != l.page || leadingDates(n, year) != null || headerAt(lines, j) != null || skipRow.containsMatchIn(n.text) || cardHeading(n) != null ||
                    Regex("""(supplementary|primary|additional)\s*card""", RegexOption.IGNORE_CASE).containsMatchIn(n.text)) break
                if (n.text.trim() == "-") { j++; break }
                block += n; j++
            }
            val rest = l.text.substring(row.end)
            var amts = amountsIn(l, row.end).filterNot { it.integer }
            var fromBlock = false
            if (amts.isEmpty() && cols == null) { i++; continue }
            if (amts.isEmpty()) {
                // multi-line transaction: amounts on the line above (description line) and the lines below
                amts = pendingAmts + block.drop(1).flatMap { amountsIn(it) }.filterNot { it.integer }
                fromBlock = true
            }
            val descEnd = amts.firstOrNull()?.takeIf { !fromBlock }?.start ?: l.text.length
            var desc = cleanDescription(l.text.substring(row.end, descEnd.coerceAtLeast(row.end)))
            // Before any table header, a dated line with no description of its own is a row of summary figures
            // ("12 Sep 2026   07 Oct 2026   AED 2,531.60"), not a transaction.
            if (cols == null && desc.isBlank()) { i++; continue }
            if (desc.length < 4 && pending != null) desc = cleanDescription(pending!! + if (desc.isNotBlank()) " $desc" else "")
            // (never a row of labels: a line of summary figures isn't a transaction)
            if (desc.isBlank()) desc = block.drop(1).firstOrNull { amountsIn(it).isEmpty() && anyLabel.none { r -> r.containsMatchIn(it.text) } }?.text?.let(::cleanDescription) ?: ""
            // generic bank wording ("Telex Transfer", "IPP Transfer"): the next line says what it was for ("/REF/car installment")
            if (desc.length < 20 || desc.endsWith(":") || Regex("""transfer|remittance|payment|information|IPP|instant""", RegexOption.IGNORE_CASE).containsMatchIn(desc)) {
                block.drop(1).firstOrNull { amountsIn(it).isEmpty() && it.text.trim().length > 3 && anyLabel.none { r -> r.containsMatchIn(it.text) } }?.let { c ->
                    val extra = cleanDescription(c.text)
                    if (extra.isNotEmpty() && !Regex("""^(FT|REF)\w+$""").matches(extra)) desc = cleanDescription("$desc $extra").take(80)
                }
            }
            pending = null; pendingAmts = emptyList()
            // a dated line that continues the previous transaction ("23-08-2026 towards Principle AED ...")
            val firstCh = desc.firstOrNull()
            if (row.count < lastRowDates && firstCh != null && (firstCh.isLowerCase() || !firstCh.isLetterOrDigit())) { i = j; continue }
            lastRowDates = row.count
            if (amts.isEmpty() || desc.isBlank() || skipRow.containsMatchIn(desc) || skipRow.containsMatchIn(rest)) { i = j; continue }

            // pick the amount and its direction
            var amount: Amt? = null
            var credit = false
            var balance: Long? = null
            var colDir: Boolean? = null
            val c = cols
            if (c != null && !fromBlock && (c.debit != null || c.credit != null || c.amount != null)) {
                val targets = listOfNotNull(c.debit?.let { "debit" to it }, c.credit?.let { "credit" to it }, c.balance?.let { "balance" to it },
                    c.amount?.let { "amount" to it }, c.original?.let { "original" to it })
                for (a in amts) {
                    val (name, _) = targets.minByOrNull { abs(it.second - (a.xEnd - 8f)) } ?: continue
                    when (name) {
                        "debit" -> if (amount == null) { amount = a; credit = a.credit; colDir = false }
                        "credit" -> if (amount == null) { amount = a; credit = !a.debitMark; colDir = true }
                        "amount" -> if (amount == null || c.debit == null) { amount = a; credit = a.credit }
                        "balance" -> balance = balanceOf(a)
                        else -> {}
                    }
                }
                if (amount == null) amount = amts.filter { a -> targets.minByOrNull { abs(it.second - (a.xEnd - 8f)) }?.first != "original" }.lastOrNull() ?: amts.last()
                if (amount.credit) credit = true
            } else if (fromBlock) {
                // multi-line transaction: the last amount of the block is the AED total (includes fees)
                amount = amts.last(); credit = amount.credit
            } else {
                val aed = amts.firstOrNull { a -> l.text.substring(0, a.start).trimEnd().endsWith("AED", true) }
                amount = if (amts.size >= 2) (aed?.let { amts.last() } ?: amts.first()) else amts.first()
                credit = amount.credit || amts.last().credit
                if (amts.size >= 2 && aed == null) balance = balanceOf(amts.last())
            }
            val a = amount
            // On a bank account a minus means money out and a plus money in (on a card, a minus is a credit).
            if (looksAccount && colDir == null && a.sign != 0) credit = a.sign > 0
            // a rising running balance means money in (when columns didn't already say)
            val balDir = if (balance != null && lastBalance != null && balance != lastBalance) balance > lastBalance!! else null
            if (balDir != null && (c?.credit == null || c.debit == null)) credit = balDir
            if (balance != null) lastBalance = balance
            val kwDir = creditWordsStrict.containsMatchIn(desc)
            if (kwDir && c?.debit == null) credit = credit || !a.debitMark
            if (a.minor == 0L) { i = j; continue }
            if (firstTxnIndex < 0) firstTxnIndex = i
            out += StatementLine(row.date, desc, a.minor, credit, block.joinToString(" | ") { it.text.trim() }, section)
            evidence += Evidence(
                column = colDir,
                mark = when { a.debitMark -> false; a.credit -> true; a.plus -> true; else -> null },
                sign = a.sign,
                balance = balDir,
                keyword = kwDir,
                keywordBroad = creditWords.containsMatchIn(desc),
            )
            i = j
        }

        // -------- key figures
        val header = if (firstTxnIndex > 0) lines.subList(0, firstTxnIndex) else lines
        val stmtDate = byLabel<CalendarDate>(lines, STATEMENT_DATE, Kind.DATE, year)?.value
        val dueDate = byLabel<CalendarDate>(lines, DUE_DATE, Kind.DATE, year, accept = { d -> stmtDate == null || (d.isAfter(stmtDate) && CalendarDate.daysBetween(stmtDate, d) <= 60) })?.value
        val limit = byLabel<Long>(lines, CREDIT_LIMIT, Kind.AMOUNT, year, accept = { it > 0 })?.value
        val avail = byLabel<Long>(lines, AVAILABLE, Kind.AMOUNT, year)?.value
        var total = byLabel<Long>(lines, TOTAL_DUE, Kind.AMOUNT, year)?.value
        var min = byLabel<Long>(lines, MIN_DUE, Kind.AMOUNT, year, accept = { it >= 0 && (total == null || it <= maxOf(total!!, 0L) + 100) })?.value
        // On an account a "Cr" balance is money you have: read it as positive.
        val previous = byLabel<Long>(lines, PREVIOUS, Kind.AMOUNT, year)?.value?.let { if (looksAccount) abs(it) else it }

        // Figures given only in sentences ("Your statement balance is AED 635.00. Please pay at least AED 31.75 by
        // 04 October 2026"): read clear phrasing only, and only when no labelled figure was found.
        val prose = lines.joinToString(" ") { it.text }.replace(Regex("""\s+"""), " ")
        fun proseAmount(r: Regex): Long? = r.find(prose)?.groupValues?.get(1)?.let { runCatching { minor(it) }.getOrNull() }
        if (total == null) total = proseAmount(PROSE_TOTAL)
        if (min == null) min = proseAmount(PROSE_MIN)?.takeIf { m -> total == null || m <= maxOf(total!!, 0L) }
        val proseDue = if (dueDate == null) PROSE_DUE.find(prose)?.let { m -> dateRx.find(prose, m.range.last + 1 - m.groupValues[1].length)?.let { dateOf(it, year) } } else null

        // inference for layouts whose labels are pictures (only the values are text)
        var sDate = stmtDate
        var dDate = dueDate ?: proseDue?.takeIf { d -> stmtDate == null || (d.isAfter(stmtDate) && CalendarDate.daysBetween(stmtDate, d) <= 60) }
        val loneDates = header.mapNotNull { l -> dateRx.matchEntire(l.text.trim())?.let { dateOf(it, year) } }
        if (sDate == null || dDate == null) {
            for (k in 0 until loneDates.size - 1) {
                val a = loneDates[k]; val b = loneDates[k + 1]
                val gap = CalendarDate.daysBetween(a, b)
                if (gap in 10..45) { if (sDate == null) sDate = a; if (dDate == null) dDate = b; break }
            }
        }
        val loneAmounts = header.flatMap { l -> amountsIn(l).filterNot { it.integer || it.credit }.map { it.minor } }
        var cLimit = limit
        var cAvail = avail
        if (total != null && total!! > 0 && (cLimit == null || cAvail == null)) {
            // limit = available + balance (both printed, labels may be pictures)
            outer@ for (x in loneAmounts) for (y in loneAmounts) {
                if (x > y && y > 0 && (cLimit == null || x == cLimit) && abs(x - (y + total!!)) <= maxOf(100L, x / 100)) {
                    if (cLimit == null) cLimit = x; if (cAvail == null) cAvail = y; break@outer
                }
            }
        }
        if (min == null && total != null && total!! > 0) {
            val target = maxOf(10_000L, total!! * 5 / 100)
            min = loneAmounts.filter { it in 1..total!! }.minByOrNull { abs(it - target) }?.takeIf { abs(it - target) <= target }
        }
        if (total == null && loneAmounts.isNotEmpty() && previous != null) total = null

        // account statements: period end as statement date
        val periodMatch = Regex("""(?:period|from)\b[^\n]{0,40}?$MON|(?:period|from)\b""", RegexOption.IGNORE_CASE)
        var periodTo: CalendarDate? = null
        var periodFrom: CalendarDate? = null
        lines.firstOrNull { periodMatch.containsMatchIn(it.text) && dateRx.findAll(it.text).count() >= 2 }?.let { pl ->
            val ds = dateRx.findAll(pl.text).mapNotNull { dateOf(it, year) }.toList()
            periodFrom = ds.firstOrNull(); periodTo = ds.lastOrNull()
        }
        if (sDate == null || (periodTo != null && abs(CalendarDate.daysBetween(periodTo, sDate)) > 5 && dDate != null && !sDate!!.isBefore(dDate))) sDate = periodTo

        // card number and bank
        val accountLast4 = accountNo.find(all)?.groupValues?.get(1)?.filter(Char::isDigit)?.takeLast(4)
        val last4 = if (looksAccount && accountLast4 != null) accountLast4
            else cardMask.find(headText)?.groupValues?.drop(1)?.lastOrNull { it.length == 4 }
                ?: endingIn.find(headText)?.groupValues?.get(1) ?: accountLast4
        val bank = bankNames.maxByOrNull { (_, r) ->
            lines.sumOf { l -> val n = r.findAll(l.text).count(); if (Regex("""PJSC|P\.J\.S\.C|licensed|regulated|www\.|Limited|Bank\s*TRN""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) n * 4 else n }
        }?.takeIf { (_, r) -> r.containsMatchIn(all) }?.first

        val summary = StatementSummary(
            statementDate = sDate, dueDate = dDate, creditLimitMinor = cLimit, availableLimitMinor = cAvail,
            totalDueMinor = if (looksAccount) null else total, minimumDueMinor = if (looksAccount) null else min,
            cardLast4 = last4, bank = bank, previousBalanceMinor = previous, isAccount = looksAccount,
            closingBalanceMinor = if (looksAccount) byLabel<Long>(
                lines,
                rx("""closing\s*(?:book\s*)?balance""", """balance\s*carried\s*forward""", """balance\s*at\s*(?:the\s*)?end""", """ending\s*balance""", """$W_BAL\s*as\s*(?:at|of)\s*(?:the\s*)?end"""),
                Kind.AMOUNT, year,
            )?.value?.let(::abs) else null,
            periodFrom = periodFrom, periodTo = periodTo,
        )

        // -------- totals check: previous + spends − credits should equal the new balance.
        // When it doesn't, other readings of the same evidence are tried (the statement's own arithmetic decides).
        var check: String? = null
        var agree: Boolean? = null
        var lines2: List<StatementLine> = out
        val balances = if (summary.isAccount) listOfNotNull(summary.closingBalanceMinor)
            else listOfNotNull(total, byLabel<Long>(lines, rx("""current\s*balance""", """(?:total\s*)?outstanding\s*balance""", """new\s*balance""", """statement\s*balance""", """total\s*outstanding"""), Kind.AMOUNT, year)?.value)
        if (previous != null && balances.isNotEmpty() && out.isNotEmpty()) {
            fun expectedFor(dirs: List<Boolean>): Long {
                val cr = out.indices.filter { dirs[it] }.sumOf { out[it].amountMinor }
                val dr = out.indices.filterNot { dirs[it] }.sumOf { out[it].amountMinor }
                return if (summary.isAccount) previous + cr - dr else previous + dr - cr
            }
            fun agrees(dirs: List<Boolean>) = balances.any { abs(it - expectedFor(dirs)) <= 100 }
            val asRead = out.map { it.isCredit }
            var chosen = asRead
            var how: String? = null
            if (!agrees(asRead)) {
                val alternatives = listOf(
                    "the running balance" to evidence.map { e -> e.balance ?: e.column ?: e.mark ?: e.keyword },
                    "the debit / credit columns" to evidence.map { e -> e.column ?: e.mark ?: e.keyword },
                    "the CR / minus marks" to evidence.map { e -> e.mark ?: e.column ?: e.keyword },
                    // Some print money out with a minus and money in without one; others the other way round.
                    "the signs (minus = credit)" to evidence.map { e -> e.sign < 0 },
                    "the signs (minus = money out)" to evidence.map { e -> e.sign >= 0 },
                    "the signs (plus = money in)" to evidence.map { e -> e.sign > 0 },
                    "the descriptions" to evidence.map { e -> e.keywordBroad },
                )
                alternatives.firstOrNull { (_, dirs) -> agrees(dirs) }?.let { (name, dirs) -> chosen = dirs; how = name }
                if (how == null) {
                    // One line read the wrong way round (e.g. a refund without "CR"): flipping it alone fixes the totals.
                    out.indices.firstOrNull { k -> agrees(asRead.mapIndexed { m, d -> if (m == k) !d else d }) }?.let { k ->
                        chosen = asRead.mapIndexed { m, d -> if (m == k) !d else d }
                        how = "one line's direction (\"${out[k].description.take(30)}\")"
                    }
                }
            }
            if (chosen != asRead) lines2 = out.mapIndexed { k, l -> l.copy(isCredit = chosen[k]) }
            val credits = lines2.filter { it.isCredit }.sumOf { it.amountMinor }
            val debits = lines2.filterNot { it.isCredit }.sumOf { it.amountMinor }
            val expected = expectedFor(chosen)
            val closing = balances.minByOrNull { abs(it - expected) }!!
            agree = abs(expected - closing) <= 100
            check = "Read ${out.size} transactions: ${if (summary.isAccount) "in" else "spends"} ${fmt(if (summary.isAccount) credits else debits)}, " +
                "${if (summary.isAccount) "out" else "credits"} ${fmt(if (summary.isAccount) debits else credits)}. " +
                (if (agree) "They add up to the statement's balance." else "Expected balance ${fmt(expected)}, statement shows ${fmt(closing)}: some lines may be missing or instalments are included.") +
                (how?.let { " (Money in / out was worked out from $it.)" } ?: "")
        }
        return StatementAnalysis(summary, lines2, check, agree)
    }

    /** What each transaction line says about its direction; used when the first reading doesn't add up. */
    private data class Evidence(
        val column: Boolean?, val mark: Boolean?, val sign: Int, val balance: Boolean?, val keyword: Boolean, val keywordBroad: Boolean,
    )

    /** Clearly money in / a credit on a card. */
    private val creditWordsStrict = Regex("""PAYMENT\s+RECEIVED|THANK\s+YOU|CASHBACK|REFUND|REVERSAL""", RegexOption.IGNORE_CASE)

    /** Wider: also "SALARY", "CREDIT", "PAYMENT -": only used when the first reading doesn't add up. */
    private val creditWords = Regex(
        """PAYMENT\s+RECEIVED|THANK\s+YOU|CASH\s?BACK|REFUND|REVERSAL|REVERSED|\bCREDIT(?:ED)?\b|SALARY|DEPOSIT|INWARD|PAYMENT\s*-|^PAYMENT\b|FEE\s+WAIVER""",
        RegexOption.IGNORE_CASE,
    )

    private fun fmt(minor: Long) = com.uaefinancial.tracker.parser.SmsParser.homeCurrency + " " + Decimal.valueOf(minor, 2).toPlainString()
}
