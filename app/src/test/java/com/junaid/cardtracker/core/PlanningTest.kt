package com.junaid.cardtracker.core

import com.junaid.cardtracker.parser.TxnType
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningTest {
    private val today = LocalDate.of(2026, 9, 23)

    @Test fun budgets() {
        val s = Budgets.status(mapOf(1L to 100_000L, 2L to 50_000L, 3L to 0L), mapOf(1L to 85_000L, 2L to 60_000L, null to 999L))
        assertEquals(listOf(2L, 1L), s.map { it.categoryId })
        assertEquals(120, s[0].percent)
        assertEquals(-10_000L, s[0].remainingMinor)
        assertEquals(85, s[1].percent)
        assertEquals(100, Budgets.threshold(120))
        assertEquals(80, Budgets.threshold(85))
        assertNull(Budgets.threshold(79))
        assertEquals(0, Budgets.status(mapOf(4L to 1000L), emptyMap()).single().spentMinor)
    }

    @Test fun fixed_payments() {
        assertEquals(LocalDate.of(2026, 2, 28), FixedSchedule.dueDate(31, YearMonth.of(2026, 2)))
        assertEquals(LocalDate.of(2026, 9, 25), FixedSchedule.nextDue(25, today, null))
        assertEquals(LocalDate.of(2026, 10, 25), FixedSchedule.nextDue(25, today, YearMonth.of(2026, 9)))
        assertEquals(LocalDate.of(2026, 9, 25), FixedSchedule.nextDue(25, today, YearMonth.of(2026, 8)))
        assertEquals(-3L, FixedSchedule.daysLeft(FixedSchedule.nextDue(20, today, null), today))
        assertEquals(1L, FixedSchedule.reminderOffsetToday(24, today, null))
        assertEquals(0L, FixedSchedule.reminderOffsetToday(23, today, null))
        assertNull(FixedSchedule.reminderOffsetToday(24, today, YearMonth.of(2026, 9)))
        assertNull(FixedSchedule.reminderOffsetToday(28, today, null))
    }

    @Test fun alert_rules() {
        assertTrue(AlertRules.isBigSpend(TxnType.PURCHASE, 200_000, 100_000))
        assertFalse(AlertRules.isBigSpend(TxnType.REFUND, 200_000, 100_000))
        assertFalse(AlertRules.isBigSpend(TxnType.PURCHASE, 200_000, 0))
        assertTrue(AlertRules.isLowBalance(50_000, 100_000))
        assertFalse(AlertRules.isLowBalance(null, 100_000))
        val now = 1_000_000_000_000L
        assertTrue(AlertRules.isFresh(now - 3_600_000, now))
        assertFalse(AlertRules.isFresh(now - 2 * 86_400_000L, now))
    }
}
