package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Owner decision 22 Nov: notices of a FUTURE debit are ignored; a debit that happened is still read. */
class UpcomingDebitNoticeTest {
    private val received = 1_780_000_000_000L

    @Test fun hdfc_and_pnb_style_upcoming_debit_notices_are_ignored() {
        val notices = listOf(
            "Rs 499.00 will be debited on 25/11/2026 from your HDFC Bank Card ending 1234 for NETFLIX. Ensure sufficient balance.",
            "Your UPI Autopay of Rs 150.00 will be debited from A/c XX1234 on 25-Nov-2026 for SPOTIFY. PNB",
        )
        for (n in notices) {
            val r = SmsParser.parseAsBank("HDFC Bank", n, received)
            val ig = assertIs<ParseResult.Ignored>(r, n)
            assertEquals("Upcoming debit notice", ig.reason)
        }
    }

    @Test fun a_debit_that_already_happened_is_not_ignored_as_upcoming() {
        val done = listOf(
            "Rs 499.00 has been debited from your HDFC Bank Card ending 1234 at NETFLIX on 25/11/2026. Avl limit Rs 10,000.00.",
            "AED 60.00 was debited from your card ending 9090 at LULU HYPERMARKET. A further AED 5.00 will be debited as a fee.",
        )
        for (d in done) {
            val r = SmsParser.parseAsBank("HDFC Bank", d, received)
            assertTrue(!(r is ParseResult.Ignored && r.reason == "Upcoming debit notice"), d)
        }
    }
}
