package com.junaid.cardtracker.parser

import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Samples transcribed from real SMS screenshots (Sept 2026). Add new ones here when a format changes. */
class ParserTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = LocalDateTime.of(2026, 9, 21, 22, 10).atZone(zone).toInstant().toEpochMilli()

    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun txn(sender: String, body: String, at: Long = received): ParsedTransaction {
        val r = SmsParser.parse(sender, body, at, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r")
        return r.txn
    }

    private fun stmt(sender: String, body: String): ParsedStatement {
        val r = SmsParser.parse(sender, body, received, zone)
        assertIs<ParseResult.Statement>(r, "Expected a statement but got $r")
        return r.statement
    }

    private fun bd(s: String) = BigDecimal(s)

    // ------------------------------------------------------------------ FAB

    @Test fun fab_purchase_multiline() {
        val t = txn(
            "FAB",
            "Credit Card Purchase\nCard No XXXX0831\nAED 5.00\nPICCADILLY WHIPPY CAFE DUBAI ARE\n19/09/26 17:48\n" +
                "Avl Bal AED 678.88\nSeptember statement due on\n26/09/2026\n" +
                "Pay school fees in 12 instalments at 0% interest with no fees. Conditions apply.",
        )
        assertEquals("FAB", t.bank)
        assertEquals("0831", t.cardLast4)
        assertEquals(CardType.CREDIT, t.cardType)
        assertEquals(bd("5.00"), t.amount)
        assertEquals("AED", t.currency)
        assertEquals("PICCADILLY WHIPPY CAFE DUBAI", t.merchant)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(ts(2026, 9, 19, 17, 48), t.timestamp)
        assertEquals(bd("678.88"), t.availableLimit)
    }

    @Test fun fab_purchase_single_line() {
        val t = txn(
            "AD-FAB",
            "Credit Card Purchase Card No XXXX0831 AED 15.30 ADNOC ROVE HOTEL 532 DUBAI ARE 19/09/26 19:42 " +
                "Avl Bal AED 663.58 September statement due on 26/09/2026 Pay school fees in 12 instalments at 0% interest with no fees. Conditions apply.",
        )
        assertEquals("0831", t.cardLast4)
        assertEquals(bd("15.30"), t.amount)
        assertEquals("ADNOC ROVE HOTEL 532 DUBAI", t.merchant)
        assertEquals(ts(2026, 9, 19, 19, 42), t.timestamp)
    }

    // --------------------------------------------------------- Emirates NBD

    @Test fun enbd_purchases() {
        val cases = listOf(
            Triple("Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12", "28.72", "MORE VALUE SUPERMARKE"),
            Triple("Purchase of AED 75.55 with Credit Card ending 3944 at W Z D WEST ZONE SUPERM, DUBAI. Avl Cr. Limit is AED 809.57", "75.55", "W Z D WEST ZONE SUPERM"),
            Triple("Purchase of AED 45.86 with Credit Card ending 3944 at AMAZONUFR DI, Dubai. Avl Cr. Limit is AED 763.71", "45.86", "AMAZONUFR DI"),
            Triple("Purchase of AED 142.00 with Credit Card ending 3944 at AL MANDOOS GAS CYLINDE, DUBAI. Avl Cr. Limit is AED 621.71", "142.00", "AL MANDOOS GAS CYLINDE"),
        )
        for ((body, amount, merchant) in cases) {
            val t = txn("EmiratesNBD", body)
            assertEquals("Emirates NBD", t.bank)
            assertEquals("3944", t.cardLast4)
            assertEquals(CardType.CREDIT, t.cardType)
            assertEquals(bd(amount), t.amount)
            assertEquals("AED", t.currency)
            assertEquals(merchant, t.merchant)
            assertEquals(received, t.timestamp, "ENBD SMS has no date, so the arrival time is used")
            assertEquals(false, t.dateFromSms)
        }
    }

    @Test fun enbd_foreign_currency() {
        val t = txn("EmiratesNBD", "Purchase of USD 20.00 with Credit Card ending 3944 at NETFLIX.COM, LOS GATOS. Avl Cr. Limit is AED 500.00")
        assertEquals("USD", t.currency)
        assertEquals("NETFLIX.COM", t.merchant)
        assertEquals(7345L to true, Money.toAedMinor(t.amount, t.currency))
    }

    // ----------------------------------------------------------------- ADCB

    @Test fun adcb_purchase() {
        val t = txn(
            "ADCBAlert",
            "Credit Card XX3538 was used for AED90.90 on 13/09/2026 11:58:47 at talabat.com, DUBAI-AE. Available limit AED4736.08",
        )
        assertEquals("ADCB", t.bank)
        assertEquals("3538", t.cardLast4)
        assertEquals(CardType.CREDIT, t.cardType)
        assertEquals(bd("90.90"), t.amount)
        assertEquals("talabat.com", t.merchant)
        assertEquals(ts(2026, 9, 13, 11, 58, 47), t.timestamp)
        assertEquals(bd("4736.08"), t.availableLimit)
    }

    @Test fun adcb_statement() {
        val s = stmt(
            "ADCBAlert",
            "Cr.Card XXX3538 Billing alert: Total due to avoid fin. charges: AED1263.92. Due date Oct 14 2026; " +
                "Pay min. AED100.00 by due date to avoid AED241.50 late fees.",
        )
        assertEquals("ADCB", s.bank)
        assertEquals("3538", s.cardLast4)
        assertEquals(bd("1263.92"), s.statementBalance)
        assertEquals(bd("100.00"), s.minimumDue)
        assertEquals(LocalDate.of(2026, 10, 14), s.dueDate)
        assertEquals("AED", s.currency)
    }

    // ------------------------------------------------------------- Al Hilal

    @Test fun alhilal_purchases() {
        val t1 = txn(
            "AlHilal",
            "Purchase of 3,589.95 AED at LIVA INS B S C CLOSED    ABU DHABI    AE on 14-09-2026,  10:20:46, card 3976. Limit: 338.04 AED.",
        )
        assertEquals("Al Hilal", t1.bank)
        assertEquals(CardType.CREDIT, t1.cardType, "Al Hilal SMS don't say credit/debit; rule default is CREDIT")
        assertEquals("3976", t1.cardLast4)
        assertEquals(bd("3589.95"), t1.amount)
        assertEquals("AED", t1.currency)
        assertEquals("LIVA INS B S C CLOSED", t1.merchant)
        assertEquals(ts(2026, 9, 14, 10, 20, 46), t1.timestamp)
        assertEquals(bd("338.04"), t1.availableLimit)

        val t2 = txn(
            "AlHilal",
            "Purchase of 1,000.00 AED at THE BIG TICKET LLC       Dubai        AE on 20-09-2026,  04:56:38, card 3976. Limit: 1,838.04 AED.",
        )
        assertEquals(bd("1000.00"), t2.amount)
        assertEquals("THE BIG TICKET LLC", t2.merchant)
        assertEquals(ts(2026, 9, 20, 4, 56, 38), t2.timestamp)
        assertEquals(bd("1838.04"), t2.availableLimit)
    }

    @Test fun alhilal_declined_is_ignored() {
        val r = SmsParser.parse(
            "AlHilal",
            "Dear Customer, A transaction of AED 141.88 on your card 529106******3976 at BRANDS VILLAGE OUTLET    DUBAI\nAE is declined. " +
                "Please contact 600522229 to activate your card",
            received,
        )
        assertEquals(ParseResult.Ignored("Al Hilal", "Declined transaction"), r)
    }

    @Test fun alhilal_limit_change_is_ignored() {
        val r = SmsParser.parse("AlHilal", "The daily purchase limit of your card ending with 3976 has been changed to AED 4300 daily", received)
        assertEquals(ParseResult.Ignored("Al Hilal", "Limit change"), r)
    }

    // ----------------------------------------------------------------- HSBC

    @Test fun hsbc_statement() {
        val s = stmt(
            "HSBC-UAE",
            "HSBC Credit Card ending *** 5258 Statement Date 11/08/2026. Total Amt Due AED 79.20, Due Date 05/09/2026. " +
                "Min. Amt Due AED 33.70. Please ignore if paid.",
        )
        assertEquals("HSBC", s.bank)
        assertEquals("5258", s.cardLast4)
        assertEquals(bd("79.20"), s.statementBalance)
        assertEquals(bd("33.70"), s.minimumDue)
        assertEquals(LocalDate.of(2026, 9, 5), s.dueDate)
        assertEquals(LocalDate.of(2026, 8, 11), s.statementDate)
    }

    @Test fun hsbc_payment() {
        val arrived = ts(2026, 9, 4, 11, 5)
        val t = txn(
            "HSBC-UAE",
            "Thank you for the payment of AED 900.00 on 03/09/2026 towards your Credit Card ending *** 5258. " +
                "Your credit card available balance is AED 7,102.54",
            arrived,
        )
        assertEquals(TxnType.PAYMENT, t.type)
        assertEquals("5258", t.cardLast4)
        assertEquals(bd("900.00"), t.amount)
        assertEquals("Card payment", t.merchant)
        assertEquals(ts(2026, 9, 3, 12, 0), t.timestamp, "date-only SMS on an earlier day -> noon that day")
        assertEquals(bd("7102.54"), t.availableLimit)
    }

    @Test fun hsbc_cashback_is_refund() {
        val t = txn(
            "HSBC-UAE",
            "You have earned cashback of AED 0.39 credited to your card ending *** 5258. Please check your statement or log on to personal internet banking for details.",
        )
        assertEquals(TxnType.REFUND, t.type)
        assertEquals(bd("0.39"), t.amount)
        assertEquals("Cashback", t.merchant)
    }

    // ---------------------------------------------------------- debit cards
    // Debit formats below are ASSUMED (same layout as the credit SMS). Replace with real samples when you have them.

    @Test fun debit_cards_detected() {
        val enbd = txn("EmiratesNBD", "Purchase of AED 12.00 with Debit Card ending 1111 at CARREFOUR, DUBAI. Avl Bal is AED 5,000.00")
        assertEquals(CardType.DEBIT, enbd.cardType)
        assertEquals("1111", enbd.cardLast4)
        assertEquals("CARREFOUR", enbd.merchant)
        assertEquals(bd("5000.00"), enbd.availableLimit)

        val adcb = txn("ADCBAlert", "Debit Card XX2222 was used for AED50.00 on 13/09/2026 10:00:00 at NOON, DUBAI-AE. Available balance AED1000.00")
        assertEquals(CardType.DEBIT, adcb.cardType)
        assertEquals("2222", adcb.cardLast4)

        val fab = txn("FAB", "Debit Card Purchase\nCard No XXXX3333\nAED 7.50\nCOFFEE PLANET DUBAI ARE\n19/09/26 09:15\nAvl Bal AED 900.00")
        assertEquals(CardType.DEBIT, fab.cardType)
        assertEquals("COFFEE PLANET DUBAI", fab.merchant)
    }

    @Test fun statements_are_credit_cards() {
        assertEquals(CardType.CREDIT, stmt("ADCBAlert", "Cr.Card XXX3538 Billing alert: Total due to avoid fin. charges: AED1263.92. Due date Oct 14 2026; Pay min. AED100.00 by due date to avoid AED241.50 late fees.").cardType)
    }

    // ------------------------------------------------------ Mashreq (generic)

    @Test fun mashreq_generic_rule() {
        val t = txn("Mashreq", "Purchase of AED 120.50 with your Credit Card ending 1234 at CARREFOUR MOE on 21/09/2026 20:15. Available limit AED 9,000.00")
        assertEquals("Mashreq", t.bank)
        assertEquals("1234", t.cardLast4)
        assertEquals(bd("120.50"), t.amount)
        assertEquals("CARREFOUR MOE", t.merchant)
        assertEquals(ts(2026, 9, 21, 20, 15), t.timestamp)
    }

    // --------------------------------------------------------- general rules

    @Test fun otp_messages_are_ignored() {
        val r1 = SmsParser.parse("EmiratesNBD", "123456 is your OTP for the transaction of AED 250.00 at AMAZON on card ending 3944. Do not share it.", received)
        assertEquals(ParseResult.Ignored("Emirates NBD", "OTP", store = false), r1)
        val r2 = SmsParser.parse("ADCBAlert", "Your one-time password for online purchase of AED 99.00 is 482913", received)
        assertIs<ParseResult.Ignored>(r2)
        assertEquals(false, r2.store, "OTP SMS must not be stored")
    }

    @Test fun otp_with_amount_is_skipped_even_if_a_rule_would_match() {
        // Would otherwise match Mashreq's generic purchase rule.
        val r = SmsParser.parse("Mashreq", "OTP for purchase of AED 120.00 with card ending 1234 at AMAZON is 123456", received)
        assertEquals(ParseResult.Ignored("Mashreq", "OTP", store = false), r)
    }

    @Test fun otp_warning_footer_does_not_hide_a_purchase() {
        val t = txn(
            "EmiratesNBD",
            "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12. Never share your OTP with anyone.",
        )
        assertEquals(bd("28.72"), t.amount)
    }

    @Test fun unknown_format_with_amount_is_flagged() {
        val r = SmsParser.parse("FAB", "Credit Card Refund Card No XXXX0831 AED 20.00 NOON.COM 22/09/26 10:00", received)
        assertIs<ParseResult.Failed>(r)
        assertEquals("FAB", r.bank)
    }

    @Test fun non_financial_bank_sms_is_ignored() {
        val r = SmsParser.parse("HSBC-UAE", "Your HSBC mobile banking app has been activated on a new device.", received)
        assertIs<ParseResult.Ignored>(r)
    }

    @Test fun other_senders_are_not_bank() {
        assertEquals(ParseResult.NotBank, SmsParser.parse("du", "Purchase of AED 10.00 with Credit Card ending 1111 at X, DUBAI. Avl Cr. Limit is AED 1", received))
        assertEquals(ParseResult.NotBank, SmsParser.parse("+971501234567", "hi", received))
        assertNull(SmsParser.bankFor("FABULOUS"))
    }

    @Test fun sender_matching() {
        assertEquals("FAB", SmsParser.bankFor("fab")?.name)
        assertEquals("ADCB", SmsParser.bankFor("ADCBAlert")?.name)
        assertEquals("HSBC", SmsParser.bankFor("HSBC-UAE")?.name)
        assertEquals("Al Hilal", SmsParser.bankFor("AlHilal")?.name)
        assertEquals("Emirates NBD", SmsParser.bankFor("EmiratesNBD")?.name)
        assertEquals("Mashreq", SmsParser.bankFor("Mashreq")?.name)
    }

    // ---------------------------------------------------------- manual entry

    @Test fun manual_entries() {
        assertEquals(ManualEntry("lunch", bd("45"), "AED", null, TxnType.PURCHASE), ManualEntryParser.parse("lunch 45 aed"))
        assertEquals(ManualEntry("coffee", bd("12.5"), "AED", null, TxnType.PURCHASE), ManualEntryParser.parse("coffee 12.5"))
        assertEquals(ManualEntry("netflix", bd("20"), "USD", "3944", TxnType.PURCHASE), ManualEntryParser.parse("usd 20 netflix #3944"))
        assertEquals(ManualEntry("taxi", bd("45"), "AED", null, TxnType.PURCHASE), ManualEntryParser.parse("45aed taxi"))
        assertEquals(ManualEntry("amazon", bd("50"), "AED", null, TxnType.REFUND), ManualEntryParser.parse("refund amazon 50"))
        assertEquals(ManualEntry("dinner at zuma", bd("1250.75"), "AED", "0831", TxnType.PURCHASE), ManualEntryParser.parse("dinner at zuma 1,250.75 card 0831"))
        assertNull(ManualEntryParser.parse("lunch"))
        assertNull(ManualEntryParser.parse(""))
    }

    @Test fun money_conversion() {
        assertEquals(500L to false, Money.toAedMinor(bd("5.00"), "AED"))
        assertEquals(359000L, Money.toMinor(bd("3590.00")))
        assertNull(Money.toAedMinor(bd("1"), "XYZ"))
        assertTrue(Money.fromMinor(12345).compareTo(bd("123.45")) == 0)
    }
}
