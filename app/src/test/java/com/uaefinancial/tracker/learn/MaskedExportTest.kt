package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.learn.MaskedExport.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MaskedExportTest {
    @Test fun directions_and_sources() {
        assertEquals("out", MaskedExport.directionOf("PURCHASE"))
        assertEquals("in", MaskedExport.directionOf("REFUND"))
        assertEquals("none", MaskedExport.directionOf("STATEMENT"))
        assertEquals("user-fix", MaskedExport.sourceOf("anything", true))
        assertEquals("pack", MaskedExport.sourceOf("pack:tabby", false))
        assertEquals("learned", MaskedExport.sourceOf("learned-from-you", false))
        assertEquals("rule", MaskedExport.sourceOf("fab-purchase", false))
        assertEquals("none", MaskedExport.sourceOf(null, false))
    }

    @Test fun identical_rows_are_merged_with_a_count_and_nothing_personal_is_added() {
        val r = Row("You spent {CUR} {AMOUNT} at {TEXT}", "Tabby", "PURCHASE", "out", "CREDIT", "pack")
        val text = MaskedExport.render(listOf(r, r, r.copy(kind = "REFUND", direction = "in")), skippedUnsafe = 2, skippedOtp = 1)
        val lines = text.lines().filter { !it.startsWith("#") && it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.startsWith("2\tTabby\tPURCHASE\tout\tCREDIT\tpack\t") })
        assertTrue(text.contains("2 messages whose shape was not safe"))
        assertFalse(text.contains("AED"))
    }
}
