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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * v21 (23 Nov 2026, owner request): the wallet app's payment notifications ("Merchant. AED 100.00 with Visa Infinite 1234").
 * The same purchase can also arrive as the bank's SMS; the app merges an SMS and a notification of one transaction.
 * Wordings are invented from the masked shape.
 */
class ShippedPackV21Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 21)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(body: String) = SmsParser.parseAsBank("Wallet", body, received, zone)

    @Test fun walletVisaPaymentIsReadAsCreditCardPurchase() {
        for ((body, amount, merchant) in listOf(
            Triple("Abu Dhabi Police. AED 100.00 with Visa Infinite 1234", "100.00", "Abu Dhabi Police"),
            Triple("Flamingo Room by Foo Abu Dhabi. AED 1,250.50 with Visa Infinite 1234", "1250.50", "Flamingo Room by Foo Abu Dhabi"),
            Triple("Carrefour. AED 45.25 with Visa Infinite 1234", "45.25", "Carrefour"),
        )) {
            val r = read(body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:wallet-card-payment-visa-20261123", r.ruleId, body)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(CardType.CREDIT, r.txn.cardType)
            assertEquals("1234", r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(merchant, r.txn.merchant)
        }
    }

    @Test fun walletPersonalCardPaymentIsReadAsDebitCardPurchase() {
        val r = read("Abu Dhabi Police. AED 100.00 with Wio Personal 4321")
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("pack:wallet-card-payment-personal-20261123", r.ruleId)
        assertEquals(CardType.DEBIT, r.txn.cardType)
        assertEquals("4321", r.txn.cardLast4)
        assertEquals(Decimal("100.00"), r.txn.amount)
        assertEquals("Abu Dhabi Police", r.txn.merchant)
    }

    @Test fun walletLookAlikesDoNotUseTheNewRules() {
        for (body in listOf(
            "Your card Visa Infinite 1234 was added to Wallet.",
            "Enjoy 10% back. AED 100.00 with Visa Infinite 1234 when you spend this month and more offers apply",
            "Refund. AED 100.00 to Visa Infinite 1234",
        )) {
            val r = read(body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:wallet-card-payment-visa-20261123", r.ruleId, body)
        }
    }

    @Test fun walletIsKnownAsASender() {
        assertEquals("Wallet", SmsParser.bankFor("WALLET")?.name)
        assertEquals("Wallet", SmsParser.bankFor("Wallet")?.name)
    }
}
