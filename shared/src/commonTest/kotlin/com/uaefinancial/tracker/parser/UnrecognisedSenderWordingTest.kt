package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.rule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Section 2 of the 2.8 spec: a message from a sender with no bank of its own (a chat added by hand) is read by its wording
 * when the rules file switches the lever on. All wordings are invented from masked shapes.
 */
class UnrecognisedSenderWordingTest {
    private val at = 1_795_000_000_000L
    private val zone = SmsParser.UAE_ZONE

    @BeforeTest fun on() { apply(file(ext = levers("wordingBanks"))) }
    @AfterTest fun off() { RulesPack.apply(null); SmsParser.knownCard = null }

    private fun chat(body: String) = SmsParser.parseAsBank("My Card Chat", body, at, zone)

    @Test fun emiratesIslamicCardPaymentFromUnknownSenderIsRead() {
        for ((body, amount, merchant) in listOf(
            Triple("Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", "88.10", "SPINNEYS LLC"),
            Triple("Pymt for CARREFOUR DUBAI of AED 172.94 has been made using Credit Card 71534.Avl.limit AED 4,120.50.", "172.94", "CARREFOUR DUBAI"),
        )) {
            val r = chat(body)
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals("Emirates Islamic", r.txn.bank)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(merchant, r.txn.merchant)
            assertEquals("1534", r.txn.cardLast4, "the 5-digit card form gives the last four")
            assertEquals("Emirates Islamic", r.attributedBank)
            assertEquals(AttributedBy.BUILTIN_WORDING, r.attributedBy)
        }
    }

    @Test fun withTheLeverOffNothingChanges() {
        RulesPack.apply(null)
        val r = chat("Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.")
        assertNotEquals("Emirates Islamic", (r as? ParseResult.Transaction)?.txn?.bank)
    }

    @Test fun aCreditedPymtIsNotReadAsAPurchase() {
        val r = chat("Pymt of AED 2,000.00 has been credited to your account XXXX1534. Avl.limit AED 4,120.50.")
        if (r is ParseResult.Transaction) assertNotEquals(TxnType.PURCHASE, r.txn.type, "got $r")
    }

    @Test fun lookAlikesAndNonBankChatsAreUnchanged() {
        for (body in listOf(
            "Payment for SPINNEYS LLC of AED 88.10 will be made using Credit Card ending with 1534 tomorrow.",
            "Your order is ready. Call us on 600500000 for help.",
            "Hey, can you send me AED 50 for the dinner? Thanks!",
        )) {
            val r = chat(body)
            assertNull((r as? ParseResult.Transaction)?.attributedBank, "got $r for: $body")
        }
        // A known sender still uses its own rules (no attribution).
        val own = SmsParser.parseAsBank("Emirates Islamic", "Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", at, zone)
        assertIs<ParseResult.Transaction>(own)
        assertNull(own.attributedBank)
        assertEquals(Decimal("88.10"), own.txn.amount)
    }

    @Test fun aWordingTwoBanksReadDifferentlyStaysInReview() {
        val body = "Card XXXX4455 used for purchase of AED 61.00 at SOME CAFE. Avl bal AED 300.00"
        val rules = listOf(
            rule("Wio", "wio-test", """Card {CARD} used for purchase of {CUR} {AMOUNT} at {MERCHANT}\. Avl""", type = "PURCHASE"),
            rule("RAKBANK", "rak-test", """Card {CARD} used for purchase of {CUR} {AMOUNT} at {MERCHANT}\. Avl""", type = "TRANSFER_OUT"),
        ).joinToString(",")
        apply(file(legacy = "\"rules\":[$rules]", ext = levers("wordingBanks")))
        val r = chat(body)
        assertIs<ParseResult.Failed>(r, "got $r")
        assertEquals(Stage.AMBIGUOUS_BANKS, r.stage)
    }

    @Test fun banksAgreeingAreSettledOnlyByAKnownCard() {
        val body = "Card XXXX4455 used for purchase of AED 61.00 at SOME CAFE. Avl bal AED 300.00"
        val rules = listOf(
            rule("Wio", "wio-test", """Card {CARD} used for purchase of {CUR} {AMOUNT} at {MERCHANT}\. Avl"""),
            rule("RAKBANK", "rak-test", """Card {CARD} used for purchase of {CUR} {AMOUNT} at {MERCHANT}\. Avl"""),
        ).joinToString(",")
        apply(file(legacy = "\"rules\":[$rules]", ext = levers("wordingBanks")))
        assertIs<ParseResult.Failed>(chat(body))
        SmsParser.knownCard = { bank, last4 -> bank == "RAKBANK" && last4 == "4455" }
        val r = chat(body)
        assertIs<ParseResult.Transaction>(r)
        assertEquals("RAKBANK", r.attributedBank)
    }

    @Test fun aWordingBanksEntryNamesTheBankOfABrandNewWording() {
        val r = rule("Emirates Islamic", "ei-new-20261124", """Card spend alert: {CUR} {AMOUNT} purchase at {MERCHANT} on your card {CARD} was approved""", cardType = "CREDIT")
        apply(file(ext = levers("wordingBanks") + ",\"rules\":[$r],\"wordingBanks\":[{\"pattern\":\"^Card spend alert: .{3,80} was approved\",\"bank\":\"Emirates Islamic\"}]"))
        val t = chat("Card spend alert: AED 45.00 purchase at GROCERY TOWN on your card XXXX1534 was approved. Avl limit AED 900.00")
        assertIs<ParseResult.Transaction>(t, "got $t")
        assertEquals("Emirates Islamic", t.txn.bank)
        assertEquals(AttributedBy.WORDING_BANKS, t.attributedBy)
        assertEquals("1534", t.txn.cardLast4)
    }
}
