package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Section 5 and 16.3 of the 2.8 spec: layout mode keeps how amounts and dates are written, never their digits. Invented wordings. */
class MaskerLayoutTest {
    private fun layout(body: String, bank: String) = Masker.mask(body, bank, loose = true, fuller = true, layout = true)

    /** No real digit run survives outside the layout tokens (lengths only inside them). */
    private fun noRealDigits(shape: String) =
        assertFalse(Regex("""\d""").containsMatchIn(shape.replace(Regex("""\{[A-Z]+(?::[^{}\s]+)?\}"""), "")), shape)

    @Test fun hsbcStyleShowsWhichFigureIsTheMoney() {
        val r = layout("Your account 123456***-AED 100.00 2,500.00- TT Payment from 654321. Your available balance is AED 2,400.00", "HSBC")
        assertTrue(r.safe, r.toString())
        assertTrue(r.shape.contains("AED {AMT:3.2}"), r.shape)
        assertTrue(r.shape.contains("{AMT:1,3.2}-"), r.shape)
        assertTrue(r.shape.contains("AED {AMT:1,3.2}"), r.shape)
        assertFalse(r.shape.contains("{NUM:1},{NUM:3}"), "a number is never split: ${r.shape}")
        noRealDigits(r.shape)
    }

    @Test fun dibAndSabStyle() {
        val dib = layout("AED 1,250.00 has been drawn from A/C No. 123-456789XXXX-01 as OWN ACCOUNT TRANSFER. Available balance is AED 10,500.75", "Dubai Islamic Bank")
        assertTrue(dib.safe, dib.toString()); noRealDigits(dib.shape)
        assertTrue(dib.shape.contains("{AMT:1,3.2}"), dib.shape)
        val sab = layout("SAR 45.50 debited from account ****3001 on 05-DEC-26. Balance SAR 12,000.00+", "SABB")
        assertTrue(sab.safe, sab.toString()); noRealDigits(sab.shape)
        assertTrue(sab.shape.contains("{DATE:dd-MON-yy}"), sab.shape)
        assertTrue(sab.shape.contains("{AMT:2,3.2}+"), sab.shape)
    }

    @Test fun dateLayouts() {
        assertEquals("dd-MON-yy", Masker.dateLayout("05-DEC-26"))
        assertEquals("d_MON_yyyy", Masker.dateLayout("5 Dec 2026"))
        assertEquals("MON_dd,_yyyy", Masker.dateLayout("Dec 05, 2026"))
        assertEquals("dd/mm/yy", Masker.dateLayout("05/12/26"))
        assertEquals("yyyy-mm-dd", Masker.dateLayout("2026-12-05"))
    }

    @Test fun ordinaryMaskingIsUnchanged() {
        val r = Masker.mask("Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00")
        assertEquals("Your Visa card ending {CARD:4} was used for {CUR} {AMOUNT} at NOON on {DATE}. Avl bal {CUR} {AMOUNT}", r.shape)
    }
}
