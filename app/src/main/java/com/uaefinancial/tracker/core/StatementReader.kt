package com.uaefinancial.tracker.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
        for ((page, pg) in glyphs.filter { it.text.isNotBlank() }.groupBy { it.page }.toSortedMap()) {
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

    /** Dates anywhere in a line: 05/09/2026, 05-09-26, 05 Aug 2026, 13-Aug-26, 03-Sept-26, 12 August 26, Aug 05, 2026, 05/09. */
    private val dateRx = Regex(
        """(?<![\d/.\-])(?:(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})|(\d{1,2})[\s\-/]($MON)[\s\-/,]*(\d{4}|\d{2})(?!\d)|($MON)\s+(\d{1,2}),?\s+(\d{4})|(\d{1,2})/(\d{1,2})|(\d{1,2})[\s\-]($MON)(?![a-z]*[\s\-/,]*\d))(?![\d/])""",
        RegexOption.IGNORE_CASE,
    )

    private fun dateOf(m: MatchResult, fallbackYear: Int): LocalDate? {
        val g = m.groupValues
        return runCatching {
            when {
                g[1].isNotEmpty() -> LocalDate.of(year(g[3]), g[2].toInt(), g[1].toInt())
                g[4].isNotEmpty() -> LocalDate.of(year(g[6]), monthOf(g[5])!!, g[4].toInt())
                g[7].isNotEmpty() -> LocalDate.of(g[9].toInt(), monthOf(g[7])!!, g[8].toInt())
                g[10].isNotEmpty() -> LocalDate.of(fallbackYear, g[11].toInt(), g[10].toInt())
                else -> LocalDate.of(fallbackYear, monthOf(g[13])!!, g[12].toInt())
            }
        }.getOrNull()
    }

    /** Money: 1,234.56 / 1234.56 / .56, optionally followed by CR / DR / a minus sign. */
    private val amountRx = Regex("""(?<![\d.,/])(-?)(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+\.\d{2}|(?<![\w\-])\d{4,7}(?=\s|$))(?![\d/])\s?(CR|Cr|cr|DR|Dr|-(?!\d))?""")

    private data class Amt(val minor: Long, val credit: Boolean, val debitMark: Boolean, val start: Int, val end: Int, val xEnd: Float, val integer: Boolean = false)

    private fun minor(s: String): Long = BigDecimal(s.replace(",", "")).movePointRight(2).toLong()

    private fun amountsIn(line: PrintedLine, from: Int = 0): List<Amt> =
        amountRx.findAll(line.text, from).mapNotNull { m ->
            val v = runCatching { minor(m.groupValues[2]) }.getOrNull() ?: return@mapNotNull null
            val mark = m.groupValues[3]
            val numEnd = m.groups[2]!!.range.last + 1
            Amt(
                minor = v,
                credit = m.groupValues[1] == "-" || mark.equals("CR", true) || mark == "-",
                debitMark = mark.equals("DR", true),
                start = m.range.first, end = m.range.last + 1, xEnd = line.xEnd(numEnd),
                integer = !m.groupValues[2].contains('.') && !m.groupValues[2].contains(','),
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
                val stop = Regex("""\s{2,}[A-Za-z][A-Za-z .()/]{3,}""").find(rest)?.let { s ->
                    // only stop if a value comes after that next label, i.e. this label's value isn't on this line
                    val before = rest.substring(0, s.range.first)
                    if (dateRx.containsMatchIn(before) || amountRx.containsMatchIn(before)) null else m.range.last + 1 + s.range.first
                }
                val sameLineText = if (stop != null) PrintedLine(l.page, l.y, l.text.substring(0, stop), l.xs.copyOfRange(0, stop)) else l
                pick(sameLineText, m.range.last + 1, x0, x1, sameLine = true)?.let { return Found(it, li) }
                // value printed a little above/below the label's baseline, to its right
                for (j in maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)) {
                    val n = lines[j]
                    if (j == i || n.page != l.page || abs(n.y - l.y) > 6f) continue
                    val from = n.xs.indexOfFirst { it >= x1 - 4f }.takeIf { it >= 0 } ?: continue
                    pick(n, from, x0, x1, sameLine = true)?.let { return Found(it, li) }
                }
                // label continues on the next rows (same left edge) and the value is at the end of one of them
                for (j in i + 1..minOf(i + 4, lines.lastIndex)) {
                    val n = lines[j]
                    if (n.page != l.page || n.y - l.y > 45f) break
                    val left = n.xs.firstOrNull() ?: continue
                    if (abs(left - x0) > 25f || n.text.firstOrNull()?.isLetter() != true) continue
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

    private fun rx(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }

    private val STATEMENT_DATE = rx("""statement\s*date""", """date\s*of\s*statement""", """statement\s*generated\s*on""", """statement\s*as\s*(?:on|at|of)""", """billing\s*date""")
    private val DUE_DATE = rx("""payment\s*due\s*date""", """due\s*date""", """pay(?:ment)?\s*by""", """due\s*on""")
    private val CREDIT_LIMIT = rx("""total\s*credit\s*limit""", """combined\s*credit\s*limit""", """(?<!available\s)(?<!over\s)(?<!available)credit\s*limit(?!\s*\))""", """card\s*limit""")
    private val AVAILABLE = rx("""available\s*credit\s*limit""", """available\s*limit""", """available\s*credit(?!\s*cash)""")
    private val TOTAL_DUE = rx(
        """total\s*payment\s*due""", """total\s*amount\s*due""", """amount\s*due\s*to\s*avoid""", """total\s*amount\s*payable""",
        """total\s*due""", """statement\s*balance""", """total\s*outstanding(?:\s*balance)?""", """new\s*balance(?:\s*outstanding)?""",
        """closing\s*balance""", """current\s*balance""", """outstanding\s*balance""",
    )
    private val MIN_DUE = rx("""minimum\s*payment\s*due""", """minimum\s*amount\s*due""", """min\.?\s*amount\s*due""", """minimum\s*due""", """min(?:imum)?\.?\s*payment""", """min\.?\s*due""")
    private val PREVIOUS = rx("""previous\s*balance""", """previous\s*statement(?:\s*due)?""", """opening\s*balance""", """balance\s*brought\s*forward""")
    private val PERIOD = Regex("""(?:period|from)\D{0,30}?($MON|\d)""", RegexOption.IGNORE_CASE)

    private val bankNames = listOf(
        "FAB" to """First Abu Dhabi Bank|bankfab|\bFAB\b""", "Emirates NBD" to """Emirates\s*NBD""", "ADCB" to """\bADCB\b|Abu Dhabi Commercial""",
        "Al Hilal" to """Al\s*Hilal""", "Mashreq" to """Mashreq""", "HSBC" to """\bHSBC\b""", "Emirates Islamic" to """Emirates\s+Islamic""",
        "Dubai Islamic" to """Dubai\s+Islamic|\bDIB\b""", "RAKBANK" to """RAKBANK|RAK\s+Bank""", "ADIB" to """\bADIB\b|Abu Dhabi Islamic""",
        "CBD" to """Commercial Bank of Dubai|\bCBD\b""", "Dubai First" to """Dubai\s+First""", "Citibank" to """Citi\s?bank""",
        "Standard Chartered" to """Standard\s+Chartered""", "NBF" to """National Bank of Fujairah|\bNBF\b""",
    ).map { (n, p) -> n to Regex(p, RegexOption.IGNORE_CASE) }

    private val cardMask = Regex("""\b(\d{4})[\s-]?(?:\d{2}|X{2}|\*{2}|x{2})(?:[X*x\d]{2})?[\s-]?(?:X{4}|\*{4}|x{4}|\d{4})[\s-]?(\d{4})\b|[X*x]{6,}\s?(\d{4})\b|\b\d{6}[X*x]{6}(\d{4})\b""")
    private val accountNo = Regex("""(?:AC-?NUM|Account\s+(?:No|Number)\.?|A/C\s+No\.?)\s*:?\s*([\d][\d\- ]{6,}\d)""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ transactions

    private val skipRow = Regex(
        """^\s*(opening\s+balance|closing\s+balance|balance\s+(brought|carried)\s+forward|sub-?\s?total|total\b|previous\s+balance|new\s+balance|statement\s+balance|payment\s+due|minimum)""",
        RegexOption.IGNORE_CASE,
    )
    private val headerWord = Regex("""\b(date|description|details|particulars|narration|amount|debit|credit|balance|withdrawals?|deposits?|posting)\b""", RegexOption.IGNORE_CASE)

    private data class Columns(val debit: Float?, val credit: Float?, val balance: Float?, val amount: Float?, val original: Float?)

    private fun colX(l: PrintedLine, r: Regex): Float? = r.find(l.text)?.let { m -> (l.xAt(m.range.first) + l.xEnd(m.range.last + 1)) / 2f }

    /** Header rows of a transaction table (words like Date / Description / Debit / Credit / Balance / Amount). */
    private fun headerAt(lines: List<PrintedLine>, i: Int): Columns? {
        val l = lines[i]
        val hits = headerWord.findAll(l.text).map { it.value.lowercase() }.toSet()
        if (hits.size < 2 || amountsIn(l).isNotEmpty() || dateRx.containsMatchIn(l.text)) return null
        if (!(hits.any { it.startsWith("amount") || it.startsWith("debit") || it.startsWith("credit") || it.startsWith("balance") || it.startsWith("withdraw") || it.startsWith("deposit") })) return null
        // Some headers are split over 2-3 rows ("UAE Dirham Amount" above "Debit  Credit").
        val band = (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).map { lines[it] }.filter { it.page == l.page && abs(it.y - l.y) < 40 && amountsIn(it).isEmpty() && !dateRx.containsMatchIn(it.text) }
        fun find(r: Regex) = band.firstNotNullOfOrNull { colX(it, r) }
        val debit = find(Regex("""\b(debit|withdrawals?|dr)\b""", RegexOption.IGNORE_CASE))
        val credit = find(Regex("""\b(credits?|deposits?|cr)\b(?!\s*(card|limit))""", RegexOption.IGNORE_CASE))
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

    private data class RowDate(val date: LocalDate, val end: Int, val count: Int)

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

        // -------- transactions
        val out = mutableListOf<StatementLine>()
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
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val hdr = headerAt(lines, i)
            if (hdr != null) { cols = hdr; pending = null; headerPage = l.page; headerY = l.y; i++; continue }
            val row = leadingDates(l, year)
            if (row == null) {
                // card sections inside the table ("Supplementary Card Number ... 3944")
                cardHeading(l)?.let { section = it }
                // an opening balance helps the balance-change check
                if (Regex("""opening\s+balance|brought\s+forward|previous\s+balance""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) {
                    amountsIn(l).lastOrNull()?.let { lastBalance = if (it.credit) -it.minor else it.minor }
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
            if (desc.length < 4 && pending != null) desc = cleanDescription(pending!! + if (desc.isNotBlank()) " $desc" else "")
            if (desc.isBlank()) desc = block.drop(1).firstOrNull { amountsIn(it).isEmpty() }?.text?.let(::cleanDescription) ?: ""
            // generic bank wording ("Telex Transfer", "IPP Transfer"): the next line says what it was for ("/REF/car installment")
            if (desc.length < 20 || desc.endsWith(":") || Regex("""transfer|remittance|payment|information|IPP|instant""", RegexOption.IGNORE_CASE).containsMatchIn(desc)) {
                block.drop(1).firstOrNull { amountsIn(it).isEmpty() && it.text.trim().length > 3 }?.let { c ->
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
            val c = cols
            if (c != null && !fromBlock && (c.debit != null || c.credit != null || c.amount != null)) {
                val targets = listOfNotNull(c.debit?.let { "debit" to it }, c.credit?.let { "credit" to it }, c.balance?.let { "balance" to it },
                    c.amount?.let { "amount" to it }, c.original?.let { "original" to it })
                for (a in amts) {
                    val (name, _) = targets.minByOrNull { abs(it.second - (a.xEnd - 8f)) } ?: continue
                    when (name) {
                        "debit" -> if (amount == null) { amount = a; credit = a.credit }
                        "credit" -> if (amount == null) { amount = a; credit = !a.debitMark }
                        "amount" -> if (amount == null || c.debit == null) { amount = a; credit = a.credit }
                        "balance" -> balance = if (a.credit) -a.minor else a.minor
                        else -> {}
                    }
                }
                if (amount == null) amount = amts.filter { a -> targets.minByOrNull { abs(it.second - (a.xEnd - 8f)) }?.first != "original" }.lastOrNull() ?: amts.last()
                if (amount != null && amount.credit) credit = true
            } else if (fromBlock) {
                // multi-line transaction: the last amount of the block is the AED total (includes fees)
                amount = amts.last(); credit = amount.credit
            } else {
                val aed = amts.firstOrNull { a -> l.text.substring(0, a.start).trimEnd().endsWith("AED", true) }
                amount = if (amts.size >= 2) (aed?.let { amts.last() } ?: amts.first()) else amts.first()
                credit = amount.credit || amts.last().credit
                if (amts.size >= 2 && aed == null) balance = amts.last().let { if (it.credit) -it.minor else it.minor }
            }
            val a = amount
            if (a == null) { i = j; continue }
            // a rising running balance means money in (when columns didn't already say)
            if (balance != null && lastBalance != null && (c?.credit == null || c.debit == null)) {
                if (balance != lastBalance) credit = balance > lastBalance!!
            }
            if (balance != null) lastBalance = balance
            if (Regex("""PAYMENT\s+RECEIVED|THANK\s+YOU|CASHBACK|REFUND|REVERSAL""", RegexOption.IGNORE_CASE).containsMatchIn(desc) && c?.debit == null) credit = credit || !a.debitMark
            if (a.minor == 0L) { i = j; continue }
            if (firstTxnIndex < 0) firstTxnIndex = i
            out += StatementLine(row.date, desc, a.minor, credit, block.joinToString(" | ") { it.text.trim() }, section)
            i = j
        }

        // -------- key figures
        val header = if (firstTxnIndex > 0) lines.subList(0, firstTxnIndex) else lines
        val stmtDate = byLabel<LocalDate>(lines, STATEMENT_DATE, Kind.DATE, year)?.value
        val dueDate = byLabel<LocalDate>(lines, DUE_DATE, Kind.DATE, year, accept = { d -> stmtDate == null || (d.isAfter(stmtDate) && ChronoUnit.DAYS.between(stmtDate, d) <= 60) })?.value
        val limit = byLabel<Long>(lines, CREDIT_LIMIT, Kind.AMOUNT, year, accept = { it > 0 })?.value
        val avail = byLabel<Long>(lines, AVAILABLE, Kind.AMOUNT, year)?.value
        var total = byLabel<Long>(lines, TOTAL_DUE, Kind.AMOUNT, year)?.value
        var min = byLabel<Long>(lines, MIN_DUE, Kind.AMOUNT, year, accept = { it >= 0 && (total == null || it <= total!! + 100) })?.value
        val previous = byLabel<Long>(lines, PREVIOUS, Kind.AMOUNT, year)?.value

        // inference for layouts whose labels are pictures (only the values are text)
        var sDate = stmtDate
        var dDate = dueDate
        val loneDates = header.mapNotNull { l -> dateRx.matchEntire(l.text.trim())?.let { dateOf(it, year) } }
        if (sDate == null || dDate == null) {
            for (k in 0 until loneDates.size - 1) {
                val a = loneDates[k]; val b = loneDates[k + 1]
                val gap = ChronoUnit.DAYS.between(a, b)
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
        var periodTo: LocalDate? = null
        var periodFrom: LocalDate? = null
        lines.firstOrNull { periodMatch.containsMatchIn(it.text) && dateRx.findAll(it.text).count() >= 2 }?.let { pl ->
            val ds = dateRx.findAll(pl.text).mapNotNull { dateOf(it, year) }.toList()
            periodFrom = ds.firstOrNull(); periodTo = ds.lastOrNull()
        }
        if (sDate == null || (periodTo != null && abs(ChronoUnit.DAYS.between(periodTo, sDate)) > 5 && dDate != null && !sDate!!.isBefore(dDate))) sDate = periodTo

        // card number and bank
        val headText = lines.take(80).joinToString("\n") { it.text }
        val looksAccount = Regex("""account\s*statement|statement\s*of\s*account|account\s*summary""", RegexOption.IGNORE_CASE).containsMatchIn(headText) &&
            !Regex("""minimum\s*(payment|amount)|min\.?\s*amount|credit\s*limit|card\s*limit""", RegexOption.IGNORE_CASE).containsMatchIn(all)
        val accountLast4 = accountNo.find(all)?.groupValues?.get(1)?.filter(Char::isDigit)?.takeLast(4)
        val last4 = if (looksAccount && accountLast4 != null) accountLast4
            else cardMask.find(headText)?.groupValues?.drop(1)?.lastOrNull { it.length == 4 } ?: accountLast4
        val bank = bankNames.maxByOrNull { (_, r) ->
            lines.sumOf { l -> val n = r.findAll(l.text).count(); if (Regex("""PJSC|P\.J\.S\.C|licensed|regulated|www\.|Limited|Bank\s*TRN""", RegexOption.IGNORE_CASE).containsMatchIn(l.text)) n * 4 else n }
        }?.takeIf { (_, r) -> r.containsMatchIn(all) }?.first

        val summary = StatementSummary(
            statementDate = sDate, dueDate = dDate, creditLimitMinor = cLimit, availableLimitMinor = cAvail,
            totalDueMinor = if (looksAccount) null else total, minimumDueMinor = if (looksAccount) null else min,
            cardLast4 = last4, bank = bank, previousBalanceMinor = previous, isAccount = looksAccount,
            closingBalanceMinor = if (looksAccount) byLabel<Long>(lines, rx("""closing\s*(?:book\s*)?balance""", """balance\s*carried\s*forward"""), Kind.AMOUNT, year)?.value else null,
            periodFrom = periodFrom, periodTo = periodTo,
        )

        // -------- totals check: previous + spends − credits should equal the new balance
        val debits = out.filterNot { it.isCredit }.sumOf { it.amountMinor }
        val credits = out.filter { it.isCredit }.sumOf { it.amountMinor }
        var check: String? = null
        var agree: Boolean? = null
        val balances = if (summary.isAccount) listOfNotNull(summary.closingBalanceMinor)
            else listOfNotNull(total, byLabel<Long>(lines, rx("""current\s*balance""", """(?:total\s*)?outstanding\s*balance""", """new\s*balance""", """statement\s*balance""", """total\s*outstanding"""), Kind.AMOUNT, year)?.value)
        if (previous != null && balances.isNotEmpty() && out.isNotEmpty()) {
            val expected = if (summary.isAccount) previous + credits - debits else previous + debits - credits
            val closing = balances.minByOrNull { abs(it - expected) }!!
            agree = abs(expected - closing) <= 100
            check = "Read ${out.size} transactions: ${if (summary.isAccount) "in" else "spends"} ${fmt(if (summary.isAccount) credits else debits)}, " +
                "${if (summary.isAccount) "out" else "credits"} ${fmt(if (summary.isAccount) debits else credits)}. " +
                if (agree) "They add up to the statement's balance." else "Expected balance ${fmt(expected)}, statement shows ${fmt(closing)}: some lines may be missing or instalments are included."
        }
        return StatementAnalysis(summary, out, check, agree)
    }

    private fun fmt(minor: Long) = "AED " + BigDecimal.valueOf(minor, 2).toPlainString()
}
