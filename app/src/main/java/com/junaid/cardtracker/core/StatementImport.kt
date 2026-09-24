package com.junaid.cardtracker.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One transaction line read from a bank statement PDF. Amounts in AED fils. */
data class StatementLine(
    val date: LocalDate,
    val description: String,
    val amountMinor: Long,
    /** Money in / payment / refund (CR), as opposed to a spend or debit. */
    val isCredit: Boolean,
    val raw: String,
)

/** Key figures printed on the statement (any may be missing if the layout isn't recognised). */
data class StatementSummary(
    val statementDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val creditLimitMinor: Long? = null,
    val availableLimitMinor: Long? = null,
    /** Total amount due / amount to pay to avoid finance charges / statement balance. */
    val totalDueMinor: Long? = null,
    val minimumDueMinor: Long? = null,
    val cardLast4: String? = null,
    val bank: String? = null,
) {
    val isEmpty: Boolean get() = statementDate == null && dueDate == null && creditLimitMinor == null && totalDueMinor == null && minimumDueMinor == null
}

/** A transaction already in the app, reduced to what matching needs. */
data class AppTxnRef(val id: Long, val date: LocalDate, val amountMinor: Long, val isCredit: Boolean, val estimated: Boolean, val label: String)

data class Reconciliation(
    val matched: List<Pair<StatementLine, AppTxnRef>>,
    /** On the statement but not in the app: offered for adding. */
    val missing: List<StatementLine>,
    /** In the app (within the statement's dates) but not on the statement. */
    val extra: List<AppTxnRef>,
    val from: LocalDate?,
    val to: LocalDate?,
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
    fun leadingDate(line: String, fallbackYear: Int): Pair<LocalDate, String>? {
        val t = line.trim()
        for ((i, r) in datePatterns.withIndex()) {
            val m = r.find(t) ?: continue
            val g = m.groupValues
            val d = runCatching {
                when (i) {
                    0 -> LocalDate.of(year(g[3]), g[2].toInt(), g[1].toInt())
                    1 -> LocalDate.of(year(g[3]), monthOf(g[2])!!, g[1].toInt())
                    2 -> LocalDate.of(fallbackYear, monthOf(g[2])!!, g[1].toInt())
                    else -> LocalDate.of(g[3].toInt(), monthOf(g[1])!!, g[2].toInt())
                }
            }.getOrNull() ?: continue
            return d to t.substring(m.range.last + 1).trim()
        }
        return null
    }

    private fun minor(s: String): Long = BigDecimal(s.replace(",", "")).movePointRight(2).toLong()

    /**
     * Transaction lines from statement text. A second date right after the first (posting date) is skipped.
     * With two or more amounts on a line the first is the transaction and the last the running balance;
     * then a rising balance means money in.
     */
    fun parse(text: String, fallbackYear: Int): List<StatementLine> {
        val out = mutableListOf<StatementLine>()
        var lastBalance: Long? = null
        for (rawLine in text.lines()) {
            val line = rawLine.replace(' ', ' ').trim()
            if (line.length < 8) continue
            val (date, rest0) = leadingDate(line, fallbackYear) ?: continue
            if (skipWords.containsMatchIn(rest0)) {
                // Keep track of an opening balance for the sign heuristic.
                amount.findAll(rest0).lastOrNull()?.let { lastBalance = minor(it.value) }
                continue
            }
            val rest = leadingDate(rest0, fallbackYear)?.second ?: rest0
            val amounts = amount.findAll(rest).toList()
            if (amounts.isEmpty()) continue
            // Prefer an amount written right after "AED" (foreign spends often show both currencies).
            val aedIdx = amounts.indexOfFirst { a -> rest.substring(0, a.range.first).trimEnd().endsWith("AED", ignoreCase = true) }
            val txnMatch = if (aedIdx >= 0) amounts[aedIdx] else amounts.first()
            val value = minor(txnMatch.value)
            val description = rest.substring(0, amounts.first().range.first)
                .replace(Regex("""\b(AED|USD|EUR|GBP|SAR)\s*$"""), "")
                .replace(Regex("""\s{2,}"""), " ").trim().trimEnd('-', ',', ':')
            if (description.isBlank() || value == 0L) continue
            val after = rest.substring(txnMatch.range.last + 1).trim()
            var credit = value < 0 || after.startsWith("CR", ignoreCase = true) || after.startsWith("+") || creditWords.containsMatchIn(description)
            if (amounts.size >= 2 && aedIdx < 0) {
                val balance = minor(amounts.last().value)
                lastBalance?.let { prev -> if (balance != prev) credit = balance > prev }
                lastBalance = balance
            }
            out += StatementLine(date, description, abs(value), credit, line)
        }
        return out
    }

    // ------------------------------------------------------------ key figures

    private const val GAP = """[^\d\n]{0,40}?"""
    private val AMT_T = """(-?\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|-?\d+(?:\.\d{1,2})?)"""
    private val dateText = """(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{1,2}[\s\-/]$MON[\s\-/,]*\d{2,4}|$MON\s+\d{1,2},?\s+\d{4})"""

    private fun findAmount(text: String, vararg labels: String): Long? {
        for (l in labels) {
            val m = Regex("""(?:$l)$GAP(?:AED\s*)?$AMT_T\s*(CR|Cr)?""", RegexOption.IGNORE_CASE).find(text) ?: continue
            val v = runCatching { minor(m.groupValues[1]) }.getOrNull() ?: continue
            return if (m.groupValues[2].isNotEmpty()) -kotlin.math.abs(v) else v
        }
        return null
    }

    private fun findDate(text: String, vararg labels: String): LocalDate? {
        for (l in labels) {
            val m = Regex("""(?:$l)[^\n\d]{0,30}?$dateText""", RegexOption.IGNORE_CASE).find(text) ?: continue
            leadingDate(m.groupValues[1], LocalDate.now().year)?.first?.let { return it }
        }
        return null
    }

    private val banks = listOf(
        "Emirates Islamic", "Emirates NBD", "First Abu Dhabi Bank", "FAB", "ADCB", "Abu Dhabi Commercial", "Mashreq", "HSBC",
        "Al Hilal", "RAKBANK", "Dubai Islamic", "ADIB", "Citibank", "Standard Chartered", "Dubai First", "CBD", "Commercial Bank of Dubai",
        "NBF", "Ajman Bank", "Liv",
    )

    /**
     * Statement date, due date, credit limit, amount due and minimum due from the statement text, where printed
     * as "Label ... value" on one line (the usual UAE layouts).
     */
    fun summary(text: String): StatementSummary {
        val t = text.replace('\u00A0', ' ')
        val last4 = Regex("""(?:card\s*(?:number|no\.?)?|ending(?:\s+with)?)[:\s]*(?:[X*x\d]{4}[\s-]*){0,3}[X*x]{2,}[\s-]*(\d{4})\b|[X*x]{4,}[\s-]*(\d{4})\b""", RegexOption.IGNORE_CASE)
            .find(t)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.ifEmpty { null }
        return StatementSummary(
            statementDate = findDate(t, "Statement Date", "Statement Period Ending", "Statement generated on"),
            dueDate = findDate(t, "Payment Due Date", "Due Date", "Pay by", "Payment Date"),
            creditLimitMinor = findAmount(t, "Total Credit Limit", "Credit Limit", "Card Limit"),
            availableLimitMinor = findAmount(t, "Available Credit Limit", "Available Credit", "Available Limit"),
            totalDueMinor = findAmount(
                t, "amount due to avoid financ(?:e|ial) charges", "Total Amount Due", "Total Due", "Total Outstanding",
                "Statement Balance", "Closing Balance", "New Balance", "Total Payment Due",
            ),
            minimumDueMinor = findAmount(t, "Minimum Amount Due", "Minimum Payment Due", "Minimum Due", "Min\\.? Amount Due", "Minimum Payment"),
            cardLast4 = last4,
            bank = banks.firstOrNull { t.contains(it, ignoreCase = true) }?.let { if (it == "First Abu Dhabi Bank") "FAB" else it },
        )
    }

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
                    abs(ChronoUnit.DAYS.between(a.date, l.date)) <= days &&
                    (if (a.estimated) abs(a.amountMinor - l.amountMinor) * 100 <= l.amountMinor * 3 else a.amountMinor == l.amountMinor)
            }.minByOrNull { abs(ChronoUnit.DAYS.between(it.date, l.date)) * 1_000_000 + abs(it.amountMinor - l.amountMinor) }
            if (best != null) { matched += l to best; pool.remove(best) } else missing += l
        }
        val extra = pool.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        return Reconciliation(matched, missing, extra, from, to)
    }
}
