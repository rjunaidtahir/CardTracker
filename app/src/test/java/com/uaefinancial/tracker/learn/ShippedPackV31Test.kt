package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * v31 (25 Nov 2026 loop run, 15:23 slot): one ignore entry, no reading rules, no words, no new banks.
 *  - FAB "request ... for an Easy Payment Plan for N months with a % interest rate and an AED x processing fee was approved and
 *    closed": the rate number is missing in the wording, so the v17 ignore (which needs digits before the %) never matched.
 *    Two reports (23 Nov 13:32 and 25 Nov 13:42, the second one a resend after sendMore) came back unread. Wording invented from the masked shape.
 */
class ShippedPackV31Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val label = "Notice: FAB EPP closed (no rate)"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 31)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun assertIgnoredBy(r: ParseResult) {
        assertIs<ParseResult.Ignored>(r, "got $r")
        assertEquals(label, r.reason)
    }

    private fun assertNotIgnoredBy(r: ParseResult) {
        assertTrue(r !is ParseResult.Ignored || r.reason != label, "hidden by $label: $r")
    }

    @Test fun eppClosedNoticeWithoutRateIsHidden() {
        assertIgnoredBy(read("FAB", "Dear Customer, your request 1234567890123 dated 12/11/2026 for an Easy Payment Plan for 6 months with a % interest rate and an AED 99.00 processing fee was approved and closed on 13/11/2026. For more information, please call 600525500."))
        assertIgnoredBy(read("FAB", "Dear Customer, your request 1234567890123 dated 12/11/2026 for an Easy Payment Plan for 12 months with a % interest rate and an AED 1,250.50 processing fee was approved and closed on 13/11/2026. For more information, please call 600525500."))
    }

    @Test fun eppLookAlikesAreNotHiddenByTheNewEntry() {
        // the earlier wording with a rate still goes to the v17 entry, not the new one
        assertNotIgnoredBy(read("FAB", "Dear Customer, your request 1234567890123 dated 12/11/2026 for an Easy Payment Plan for 6 months with a 1.5% interest rate and an AED 99.00 processing fee was approved and closed on 13/11/2026. For more information, please call 600525500."))
        // a real purchase / debit that mentions the plan is not hidden
        assertNotIgnoredBy(read("FAB", "Purchase of AED 99.00 at FAB EASY PAYMENT PLAN on card ending 1234 was approved. Available limit AED 5,000.00"))
        assertNotIgnoredBy(read("FAB", "Your FAB credit card purchase of AED 500.00 at CARREFOUR was converted to an Easy Payment Plan for 6 months with a % interest rate. A processing fee of AED 99.00 was debited from card ending 1234"))
        assertNotIgnoredBy(read("FAB", "Credit Card Purchase of AED 99.00 at CARREFOUR DUBAI on card ending 1234 on 13/11/2026. Available limit AED 4,000.00"))
    }

    @Test fun packCountsAfterV31() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 31)
        assertTrue(out.pack.rules.size >= 39)
        assertTrue(out.pack.ignore.size >= 54)
        assertTrue(out.pack.ignore.any { it.label == label })
        assertTrue(out.pack.ignore.all { it.label.length <= 40 })
    }
}
