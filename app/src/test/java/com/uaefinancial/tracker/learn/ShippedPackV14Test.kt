package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** v14: the HDFC daily-balance notice is ignored; the nearest real-transaction look-alikes are not. */
class ShippedPackV14Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun hdfcBalanceNoticeIsIgnoredAndLookAlikesAreNot() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 14)
        val entry = out.pack.ignore.first { it.label == "Notice: HDFC daily balance" }
        assertTrue(entry.label.length <= 40)
        val re = Regex(entry.pattern, RegexOption.IGNORE_CASE)
        // the exact notice shape (invented values)
        assertTrue(re.containsMatchIn("Available Bal in HDFC Bank A/c XX1234 as on yesterday:21-NOV-26 is Rs. 12,345.67. Cheques are subject to realisation. For updated A/C Bal call 18002586161."))
        assertTrue(re.containsMatchIn("Available Bal in HDFC Bank A/c *5678 as on yesterday:21-NOV-26 is INR 500.00. Cheques are subject to realisation."))
        // nearest look-alikes: same bank, similar words, with an amount and a debit/credit verb are NOT ignored
        assertTrue(!re.containsMatchIn("HDFC Bank: Rs 500.00 debited from A/c XX1234 on 21-11-26 to VPA x@y. Available Bal Rs 12,000.00. Not you? Call 18002586161"))
        assertTrue(!re.containsMatchIn("Rs 5,000.00 credited to HDFC Bank A/c XX1234 on 21-11-26 by UPI. Available Bal in HDFC Bank A/c XX1234 is Rs 9,000.00"))
        assertTrue(!re.containsMatchIn("Thanks for using HDFC Bank Visa AED Card 1234 for AED 7.00 at SPINNEYS on 14-11-2026 18:02:11. Card Bal: AED 88.50."))
        // one ignore entry added in v14 (later packs may add more); no reading rule added
        assertTrue(out.pack.ignore.size >= 29)
        assertEquals(22, out.pack.rules.size)
    }
}
