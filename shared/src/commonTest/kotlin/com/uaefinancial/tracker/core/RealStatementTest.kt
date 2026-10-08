package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real statements (personal details blanked, see tools/embed_real_statement.py). The synthetic statements in
 * StatementReaderPdfTest were made up by us; these are what banks really print.
 */
class RealStatementTest {
    private fun analyze(fixture: String): Pair<Map<String, String>, StatementAnalysis> {
        val expect = mutableMapOf<String, String>()
        val glyphs = mutableListOf<Glyph>()
        for (line in fixture.lines()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) { val (k, v) = line.drop(1).split("=", limit = 2); expect[k] = v; continue }
            val p = line.split("\t")
            glyphs += Glyph(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5])
        }
        return expect to StatementReader.analyze(StatementReader.linesFromGlyphs(glyphs), 2026)
    }

    /**
     * RAKBANK Air Arabia card with an instalment plan. The available limit is printed under a caption that wraps onto
     * two rows ("Available Credit / Card Limit"), straight below the credit limit's own figure; and a table of
     * instalment plans, with dates and amounts of its own, follows the transactions.
     */
    @Test fun rakbank_card_with_instalment_plan() {
        val (e, a) = analyze(RealStatementFixtures.rak_air_arabia_instalments)
        val s = a.summary
        assertEquals(CalendarDate.parse(e.getValue("sd")), s.statementDate, "statement date")
        assertEquals(CalendarDate.parse(e.getValue("dd")), s.dueDate, "due date")
        assertEquals(e.getValue("limit").toLong(), s.creditLimitMinor, "credit limit")
        assertEquals(e.getValue("avail").toLong(), s.availableLimitMinor, "available limit (not the credit limit)")
        assertEquals(e.getValue("total").toLong(), s.totalDueMinor, "amount due")
        assertEquals(e.getValue("min").toLong(), s.minimumDueMinor, "minimum due")
        assertEquals(e.getValue("prev").toLong(), s.previousBalanceMinor, "previous balance")
        assertEquals(e.getValue("n").toInt(), a.lines.size, "transactions: the instalment plan table is not one")
        assertEquals(e.getValue("deb").toLong(), a.lines.filterNot { it.isCredit }.sumOf { it.amountMinor }, "spends")
        assertEquals(e.getValue("cred").toLong(), a.lines.filter { it.isCredit }.sumOf { it.amountMinor }, "credits")
        assertTrue(a.totalsAgree == true, "the lines add up to the statement: ${a.totalsCheck}")
    }
}
