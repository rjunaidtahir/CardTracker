package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.bridge.Bridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One bank = one name, however it's written (so one card never turns into two). */
class BankNamesTest {
    @Test fun spellings_of_a_bank_give_one_name() {
        for (s in listOf("RAKBANK", "RAK BANK", "rak bank", "RAK-BANK", "RAKBankUAE", "National Bank of Ras Al Khaimah", "RAKBANK >", "RAKBANK ›")) {
            assertEquals("RAKBANK", BankNames.canonical(s), s)
        }
        assertEquals("ADCB", BankNames.canonical("AD-ADCBAlert"))
        assertEquals("ADCB", BankNames.canonical("Abu Dhabi Commercial Bank"))
        assertEquals("Emirates NBD", BankNames.canonical("EmiratesNBD"))
        assertEquals("Emirates NBD", BankNames.canonical("Emirates NBD Bank"))
        assertEquals("FAB", BankNames.canonical("First Abu Dhabi Bank"))
        assertEquals("Dubai Islamic Bank", BankNames.canonical("Dubai Islamic Bank"))
        assertEquals("Emirates Islamic", BankNames.canonical("Emirates Islamic Bank"))
        assertEquals("Mashreq", BankNames.canonical("Mashreq Bank"))
        assertNull(BankNames.canonical("Carrefour"))
        assertNull(BankNames.canonical(""))
        assertNull(BankNames.canonical("Other bank"))
    }

    @Test fun bank_named_in_text() {
        assertEquals("RAKBANK", BankNames.namedIn("Thank you for banking with RAK BANK."))
        assertEquals("Emirates Islamic", BankNames.namedIn("Your Emirates Islamic card 1234 was used"))
        assertEquals("ADCB", BankNames.namedIn("Abu Dhabi Commercial Bank: your card was used"))
        // Place names and shops are not banks.
        assertNull(BankNames.namedIn("Purchase at CITI CENTRE DEIRA AED 10"))
        assertNull(BankNames.namedIn("AJMAN SWEETS AED 5.00"))
        assertNull(BankNames.namedIn("Your card ending 1234 was used for AED 10"))
    }

    @Test fun a_message_you_say_is_from_a_bank() {
        val t = 1_758_800_000_000L
        val body = "Your Credit Card ending 0552 was used for AED 22.75 at TALABAT on 21/09/2026 13:05. Available limit AED 4,384.47"
        assertEquals(Bridge.UNKNOWN_BANK, Bridge.readSmsAnySender(body, t, emptyMap()).bank)
        val r = Bridge.readSmsAsBank("RAK BANK", body, t, emptyMap())
        assertEquals("transaction", r.kind)
        assertEquals("RAKBANK", r.bank)
        assertEquals("0552", r.cardLast4)
        assertEquals(2275L, r.amountMinor)
    }
}
