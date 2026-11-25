package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 22 Nov 2026: the global "Card setting / service notice" ignore had a bare "lounge" word, which hid real card
 * purchases at merchants with LOUNGE in the name (3 ADCB reports). A message that also shows a balance after the word
 * ("Avl.Bal ...") is a purchase alert and is not hidden by it; lounge-access notices without a balance stay hidden.
 */
class LoungePurchaseNotHiddenTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @Test fun purchaseAtALoungeIsNotHiddenByTheServiceNoticeIgnore() {
        val body = "Your debit card 1234 linked to acc. 123456789012 was used for AED 45.50 on 21/11/2026 20:15:33 at SHISHA LOUNGE C,AE. Avl.Bal AED 1,234.56."
        val r = SmsParser.parseAsBank("ADCB", body, received, zone)
        if (r is ParseResult.Ignored) assertTrue(r.reason != "Card setting / service notice", "still hidden by lounge ignore")
    }

    @Test fun loungeAccessNoticeStaysHidden() {
        val body = "Enjoy complimentary airport lounge access with your ADCB card. T&C apply."
        val r = SmsParser.parseAsBank("ADCB", body, received, zone)
        assertIs<ParseResult.Ignored>(r, "got $r")
    }

    /** 25 Nov 2026: SAB (Saudi Awwal Bank) Arabic POS purchase at "Awwal Lounge" was hidden; the Arabic word for balance now counts like "Avl". */
    @Test fun arabicPosPurchaseAtALoungeIsNotHiddenByTheServiceNoticeIgnore() {
        val body = "شراء عبر نقاط البيع بطاقة: 1234; VISA مبلغ: SAR 45.50 لدى: Awwal Lounge 1 رصيد: SAR 900.00 في: 24/11/2026 14:30"
        val r = SmsParser.parseAsBank("SAB", body, received, zone)
        if (r is ParseResult.Ignored) assertTrue(r.reason != "Card setting / service notice", "still hidden by lounge ignore")
    }

    @Test fun arabicLoungeAccessNoticeWithoutBalanceStaysHidden() {
        val body = "استمتع بدخول صالة المطار lounge مجانا مع بطاقتك. T&C apply."
        val r = SmsParser.parseAsBank("SAB", body, received, zone)
        assertIs<ParseResult.Ignored>(r, "got $r")
    }
}
