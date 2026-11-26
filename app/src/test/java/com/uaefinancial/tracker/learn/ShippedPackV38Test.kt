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
 * 26 Nov 2026 (23:23 slot). Pack v38: IDFC FIRST Bank FASTag "Rs. n toll paid from IDFC FIRST Bank Tag ... for vehicle no. ... at <plaza> on <date>"
 * (ext rule, debit from the tag account, one loose-masked report). Wordings invented from the masked shape.
 */
class ShippedPackV38Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val id = "pack:idfc-fastag-toll-paid-20261126"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 38)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun fastagTollIsReadAsDebitFromTheTag() {
        val cases = listOf(
            Triple("Rs. 85.00", "7XXX1234", "Vashi Toll Plaza"),
            Triple("INR 140.00", "6XXX9876", "Hosur Toll Plaza"),
            Triple("Rs. 1,020.00", "5XXX4455", "Khed Shivapur Toll Plaza"),
        )
        for ((amt, tag, plaza) in cases) {
            val body = "$amt toll paid from IDFC FIRST Bank Tag $tag for vehicle no. MH12GB3456 at $plaza on 25-11-2026 14:32:10. Avbl. Bal.: Rs. 1,250.50."
            val r = read("IDFC FIRST Bank", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals(id, r.ruleId, "got $r")
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(amt.substringAfter(' ').replace(",", ""), r.txn.amount.toPlainString())
            assertEquals("INR", r.txn.currency)
            assertEquals(tag.takeLast(4), r.txn.cardLast4)
            assertEquals(plaza, r.txn.merchant)
            assertTrue(r.txn.dateFromSms, "date should come from the message")
        }
    }

    @Test fun lookAlikesAreNotReadByTheNewRule() {
        for (body in listOf(
            "Rs. 85.00 credited to IDFC FIRST Bank Tag 7XXX1234 on 25-11-2026 14:32:10. Avbl. Bal.: Rs. 1,250.50.",
            "Your IDFC FIRST Bank Tag 7XXX1234 balance is low. Recharge now to avoid toll payment failure at Vashi Toll Plaza.",
            "Rs. 500.00 debited from your IDFC FIRST Bank A/c XX1234 on 25-11-2026 for UPI txn to Shop. Avbl. Bal.: Rs. 900.00.",
        )) {
            val r = read("IDFC FIRST Bank", body)
            assertTrue(!(r is ParseResult.Transaction && r.ruleId == id), "got $r for $body")
        }
        // the credit and the plain debit keep their old reading (the new rule does not touch them)
        val credit = read("IDFC FIRST Bank", "Rs. 85.00 credited to IDFC FIRST Bank Tag 7XXX1234 on 25-11-2026 14:32:10. Avbl. Bal.: Rs. 1,250.50.")
        assertTrue(credit is ParseResult.Transaction && credit.txn.type == TxnType.TRANSFER_IN, "got $credit")
        val debit = read("IDFC FIRST Bank", "Rs. 500.00 debited from your IDFC FIRST Bank A/c XX1234 on 25-11-2026 for UPI txn to Shop. Avbl. Bal.: Rs. 900.00.")
        assertTrue(debit is ParseResult.Transaction && debit.ruleId == "auto-purchase", "got $debit")
        val other = read("HDFC Bank", "Rs. 85.00 toll paid from IDFC FIRST Bank Tag 7XXX1234 for vehicle no. MH12GB3456 at Vashi Toll Plaza on 25-11-2026 14:32:10.")
        assertTrue(!(other is ParseResult.Transaction && other.ruleId == id), "other bank got $other")
    }
}
