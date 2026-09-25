package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.TxnType
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

    @Test fun fixed_payment_auto_paid() {
        val txns = listOf(
            FixedSchedule.MonthTxn("FAB ·8001", 245_000, 20L, TxnType.TRANSFER_OUT),
            FixedSchedule.MonthTxn("FAB ·8001", 10_000, null, TxnType.PURCHASE),
        )
        assertTrue(FixedSchedule.autoPaid(250_000, "FAB ·8001", 20L, txns)) // within 5%
        assertFalse(FixedSchedule.autoPaid(250_000, "ENBD ·9940", 20L, txns)) // other card
        assertFalse(FixedSchedule.autoPaid(250_000, "FAB ·8001", 11L, txns)) // other category
        assertFalse(FixedSchedule.autoPaid(300_000, "FAB ·8001", null, txns)) // amount too far
        assertTrue(FixedSchedule.autoPaid(10_000, null, null, txns))
    }

    @Test fun amount_specific_learning() {
        val emi = "Account debit (EMI / direct debit)"
        assertTrue(com.uaefinancial.tracker.parser.CategoryRules.isAmountSpecific(emi, TxnType.PURCHASE))
        assertTrue(com.uaefinancial.tracker.parser.CategoryRules.isAmountSpecific("Transfer to ·2001", TxnType.TRANSFER_OUT))
        assertFalse(com.uaefinancial.tracker.parser.CategoryRules.isAmountSpecific("talabat.com", TxnType.PURCHASE))
        assertEquals("=ACCOUNT DEBIT (EMI / DIRECT DEBIT)#250000", com.uaefinancial.tracker.parser.CategoryRules.learningKey(emi, 250_000, TxnType.PURCHASE))
        assertEquals("TALABAT COM", com.uaefinancial.tracker.parser.CategoryRules.learningKey("talabat.com", 5_000, TxnType.PURCHASE))
    }

    @Test fun recurring_includes_categorised_transfers() {
        fun t(d: LocalDate, type: TxnType, cat: Long?) = InsightTxn(d, type, 250_000, "FAB ·8001", cat, "Transfer to ·2001", "TRANSFER TO", "AED", 250_000)
        val dates = listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))
        val r = Insights.recurring(dates.map { t(it, TxnType.TRANSFER_OUT, 20L) }, today)
        assertEquals(1, r.size)
        assertEquals(20L, r[0].categoryId)
        assertEquals(LocalDate.of(2026, 10, 1), r[0].nextExpected)
        assertTrue(Insights.recurring(dates.map { t(it, TxnType.TRANSFER_OUT, null) }, today).isEmpty())
    }

    @Test fun card_days_from_statement() {
        val st = listOf(
            Triple(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 9, 10)),
            Triple(null, LocalDate.of(2026, 8, 14), LocalDate.of(2026, 7, 20)), // too old
        )
        val d = CardDays.fromStatements(st, today)!!
        assertEquals(9, d.statementDay)
        assertEquals(3, d.dueDay)
        val noDate = CardDays.fromStatements(listOf(Triple(null, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 9, 20))), today)!!
        assertEquals(20, noDate.statementDay)
        assertNull(CardDays.fromStatements(listOf(st[1]), today))
    }

    @Test fun since_statement() {
        val start = SinceStatement.startDate(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        assertEquals(LocalDate.of(2026, 9, 10), start)
        val txns = listOf(
            InsightTxn(LocalDate.of(2026, 9, 9), TxnType.PURCHASE, 5000, "C", null, "a", "a", "AED", 5000),
            InsightTxn(LocalDate.of(2026, 9, 12), TxnType.PURCHASE, 7000, "C", null, "b", "b", "AED", 7000),
            InsightTxn(LocalDate.of(2026, 9, 13), TxnType.REFUND, 1000, "C", null, "c", "c", "AED", 1000),
            InsightTxn(LocalDate.of(2026, 9, 14), TxnType.PAYMENT, 9000, "C", null, "d", "d", "AED", 9000),
        )
        assertEquals(6000L, SinceStatement.spend(txns, start))
    }
}
