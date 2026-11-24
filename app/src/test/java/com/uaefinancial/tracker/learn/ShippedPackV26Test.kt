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
 * v26 (24 Nov 2026 loop run, 11:23 slot): one reading rule, no ignore, no words.
 *  - Wio "Your term deposit X is now mature. An amount of AED n has been credited to Current Account." was hidden as an
 *    Advert although money arrives (2 reports, 2 different deposits, loose-masked 2.7/e7). Pack rules run before the built-in
 *    ignore, so the rule reads it as money in without an app update. Wordings are invented from the masked shape.
 */
class ShippedPackV26Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 26)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun maturedDepositCreditedToCurrentAccountIsReadAsMoneyIn() {
        for ((name, amount) in listOf("Fixed Saving Space" to "5012.50", "New house" to "20100.00")) {
            val shown = if (amount == "5012.50") "5,012.50" else "20,100.00"
            val r = read("Wio", "Congrats! Your term deposit $name is now mature. An amount of AED $shown has been credited to Current Account. Open a new Fixed Saving Space to continue earning interest.")
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals(TxnType.TRANSFER_IN, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency)
            assertEquals("Term deposit matured", r.txn.merchant)
        }
    }

    @Test fun lookAlikesAreUnchanged() {
        // A pure advert stays hidden.
        val ad = read("Wio", "Open a new Fixed Saving Space and earn up to 5% interest. Offer ends soon. Terms apply.")
        assertTrue(ad !is ParseResult.Transaction, "advert read as money: $ad")
        // The interest-and-principal wording keeps the reading it had before (biggest amount, by the built-in reader).
        val old = read("Wio", "Congrats! Your term deposit Savings is now mature. You've earned AED 12.50 in interest! The total amount of AED 5,012.50 in interest, along with your principal amount of AED 5,000.00, has been credited to your Current Account. Open a new Fixed Saving Space to continue earning interest.")
        assertIs<ParseResult.Transaction>(old, "got $old")
        assertEquals("5012.50", old.txn.amount.toPlainString())
        assertTrue(old.txn.merchant != "Term deposit matured")
        // A deposit that is not yet mature (reminder) is not read by this rule.
        val soon = read("Wio", "Your term deposit Fixed Saving Space will mature on 30 Nov. An amount of AED 5,000.00 will be credited to Current Account. Open a new Fixed Saving Space to continue earning interest.")
        assertTrue(soon !is ParseResult.Transaction || soon.txn.merchant != "Term deposit matured", "reminder read by the rule: $soon")
        // The rule only belongs to Wio.
        val other = read("Unknown Test Bank", "Your term deposit Fixed Saving Space is now mature. An amount of AED 5,000.00 has been credited to Current Account.")
        assertTrue(other !is ParseResult.Transaction || other.txn.merchant != "Term deposit matured", "other bank read by the rule: $other")
        // Ordinary purchase wordings are still read.
        val purchase = read("Unknown Test Bank", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals("45.50", purchase.txn.amount.toPlainString())
    }

    @Test fun packCountsAfterV26() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 26)
        assertTrue(out.pack.rules.size >= 35)
        assertTrue(out.pack.ignore.size >= 47)
        assertTrue(out.pack.rules.any { it.rule.id == "pack:wio-term-deposit-matured-credited-20261124" })
    }
}
