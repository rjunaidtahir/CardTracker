package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.core.ReadingGuard.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingGuardTest {
    private val debit = Line("TXN", "PURCHASE", 12_550, "AED")

    @Test fun sameReadingIsNotMaterial() = assertFalse(ReadingGuard.isMaterial(listOf(debit), listOf(debit)))
    @Test fun nothingBeforeIsNotMaterial() = assertFalse(ReadingGuard.isMaterial(emptyList(), listOf(debit)))
    @Test fun readingLostIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), emptyList()))
    @Test fun amountChangeIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), listOf(debit.copy(amountMinor = 100))))
    @Test fun typeChangeIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), listOf(debit.copy(type = "INCOME"))))
    @Test fun currencyChangeIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), listOf(debit.copy(currency = "USD"))))
    @Test fun orderDoesNotMatter() {
        val b = debit.copy(amountMinor = 5)
        assertFalse(ReadingGuard.isMaterial(listOf(debit, b), listOf(b, debit)))
    }
    @Test fun describeReads() {
        assertEquals("purchase AED 125.50", ReadingGuard.describe(listOf(debit)))
        assertEquals("no transaction", ReadingGuard.describe(emptyList()))
    }
}
