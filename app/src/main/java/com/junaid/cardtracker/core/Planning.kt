package com.junaid.cardtracker.core

import com.junaid.cardtracker.parser.TxnType
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** One category's budget for a calendar month. */
data class BudgetStatus(val categoryId: Long, val limitMinor: Long, val spentMinor: Long) {
    val percent: Int get() = if (limitMinor <= 0) 0 else (spentMinor * 100 / limitMinor).toInt()
    val remainingMinor: Long get() = limitMinor - spentMinor
}

object Budgets {
    /** Status of every budget with a limit, fullest first. [spentByCategory] comes from Insights.byCategory. */
    fun status(limits: Map<Long, Long>, spentByCategory: Map<Long?, Long>): List<BudgetStatus> =
        limits.filter { it.value > 0 }
            .map { (id, limit) -> BudgetStatus(id, limit, (spentByCategory[id] ?: 0L).coerceAtLeast(0L)) }
            .sortedWith(compareByDescending<BudgetStatus> { it.percent }.thenBy { it.categoryId })

    /** The highest alert level reached: 100 (over budget), 80 (close) or null. */
    fun threshold(percent: Int): Int? = when {
        percent >= 100 -> 100
        percent >= 80 -> 80
        else -> null
    }
}

/** Fixed monthly payments you add by hand (rent, school fees, loans without SMS). */
object FixedSchedule {
    /** The payment day in a given month; day 31 becomes the last day of shorter months. */
    fun dueDate(dayOfMonth: Int, month: YearMonth): LocalDate = month.atDay(dayOfMonth.coerceIn(1, month.lengthOfMonth()))

    fun paidThisMonth(lastPaid: YearMonth?, today: LocalDate): Boolean = lastPaid != null && !lastPaid.isBefore(YearMonth.from(today))

    /** This month's date, or next month's once this month is marked paid. */
    fun nextDue(dayOfMonth: Int, today: LocalDate, lastPaid: YearMonth?): LocalDate {
        val m = YearMonth.from(today)
        return if (paidThisMonth(lastPaid, today)) dueDate(dayOfMonth, m.plusMonths(1)) else dueDate(dayOfMonth, m)
    }

    /** Days until [due] (negative = overdue). */
    fun daysLeft(due: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, due)

    /** Reminder offset that fires today (3, 1 or 0 days before), or null. Nothing once paid. */
    fun reminderOffsetToday(dayOfMonth: Int, today: LocalDate, lastPaid: YearMonth?): Long? {
        if (paidThisMonth(lastPaid, today)) return null
        val left = daysLeft(dueDate(dayOfMonth, YearMonth.from(today)), today)
        return Reminders.OFFSETS.firstOrNull { it == left }
    }
}

/** When a new transaction deserves a notification. A threshold of 0 means "off". */
object AlertRules {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun isBigSpend(type: TxnType, amountAedMinor: Long?, thresholdMinor: Long): Boolean =
        thresholdMinor > 0 && type == TxnType.PURCHASE && amountAedMinor != null && amountAedMinor >= thresholdMinor

    fun isLowBalance(balanceMinor: Long?, thresholdMinor: Long): Boolean =
        thresholdMinor > 0 && balanceMinor != null && balanceMinor < thresholdMinor

    /** Only alert for recent transactions, so the first Sync of an old inbox doesn't flood you. */
    fun isFresh(timestamp: Long, now: Long): Boolean = timestamp in (now - DAY_MS)..(now + 60 * 60 * 1000L)
}
