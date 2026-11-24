package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v24 (24 Nov 2026 loop run, 07:23 slot): one narrow ignore entry, no new reading rule.
 *  - Tabby "Not enough available balance. To shop at X for AED n, make a payment to increase your Tabby Card limit":
 *    a declined purchase, 3 reports (3 merchants, separate timestamps). Wordings are invented from the masked shapes.
 */
class ShippedPackV24Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 24)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun entry(label: String): Regex {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out)
        val e = out.pack.ignore.first { it.label == label }
        assertTrue(e.label.length <= 40)
        return Regex(e.pattern, RegexOption.IGNORE_CASE)
    }

    @Test fun tabbyDeclinedNoticeIsIgnoredAndLookAlikesAreNot() {
        val label = "Declined: Tabby card limit too low"
        val re = entry(label)
        for (body in listOf(
            "Not enough available balance. To shop at Excellence Driving Cen for AED 450.00, make a payment to increase your Tabby Card limit https://tabby.ai/x",
            "Not enough available balance. To shop at www.shop.com for AED 99.50, make a payment to increase your Tabby Card limit https://tabby.ai/x",
            "Not enough available balance. To shop at Namshi for AED 1,200.00, make a payment to increase your Tabby Card limit https://tabby.ai/x",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("Tabby", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
        }
        // Nearest look-alikes (same sender, same family of words, money words) must not be ignored by this entry.
        for (body in listOf(
            "Order of AED 120.00 from Namshi is confirmed. Track your upcoming payments with the Tabby app: https://tabby.ai/x",
            "Tabby Card total limit is now increased to AED 5000",
            "Your Tabby Card ending 1234 was used for AED 45.50 at NAMSHI on 03-10-2026. Avl bal AED 9,876.00",
            "AED 100.00 debited from your account XXXX1234 on 12/10/2026",
        )) {
            assertTrue(!re.containsMatchIn(body), body)
            val r = read("Unknown Test Bank", body)
            if (r is ParseResult.Ignored) assertTrue(r.reason != label, body)
        }
        val purchase = read("Unknown Test Bank", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals(Decimal("45.50"), purchase.txn.amount)
    }

    @Test fun packCountsAfterV24() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 24)
        assertTrue(out.pack.rules.size >= 34)
        assertTrue(out.pack.ignore.size >= 47)
    }
}
