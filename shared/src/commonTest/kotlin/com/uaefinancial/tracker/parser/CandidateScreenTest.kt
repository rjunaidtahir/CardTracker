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

    @Test fun a_message_with_an_amount_but_no_clear_format_is_kept_as_unfamiliar() {
        val s = assertNotNull(CandidateScreen.screen("MyBank", "Reminder: your invoice of AED 350.00 is waiting for you.", now))
        assertEquals(CandidateKind.UNFAMILIAR, s.kind)
        assertNull(s.reading)
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
        assertNotNull(CandidateScreen.screen("24273", "Reminder: your invoice of AED 350.00 is waiting for you.", now))
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
