package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.Masker
import com.uaefinancial.tracker.parser.RulesPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Layout form for a message whose amount the normal masker cannot place: digit lengths only, never digits. */
class LayoutProbeTest {
    private val body = "From HSBC: 21/11/2026 TT Payment to 123456***-AED 12 1,234.56+ Your available balance is AED 9,876.54."

    @Test fun layoutKeepsDigitLengthsAndNeverDigits() {
        val r = Masker.mask(body, "HSBC", loose = true, fuller = true, layout = true)
        assertTrue(r.safe, r.reason ?: r.shape)
        // 2.8 layout: a whole amount is ONE token with its digit-group lengths (spec section 5), never split into {NUM} pieces.
        assertTrue(r.shape.contains("{AMT:1,3.2}+"), r.shape)
        assertTrue(r.shape.contains("AED {AMT:2} "), r.shape)
        assertFalse(Regex("""\d""").containsMatchIn(r.shape.replace(Regex("""\{[A-Z]+(?::[^{}\s]+)?\}"""), "")), r.shape)
        assertFalse(r.shape.contains("{AMOUNT}"), r.shape)
    }

    @Test fun normalMaskingIsUnchanged() {
        val r = Masker.mask(body, "HSBC", loose = true, fuller = true)
        assertTrue(r.shape.contains("{AMOUNT}"), r.shape)
    }

    @Test fun layoutBanksComeFromTheRulesFile() {
        val json = """{"version": 99, "minEngine": 5, "sendMore": {"layoutBanks": ["HSBC"]}, "banks": [], "rules": [], "ignore": []}"""
        val out = RulesPack.parse(json, currentVersion = 4)
        assertTrue(out is RulesPack.Outcome.Ok, out.toString())
        assertEquals(setOf("hsbc"), out.pack.sendMore.layoutBanks)
        RulesPack.apply(out.pack)
        assertEquals(setOf("hsbc"), RulesPack.layoutBanks)
        RulesPack.apply(null)
        assertEquals(emptySet(), RulesPack.layoutBanks)
    }
}
