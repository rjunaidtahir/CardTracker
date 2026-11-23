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
 * v22 (23 Nov 2026 loop run, 19:23 slot): two narrow ignore entries, no new reading rule.
 *  - ADCB debit card transaction that FAILED ("... txn at <merchant> ... from Debit Card 1234 failed due to <reason>"): many reports of the
 *    shape, nothing is charged.
 *  - An advert for a savings prize draw ("boost your chances to win ... Teslas"): 4 reports, no money moves.
 * Wordings are invented from the masked shapes; the nearest real transactions must stay read.
 */
class ShippedPackV22Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 22)
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

    @Test fun adcbFailedDebitCardTxnIsIgnoredAndLookAlikesAreNot() {
        val label = "Failed: ADCB debit card txn"
        val re = entry(label)
        for (body in listOf(
            "AED 25.00 txn at KFC Sharjah AE on 22/11/2026 15:08 from Debit Card 1234 failed due to incorrect CVV. Call +97124030030 for help.",
            "USD 9.99 txn at www.example.com +18005550100 CA on 23/11/2026 19:01 from Debit Card 4321 failed due to incorrect CVV. Call +97124030030 for assistance.",
            "AED 130.00 txn at ZORBO SHOP DUBAI on 17/11/2026 06:26 from Debit Card 1234 failed due to card expired. Call +97124030030 for assistance.",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("ADCB", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
        }
        // Nearest real transactions at the same bank: same words around, with an amount and a money verb, but no "failed due to".
        for (body in listOf(
            "AED 25.00 txn at KFC Sharjah AE on 22/11/2026 15:08 from Debit Card 1234 was successful. Avl Bal AED 500.00",
            "Your debit card 1234 linked to acc. 5678 was used for AED 25.00 on 22/11/2026 15:08 at KFC SHARJAH. Avl. Bal. AED 500.00",
            "AED 25.00 has been refunded to your debit card 1234 on 22/11/2026 15:08 by KFC. The amount will be credited to your account.",
        )) {
            assertTrue(!re.containsMatchIn(body), body)
            val r = read("ADCB", body)
            if (r is ParseResult.Ignored) assertTrue(r.reason != label, body)
        }
        val purchase = read("ADCB", "Your debit card 1234 linked to acc. 5678 was used for AED 25.00 on 22/11/2026 15:08 at KFC SHARJAH. Avl. Bal. AED 500.00")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals(Decimal("25.00"), purchase.txn.amount)
    }

    @Test fun savingsPrizeDrawAdvertIsIgnoredAndLookAlikesAreNot() {
        val label = "Advert: savings prize draw"
        val re = entry(label)
        val advert = "Increase your Zorbo Savings Account balance & boost your chances to win AED 1 million & 2 Teslas till end of 2026 + daily cash prizes. Visit www.example.com"
        assertTrue(re.containsMatchIn(advert))
        for (bank in listOf("Unknown Test Bank", "ADCB")) {
            val r = read(bank, advert)
            assertIs<ParseResult.Ignored>(r, "got $r for $bank")
        }
        for (body in listOf(
            "AED 1,000.00 has been credited to your Zorbo Savings Account. Your balance is AED 5,000.00",
            "AED 1,000.00 debited from your Savings Account 1234 on 22/11/2026. Available balance is AED 4,000.00",
            "Increase your Zorbo Savings Account balance and earn up to 4% interest. Visit www.example.com",
        )) assertTrue(!re.containsMatchIn(body), body)
        val credit = read("ADCB", "AED 1,000.00 has been credited to your Savings Account 1234 on 22/11/2026. Your balance is AED 5,000.00")
        if (credit is ParseResult.Ignored) assertTrue(credit.reason != label)
    }

    @Test fun packCountsAfterV22() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertEquals(22, out.pack.version)
        assertTrue(out.pack.rules.size >= 34)
        assertTrue(out.pack.ignore.size >= 45)
    }
}
