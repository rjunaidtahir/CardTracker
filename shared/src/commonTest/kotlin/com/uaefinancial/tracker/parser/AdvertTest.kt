package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adverts must not be recorded as spending (20 Nov 2026: 57 wordings / 172 messages of the owner's real "not a transaction" set). */
class AdvertTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
    private fun read(s: String) = SmartParser.read("Unknown Test Bank", SmsParser.normalizeBody(s), at, zone)

    @Test fun buyNowPayLaterAdvertIsNotSpending() {
        assertNull(read("*Pay now* Pay as low as AED 250.00 for the purchase of AED 1,000.00 at ZENITH BAKERY with credit card ending 1234 via Zorbo www.example.com"))
    }

    @Test fun earnCashbackOfferIsNotSpending() {
        assertNull(read("Earn AED 50 cashback when you spend AED 500 or more with your Credit Card ending 1234 before 30 Nov. T&C apply."))
    }

    @Test fun percentCashbackOfferIsNotSpending() {
        assertNull(read("Get 10% cashback on fee payments up to AED 100 with your Credit Card. To register SMS REG to 2626."))
    }

    @Test fun transferYourSalaryOfferIsNotMoneyIn() {
        assertNull(read("Transfer your salary to Zorbo Bank and increase your balance to get up to AED 500 before 31 Dec."))
    }

    @Test fun realPurchaseWithAdvertTailIsStillAPurchase() {
        val r = read("Credit Card Purchase Card No XXXXXXXX1234 AED 123.45 ZENITH BAKERY DUBAI ARE 21/09/2026 12:30 Get up to 10% cashback on online shopping with Zorbo. Visit www.example.com")
        assertTrue(r is ParseResult.Transaction, "$r")
        assertEquals(TxnType.PURCHASE, r.txn.type)
    }

    @Test fun realCashbackCreditIsStillAReading() {
        val r = read("Cashback of AED 5.00 has been credited to your card ending 1234.")
        assertTrue(r is ParseResult.Transaction, "$r")
        assertEquals(TxnType.REFUND, r.txn.type)
    }

    @Test fun ordinaryDebitIsUntouched() {
        val r = read("AED 50.00 was debited from your account ending 1234 on 12-Nov. Available balance AED 900.00")
        assertTrue(r is ParseResult.Transaction, "$r")
    }
}
