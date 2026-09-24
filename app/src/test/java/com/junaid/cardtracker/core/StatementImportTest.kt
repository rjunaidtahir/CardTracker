package com.junaid.cardtracker.core

import org.junit.Test
import java.time.LocalDate
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
        assertEquals(LocalDate.of(2026, 8, 12), l[0].date)
        assertEquals("TALABAT.COM DUBAI", l[0].description)
        assertEquals(9090L, l[0].amountMinor)
        assertFalse(l[0].isCredit)
        assertEquals(15000L, l[1].amountMinor)
        assertTrue(l[2].isCredit)
        assertEquals(100000L, l[2].amountMinor)
        assertEquals(98055L, l[3].amountMinor)
        assertEquals(LocalDate.of(2026, 8, 25), l[4].date)
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
        val d = LocalDate.of(2026, 8, 12)
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

    @Test fun summary_figures() {
        val text = """
            Emirates Islamic Credit Card Statement
            Card Number 4567 XXXX XXXX 6901
            Statement Date: 09/09/2026        Payment Due Date: 04 Oct 2026
            Total Credit Limit AED 25,000.00   Available Credit Limit AED 18,420.50
            Total Amount Due AED 6,579.50
            Minimum Amount Due AED 329.00
        """.trimIndent()
        val s = StatementImport.summary(text)
        assertEquals(LocalDate.of(2026, 9, 9), s.statementDate)
        assertEquals(LocalDate.of(2026, 10, 4), s.dueDate)
        assertEquals(2_500_000L, s.creditLimitMinor)
        assertEquals(1_842_050L, s.availableLimitMinor)
        assertEquals(657_950L, s.totalDueMinor)
        assertEquals(32_900L, s.minimumDueMinor)
        assertEquals("6901", s.cardLast4)
        assertEquals("Emirates Islamic", s.bank)

        val fab = StatementImport.summary("Amount due to avoid financial charges: 3,734.00\nMinimum Due 186.70\nDue Date 06-12-2023")
        assertEquals(373_400L, fab.totalDueMinor)
        assertEquals(LocalDate.of(2023, 12, 6), fab.dueDate)
        assertTrue(StatementImport.summary("hello").isEmpty)
    }

    @Test fun since_statement() {
        val start = SinceStatement.startDate(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10))
        assertEquals(LocalDate.of(2026, 9, 10), start)
        val txns = listOf(
            InsightTxn(LocalDate.of(2026, 9, 9), com.junaid.cardtracker.parser.TxnType.PURCHASE, 5000, "C", null, "a", "a", "AED", 5000),
            InsightTxn(LocalDate.of(2026, 9, 12), com.junaid.cardtracker.parser.TxnType.PURCHASE, 7000, "C", null, "b", "b", "AED", 7000),
            InsightTxn(LocalDate.of(2026, 9, 13), com.junaid.cardtracker.parser.TxnType.REFUND, 1000, "C", null, "c", "c", "AED", 1000),
            InsightTxn(LocalDate.of(2026, 9, 14), com.junaid.cardtracker.parser.TxnType.PAYMENT, 9000, "C", null, "d", "d", "AED", 9000),
        )
        assertEquals(6000L, SinceStatement.spend(txns, start))
    }
}
