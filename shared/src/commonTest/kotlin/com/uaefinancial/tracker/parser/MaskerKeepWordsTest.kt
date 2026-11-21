package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The local export keeps every ordinary word; only values are replaced. */
class MaskerKeepWordsTest {
    @Test fun ordinaryWordsStayValuesGo() {
        val body = "A Cr. transaction of AED 1,250.50 on your account number 1234567890 was successful. Available balance is AED 9,000.00 on 21/09/2026 12:30"
        val r = Masker.mask(body, "ADCB", keepWords = true)
        assertTrue(r.safe, r.reason ?: "")
        assertTrue(r.shape.contains("A Cr. transaction of AED {AMOUNT}"), r.shape)
        assertTrue(r.shape.contains("was successful"), r.shape)
        assertFalse(r.shape.contains("1,250"), r.shape)
        assertFalse(r.shape.contains("1234567890"), r.shape)
    }

    @Test fun merchantNameIsReplacedByItsMarker() {
        val r = Masker.mask("Credit Card Purchase Card No XXXXXXXX1234 AED 80.00 ZENITH BAKERY DUBAI 21/09/2026", "FAB", merchant = "ZENITH BAKERY", keepWords = true)
        assertTrue(r.safe, r.reason ?: "")
        assertTrue(r.shape.contains("{MERCHANT}"), r.shape)
        assertFalse(r.shape.contains("ZENITH"), r.shape)
    }

    @Test fun shortAndAmountlessMessagesAreKeptToo() {
        val r = Masker.mask("Your statement is ready", "Tabby", keepWords = true)
        assertTrue(r.safe)
        assertEquals("Your statement is ready", r.shape)
    }

    @Test fun normalMaskingIsUnchanged() {
        val r = Masker.mask("A Cr. transaction of AED 1,250.50 on your account number 1234567890 was successful. Thank you for banking with Zorbo Quibble", "ADCB")
        assertTrue(r.shape.contains("{TEXT}"), r.shape)
    }

    @Test fun decisiveAndAbbreviatedWordsStayInNormalMasking() {
        val r = Masker.mask("A Cr. transaction of AED 1,250.50 on your account number 1234567890 was successful. Avl Bal AED 9,000.00 Pur reversal instructions", "ADCB")
        assertTrue(r.safe, r.reason ?: r.shape)
        assertTrue(r.shape.contains("A Cr. transaction"), r.shape)
        assertTrue(r.shape.contains("Avl Bal"), r.shape)
        assertTrue(r.shape.contains("reversal instructions"), r.shape)
    }

    @Test fun aNameAfterDearOrToIsHiddenEvenIfItIsAnOrdinaryWord() {
        val r = Masker.mask("Dear Rose, AED 500.00 debited from your account ending 1234 and sent to Hope Smith on 21/09/2026", "ADCB")
        assertTrue(r.safe, r.reason ?: r.shape)
        assertFalse(r.shape.contains("Rose"), r.shape)
        assertFalse(r.shape.contains("Hope"), r.shape)
        assertFalse(r.shape.contains("Smith"), r.shape)
    }

    @Test fun aPrivateChatIsStillRefused() {
        assertFalse(Masker.mask("Hi mum, running late, see you at the mall at 7, I will bring AED 50 for the groceries and the pizza tonight", null).safe)
    }

    @Test fun wordsFromTheRealExportStayVisible() {
        val r = Masker.mask(
            "Dear Customer, you've been charged AED 150.00 per transaction on your Credit Card ending 1234. Your June statement is ready. If this wasn't you, please go to the app for details and review your transaction. Tap to approve, or call us to avoid charges.",
            "ADCB",
        )
        assertTrue(r.safe, r.reason ?: r.shape)
        for (w in listOf("you've", "per", "wasn't", "go", "details", "review", "approve", "avoid", "June", "us")) {
            assertTrue(r.shape.contains(w), "$w was hidden: ${r.shape}")
        }
    }

    @Test fun aCapitalisedNameAfterToOrFromIsStillHidden() {
        val r = Masker.mask("AED 500.00 sent to Rose Khan from Mary Jones on 21/09/2026, Credit Card ending 1234 was used, available balance AED 90.00", "ADCB")
        assertTrue(r.safe, r.reason ?: r.shape)
        for (n in listOf("Rose", "Khan", "Mary", "Jones")) assertFalse(r.shape.contains(n), "$n leaked: ${r.shape}")
    }

    @Test fun looseKeepsBrandsAndForeignWordsButHidesNames() {
        val body = "Dear Rose, AED 500.00 was debited from your Credit Card ending 1234 for Samsung Pay on 21/09/2026. Beneficiary request for PAPPU done. Regards, Rana Muhammad Junaid Tahir"
        val r = Masker.mask(body, "FAB", loose = true)
        assertTrue(r.safe, r.reason ?: r.shape)
        assertTrue(r.shape.contains("Samsung"), r.shape)
        for (n in listOf("Rose", "PAPPU", "Rana", "Muhammad", "Junaid", "Tahir")) assertFalse(r.shape.contains(n), "$n leaked: ${r.shape}")
    }

    @Test fun looseAcceptsAMessageInAnotherLanguage() {
        val body = "تم خصم مبلغ AED 100.00 من بطاقتك الائتمانية المنتهية بالرقم 1234 لدى مطعم الشاطئ في 21/09/2026"
        assertTrue(Masker.mask(body, "FAB", loose = true).safe)
        assertFalse(Masker.mask(body, "FAB", loose = false).safe)
    }

    @Test fun looseDoesNotMakeAPrivateChatAcceptable() {
        // Learning.queue passes loose only for a recognised bank; the strict path (no bank) still refuses it.
        assertFalse(Masker.mask("Hi mum, running late, see you at the mall at 7, I will bring AED 50 for the groceries and the pizza tonight", null).safe)
    }
}
