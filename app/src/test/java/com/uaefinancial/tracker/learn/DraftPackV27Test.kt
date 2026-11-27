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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DRAFT rules file v27 (`rules/drafts/pack-v27-draft.json`, not published; `rules/pack.json` is unchanged). Its first `ext`:
 *  - lever wordingBanks: Emirates Islamic card payments from a chat with no bank of its own are read by the built-in wording;
 *  - two HSBC Global Money Account rules (direction stated by the wording, one amount);
 *  - three narrow ignores (cheque returned, "earlier SMS sent in error", overdue reminder);
 *  - lever ask + one ask entry (HSBC amount layout), so 2.8 phones say which number in an HSBC account message is the amount.
 * All wordings below are invented from masked shapes.
 */
class DraftPackV27Test {
    private val file = listOf(File("../rules/drafts/pack-v27-draft.json"), File("rules/drafts/pack-v27-draft.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.ext != null, "ext was rejected")
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun emiratesIslamicPaymentFromAChatWithNoBankIsRead() {
        val r = read("My Card Chat", "Payment for SPINNEYS LLC of AED 88.10 has been made using Credit Card ending with 1534. Available limit AED 3,900.00.")
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("Emirates Islamic", r.attributedBank)
        assertEquals(TxnType.PURCHASE, r.txn.type)
        assertEquals("88.10", r.txn.amount.toPlainString())
        assertEquals("1534", r.txn.cardLast4)
        // A chat that is not a bank stays unread.
        val chat = read("My Card Chat", "Hey, can you send me AED 50 for the dinner? Thanks!")
        assertNull((chat as? ParseResult.Transaction)?.attributedBank, "got $chat")
    }

    @Test fun hsbcGlobalMoneyAccountInAndOut() {
        val inn = read("HSBC", "From HSBC: 21NOV Add Money Credit to HSBC Global Money Account AED 1,250.00. Your available balance is AED 3,400.00.")
        assertIs<ParseResult.Transaction>(inn, "got $inn")
        assertEquals(TxnType.TRANSFER_IN, inn.txn.type)
        assertEquals("1250.00", inn.txn.amount.toPlainString())
        val out = read("HSBC", "From HSBC: 21NOV online money transfer Debit from HSBC Global Money Account USD 75.00. Your available balance is USD 410.00.")
        assertIs<ParseResult.Transaction>(out, "got $out")
        assertEquals(TxnType.TRANSFER_OUT, out.txn.type)
        assertEquals("75.00", out.txn.amount.toPlainString())
        assertEquals("USD", out.txn.currency)
    }

    @Test fun ignoresHideOnlyTheirNotices() {
        for (body in listOf(
            "Your cheque number 004512 for AED 2,500.00 has been returned. For assistance please call ADCB Contact Centre on 600502030.",
            "Dear Customer, due to a technical issue, you may have received an incorrect SMS from the Bank regarding an overdue payment of AED 1,000.00 on your Credit Card. Please disregard the message and we apologize for the inconvenience.",
            "The payment of your Emirates Skywards card starting with 412345* is overdue. Please pay the minimum amount of AED 250.00 or more to regularize your card payment. Thank you.",
        )) {
            val r = read("ADCB", body)
            assertIs<ParseResult.Ignored>(r, "not hidden: $body -> $r")
        }
        // Money that really moved is still read.
        val cheque = read("Emirates NBD", "Dear Customer, the cheque number 004512 for AED 2,500.00 has been debited from account 101-12345-01 on 21/11/2026 10:00.")
        assertTrue(cheque !is ParseResult.Ignored || !cheque.fromPack, "a paid cheque hidden by the draft: $cheque")
        val purchase = read("Unknown Test Bank", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
    }

    @Test fun shapeOfTheDraft() {
        val text = file.readText()
        assertTrue(text.toByteArray().size <= 262_144, "a 2.7 phone refuses files over 262,144 bytes")
        val out = RulesPack.parse(text, currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out)
        val shipped = RulesPack.parse(listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(shipped)
        // The legacy part is exactly the shipped one: 2.6/2.7 phones read nothing new.
        assertEquals(shipped.pack.rules, out.pack.rules)
        assertEquals(shipped.pack.ignore, out.pack.ignore)
        assertTrue(out.pack.version > shipped.pack.version)
    }
}
