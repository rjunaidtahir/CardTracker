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

    @Test fun screenKeepsAKnownInstitutionMessage() {
        assertTrue(CandidateScreen.screen("Tabby", tabby, 0L) != null)
    }
}

class TabbyStatementWordingTest {
    private val stmt = "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app."

    @Test fun tabbyStatementIsReadAsAStatement() {
        val r = SmsParser.parse("Tabby", stmt, 0L)
        kotlin.test.assertIs<ParseResult.Statement>(r)
        kotlin.test.assertEquals("511.06", r.statement.statementBalance.toPlainString())
        kotlin.test.assertEquals("127.78", r.statement.minimumDue?.toPlainString())
        kotlin.test.assertEquals(30, r.statement.dueDate.dayOfMonth)
        kotlin.test.assertEquals(6, r.statement.dueDate.monthValue)
    }

    @Test fun aChargedPurchaseWithADateIsStillAPurchase() {
        val r = SmsParser.parse("Tabby", "You spent AED 50.00 at CAFE X on 12 Oct 2026. Your available Tabby Card limit is now AED 2000.00.", 0L)
        kotlin.test.assertIs<ParseResult.Transaction>(r)
    }
}

class StatementHintsTest {
    @Test fun findsTotalMinimumAndDueDateFromTabbyWording() {
        val text = SmsParser.normalizeBody("Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app.")
        val h = SmartParser.statementHints(text)
        kotlin.test.assertEquals("511.06", h.total?.toPlainString())
        kotlin.test.assertEquals("127.78", h.minimum?.toPlainString())
        kotlin.test.assertEquals("AED", h.currency)
        kotlin.test.assertEquals(30, h.due?.dayOfMonth)
        kotlin.test.assertEquals(6, h.due?.monthValue)
    }
}
