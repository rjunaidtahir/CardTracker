package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.core.StandingGap.Account
import com.uaefinancial.tracker.core.StandingGap.Entry
import com.uaefinancial.tracker.core.StandingGap.Outcome
import com.uaefinancial.tracker.core.StandingGap.Standing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The FAB case: two SMS per transfer, and on 1 Nov only the "standing instructions ... processed" one (AED 1,805 to
 * ENBD ANNUM XXXX7701) arrived, with no debit SMS. The balances show where the money went:
 *   after the 3,160.00 debit  the balance was 6,383.71
 *   after the   100.00 debit  it was         4,477.73   (drop 1,905.98 = 1,805.00 + 100.00 + 0.98)
 * Amounts below are in fils.
 */
class StandingGapTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t0 = 20_000L * day                      // 31 Oct, the 3,160.00 debit
    private val t1 = t0 + day + 6 * hour                // 1 Nov, the 100.00 debit
    private val siDay = t0 + day                        // the date in the standing-instruction message

    private fun debit(id: Long, t: Long, amount: Long, bal: Long?) = Entry(id, t, "TRANSFER_OUT", amount, bal)

    private val fab8001 = Account("FAB|8001", listOf(
        debit(1, t0, 316_000, 638_371),
        debit(2, t1, 10_000, 447_773),
    ))

    /** A second account with a chain that adds up: nothing missing there. */
    private val fab2407 = Account("FAB|2407", listOf(
        debit(10, t0, 35_000, 500_000),
        debit(11, t1, 5_000, 495_000),
    ))

    private val si1805 = Standing(100, siDay, 180_500)

    @Test fun the_1805_goes_on_8001_between_the_3160_and_the_100() {
        val r = StandingGap.resolve(listOf(fab8001, fab2407), listOf(si1805)).single()
        assertIs<Outcome.Place>(r)
        assertEquals("FAB|8001", r.accountKey)
        assertTrue(r.timestamp > t0 && r.timestamp < t1, "placed at ${r.timestamp}, between $t0 and $t1")
        assertEquals(98, r.residualMinor)               // AED 0.98 stays as its own flag
    }

    @Test fun the_balance_check_passes_after_it_is_placed_and_the_residual_is_flagged() {
        val r = StandingGap.resolve(listOf(fab8001, fab2407), listOf(si1805)).single() as Outcome.Place
        val rows = listOf(
            Checks.Row(1, "FAB|8001", t0, "TRANSFER_OUT", 316_000, "AED", null, 638_371),
            Checks.Row(100, "FAB|8001", r.timestamp, "TRANSFER_OUT", 180_500, "AED", null, null, inferred = true),
            Checks.Row(2, "FAB|8001", t1, "TRANSFER_OUT", 10_000, "AED", null, 447_773),
        )
        val f = Checks.evaluate(rows)
        assertTrue(f[2L] == null, "the balance check on the neighbouring row passes: ${f[2L]}")
        val residual = f[100L]!!.single()
        assertEquals(Checks.Kind.RESIDUAL, residual.kind)
        assertTrue(residual.message.contains("0.98"), residual.message)
        // Without the placed row the same data is the balance flag the app showed before.
        val before = Checks.evaluate(rows.filter { it.id != 100L })
        assertEquals(Checks.Kind.BALANCE, before[2L]!!.single().kind)
    }

    @Test fun a_late_debit_sms_means_it_is_already_counted_and_nothing_is_added() {
        // The debit SMS arrives later: same account, same amount, same day, with its own balance. It replaces the placed row.
        val withDebit = Account("FAB|8001", listOf(
            debit(1, t0, 316_000, 638_371),
            debit(3, siDay + 2 * hour, 180_500, 457_871),     // 6,383.71 - 1,805.00 (+ the 0.98 is gone: 4,578.71 - 100 = 4,478.71)
            debit(2, t1, 10_000, 447_773),
        ))
        val r = StandingGap.resolve(listOf(withDebit, fab2407), listOf(si1805)).single()
        assertIs<Outcome.AlreadyCounted>(r)
    }

    @Test fun a_standing_instruction_that_has_its_matching_debit_adds_nothing() {
        val paired = Account("FAB|8001", listOf(debit(1, siDay, 199_292, 1_166_218)))
        val r = StandingGap.resolve(listOf(paired), listOf(Standing(7, siDay, 199_292))).single()
        assertIs<Outcome.AlreadyCounted>(r)
    }

    @Test fun a_gap_on_two_accounts_is_not_guessed() {
        val other = Account("FAB|2407", listOf(
            debit(10, t0, 35_000, 500_000),
            debit(11, t1, 5_000, 313_950),                      // 4,950.00 - 1,805.00 - 50.00 gone: also a 1,805.50 hole
        ))
        val r = StandingGap.resolve(listOf(fab8001, other), listOf(si1805)).single()
        assertIs<Outcome.Unplaced>(r)
        assertEquals(StandingGap.Why.AMBIGUOUS, r.why)
    }

    @Test fun no_gap_anywhere_means_unplaced() {
        val r = StandingGap.resolve(listOf(fab2407), listOf(si1805)).single()
        assertIs<Outcome.Unplaced>(r)
        assertEquals(StandingGap.Why.NO_GAP, r.why)
    }

    @Test fun before_the_next_balance_arrives_there_is_nothing_to_place_it_against() {
        val onlyFirst = Account("FAB|8001", listOf(debit(1, t0, 316_000, 638_371)))
        val r = StandingGap.resolve(listOf(onlyFirst), listOf(si1805)).single()
        assertIs<Outcome.Unplaced>(r)
    }

    @Test fun two_missing_items_in_one_gap_are_placed_together_in_message_order() {
        // 1,805.00 and 350.00 both missing: the 100.00 debit's balance shows a hole of 2,155.98.
        val acc = Account("FAB|8001", listOf(
            debit(1, t0, 316_000, 638_371),
            debit(2, t1, 10_000, 638_371 - 10_000 - 215_500 - 98),
        ))
        val out = StandingGap.resolve(listOf(acc), listOf(si1805, Standing(101, siDay, 35_000)))
        assertEquals(2, out.size)
        out.forEach { assertIs<Outcome.Place>(it); assertEquals("FAB|8001", (it as Outcome.Place).accountKey) }
        assertEquals(98, (out[0] as Outcome.Place).residualMinor)
    }

    @Test fun two_candidates_for_one_hole_are_not_guessed() {
        // One 1,805.00 hole, but two different waiting messages that could each be it (1,805.00 and 1,800.00 both fit).
        val out = StandingGap.resolve(listOf(fab8001), listOf(si1805, Standing(101, siDay, 180_000)))
        out.forEach { assertIs<Outcome.Unplaced>(it); assertEquals(StandingGap.Why.AMBIGUOUS, (it as Outcome.Unplaced).why) }
    }

    @Test fun a_hole_with_too_much_left_over_is_not_filled() {
        // 1,805.00 does not explain a 1,905.98 hole: AED 100.98 would be unexplained, more than a fee.
        val acc = Account("FAB|8001", listOf(
            debit(1, t0, 316_000, 638_371),
            debit(2, t1, 10_000, 638_371 - 10_000 - 190_598),
        ))
        val r = StandingGap.resolve(listOf(acc), listOf(si1805)).single()
        assertIs<Outcome.Unplaced>(r)
    }

    @Test fun reading_again_gives_the_same_decisions() {
        val first = StandingGap.resolve(listOf(fab8001, fab2407), listOf(si1805))
        val again = StandingGap.resolve(listOf(fab2407, fab8001), listOf(si1805))     // even with the accounts in another order
        assertEquals(first, again)
        // And after the placed row exists and the message is re-read (its own row is removed first, other rows stay).
        val placed = first.single() as Outcome.Place
        val other = Standing(101, siDay + 5 * hour, 20_000)
        val withOther = Entry(500, siDay + hour, "TRANSFER_OUT", 20_000, null)       // another placed message, no balance
        val acc = Account("FAB|8001", listOf(fab8001.rows[0], withOther, fab8001.rows[1].copy(availMinor = 447_773 - 20_000)))
        val second = StandingGap.resolve(listOf(acc), listOf(si1805))
        assertEquals(placed.accountKey, (second.single() as Outcome.Place).accountKey)
        assertTrue(other.amountMinor > 0)
    }
}
