package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A cashback offer tacked on to a purchase message is an advert, not a refund (found on 20 Nov 2026: 23 FAB wordings / 49 messages). */
class CashbackOfferTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)

    private fun read(bank: String, s: String): ParsedTransaction {
        val r = SmartParser.read(bank, SmsParser.normalizeBody(s), at, zone)
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        return r.txn
    }

    @Test fun purchaseWithCashbackAdvertStaysAPurchase() {
        val t = read("Unknown Test Bank", "Credit Card Purchase Card No XXXXXXXX1234 AED 123.45 ZENITH BAKERY DUBAI ARE 21/09/2026 12:30Your May statement payment due date is 25/10/2026 Get up to 10% cashback on online shopping with Zorbo. Visit www.example.com on the FAB Mobile app")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("123.45", t.amount.toString())
    }

    @Test fun percentCashbackAdvertWithoutAnyVerb() {
        val t = read("Unknown Test Bank", "Credit Card Purchase Card No XXXXXXXX1234 AED 80.00 ZENITH BAKERY DUBAI ARE 21/09/2026 12:30 up to 5% cashback on online shopping with Zorbo.")
        assertEquals(TxnType.PURCHASE, t.type)
    }

    @Test fun realCashbackCreditIsStillMoneyBack() {
        val t = read("Unknown Test Bank", "Cashback of AED 5.00 has been credited to your card ending 1234.")
        assertEquals(TxnType.REFUND, t.type)
        assertEquals("5.00", t.amount.toString())
    }

    @Test fun realRefundIsStillARefund() {
        val t = read("Unknown Test Bank", "A refund of AED 40.00 from ZENITH BAKERY has been credited to your card ending 1234.")
        assertEquals(TxnType.REFUND, t.type)
    }
}
