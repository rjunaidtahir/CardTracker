package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real InvestBank wording (20 Nov 2026, card digits replaced): the merchant sits between "from" and "for AED <amount>". */
class InvestPurchaseTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)

    private fun read(s: String): ParsedTransaction {
        val r = SmartParser.read("InvestBank", SmsParser.normalizeBody(s), at, zone)
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        return r.txn
    }

    @Test fun fromMerchantForAmount() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.")
        assertEquals("AL AHLI HOSPITAL", t.merchant)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("25.00", t.amount.toString())
        assertEquals("1234", t.cardLast4)
        assertEquals(CardType.CREDIT, t.cardType)
    }

    @Test fun atMerchantForAmountStopsBeforeAmount() {
        val t = read("You have made a Purchase transaction on your Credit Card ending 1234 at CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.")
        assertEquals("CARREFOUR MALL", t.merchant)
    }

    @Test fun debitedFromAccountIsNotAMerchant() {
        val t = read("AED 50.00 was debited from your account ending 1234 on 12-Nov. Available balance AED 900.00")
        assertTrue(t.merchant != "your account" && !t.merchant.contains("1234"), t.merchant)
    }
}
