package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksTest {
    private val min = 60_000L
    private fun row(id: Long, t: Long, amount: Long, avail: Long? = null, type: String = "PURCHASE", merchant: String? = "carrefour", card: String? = "A|1111") =
        Checks.Row(id, card, t, type, amount, "AED", merchant, avail)

    // ------------------------------------------------------------ amount in the message
    @Test fun amount_found_in_common_layouts() {
        assertTrue(Checks.amountAppearsIn("Spent AED 1,250.50 at X", 125050))
        assertTrue(Checks.amountAppearsIn("Spent AED 1250.5 at X", 125050))
        assertTrue(Checks.amountAppearsIn("Spent EUR 1.250,50 at X", 125050))
        assertTrue(Checks.amountAppearsIn("Spent CHF 1'250.50 at X", 125050))
        assertTrue(Checks.amountAppearsIn("Spent 1 250,50 kr", 125050))
        assertTrue(Checks.amountAppearsIn("Spent AED 1,250 at X", 125000))
        assertTrue(Checks.amountAppearsIn("Spent AED 80 at X", 8000))
        assertTrue(Checks.amountAppearsIn("Spent KWD 12.345 at X", 1235))
    }

    @Test fun amount_missing_is_caught() {
        assertFalse(Checks.amountAppearsIn("Spent AED 1,250.50 at X card 4821", 999900))
        assertFalse(Checks.amountAppearsIn("Spent AED 12.50 at X", 125000)) // decimal point misplaced
        assertTrue(Checks.amountAppearsIn("No digits here", 100))
    }

    // ------------------------------------------------------------ balance
    @Test fun balance_that_adds_up_is_quiet() {
        val rows = listOf(row(1, 0, 1800, avail = 214000), row(2, 5 * min, 1800, avail = 212200))
        assertTrue(Checks.evaluate(rows).isEmpty())
    }

    @Test fun balance_gap_is_flagged_on_the_later_one() {
        val rows = listOf(row(1, 0, 1800, avail = 214000), row(2, 5 * min, 1800, avail = 192200))
        val f = Checks.evaluate(rows)
        assertNull(f[1L])
        assertEquals(Checks.Kind.BALANCE, f[2L]!!.single().kind)
        assertTrue(f[2L]!!.single().message.contains("20,000.00") || f[2L]!!.single().message.contains("200.00"))
    }

    @Test fun refunds_add_to_the_balance() {
        val rows = listOf(row(1, 0, 1000, avail = 100000), row(2, min, 5000, avail = 105000, type = "REFUND"))
        assertTrue(Checks.evaluate(rows).isEmpty())
    }

    @Test fun balance_is_not_compared_across_cards_or_long_gaps() {
        val a = listOf(row(1, 0, 1000, avail = 100000, card = "A"), row(2, min, 1000, avail = 5000, card = "B"))
        assertTrue(Checks.evaluate(a).isEmpty())
        val gap = listOf(row(1, 0, 1000, avail = 100000), row(2, 30L * 24 * 60 * min, 1000, avail = 5000))
        assertTrue(Checks.evaluate(gap).isEmpty())
    }

    // ------------------------------------------------------------ duplicate
    @Test fun same_amount_and_merchant_within_minutes_is_flagged() {
        val rows = listOf(row(1, 0, 2500), row(2, 2 * min, 2500))
        assertEquals(Checks.Kind.DUPLICATE, Checks.evaluate(rows)[2L]!!.single().kind)
        assertNull(Checks.evaluate(rows)[1L])
    }

    @Test fun repeat_hours_later_or_other_amount_is_fine() {
        assertTrue(Checks.evaluate(listOf(row(1, 0, 2500), row(2, 60 * min, 2500))).isEmpty())
        assertTrue(Checks.evaluate(listOf(row(1, 0, 2500), row(2, min, 2600))).isEmpty())
    }

    // ------------------------------------------------------------ unusual
    @Test fun a_far_larger_spend_is_flagged_only_with_enough_history() {
        val usual = (1L..10L).map { row(it, it * 60 * min, 5000, merchant = "m$it") }
        val big = row(99, 11 * 60 * min, 5_000_00, merchant = "x")
        assertEquals(Checks.Kind.UNUSUAL, Checks.evaluate(usual + big)[99L]!!.single().kind)
        assertTrue(Checks.evaluate(listOf(big)).isEmpty())
        assertTrue(Checks.evaluate(usual + row(98, 12 * 60 * min, 60_000, merchant = "y")).isEmpty()) // 600.00 < floor
    }
}
