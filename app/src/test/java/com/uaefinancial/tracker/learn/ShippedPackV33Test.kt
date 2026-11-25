package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PREPARED, not on main (25 Nov 2026, owner's challenge: the 2.8 build already has the levers, use them).
 * First use of `ext.rules` with the `curAbbrev` lever: a Bank of Baroda UPI debit "Rs X Dr. from A/C ... and Cr. to <payee> Ref ..."
 * (wrongly hidden as a notice on phones where the sender was added by hand). Wording invented from the masked shape.
 */
class ShippedPackV33Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val id = "pack:bob-upi-dr-cr-20261125"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 33)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun upiDebitWithRsIsReadAsTransferOutInInr() {
        for ((body, amount, card) in listOf(
            Triple("Rs.150.00 Dr. from A/C XXXXXX1234 and Cr. to shop@upi Ref:123456789012. AvlBal:Rs.900.00(2026:11:25 12:30:45). Not you? Call 18005700/5000-BOB", "150.00", "1234"),
            Triple("Rs 2,450.50 Dr. from A/C XXXXXX5678 and Cr. to paytm.s1a2b3@paytm Ref:210987654321. AvlBal:Rs 10,000.00(2026:11:25 09:01:02). Not you? Call 18005700/5000-BOB", "2450.50", "5678"),
        )) {
            val r = read("Bank of Baroda", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("INR", r.txn.currency)
            assertEquals(card, r.txn.cardLast4)
            assertEquals(id, r.ruleId)
        }
    }

    @Test fun lookAlikesAreNotReadByTheRule() {
        // a credit to the customer's own account: the rule needs "Dr. from A/C ... and Cr. to <payee>"
        val credit = read("Bank of Baroda", "Rs.500.00 Cr. to A/C XXXXXX1234 on 25-11-2026 by UPI Ref:123456789012. AvlBal:Rs.1500.00. Not you? Call 18005700/5000-BOB")
        assertTrue(credit !is ParseResult.Transaction || credit.ruleId != id, "read by the rule: $credit")
        // the same words from another bank: the rule belongs to Bank of Baroda
        val other = read("Unknown Test Bank", "Rs.150.00 Dr. from A/C XXXXXX1234 and Cr. to shop@upi Ref:123456789012. AvlBal:Rs.900.00. Not you? Call 18005700")
        assertTrue(other !is ParseResult.Transaction || other.ruleId != id, "read by the rule: $other")
    }
}
