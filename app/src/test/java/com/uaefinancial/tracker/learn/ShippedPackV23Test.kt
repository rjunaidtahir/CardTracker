package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.core.Decimal
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
 * v23 (23 Nov 2026 loop run, 23:23 slot): one narrow ignore entry, no new reading rule.
 *  - Telecom advance notice "you have used your Zero Credit Call. With your next recharge, the amount of ... will be deducted":
 *    reported on several days from several phones; nothing is charged by the notice itself (the real deduction comes in a
 *    separate "has been deducted for the Zero Credit Call you made previously" message, which must stay unignored).
 * Wordings are invented from the masked shapes.
 */
class ShippedPackV23Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 23)
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

    @Test fun telecomCreditCallNoticeIsIgnoredAndLookAlikesAreNot() {
        val label = "Notice: telecom credit call due"
        val re = entry(label)
        for (body in listOf(
            "Dear Customer, you have used your Zero Credit Call. With your next recharge, the amount of AED 5.25 (Inclusive of 5% VAT) will be deducted.",
            "Dear Customer, you have used your Credit Call. With your next recharge, the amount of AED 10.50 (Inclusive of 5% VAT) will be deducted",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("Unknown Test Bank", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
        }
        // Nearest look-alikes: the real deduction, and ordinary debits and purchases.
        for (body in listOf(
            "Dear Customer, AED 5.25 (Inclusive of 5% VAT) has been deducted for the Zero Credit Call you made previously.",
            "Dear Customer, you have used your Zero Credit Call. AED 5.25 has been deducted from your balance.",
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

    @Test fun packCountsAfterV23() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 23)
        assertTrue(out.pack.rules.size >= 34)
        assertTrue(out.pack.ignore.size >= 46)
    }
}
