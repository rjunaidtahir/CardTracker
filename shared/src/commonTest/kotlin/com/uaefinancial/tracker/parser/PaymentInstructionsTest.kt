package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 21 Nov 2026 export: 62 FAB "payment instructions ... to <card number>" messages were read as purchases by the generic reader. */
class PaymentInstructionsTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
    private fun read(s: String) = SmartParser.read("Unknown Test Bank", SmsParser.normalizeBody(s), at, zone)

    @Test fun paymentToACardNumberIsMoneyOutOfAnAccount() {
        for (t in listOf(
            "Dear Customer, Your payment instructions of AED 500.00 to 5425********0831 has been processed on 15/09/2026 06:24",
            "Dear Customer, Your payment instructions of AED 1,250.50 to 5425XXXXXXXX0831 has been processed on 15/09/2026 06:24",
        )) {
            val r = read(t)
            assertTrue(r is ParseResult.Transaction, "not read: $t")
            assertEquals(TxnType.TRANSFER_OUT, r.txn.type, t)
            assertEquals(CardType.ACCOUNT, r.txn.cardType, t)
        }
    }

    @Test fun billToANamedCompanyStaysAPurchase() {
        val r = read("Dear Customer, Your payment instructions of AED 313.95 to HomeInternet for consumer number 045923079 has been processed on 15/09/2026 14:23")
        assertTrue(r is ParseResult.Transaction)
        assertEquals(TxnType.PURCHASE, r.txn.type)
    }
}
