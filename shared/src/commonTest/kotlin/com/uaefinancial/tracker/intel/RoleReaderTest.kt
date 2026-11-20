package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoleReaderTest {
    private val tabbyStatement = "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app."

    /** The honest test: the model is trained WITHOUT any Tabby message, then must read the Tabby statement. */
    @Test fun tabbyStatementIsReadByAModelThatNeverSawTabby() {
        val m = RoleModel(RoleCorpus.samples.filter { it.group != "Tabby" })
        val text = SmsParser.normalizeBody(tabbyStatement)
        val spans = SmartParser.roleSpans(text)
        val g = m.guess(text, spans)
        println("ROLE Tabby held out: " + spans.indices.joinToString { i -> "${text.substring(spans[i].start, spans[i].end)}=${g[i].role}(${(g[i].p * 100).toInt()}%)" })
        val r = RoleReader.readStatement("Tabby", text, with = m)
        println("ROLE Tabby held out result: " + r?.statement)
        assertNotNull(r, "the role reader must read the Tabby statement without ever having seen Tabby")
        assertEquals("511.06", r.statement.statementBalance.toPlainString())
        assertEquals("127.78", r.statement.minimumDue?.toPlainString())
        assertEquals(CalendarDate.of(2026, 6, 30), r.statement.dueDate)
    }

    /** Leave one bank out: how often each role is right on a bank the model never saw, and how it behaves as a statement reader. */
    @Test fun leaveOneBankOutReport() {
        val all = RoleCorpus.samples
        var n = 0; var right = 0; var key = 0; var keyRight = 0; var keyConf = 0; var keyConfWrong = 0
        var stmts = 0; var stmtRight = 0; var stmtWrong = 0; var stmtReview = 0
        var nonStmt = 0; var falseStmt = 0
        val keyRoles = setOf("TOTAL", "MIN", "DUE")
        for (bank in all.map { it.group }.distinct()) {
            val train = all.filter { it.group != bank }
            val test = all.filter { it.group == bank }
            if (train.size < 40) continue
            val m = RoleModel(train)
            for (s in test) {
                val spans = SmartParser.roleSpans(s.body)
                val g = m.guess(s.body, spans)
                // The sample's own gold roles in the same order (same spans, same function).
                for ((i, mention) in s.mentions.withIndex()) {
                    if (i >= g.size) break
                    n++; if (g[i].role == mention.role) right++
                    if (mention.role in keyRoles || g[i].role in keyRoles) {
                        key++; if (g[i].role == mention.role) keyRight++
                        if (g[i].p >= RoleReader.CONFIDENT && g[i].role in keyRoles) { keyConf++; if (g[i].role != mention.role) keyConfWrong++ }
                    }
                }
                val r = RoleReader.readStatement(s.group, s.body, with = m)
                if (s.hasStatement) {
                    stmts++
                    if (r == null) stmtReview++
                    else {
                        // right = same statement amount as the sample's TOTAL mention
                        val goldTotal = s.mentions.indexOfFirst { it.role == "TOTAL" }.takeIf { it >= 0 }?.let { spans[it].amount }
                        if (goldTotal != null && r.statement.statementBalance.compareTo(goldTotal) == 0) stmtRight++ else stmtWrong++
                    }
                } else { nonStmt++; if (r != null) falseStmt++ }
            }
        }
        println("ROLE leave-one-bank-out: every amount/date role right ${right}/${n}; total/minimum/due roles right ${keyRight}/${key}; " +
            "confident total/min/due answers wrong ${keyConfWrong}/${keyConf}")
        println("ROLE statements (unseen bank): read right $stmtRight, read wrong $stmtWrong, left for review $stmtReview, of $stmts; " +
            "non-statements wrongly read as statements $falseStmt of $nonStmt")
        assertTrue(n > 100)
    }
}
