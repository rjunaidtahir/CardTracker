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
 * 26 Nov 2026 (07:23 slot). Pack v35: Al Hilal "Transfer of ... to ... from account ending with ...: New balance" (ext rule,
 * transfer out) and the Mashreq low-credit-limit / over-limit-fee warning (ignore). Wordings invented from masked shapes.
 */
class ShippedPackV35Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private lateinit var pack: RulesPack.Pack

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 35)
        pack = out.pack
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun alHilalTransferOutIsRead() {
        for ((payee, amount) in listOf("AL RAYAN TRADING LLC" to "1250.50", "KHALID TRADERS" to "75.00")) {
            val body = "Transfer of AED $amount to $payee from account ending with 1234. New balance: AED 5000.00."
            val r = read("Al Hilal", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals("pack:alhilal-transfer-out-20261126", r.ruleId, "got $r")
            assertEquals(TxnType.TRANSFER_OUT, r.txn.type); assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency); assertEquals("1234", r.txn.cardLast4)
            assertTrue(r.txn.merchant.contains(payee.split(" ").first(), ignoreCase = true), "merchant was ${r.txn.merchant}")
        }
    }

    @Test fun alHilalLookAlikesAreNotReadByTheNewRule() {
        for (body in listOf(
            "Transfer of AED 500.00 from KHALID TRADERS to account ending with 1234. New balance: AED 5000.00.",
            "Transfer of AED 500.00 to KHALID TRADERS from account ending with 1234 failed. New balance: AED 5000.00.",
            "Your transfer limit is now AED 5000.00 to KHALID TRADERS from account ending with 1234.",
        )) {
            val r = read("Al Hilal", body)
            assertTrue(!(r is ParseResult.Transaction && r.ruleId == "pack:alhilal-transfer-out-20261126"), "got $r for $body")
        }
        val other = read("Mashreq", "Transfer of AED 500.00 to KHALID TRADERS from account ending with 1234. New balance: AED 5000.00.")
        assertTrue(!(other is ParseResult.Transaction && other.ruleId == "pack:alhilal-transfer-out-20261126"), "other bank got $other")
    }

    @Test fun mashreqLowLimitNoticeIsIgnoredAndLookAlikesAreNot() {
        val entry = pack.ignore.first { it.label == "Notice: Mashreq low credit limit" }
        assertTrue(entry.label.length <= 40)
        val re = Regex(entry.pattern, RegexOption.IGNORE_CASE)
        assertTrue(re.containsMatchIn("Dear Customer, less than 20% credit limit is available on your Mashreq Credit Card(s). Exceeding your limit may lead to an Over Limit Fee of AED 100. If you need help managing your debt, Visit www.example.com Stay informed."))
        assertTrue(re.containsMatchIn("Dear Customer, only 10% credit limit is available on your Mashreq Credit Card(s). Exceeding your limit may lead to an Over Limit Fee of AED 100."))
        // nearest real-transaction look-alikes are NOT ignored
        assertTrue(!re.containsMatchIn("Thank you for using your Mashreq Credit Card ending 1234 for AED 100.00 at SPINNEYS on 14-11-2026 18:02. Avl.Limit: AED 9,000.00"))
        assertTrue(!re.containsMatchIn("An Over Limit Fee of AED 100.00 has been charged to your Mashreq Credit Card ending 1234 on 14-11-2026."))
        assertTrue(!re.containsMatchIn("AED 100.00 has been credited to your Mashreq Credit Card ending 1234. 20% credit limit is now available."))
        // the card purchase still reads (ignore did not swallow it)
        val r = read("Mashreq", "Thank you for using your Mashreq Credit Card ending 1234 for AED 100.00 at SPINNEYS on 14-11-2026 18:02. Avl.Limit: AED 9,000.00")
        assertTrue(r !is ParseResult.Ignored, "got $r")
    }
}
