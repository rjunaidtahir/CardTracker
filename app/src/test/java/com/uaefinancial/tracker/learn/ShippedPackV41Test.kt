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
 * 27 Nov 2026 (15:23 slot). Pack v41: three narrow ignores.
 * Two Sharjah Islamic Bank Millionaire adverts (each reported by two different phones, pack v31 and v32) and the Tabby
 * "To complete your Tabby Card payment at ... confirm it in the Tabby app within N minutes" notice (a pending request,
 * no money has moved yet; reported in one batch by one phone, so flagged as a single-phone group). Wordings invented from the masked shape.
 */
class ShippedPackV41Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val labels = listOf("Advert: SIB Millionaire March winners", "Advert: SIB gateway to millions", "Notice: Tabby payment awaits confirm")

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 41)
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

    @Test fun newNoticesAreIgnored() {
        val cases = listOf(
            "Sharjah Islamic Bank" to ("1 million dirhams and BMW XM Car awaits our SIB Millionaire March winners! Every AED 1,000 saved gives you a chance to win. Download SIB Digital App and open your Savings Account now. Terms apply, www.example.com" to labels[0]),
            "Sharjah Islamic Bank" to ("2 MILLION Dirhams Awaits! SIB Savings Account, your gateway to millions. Maintain AED 5,000 to enter the monthly Millionaire draw. One Million Exclusive for UAE Nationals & One Million for everyone every quarter. 1st draw on 15/12/2026. Terms apply www.example.com" to labels[1]),
            "Tabby" to ("To complete your Tabby Card payment at Careem for AED 20.00, confirm it in the Tabby app within 5 minutes" to labels[2]),
            "Tabby" to ("To complete your Tabby Card payment at Kuwait Food Co. LLC for AED 1,020.50, confirm it in the Tabby app within 10 minutes" to labels[2]),
        )
        for ((bank, c) in cases) {
            val (body, label) = c
            assertTrue(entry(label).containsMatchIn(body), body)
            for (b in listOf(bank, "Unknown Test Bank")) {
                val r = read(b, body)
                assertIs<ParseResult.Ignored>(r, "got $r for $b")
                assertEquals(label, r.reason)
            }
        }
    }

    @Test fun lookAlikesAreNotIgnored() {
        val bodies = listOf(
            "Tabby" to "You spent AED 20.00 at Careem. Your available Tabby Card limit is now AED 80.00.",
            "Tabby" to "Your Careem purchase for AED 20.00 was cancelled. Your available Tabby Cash Card balance is AED 80.00.",
            "Tabby" to "We've received your payment of AED 100.00",
            "Tabby" to "You sent AED 50.00 to John Doe Your new balance is AED 10.00. For more, go to the Tabby app.",
            "Sharjah Islamic Bank" to "AED 500.00 has been credited to your Savings Account 12345678 on 27/11/2026. Available balance is AED 5,000.00",
            "Sharjah Islamic Bank" to "A txn on your Card 1234 at McDonalds for AED 45.50 on 27/11/2026at 10:15 is approved. Your available balance is 1234.56",
            "Sharjah Islamic Bank" to "A/C 1234567890123 Outward Remit AED 100.00. Available Balance is AED +1.50.",
            "Sharjah Islamic Bank" to "Transfer of AED 500.00 to Millionaire March winners Trading LLC was completed. Balance AED 4,500.00",
        )
        for ((bank, body) in bodies) {
            for (l in labels) assertTrue(!entry(l).containsMatchIn(body), "$l matched $body")
            val r = read(bank, body)
            if (r is ParseResult.Ignored) assertTrue(r.reason !in labels, body)
        }
        val spent = read("Tabby", "You spent AED 20.00 at Careem. Your available Tabby Card limit is now AED 80.00.")
        assertTrue(spent is ParseResult.Transaction && spent.txn.type == TxnType.PURCHASE, "got $spent")
        val remit = read("Sharjah Islamic Bank", "A/C 1234567890123 Outward Remit AED 100.00. Available Balance is AED +1.50.")
        assertTrue(remit is ParseResult.Transaction && remit.txn.type == TxnType.TRANSFER_OUT, "got $remit")
    }

    @Test fun packCountsAfterV41() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 41)
        assertTrue(out.pack.ignore.size >= 62)
    }
}
