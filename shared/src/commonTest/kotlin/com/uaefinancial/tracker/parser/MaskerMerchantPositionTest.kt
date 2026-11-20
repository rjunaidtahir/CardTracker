package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskerMerchantPositionTest {
    private val invest = "You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card."

    @Test fun merchantPlaceIsMarkedAndItsNameNeverAppears() {
        val r = Masker.mask(invest, "Invest Bank", "AL AHLI HOSPITAL")
        assertTrue(r.safe, r.reason)
        assertTrue(r.shape.contains("from {MERCHANT} for {CUR} {AMOUNT}"), r.shape)
        assertFalse(r.shape.contains("AHLI", ignoreCase = true), r.shape)
    }

    @Test fun withoutAMerchantTheWordingIsMaskedAsBefore() {
        val r = Masker.mask(invest, "Invest Bank")
        assertTrue(r.safe, r.reason)
        assertFalse(r.shape.contains("{MERCHANT}"))
        assertFalse(r.shape.contains("AHLI", ignoreCase = true))
    }

    @Test fun placeholderOrAbsentMerchantMarksNothing() {
        val a = Masker.mask(invest, "Invest Bank", "Card purchase")
        val b = Masker.mask(invest, "Invest Bank", "NOT IN THE MESSAGE")
        assertEquals(Masker.mask(invest, "Invest Bank").shape, a.shape)
        assertEquals(Masker.mask(invest, "Invest Bank").shape, b.shape)
    }

    @Test fun aMerchantWithADotOrSymbolIsStillMarked() {
        val body = "Your credit card ending 1234 was used for AED 45.50 at NOON.COM on 03-10-2026. Available limit AED 900.00"
        val r = Masker.mask(body, "Emirates NBD", "NOON.COM")
        assertTrue(r.shape.contains("at {MERCHANT}"), r.shape)
    }
}
