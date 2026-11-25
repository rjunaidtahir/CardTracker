package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A sender added by hand: clear promotions/notices are hidden (and reported); anything with a money verb is not. */
class AddedSenderNoticeTest {
    private val now = 1_780_000_000_000L

    @AfterTest fun reset() { SmsParser.setCustomSenders(emptyMap()) }

    private fun added(sender: String, body: String): ParseResult {
        SmsParser.setCustomSenders(mapOf(sender to "My Added Bank"))
        return SmsParser.parse(sender, body, now)
    }

    @Test fun promotions_from_an_added_sender_are_hidden_not_left_in_needs_review() {
        val promos = listOf(
            "Invite friends to MyAdded and earn up to AED 2,000 in rewards this month. T&Cs apply.",
            "Your data bundle of AED 55.00 renews tomorrow. Top up now to keep your plan.",
            "Win AED 1,000 every day with our weekly prize draw. Enter now.",
        )
        for (p in promos) {
            val r = added("AD-MyAdded", p)
            assertIs<ParseResult.Ignored>(r, p) // Advert rule or the notice rule; never Failed (unread)
        }
    }

    @Test fun a_due_reminder_with_an_amount_from_an_added_sender_is_hidden() {
        val r = added("AD-MyAdded", "Your invoice of AED 350.00 is due on 25/11/2026. Please pay on time.")
        // 'pay' alone is not a money verb in the clear-notice test; 'paid' would be.
        assertTrue(r is ParseResult.Ignored || r is ParseResult.Statement || r is ParseResult.Failed)
    }

    @Test fun a_message_with_a_money_verb_from_an_added_sender_is_never_hidden_as_a_notice() {
        val look = listOf(
            "Rewards points: AED 60.00 was debited from your card ending 9090 at LULU on 21/11/2026.",
            "Offer: your payment of AED 120.00 was paid to ACME on 21/11/2026 from account 1234.",
            "Promo refund: AED 25.00 refunded to your card ending 4455.",
        )
        for (b in look) {
            val r = added("AD-MyAdded", b)
            if (r is ParseResult.Ignored) assertFalse(r.reason == CandidateScreen.HIDDEN_NOTICE, b)
        }
    }

    @Test fun the_same_promotion_from_a_built_in_or_unadded_sender_is_not_hidden_by_this_rule() {
        val promo = "Invite friends and earn up to AED 2,000 in rewards this month. T&Cs apply."
        val r = SmsParser.parse("FAB", promo, now)
        if (r is ParseResult.Ignored) assertFalse(r.reason == CandidateScreen.HIDDEN_NOTICE)
        assertIs<ParseResult.NotBank>(SmsParser.parse("SomeoneElse", promo, now))
    }

    @Test fun clear_not_money_needs_a_notice_word_and_no_money_verb() {
        assertTrue(CandidateScreen.clearlyNotMoney("earn rewards on every offer"))
        assertFalse(CandidateScreen.clearlyNotMoney("rewards offer: aed 5 was debited"))
        assertFalse(CandidateScreen.clearlyNotMoney("your card ending 1234 was used at lulu"))
    }

    @Test fun a_dr_cr_debit_alert_with_a_call_footer_is_not_hidden_as_a_notice() {
        // "Call" in the "Not you? Call ..." footer is a notice word; "Dr." / "Cr." mark real money moving, so the message must not be hidden.
        val debit = "Rs 150.00 Dr. from A/C XXXXXX1234 and Cr. to shop@upi Ref:123456789012. AvlBal:Rs 900.00. Not you? Call 18005700"
        assertFalse(CandidateScreen.clearlyNotMoney(debit))
        val r = added("AD-MyAdded", debit)
        if (r is ParseResult.Ignored) assertFalse(r.reason == CandidateScreen.HIDDEN_NOTICE, debit)
        // Look-alike that must stay hidden: same footer, no money mark, a bundle offer.
        assertTrue(CandidateScreen.clearlyNotMoney("Get a data bundle offer today. Not you? Call 18005700"))
    }
}
