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

    /** 2.8 spec 13: merge and check columns, and the per-day transfers vs messages lines (invented rows). */
    @Test fun merge_and_check_columns_and_per_day_counts() {
        assertEquals("agree", MaskedExport.amountCheck(1_000L, 1_000L))
        assertEquals("disagree", MaskedExport.amountCheck(1_000L, 1_001L))
        assertEquals("n-a", MaskedExport.amountCheck(null, 1_000L))
        assertEquals("agree", MaskedExport.merchantCheck("Shop One LLC", "SHOP ONE, LLC"))
        assertEquals("disagree", MaskedExport.merchantCheck("Shop One", "Shop Two"))
        assertEquals("n-a", MaskedExport.merchantCheck("Shop One", " "))
        assertEquals("pair", MaskedExport.mergeKindOf("Same transfer as transaction #12 (merged)", needsTwinRule = false))
        assertEquals("twin", MaskedExport.mergeKindOf("Same transfer as transaction #12 (merged)", needsTwinRule = true))
        assertEquals("twin", MaskedExport.mergeKindOf("Same transaction as #12 (merged)", needsTwinRule = false))
        assertEquals("none", MaskedExport.mergeKindOf("Read by rule", needsTwinRule = false))

        val debit = Row("Transfer of {CUR} {AMOUNT} to {TEXT}", "Bank A", "TRANSFER_OUT", "out", "ACCOUNT", "rule",
            amountCheck = "agree", merchantCheck = "n-a", day = "2026-11-20", transferTxnId = 7L)
        val notice = debit.copy(shape = "Your transfer of {CUR} {AMOUNT} is on its way", kind = "MERGED", direction = "none",
            mergedInto = 7L, mergeKind = "pair", amountCheck = "agree")
        val other = debit.copy(day = "2026-11-21", transferTxnId = 9L)
        val text = MaskedExport.render(listOf(debit, notice, other), skippedUnsafe = 0, skippedOtp = 0)
        val lines = text.lines().filter { !it.startsWith("#") && it.isNotBlank() }
        // Same shape on two days is still one line with a count; the merged notice names its transaction and the merge kind.
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.startsWith("2\tBank A\tTRANSFER_OUT") && it.endsWith("\t\tnone\tagree\tn-a") })
        assertTrue(lines.any { it.contains("\tMERGED\t") && it.endsWith("\t7\tpair\tagree\tn-a") })
        assertTrue(text.contains("# day\t2026-11-20\tmessages=2\ttransfers=1\tmerged=1"))
        assertTrue(text.contains("# day\t2026-11-21\tmessages=1\ttransfers=1\tmerged=0"))
    }
}
