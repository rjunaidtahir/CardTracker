package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.ignore
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.read
import com.uaefinancial.tracker.parser.P28.rule
import com.uaefinancial.tracker.parser.legacy.RulesPack27
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Section 3 and 4 of the 2.8 spec: the `ext` section, its compatibility with 2.6/2.7 phones, caps, tokens and fields. */
class RulesPackExtTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val legacyRule = "\"rules\":[" + rule("Dubai Islamic Bank", "dib-legacy-1", """Your card {CARD} spent {CUR} {AMOUNT} at {MERCHANT}\.""") + "]"

    @Test fun a28FileIsAcceptedByThe27ReaderAsItsLegacyPartOnly() {
        val json = file(
            legacy = legacyRule.removePrefix("\"rules\":").let { "\"ignore\":[]" },
            ext = levers("acctToken") + ",\"rules\":[" + rule("Dubai Islamic Bank", "dib-acct", """{AMOUNT} drawn from A/C No\. {ACCT} as fees""", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Bank fees\"") + "]",
        )
        val old = RulesPack27.parse(json)
        assertIs<RulesPack27.Outcome.Ok>(old, "a 2.7 phone must accept it: $old")
        assertEquals(0, old.pack.rules.size)
        val new = RulesPack.parse(json)
        assertIs<RulesPack.Outcome.Ok>(new)
        assertEquals(1, new.pack.ext?.rules?.size)
        // and the legacy-only mode of the 2.8 reader agrees with the frozen 2.7 reader
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(json, legacyOnly = true))
    }

    @Test fun anOldFileParsesUnchangedOn28() {
        val json = """{"version":12,"minEngine":5,"banks":[],${legacyRule}}"""
        val old = RulesPack27.parse(json) as RulesPack27.Outcome.Ok
        val new = RulesPack.parse(json) as RulesPack.Outcome.Ok
        assertEquals(old.pack.rules.map { it.rule.id }, new.pack.rules.map { it.rule.id })
        assertNull(new.pack.ext)
        assertNull(new.pack.extRejected)
    }

    @Test fun aMalformedExtIsIgnoredWithoutTouchingTheLegacyPart() {
        for (badExt in listOf(
            "\"levers\":{\"noSuchLever\":true}",
            "\"rules\":[" + rule("Dubai Islamic Bank", "x", "no amount here") + "]",
            "\"ignore\":[" + ignore("Hides purchases", """was used for""") + "]",
            "\"wordingBanks\":[{\"pattern\":\"(a+)+bbbbbbbb\",\"bank\":\"Wio\"}]",
            "\"ask\":[{\"bank\":\"*\",\"fields\":[\"phoneNumber\"],\"until\":40}]",
        )) {
            val out = RulesPack.parse(file(legacy = legacyRule, ext = badExt))
            assertIs<RulesPack.Outcome.Ok>(out, badExt)
            assertNotNull(out.pack.extRejected, badExt)
            assertNull(out.pack.ext)
            assertEquals(1, out.pack.rules.size, "legacy part kept for $badExt")
        }
    }

    @Test fun legacyRulesCannotUse28TokensSoOldPhonesNeverRejectTheFile() {
        val json = file(legacy = "\"rules\":[" + rule("Dubai Islamic Bank", "dib-acct-legacy", """{AMOUNT} drawn from A/C No\. {ACCT}""") + "]")
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(json))
        assertIs<RulesPack27.Outcome.Rejected>(RulesPack27.parse(json))
    }

    @Test fun legacyLimitsOfOldPhonesAreChecked() {
        val many = (1..201).joinToString(",") { ignore("Notice $it", """\bnotice number $it of many\b""") }
        val json = file(legacy = "\"ignore\":[$many]")
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(json)) // 2.8 takes up to 600
        assertTrue(RulesPack.legacyLimitProblems(json).isNotEmpty())
        assertTrue(RulesPack.legacyLimitProblems(file(legacy = legacyRule)).isEmpty())
    }

    @Test fun capsAreRaised() {
        assertEquals(524_288, RulesPack.MAX_BYTES)
        val words = (1..250).joinToString(",") { i -> "\"word" + ('a' + i % 26) + ('a' + i / 26) + "\"" }
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(file(legacy = "\"words\":{\"debit\":[$words]}")))
    }

    @Test fun leversAreOffUnlessTheFileTurnsThemOn() {
        val p = apply(file(ext = "\"rules\":[]"))
        assertFalse(RulesPack.levers.on(RulesPack.Lever.WORDING_BANKS))
        assertTrue(RulesPack.levers.on(RulesPack.Lever.HSBC_LIVE_PLUS_NAMES))
        assertNotNull(p.ext)
    }

    @Test fun extFieldsAreParsedWithLimits() {
        val r = rule("Dubai Islamic Bank", "dib-own", """{AMOUNT} has been drawn from A/C No\. {ACCT} as OWN ACCOUNT TRANSFER""", type = "TRANSFER_OUT", cardType = "ACCOUNT",
            extra = ",\"pairGroup\":\"dib-own\",\"describesDestination\":false,\"pairWindowMinutes\":60,\"accountNotNamed\":true,\"needsTwin\":true,\"twinWindowMinutes\":120,\"directionFromBalance\":true,\"direction\":\"OUT\"")
        val p = apply(file(ext = levers("acctToken") + ",\"rules\":[$r]"))
        val x = p.ext!!.rules.single().rule
        assertEquals("dib-own", x.pairGroup); assertEquals(60, x.pairWindowMinutes); assertTrue(x.accountNotNamed)
        assertTrue(x.needsTwin); assertEquals(120, x.twinWindowMinutes); assertTrue(x.directionFromBalance); assertEquals("OUT", x.direction)
        assertFalse(x.namesDestination)
        for (bad in listOf(",\"pairWindowMinutes\":0", ",\"pairWindowMinutes\":1441", ",\"pairGroup\":\"" + "x".repeat(41) + "\"", ",\"direction\":\"SIDEWAYS\"")) {
            val out = RulesPack.parse(file(ext = "\"rules\":[" + rule("Dubai Islamic Bank", "d", "{CUR} {AMOUNT} debited {CARD}", extra = bad) + "]")) as RulesPack.Outcome.Ok
            assertNotNull(out.pack.extRejected, bad)
        }
        // every TxnType stays accepted
        for (t in TxnType.entries) assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(file(legacy = "\"rules\":[" + rule("Dubai Islamic Bank", "t-$t", "{CUR} {AMOUNT} x {CARD}", type = t.name) + "]")))
    }

    // ---- 3.1 currency abbreviations ({CUR} in ext rules, lever curAbbrev)

    @Test fun curAcceptsAbbreviationsAndSymbolsInExtRules() {
        val r = rule("HDFC Bank", "hdfc-cur", """{CUR} {AMOUNT} spent on card {CARD} at {MERCHANT} on""", cardType = "CREDIT")
        apply(file(ext = levers("curAbbrev") + ",\"rules\":[$r]"))
        SmsParser.setHomeCurrency("INR")
        try {
            for ((cur, iso) in listOf("Rs." to "INR", "Rs" to "INR", "SR" to "SAR", "KD" to "KWD", "Dhs" to "AED", "AED" to "AED", "INR" to "INR", "USD" to "USD", "$" to "USD")) {
                val t = read("HDFC Bank", "$cur 1,250.00 spent on card 4321 at SHOP NAME on 05-11-26")
                assertIs<ParseResult.Transaction>(t, cur)
                assertEquals(iso, t.txn.currency, cur)
                assertEquals(Decimal("1250.00"), t.txn.amount, cur)
            }
        } finally { SmsParser.setHomeCurrency("AED") }
    }

    @Test fun withoutTheLeverExtRulesKeepThe27Cur() {
        val r = rule("HDFC Bank", "hdfc-cur", """{CUR} {AMOUNT} spent on card {CARD} at {MERCHANT} on""", cardType = "CREDIT")
        apply(file(ext = "\"rules\":[$r]"))
        assertFalse(read("HDFC Bank", "Rs. 1,250.00 spent on card 4321 at SHOP NAME on 05-11-26").let { it is ParseResult.Transaction && it.ruleId == "pack:hdfc-cur" })
    }

    // ---- 3.2 account token

    @Test fun accountKeyRules() {
        assertEquals("89XXXX01", SmsParser.accountKey("123-456789XXXX-01"))
        assertEquals("54XXXX01", SmsParser.accountKey("123-987654XXXX-01"))
        assertEquals("1234", SmsParser.accountKey("XXXX1234"))
        assertEquals("1234", SmsParser.accountKey("1234"))
        assertEquals("1234", SmsParser.accountKey("AE07 0331 XXXX XXXX 1234".removePrefix("AE")))
        assertEquals("3001", SmsParser.accountKey("****3001"))
        assertNull(SmsParser.accountKey("XXXX-X1"))
    }

    @Test fun acctTokenReadsDibFabAndPlainAccounts() {
        val rules = listOf(
            rule("Dubai Islamic Bank", "dib-fees", """{CUR} {AMOUNT} has been drawn from A/C No\. {ACCT} as PROCESSING FEES""", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Bank fees\""),
            rule("FAB", "fab-acct", """Debit of {CUR} {AMOUNT} from account {ACCT} at {MERCHANT} on""", cardType = "ACCOUNT"),
        ).joinToString(",")
        apply(file(ext = levers("acctToken") + ",\"rules\":[$rules]"))
        val a = read("Dubai Islamic Bank", "AED 25.00 has been drawn from A/C No. 123-456789XXXX-01 as PROCESSING FEES. Available balance is AED 1,000.00")
        assertIs<ParseResult.Transaction>(a); assertEquals("89XXXX01", a.txn.cardLast4); assertEquals("Bank fees", a.txn.merchant)
        val b = read("Dubai Islamic Bank", "AED 25.00 has been drawn from A/C No. 123-987654XXXX-01 as PROCESSING FEES. Available balance is AED 900.00")
        assertIs<ParseResult.Transaction>(b); assertEquals("54XXXX01", b.txn.cardLast4, "two accounts ending alike must not merge")
        val c = read("FAB", "Debit of AED 10.00 from account XXXX8001 at SOME SHOP on 01/11/2026")
        assertIs<ParseResult.Transaction>(c); assertEquals("8001", c.txn.cardLast4)
        // without its lever the {ACCT} rule is not used at all
        apply(file(ext = "\"rules\":[$rules]"))
        assertFalse((read("Dubai Islamic Bank", "AED 25.00 has been drawn from A/C No. 123-456789XXXX-01 as PROCESSING FEES. Available balance is AED 1,000.00") as? ParseResult.Transaction)?.ruleId == "pack:dib-fees")
    }

    // ---- 3.4 phrases and notMoney

    @Test fun phrasesInWordsAndNotMoney() {
        val words = "\"words\":{\"debit\":[\"has been settled by\"],\"notMoney\":[\"will be\",\"could not\",\"is due\",\"reminder\"]}"
        apply(file(ext = levers("phrases", "notMoney") + ",$words"))
        val d = read("Sharjah Islamic Bank", "AED 300.00 has been settled by your account XXXX1234 to SOME PAYEE. Avl bal AED 1,200.00")
        assertIs<ParseResult.Transaction>(d, "a phrase reads a debit: $d")
        assertTrue(d.txn.type == TxnType.PURCHASE || d.txn.type == TxnType.TRANSFER_OUT)
        val blocked = read("Sharjah Islamic Bank", "Reminder: AED 300.00 will be debited from your account XXXX1234 on 05/12/2026")
        assertFalse(blocked is ParseResult.Transaction, "notMoney blocks a read: $blocked")
        // single words still work
        apply(file(legacy = "\"words\":{\"debit\":[\"deducted\"]}"))
        assertIs<ParseResult.Transaction>(read("Sharjah Islamic Bank", "AED 40.00 deducted from your account XXXX1234 for SOME SHOP. Avl bal AED 900.00"))
    }

    @Test fun pymtCreditedIsACreditNotAPurchase() {
        val r = read("Sharjah Islamic Bank", "Pymt of AED 1,500.00 credited to your account XXXX1234 on 03/11/2026. Avl bal AED 2,000.00")
        assertIs<ParseResult.Transaction>(r)
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
    }

    @Test fun phraseValidation() {
        for (bad in listOf("\"ab\"", "\"" + "a".repeat(41) + "\"", "\"rs 100\"", "\"a|b\"")) {
            val out = RulesPack.parse(file(ext = "\"words\":{\"debit\":[$bad]}")) as RulesPack.Outcome.Ok
            assertNotNull(out.pack.extRejected, bad)
        }
    }

    // ---- 3.6 ignores: bank scope, unless, minWords

    @Test fun scopedIgnoreWithUnless() {
        val ig = ignore("Card blocked notice", """card has been blocked""", ",\"bank\":\"CBD\",\"unless\":\"\\\\bpurchase of\\\\b\"")
        apply(file(ext = levers("unless") + ",\"ignore\":[$ig]"))
        val hidden = read("CBD", "Your card has been blocked for your safety. Call us on 600 575 556.")
        assertIs<ParseResult.Ignored>(hidden); assertEquals("Card blocked notice", hidden.reason); assertTrue(hidden.fromPack)
        // the same words at another bank are not hidden by a CBD-only ignore
        assertFalse((read("RAKBANK", "Your card has been blocked for your safety. Call us on 600 575 556.") as? ParseResult.Ignored)?.reason == "Card blocked notice")
        // unless: a purchase in it, so the ignore yields
        assertFalse((read("CBD", "Your card has been blocked after a purchase of AED 99.00 at SHOP. Card ending 1234.") as? ParseResult.Ignored)?.reason == "Card blocked notice")
        // the lever off: the ignore with unless is not used at all (never hides more than intended)
        apply(file(ext = "\"ignore\":[$ig]"))
        assertFalse((read("CBD", "Your card has been blocked for your safety. Call us on 600 575 556.") as? ParseResult.Ignored)?.reason == "Card blocked notice")
    }

    @Test fun minWordsRefusesVagueIgnores() {
        val vague = ignore("Vague", """\bnotice\b.{0,20}\bplease\b""", ",\"minWords\":6")
        assertNotNull((RulesPack.parse(file(ext = "\"ignore\":[$vague]")) as RulesPack.Outcome.Ok).pack.extRejected)
        val specific = ignore("Specific", """your reward points will expire at the end of this month unless redeemed""", ",\"minWords\":6")
        assertNull((RulesPack.parse(file(ext = "\"ignore\":[$specific]")) as RulesPack.Outcome.Ok).pack.extRejected)
    }

    // ---- 3.8 statements

    @Test fun statementRulesCarryTotalMinimumDueAndStatementDate() {
        val rules = listOf(
            rule("HDFC Bank", "hdfc-stmt", """Statement for card {CARD} dated {STMTDATE}: Total due {CUR} {TOTAL}, Min due {ANYCUR}\.? {MIN}, Due by {DUE}""", kind = "STATEMENT", cardType = "CREDIT"),
            rule("Emirates NBD", "amex-bill", """bill for Card ending {CARD} is {CUR} {TOTAL}\. Minimum payment {ANYCUR} {MIN} is due on {DUE}""", kind = "STATEMENT", cardType = "CREDIT"),
        ).joinToString(",")
        apply(file(ext = levers("curAbbrev") + ",\"rules\":[$rules]"))
        SmsParser.setHomeCurrency("INR")
        try {
            for (due in listOf("05-DEC-26", "5 Dec 2026", "05DEC26", "Dec 05, 2026", "05/12/26")) {
                val s = read("HDFC Bank", "Statement for card 4321 dated 15-Nov-26: Total due Rs. 12,345.00, Min due INR 620.00, Due by $due")
                assertIs<ParseResult.Statement>(s, due)
                assertEquals(Decimal("12345.00"), s.statement.statementBalance)
                assertEquals(Decimal("620.00"), s.statement.minimumDue)
                assertEquals(2026, s.statement.dueDate.year, due); assertEquals(12, s.statement.dueDate.monthValue, due); assertEquals(5, s.statement.dueDate.dayOfMonth, due)
                assertNotNull(s.statement.statementDate)
            }
        } finally { SmsParser.setHomeCurrency("AED") }
        val amex = read("Emirates NBD", "Your bill for Card ending 1005 is AED 3,210.50. Minimum payment AED 160.00 is due on 20/12/2026")
        assertIs<ParseResult.Statement>(amex)
        assertEquals(Decimal("160.00"), amex.statement.minimumDue)
    }

    // ---- 17 sanity check

    @Test fun sanityCheckSendsAContradictoryExtReadingToReview() {
        val r = rule("Sharjah Islamic Bank", "sib-bad", """{CUR} {AMOUNT} credited to your account {ACCT}""", type = "PURCHASE", cardType = "ACCOUNT")
        apply(file(ext = levers("acctToken") + ",\"rules\":[$r]"))
        val out = read("Sharjah Islamic Bank", "AED 500.00 credited to your account XXXX1234. Avl bal AED 900.00")
        assertFalse(out is ParseResult.Transaction && out.ruleId == "pack:sib-bad", "a PURCHASE rule on a credit wording must not read: $out")
        if (out is ParseResult.Failed) assertEquals(Stage.SANITY, out.stage)
        // a rule that names its direction may read a message with both words
        val r2 = rule("Sharjah Islamic Bank", "sib-ok", """{CUR} {AMOUNT} debited from your account {ACCT} and credited to""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"direction\":\"OUT\"")
        apply(file(ext = levers("acctToken") + ",\"rules\":[$r2]"))
        assertIs<ParseResult.Transaction>(read("Sharjah Islamic Bank", "AED 500.00 debited from your account XXXX1234 and credited to account XXXX9876."))
    }

    // ---- 4.6 safe mode

    @Test fun safeModeDropsEveryPackRuleIgnoreAndWord() {
        val legacy = "\"rules\":[" + rule("Sharjah Islamic Bank", "sib-1", """Within UAE Funds Transfer of {CUR} {AMOUNT} from {CARD}""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Transfer\"") + "]," +
            "\"ignore\":[" + ignore("Survey", """please rate your branch visit today""") + "]"
        val body = "Within UAE Funds Transfer of AED 700.00 from 1234 is complete."
        apply(file(legacy = legacy))
        assertIs<ParseResult.Transaction>(read("Sharjah Islamic Bank", body))
        apply(file(legacy = legacy, ext = "\"safeMode\":true"))
        assertTrue(RulesPack.safeMode)
        assertFalse((read("Sharjah Islamic Bank", body) as? ParseResult.Transaction)?.ruleId == "pack:sib-1")
        assertFalse((read("Sharjah Islamic Bank", "Please rate your branch visit today, AED 0 fee") as? ParseResult.Ignored)?.reason == "Survey")
    }

    // ---- 4.4 / 4.7 / 4.3 parsing of ask, keyChange, notificationBanks

    @Test fun askKeyChangeAndNotificationBanksNeedTheirLevers() {
        val ext = "\"ask\":[{\"bank\":\"HSBC\",\"hash\":null,\"fields\":[\"layout\",\"stage\"],\"until\":40},{\"sender\":\"DFM\",\"fields\":[\"fullerWords\"],\"until\":40}]," +
            "\"readingPolicy\":{\"applyKeyChangeFor\":[\"ADCB\"]}," +
            "\"notificationBanks\":[{\"package\":\"com.google.android.apps.walletnfcrel\",\"bank\":\"Wallet\",\"isAggregator\":true}]"
        val walletBank = ",\"banks\":[{\"name\":\"Wallet\",\"country\":\"AE\",\"senderIds\":[\"WALLET\"],\"track\":true}]"
        apply(file(ext = ext + walletBank))
        assertTrue(RulesPack.ask.isEmpty()); assertTrue(ReadingPolicy.keyChangeBanks.isEmpty()); assertTrue(RulesPack.notificationBanks.isEmpty())
        apply(file(ext = levers("ask", "keyChange", "notificationBanks") + "," + ext + walletBank))
        assertEquals(2, RulesPack.ask.size); assertEquals("DFM", RulesPack.ask[1].sender)
        assertEquals(setOf("adcb"), ReadingPolicy.keyChangeBanks)
        assertEquals("Wallet", RulesPack.notificationBanks.single().bank)
        assertTrue("wallet" in SmsParser.aggregatorBanks)
    }
}
