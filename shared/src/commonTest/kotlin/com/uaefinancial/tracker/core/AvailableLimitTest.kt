package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.core.AvailableLimit.Figure
import com.uaefinancial.tracker.core.AvailableLimit.Move
import com.uaefinancial.tracker.core.AvailableLimit.Source
import com.uaefinancial.tracker.parser.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvailableLimitTest {
    private val stmt = Figure(5_615_600, 100)      // AED 56,156.00 on day 100

    @Test fun nothing_known() = assertNull(AvailableLimit.resolve(null, null, emptyList()))

    @Test fun sms_only() {
        val r = AvailableLimit.resolve(Figure(9_328_800, 110), null, emptyList())!!
        assertEquals(9_328_800, r.minor); assertEquals(Source.SMS, r.source)
    }

    @Test fun statement_only_rolls_forward_purchases_and_payments() {
        val moves = listOf(
            Move(101, TxnType.PURCHASE, 100_000),   // -1,000
            Move(102, TxnType.PURCHASE, 25_050),    // -250.50
            Move(103, TxnType.PAYMENT, 300_000),    // +3,000
            Move(104, TxnType.REFUND, 10_000),      // +100
        )
        val r = AvailableLimit.resolve(null, stmt, moves, 10_000_000)!!
        assertEquals(5_615_600 - 100_000 - 25_050 + 300_000 + 10_000, r.minor)
        assertEquals(Source.STATEMENT, r.source); assertEquals(4, r.moves); assertEquals(100, r.asOfEpochDay)
    }

    @Test fun transactions_on_or_before_the_statement_date_are_already_in_the_statement() {
        val r = AvailableLimit.resolve(null, stmt, listOf(Move(100, TxnType.PURCHASE, 50_000), Move(99, TxnType.PURCHASE, 70_000)))!!
        assertEquals(stmt.minor, r.minor); assertEquals(0, r.moves)
    }

    @Test fun newer_sms_figure_wins_over_the_statement() {
        val r = AvailableLimit.resolve(Figure(9_328_800, 110), stmt, listOf(Move(105, TxnType.PURCHASE, 1_000)))!!
        assertEquals(9_328_800, r.minor); assertEquals(Source.SMS, r.source)
    }

    @Test fun sms_figure_on_the_statement_day_is_used() {
        val r = AvailableLimit.resolve(Figure(5_000_000, 100), stmt, emptyList())!!
        assertEquals(5_000_000, r.minor); assertEquals(Source.SMS, r.source)
    }

    @Test fun older_sms_figure_is_ignored_when_the_statement_is_newer() {
        val r = AvailableLimit.resolve(Figure(9_000_000, 90), stmt, listOf(Move(101, TxnType.PURCHASE, 100_000)))!!
        assertEquals(5_515_600, r.minor); assertEquals(Source.STATEMENT, r.source)
    }

    @Test fun never_below_zero_or_above_the_limit() {
        assertEquals(0, AvailableLimit.resolve(null, Figure(10_000, 100), listOf(Move(101, TxnType.PURCHASE, 99_999)), 10_000_000)!!.minor)
        assertEquals(10_000_000, AvailableLimit.resolve(null, Figure(9_990_000, 100), listOf(Move(101, TxnType.PAYMENT, 500_000)), 10_000_000)!!.minor)
    }

    @Test fun transfers_and_unknown_amounts_do_not_move_it() {
        val moves = listOf(Move(101, TxnType.TRANSFER_OUT, 5_000), Move(102, TxnType.TRANSFER_IN, 5_000), Move(103, TxnType.PURCHASE, null))
        val r = AvailableLimit.resolve(null, stmt, moves)!!
        assertEquals(stmt.minor, r.minor); assertEquals(0, r.moves)
    }
}
