package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Mashreq "Thank you for ..." purchase alerts: shapes from users' reports (Fils shape reports, Nov 2026). */
class MashreqThankYouTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 15, 12, 31).toEpochMillis(zone)

    private fun txn(body: String): ParsedTransaction {
        val r = SmsParser.parse("Mashreq", body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        return r.txn
    }

    private fun check(body: String, card: String, amount: String, merchant: String, type: CardType, limit: String) {
        val t = txn(body)
        assertEquals("Mashreq", t.bank)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(type, t.cardType, body)
        assertEquals(card, t.cardLast4, body)
        assertEquals(Decimal(amount), t.amount, body)
        assertEquals(merchant, t.merchant, body)
        assertEquals(Decimal(limit), t.availableLimit, body)
        assertEquals(false, t.auto, "should be a rule match, not the generic reader: $body")
    }

    @Test fun your_card_avl_limit_colon() = check(
        "Thank you for using your card ending 1234 for AED 45.50 at CARREFOUR LLC on 15-NOV-2026 12:30 PM. Avl.Limit: AED 5,000.00",
        "1234", "45.50", "CARREFOUR LLC", CardType.CREDIT, "5000.00")

    @Test fun your_card_avl_limit_is() = check(
        "Thank you for using your card ending 1234 for AED 45.50 at CARREFOUR LLC on 15-NOV-2026 12:30 PM. Avl.Limit is AED 5,000.00.",
        "1234", "45.50", "CARREFOUR LLC", CardType.CREDIT, "5000.00")

    @Test fun cashback_card_name() = check(
        "Thank you for using your Mashreq Cashback Card ending 1234 for AED 45.50 at CARREFOUR on 15-NOV-2026 12:30 PM. Avl.Limit is AED 5,000.00",
        "1234", "45.50", "CARREFOUR", CardType.CREDIT, "5000.00")

    @Test fun cashback_credit_card_card_ending() = check(
        "Thank you for using your Cashback Credit Card Card ending 1234 for AED 45.50 at DUBAI MARINA on 15-NOV-2026 12:30 PM. Avl.Limit is AED 5,000.00.",
        "1234", "45.50", "DUBAI MARINA", CardType.CREDIT, "5000.00")

    @Test fun visa_platinum_available_balance() = check(
        "Thank you for using your VISA Platinum Card ending 1234 for AED 45.50 at DUBAI MALL on 15-NOV-2026 12:30 PM. Available Balance is AED 5,000.00",
        "1234", "45.50", "DUBAI MALL", CardType.CREDIT, "5000.00")

    @Test fun visa_debit_card_is_debit() = check(
        "Thank you for using your VISA Debit Card Card ending 1234 for AED 45.50 at SPINNEYS LLC on 15-NOV-2026 12:30 PM. Available Balance is AED 5,000.00",
        "1234", "45.50", "SPINNEYS LLC", CardType.DEBIT, "5000.00")

    @Test fun debit_card_avl_bal() = check(
        "Thank You for using your Debit Card ending 1234 for AED 45.50 at SHOP 4 ,DUBAI ,AE on 15-NOV-2026 12:30 PM. Avl. Bal is AED 5,000.00.",
        "1234", "45.50", "SHOP 4 ,DUBAI ,AE", CardType.DEBIT, "5000.00")

    @Test fun plain_card_avl_limit_is_with_space() = check(
        "Thank You for using Card ending 1234 for AED 45.50 at SHOP ,Dubai ,AE on 15-NOV-2026 12:30 PM. Avl. limit is AED 5,000.00.",
        "1234", "45.50", "SHOP ,Dubai ,AE", CardType.CREDIT, "5000.00")
}
