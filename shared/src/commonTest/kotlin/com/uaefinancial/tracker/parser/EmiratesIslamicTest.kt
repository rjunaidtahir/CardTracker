package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Emirates Islamic card alerts as they really arrive: sender "EI SMS" (shape taken from published SMS-format samples). */
class EmiratesIslamicTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 1, 20, 5).toEpochMillis(zone)

    private fun txn(sender: String, body: String): ParsedTransaction {
        val r = SmsParser.parse(sender, body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r")
        return r.txn
    }

    @Test fun sender_ei_sms_is_recognised_as_emirates_islamic() {
        for (s in listOf("EI SMS", "ei sms", "EISMS", "AD-EISMS")) {
            assertEquals("Emirates Islamic", SmsParser.bankFor(s)?.name, "sender '$s'")
        }
    }

    // Real shapes from users' reports (Fils shape reports); words the reports mask are filled with the likely wording.
    @Test fun purchase_one_line_5_digit_card() {
        val t = txn("EISMS", "Purchase for CARREFOUR DUBAI of AED 172.94 has been made using Credit Card 71534.Avl.limit AED 4,120.50.")
        assertEquals("Emirates Islamic", t.bank)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(CardType.CREDIT, t.cardType)
        assertEquals("1534", t.cardLast4)
        assertEquals(Decimal("172.94"), t.amount)
        assertEquals("CARREFOUR DUBAI", t.merchant)
        assertEquals(Decimal("4120.50"), t.availableLimit)
    }

    @Test fun payment_for_merchant_ending_with() {
        val t = txn("EISMS", "Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("1534", t.cardLast4)
        assertEquals(Decimal("88.10"), t.amount)
        assertEquals("SPINNEYS LLC", t.merchant)
        assertEquals(Decimal("3900.00"), t.availableLimit)
    }

    @Test fun merchant_containing_of_and_a_url() {
        val a = txn("EISMS", "Payment for BANK OF SHARJAH SHOP of AED 20.00 has been made using Credit Card ending with 1534. Available limit AED 100.00.")
        assertEquals("BANK OF SHARJAH SHOP", a.merchant)
        val b = txn("EISMS", "Purchase for WWW.ADNOCDISTRIBUTION.AE of AED 120.00 has been made using Credit Card 71534.Avl.limit AED 3,759.00.")
        assertEquals("WWW.ADNOCDISTRIBUTION.AE", b.merchant)
        assertEquals(Decimal("120.00"), b.amount)
    }

    @Test fun multiline_card_purchase_from_public_samples() {
        val t = txn("EI SMS", "Credit Card Purchase\nCard Ending: 1534\nAt: carrefouruae.com, Dubai\nAmount: AED 172.94\nDate: 01/09/2026, 20:03\nAvailable Limit: AED -1,270.88")
        assertEquals("Emirates Islamic", t.bank)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("1534", t.cardLast4)
        assertEquals(Decimal("172.94"), t.amount)
        assertTrue(t.merchant.contains("carrefouruae", ignoreCase = true), "merchant was '${t.merchant}'")
        assertEquals(DateTime.of(2026, 9, 1, 20, 3).toEpochMillis(zone), t.timestamp)
        assertEquals(Decimal("-1270.88"), t.availableLimit)
    }

    @Test fun credit_to_card_is_a_refund() {
        val t = txn("EI SMS", "Dear Customer, Amount of AED 44.99 from Amazon.ae has been credited to your card ending 6268. Available Limit is AED 17,418.16.")
        assertEquals("Emirates Islamic", t.bank)
        assertEquals(TxnType.REFUND, t.type)
        assertEquals("6268", t.cardLast4)
        assertEquals(Decimal("44.99"), t.amount)
        assertNotNull(t.availableLimit)
        assertEquals(Decimal("17418.16"), t.availableLimit)
    }
}
