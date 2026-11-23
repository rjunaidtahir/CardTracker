package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.CardType
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
 * v23 (23 Nov 2026 evening): one debit word, "pymt" (the abbreviation of payment). A card alert worded "Pymt for <merchant> of AED x has been
 * made using Credit Card <5 digits>.Avl.limit AED y" from a chat the app has no rules for (a chat added by hand under any name) was left
 * unread because the smart reader's verb list did not know "pymt". Wordings are invented from the masked shape.
 */
class ShippedPackV23Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    private fun load(withPack: Boolean) {
        if (!withPack) { RulesPack.apply(null); return }
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 23)
        assertTrue("pymt" in out.pack.words.debit)
        RulesPack.apply(out.pack)
    }
    @BeforeTest fun before() = load(true)
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(body: String) = SmsParser.parseAsBank("My Card Chat", body, received, zone)

    @Test fun pymtCardAlertFromAnUnknownChatIsReadAsCreditCardPurchase() {
        for ((body, amount, merchant) in listOf(
            Triple("Pymt for SPINNEYS LLC of AED 88.10 has been made using Credit Card 71534.Avl.limit AED 3,900.00.", "88.10", "SPINNEYS LLC"),
            Triple("Pymt for DUBAI TAXI of AED 25.50 has been made using Credit Card 71534.Avl.limit AED 4,120.50.", "25.50", "DUBAI TAXI"),
            Triple("Pymt for GOOGLE *SERVICES of AED 14.69 has been made using Credit Card 71534.Avl.limit AED 900.00.", "14.69", "GOOGLE *SERVICES"),
        )) {
            val r = read(body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals(TxnType.PURCHASE, r.txn.type, body)
            assertEquals(CardType.CREDIT, r.txn.cardType, body)
            assertEquals("1534", r.txn.cardLast4, body)
            assertEquals(Decimal(amount), r.txn.amount, body)
            assertEquals(merchant, r.txn.merchant, body)
        }
    }

    @Test fun lookAlikesAreNotTurnedIntoPurchases() {
        // A failed or not-yet-done payment and money coming in are never a purchase.
        for (body in listOf(
            "Pymt for SPINNEYS LLC of AED 88.10 failed using Credit Card 71534.Avl.limit AED 3,900.00.",
            "Pymt of AED 500.00 credited to your account 1234. Available balance AED 2,500.00",
            "Please make your pymt of AED 500.00 before 25/11/2026 to avoid charges.",
        )) {
            val r = read(body)
            if (r is ParseResult.Transaction) assertTrue(r.txn.type != TxnType.PURCHASE, "got $r for: $body")
        }
    }

    @Test fun packCountsAfterV23() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertEquals(23, out.pack.version)
        assertEquals(34, out.pack.rules.size)
        assertEquals(45, out.pack.ignore.size)
    }
}
