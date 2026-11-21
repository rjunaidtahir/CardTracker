package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The local export keeps every ordinary word; only values are replaced. */
class MaskerKeepWordsTest {
    @Test fun ordinaryWordsStayValuesGo() {
        val body = "A Cr. transaction of AED 1,250.50 on your account number 1234567890 was successful. Available balance is AED 9,000.00 on 21/09/2026 12:30"
        val r = Masker.mask(body, "ADCB", keepWords = true)
        assertTrue(r.safe, r.reason ?: "")
        assertTrue(r.shape.contains("A Cr. transaction of AED {AMOUNT}"), r.shape)
        assertTrue(r.shape.contains("was successful"), r.shape)
        assertFalse(r.shape.contains("1,250"), r.shape)
        assertFalse(r.shape.contains("1234567890"), r.shape)
    }

    @Test fun merchantNameIsReplacedByItsMarker() {
        val r = Masker.mask("Credit Card Purchase Card No XXXXXXXX1234 AED 80.00 ZENITH BAKERY DUBAI 21/09/2026", "FAB", merchant = "ZENITH BAKERY", keepWords = true)
        assertTrue(r.safe, r.reason ?: "")
        assertTrue(r.shape.contains("{MERCHANT}"), r.shape)
        assertFalse(r.shape.contains("ZENITH"), r.shape)
    }

    @Test fun shortAndAmountlessMessagesAreKeptToo() {
        val r = Masker.mask("Your statement is ready", "Tabby", keepWords = true)
        assertTrue(r.safe)
        assertEquals("Your statement is ready", r.shape)
    }

    @Test fun normalMaskingIsUnchanged() {
        val r = Masker.mask("A Cr. transaction of AED 1,250.50 on your account number 1234567890 was successful. Thank you for banking with us", "ADCB")
        assertTrue(r.shape.contains("{TEXT}"), r.shape)
    }
}
