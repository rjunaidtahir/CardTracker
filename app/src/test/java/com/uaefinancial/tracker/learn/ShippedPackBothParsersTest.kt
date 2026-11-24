package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.legacy.RulesPack27
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 2.8 spec 4.8 and 8.3: the shipped rules file must load on 2.6 / 2.7 phones (the frozen 2.7 reader, which ignores `ext`) AND on
 * 2.8, with the same legacy part, and stay inside the old phones' limits. Counts are minimums so a version bump never breaks it.
 */
class ShippedPackBothParsersTest {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    @AfterTest fun reset() { RulesPack.apply(null) }

    @Test fun theShippedFileLoadsUnderTheLegacyAndThe28Readers() {
        val text = file.readText()
        val old = RulesPack27.parse(text, currentVersion = 4)
        assertIs<RulesPack27.Outcome.Ok>(old, "a 2.7 phone would reject the file: $old")
        val new = RulesPack.parse(text, currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(new, "2.8 rejects the file: $new")
        assertNull(new.pack.extRejected, "the ext section was refused")
        assertTrue(new.pack.version >= 26)
        assertEquals(old.pack.version, new.pack.version)
        assertEquals(old.pack.rules.map { it.rule.id }, new.pack.rules.map { it.rule.id }, "both read the same legacy rules")
        assertEquals(old.pack.ignore.map { it.label }, new.pack.ignore.map { it.label }, "both read the same legacy ignores")
        assertEquals(old.pack.banks.map { it.name }, new.pack.banks.map { it.name })
        // The legacy-only path of the 2.8 reader agrees too.
        assertIs<RulesPack.Outcome.Ok>(RulesPack.parse(text, currentVersion = 4, legacyOnly = true))
    }

    @Test fun theShippedFileStaysInsideTheOldPhonesLimits() {
        val text = file.readText()
        assertTrue(text.toByteArray().size <= RulesPack27.MAX_BYTES, "over the 2.7 size limit")
        assertEquals(emptyList(), RulesPack.legacyLimitProblems(text))
    }
}
