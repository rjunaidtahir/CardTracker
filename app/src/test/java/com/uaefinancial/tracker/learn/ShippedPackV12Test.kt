package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** v12: asks phones to resend still-unread messages of named banks with fuller wording (kind unread-more). No rules or ignores change. */
class ShippedPackV12Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun sendMoreAsksForNamedBanksAndNothingElseChanges() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 12)
        assertTrue("mashreq" in out.pack.sendMore.banks)
        assertTrue("emirates nbd" in out.pack.sendMore.banks)
        assertTrue(out.pack.sendMore.banks.size in 1..50)
        assertEquals(28, out.pack.ignore.size)
        assertEquals(22, out.pack.rules.size)
    }
}
