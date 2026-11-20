package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.core.ReadingGuard.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingGuardTest {
    private val debit = Line("TXN", "PURCHASE", 12_550, "AED", 1_700_000_000_000, "FAB", "FAB ·1234", "1234", "Carrefour")

    private fun material(after: Line) = ReadingGuard.isMaterial(listOf(debit), listOf(after))

    @Test fun sameReadingIsNotMaterial() = assertFalse(ReadingGuard.isMaterial(listOf(debit), listOf(debit)))
    @Test fun merchantCaseAndSpacesAreNotMaterial() = assertFalse(material(debit.copy(merchant = "  CARREFOUR ")))
    @Test fun nothingBeforeIsNotMaterial() = assertFalse(ReadingGuard.isMaterial(emptyList(), listOf(debit)))
    @Test fun readingLostIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), emptyList()))
    @Test fun amountChangeIsMaterial() = assertTrue(material(debit.copy(amountMinor = 100)))
    @Test fun typeChangeIsMaterial() = assertTrue(material(debit.copy(type = "REFUND")))
    @Test fun currencyChangeIsMaterial() = assertTrue(material(debit.copy(currency = "USD")))
    @Test fun dateChangeIsMaterial() = assertTrue(material(debit.copy(timestamp = debit.timestamp + 60_000)))
    @Test fun bankChangeIsMaterial() = assertTrue(material(debit.copy(bank = "ADCB")))
    @Test fun cardChangeIsMaterial() = assertTrue(material(debit.copy(cardKey = "FAB ·9999")))
    @Test fun last4ChangeIsMaterial() = assertTrue(material(debit.copy(last4 = "9999")))
    @Test fun merchantChangeIsMaterial() = assertTrue(material(debit.copy(merchant = "Lulu")))
    @Test fun extraReadingIsMaterial() = assertTrue(ReadingGuard.isMaterial(listOf(debit), listOf(debit, debit.copy(amountMinor = 5))))
    @Test fun orderDoesNotMatter() {
        val b = debit.copy(amountMinor = 5)
        assertFalse(ReadingGuard.isMaterial(listOf(debit, b), listOf(b, debit)))
    }
    @Test fun directionFlipIsReportedAsDirectionAndType() {
        val f = ReadingGuard.changedFields(listOf(debit), listOf(debit.copy(type = "REFUND")))
        assertTrue("direction" in f && "type" in f)
    }
    @Test fun purchaseToTransferOutKeepsDirection() {
        val f = ReadingGuard.changedFields(listOf(debit), listOf(debit.copy(type = "TRANSFER_OUT")))
        assertEquals(listOf("type"), f)
    }
    @Test fun changedFieldsNamesEachChange() {
        val f = ReadingGuard.changedFields(listOf(debit), listOf(debit.copy(amountMinor = 1, merchant = "X", last4 = "1")))
        assertEquals(listOf("amount", "card digits", "merchant"), f)
    }
    @Test fun describeReads() {
        assertEquals("purchase AED 125.50 · Carrefour · FAB 1234", ReadingGuard.describe(listOf(debit)))
        assertEquals("no transaction", ReadingGuard.describe(emptyList()))
    }
}
