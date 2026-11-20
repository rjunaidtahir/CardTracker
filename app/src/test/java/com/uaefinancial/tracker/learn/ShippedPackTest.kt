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
        assertTrue(out.pack.rules.size >= 10) // grows with each published pack (v6: 18)
        assertTrue(out.pack.ignore.size >= 13) // v6: 18
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

    @Test fun v7IgnoresTheTabbyLimitNoticeAndTheZeroDueStatementOnly() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        assertTrue(out.pack.version >= 7)
        RulesPack.apply(out.pack)
        // the two notices are ignored
        val limit = SmsParser.parse("Tabby", "Your Tabby Card total limit is now increased to AED 3,000.00", 0L)
        assertIs<ParseResult.Ignored>(limit)
        val zero = out.pack.ignore.first { it.label.startsWith("Statement: nothing due") }
        val re = Regex(zero.pattern, RegexOption.IGNORE_CASE)
        assertTrue(re.containsMatchIn("Your CC Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 0 with Min Due AED 0.00")) // the real message
        assertTrue(re.containsMatchIn("CC Statement: your credit balance AED 0 with Min Due AED 0.00"))
        assertTrue(!re.containsMatchIn("Your CC Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 0 with Min Due AED 150.00"))
        assertTrue(re.containsMatchIn("credit balance AED 0.00 with Minimum Due AED 0.00"))
        // a statement with something due, and ordinary Tabby messages, are not ignored
        assertTrue(!re.containsMatchIn("CC Statement: your credit balance AED 0 with Min Due AED 150.00"))
        assertTrue(!re.containsMatchIn("CC Statement: your credit balance AED 0 with Min Due AED 0.50"))
        assertTrue(!re.containsMatchIn("CC Statement: your credit balance AED 2,500.00 with Min Due AED 0.00 ... AED 0"))
        val spent = SmsParser.parse("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.", 0L)
        assertIs<ParseResult.Transaction>(spent)
        val stmt = SmsParser.parse("Tabby", "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026.", 0L)
        assertTrue(stmt !is ParseResult.Ignored)
    }
}

/**
 * Checks the shop-name category words in the exact rules/pack.json about to be published. rules/merchants.json holds one
 * real example name per published word group (written by the daily merchant review); each must file under the category
 * the review chose once the shipped pack is applied, and no word may change what the built-in keywords already file.
 */
class ShippedCategoryWordsTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val packFile = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val examplesFile = listOf(File("../rules/merchants.json"), File("rules/merchants.json")).first { it.exists() }

    @Test fun everyExampleFilesWhereTheReviewChose() {
        val out = RulesPack.parse(packFile.readText(), currentVersion = 0)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        RulesPack.apply(out.pack)
        // rules/merchants.json: {"name": "...", "category": "..."} pairs (read with a regex: the JSON helper is internal to shared)
        val pair = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"category\"\\s*:\\s*\"([^\"]+)\"")
        val examples = pair.findAll(examplesFile.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        for ((name, category) in examples) {
            val want = com.uaefinancial.tracker.parser.CategoryRules.defaults.first { it.name.equals(category, true) }.id
            assertEquals(want, com.uaefinancial.tracker.parser.CategoryRules.guess(name, com.uaefinancial.tracker.parser.TxnType.PURCHASE), "example $name")
        }
        // every published word must file at least one example (no word goes out untested)
        for ((_, words) in out.pack.categories) for (w in words) {
            assertTrue(examples.any { it.first.uppercase().contains(w) }, "word $w has no example in rules/merchants.json")
        }
    }

    @Test fun builtInFilingIsNeverChangedByThePack() {
        val tp = com.uaefinancial.tracker.parser.TxnType.PURCHASE
        val names = listOf("CARREFOUR CITY", "EPPCO SITE 91", "GAZANIA RESTAURANT", "AMAZONUFR DI", "ADNOC 1234", "NOON FOOD", "DEWA", "SALIK", "TALABAT", "LULU HYPERMARKET")
        val before = names.map { com.uaefinancial.tracker.parser.CategoryRules.guess(it, tp) }
        val out = RulesPack.parse(packFile.readText(), currentVersion = 0) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        assertEquals(before, names.map { com.uaefinancial.tracker.parser.CategoryRules.guess(it, tp) })
    }
}
