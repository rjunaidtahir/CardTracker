package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ENBD payment-due reminders carry a due date, total and minimum: they are read as statements, not hidden. */
class PaymentDueReminderTest {
    private val received = 1_780_000_000_000L

    @Test fun a_payment_due_reminder_with_total_min_and_due_date_is_read_as_a_statement() {
        val r = SmsParser.parseAsBank("Emirates NBD",
            "Payment for Credit Card ending 9940 is due on 03/09/26. Total Amt - AED 2862.26; Min Amt - AED 132.61. Please pay by due date to avoid charges.", received)
        val st = assertIs<ParseResult.Statement>(r)
        assertEquals("9940", st.statement.cardLast4)
        assertEquals(CalendarDate.of(2026, 9, 3), st.statement.dueDate)
        assertTrue(st.statement.statementBalance.toString().startsWith("2862.26"))
        assertTrue(st.statement.minimumDue.toString().startsWith("132.61"))
    }

    @Test fun the_standing_instruction_variant_that_pays_itself_stays_hidden() {
        val r = SmsParser.parseAsBank("Emirates NBD",
            "Payment for Emirates NBD card ending 0866 is due on 28/02/25 and will be deducted from your bank account per your standing instruction. Please ensure the account is funded. Total Amt Due AED 110.09, Min Amt Due AED 100.00.", received)
        val ig = assertIs<ParseResult.Ignored>(r)
        assertEquals("Payment reminder", ig.reason)
    }

    @Test fun a_reminder_with_an_advert_tail_or_no_amounts_is_not_read_as_a_statement() {
        val r = SmsParser.parseAsBank("Emirates NBD", "Payment for Credit Card ending 1234 is due. Use Code: SAVE10 for 20% off.", received)
        assertTrue(r !is ParseResult.Statement)
    }
}
