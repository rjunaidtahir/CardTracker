package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The DRAFT Tabby statement rule (rules/drafts/tabby-statement-rule.json) is not part of the published rules file.
 * This proves that, added to the shipped pack, it passes the app's own checks and reads the real Tabby statement.
 */
class TabbyStatementDraftTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun find(name: String) = listOf(File("../$name"), File(name)).first { it.exists() }.readText()
    private val stmt = "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app."

    @Test fun draftRuleIsAcceptedAndReadsTheRealMessage() {
        val pack = find("rules/pack.json")
        val rule = find("rules/drafts/tabby-statement-rule.json").trim()
        val marker = Regex(""""rules"\s*:\s*\[""").find(pack)!!
        val withRule = pack.substring(0, marker.range.last + 1) + rule + "," + pack.substring(marker.range.last + 1)
        val out = RulesPack.parse(withRule, currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        RulesPack.apply(out.pack)
        val r = SmsParser.parse("Tabby", stmt, 0L)
        assertIs<ParseResult.Statement>(r)
        assertEquals("tabby-statement-20261119", r.ruleId)
        assertEquals("511.06", r.statement.statementBalance.toPlainString())
        assertEquals("127.78", r.statement.minimumDue?.toPlainString())
    }
}
