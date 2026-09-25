package com.uaefinancial.tracker.core

import kotlin.math.abs

/** One transaction line read from a bank statement PDF. Amounts in AED fils. */
data class StatementLine(
    val date: CalendarDate,
    val description: String,
    val amountMinor: Long,
    /** Money in / payment / refund (CR), as opposed to a spend or debit. */
    val isCredit: Boolean,
    val raw: String,
    /** Last 4 of the card this line belongs to, when the statement has several (supplementary cards). */
    val cardLast4: String? = null,
)

/** Key figures printed on the statement (any may be missing if the layout isn't recognised). */
data class StatementSummary(
    val statementDate: CalendarDate? = null,
    val dueDate: CalendarDate? = null,
    val creditLimitMinor: Long? = null,
    val availableLimitMinor: Long? = null,
    /** Total amount due / amount to pay to avoid finance charges / statement balance. */
    val totalDueMinor: Long? = null,
    val minimumDueMinor: Long? = null,
    val cardLast4: String? = null,
    val bank: String? = null,
    val previousBalanceMinor: Long? = null,
    /** A bank account statement (not a credit card). */
    val isAccount: Boolean = false,
    /** Accounts: balance at the end of the period. */
    val closingBalanceMinor: Long? = null,
    val periodFrom: CalendarDate? = null,
    val periodTo: CalendarDate? = null,
) {
    val isEmpty: Boolean get() = statementDate == null && dueDate == null && creditLimitMinor == null && totalDueMinor == null && minimumDueMinor == null
}

/** A transaction already in the app, reduced to what matching needs. */
data class AppTxnRef(val id: Long, val date: CalendarDate, val amountMinor: Long, val isCredit: Boolean, val estimated: Boolean, val label: String)

data class Reconciliation(
    val matched: List<Pair<StatementLine, AppTxnRef>>,
    /** On the statement but not in the app: offered for adding. */
    val missing: List<StatementLine>,
    /** In the app (within the statement's dates) but not on the statement. */
    val extra: List<AppTxnRef>,
    val from: CalendarDate?,
    val to: CalendarDate?,
)

/**
 * Reads statement text (as extracted from the PDF, one line per row) and compares it with the app.
 * Works on the common layout "date [posting date] description amount [CR] [balance]" used by UAE banks.
 */
object StatementImport {
    private const val MON = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*"
    private val datePatterns = listOf(
        Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b"""),               // 05/09/2026, 05-09-26
        Regex("""^(\d{1,2})[\s\-/]($MON)[\s\-/,]*(\d{2,4})\b""", RegexOption.IGNORE_CASE), // 05 Sep 2026, 05-SEP-26
        Regex("""^(\d{1,2})[\s\-/]($MON)\b""", RegexOption.IGNORE_CASE),      // 05 Sep (year from context)
        Regex("""^($MON)\s+(\d{1,2}),?\s+(\d{4})\b""", RegexOption.IGNORE_CASE), // Sep 05, 2026
    )
    private val amount = Regex("""(?<![\d.])-?\d{1,3}(?:,\d{3})*(?:\.\d{2})|(?<![\d.,])-?\d+\.\d{2}(?![\d])""")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val creditWords = Regex("""\b(CR|Cr|CREDIT|Credit|PAYMENT RECEIVED|Payment Received|THANK YOU|REFUND|Refund|REVERSAL|CASHBACK|Cashback)\b""")
    private val skipWords = Regex(
        """\b(opening balance|closing balance|previous balance|balance brought forward|balance carried forward|total|minimum (amount )?due|statement date|payment due date|credit limit|available)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun monthOf(s: String) = months.indexOf(s.take(3).lowercase()).takeIf { it >= 0 }?.plus(1)
    private fun year(raw: String) = raw.toInt().let { if (it < 100) 2000 + it else it }

    /** The date at the start of [line] and the rest of the line, or null. */
    fun leadingDate(line: String, fallbackYear: Int): Pair<CalendarDate, String>? {
        val t = line.trim()
        for ((i, r) in datePatterns.withIndex()) {
            val m = r.find(t) ?: continue
            val g = m.groupValues
            val d = runCatching {
                when (i) {
                    0 -> CalendarDate.of(year(g[3]), g[2].toInt(), g[1].toInt())
                    1 -> CalendarDate.of(year(g[3]), monthOf(g[2])!!, g[1].toInt())
                    2 -> CalendarDate.of(fallbackYear, monthOf(g[2])!!, g[1].toInt())
                    else -> CalendarDate.of(g[3].toInt(), monthOf(g[1])!!, g[2].toInt())
                }
            }.getOrNull() ?: continue
            return d to t.substring(m.range.last + 1).trim()
        }
        return null
    }

    private fun minor(s: String): Long = Decimal(s.replace(",", "")).movePointRight(2).setScale(0).unscaled

    /**
     * Transaction lines from statement text. A second date right after the first (posting date) is skipped.
     * With two or more amounts on a line the first is the transaction and the last the running balance;
     * then a rising balance means money in.
     */
    /** Transaction lines from plain statement text (see StatementReader for the layout-aware reader). */
    fun parse(text: String, fallbackYear: Int): List<StatementLine> =
        StatementReader.analyze(StatementReader.linesFromText(text), fallbackYear).lines

    /** Key figures from plain statement text. */
    /** [year] is used for dates printed without one (normally this year). */
    fun summary(text: String, year: Int): StatementSummary =
        StatementReader.analyze(StatementReader.linesFromText(text), year).summary

    /**
     * Matches each statement line to one app transaction: same direction, same amount (within 3% when the
     * app's AED figure is an exchange-rate estimate), within [days] days; nearest date wins.
     */
    fun reconcile(lines: List<StatementLine>, app: List<AppTxnRef>, days: Long = 3): Reconciliation {
        if (lines.isEmpty()) return Reconciliation(emptyList(), emptyList(), emptyList(), null, null)
        val from = lines.minOf { it.date }
        val to = lines.maxOf { it.date }
        val pool = app.filter { !it.date.isBefore(from.minusDays(days)) && !it.date.isAfter(to.plusDays(days)) }.toMutableList()
        val matched = mutableListOf<Pair<StatementLine, AppTxnRef>>()
        val missing = mutableListOf<StatementLine>()
        for (l in lines.sortedBy { it.date }) {
            val best = pool.filter { a ->
                a.isCredit == l.isCredit &&
                    abs(CalendarDate.daysBetween(a.date, l.date)) <= days &&
                    (if (a.estimated) abs(a.amountMinor - l.amountMinor) * 100 <= l.amountMinor * 3 else a.amountMinor == l.amountMinor)
            }.minByOrNull { abs(CalendarDate.daysBetween(it.date, l.date)) * 1_000_000 + abs(it.amountMinor - l.amountMinor) }
            if (best != null) { matched += l to best; pool.remove(best) } else missing += l
        }
        val extra = pool.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        return Reconciliation(matched, missing, extra, from, to)
    }
}
