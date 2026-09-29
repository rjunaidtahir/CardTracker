package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Currencies, symbols and amount formats from around the world, and amounts in your home currency. */
class GlobalCurrencyTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 7, 0).toEpochMillis(zone)

    @AfterTest fun reset() {
        SmsParser.setHomeCurrency("AED")
        SmsParser.setCustomSenders(emptyMap())
    }

    private fun same(expected: String, actual: Decimal, msg: String = "") = assertEquals(0, Decimal(expected).compareTo(actual), "$msg: $actual")

    // ------------------------------------------------------------------ amount formats

    @Test fun amounts_in_every_common_format() {
        val cases = listOf(
            Triple("1,234.56", null, "1234.56"), Triple("90.90", null, "90.90"), Triple("4300", null, "4300"),
            Triple(".07", null, "0.07"), Triple("1,23,456.78", "INR", "123456.78"), Triple("12,34,567", "INR", "1234567"),
            Triple("1.234,56", "EUR", "1234.56"), Triple("45,00", "EUR", "45"), Triple("1'234.50", "CHF", "1234.50"),
            Triple("1 234,56", "PLN", "1234.56"), Triple("1 234,56", "EUR", "1234.56"),
            Triple("12.500", "KWD", "12.5"), Triple("1.250", "BHD", "1.25"), Triple("0.500", "OMR", "0.5"),
            Triple("1,250.000", "KWD", "1250"), Triple("1,250", "KWD", "1250"),
            Triple("1.500.000", "IDR", "1500000"), Triple("50.000", "IDR", "50000"), Triple("1,200", "JPY", "1200"),
            Triple("1,500", "SAR", "1500"), Triple("0.500", "AED", "0.5"),
        )
        for ((raw, cur, want) in cases) same(want, SmsParser.parseAmount(raw, cur), "$raw $cur")
    }

    // ------------------------------------------------------------------ symbols and codes

    @Test fun symbols_and_abbreviations_map_to_codes() {
        val fixed = mapOf(
            "€" to "EUR", "£" to "GBP", "₹" to "INR", "US$" to "USD", "R$" to "BRL", "zł" to "PLN", "SR" to "SAR", "QR" to "QAR",
            "KD" to "KWD", "BD" to "BHD", "RO" to "OMR", "RM" to "MYR", "Rp" to "IDR", "Dhs" to "AED", "د.إ" to "AED", "ر.س" to "SAR",
        )
        for ((raw, code) in fixed) assertEquals(code, SmsParser.normalizeCurrency(raw), raw)
    }

    @Test fun shared_symbols_follow_your_home_currency() {
        assertEquals("USD", SmsParser.normalizeCurrency("$"))
        assertEquals("INR", SmsParser.normalizeCurrency("Rs."))
        SmsParser.setHomeCurrency("CAD")
        assertEquals("CAD", SmsParser.normalizeCurrency("$"))
        SmsParser.setHomeCurrency("PKR")
        assertEquals("PKR", SmsParser.normalizeCurrency("Rs"))
        assertEquals("PKR", SmsParser.normalizeCurrency("RS."))
        SmsParser.setHomeCurrency("NOK")
        assertEquals("NOK", SmsParser.normalizeCurrency("kr"))
    }

    @Test fun unknown_home_currency_falls_back_to_aed() {
        SmsParser.setHomeCurrency("XYZ")
        assertEquals("AED", SmsParser.homeCurrency)
        SmsParser.setHomeCurrency("sar")
        assertEquals("SAR", SmsParser.homeCurrency)
    }

    @Test fun words_that_are_currency_codes_are_left_out() {
        for (w in listOf("ALL", "TOP", "CUP", "SOS", "PEN", "BOB")) assertTrue(!Currencies.isCode(w), w)
        assertTrue(Currencies.rateToAed.size > 130)
        same("3.6725", Currencies.rateToAed.getValue("USD"))
    }

    // ------------------------------------------------------------------ home currency

    @Test fun amounts_convert_to_your_home_currency() {
        // Home AED (default): exact, as before.
        assertEquals(7345L to true, Money.toAedMinor(Decimal("20.00"), "USD"))
        assertEquals(500L to false, Money.toAedMinor(Decimal("5.00"), "AED"))
        // Home SAR: SAR is exact, AED and USD are estimates through the rate table.
        SmsParser.setHomeCurrency("SAR")
        assertEquals(1250L to false, Money.toAedMinor(Decimal("12.50"), "SAR"))
        val usd = Money.toAedMinor(Decimal("100.00"), "USD")!!
        assertTrue(usd.second)
        assertTrue(usd.first in 37400L..37600L, "100 USD ≈ 375 SAR, got ${usd.first}") // pegged 3.75
        val aed = Money.toAedMinor(Decimal("100.00"), "AED")!!
        assertTrue(aed.first in 10150L..10250L, "100 AED ≈ 102 SAR, got ${aed.first}")
        assertNull(Money.toAedMinor(Decimal("1"), "XYZ"))
    }

    @Test fun typed_entries_use_your_home_currency() {
        SmsParser.setHomeCurrency("INR")
        assertEquals("INR", ManualEntryParser.parse("lunch 450")?.currency)
        assertEquals("USD", ManualEntryParser.parse("usd 20 netflix")?.currency)
        assertEquals("EUR", ManualEntryParser.parse("€9 coffee")?.currency)
        // Ordinary words that happen to be currency codes stay words.
        val e = ManualEntryParser.parse("try pizza 45")!!
        assertEquals("INR", e.currency)
        assertEquals("try pizza", e.description)
    }

    // ------------------------------------------------------------------ reading messages

    private fun txn(body: String): ParsedTransaction {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        val r = SmsParser.parse("MYBANK", body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        return r.txn
    }

    @Test fun reads_amounts_written_the_local_way() {
        SmsParser.setHomeCurrency("INR")
        txn("Rs.1,23,456.78 debited from your A/c XX1234 on 28-09-26 at AMAZON").let {
            assertEquals("INR", it.currency); same("123456.78", it.amount); assertEquals("1234", it.cardLast4)
        }
        SmsParser.setHomeCurrency("USD")
        txn("Your card ending 4411 was charged $45.00 at WALMART").let { assertEquals("USD", it.currency); same("45", it.amount) }
        SmsParser.setHomeCurrency("KWD")
        txn("KWD 12.500 spent on your credit card ending 2211 at SULTAN CENTER").let { assertEquals("KWD", it.currency); same("12.5", it.amount) }
        SmsParser.setHomeCurrency("GBP")
        txn("£12.30 spent at TESCO on your debit card ending 7788").let { assertEquals("GBP", it.currency); same("12.30", it.amount) }
        SmsParser.setHomeCurrency("EUR")
        txn("Purchase of EUR 1.234,56 at MEDIAMARKT with card ending 5566").let { assertEquals("EUR", it.currency); same("1234.56", it.amount) }
    }
}
