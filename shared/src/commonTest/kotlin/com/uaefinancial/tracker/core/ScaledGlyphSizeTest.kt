package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Synthetic fixture for the HSBC Live+ case: the PDF reports a font size 10x the shown size. */
class ScaledGlyphSizeTest {
    private fun line(page: Int, y: Float, text: String, size: Float): List<Glyph> {
        var x = 40f
        return text.map { ch -> Glyph(page, x, y, 5f, size, ch.toString()).also { x += 5f } }
    }

    @Test fun a_reported_size_far_above_the_displayed_height_is_replaced_by_the_displayed_height() {
        assertEquals(9f, GlyphSize.displayed(90f, 9f))
        assertEquals(10f, GlyphSize.displayed(10f, 9f))   // normal: reported stays
        assertEquals(10f, GlyphSize.displayed(10f, 0f))   // no height: reported stays
    }

    @Test fun lines_twelve_units_apart_are_two_lines_with_the_displayed_size_and_merge_with_the_scaled_one() {
        fun glyphs(size: Float) = line(0, 100f, "LULU HYPERMARKET", size) + line(0, 112f, "05 Nov 2026", size)
        val scaled = StatementReader.linesFromGlyphs(glyphs(90f))
        assertEquals(1, scaled.size) // the fault: 12 apart is inside 0.35 x 90
        val fixed = StatementReader.linesFromGlyphs(glyphs(GlyphSize.displayed(90f, 9f)))
        assertEquals(listOf("LULU HYPERMARKET", "05 Nov 2026"), fixed.map { it.text })
    }
}
