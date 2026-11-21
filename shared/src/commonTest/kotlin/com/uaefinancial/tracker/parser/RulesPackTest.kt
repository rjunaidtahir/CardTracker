package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesPackTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun pack(rules: String, version: Int = 3, banks: String = "[]", extra: String = "") =
        """{"version":$version,$extra"banks":$banks,"rules":$rules}"""

    private val goodRule = """[{"bank":"HBL","id":"hbl-1","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT",
        "pattern":"spent {CUR} {AMOUNT} at {MERCHANT} card {CARD}"}]"""

    @Test fun acceptsAGoodPackAndReadsWithItsRule() {
        val out = RulesPack.parse(pack(goodRule, banks = """[{"name":"HBL","country":"PK","aliases":["Habib Bank"],"senderIds":["HBLBank"]}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        val r = SmsParser.parseAsBank("HBL", "Dear customer you spent PKR 1,500.00 at Daraz card 4321", 0L)
        assertIs<ParseResult.Transaction>(r)
        assertEquals("pack:hbl-1", r.ruleId)
        assertNotNull(GlobalBanks.match("HBLBank"))
    }

    @Test fun packNeverOverridesABuiltInRule() {
        val builtIn = BankRules.banks.first { it.rules.isNotEmpty() }
        val rule = builtIn.rules.first { it.kind == RuleKind.TRANSACTION }
        val greedy = """[{"bank":"${builtIn.name}","id":"greedy","kind":"TRANSACTION","type":"REFUND","pattern":"{CUR} {AMOUNT}"}]"""
        val out = RulesPack.parse(pack(greedy))
        assertIs<RulesPack.Outcome.Ok>(out)
        assertTrue(out.pack.rules.single().rule.id.startsWith("pack:"))
        assertTrue(rule.id.isNotEmpty())
    }

    @Test fun rejectsBadFiles() {
        fun rejected(json: String) = assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(json), json)
        rejected("not json")
        rejected("""{"banks":[],"rules":[]}""")                                           // no version
        rejected(pack("""[{"bank":"Nobody Bank","id":"x","kind":"TRANSACTION","pattern":"{AMOUNT}"}]"""))  // unknown bank
        rejected(pack("""[{"bank":"HBL","id":"x","kind":"TRANSACTION","pattern":"(a+)+ {AMOUNT}"}]"""))     // nested repetition
        rejected(pack("""[{"bank":"HBL","id":"x","kind":"TRANSACTION","pattern":"no amount token"}]"""))
        rejected(pack("""[{"bank":"HBL","id":"x","kind":"TRANSACTION","pattern":"(unclosed {AMOUNT}"}]"""))
        rejected(pack("""[{"bank":"HBL","id":"x","kind":"BOGUS","pattern":"{AMOUNT}"}]"""))
        rejected(pack("[]", extra = "\"minEngine\":999,"))
        rejected(pack("[]", banks = """[{"name":"X","country":"PK","senderIds":["AB"]}]"""))   // too-short sender ID
        rejected("{\"version\":1,\"banks\":[],\"rules\":[]} trailing")
    }

    @Test fun versionCannotGoBackwards() {
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", version = 2), currentVersion = 5))
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(pack("[]", version = 5), currentVersion = 5))
    }

    @Test fun clearingThePackWithdrawsItsRules() {
        val out = RulesPack.parse(pack(goodRule)) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        assertEquals(1, SmsParser.packRuleCount())
        RulesPack.apply(null)
        assertEquals(0, SmsParser.packRuleCount())
    }

    @Test fun directoryMatchesNamesCarefully() {
        assertEquals("HDFC Bank", GlobalBanks.match("AX-HDFCBK-S")?.name)
        assertEquals("Meezan Bank", GlobalBanks.match("Meezan")?.name)
        assertEquals("Bank Alfalah", GlobalBanks.match("BankAlfalah")?.name)
        assertEquals(null, GlobalBanks.match("DBX"))
        assertEquals(null, GlobalBanks.match("MOM"))
    }

    private fun packWith(extra: String) = """{"version":9,"banks":[],"rules":[],$extra"minEngine":5}"""

    @Test fun ignoreRulesFromThePackStopANoticeBeingRead() {
        SmsParser.setCustomSenders(mapOf("PKBANK" to "Some Bank"))
        val body = "Dear Customer, kindly pay PKR 500.00 fee to keep your locker, visit your branch by Friday"
        val before = SmsParser.parse("PKBANK", body, 0L)
        val out = RulesPack.parse(packWith(""""ignore":[{"label":"Locker notice","pattern":"keep your locker"}],"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        val after = SmsParser.parse("PKBANK", body, 0L)
        assertIs<ParseResult.Ignored>(after)
        assertEquals("Locker notice", after.reason)
        RulesPack.apply(null)
        assertEquals(before::class, SmsParser.parse("PKBANK", body, 0L)::class)
        SmsParser.setCustomSenders(emptyMap())
    }

    @Test fun anIgnoreRuleThatWouldHideTransactionsIsRefused() {
        val out = RulesPack.parse(packWith(""""ignore":[{"label":"Too wide","pattern":"AED|PKR|debited"}],"""))
        assertIs<RulesPack.Outcome.Rejected>(out)
        val nested = RulesPack.parse(packWith(""""ignore":[{"label":"Slow","pattern":"(a+)+b notice"}],"""))
        assertIs<RulesPack.Outcome.Rejected>(nested)
    }

    @Test fun extraWordsTeachTheReaderANewVerb() {
        SmsParser.setCustomSenders(mapOf("PKBANK" to "Some Bank"))
        val body = "Dear Customer, You have zorbed an item of PKR 700.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026"
        val out = RulesPack.parse(packWith(""""words":{"debit":["zorbed"],"transfer":["zorbed"]},"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        val r = SmsParser.parse("PKBANK", body, 0L)
        assertIs<ParseResult.Transaction>(r)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
        val bad = RulesPack.parse(packWith(""""words":{"debit":["not a word!"]},"""))
        assertIs<RulesPack.Outcome.Rejected>(bad)
        SmsParser.setCustomSenders(emptyMap())
    }
}

class PackTrackedSendersTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun pack(banks: String) = """{"version":9,"banks":$banks,"rules":[]}"""

    @Test fun packTracksANewSenderWithoutAnAppUpdate() {
        assertEquals(null, SmsParser.bankFor("NewPayCo"))
        val out = RulesPack.parse(pack("""[{"name":"NewPayCo","country":"AE","senderIds":["NewPayCo"],"track":true}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        assertEquals("NewPayCo", SmsParser.bankFor("NewPayCo")?.name)
        assertEquals("NewPayCo", SmsParser.bankFor("AD-NewPayCo")?.name)
        assertTrue(SmsParser.isPackSender("NewPayCo"))
        RulesPack.apply(null)
        assertEquals(null, SmsParser.bankFor("NewPayCo"))
    }

    @Test fun trackedSenderOfABuiltInBankUsesItsVerifiedRules() {
        val out = RulesPack.parse(pack("""[{"name":"ADCB","country":"AE","senderIds":["ZZCardUnit"],"track":true}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        assertEquals("ADCB", SmsParser.bankFor("ZZCardUnit")?.name)
    }

    @Test fun simplyLifeAndTabbyAreKnown() {
        assertEquals("ADCB", SmsParser.bankFor("SIMPLYLIFE")?.name)
        assertEquals("Tabby", SmsParser.bankFor("Tabby")?.name)
        val r = SmsParser.parse("SIMPLYLIFE", "Your Cr.Card XXX5886 was used for USD30.60 on 04/08/2023 11:04:30 at OW *passcert.com,London-GB. Avl. Cr.limit is AED2882.95", 0L)
        assertIs<ParseResult.Transaction>(r)
    }

    @Test fun userExclusionAndBuiltInsStillWin() {
        val out = RulesPack.parse(pack("""[{"name":"ADCB","country":"AE","senderIds":["ADCBAlert"],"track":true},{"name":"NewPayCo","country":"AE","senderIds":["NewPayCo"],"track":true}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        SmsParser.setExcludedBanks(setOf("NewPayCo"))
        try {
            assertEquals(null, SmsParser.bankFor("NewPayCo"))
            assertEquals("NewPayCo", SmsParser.anyBankFor("NewPayCo")?.name)
        } finally { SmsParser.setExcludedBanks(emptySet()) }
    }

    @Test fun rejectsPhoneNumbersAndTinyIds() {
        fun rejected(ids: String) = assertIs<RulesPack.Outcome.Rejected>(
            RulesPack.parse(pack("""[{"name":"X Pay","country":"AE","senderIds":$ids,"track":true}]""")), ids)
        rejected("""["+971501234567"]""")
        rejected("""["971501234567"]""")
        rejected("""["AB"]""")
        rejected("""["12"]""")
        rejected("[]")
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(pack("""[{"name":"X Pay","country":"PK","senderIds":["8258"],"track":true}]""")))
    }

    @Test fun shippedPackParsesAndTracksTabbyAndSimplyLife() {
        // The same text as rules/pack.json banks section.
        val out = RulesPack.parse(pack("""[{"name":"Tabby","country":"AE","senderIds":["Tabby"],"track":true},{"name":"ADCB","country":"AE","aliases":["SimplyLife"],"senderIds":["SIMPLYLIFE"],"track":true},{"name":"Tamara","country":"AE"}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
    }

    @Test fun sendMoreRequestIsParsedAndChecked() {
        val ok = RulesPack.parse("""{"version":9,"sendMore":{"hashes":["abc123def456"],"banks":["RAKBANK"]},"banks":[],"rules":[]}""")
        assertIs<RulesPack.Outcome.Ok>(ok)
        assertEquals(setOf("abc123def456"), ok.pack.sendMore.hashes)
        assertEquals(setOf("rakbank"), ok.pack.sendMore.banks)
        assertTrue(RulesPack.parse("""{"version":9,"sendMore":{"hashes":[5]},"banks":[],"rules":[]}""") is RulesPack.Outcome.Rejected)
        assertTrue(RulesPack.parse("""{"version":9,"banks":[],"rules":[]}""").let { it is RulesPack.Outcome.Ok && it.pack.sendMore.hashes.isEmpty() })
    }

    // ---- 2.6.1: remote off switch for built-in rules, and the re-read policy
    private val fabOut = "Outward Remittance Debit Account XXXX8001 AED 1000.00 Date 17/09/2026 21:45 Balance AED 2386.00"

    @Test fun disableSwitchesOffNamedBuiltInRuleOnly() {
        val before = SmsParser.parseAsBank("FAB", fabOut, 0L)
        assertIs<ParseResult.Transaction>(before)
        assertEquals("fab-outward-remittance", before.ruleId)
        val out = RulesPack.parse(pack("[]", extra = "\"disable\":[\"fab-outward-remittance\"],"))
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        val after = SmsParser.parseAsBank("FAB", fabOut, 0L)
        assertTrue(after !is ParseResult.Transaction || after.ruleId != "fab-outward-remittance")
        // another built-in rule of the same bank still works
        val other = SmsParser.parseAsBank("FAB", "Inward Remittance Credit Account XXXX8001 AED 500.00 Date 17/09/2026 21:45 Balance AED 2886.00", 0L)
        assertIs<ParseResult.Transaction>(other)
        assertEquals("fab-inward-remittance", other.ruleId)
        // withdrawing the switch (a newer file without it) turns the rule back on
        RulesPack.apply(null)
        assertEquals("fab-outward-remittance", (SmsParser.parseAsBank("FAB", fabOut, 0L) as ParseResult.Transaction).ruleId)
    }

    @Test fun disableRejectsUnknownIdsPackRulesAndIgnoreLabels() {
        for (bad in listOf("\"no-such-rule\"", "\"pack:hbl-1\"", "\"Advert\"", "1", "\"\"")) {
            assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", extra = "\"disable\":[$bad],")), bad)
        }
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", extra = "\"disable\":\"fab-outward-remittance\",")))
        val tooMany = (1..21).joinToString(",") { "\"fab-outward-remittance\"" }
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", extra = "\"disable\":[$tooMany],")))
    }

    @Test fun readingPolicyAcceptsKnownBanksOnly() {
        val ok = RulesPack.parse(pack("[]", extra = "\"readingPolicy\":{\"applyNewFor\":[\"FAB\"]},"))
        assertIs<RulesPack.Outcome.Ok>(ok)
        assertEquals(setOf("fab"), ok.pack.softNew)
        RulesPack.apply(ok.pack)
        assertEquals(setOf("fab"), ReadingPolicy.softNewBanks)
        RulesPack.apply(null)
        assertEquals(emptySet(), ReadingPolicy.softNewBanks)
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", extra = "\"readingPolicy\":{\"applyNewFor\":[\"No Such Bank\"]},")))
        assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack("[]", extra = "\"readingPolicy\":{\"applyNewFor\":\"FAB\"},")))
    }

    @Test fun aPackWithoutTheNewSectionsStillParses() {
        val ok = RulesPack.parse(pack("[]"))
        assertIs<RulesPack.Outcome.Ok>(ok)
        assertTrue(ok.pack.disable.isEmpty() && ok.pack.softNew.isEmpty())
    }
}
