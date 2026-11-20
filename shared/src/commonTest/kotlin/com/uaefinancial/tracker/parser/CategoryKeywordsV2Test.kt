package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals

/** Merchants seen as "Other" on a real phone (names are cut short by the bank SMS). */
class CategoryKeywordsV2Test {
    private fun cat(m: String) = CategoryRules.guess(m, TxnType.PURCHASE)

    @Test fun cutOffAndNewNamesAreFiled() {
        assertEquals(CategoryRules.GROCERIES, cat("CIRCLE K MINI STORE BR"))
        assertEquals(CategoryRules.GROCERIES, cat("NAHAR W LAIL MINI MAR"))
        assertEquals(CategoryRules.DINING, cat("THE WOODEN SPOON RES"))
        assertEquals(CategoryRules.GOVERNMENT, cat("Digital Dubai"))
    }

    @Test fun existingFilingIsUnchanged() {
        assertEquals(CategoryRules.DINING, cat("GAZANIA RESTAURANT"))
        assertEquals(CategoryRules.DINING, cat("ANGAARA CAFE"))
        assertEquals(CategoryRules.TRANSPORT, cat("EPPCO-SITE NO -91"))
        assertEquals(CategoryRules.SHOPPING, cat("AMAZONUFR DI"))
        assertEquals(CategoryRules.GROCERIES, cat("ALL DAY MARKET SUPERMA"))
        assertEquals(CategoryRules.GROCERIES, cat("SAFA AL MADINA HYPERMA"))
    }

    @Test fun unrelatedNamesStayOther() {
        assertEquals(CategoryRules.OTHER, cat("RESORT SPA"))      // "RES" only counts as a whole word at the end
        assertEquals(CategoryRules.OTHER, cat("PRESS CORNER LLC"))
    }
}
