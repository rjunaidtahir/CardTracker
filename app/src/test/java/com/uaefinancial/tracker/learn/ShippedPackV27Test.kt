package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * v27 candidate (24 Nov 2026 loop run, 19:23 slot): two Wio reading rules, one report each (loose-masked 2.7/e7).
 * IPO subscription debit and IPO refund were hidden by the built-in IPO ignore although money moves. Pack rules run before
 * the built-in ignore. Wordings invented from the masked shape. Held for the owner (policy: track IPO holds).
 */
class ShippedPackV27Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 27)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun ipoDebitIsReadAsPurchase() {
        val r = read("Wio", "AED 10,000.00 has been debited from your account number 1234567890 for Alpha Data IPO Subscription.")
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals(TxnType.PURCHASE, r.txn.type)
        assertEquals("10000.00", r.txn.amount.toPlainString())
        assertEquals("AED", r.txn.currency)
        assertEquals("IPO subscription", r.txn.merchant)
    }

    @Test fun ipoRefundIsReadAsRefund() {
        val r = read("Wio", "AED 10,000.00 refund amount has been credited to your account number 1234567890 for Alpha Data IPO.")
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals(TxnType.REFUND, r.txn.type)
        assertEquals("10000.00", r.txn.amount.toPlainString())
        assertEquals("AED", r.txn.currency)
    }

    @Test fun lookAlikesAreUnchanged() {
        // An IPO advert without an amount and debit verb stays hidden.
        val ad = read("Wio", "Subscribe to the new IPO now through the Wio app. Offer closes Friday. Terms apply.")
        assertTrue(ad !is ParseResult.Transaction, "advert read as money: $ad")
        // Another bank is not read by the Wio rules.
        val other = read("Unknown Test Bank", "AED 10,000.00 has been debited from your account number 1234567890 for Alpha Data IPO Subscription.")
        assertTrue(other !is ParseResult.Transaction || other.txn.merchant != "IPO subscription", "other bank read by rule: $other")
        // An ordinary Wio debit is not given the IPO label.
        val plain = read("Wio", "AED 50.00 has been debited from your account number 1234567890 for Netflix.")
        assertTrue(plain !is ParseResult.Transaction || plain.txn.merchant != "IPO subscription", "plain debit labelled IPO: $plain")
    }

    @Test fun packCountsAfterV27() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 27)
        assertTrue(out.pack.rules.size >= 37)
        assertTrue(out.pack.rules.any { it.rule.id == "pack:wio-ipo-subscription-debit-20261124" })
        assertTrue(out.pack.rules.any { it.rule.id == "pack:wio-ipo-refund-credited-20261124" })
    }
}
