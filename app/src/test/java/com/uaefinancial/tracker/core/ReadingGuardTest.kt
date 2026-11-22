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

    // --- 2.6.1: soft changes (same money, only date/time or merchant differ) are not put to the user ---
    @Test fun fabTransferPayeeLostIsSoftAndKeptSilently() {
        val old = Line("TXN", "TRANSFER_OUT", 578_600, "AED", 1_700_000_000_000, "FAB", "FAB 8001", "8001", "ENBD Junaid CC")
        val new = old.copy(merchant = "Outward remittance", timestamp = old.timestamp + 3_600_000)
        assertTrue(ReadingGuard.isMaterial(listOf(old), listOf(new)))
        assertTrue(ReadingGuard.isSoftOnly(listOf(old), listOf(new)))
        assertTrue(ReadingGuard.keepOldSilently(listOf(old), listOf(new)))
    }
    @Test fun amountChangeIsNotSoft() = assertFalse(ReadingGuard.isSoftOnly(listOf(debit), listOf(debit.copy(amountMinor = 100, merchant = "X"))))
    @Test fun directionOrTypeChangeIsNotSoft() = assertFalse(ReadingGuard.isSoftOnly(listOf(debit), listOf(debit.copy(type = "REFUND"))))
    @Test fun cardOrBankChangeIsNotSoft() {
        assertFalse(ReadingGuard.isSoftOnly(listOf(debit), listOf(debit.copy(cardKey = "FAB ·9999"))))
        assertFalse(ReadingGuard.isSoftOnly(listOf(debit), listOf(debit.copy(bank = "ADCB"))))
    }
    @Test fun lostOrExtraReadingIsNotSoft() {
        assertFalse(ReadingGuard.isSoftOnly(listOf(debit), emptyList()))
        assertFalse(ReadingGuard.isSoftOnly(listOf(debit), listOf(debit, debit.copy(amountMinor = 5))))
    }
    @Test fun blankOldMerchantTakesTheNewOne() {
        val old = debit.copy(merchant = "")
        val new = debit.copy(merchant = "Lulu")
        assertTrue(ReadingGuard.isSoftOnly(listOf(old), listOf(new)))
        assertFalse(ReadingGuard.keepOldSilently(listOf(old), listOf(new)))
    }
    @Test fun softOnlyIgnoresOrder() {
        val b = debit.copy(amountMinor = 5)
        assertTrue(ReadingGuard.isSoftOnly(listOf(debit, b), listOf(b.copy(merchant = "Z"), debit.copy(timestamp = 1))))
    }
    @Test fun heldCardsWithOnlyDateOrMerchantChangesAreSoft() {
        assertTrue(ReadingGuard.isSoftSummary("transfer out AED 1.00 (changes: date and time, merchant)"))
        assertTrue(ReadingGuard.isSoftSummary("x (changes: merchant)"))
        assertTrue(ReadingGuard.isSoftSummary("x (changes: date and time)"))
    }
    @Test fun heldCardsWithMoneyChangesAreNotSoft() {
        assertFalse(ReadingGuard.isSoftSummary("purchase PKR 290.00 (changes: amount)"))
        assertFalse(ReadingGuard.isSoftSummary("x (changes: date and time, amount)"))
        assertFalse(ReadingGuard.isSoftSummary("no transaction"))
        assertFalse(ReadingGuard.isSoftSummary("x (changes: reading removed)"))
        assertFalse(ReadingGuard.isSoftSummary("x (changes: number of readings)"))
    }
}
