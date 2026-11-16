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

    @Test fun card_purchase_multiline() {
        val t = txn("EI SMS", "Credit Card Purchase\nCard Ending: 1534\nAt: carrefouruae.com, Dubai\nAmount: AED 172.94\nDate: 01/09/2026, 20:03\nAvailable Limit: AED -1,270.88")
        assertEquals("Emirates Islamic", t.bank)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(CardType.CREDIT, t.cardType)
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
