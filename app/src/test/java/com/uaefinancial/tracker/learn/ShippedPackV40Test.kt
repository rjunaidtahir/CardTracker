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
 * 27 Nov 2026 (11:23 slot). Pack v40: one narrow ignore, "Advert: SIB savings account millionaire" (a Sharjah Islamic Bank
 * savings-draw advert, "... Awaits! Your Savings Account makes you a MILLIONAIRE! ..."; two reports with different timestamps).
 * Wordings invented from the masked shape.
 */
class ShippedPackV40Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val label = "Advert: SIB savings account millionaire"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 40)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun entry(): Regex {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out)
        val e = out.pack.ignore.first { it.label == label }
        assertTrue(e.label.length <= 40)
        return Regex(e.pattern, RegexOption.IGNORE_CASE)
    }

    @Test fun sibMillionaireAdvertIsIgnored() {
        val re = entry()
        val adverts = listOf(
            "AED 500.00 Awaits! Your Savings Account makes you a MILLIONAIRE! Every AED 500.00 gives you a chance to enter the monthly draws on a Million Dirhams, Luxury Car and many Cash Prizes. for more visit www.example.com Terms apply",
            "AED 1,000.00 Awaits! Your Savings Account makes you a MILLIONAIRE! Every AED 500 gives you a chance to enter the monthly draws on a Million Dirhams, Luxury Car and many Cash Prizes. for more visit www.example.com 1st draw on 15/12/2026. 2 Million Awaits",
        )
        for (ad in adverts) {
            assertTrue(re.containsMatchIn(ad), ad)
            for (bank in listOf("Sharjah Islamic Bank", "Unknown Test Bank")) {
                val r = read(bank, ad)
                assertIs<ParseResult.Ignored>(r, "got $r for $bank")
                assertEquals(label, r.reason)
            }
        }
    }

    @Test fun lookAlikesAreNotIgnored() {
        val re = entry()
        for (body in listOf(
            "AED 500.00 has been credited to your Savings Account 12345678 on 27/11/2026. Available balance is AED 5,000.00",
            "AED 500.00 debited from your Savings Account 12345678 on 27/11/2026 at Awaits Cafe. Your Savings Account makes you a happy saver. Balance AED 4,500.00",
            "Transfer of AED 500.00 to your Savings Account makes you a MILLIONAIRE Trading LLC was completed. Balance AED 4,500.00",
            "A txn on your Card 1234 at McDonalds for AED 45.50 on 27/11/2026at 10:15 is approved. Your available balance is 1234.56",
            "Statement for Credit Card ending with 12345678 was issued with outstanding of AED 1,234.56, minimum payment due is AED 100.00. Please ensure to pay before 15/12/2026to avoid any charges.",
        )) {
            assertTrue(!re.containsMatchIn(body), body)
            val r = read("Sharjah Islamic Bank", body)
            if (r is ParseResult.Ignored) assertTrue(r.reason != label, body)
        }
        // the neighbouring reading is unchanged: the SIB card purchase keeps its own rule, the credit stays money in
        val purchase = read("Sharjah Islamic Bank", "A txn on your Card 1234 at McDonalds for AED 45.50 on 27/11/2026at 10:15 is approved. Your available balance is 1234.56")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals("pack:sib-card-txn-approved", purchase.ruleId)
        assertEquals(TxnType.PURCHASE, purchase.txn.type)
        val credit = read("Sharjah Islamic Bank", "AED 500.00 has been credited to your Savings Account 12345678 on 27/11/2026. Available balance is AED 5,000.00")
        assertTrue(credit is ParseResult.Transaction && credit.txn.type == TxnType.TRANSFER_IN, "got $credit")
    }

    @Test fun packCountsAfterV40() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 40)
        assertTrue(out.pack.ignore.size >= 59)
    }
}
