package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.TxnType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Minimal transaction view for insight calculations (no Android types). */
data class InsightTxn(
    val date: LocalDate,
    val type: TxnType,
    val amountAedMinor: Long?,
    val cardKey: String?,
    val categoryId: Long?,
    val merchant: String,
    val merchantKey: String,
    val currency: String,
    val amountMinor: Long,
)

data class Slice(val key: String, val amountMinor: Long)
data class MonthTotal(val month: YearMonth, val amountMinor: Long)
data class CurrencyTotal(val currency: String, val originalMinor: Long, val aedMinor: Long, val count: Int)

data class RecurringPayment(
    val merchant: String,
    val merchantKey: String,
    val averageMinor: Long,
    val occurrences: Int,
    val lastDate: LocalDate,
    val nextExpected: LocalDate,
    val cardKey: String?,
    val categoryId: Long? = null,
)

object Insights {
    private fun counted(t: InsightTxn, excluded: Set<String>) = t.cardKey == null || t.cardKey !in excluded

    /** Net spending per category (purchases minus refunds), largest first. Zero/negative categories dropped. */
    fun byCategory(txns: List<InsightTxn>, excluded: Set<String>): List<Pair<Long?, Long>> =
        txns.filter { counted(it, excluded) }
            .groupBy { it.categoryId }
            .mapValues { (_, l) -> l.sumOf { Spending.contributionAedMinor(it.type, it.amountAedMinor, true) } }
            .filter { it.value > 0 }
            .toList()
            .sortedByDescending { it.second }

    /** Net spending per card. */
    fun byCard(txns: List<InsightTxn>, excluded: Set<String>): List<Slice> =
        txns.filter { counted(it, excluded) }
            .groupBy { it.cardKey ?: "Typed entries" }
            .map { (k, l) -> Slice(k, l.sumOf { Spending.contributionAedMinor(it.type, it.amountAedMinor, true) }) }
            .filter { it.amountMinor > 0 }
            .sortedByDescending { it.amountMinor }

    /** Spending for each of the [months] months ending at [end] (oldest first, zero months included). */
    fun byMonth(txns: List<InsightTxn>, excluded: Set<String>, end: YearMonth, months: Int = 12): List<MonthTotal> {
        val start = end.minusMonths((months - 1).toLong())
        val sums = txns.filter { counted(it, excluded) }
            .groupBy { YearMonth.from(it.date) }
            .mapValues { (_, l) -> l.sumOf { Spending.contributionAedMinor(it.type, it.amountAedMinor, true) } }
        return (0 until months).map { i -> start.plusMonths(i.toLong()).let { MonthTotal(it, sums[it] ?: 0L) } }
    }

    /** Purchases in each currency other than [home]: original total and the [home] equivalent. */
    fun byCurrency(txns: List<InsightTxn>, excluded: Set<String>, home: String = com.uaefinancial.tracker.parser.SmsParser.homeCurrency): List<CurrencyTotal> =
        txns.filter { counted(it, excluded) && it.type == TxnType.PURCHASE && it.currency != home }
            .groupBy { it.currency }
            .map { (c, l) -> CurrencyTotal(c, l.sumOf { it.amountMinor }, l.sumOf { it.amountAedMinor ?: 0L }, l.size) }
            .sortedByDescending { it.aedMinor }

    /**
     * Recurring payments: the same merchant (or the same account-debit amount) at least 3 times,
     * roughly monthly (25-35 days apart), amounts within 15% of their median.
     */
    fun recurring(txns: List<InsightTxn>, today: LocalDate): List<RecurringPayment> {
        // Purchases, plus transfers you have given a category (e.g. a car EMI paid by transfer).
        val purchases = txns.filter {
            (it.type == TxnType.PURCHASE || (it.type == TxnType.TRANSFER_OUT && it.categoryId != null)) && (it.amountAedMinor ?: 0) > 0
        }
        // Generic account debits (EMIs) and transfers have no real merchant name, so group those by amount too.
        val groups = purchases.groupBy { t ->
            if (t.type == TxnType.TRANSFER_OUT || t.merchantKey.startsWith("ACCOUNT DEBIT")) "${t.type}|${t.merchantKey}|${t.cardKey}|${t.amountMinor}"
            else "${t.merchantKey}|${t.cardKey}"
        }
        return groups.values.mapNotNull { g ->
            if (g.size < 3) return@mapNotNull null
            val sorted = g.sortedBy { it.date }
            // Collapse same-day duplicates (e.g. two SMS for one charge).
            val byDay = sorted.distinctBy { it.date }
            if (byDay.size < 3) return@mapNotNull null
            val amounts = byDay.map { it.amountAedMinor ?: 0L }.sorted()
            val median = amounts[amounts.size / 2]
            if (median <= 0 || amounts.any { abs(it - median) * 100 > median * 15 }) return@mapNotNull null
            val gaps = byDay.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
            val monthlyGaps = gaps.count { it in 25..35 }
            if (monthlyGaps * 10 < gaps.size * 7) return@mapNotNull null // at least 70% of gaps look monthly
            val last = byDay.last()
            var next = last.date.plusMonths(1)
            while (next.isBefore(today)) next = next.plusMonths(1)
            // Stale if nothing for ~2.5 months.
            if (ChronoUnit.DAYS.between(last.date, today) > 75) return@mapNotNull null
            RecurringPayment(last.merchant, last.merchantKey, amounts.average().toLong(), byDay.size, last.date, next, last.cardKey, byDay.lastOrNull { it.categoryId != null }?.categoryId)
        }.sortedByDescending { it.averageMinor }
    }
}
