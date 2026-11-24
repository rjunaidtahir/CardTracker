package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.RulesPack
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2.8 spec 12: on HSBC Live+ statements the shop name is printed on the line ABOVE the dated row, which shows only a place
 * ("MUSCAT OM OMR/AED .104790419"). Synthetic fixtures only (no real statement text): rows with the name above, a wrapped
 * description above (never taken), a row with nothing above, scaled and unscaled font sizes, the kill switch, and other
 * banks' statements unchanged.
 */
class LivePlusNamesTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    /** A printed line at [y] (points), characters 5 points apart. */
    private fun at(y: Float, t: String, page: Int = 0) = PrintedLine(page, y, t, FloatArray(t.length) { it * 5f })

    private val head = listOf(
        at(10f, "HSBC Bank Middle East Limited"),
        at(22f, "Credit Card Statement"),
        at(34f, "Statement Date              10/11/2026"),
        at(46f, "Card Number 4000 12** **** 5555"),
        at(100f, "Transaction Date  Posting Date  Transaction Details              Debit        Credit"),
    )

    /** Rows 14 points apart; a shop name sits 8 points above its row, a wrapped description 6 points below the row it belongs to. */
    private val body = listOf(
        at(118f, "LULU HYPERMARKET"),
        at(126f, "02/11/2026        03/11/2026    DUBAI AE                         120.00"),
        at(140f, "SOME CAFE LLC"),
        at(148f, "04/11/2026        05/11/2026    MUSCAT OM OMR/AED .104790419      45.30"),
        at(162f, "06/11/2026        07/11/2026    ONLINE STORE DUBAI AE             80.00"),
        at(168f, "ORDER REF WRAPPED TEXT"),
        at(184f, "08/11/2026        09/11/2026    SHARJAH AE                        20.00"),
        at(198f, "10/11/2026        11/11/2026    ABU DHABI AE                      10.00"),
    )

    private fun read(lines: List<PrintedLine>) = StatementReader.analyze(lines, 2026)

    @Test fun theShopNameComesFromTheLineAbove() {
        val a = read(head + body)
        assertEquals(listOf("LULU HYPERMARKET", "SOME CAFE LLC"), a.lines.take(2).map { it.description })
        assertEquals(listOf(12_000L, 4_530L), a.lines.take(2).map { it.amountMinor }, "amounts unchanged")
        assertTrue(a.nameStats.livePlus)
        assertEquals(2, a.nameStats.merchantFromAbove)
    }

    @Test fun aWrappedDescriptionAboveIsNeverTakenAndARowWithNothingAboveKeepsItsText() {
        val a = read(head + body)
        assertEquals(5, a.lines.size)
        assertTrue(a.lines[2].description.startsWith("ONLINE STORE"), a.lines[2].description)
        assertFalse(a.lines[3].description.contains("WRAPPED"), "the wrap belongs to the row above it: ${a.lines[3].description}")
        assertFalse(a.lines[4].description.contains("SHARJAH"), "no line above: ${a.lines[4].description}")
        assertEquals(2, a.nameStats.placeholder)
    }

    @Test fun scaledAndUnscaledFontSizesReadTheSame() {
        fun glyphs(size: Float) = (head + body).flatMap { l -> l.text.mapIndexed { i, ch -> Glyph(0, 40f + i * 5f, l.y, 5f, size, ch.toString()) } }
        val unscaled = read(StatementReader.linesFromGlyphs(glyphs(9f)))
        val scaled = read(StatementReader.linesFromGlyphs(glyphs(GlyphSize.displayed(90f, 9f))))
        assertEquals(unscaled.lines.map { it.description to it.amountMinor }, scaled.lines.map { it.description to it.amountMinor })
        assertEquals("LULU HYPERMARKET", unscaled.lines.first().description)
    }

    @Test fun theKillSwitchRestoresThe27Reading() {
        val on = read(head + body)
        val p = RulesPack.parse("""{"version":40,"minEngine":5,"banks":[],"rules":[],"ext":{"levers":{"hsbcLivePlusNames":false}}}""")
        RulesPack.apply((p as RulesPack.Outcome.Ok).pack)
        val off = read(head + body)
        assertFalse(off.nameStats.livePlus)
        assertFalse(off.lines.any { it.description == "LULU HYPERMARKET" })
        assertEquals(on.lines.map { it.amountMinor to it.date }, off.lines.map { it.amountMinor to it.date }, "amounts and dates never depend on it")
    }

    @Test fun anotherBanksStatementIsReadIdenticallyWithAndWithoutTheFix() {
        val other = listOf(
            at(10f, "Some Other Bank PJSC"),
            at(22f, "Credit Card Statement"),
            at(34f, "Statement Date              10/11/2026"),
            at(100f, "Transaction Date  Posting Date  Transaction Details              Debit        Credit"),
        ) + body
        val on = read(other)
        val p = RulesPack.parse("""{"version":40,"minEngine":5,"banks":[],"rules":[],"ext":{"levers":{"hsbcLivePlusNames":false}}}""")
        RulesPack.apply((p as RulesPack.Outcome.Ok).pack)
        val off = read(other)
        assertFalse(on.nameStats.livePlus)
        assertEquals(off.lines, on.lines)
        assertEquals(off.summary, on.summary)
    }

    @Test fun anHsbcStatementWithoutTheLivePlusHeaderIsNotChanged() {
        val plain = listOf(at(10f, "HSBC Bank Middle East"), at(100f, "Date    Description                               Amount (AED)")) +
            listOf(at(118f, "LULU HYPERMARKET"), at(126f, "02/11/2026  DUBAI AE                            120.00"))
        assertFalse(read(plain).nameStats.livePlus)
    }

    /** Spec 12 first start: only a saved name that is a place line (city, country, currency pair, digits) is marked; invented names. */
    @Test fun onlyAPlaceLineLooksLikeAGarbledShopName() {
        assertTrue(StatementReader.looksLikePlaceNotShop("MUSCAT OM OMR/AED .104790419"))
        assertTrue(StatementReader.looksLikePlaceNotShop("NEW TOWN GB GBP/AED 4.6512"))
        assertFalse(StatementReader.looksLikePlaceNotShop("CARREFOUR CITY CENTRE"))
        assertFalse(StatementReader.looksLikePlaceNotShop("MUSCAT OM"))
        assertFalse(StatementReader.looksLikePlaceNotShop("LULU HYPERMARKET OM OMR/AED"))
        assertFalse(StatementReader.looksLikePlaceNotShop("AMAZON AE 12345"))
        assertFalse(StatementReader.looksLikePlaceNotShop(null))
    }
}
