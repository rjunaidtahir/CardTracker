package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Standing orders that have run, and the messages FAB sends around them. A topic word like "standing instruction" must
 * not throw away a message that says money moved; setting up or cancelling one is still not a transaction.
 */
class StandingInstructionTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 20, 24).toEpochMillis(zone)

    @AfterTest fun reset() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setHomeCurrency("AED")
    }

    private fun txn(sender: String, body: String): ParsedTransaction {
        val r = SmsParser.parse(sender, body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        return r.txn
    }

    private fun same(expected: String, actual: Decimal?, msg: String = "") =
        assertEquals(0, Decimal(expected).compareTo(actual ?: Decimal("-1")), "$msg: $actual")

    @Test fun fab_standing_instruction_with_a_payee_name_is_a_transfer_out() {
        val t = txn(
            "FAB",
            "Dear Customer, your Domestic Fund transfer standing instructions of AED 915.32 to RAK Annum XXXX0552 has been processed on 29/09/2026.",
        )
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        same("915.32", t.amount)
        assertEquals("AED", t.currency)
        assertEquals("0552", t.toLast4)
        assertTrue(t.merchant.contains("RAK", ignoreCase = true), t.merchant)
        assertTrue(t.accountNotNamed)
        assertTrue(t.dateFromSms)
    }

    @Test fun fab_standing_instruction_to_another_bank() {
        val t = txn(
            "FAB",
            "Dear Customer, your Domestic Fund transfer standing instructions of AED 500 to ENBD ANNUM XXXX7701 has been processed on 29/09/2026.",
        )
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        same("500", t.amount)
        assertEquals("7701", t.toLast4)
    }

    @Test fun fab_standing_instruction_without_a_payee_name() {
        val t = txn("FAB", "Dear Customer, your standing instruction of AED 300.00 to XXXX1234 has been processed on 29/09/2026.")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        same("300", t.amount)
        assertEquals("1234", t.toLast4)
    }

    @Test fun fab_outward_remittance_spread_over_lines() {
        val t = txn(
            "FAB",
            "Outward Remittance\nDebit\nAccount XXXX8001\nAED 915.32\nDate 29/09/2026\nBalance AED 18669.21",
        )
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals("8001", t.cardLast4)
        same("915.32", t.amount)
        same("18669.21", t.availableLimit, "balance")
    }

    @Test fun fab_account_debit_with_a_lower_case_balance() {
        val t = txn(
            "FAB",
            "An amount of AED 4810.00 has been debited from your FAB account XXXX8001 on 30/09/2026. Your balance is AED 13259.40",
        )
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals("8001", t.cardLast4)
        same("4810", t.amount)
        same("13259.40", t.availableLimit, "balance")
        assertTrue(t.dateFromSms)
    }

    @Test fun setting_up_or_cancelling_a_standing_instruction_is_still_not_a_transaction() {
        for (b in listOf(
            "Dear Customer, your standing instruction of AED 500.0 has been scheduled. Transfer of AED 500.0 will be done on 28/09/2026",
            "Dear Customer, you have deregistered your standing instruction to XXXX7701",
            "Your standing instruction for AED 500 to XXXX7701 has been registered successfully",
        )) {
            val r = SmsParser.parse("FAB", b, received, zone)
            assertIs<ParseResult.Ignored>(r, b)
        }
    }

    @Test fun another_banks_executed_standing_order_is_never_silently_dropped() {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        val b = "Your standing instruction of AED 300.00 to Salma XXXX1234 has been processed on 29/09/2026"
        val r = SmsParser.parse("MYBANK", b, received, zone)
        // Read as a transaction, or at worst listed in Needs review: never quietly ignored.
        assertTrue(r is ParseResult.Transaction || r is ParseResult.Failed, "Silently ignored: $r")
    }

    @Test fun a_completed_debit_that_only_mentions_standing_instruction_is_not_ignored() {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        val b = "AED 250.00 has been debited from your account XXXX8001 as per your standing instruction on 29/09/2026"
        val r = SmsParser.parse("MYBANK", b, received, zone)
        assertTrue(r is ParseResult.Transaction || r is ParseResult.Failed, "Silently ignored: $r")
    }

    @Test fun engine_version_moved_so_stored_messages_are_read_again() {
        assertTrue(SmsParser.ENGINE_VERSION >= 4)
    }
}
