package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Blind test on REAL PDFs: 8 made-up statements in layouts the reader had never seen (values above their labels,
 * Arabic/English labels, credit balances with Dr/Cr words, rewards and instalment tables that must not count,
 * a two-page account statement with brought-forward rows, foreign-amount columns, terse abbreviations, a
 * supplementary card with subtotal lines). They were rendered as PDFs with proportional fonts and right-aligned
 * amounts, then read back character by character (StatementFixtures.kt: page, x, y, width, size, text),
 * the same input the app gets from PdfBox on the phone. Generated so every statement adds up.
 */
class StatementReaderPdfTest {
    private val names = listOf(
        "b1_values_above_labels", "b2_bilingual_arabic", "b3_credit_balance_dr_cr_words", "b4_rewards_and_instalment_tables",
        "b5_account_two_pages_brought_forward", "b6_foreign_amount_columns", "b7_terse_abbreviations_iso", "b8_supplementary_card_subtotals",
    )

    /** Round 2: written and scored before the reader was changed for it. */
    private val round2 = listOf(
        "c1_balance_box_ref_numbers", "c2_dd_mmm_yy_aed_suffix", "c3_values_before_labels", "c4_account_one_line_summary",
        "c5_prose_due_and_minimum", "c6_two_line_rows_repeated_page_header", "c7_dhs_no_year_dates", "c8_purchases_payments_points_columns",
    )

    private fun load(name: String): Pair<Map<String, String>, List<Glyph>> {
        val text = StatementFixtures.all.getValue(name)
        val expect = mutableMapOf<String, String>()
        val glyphs = mutableListOf<Glyph>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) { val (k, v) = line.drop(1).split("=", limit = 2); expect[k] = v; continue }
            val p = line.split("\t")
            glyphs += Glyph(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5])
        }
        return expect to glyphs
    }

    private fun check(name: String): List<String> {
        val (e, glyphs) = load(name)
        val a = StatementReader.analyze(StatementReader.linesFromGlyphs(glyphs), 2026)
        val s = a.summary
        val bad = mutableListOf<String>()
        fun cmp(field: String, want: Any?, got: Any?) { if (want != null && want != got) bad += "$name.$field: want $want got $got" }
        cmp("statementDate", e["sd"]?.let(CalendarDate::parse), s.statementDate)
        cmp("dueDate", e["dd"]?.let(CalendarDate::parse), s.dueDate)
        cmp("totalDue", e["total"]?.toLong(), s.totalDueMinor)
        cmp("minimumDue", e["min"]?.toLong(), s.minimumDueMinor)
        cmp("creditLimit", e["limit"]?.toLong(), s.creditLimitMinor)
        cmp("available", e["avail"]?.toLong(), s.availableLimitMinor)
        cmp("previous", e["prev"]?.toLong(), s.previousBalanceMinor)
        cmp("last4", e["last4"], s.cardLast4)
        cmp("isAccount", e["account"]?.toBoolean(), s.isAccount)
        cmp("lines", e["n"]?.toInt(), a.lines.size)
        cmp("spends", e["deb"]?.toLong(), a.lines.filterNot { it.isCredit }.sumOf { it.amountMinor })
        cmp("credits", e["cred"]?.toLong(), a.lines.filter { it.isCredit }.sumOf { it.amountMinor })
        cmp("addsUp", e["agree"]?.toBoolean(), a.totalsAgree)
        return bad
    }

    @Test fun blind_pdf_statements() {
        val results = names.associateWith { check(it) }
        val ok = results.count { it.value.isEmpty() }
        println("BLIND PDF STATEMENTS: $ok/${names.size} fully right, ${results.values.sumOf { it.size }} wrong fields")
        results.values.flatten().forEach { println("  $it") }
        assertEquals(emptyList(), results.values.flatten())
    }

    /** Round 3: written and scored before the reader was changed for it. */
    private val round3 = listOf(
        "d1_amounts_inside_descriptions", "d2_label_colon_value_below", "d3_dot_dates_closing_date",
        "d4_account_signed_running_balance", "d5_summary_right_of_address", "d6_payment_reversal_fees",
    )

    @Test fun blind_pdf_statements_round_3() {
        val results = round3.associateWith { check(it) }
        val ok = results.count { it.value.isEmpty() }
        println("BLIND PDF ROUND 3: $ok/${round3.size} fully right, ${results.values.sumOf { it.size }} wrong fields")
        results.values.flatten().forEach { println("  $it") }
        assertEquals(emptyList(), results.values.flatten())
    }

    @Test fun blind_pdf_statements_round_2() {
        val results = round2.associateWith { check(it) }
        val ok = results.count { it.value.isEmpty() }
        println("BLIND PDF ROUND 2: $ok/${round2.size} fully right, ${results.values.sumOf { it.size }} wrong fields")
        results.values.flatten().forEach { println("  $it") }
        assertEquals(emptyList(), results.values.flatten())
    }
}
