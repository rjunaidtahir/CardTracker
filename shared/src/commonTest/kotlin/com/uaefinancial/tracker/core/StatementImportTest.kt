package com.uaefinancial.tracker.core

import kotlin.test.Test
import com.uaefinancial.tracker.core.CalendarDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatementImportTest {
    @Test fun card_statement_lines() {
        val text = """
            Statement Date 09/09/2026
            Previous Balance 1,200.00
            12/08/2026 13/08/2026 TALABAT.COM DUBAI AE 90.90
            15-AUG-26 ADNOC 532 DUBAI 150.00
            20 Aug 2026 PAYMENT RECEIVED - THANK YOU 1,000.00 CR
            22/08/2026 AGODA.COM LONDON GB USD 265.00 AED 980.55
            25 Aug CAREEM RIDE 23.50
            Total 2,345.00
        """.trimIndent()
        val l = StatementImport.parse(text, 2026)
        assertEquals(5, l.size)
        assertEquals(CalendarDate.of(2026, 8, 12), l[0].date)
        assertEquals("TALABAT.COM DUBAI", l[0].description)
        assertEquals(9090L, l[0].amountMinor)
        assertFalse(l[0].isCredit)
        assertEquals(15000L, l[1].amountMinor)
        assertTrue(l[2].isCredit)
        assertEquals(100000L, l[2].amountMinor)
        assertEquals(98055L, l[3].amountMinor)
        assertEquals(CalendarDate.of(2026, 8, 25), l[4].date)
    }

    @Test fun account_statement_uses_balance_for_direction() {
        val text = """
            01/09/2026 Opening balance 10,000.00
            01/09/2026 CAR LOAN EMI 2,450.00 7,550.00
            05/09/2026 SALARY 20,000.00 27,550.00
        """.trimIndent()
        val l = StatementImport.parse(text, 2026)
        assertEquals(2, l.size)
        assertFalse(l[0].isCredit)
        assertEquals(245000L, l[0].amountMinor)
        assertTrue(l[1].isCredit)
    }

    @Test fun reconcile() {
        val d = CalendarDate.of(2026, 8, 12)
        val lines = listOf(
            StatementLine(d, "TALABAT", 9090, false, ""),
            StatementLine(d.plusDays(3), "ADNOC", 15000, false, ""),
            StatementLine(d.plusDays(10), "AGODA", 98055, false, ""),
        )
        val app = listOf(
            AppTxnRef(1, d.plusDays(1), 9090, false, false, "talabat"),
            AppTxnRef(2, d.plusDays(10), 97500, false, true, "agoda (est.)"),
            AppTxnRef(3, d.plusDays(5), 5000, false, false, "coffee"),
            AppTxnRef(4, d.minusDays(30), 15000, false, false, "old"),
        )
        val r = StatementImport.reconcile(lines, app)
        assertEquals(listOf(1L, 2L), r.matched.map { it.second.id })
        assertEquals(listOf("ADNOC"), r.missing.map { it.description })
        assertEquals(listOf(3L), r.extra.map { it.id })
    }

    @Test fun app_only_list_stays_inside_the_statement_period_even_with_an_old_line_on_it() {
        // Statement dated 10 Nov covers 11 Oct - 10 Nov, but an instalment-plan line on it is dated 15 Jun.
        val stmt = CalendarDate.of(2026, 11, 10)
        val lines = listOf(
            StatementLine(CalendarDate.of(2026, 6, 15), "INSTALLMENT PLAN EMI", 104158, false, ""),
            StatementLine(CalendarDate.of(2026, 10, 20), "LULU", 10745, false, ""),
        )
        val app = listOf(
            AppTxnRef(1, CalendarDate.of(2026, 10, 20), 10745, false, false, "lulu"),            // matched
            AppTxnRef(2, CalendarDate.of(2026, 10, 25), 4725, false, false, "in period, not on statement"),
            AppTxnRef(3, CalendarDate.of(2026, 10, 3), 3240, false, false, "previous statement"),
            AppTxnRef(4, CalendarDate.of(2026, 9, 18), 2500, false, false, "two statements ago"),
            AppTxnRef(5, CalendarDate.of(2026, 11, 15), 900, false, false, "after the statement date"),
        )
        val period = StatementImport.periodOf(lines, StatementSummary(statementDate = stmt), CalendarDate.of(2026, 10, 10))!!
        assertEquals(CalendarDate.of(2026, 10, 11), period.first)
        assertEquals(stmt, period.second)
        val r = StatementImport.reconcile(lines, app, periodFrom = period.first, periodTo = period.second)
        assertEquals(listOf(2L), r.extra.map { it.id })
        assertEquals(listOf(1L), r.matched.map { it.second.id })
        // With no previous statement and no printed period, the lines within 45 days of the end decide, not the June one.
        val p2 = StatementImport.periodOf(lines, StatementSummary(statementDate = stmt), null)!!
        assertEquals(CalendarDate.of(2026, 10, 20), p2.first)
    }

    @Test fun summary_figures() {
        val text = """
            Emirates Islamic Credit Card Statement
            Card Number 4567 XXXX XXXX 6901
            Statement Date: 09/09/2026        Payment Due Date: 04 Oct 2026
            Total Credit Limit AED 25,000.00   Available Credit Limit AED 18,420.50
            Total Amount Due AED 6,579.50
            Minimum Amount Due AED 329.00
        """.trimIndent()
        val s = StatementImport.summary(text, 2026)
        assertEquals(CalendarDate.of(2026, 9, 9), s.statementDate)
        assertEquals(CalendarDate.of(2026, 10, 4), s.dueDate)
        assertEquals(2_500_000L, s.creditLimitMinor)
        assertEquals(1_842_050L, s.availableLimitMinor)
        assertEquals(657_950L, s.totalDueMinor)
        assertEquals(32_900L, s.minimumDueMinor)
        assertEquals("6901", s.cardLast4)
        assertEquals("Emirates Islamic", s.bank)

        val fab = StatementImport.summary("Amount due to avoid financial charges: 3,734.00\nMinimum Due 186.70\nDue Date 06-12-2023", 2026)
        assertEquals(373_400L, fab.totalDueMinor)
        assertEquals(CalendarDate.of(2023, 12, 6), fab.dueDate)
        assertTrue(StatementImport.summary("hello", 2026).isEmpty)
    }
}
