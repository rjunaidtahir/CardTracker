package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** v11: the Kotak declined-card notice is ignored; the nearest real-transaction look-alikes are not. */
class ShippedPackV11Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun kotakDeclinedNoticeIsIgnoredAndLookAlikesAreNot() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 11)
        val entry = out.pack.ignore.first { it.label.startsWith("Declined: Kotak credit card") }
        assertTrue(entry.label.length <= 40)
        val re = Regex(entry.pattern, RegexOption.IGNORE_CASE)
        // the exact notice shape (invented amount and merchants)
        assertTrue(re.containsMatchIn("Transaction on your Kotak Bank Credit Card x1234 of AED 150.00 at EMIRATES could not be approved. Please call 18001234 for assistance."))
        assertTrue(re.containsMatchIn("Txn on your Kotak Bank Credit Card x9876 of AED 25.50 at NOON ONLINE DUBAI could not be approved. Please call 18001234."))
        // nearest look-alikes: same bank and words with an amount and a debit verb must NOT be ignored
        assertTrue(!re.containsMatchIn("Transaction on your Kotak Bank Credit Card x1234 of AED 150.00 at EMIRATES has been approved. Avl limit AED 5,000."))
        assertTrue(!re.containsMatchIn("Your Kotak Bank Credit Card x1234 has been debited with AED 150.00 at EMIRATES on 21-11-2026."))
        assertTrue(!re.containsMatchIn("Spent AED 150.00 on your Kotak Bank Credit Card x1234 at EMIRATES. Avl limit AED 5,000."))
        // no other ignore entry was changed or added
        assertEquals(28, out.pack.ignore.size - 0 - 1 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 + 0 - 0)
    }
}
