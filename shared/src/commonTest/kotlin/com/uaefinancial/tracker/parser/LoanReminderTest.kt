package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A loan instalment reminder is not a card statement (real ENBD sample, 2026). */
class LoanReminderTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 5, 6, 9, 0).toEpochMillis(zone)

    @Test fun personal_loan_instalment_reminder_is_ignored_not_a_card_statement() {
        val r = SmsParser.parse("ENBD", "Your installment of AED 3,394.00 for your PERSONAL LOAN No. 6053XXXXXXXX0001 is due on 08/05/2026. Please ensure sufficient funds are available. Thank you for banking with us.", received, zone)
        assertIs<ParseResult.Ignored>(r, "got $r")
    }

    @Test fun real_card_statement_is_still_read() {
        val r = SmsParser.parse("ENBD", "Emirates NBD Credit Card Mini Stmt for Card ending 9952: Statement date 09/12/24. Total Amt Due AED 911.75, Due Date 03/01/25. Min Amt Due AED 100.00", received, zone)
        assertIs<ParseResult.Statement>(r, "got $r")
        assertEquals("9952", r.statement.cardLast4)
    }
}
