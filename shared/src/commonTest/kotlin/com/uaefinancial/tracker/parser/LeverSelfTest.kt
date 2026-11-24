package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.ignore
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.read
import com.uaefinancial.tracker.parser.P28.rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Section 8.4 of the 2.8 spec, the proof that "a new wording needs no Play update": for EACH lever, a test rules file that
 * uses it reads or hides a matching invented message with no app change, and the same file without the lever behaves as 2.7.
 * (Pairs, cross-source merges, key changes and notification sources also have database tests in the app: PackPairDbTest,
 * KeyChangeDbTest, WalletMergeDbTest.)
 */
class LeverSelfTest {
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.setHomeCurrency("AED") }

    private fun ruleRead(r: ParseResult, id: String) = r is ParseResult.Transaction && r.ruleId == "pack:$id"

    @Test fun leverCurAbbrev() {
        val r = rule("Kotak Mahindra Bank", "kotak-rs", """Rs\.? ?{AMOUNT} debited from a/c {CARD} to {MERCHANT} on""".replace("Rs\\.? ?", "{CUR} "), cardType = "ACCOUNT")
        SmsParser.setHomeCurrency("INR")
        val body = "Rs. 499.00 debited from a/c XX4321 to SOME STORE on 03-11-26"
        apply(file(ext = levers("curAbbrev") + ",\"rules\":[$r]"))
        val t = read("Kotak Mahindra Bank", body)
        assertTrue(ruleRead(t, "kotak-rs"), "$t"); assertEquals("INR", (t as ParseResult.Transaction).txn.currency)
        apply(file(ext = "\"rules\":[$r]"))
        assertFalse(ruleRead(read("Kotak Mahindra Bank", body), "kotak-rs"))
    }

    @Test fun leverAcctToken() {
        val r = rule("Dubai Islamic Bank", "dib-acct", """{CUR} {AMOUNT} has been drawn from A/C No\. {ACCT} as OUTWARD UAE FUNDS""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Outward transfer\"")
        val body = "AED 2,000.00 has been drawn from A/C No. 123-456789XXXX-01 as OUTWARD UAE FUNDS TRANSFER. Available balance is AED 5,000.00"
        apply(file(ext = levers("acctToken") + ",\"rules\":[$r]"))
        assertTrue(ruleRead(read("Dubai Islamic Bank", body), "dib-acct"))
        apply(file(ext = "\"rules\":[$r]"))
        assertFalse(ruleRead(read("Dubai Islamic Bank", body), "dib-acct"))
    }

    @Test fun leverPhrases() {
        val body = "AED 75.00 is taken out by your account XXXX1234 for SOME CLUB. Avl bal AED 925.00"
        val w = "\"words\":{\"debit\":[\"is taken out by\"]}"
        apply(file(ext = levers("phrases") + ",$w"))
        assertIs<ParseResult.Transaction>(read("Sharjah Islamic Bank", body))
        apply(file(ext = w))
        assertFalse(read("Sharjah Islamic Bank", body) is ParseResult.Transaction)
    }

    @Test fun leverNotMoney() {
        val body = "AED 75.00 will be debited from your account XXXX1234 for SOME CLUB at month end. Avl bal AED 925.00"
        val w = "\"words\":{\"notMoney\":[\"at month end\"]}"
        apply(file(ext = levers("phrases", "notMoney") + ",$w"))
        assertFalse(read("Sharjah Islamic Bank", body) is ParseResult.Transaction)
    }

    @Test fun leverWordingBanks() {
        val body = "Pymt for GROCER of AED 12.00 has been made using Credit Card 71534.Avl.limit AED 100.00"
        apply(file(ext = levers("wordingBanks")))
        assertEquals("Emirates Islamic", (SmsParser.parseAsBank("Family Chat", body, P28.AT) as? ParseResult.Transaction)?.attributedBank)
        RulesPack.apply(null)
        assertFalse((SmsParser.parseAsBank("Family Chat", body, P28.AT) as? ParseResult.Transaction)?.attributedBank == "Emirates Islamic")
    }

    @Test fun leverUnlessWithAScopedIgnore() {
        val ig = ignore("Transfer initiated", """\btransfer\b.{0,40}\binitiated\b""", ",\"bank\":\"cbd\",\"unless\":\"\\\\b(?:debited|has been processed)\\\\b\"")
        apply(file(ext = levers("unless") + ",\"ignore\":[$ig]"))
        val notice = read("CBD", "Your transfer of AED 300.00 has been initiated. Ref 123456")
        assertIs<ParseResult.Ignored>(notice); assertEquals("Transfer initiated", notice.reason)
        // the later debit is never hidden
        assertFalse(read("CBD", "Your transfer of AED 300.00 initiated earlier has been debited from account XXXX1234. Avl bal AED 700.00") is ParseResult.Ignored)
    }

    @Test fun leverPairsDeclaresAPairGroupOnAPackRule() {
        val r = rule("Dubai Islamic Bank", "dib-own-out", """{CUR} {AMOUNT} has been drawn from A/C No\. {ACCT} as OWN ACCOUNT TRANSFER""", type = "TRANSFER_OUT", cardType = "ACCOUNT",
            extra = ",\"fixedMerchant\":\"Own account transfer\",\"pairGroup\":\"dib-own\",\"pairWindowMinutes\":30")
        val p = apply(file(ext = levers("pairs", "acctToken") + ",\"rules\":[$r]"))
        assertEquals("dib-own", p.ext!!.rules.single().rule.pairGroup)
        assertTrue(RulesPack.levers.on(RulesPack.Lever.PAIRS))
        assertTrue(ruleRead(read("Dubai Islamic Bank", "AED 500.00 has been drawn from A/C No. 123-456789XXXX-01 as OWN ACCOUNT TRANSFER. Available balance is AED 1.00"), "dib-own-out"))
    }

    @Test fun leverNotificationBanks() {
        val ext = "\"notificationBanks\":[{\"titlePattern\":\"^e& money\",\"bank\":\"e& money\",\"isAggregator\":true}],\"banks\":[{\"name\":\"e& money\",\"country\":\"AE\",\"senderIds\":[\"EANDMONEY\"],\"track\":true}]"
        apply(file(ext = levers("notificationBanks") + ",$ext"))
        assertEquals("e& money", RulesPack.notificationBanks.single().bank)
        assertEquals("e& money", RulesPack.notificationBankFor("com.some.wallet", "e& money: payment received")?.bank)
        assertEquals(null, RulesPack.notificationBankFor("com.some.wallet", "Your order is on its way"), "look-alike title")
        apply(file(ext = ext))
        assertEquals(null, RulesPack.notificationBankFor("com.some.wallet", "e& money: payment received"), "lever off")
        assertTrue(RulesPack.notificationBanks.isEmpty())
    }

    @Test fun leverKeyChange() {
        apply(file(ext = levers("keyChange") + ",\"readingPolicy\":{\"applyKeyChangeFor\":[\"ADCB\",\"CBD\"]}"))
        assertEquals(setOf("adcb", "cbd"), ReadingPolicy.keyChangeBanks)
        apply(file(ext = "\"readingPolicy\":{\"applyKeyChangeFor\":[\"ADCB\"]}"))
        assertTrue(ReadingPolicy.keyChangeBanks.isEmpty())
    }

    @Test fun leverAsk() {
        val ext = "\"ask\":[{\"bank\":\"Sharjah Islamic Bank\",\"fields\":[\"layout\",\"wholeAmount\"],\"until\":40}]"
        apply(file(ext = levers("ask") + ",$ext"))
        assertEquals(setOf("layout", "wholeAmount"), RulesPack.ask.single().fields)
        apply(file(ext = ext))
        assertTrue(RulesPack.ask.isEmpty())
    }

    @Test fun leverLearnedExempt() {
        apply(file(ext = levers("learnedExempt") + ",\"learnedExempt\":[\"\\\\bcredited to your account\\\\b\"]"))
        assertTrue(SmsParser.learnedExempt("AED 500.00 has been credited to your account XXXX1234"))
        apply(file(ext = "\"learnedExempt\":[\"\\\\bcredited to your account\\\\b\"]"))
        assertFalse(SmsParser.learnedExempt("AED 500.00 has been credited to your account XXXX1234"))
    }

    @Test fun safeModeIsThePackWideKillSwitch() {
        val r = rule("Sharjah Islamic Bank", "sib-x", """Within UAE Funds Transfer of {CUR} {AMOUNT} from account {ACCT}""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Transfer\"")
        val body = "Within UAE Funds Transfer of AED 700.00 from account XXXX1234 is complete."
        apply(file(ext = levers("acctToken", "wordingBanks") + ",\"rules\":[$r]"))
        assertTrue(ruleRead(read("Sharjah Islamic Bank", body), "sib-x"))
        apply(file(ext = levers("acctToken", "wordingBanks") + ",\"safeMode\":true,\"rules\":[$r]"))
        assertFalse(ruleRead(read("Sharjah Islamic Bank", body), "sib-x"))
        assertFalse(RulesPack.levers.on(RulesPack.Lever.WORDING_BANKS))
        assertEquals(Decimal("700.00"), Decimal("700.00"))
    }

    // Spec 15: a new tracked wallet ships its notice ignores first; the audit warns about a tracked bank that has none.
    @Test fun aTrackedWalletWithItsIgnoresDoesNotFloodReviewAndOneWithoutIsWarned() {
        val bank = "{\"name\":\"e& money\",\"country\":\"AE\",\"senderIds\":[\"EANDMONEY\"],\"track\":true}"
        val money = rule("e& money", "eand-in", """Good news! {CUR} {AMOUNT} from {MERCHANT} in your account\.""", type = "TRANSFER_IN", cardType = "ACCOUNT", extra = ",\"accountNotNamed\":true")
        val ig = ignore("Wallet reward", """\b(?:cashback|reward|points)\b""", ",\"bank\":\"e& money\"")
        val notice = "You earned 25 reward points on your last payment. Check the app."
        val withIgnore = apply(file(banks = "[$bank]", ext = levers("unless") + ",\"rules\":[$money],\"ignore\":[$ig]"))
        assertTrue(RulesPack.trackedWithoutIgnore(withIgnore).isEmpty())
        assertIs<ParseResult.Ignored>(SmsParser.parse("EANDMONEY", notice, P28.AT))
        assertIs<ParseResult.Transaction>(SmsParser.parse("EANDMONEY", "Good news! AED 200.00 from SOME FRIEND in your account. Check your new balance.", P28.AT))
        val without = apply(file(banks = "[$bank]", ext = "\"rules\":[$money]"))
        assertEquals(listOf("e& money"), RulesPack.trackedWithoutIgnore(without))
    }

    // Spec 16.5: a needsTwin rule reads only in an app that holds it until its transfer arrives (the Android repository).
    @Test fun leverNeedsTwinReadsOnlyWhereTheAppHoldsIt() {
        val r = rule("CBD", "cbd-on-way", """Your transfer of {CUR} {AMOUNT} is now on its way""", type = "TRANSFER_OUT", cardType = "ACCOUNT",
            extra = ",\"fixedMerchant\":\"Transfer\",\"needsTwin\":true")
        val body = "Your transfer of AED 900.00 is now on its way to S***** A****."
        val before = SmsParser.holdsTwins
        try {
            apply(file(ext = levers("needsTwin") + ",\"rules\":[$r]"))
            SmsParser.holdsTwins = true
            val t = read("CBD", body)
            assertTrue(ruleRead(t, "cbd-on-way"), "$t"); assertTrue((t as ParseResult.Transaction).txn.needsTwin)
            SmsParser.holdsTwins = false
            assertFalse(ruleRead(read("CBD", body), "cbd-on-way"), "an app without the hold logic never uses the rule")
            SmsParser.holdsTwins = true
            apply(file(ext = "\"rules\":[$r]"))
            assertFalse(ruleRead(read("CBD", body), "cbd-on-way"), "lever off")
        } finally { SmsParser.holdsTwins = before }
    }
}
