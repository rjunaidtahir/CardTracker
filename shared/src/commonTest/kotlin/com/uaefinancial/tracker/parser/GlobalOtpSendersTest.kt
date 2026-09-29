package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-time codes in other languages, Arabic-Indic digits, Indian DLT sender IDs and short-code senders. */
class GlobalOtpSendersTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 7, 0).toEpochMillis(zone)

    @AfterTest fun reset() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setHomeCurrency("AED")
    }

    // ------------------------------------------------------------------ one-time codes

    @Test fun codes_in_other_languages_are_otps_and_never_stored() {
        val otps = listOf(
            "Tu código de verificación es 482913. No lo compartas.",
            "Usa el código 482913 para confirmar tu compra de 45,00 EUR",
            "482913 es tu código de Banco X",
            "Seu código de verificação é 482913",
            "Use o código 482913 para concluir a compra",
            "482913 é o seu código",
            "Votre code de vérification est 482913",
            "Saisissez le code 482913 pour valider votre paiement",
            "Ihr Bestätigungscode lautet 482913",
            "Ihre mTAN lautet 482913",
            "TAN: 482913 für Überweisung",
            "Il tuo codice di verifica è 482913",
            "Je verificatiecode is 482913",
            "Gebruik code 482913 om te betalen",
            "Twój kod weryfikacyjny: 482913",
            "Doğrulama kodunuz: 482913",
            "Şifreniz 482913",
            "Kode verifikasi Anda adalah 482913",
            "رمز التحقق الخاص بك هو 482913",
            "كلمة المرور لمرة واحدة: ٤٨٢٩١٣",
            "آپ کا او ٹی پی 482913 ہے",
            "आपका ओटीपी 482913 है",
        )
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        for (b in otps) {
            assertTrue(SmsParser.isOtp(b), "OTP: $b")
            val r = SmsParser.parse("MYBANK", b, received, zone)
            assertIs<ParseResult.Ignored>(r, b)
            assertFalse(r.store, "OTP must not be stored: $b")
            assertFalse(SmartParser.looksLikeBankAlert(b), "an OTP is not a bank alert: $b")
        }
    }

    @Test fun purchase_alerts_with_references_are_not_otps() {
        val real = listOf(
            "Compra de 45,00 EUR en MERCADONA con tu tarjeta ...1234. Código de referencia 123456.",
            "Kartenzahlung 45,00 EUR bei REWE. Referenz 12345678",
            "Paiement de 45,00 EUR chez CARREFOUR avec votre carte 1234",
            "Votre code client 12345678 : paiement de 45,00 EUR",
            "Pagamento di 45,00 EUR presso ESSELUNGA con carta ***1234, codice autorizzazione 123456",
            "Betaling van 45,00 EUR bij ALBERT HEIJN met pas 1234",
            "Compra aprovada de R$ 45,00 no cartão final 1234 em PADARIA",
            "تمت عملية شراء بمبلغ 45.00 درهم لدى كارفور باستخدام بطاقتك المنتهية بـ 1234. رقم المرجع 998877",
            "Kartınızla 45,00 TL harcama yapıldı. Ref 12345678",
            "Płatność kartą 1234 na kwotę 45,00 zł w BIEDRONKA",
            "Your tan leather order 12345 shipped",
            "Purchase of AED 250.00 at LE CODE 12345 BOUTIQUE",
            "Use 2 of your 12000 points at checkout",
            "Your Credit Card ending 1234 was used for AED 45.00 at CAREEM. Never share your security code with anyone. Call 8002222",
            "Merchant code 5411: AED 80.00 spent at CARREFOUR with card 2093",
        )
        for (b in real) assertFalse(SmsParser.isOtp(b), "Not an OTP: $b")
    }

    // ------------------------------------------------------------------ digits

    @Test fun arabic_indic_digits_are_read() {
        assertEquals("AED 123.45 card 1234", SmsParser.westernDigits("AED ١٢٣٫٤٥ card ١٢٣٤"))
        assertEquals("1,250", SmsParser.westernDigits("۱٬۲۵۰"))
        assertEquals("plain text", SmsParser.westernDigits("plain text"))
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        val r = SmsParser.parse("MYBANK", "AED ١٢٣٫٤٥ spent on your credit card ending ١٢٣٤ at CARREFOUR", received, zone)
        assertIs<ParseResult.Transaction>(r)
        assertEquals(0, Decimal("123.45").compareTo(r.txn.amount))
        assertEquals("1234", r.txn.cardLast4)
    }

    @Test fun turkish_lira_abbreviation() {
        assertEquals("TRY", SmsParser.normalizeCurrency("TL"))
    }

    // ------------------------------------------------------------------ senders

    @Test fun indian_dlt_sender_ids_are_one_sender() {
        assertEquals("HDFCBK", SmsParser.senderKey("AX-HDFCBK-S"))
        assertEquals("HDFCBK", SmsParser.senderKey("VM-HDFCBK"))
        assertEquals("HDFCBK", SmsParser.senderKey("jd-hdfcbk-t"))
        // Not the DLT shape: just normalized.
        assertEquals("ADFAB", SmsParser.senderKey("AD-FAB"))
        assertEquals("ADADCBALERT", SmsParser.senderKey("AD-ADCBAlert"))

        SmsParser.setCustomSenders(mapOf("AX-HDFCBK-S" to "HDFC Bank"))
        for (s in listOf("AX-HDFCBK-S", "VM-HDFCBK", "JD-HDFCBK-T", "HDFCBK")) assertEquals("HDFC Bank", SmsParser.bankFor(s)?.name, s)
        assertNull(SmsParser.bankFor("AX-ICICIB-S"))
    }

    @Test fun built_in_senders_still_match() {
        assertEquals("Wio", SmsParser.bankFor("WioPersonal")?.name)
        assertTrue(SmsParser.senderMatches("AD-ADCBAlert", "ADCBAlert"))
        assertFalse(SmsParser.senderMatches("XFAB", "FAB"))
    }

    @Test fun short_codes() {
        assertTrue(SmsParser.isShortCode("24273"))
        assertTrue(SmsParser.isShortCode("692484"))
        assertFalse(SmsParser.isShortCode("+971501234567"))
        assertFalse(SmsParser.isShortCode("0501234567"))
        assertFalse(SmsParser.isShortCode("ADCB"))
        SmsParser.setCustomSenders(mapOf("24273" to "Chase"))
        assertEquals("Chase", SmsParser.bankFor("24273")?.name)
    }
}
