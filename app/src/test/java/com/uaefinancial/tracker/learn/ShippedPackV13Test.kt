package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.RulesPack
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v13: more senders are named banks (so they can be asked for fuller wording); none of them is tracked. */
class ShippedPackV13Test {
    @AfterTest fun reset() { RulesPack.apply(null) }
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun newSendersResolveToTheirBanksAndRulesAreUnchanged() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 13)
        RulesPack.apply(out.pack)
        assertEquals("State Bank of India", Learning.publicBankName("VM-CBSSBI", null))
        assertEquals("State Bank of India", Learning.publicBankName("JM-CBSSBI", null))
        assertEquals("State Bank of India", Learning.publicBankName("TX-CBSSBI", null))
        assertEquals("State Bank of India", Learning.publicBankName("JD-SBIUPI", null))
        assertEquals("Bank of Baroda", Learning.publicBankName("JD-BOBSMS-S", null))
        assertEquals("Dubai Islamic Bank", Learning.publicBankName("DIB-SMS", null))
        assertEquals("SAB", Learning.publicBankName("SAB", null))
        for (c in listOf("8810", "8812", "9225")) assertEquals("Pakistan bank 8810/8812/9225", Learning.publicBankName(c, null))
        // look-alike senders stay unknown
        assertNull(Learning.publicBankName("SABRE", null))
        assertNull(Learning.publicBankName("8811", null))
        assertNull(Learning.publicBankName("SBIFAKE", null))
        // nothing new is tracked, no rule or ignore changed
        assertEquals(setOf("Tabby", "ADCB"), out.pack.banks.filter { it.track }.map { it.name }.toSet())
        assertEquals(22, out.pack.rules.size)
        assertTrue(out.pack.ignore.size >= 28)
        assertTrue(out.pack.sendMore.banks.size in 1..50)
        assertTrue("state bank of india" in out.pack.sendMore.banks)
    }
}
