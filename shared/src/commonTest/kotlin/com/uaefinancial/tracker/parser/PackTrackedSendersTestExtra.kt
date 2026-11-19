package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownInstitutionAlertTest {
    private val tabby = "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94."

    @Test fun aKnownInstitutionNeedsNoExtraBankingWords() {
        assertFalse(SmartParser.looksLikeBankAlert(tabby))
        assertTrue(SmartParser.looksLikeBankAlert(tabby, knownInstitution = true))
    }

    @Test fun oneTimeCodesAndNonFinancialStayOutEvenFromAKnownInstitution() {
        assertFalse(SmartParser.looksLikeBankAlert("123456 is your OTP for AED 50.00 payment", knownInstitution = true))
        assertFalse(SmartParser.looksLikeBankAlert("Your parcel is on its way, see you soon", knownInstitution = true))
    }
}

class TabbyWordingTest {
    private val tabby = "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94."

    @Test fun tabbyPurchaseWithoutACardNumberIsRead() {
        val r = SmsParser.parse("Tabby", tabby, 0L)
        kotlin.test.assertIs<ParseResult.Transaction>(r)
        kotlin.test.assertEquals("ISLAND SHELL CAFETERIA", r.txn.merchant.trim().trimEnd('.'))
        kotlin.test.assertEquals(TxnType.PURCHASE, r.txn.type)
        kotlin.test.assertTrue(r.txn.auto, "read by the generic reader, so its shape is offered for a verified rule")
    }

    @Test fun screenKeepsAKnownInstitutionMessageButNotAnUnknownSendersSame() {
        assertTrue(CandidateScreen.screen("Tabby", tabby, 0L) != null)
        assertTrue(CandidateScreen.screen("RandomShop", tabby, 0L) == null)
    }
}
