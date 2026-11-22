package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * v15: three notices that move no money are ignored (HDFC average-balance notice, CRED money request,
 * HDFC "Amt Due ... Pay with PayZapp" reminder with no due date). The nearest real-transaction
 * look-alikes (same bank, similar words, amount and a debit/credit verb) are NOT ignored.
 */
class ShippedPackV15Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    private fun entry(label: String): Regex {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 15)
        val e = out.pack.ignore.first { it.label == label }
        assertTrue(e.label.length <= 40)
        return Regex(e.pattern, RegexOption.IGNORE_CASE)
    }

    @Test fun hdfcAvgBalanceNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: HDFC avg balance maintenance")
        assertTrue(re.containsMatchIn("Alert! HDFC Bank A/c XX1234 Avg. bal. maintained as on 31-OCT-26: Rs 10,000.00 Avoid non-maintenance charges by maintaining Avg. bal. of Rs 25,000.00 in OCT-26."))
        assertTrue(re.containsMatchIn("Alert! HDFC Bank A/c *1234 Avg. bal. maintained as on 30-SEP-26: INR 8,000.00 Avoid non-maintenance charges by maintaining Avg. bal. of INR 25,000.00 in JUL-SEP"))
        assertTrue(!re.containsMatchIn("Rs 5,000.00 debited from HDFC Bank A/c XX1234 on 21-11-26. Avg. bal. maintained Rs 9,000.00. Avoid fraud, call 18002586161"))
        assertTrue(!re.containsMatchIn("Rs 5,000.00 credited to HDFC Bank A/c XX1234 on 21-11-26 by UPI. Avl Bal Rs 9,000.00. Avoid non-maintenance charges"))
        assertTrue(!re.containsMatchIn("Thanks for using HDFC Bank Visa AED Card 1234 for AED 7.00 at SPINNEYS on 14-11-2026 18:02:11. Card Bal: AED 88.50."))
    }

    @Test fun credMoneyRequestIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Payment request: CRED approval")
        assertTrue(re.containsMatchIn("PHARMEASY has requested money from you on CRED. On approving the request, Rs 499.00 will be debited from your account. Click here to approve: https://x.y/z"))
        assertTrue(re.containsMatchIn("Amazon Pay Groceries has requested money from you on CRED. On approving the request, INR 120.00 will be debited from your account."))
        assertTrue(!re.containsMatchIn("Rs 499.00 debited from HDFC Bank A/c XX1234 to CRED on 21-11-26. Avl Bal Rs 9,000.00"))
        assertTrue(!re.containsMatchIn("Rs 499.00 credited to HDFC Bank A/c XX1234 by CRED Club on 21-11-26 by UPI."))
        // the older UPI wording is handled by its own entry, not this one
        assertTrue(!re.containsMatchIn("Sender Name has requested money from you on your HDFC UPI. AED 100.00 will be debited from your account, on approving the request."))
    }

    @Test fun hdfcAmtDueReminderIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Reminder: HDFC amount due (PayZapp)")
        assertTrue(re.containsMatchIn("Amt Due: Rs. 1,234.00 on HDFC Bank Credit Card 1234. Pay Instant with PayZapp. Steps: PayZapp-Bill-Credit Card Pay Now: https://x.y"))
        assertTrue(re.containsMatchIn("Dear Customer, Amt Due Rs 1234.00 on HDFC Bank Card X1234 Pay with PayZapp, your safe Bank zone. Steps: PayZapp-Bill-Credit Card Pay Now: https://x.y"))
        // a statement with a due date, and a purchase that mentions the amount due, must NOT be ignored
        assertTrue(!re.containsMatchIn("HDFC Bank Credit Card XX1234 Statement: Total Amt Due Rs 1,234.00, Min Amt Due Rs 100.00, Due Date 05-DEC-26. Pay with PayZapp"))
        assertTrue(!re.containsMatchIn("Rs 1,234.00 debited from HDFC Bank Credit Card X1234 at AMAZON on 21-11-26. Amt Due Rs 5,000.00. Pay with PayZapp"))
        assertTrue(!re.containsMatchIn("Thanks for using HDFC Bank Visa AED Card 1234 for AED 7.00 at SPINNEYS on 14-11-2026 18:02:11. Card Bal: AED 88.50."))
    }

    @Test fun packCountsAfterV15() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        // three ignore entries added to the 29 of v14; no reading rule added
        assertTrue(out.pack.ignore.size >= 32)
        assertTrue(out.pack.rules.size >= 22)
    }
}
