package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.ParsedTransaction
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingGapsTest {
    private val zone = SmsParser.UAE_ZONE
    private fun read(s: String): ParsedTransaction {
        val r = SmartParser.read("X", SmsParser.normalizeBody(s), 1_700_000_000_000L, zone)
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        return r.txn
    }

    @Test fun noMerchantInTheMessageIsAGap() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 for AED 25.00. Thank you for using Invest Bank Credit Card.")
        assertEquals(listOf("merchant"), ReadingGaps.of(t))
    }

    @Test fun merchantFoundIsNoGap() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00. Thank you for using Invest Bank Credit Card.")
        assertEquals(emptyList(), ReadingGaps.of(t))
    }

    @Test fun readByAVerifiedRuleIsNotChecked() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 for AED 25.00.")
        assertEquals(emptyList(), ReadingGaps.of(t.copy(auto = false)))
    }

    @Test fun missingCardIsAGap() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 at CARREFOUR MALL for AED 25.00.")
        assertEquals(listOf("card"), ReadingGaps.of(t.copy(cardLast4 = null)))
    }

    @Test fun describeIsReadable() {
        assertTrue(ReadingGaps.describe("merchant,card").contains("no merchant name") && ReadingGaps.describe("merchant,card").contains("no card"))
    }
}
