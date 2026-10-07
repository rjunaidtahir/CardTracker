package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskerTest {
    @Test fun masksAPurchase() {
        val r = Masker.mask("Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026 14:22. Avl bal AED 9,876.00", "Emirates NBD")
        assertTrue(r.safe, r.reason ?: r.shape)
        assertEquals("Your Visa card ending {CARD} was used for {CUR} {AMOUNT} at {TEXT} on {DATE} {TIME}. Avl bal {CUR} {AMOUNT}", r.shape)
    }

    @Test fun keepsNothingPersonal() {
        val r = Masker.mask("Dear Junaid Tahir, AED 1,200.00 debited from a/c 0123456789 to Ahmed Khan ref 99887766. Call 0501234567 or visit https://bank.example.com/x", "HBL")
        assertFalse(r.shape.contains("Junaid"))
        assertFalse(r.shape.contains("Ahmed"))
        assertFalse(r.shape.contains("0123"))
        assertFalse(r.shape.contains("0501"))
        assertFalse(r.shape.contains("http"))
        assertFalse(r.shape.any { it.isDigit() })
    }

    @Test fun neverContainsDigitsOrAddresses() {
        val samples = listOf(
            "PKR 5,000.00 has been received in your account 1234567890 from Ali on 12/10/2026",
            "Rs.2500 spent on HDFC Bank Card x1234 at Amazon on 05-Oct-26. Avl limit Rs 85,000. Not you? Call 1800 258 6161",
            "You paid $12.99 to Netflix with card ending 4242 on Oct 3, 2026. Contact me@example.com",
        )
        for (s in samples) {
            val r = Masker.mask(s, "HDFC Bank")
            assertFalse(r.shape.replace(Regex("""\{[A-Z]+\}"""), "").any { it.isDigit() }, r.shape)
            assertFalse(r.shape.contains('@'), r.shape)
        }
    }

    @Test fun refusesWhatItCannotMask() {
        assertFalse(Masker.mask("Hi mum, running late, see you at Dubai Mall at 7, I will bring AED 50", null).safe)
        assertFalse(Masker.mask("تم خصم مبلغ 100 ريال من حسابك لدى متجر الأمل", null).safe)
        assertFalse(Masker.mask("", null).safe)
    }

    @Test fun otpLookalikeNeedsAnAmountToBeSent() {
        assertFalse(Masker.mask("Your one time password is 123456 do not share it with anyone", null).safe)
    }
}
