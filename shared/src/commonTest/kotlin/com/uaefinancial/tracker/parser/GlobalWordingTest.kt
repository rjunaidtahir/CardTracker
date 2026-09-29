package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Transaction alerts written in Spanish, Portuguese, French, German and Arabic, read by the smart reader. */
class GlobalWordingTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 7, 0).toEpochMillis(zone)

    @AfterTest fun reset() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setHomeCurrency("AED")
    }

    private fun parse(body: String): ParseResult {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        return SmsParser.parse("MYBANK", body, received, zone)
    }

    private fun txn(body: String): ParsedTransaction {
        val r = parse(body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        return r.txn
    }

    private fun same(expected: String, actual: Decimal?, msg: String = "") =
        assertEquals(0, Decimal(expected).compareTo(actual ?: Decimal("-1")), "$msg: $actual")

    @Test fun spanish_purchase() {
        val t = txn("Compra de 45,00 EUR en MERCADONA con tu tarjeta terminada en 1234. Saldo disponible: 1.234,56 EUR")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("MERCADONA", t.merchant)
        same("45", t.amount)
        assertEquals("EUR", t.currency)
        assertEquals("1234", t.cardLast4)
        same("1234.56", t.availableLimit, "available")
    }

    @Test fun spanish_money_in_and_atm() {
        txn("Transferencia recibida de JUAN PEREZ por 100,00 EUR en tu cuenta terminada en 5566").let {
            assertEquals(TxnType.TRANSFER_IN, it.type)
            assertEquals("From JUAN PEREZ", it.merchant)
            assertEquals("5566", it.cardLast4)
            assertEquals(CardType.ACCOUNT, it.cardType)
        }
        txn("Retiro de efectivo de 100,00 EUR en cajero con tu tarjeta de débito terminada en 1234").let {
            assertEquals(TxnType.PURCHASE, it.type)
            assertEquals("ATM cash withdrawal", it.merchant)
            assertEquals(CardType.DEBIT, it.cardType)
        }
    }

    @Test fun declined_in_other_languages_is_not_a_transaction() {
        for (b in listOf(
            "Compra rechazada de 45,00 EUR en ZARA con tu tarjeta terminada en 1234",
            "Paiement refusé de 45,00 EUR chez FNAC avec votre carte se terminant par 1234",
            "Kartenzahlung 45,00 EUR bei REWE abgelehnt",
        )) {
            val r = parse(b)
            assertIs<ParseResult.Ignored>(r, b)
            assertEquals("Declined transaction", r.reason, b)
        }
    }

    @Test fun portuguese_purchase_and_pix() {
        txn("Compra aprovada de R$ 45,00 no cartão final 1234 em PADARIA SAO JOAO").let {
            assertEquals(TxnType.PURCHASE, it.type)
            assertEquals("PADARIA SAO JOAO", it.merchant)
            assertEquals("BRL", it.currency)
            same("45", it.amount)
            assertEquals("1234", it.cardLast4)
        }
        txn("Pix recebido de MARIA SILVA no valor de R$ 150,00 na sua conta").let {
            assertEquals(TxnType.TRANSFER_IN, it.type)
            assertEquals("From MARIA SILVA", it.merchant)
            same("150", it.amount)
        }
    }

    @Test fun french_purchase_with_date() {
        val t = txn("Paiement de 45,00 EUR chez CARREFOUR avec votre carte se terminant par 1234 le 28/09/2026")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("CARREFOUR", t.merchant)
        assertEquals("1234", t.cardLast4)
        assertTrue(t.dateFromSms)
    }

    @Test fun german_purchase_with_date_and_time_and_refund() {
        txn("Kartenzahlung 45,00 EUR bei REWE mit Karte endet auf 1234 am 28.09.2026 um 14:30").let {
            assertEquals(TxnType.PURCHASE, it.type)
            assertEquals("REWE", it.merchant)
            assertEquals("1234", it.cardLast4)
            assertEquals(DateTime.of(2026, 9, 28, 14, 30).toEpochMillis(zone), it.timestamp)
        }
        txn("Rückerstattung 20,00 EUR von AMAZON auf Karte endet auf 1234").let {
            assertEquals(TxnType.REFUND, it.type)
            assertEquals("AMAZON", it.merchant)
            same("20", it.amount)
        }
    }

    @Test fun italian_and_dutch_purchases() {
        txn("Pagamento di 45,00 EUR presso ESSELUNGA con carta che termina con 1234").let {
            assertEquals(TxnType.PURCHASE, it.type)
            assertEquals("ESSELUNGA", it.merchant)
            assertEquals("1234", it.cardLast4)
            same("45", it.amount)
        }
        txn("Betaling van 45,00 EUR bij ALBERT HEIJN met betaalpas eindigend op 1234").let {
            assertEquals(TxnType.PURCHASE, it.type)
            assertEquals("ALBERT HEIJN", it.merchant)
            assertEquals("1234", it.cardLast4)
            assertEquals(CardType.DEBIT, it.cardType)
        }
    }

    @Test fun arabic_purchase() {
        val t = txn("تمت عملية شراء بمبلغ 45.00 درهم لدى كارفور باستخدام بطاقتك المنتهية بـ 1234")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("كارفور", t.merchant)
        assertEquals("AED", t.currency)
        same("45", t.amount)
        assertEquals("1234", t.cardLast4)
    }

    @Test fun english_date_followed_by_at_time_keeps_the_time() {
        val t = txn("Your card ending 9090 was used for AED 60.00 at LULU on 21/09/2026 at 12:30.")
        assertEquals("LULU", t.merchant)
        assertEquals(DateTime.of(2026, 9, 21, 12, 30).toEpochMillis(zone), t.timestamp)
    }
}
