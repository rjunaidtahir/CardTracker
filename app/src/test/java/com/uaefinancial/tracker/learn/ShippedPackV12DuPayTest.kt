package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** v12: du Pay prepaid card spends are read; du Pay promotions and look-alikes never become Needs review items. */
class ShippedPackV12DuPayTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    private fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 12)
        RulesPack.apply(out.pack)
    }

    private fun spend(sender: String, text: String): ParseResult.Transaction {
        val r = SmsParser.parse(sender, text, 1_700_000_000_000L)
        return assertIs<ParseResult.Transaction>(r, text)
    }

    @Test fun senderIsTrackedAsDuPay() {
        load()
        assertEquals("duPay", SmsParser.bankFor("duPay")?.name)
        assertEquals("duPay", SmsParser.bankFor("AD-duPay")?.name)
    }

    @Test fun cardSpendsAreReadWithAmountMerchantAndCard() {
        load()
        // invented names, numbers and ids; wording shape as reported by phones
        val a = spend("duPay", "Hello Test User, Your du Pay Card ending in 1234 has been used for AED 145.00 at Sample Govt - Tab. Your available balance is now AED 55.87 and your transaction ID is D1A2B3C4D5.")
        assertEquals(TxnType.PURCHASE, a.txn.type)
        assertEquals("145", a.txn.amount.toString().removeSuffix(".00").removeSuffix(".0"))
        assertEquals("Sample Govt - Tab", a.txn.merchant)
        val b = spend("duPay", "Hello Test User, Your du Pay Card ending in 1234 has been used for AED 400.30 at EXAMPLE.com/Al Sample Excha. Your available balance is now AED 0.04 and your transaction ID is C9Z8Y7X6W5.")
        assertEquals("EXAMPLE.com/Al Sample Excha", b.txn.merchant)
        val c = spend("AD-duPay", "Hello Test User, Your du Pay Card ending in 1234 has been used for AED 284.46 at SAMPLESHOP. Your available balance is now AED 1.34 and your transaction ID is CH1234ABCD.")
        assertEquals(TxnType.PURCHASE, c.txn.type)
        assertEquals("SAMPLESHOP", c.txn.merchant)
    }

    @Test fun duPayPromotionsAndNoticesAreNeverUnread() {
        load()
        val promos = listOf(
            "du recharge offer: Recharge any du prepaid with AED 50+ on du Pay and get AED 10 back instantly. Applicable once per customer. T&Cs apply.",
            "Know someone who'd love du Pay as much as you do? Invite them today and earn up to AED 2,000 in rewards!",
            "Win AED 1,000 every day! Send money home or spend with du Pay. Enter now: dupay.app.link/CSM25",
            "Hello Test User, Your du Pay balance is AED 55.87. Top up now with AED 100 and get a bonus.",
        )
        for (p in promos) {
            val r = SmsParser.parse("duPay", p, 1_700_000_000_000L)
            assertTrue(r !is ParseResult.Failed, "would land in Needs review: $p -> $r")
            assertTrue(r !is ParseResult.Transaction, "promo read as a transaction: $p -> $r")
        }
        // a different bank's wording from another sender is not touched by this rule
        assertNotNull(SmsParser.bankFor("duPay"))
    }
}
