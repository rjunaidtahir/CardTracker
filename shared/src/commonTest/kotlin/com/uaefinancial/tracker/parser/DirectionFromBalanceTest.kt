package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.read
import com.uaefinancial.tracker.parser.P28.rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Section 14 of the 2.8 spec: a wording with no direction (invented, SIB "Within UAE Funds Transfer" style) is read only when the
 * balance proves the direction; otherwise it stays in Needs review with stage NO_DIRECTION. Never a guess.
 */
class DirectionFromBalanceTest {
    private val bank = "Sharjah Islamic Bank"
    private val r = rule(bank, "sib-within", """Within UAE Funds Transfer of {CUR} {AMOUNT} (?:from|to) account {ACCT} is complete\. Available balance {ANYCUR} {AVAIL}""",
        type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Within UAE transfer\",\"directionFromBalance\":true")
    private val day = 86_400_000L

    /** Earlier balances the app "knows": (bank, last4, currency) -> (minor, at). */
    private var known: Map<Triple<String, String?, String>, Pair<Long, Long>> = emptyMap()

    @BeforeTest fun on() {
        apply(file(ext = levers("acctToken", "directionFromBalance") + ",\"rules\":[$r]"))
        SmsParser.lastBalance = { b, l, c, before -> known[Triple(b, l, c)]?.takeIf { it.second <= before } }
    }
    @AfterTest fun off() { RulesPack.apply(null); SmsParser.lastBalance = null }

    private fun msg(amount: String, bal: String, cur: String = "AED") =
        read(bank, "Within UAE Funds Transfer of $cur $amount from account XXXX1234 is complete. Available balance $cur $bal")

    @Test fun debitProvenByTheBalance() {
        known = mapOf(Triple(bank, "1234", "AED") to (100_000L to P28.AT - day))
        val t = msg("700.00", "300.00")
        assertIs<ParseResult.Transaction>(t, "got $t")
        assertEquals(TxnType.TRANSFER_OUT, t.txn.type)
        assertEquals("direction-from-balance", t.txn.inferred, "the row is marked Check this")
    }

    @Test fun creditProvenByTheBalance() {
        known = mapOf(Triple(bank, "1234", "AED") to (100_000L to P28.AT - day))
        val t = msg("700.00", "1,700.00")
        assertIs<ParseResult.Transaction>(t, "got $t")
        assertEquals(TxnType.TRANSFER_IN, t.txn.type)
    }

    @Test fun noEarlierBalanceStaysInReview() {
        known = emptyMap()
        val f = msg("700.00", "300.00")
        assertIs<ParseResult.Failed>(f, "got $f"); assertEquals(Stage.NO_DIRECTION, f.stage)
    }

    @Test fun aMissedMessageInBetweenStaysInReview() {
        known = mapOf(Triple(bank, "1234", "AED") to (100_000L to P28.AT - day))
        val f = msg("700.00", "550.00") // fits neither way
        assertIs<ParseResult.Failed>(f); assertEquals(Stage.NO_DIRECTION, f.stage)
    }

    @Test fun anOtherCurrencyOrAnOldBalanceStaysInReview() {
        known = mapOf(Triple(bank, "1234", "USD") to (100_000L to P28.AT - day))
        assertIs<ParseResult.Failed>(msg("700.00", "300.00"))
        known = mapOf(Triple(bank, "1234", "AED") to (100_000L to P28.AT - 40 * day))
        assertIs<ParseResult.Failed>(msg("700.00", "300.00"))
    }

    @Test fun bothDirectionsFittingIsNotProof() {
        assertNull(SmsParser.directionFromBalance(1000, 0, 0, 1000, 10))
        assertNull(SmsParser.directionFromBalance(1000, 0, 100, 500, 10))
        assertEquals(true, SmsParser.directionFromBalance(1000, 0, 100, 900, 10))
        assertEquals(false, SmsParser.directionFromBalance(1000, 0, 100, 1100, 10))
    }

    @Test fun withTheLeverOffTheRuleIsNotUsed() {
        known = mapOf(Triple(bank, "1234", "AED") to (100_000L to P28.AT - day))
        apply(file(ext = levers("acctToken") + ",\"rules\":[$r]"))
        val t = msg("700.00", "300.00")
        assertEquals(null, (t as? ParseResult.Transaction)?.ruleId?.takeIf { it == "pack:sib-within" }, "got $t")
    }
}
