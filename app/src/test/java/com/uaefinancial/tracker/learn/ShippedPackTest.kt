package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The exact rules/pack.json that is about to be signed and published must pass the app's own checks. */
class ShippedPackTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun shippedPackIsAcceptedByTheAppAndTracksTheNewSenders() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 5)
        assertEquals(10, out.pack.rules.size)
        assertEquals(13, out.pack.ignore.size)
        val tracked = out.pack.banks.filter { it.track }.associate { it.name to it.senderIds }
        assertEquals(mapOf("Tabby" to listOf("Tabby"), "ADCB" to listOf("SIMPLYLIFE")), tracked)
        RulesPack.apply(out.pack)
        assertEquals("ADCB", SmsParser.bankFor("SIMPLYLIFE")?.name)
        assertEquals("Tabby", SmsParser.bankFor("AD-Tabby")?.name)
        val tamara = out.pack.banks.first { it.name == "Tamara" }
        assertNotNull(tamara)
        assertEquals(null, SmsParser.bankFor("Tamara")) // names only, no sender claimed without proof
    }

    @Test fun realMessagesFromTheTwoSendersAreRead() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        val simply = SmsParser.parse("SIMPLYLIFE", "Your Cr.Card XXX5886 was used for USD30.60 on 04/08/2023 11:04:30 at OW *passcert.com,London-GB. Avl. Cr.limit is AED2882.95", 0L)
        assertIs<ParseResult.Transaction>(simply)
        val tabby = SmsParser.parse("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.", 0L)
        assertIs<ParseResult.Transaction>(tabby)
        assertEquals("9", tabby.txn.amount.toString().removeSuffix(".00").removeSuffix(".0"))
    }
}
