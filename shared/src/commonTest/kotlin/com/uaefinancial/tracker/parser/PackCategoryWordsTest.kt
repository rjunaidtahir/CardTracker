package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackCategoryWordsTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun pack(cats: String) = """{"version":9,"minEngine":5,"banks":[],"rules":[],"ignore":[],"categories":$cats}"""

    @Test fun wordsFileNamesTheAppCouldNotFile() {
        val out = RulesPack.parse(pack("""[{"category":"Groceries","words":["ZAHRAT AL FAJR","BIN DAWOOD"]},{"category":"Health","words":["MEDICLINIC"]}]"""))
        assertIs<RulesPack.Outcome.Ok>(out)
        assertEquals(CategoryRules.OTHER, CategoryRules.guess("ZAHRAT AL FAJR FOODSTUFF", TxnType.PURCHASE))
        RulesPack.apply(out.pack)
        assertEquals(CategoryRules.GROCERIES, CategoryRules.guess("ZAHRAT AL FAJR FOODSTUFF", TxnType.PURCHASE))
        assertEquals(CategoryRules.HEALTH, CategoryRules.guess("MEDICLINIC PARKVIEW", TxnType.PURCHASE))
        assertEquals(9, CategoryRules.packKeywordsVersion)
    }

    @Test fun aPackWordNeverMovesWhatTheAppAlreadyFiles() {
        val out = RulesPack.parse(pack("""[{"category":"Shopping","words":["CARREFOUR","EPPCO"]}]""")) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        assertEquals(CategoryRules.GROCERIES, CategoryRules.guess("CARREFOUR CITY", TxnType.PURCHASE))
        assertEquals(CategoryRules.TRANSPORT, CategoryRules.guess("EPPCO SITE 91", TxnType.PURCHASE))
    }

    @Test fun badWordsAndCategoriesAreRefused() {
        fun bad(cats: String) = assertIs<RulesPack.Outcome.Rejected>(RulesPack.parse(pack(cats)))
        bad("""[{"category":"Groceries","words":["STORE"]}]""")            // too generic
        bad("""[{"category":"Groceries","words":["AB"]}]""")               // too short
        bad("""[{"category":"Groceries","words":[".*MART.*"]}]""")          // a pattern, not a name
        bad("""[{"category":"Salary & income","words":["PAYROLL LTD"]}]""") // not a spending category
        bad("""[{"category":"Cash","words":["ATM POINT"]}]""")
        bad("""[{"category":"Nonsense","words":["SOME SHOP"]}]""")
        bad("""[{"category":"Groceries"}]""")
    }

    @Test fun withoutTheSectionNothingChanges() {
        val out = RulesPack.parse("""{"version":9,"minEngine":5,"banks":[],"rules":[],"ignore":[]}""")
        assertIs<RulesPack.Outcome.Ok>(out)
        assertTrue(out.pack.categories.isEmpty())
        RulesPack.apply(out.pack)
        assertEquals(0, CategoryRules.packKeywordsVersion)
    }
}
