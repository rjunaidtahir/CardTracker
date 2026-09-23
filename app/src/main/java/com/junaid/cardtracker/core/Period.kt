package com.junaid.cardtracker.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The period chips: a calendar month (with arrows), rolling windows ending today, everything, or your own range. */
enum class PeriodKind(val chip: String) {
    MONTH("Month"), W1("1W"), M1("1M"), M3("3M"), M6("6M"), M12("12M"), ALL("All"), CUSTOM("Custom"),
}

/**
 * A date range, both ends inclusive. [start]/[end] are null only for [PeriodKind.ALL].
 * Rolling kinds (1W…12M) end today when picked; the arrows move them back by their own length.
 */
data class Period(val kind: PeriodKind, val start: LocalDate?, val end: LocalDate?) {

    val isBounded: Boolean get() = start != null && end != null

    /** Number of days covered (inclusive), or null for All. */
    val days: Long? get() = if (start != null && end != null) ChronoUnit.DAYS.between(start, end) + 1 else null

    fun contains(d: LocalDate): Boolean = (start == null || !d.isBefore(start)) && (end == null || !d.isAfter(end))

    /** The same-length period just before this one (the previous calendar month for MONTH). Null for All. */
    fun previous(): Period? = shift(-1)

    /** Moves the period by its own length. All can't move. */
    fun shift(steps: Int): Period? {
        if (start == null || end == null) return null
        if (kind == PeriodKind.MONTH) {
            val m = YearMonth.from(start).plusMonths(steps.toLong())
            return month(m)
        }
        val len = ChronoUnit.DAYS.between(start, end) + 1
        return copy(start = start.plusDays(len * steps), end = end.plusDays(len * steps))
    }

    /** "September 2026", "17 – 23 Sep 2026", "1 Jul 2026 – 23 Sep 2026", "All time". */
    fun label(): String {
        if (start == null || end == null) return "All time"
        if (kind == PeriodKind.MONTH) return YearMonth.from(start).format(MONTH_FMT)
        return when {
            start == end -> start.format(DAY_FMT)
            start.year == end.year && start.month == end.month -> "${start.dayOfMonth} – ${end.format(DAY_FMT)}"
            start.year == end.year -> "${start.format(DAY_MONTH_FMT)} – ${end.format(DAY_FMT)}"
            else -> "${start.format(DAY_FMT)} – ${end.format(DAY_FMT)}"
        }
    }

    companion object {
        private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
        private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        private val DAY_MONTH_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        fun month(m: YearMonth) = Period(PeriodKind.MONTH, m.atDay(1), m.atEndOfMonth())

        fun all() = Period(PeriodKind.ALL, null, null)

        fun custom(from: LocalDate, to: LocalDate) =
            if (to.isBefore(from)) Period(PeriodKind.CUSTOM, to, from) else Period(PeriodKind.CUSTOM, from, to)

        /** The period for a chip, relative to [today]. CUSTOM needs dates, so it falls back to this month. */
        fun of(kind: PeriodKind, today: LocalDate): Period = when (kind) {
            PeriodKind.MONTH, PeriodKind.CUSTOM -> month(YearMonth.from(today))
            PeriodKind.W1 -> Period(kind, today.minusDays(6), today)
            PeriodKind.M1 -> Period(kind, today.minusMonths(1).plusDays(1), today)
            PeriodKind.M3 -> Period(kind, today.minusMonths(3).plusDays(1), today)
            PeriodKind.M6 -> Period(kind, today.minusMonths(6).plusDays(1), today)
            PeriodKind.M12 -> Period(kind, today.minusMonths(12).plusDays(1), today)
            PeriodKind.ALL -> all()
        }
    }
}

/** How a timeline chart groups days. */
enum class Bucket { DAY, WEEK, MONTH }

data class TimePoint(val start: LocalDate, val amountMinor: Long)

object Timeline {
    /** Day buckets up to a month, weeks up to ~4 months, months beyond that. */
    fun bucketFor(start: LocalDate, end: LocalDate): Bucket {
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return when {
            days <= 31 -> Bucket.DAY
            days <= 120 -> Bucket.WEEK
            else -> Bucket.MONTH
        }
    }

    /**
     * Net spending per bucket from [start] to [end] (inclusive), oldest first, empty buckets included.
     * Week buckets are 7-day blocks counted from [start]; month buckets are calendar months.
     * Uses the same rule as every other total (core.Spending); cards in [excluded] don't count.
     */
    fun of(txns: List<InsightTxn>, excluded: Set<String>, start: LocalDate, end: LocalDate, bucket: Bucket = bucketFor(start, end)): List<TimePoint> {
        if (end.isBefore(start)) return emptyList()
        fun keyOf(d: LocalDate): LocalDate = when (bucket) {
            Bucket.DAY -> d
            Bucket.WEEK -> start.plusDays(ChronoUnit.DAYS.between(start, d) / 7 * 7)
            Bucket.MONTH -> d.withDayOfMonth(1)
        }
        val sums = HashMap<LocalDate, Long>()
        for (t in txns) {
            if (t.date.isBefore(start) || t.date.isAfter(end)) continue
            if (t.cardKey != null && t.cardKey in excluded) continue
            val c = Spending.contributionAedMinor(t.type, t.amountAedMinor, true)
            if (c != 0L) sums.merge(keyOf(t.date), c, Long::plus)
        }
        val out = ArrayList<TimePoint>()
        var k = keyOf(start)
        while (!k.isAfter(end)) {
            out += TimePoint(k, sums[k] ?: 0L)
            k = when (bucket) {
                Bucket.DAY -> k.plusDays(1)
                Bucket.WEEK -> k.plusDays(7)
                Bucket.MONTH -> k.plusMonths(1)
            }
        }
        return out
    }
}
