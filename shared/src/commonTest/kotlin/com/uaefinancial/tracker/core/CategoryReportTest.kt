package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.CategoryRules
import com.uaefinancial.tracker.parser.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryReportTest {
    @Test fun builtInChangeIsReportedWithoutAnyMerchant() {
        val f = CategoryReport.fields(TxnType.PURCHASE, CategoryRules.OTHER, CategoryRules.GROCERIES, true)
        assertEquals("from=Other | to=Groceries | type=PURCHASE | all=yes", f)
        assertFalse(f!!.contains("merchant"))
    }

    @Test fun noChangeOrAPersonalCategoryIsNotReported() {
        assertNull(CategoryReport.fields(TxnType.PURCHASE, CategoryRules.DINING, CategoryRules.DINING, false))
        assertNull(CategoryReport.fields(TxnType.PURCHASE, CategoryRules.DINING, 57L, false)) // a category you typed yourself
    }

    @Test fun anUncategorisedStartIsNamedNone() {
        assertTrue(CategoryReport.fields(TxnType.PURCHASE, null, CategoryRules.TRAVEL, false)!!.startsWith("from=none | to=Travel"))
    }
}
