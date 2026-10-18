package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Ignore rules added from tester message shapes (week of 2026-10-18). Messages are made-up, built from the masked shapes. */
class WeeklyIgnoreRulesTest {
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.setCustomSenders(emptyMap()) }

    private val pack = """{"version":2,"minEngine":5,"banks":[],"rules":[],"ignore":[
      {"label":"Insufficient balance","pattern":"transaction of\\s+[A-Z]{3}\\s*[\\d,.]+\\s+\\w+\\s+be processed due to insufficient balance"},
      {"label":"Document request","pattern":"Emirates ID.{0,60}\\sto\\s+\\S+@\\S+\\s+from your registered"},
      {"label":"Branch notice","pattern":"/-,\\s*[A-Z]{3}\\s*[\\d,.]+/-,\\s*[A-Z]{3}\\s*[\\d,.]+/-\\s+and\\s+[A-Z]{3}\\s*[\\d,.]+/-\\s+in your account"}]}"""

    private fun setup() {
        SmsParser.setCustomSenders(mapOf("TESTBANK" to "Test Bank"))
        val out = RulesPack.parse(pack)
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
    }

    private fun label(body: String): String? =
        (SmsParser.parse("TESTBANK", body, 0L) as? ParseResult.Ignored)?.reason

    @Test fun insufficientBalanceIsNotATransaction() {
        setup()
        assertEquals("Insufficient balance", label("Dear Customer, Your Raast transaction of PKR 2,500.00 could not be processed due to insufficient balance in your account. For queries please call 111"))
    }

    @Test fun documentRequestIsNotATransaction() {
        setup()
        assertEquals("Document request", label("Dear Customer, your Platinum Credit Card is approved for you! Please pay AED 100.00 & send the Trade Licence and Emirates ID copy of the owner to help@testbank.ae from your registered email ID."))
    }

    @Test fun branchNoticeIsNotATransaction() {
        setup()
        assertEquals("Branch notice", label("Dear Customer, Bank Alfa is unable to honour its cheques for clearing your amounts of PKR 1,000/-, PKR 2,000/-, PKR 3,000/- and PKR 4,000/- in your account. For details call (021)111222 or visit your nearest branch."))
    }

    @Test fun ordinaryPurchasesAreStillRead() {
        setup()
        val r = SmsParser.parse("TESTBANK", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00", 0L)
        assertEquals(null, (r as? ParseResult.Ignored)?.reason)
    }
}
