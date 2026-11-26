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
 * 26 Nov 2026 (11:23 slot). Pack v37: ADIB "Cheque no. ... for AED ... was cleared from your a/c ...: ... account balance"
 * (ext rule, account transfer out, merchant "Cheque"). Wordings invented from the masked shape.
 */
class ShippedPackV37Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 37)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun adibChequeClearedIsRead() {
        for ((amount, acct, bal) in listOf(Triple("1500.00", "12345678", "812.40"), Triple("320.50", "87654321", "10412.00"))) {
            val body = "Dear Customer, Cheque no. 123456 for AED $amount was cleared from your a/c $acct. And your account balance is $bal"
            val r = read("ADIB", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals("pack:adib-cheque-cleared-20261126", r.ruleId, "got $r")
            assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency)
            assertEquals(acct.takeLast(4), r.txn.cardLast4)
            assertTrue(r.txn.merchant.contains("Cheque", ignoreCase = true), "merchant was ${r.txn.merchant}")
        }
    }

    @Test fun adibChequeLookAlikesAreNotReadByTheNewRule() {
        for (body in listOf(
            "Dear Customer, Cheque no. 123456 for AED 1500.00 was deposited to your a/c 12345678. And your account balance is 812.40",
            "Dear Customer, Cheque no. 123456 for AED 1500.00 was returned from your a/c 12345678. And your account balance is 812.40",
            "Dear Customer, Cheque no. 123456 for AED 1500.00 was cleared to your a/c 12345678. And your account balance is 812.40",
        )) {
            val r = read("ADIB", body)
            assertTrue(!(r is ParseResult.Transaction && r.ruleId == "pack:adib-cheque-cleared-20261126"), "got $r for $body")
        }
        val other = read("Mashreq", "Dear Customer, Cheque no. 123456 for AED 1500.00 was cleared from your a/c 12345678. And your account balance is 812.40")
        assertTrue(!(other is ParseResult.Transaction && other.ruleId == "pack:adib-cheque-cleared-20261126"), "other bank got $other")
    }
}
