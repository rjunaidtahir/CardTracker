package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateScreenTest {
    private val now = 1_780_000_000_000L
    private val purchase = "Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00."

    @Test fun a_clear_purchase_from_an_unknown_named_sender_is_recognisable() {
        val s = assertNotNull(CandidateScreen.screen("MyBank", purchase, now))
        assertEquals(CandidateKind.RECOGNISABLE, s.kind)
        assertNotNull(s.reading)
    }

    @Test fun a_message_with_an_amount_but_no_transaction_in_it_is_not_kept() {
        assertNull(CandidateScreen.screen("MyBank", "Reminder: your invoice of AED 350.00 is waiting for you.", now))
    }

    @Test fun promotions_and_telecom_offers_are_not_kept() {
        val promos = listOf(
            "du recharge offer: Recharge any du prepaid with AED 50+ on du Pay and get AED 10 back instantly. Applicable once per customer. T&Cs apply.",
            "Know someone who'd love du Pay as much as you do? Invite them today and earn up to AED 2,000 in rewards!",
            "Get 30 GB data (1 GB per day for 30 days) for only AED 50. Dial *055*199# to activate.",
            "Win AED 1,000 every day! Send money home or spend with du Pay. Enter now: dupay.app.link/CSM25",
            "Dear Customer, Thank you for reloading your account with total amount of AED 200.00. Your Current balance is AED 200.00. Thank you, ENOC",
        )
        for (p in promos) assertNull(CandidateScreen.screen("AD-duPay", p, now), p)
        // A flood of them from many senders stays out.
        var kept = 0
        for (i in 0 until 3000) if (CandidateScreen.screen("AD-Promo${i % 40}", promos[i % promos.size] + " ref $i", now) != null) kept++
        assertEquals(0, kept)
    }

    @Test fun one_time_codes_are_never_kept() {
        assertNull(CandidateScreen.screen("MyBank", "Your OTP is 123456 for payment of AED 100.00. Do not share it.", now))
        assertNull(CandidateScreen.screen("24273", "Use code 482913 to confirm AED 20.00 purchase", now))
    }

    @Test fun messages_with_no_amount_are_not_kept() {
        assertNull(CandidateScreen.screen("MyBank", "Are we still on for dinner tonight?", now))
        assertNull(CandidateScreen.screen("MyBank", "Your card ending 9090 has been blocked. Call us.", now))
        assertNull(CandidateScreen.screen("MyBank", "", now))
    }

    @Test fun chat_about_money_from_a_phone_number_is_not_kept() {
        assertNull(CandidateScreen.screen("+971501234567", "can you send me 50 dirhams for lunch", now))
    }

    @Test fun a_bank_style_message_from_a_phone_number_is_kept() {
        assertNotNull(CandidateScreen.screen("+971501234567", purchase, now))
    }

    @Test fun short_codes_count_as_named_senders() {
        assertNotNull(CandidateScreen.screen("24273", purchase, now))
    }

    @Test fun the_shape_key_carries_no_digits() {
        val s = assertNotNull(CandidateScreen.screen("MyBank", purchase, now))
        assertTrue(s.shapeKey.isNotEmpty(), "shape should be available for a normal purchase")
        assertFalse(s.shapeKey.any { it.isDigit() && !s.shapeKey.contains("{") }, "no raw digits outside tokens")
        assertFalse(s.shapeKey.contains("9090"))
        assertFalse(s.shapeKey.contains("60.00"))
    }

    @Test fun the_same_wording_gives_the_same_shape() {
        val a = CandidateScreen.screen("MyBank", purchase, now)!!
        val b = CandidateScreen.screen("MyBank", "Your card ending 1111 was used for AED 75.50 at LULU HYPERMARKET on 22/09/2026 09:30.", now)!!
        assertEquals(a.shapeKey, b.shapeKey)
    }
}
