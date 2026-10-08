package com.uaefinancial.tracker.ai

import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneAiTest {
    private val body = "Your Wio card ending 4821 was used for AED 1,250.50 at CARREFOUR MALL on 05/10. Available balance AED 9,000.00"

    @Test fun accepts_an_answer_the_message_backs_up() {
        val g = PhoneAi.parse(
            """Sure! {"type":"purchase","amount":1250.5,"currency":"AED","merchant":"CARREFOUR MALL","last4":"4821","account":"debit"}""", body,
        )!!
        assertEquals(TxnType.PURCHASE, g.type)
        assertEquals(0, BigDecimal("1250.50").compareTo(g.amount))
        assertEquals("AED", g.currency)
        assertEquals("CARREFOUR MALL", g.merchant)
        assertEquals("4821", g.cardLast4)
        assertEquals(CardType.DEBIT, g.cardType)
    }

    @Test fun rejects_an_amount_that_is_not_in_the_message() {
        assertNull(PhoneAi.parse("""{"type":"purchase","amount":999,"currency":"AED"}""", body))
    }

    @Test fun drops_invented_details_but_keeps_the_rest() {
        val g = PhoneAi.parse("""{"type":"purchase","amount":"1,250.50","currency":"USD","merchant":"Amazon","last4":"1111"}""", body)!!
        assertNull(g.currency)
        assertEquals("", g.merchant)
        assertNull(g.cardLast4)
    }

    @Test fun rejects_garbage_and_unknown_types() {
        assertNull(PhoneAi.parse("I cannot help with that", body))
        assertNull(PhoneAi.parse("""{"type":"gift","amount":1250.50}""", body))
        assertNull(PhoneAi.parse("""{"type":"purchase","amount":-5}""", body))
    }
}
