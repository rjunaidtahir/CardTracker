package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** A message from a sender the app has no rules for is still read when its wording is a built-in bank's verified wording. */
class UnrecognisedSenderWordingTest {
    private val at = 1_795_000_000_000L
    private val zone = SmsParser.UAE_ZONE

    @Test fun emiratesIslamicCardPaymentFromUnknownSenderIsRead() {
        for ((body, amount, merchant) in listOf(
            Triple("Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", "88.10", "SPINNEYS LLC"),
            Triple("Purchase for CARREFOUR DUBAI of AED 172.94 has been made using Credit Card 71534.Avl.limit AED 4,120.50.", "172.94", "CARREFOUR DUBAI"),
        )) {
            val r = SmsParser.parseAsBank("My Card Chat", body, at, zone)
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals("Emirates Islamic", r.txn.bank)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(merchant, r.txn.merchant)
            assertEquals("1534", r.txn.cardLast4)
        }
    }

    @Test fun lookAlikesAndOtherTextAreUnchanged() {
        // Not a completed payment: no rule reads it, so it is not turned into a transaction.
        for (body in listOf(
            "Payment for SPINNEYS LLC of AED 88.10 will be made using Credit Card ending with 1534 tomorrow.",
            "Your order is ready. Call us on 600500000 for help.",
        )) {
            val r = SmsParser.parseAsBank("My Card Chat", body, at, zone)
            assertNotEquals(true, (r as? ParseResult.Transaction)?.txn?.bank == "Emirates Islamic", "got $r for: $body")
        }
        // A known sender still uses its own rules.
        val own = SmsParser.parseAsBank("Emirates Islamic", "Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.", at, zone)
        assertIs<ParseResult.Transaction>(own)
        assertEquals(Decimal("88.10"), own.txn.amount)
    }
}
