package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Messages built from the masked shapes testers sent in (Oct 2026). The shapes keep the structure and hide the
 * wording, so the verbs below are plausible stand-ins: the point is that the reader copes with whatever the verb is.
 */
class TesterShapesTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 10, 12, 15, 0).toEpochMillis(zone)

    @AfterTest fun reset() = SmsParser.setCustomSenders(emptyMap())

    private fun read(body: String): ParseResult {
        SmsParser.setCustomSenders(mapOf("PKBANK" to "Some Bank"))
        return SmsParser.parse("PKBANK", body, received, zone)
    }

    private fun txn(body: String): ParsedTransaction {
        val r = read(body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r")
        return r.txn
    }

    @Test fun transfer_out_whatever_the_verb_is() {
        for (verb in listOf("initiated", "made", "placed", "completed")) {
            val t = txn("Dear Customer, You have $verb an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 through Mobile App on 12-Oct-2026, 14:55.")
            assertEquals(TxnType.TRANSFER_OUT, t.type, verb)
            assertEquals(Decimal("5000.00"), t.amount, verb)
            assertEquals("PKR", t.currency, verb)
            assertEquals("7890", t.cardLast4, verb)
        }
    }

    @Test fun transfer_out_to_a_named_bank() {
        val t = txn("Dear Customer, You have made an IBFT of PKR 12,500.00 from A/C 1234****7890 to 55001122 Some Bank Limited through Mobile on 13-Oct-2026 , 04:50.")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals(Decimal("12500.00"), t.amount)
    }

    @Test fun transfer_from_iban() {
        val t = txn("Dear Customer, you have made a Raast Transfer of PKR 2,000.00 from IBAN PK36SCBL0000001123456702 through App to Ali on 13-Oct-2026 at 06:20. For queries, please call: (021-111) 111111")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals(Decimal("2000.00"), t.amount)
    }

    @Test fun reversal_of_a_failed_transfer_is_money_back() {
        val t = txn("Dear Customer, Debit to your account for failed IBFT transaction has been reversed and PKR 500.00 credited to your account 1234-5678-******-12-34. Helpline 111222333")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        assertEquals(Decimal("500.00"), t.amount)
    }

    @Test fun insufficient_balance_is_not_a_transaction() {
        val r = read("Dear Customer, Your IBFT transaction of PKR 9,000.00 could not be processed due to insufficient balance in your account. For assistance please call 111222333")
        assertIs<ParseResult.Ignored>(r)
    }

    @Test fun emirates_id_request_is_not_a_transaction() {
        val r = read("Dear Customer, your Emirates NBD Credit Card is ready for you! Please pay AED 105.00 & send the Emirates ID copy of the cardholder to help@example.com from your registered email ID.")
        assertIs<ParseResult.Ignored>(r)
    }

    @Test fun credit_with_a_fee_note_is_money_in() {
        val t = txn("Your account has been successfully credited with AED 1,000.00. This amount includes AED 5.00 fee will be deducted from your balance")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        assertEquals(Decimal("1000.00"), t.amount)
    }
}
