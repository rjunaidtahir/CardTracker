package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstalmentPlansTest {
    private fun read(vararg l: String) = InstalmentPlans.read(StatementReader.linesFromText(l.joinToString("\n")))

    @Test fun reads_a_plan_table() {
        val p = read(
            "Total payment due 5,000.00",
            "Deal Summary",
            "Deal Type   Booked Date   Amount   Outstanding Amount   Outstanding Instalments   Tenure   Instalment Amount   Expiry Date",
            "EPP   08/07/26   47,830.00   41,851.24   21   24   1,992.92   08/06/28",
            "Transaction Details",
            "01/09/26  CARREFOUR  120.00",
        ).single()
        assertEquals("EPP", p.kind)
        assertEquals(4_783_000L, p.originalMinor)
        assertEquals(4_185_124L, p.outstandingMinor)
        assertEquals(21, p.instalmentsLeft)
        assertEquals(24, p.tenure)
        assertEquals(199_292L, p.monthlyMinor)
        assertEquals(CalendarDate.of(2026, 7, 8).toEpochDay(), p.bookedEpochDay)
        assertEquals(CalendarDate.of(2028, 6, 8).toEpochDay(), p.endEpochDay)
    }

    @Test fun reads_several_plans() {
        val ps = read(
            "Instalment Plans",
            "Plan Type   Outstanding Amount   Instalments Left   Instalment Amount",
            "EPP   10,000.00   10   1,000.00",
            "Balance Transfer   6,000.00   6   1,000.00",
        )
        assertEquals(2, ps.size)
        assertEquals(1_000_000L, ps[0].outstandingMinor)
        assertEquals(6, ps[1].instalmentsLeft)
        assertEquals("BALANCE TRANSFER", ps[1].kind)
    }

    @Test fun reads_labelled_lines() {
        val p = read(
            "Deal Summary",
            "Deal type EPP",
            "Outstanding amount 41,851.24",
            "Outstanding instalments 21",
            "Instalment amount 1,992.92",
        ).single()
        assertEquals(4_185_124L, p.outstandingMinor)
        assertEquals(21, p.instalmentsLeft)
        assertEquals(199_292L, p.monthlyMinor)
    }

    @Test fun no_plan_section_means_no_plans() {
        assertTrue(read("Statement date 12/09/26", "Date   Description   Amount", "01/09/26  CARREFOUR  120.00  50.00").isEmpty())
    }
}
