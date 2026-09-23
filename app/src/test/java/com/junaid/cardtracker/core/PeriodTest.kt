package com.junaid.cardtracker.core

import com.junaid.cardtracker.parser.TxnType
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodTest {
    private val today = LocalDate.of(2026, 9, 23)

    @Test fun chips_relative_to_today() {
        assertEquals(Period(PeriodKind.W1, LocalDate.of(2026, 9, 17), today), Period.of(PeriodKind.W1, today))
        assertEquals(LocalDate.of(2026, 8, 24), Period.of(PeriodKind.M1, today).start)
        assertEquals(LocalDate.of(2026, 6, 24), Period.of(PeriodKind.M3, today).start)
        assertEquals(LocalDate.of(2025, 9, 24), Period.of(PeriodKind.M12, today).start)
        assertEquals(Period.month(YearMonth.of(2026, 9)), Period.of(PeriodKind.MONTH, today))
        assertNull(Period.of(PeriodKind.ALL, today).start)
        assertEquals(7L, Period.of(PeriodKind.W1, today).days)
    }

    @Test fun previous_and_shift() {
        val sep = Period.month(YearMonth.of(2026, 9))
        assertEquals(Period.month(YearMonth.of(2026, 8)), sep.previous())
        assertEquals(LocalDate.of(2026, 2, 28), Period.month(YearMonth.of(2026, 3)).previous()!!.end)
        val w = Period.of(PeriodKind.W1, today)
        assertEquals(Period(PeriodKind.W1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 16)), w.previous())
        val c = Period.custom(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1)) // reversed input is fixed
        assertEquals(LocalDate.of(2026, 9, 1), c.start)
        assertEquals(Period(PeriodKind.CUSTOM, LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 31)), c.previous())
        assertNull(Period.all().previous())
        assertTrue(Period.all().contains(LocalDate.of(1999, 1, 1)))
        assertTrue(w.contains(today) && !w.contains(today.plusDays(1)))
    }

    @Test fun labels() {
        assertEquals("September 2026", Period.month(YearMonth.of(2026, 9)).label())
        assertEquals("17 – 23 Sep 2026", Period.of(PeriodKind.W1, today).label())
        assertEquals("24 Jun – 23 Sep 2026", Period.of(PeriodKind.M3, today).label())
        assertEquals("24 Sep 2025 – 23 Sep 2026", Period.of(PeriodKind.M12, today).label())
        assertEquals("All time", Period.all().label())
    }

    private fun t(d: LocalDate, aed: Long, type: TxnType = TxnType.PURCHASE, card: String? = "C") =
        InsightTxn(d, type, aed, card, null, "M", "M", "AED", aed)

    @Test fun timeline_buckets() {
        assertEquals(Bucket.DAY, Timeline.bucketFor(today.minusDays(30), today))
        assertEquals(Bucket.WEEK, Timeline.bucketFor(today.minusDays(90), today))
        assertEquals(Bucket.MONTH, Timeline.bucketFor(today.minusDays(200), today))

        val txns = listOf(
            t(LocalDate.of(2026, 9, 17), 1000),
            t(LocalDate.of(2026, 9, 17), 500),
            t(LocalDate.of(2026, 9, 18), 200, TxnType.REFUND),
            t(LocalDate.of(2026, 9, 19), 9999, TxnType.PAYMENT),
            t(LocalDate.of(2026, 9, 20), 700, card = "OFF"),
            t(LocalDate.of(2026, 9, 1), 300), // outside
        )
        val days = Timeline.of(txns, setOf("OFF"), LocalDate.of(2026, 9, 17), today)
        assertEquals(7, days.size)
        assertEquals(listOf(1500L, -200L, 0L, 0L, 0L, 0L, 0L), days.map { it.amountMinor })

        val weeks = Timeline.of(txns, emptySet(), LocalDate.of(2026, 9, 1), today, Bucket.WEEK)
        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 22)), weeks.map { it.start })
        assertEquals(listOf(300L, 0L, 2000L, 0L), weeks.map { it.amountMinor })

        val months = Timeline.of(txns, emptySet(), LocalDate.of(2026, 7, 15), today, Bucket.MONTH)
        assertEquals(listOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)), months.map { it.start })
        assertEquals(2300L, months.last().amountMinor)
    }
}
