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
}
