package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.AttributedBy
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v27 (25 Nov 2026, owner's live instruction "fix them"): no new rule, ignore or word. The rules file only switches the 2.8 lever
 * wordingBanks on (`ext.levers.wordingBanks`), so a chat with no bank of its own is read by its wording through the built-in
 * bank rules. Measured on the reports: about 905 rows of Emirates Islamic card payments reported as plain "Other". The same lever
 * changes nothing for any other bank in the reported shapes (459 shapes re-tested: all become or stay Emirates Islamic).
 * Wordings are invented from masked shapes. 2.7 phones ignore ext; the file is still accepted by the frozen 2.7 reader.
 */
class ShippedPackV27Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 27)
        assertNull(out.pack.extRejected, "ext must be accepted")
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.knownCard = null }

    private fun chat(body: String) = SmsParser.parseAsBank("Other", body, received, zone)

    @Test fun senderLessEmiratesIslamicCardPaymentsAreRead() {
        for ((body, amount, merchant) in listOf(
            Triple("Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", "88.10", "SPINNEYS LLC"),
            Triple("Payment for ZAITHOON RESTAURANT of AED 41.00 has been made using Credit Card ending with 7788. Available limit AED 12,050.75.", "41.00", "ZAITHOON RESTAURANT"),
            Triple("Pymt for CARREFOUR DUBAI of AED 172.94 has been made using Credit Card 71534.Avl.limit AED 4,120.50.", "172.94", "CARREFOUR DUBAI"),
        )) {
            val r = chat(body)
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals("Emirates Islamic", r.txn.bank)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals("AED", r.txn.currency)
            assertEquals(merchant, r.txn.merchant)
            assertNotNull(r.txn.cardLast4)
            assertEquals("Emirates Islamic", r.attributedBank)
            assertEquals(AttributedBy.BUILTIN_WORDING, r.attributedBy)
        }
    }

    @Test fun lookAlikesAreNotAttributedOrReadAsPurchases() {
        for (body in listOf(
            "Payment for SPINNEYS LLC of AED 88.10 will be made using Credit Card ending with 1534 tomorrow.",
            "Pymt of AED 2,000.00 has been credited to your account XXXX1534. Avl.limit AED 4,120.50.",
            "Your order is ready. Call us on 600500000 for help.",
            "Hey, can you send me AED 50 for the dinner? Thanks!",
        )) {
            val r = chat(body)
            if (r is ParseResult.Transaction) {
                assertNull(r.attributedBank, "got $r for: $body")
                assertNotEquals(TxnType.PURCHASE, r.txn.type, "got $r for: $body")
            }
        }
    }

    @Test fun aKnownSenderStillUsesItsOwnRulesAndAnotherBankIsUnchanged() {
        val own = SmsParser.parseAsBank("Emirates Islamic", "Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", received, zone)
        assertIs<ParseResult.Transaction>(own)
        assertNull(own.attributedBank)
        assertEquals(Decimal("88.10"), own.txn.amount)
        val wio = SmsParser.parseAsBank("Wio", "Your Wio card payment of AED 25.00 at KFC was approved. Balance AED 500.00.", received, zone)
        if (wio is ParseResult.Transaction) assertNull(wio.attributedBank)
    }
}
