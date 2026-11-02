package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FAB pair for one transfer. The 1,992.92 pair is the real text from the person's phone. The 1,805 message uses the
 * same wording (the real text of that one has not been supplied yet), so it stands for "a standing-instruction message
 * with no debit message". What matters for the gap logic: the debit message names the account and has a balance, the
 * standing-instruction message names the payee and the date but neither the account nor a balance.
 */
class FabStandingPairTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 1, 20, 0).toEpochMillis(zone)

    @AfterTest fun reset() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setHomeCurrency("AED")
    }

    private val debit = "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1992.92\nDate 01/11/2026\nBalance AED 11662.18"
    private val standing = "Dear Customer, your Domestic Fund transfer standing instructions of AED 1992.92 to Junaid Mashreq CC XXXX4680 has been processed on 01/11/2026."
    private val standingOnly = "Dear Customer, your Domestic Fund transfer standing instructions of AED 1805 to ENBD ANNUM XXXX7701 has been processed on 01/11/2026."

    private fun txn(body: String): ParseResult.Transaction {
        val r = SmsParser.parse("FAB", body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction for: $body")
        return r
    }

    @Test fun the_debit_message_names_the_account_and_carries_the_balance() {
        val r = txn(debit)
        assertEquals("8001", r.txn.cardLast4)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
        assertNotNull(r.txn.availableLimit)
        assertEquals(false, BankRules.ruleById(r.ruleId)!!.namesDestination)
    }

    @Test fun the_standing_instruction_names_the_payee_but_not_the_account_and_has_no_balance() {
        val r = txn(standing)
        assertNull(r.txn.cardLast4)
        assertTrue(r.txn.accountNotNamed)
        assertNull(r.txn.availableLimit)
        assertEquals("4680", r.txn.toLast4)
        assertEquals("fab-standing-instruction", r.ruleId)
        assertEquals("fab-transfer", BankRules.ruleById(r.ruleId)!!.pairGroup)
    }

    @Test fun the_two_halves_are_in_the_same_pair_group_so_they_merge() {
        val a = BankRules.ruleById(txn(debit).ruleId)!!
        val b = BankRules.ruleById(txn(standing).ruleId)!!
        assertEquals(a.pairGroup, b.pairGroup)
        assertTrue(a.namesDestination != b.namesDestination)
    }

    @Test fun a_standing_instruction_on_its_own_reads_as_the_same_kind_of_message() {
        val r = txn(standingOnly)
        assertEquals("fab-standing-instruction", r.ruleId)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
        assertEquals(0, com.uaefinancial.tracker.core.Decimal("1805").compareTo(r.txn.amount))
        assertEquals("7701", r.txn.toLast4)
    }
}
