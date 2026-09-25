package com.uaefinancial.tracker.parser

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

    // ----------------------------------------------------- FAB bank account
    // Real samples from the Review tab (23 Sep 2026).

    @Test fun fab_inward_remittance_is_account_credit() {
        val t = txn("FAB", "Inward Remittance\nCredit\nAccount XXXX8001\nAED 200.00\nDate 21/09/2026\nBalance AED 2586.00")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals("8001", t.cardLast4)
        assertEquals(bd("200.00"), t.amount)
        assertEquals(bd("2586.00"), t.availableLimit)
        assertEquals("Inward remittance", t.merchant)
    }

    @Test fun fab_outward_remittance_is_account_debit() {
        val t = txn("FAB", "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate 17/09/2026\nBalance AED 2386.00")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals(bd("1000.00"), t.amount)
        assertEquals(bd("2386.00"), t.availableLimit)
    }

    @Test fun fab_funds_transfer_to_own_and_family_cards() {
        val own = txn(
            "FAB",
            "Dear Customer, your funds transfer request of  AED 1,000.00 to IBAN/Account/Card XXXX9940  has been processed " +
                "successfully from your account/card XXXX8001 on 17/09/2026 21:45",
        )
        assertEquals(TxnType.TRANSFER_OUT, own.type)
        assertEquals("8001", own.cardLast4)
        assertEquals("9940", own.toLast4)
        assertEquals("Transfer to ·9940", own.merchant, "renamed by the app when ·9940 is one of your cards")
        assertEquals(ts(2026, 9, 17, 21, 45), own.timestamp)

        val alHilal = txn(
            "FAB",
            "Dear Customer, your funds transfer request of  AED 2,500.00 to IBAN/Account/Card XXXX3976  has been processed " +
                "successfully from your account/card XXXX8001 on 17/09/2026 20:55",
        )
        assertEquals(bd("2500.00"), alHilal.amount)
        assertEquals("Transfer to ·3976", alHilal.merchant)

        val wife = txn(
            "FAB",
            "Dear Customer, your funds transfer request of  AED 4,200.00 to IBAN/Account/Card XXXX6901  has been processed " +
                "successfully from your account/card XXXX8001 on 14/09/2026 19:29",
        )
        assertEquals("Transfer to ·6901", wife.merchant)
    }

    @Test fun fab_card_payment_from_account() {
        val t = txn("FAB", "Dear Customer, Your payment instructions of AED 500.00 to 5425********0831 has been processed on 15/09/2026 06:24")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertNull(t.cardLast4, "SMS doesn't name the paying account")
        assertTrue(t.accountNotNamed, "the app uses your only FAB account")
        assertEquals("0831", t.toLast4)
        assertEquals("Transfer to ·0831", t.merchant)
    }

    @Test fun fab_bill_payment_is_purchase_on_account() {
        val t = txn("FAB", "Dear Customer, Your payment instructions of AED 313.95 to HomeInternet for consumer number 045923079 has been processed on 15/09/2026 14:23")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertNull(t.cardLast4)
        assertTrue(t.accountNotNamed)
        assertEquals("HomeInternet", t.merchant)
        assertEquals(bd("313.95"), t.amount)
        assertNull(t.toLast4)
    }

    @Test fun fab_rewards_redemption_is_cashback() {
        val t = txn(
            "FAB",
            "Congratulations! You have successfully redeemed 20000 FAB Rewards to save on your bills.\nValue: AED 50\n" +
                "Redemption Type: Utility Bill\nRequest ID: 2999071897\n2026-09-15 14:23:31\nAvailable Balance: 74 FAB Rewards",
        )
        assertEquals(TxnType.REFUND, t.type)
        assertEquals(bd("50"), t.amount)
        assertNull(t.cardLast4, "not tied to a card, so it always counts")
    }

    @Test fun fab_scheduled_and_standing_instructions_are_ignored() {
        val r1 = SmsParser.parse(
            "FAB",
            "Dear Customer, your Within UAE Fund transfer to ENBD ANNUM Account/Card No. XXXX7701 has been scheduled. Transfer of AED 500.0 will be done on 28/09/2026.",
            received,
        )
        assertEquals(ParseResult.Ignored("FAB", "Scheduled transfer"), r1)
        val r2 = SmsParser.parse(
            "FAB",
            "Dear Customer, you have deregistered your standing Instruction service for Within UAE Fund transfer of AED 1,500.00 to ENBD ANNUM Account/Card No. XXXX7701",
            received,
        )
        assertIs<ParseResult.Ignored>(r2)
    }

    @Test fun adverts_are_ignored() {
        val ads = listOf(
            "Mashreq" to "Federal Government Treasury Sukuk is open till 28 Sep 2026. Subscriptions start at AED 1,000 and multiples thereafter. Apply to mashreq.com/retailoffering T&C STOP 4250",
            "Mashreq" to "Get Easy Cash up to AED 50000 instantly on your Mashreq Credit Card, with monthly instalments starting from AED 1237. Apply via Mashreq Mobile App. T&C STOP 4250",
            "Mashreq" to "You are eligible for a Mashreq Personal Loan of up to AED 375000*. Apply now on mashreq.com/enpl *T&C apply. STOP to 4250",
            "ADCBAlert" to "Get AED 200 cashback! Apply for a Credit Card on the ADCB Mobile App & spend AED 5,000 within 45 days of card issuance. Validity 30Sep26.T&C:adcb.com/ecb",
        )
        for ((sender, body) in ads) {
            val r = SmsParser.parse(sender, body, received)
            assertIs<ParseResult.Ignored>(r, body)
            assertEquals("Advert", r.reason, body)
        }
    }

    // ------------------------------------------ formats from "Share unparsed SMS" (Sep 2026)

    private data class TxnCase(
        val sender: String, val body: String, val type: TxnType, val card: String?, val amount: String,
        val currency: String = "AED", val merchant: String? = null, val cardType: CardType? = null, val to: String? = null,
    )

    @Test fun shared_transaction_formats() {
        val cases = listOf(
            // Emirates NBD
            TxnCase("EmiratesNBD", "Payment of AED 300.00 to ADNOC WALLET with Credit Card ending 9940. Avl Cr. Limit is AED 51,843.37.", TxnType.PURCHASE, "9940", "300.00", merchant = "ADNOC WALLET"),
            TxnCase("EmiratesNBD", "Payment of AED 1,109.53 to BNKNT UTLTY PYMNT-DEWA with Credit Card ending 9940. Avl Cr. Limit is AED 52,972.15.", TxnType.PURCHASE, "9940", "1109.53", merchant = "BNKNT UTLTY PYMNT-DEWA"),
            TxnCase("EmiratesNBD", "Payment of AED 96.75 to TEMU.COM with Credit Card ending 9940. Avl Cr. Limit is AED 38,404.74.", TxnType.PURCHASE, "9940", "96.75", merchant = "TEMU.COM"),
            TxnCase("EmiratesNBD", "Payment of AED 318.11 to BNKNT UTLTY PYMNT-ETISALAT with Credit Card ending 9940. Avl Cr. Limit is AED 0.00.", TxnType.PURCHASE, "9940", "318.11"),
            TxnCase("EmiratesNBD", "Amount of AED 43.81 from Amazon.ae has been credited to your card ending with 3944. Available limit is AED 54,081.68.", TxnType.REFUND, "3944", "43.81", merchant = "Amazon.ae"),
            TxnCase("EmiratesNBD", "Dear Customer, Amount of AED 53.72 from Amazon.ae has been credited to your card ending 3944. Available Limit is AED 39,354.08.", TxnType.REFUND, "3944", "53.72"),
            TxnCase("EmiratesNBD", "Amount of AED 10,960.00 from EMIRATES0002209069196 has been credited to your card ending with 9940. Available limit is AED 54,081.68.", TxnType.PAYMENT, "9940", "10960.00"),
            TxnCase("EmiratesNBD", "Purchase amount of AED 1.00 at ADNOC on your Credit Card ending 9940 has been refunded to your card account. Avl Limit is AED 40,464.60.", TxnType.REFUND, "9940", "1.00", merchant = "ADNOC"),
            // ADCB
            TxnCase("ADCBAlert", "Your Cr.Card XXX3538 was used for AED59.50 on 18/08/2026 14:06:42 at talabat.com,DUBAI-AE. Avl. Cr.limit is AED5897.94", TxnType.PURCHASE, "3538", "59.50", merchant = "talabat.com"),
            TxnCase("ADCBAlert", "Your Cr.Card XXX3538 was used for AZN5.00 on 23/03/2025 13:15:41 at I TICKET,BAKU-AZ. Avl. Cr.limit is AED2160.83", TxnType.PURCHASE, "3538", "5.00", currency = "AZN", merchant = "I TICKET"),
            TxnCase("ADCBAlert", "Credit Card XXX3538 used for AED1213.94 (+2.99% foreign txn fee) on 12/03/2026 10:00:26 at AGODA.COM AL HAMRA R,London-GB. Avl. Cr.limit AED1671.82", TxnType.PURCHASE, "3538", "1213.94", merchant = "AGODA.COM AL HAMRA R"),
            TxnCase("ADCBAlert", "Your payment of AED 102 against Credit Card no. XXX3538 was received at 08:21 AM on 24/08/2026. Thank you.", TxnType.PAYMENT, "3538", "102"),
            TxnCase("ADCBAlert", "An amount of AED510.15 has been reversed to your Credit Card XXX3538 on 21/12/2025 17:28:53 by AGODA.COM AL HAMRA V,INTERNET-GB.", TxnType.REFUND, "3538", "510.15", merchant = "AGODA.COM AL HAMRA V"),
            TxnCase("ADCBAlert", "An amount of USD1.00 has been reversed to your Credit Card XXX3538 on 29/04/2026 06:38:55 by AGODA.COM HOLIDAY IN,London-GB.", TxnType.REFUND, "3538", "1.00", currency = "USD"),
            TxnCase("ADCBAlert", "A Cr. transaction of AED 1558.46 on your account number XXX810001 was successful.Available balance is 9643.48.", TxnType.TRANSFER_IN, "0001", "1558.46", cardType = CardType.ACCOUNT),
            TxnCase("ADCBAlert", "A Dr. transaction of AED 1000.00 on your account number XXX810001 was successful.Available balance is 4084.63.", TxnType.PURCHASE, "0001", "1000.00", cardType = CardType.ACCOUNT),
            // FAB account
            TxnCase("FAB", "Outward Remittance \nDebit \nAccount XXXX8001 \nAED 2000.00\nValue Date 13/06/25  \nAvailable Balance AED 19051.83", TxnType.TRANSFER_OUT, "8001", "2000.00", cardType = CardType.ACCOUNT),
            TxnCase("FAB", "Inward Remittance \nCredit  \nAccount XXXX8001 \nAED 2000.00\nValue Date 13/10/2025 \nAvailable Balance AED 10952.54", TxnType.TRANSFER_IN, "8001", "2000.00"),
            TxnCase("FAB", "Outward Remittance\nDebit\nAccount XXXX8003\nAED 5000.00\nValue Date 29/06/24 \nAvailable Balance AED 135681.10", TxnType.TRANSFER_OUT, "8003", "5000.00"),
            TxnCase("FAB", "An amount of AED 110.81 has been credited to your FAB account XXXX8003 on 14/06/25 .Your Available Balance is AED 21377.94", TxnType.TRANSFER_IN, "8003", "110.81"),
            TxnCase("FAB", "An amount of AED 600.00 has been credited to your FAB account XXXX8001 on 24/10/2025\nYour Available Balance is AED 13457.48", TxnType.TRANSFER_IN, "8001", "600.00"),
            TxnCase("FAB", "An amount of AED 74.55 has been credited to your FAB account XXXX8001 on 10/09/2026 .Your balance is AED 8851.42", TxnType.TRANSFER_IN, "8001", "74.55"),
            TxnCase("FAB", "An amount of AED 3865.95 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08", TxnType.PURCHASE, "8001", "3865.95", merchant = "Account debit (EMI / direct debit)", cardType = CardType.ACCOUNT),
            TxnCase("FAB", "An amount of AED 4810.00 has been debited from your FAB account XXXX8001 on 31/08/2026. Your balance is AED 20691.73", TxnType.PURCHASE, "8001", "4810.00"),
            TxnCase("FAB", "An amount of AED .07 has been debited from your FAB account XXXX8001 on 26/09/23 .Your Available Balance is AED 17872.98", TxnType.PURCHASE, "8001", "0.07"),
            TxnCase("FAB", "Salary Credit\nAccount XXXX8001\nAED 20000.00\n23/05/25\nAvailable Balance AED 38841.91", TxnType.TRANSFER_IN, "8001", "20000.00", merchant = "Salary"),
            TxnCase("FAB", "Salary Credit\nAccount XXXX8001\nAED 20000.00\n29/08/2026\nBalance AED 26482.21", TxnType.TRANSFER_IN, "8001", "20000.00"),
            TxnCase("FAB", "ATM Cash withdrawal \nDebit Account XXXX8001 \nCard XXXX5919 \nAED 302.00 \n14/10/25 09:37 \nAvailable Balance AED 10652.54", TxnType.PURCHASE, "8001", "302.00", merchant = "ATM cash withdrawal"),
            TxnCase("FAB", "ATM Cash Withdrawal / Debit\nAccount XXXX8001\nCard XXXX9222\nAED 202.00\n01/08/26 20:47\nBalance AED 17153.88", TxnType.PURCHASE, "8001", "202.00"),
            TxnCase("FAB", "ATM Cash withdrawal\nDebit\nAccount XXXX8001\nCard XXXX1279\nAED 200.00\n16/06/24 20:42\nAvailable Balance AED 630.07", TxnType.PURCHASE, "8001", "200.00"),
            TxnCase("FAB", "Cash Deposit\nCredit\nAccount XXXX8001\nAED 9900.00\nDate 25/05/26", TxnType.TRANSFER_IN, "8001", "9900.00", merchant = "Cash deposit"),
            TxnCase("FAB", "Dear Customer, your funds transfer request of AED 6,000.00 from account XXXX8001 to account XXXX8003 has been processed on 05/08/2026 07:17. For more information please call 600525500 (+97126811511 if calling from overseas).", TxnType.TRANSFER_OUT, "8001", "6000.00", merchant = "Transfer to ·8003", to = "8003"),
            TxnCase("FAB", "Dear Customer, your funds transfer request of 200.00 AED to IBAN/Account/Card XXXX2001  has been processed successfully from your account/card XXXX8001 on 04/04/2026 15:01", TxnType.TRANSFER_OUT, "8001", "200.00", merchant = "Transfer to ·2001", to = "2001"),
            TxnCase("FAB", "Dear Customer, your payment of AED 100.00 for card 5492XXXXXXXX3115 has been processed on 06/01/2025", TxnType.TRANSFER_OUT, null, "100.00", merchant = "Transfer to ·3115", to = "3115"),
            TxnCase("FAB", "Dear Customer, your cashback amount of AED 100.00 has been credited to your credit card account with the card number ending 5492XXXXXXXX3115", TxnType.REFUND, "3115", "100.00", merchant = "Cashback"),
            // HSBC
            TxnCase("HSBC-UAE", "From HSBC: Your Credit Card ending with *** 5258 has been used for AED 33.25 on 02/12/2024 at W Z D WEST ZONE SUPERM. Your available limit is AED 3277.07", TxnType.PURCHASE, "5258", "33.25", merchant = "W Z D WEST ZONE SUPERM"),
            TxnCase("HSBC-UAE", "From HSBC: Your Credit Card ending with *** 5258 has been used for AED 130.55 on 29/12/2024 at Amazon.ae. Your available limit is AED 5596.50", TxnType.PURCHASE, "5258", "130.55", merchant = "Amazon.ae"),
            TxnCase("HSBC-UAE", "An amount of AED 41.53 has been reversed to your HSBC card ending *** 5258 (Amazon.ae). Your available limit is AED 8,786.31.\nتم إعادة مبلغ قدره 41.53  AED إلى بطاقة HSBC", TxnType.REFUND, "5258", "41.53", merchant = "Amazon.ae"),
            // Al Hilal
            TxnCase("AlHilal", "A cashback amount of 3.14 AED was credited to your credit card ending with 3976. Your available limit is now 4,093.50 AED.", TxnType.REFUND, "3976", "3.14"),
            // Mashreq
            TxnCase("Mashreq", "Your AC No:XXXXXXXX7639 is debited with AED 20000.00 for Aani Instant Payments (Local IPP Transfer). Login to Online Banking for details", TxnType.TRANSFER_OUT, "7639", "20000.00", merchant = "Aani Instant Payments (Local IPP Transfer)", cardType = CardType.ACCOUNT),
            TxnCase("Mashreq", "Your AC No: XXXXXXXX7639 is credited with AED 20000.00 for Salary. Login to Online Banking for details.", TxnType.TRANSFER_IN, "7639", "20000.00", merchant = "Salary"),
            TxnCase("Mashreq", "Your AC No: XXXXXXXX7639 is credited with AED 166.66 as Joining Bonus. Login to Online Banking for details.", TxnType.TRANSFER_IN, "7639", "166.66", merchant = "Joining Bonus"),
            TxnCase("Mashreq", "Amount of AED 20000.00 has been debited from your Mashreq account no. XXXXXXXX7639 for Account to Account Transfer. Login to Online Banking for details.", TxnType.TRANSFER_OUT, "7639", "20000.00"),
            TxnCase("Mashreq", "Your payment of AED 1993 has been received against your Mashreq Cashback card ending 4680 on 05/09/2026. Mashreq.com/mmb", TxnType.PAYMENT, "4680", "1993"),
        )
        for (c in cases) {
            val t = txn(c.sender, c.body)
            assertEquals(c.type, t.type, c.body)
            assertEquals(c.card, t.cardLast4, c.body)
            assertEquals(0, bd(c.amount).compareTo(t.amount), "amount: ${c.body}")
            assertEquals(c.currency, t.currency, c.body)
            c.merchant?.let { assertEquals(it, t.merchant, c.body) }
            c.cardType?.let { assertEquals(it, t.cardType, c.body) }
            c.to?.let { assertEquals(it, t.toLast4, c.body) }
        }
    }

    @Test fun shared_statement_formats() {
        val enbd = stmt("EmiratesNBD", "Emirates NBD Credit Card Mini Stmt for Card ending 9940: Statement date 09/05/26. Total Amt Due AED 7209.66, Due Date 03/06/26. Min Amt Due AED 360.48")
        assertEquals("9940", enbd.cardLast4)
        assertEquals(bd("7209.66"), enbd.statementBalance)
        assertEquals(bd("360.48"), enbd.minimumDue)
        assertEquals(LocalDate.of(2026, 6, 3), enbd.dueDate)
        assertEquals(LocalDate.of(2026, 5, 9), enbd.statementDate)

        val fab = stmt(
            "FAB",
            "Your statement of the card ending with 3115 dated 11Jun25 has been sent to you and can also be viewed in the new FAB mobile banking app, " +
                "download it from the App Store goo.gl/FB7qEZ or Google Play goo.gl/7dXnNc. The total amount due is AED 8,101.92. Minimum due is AED 405.10. Due date is 07Jul25",
        )
        assertEquals("3115", fab.cardLast4)
        assertEquals(bd("8101.92"), fab.statementBalance)
        assertEquals(bd("405.10"), fab.minimumDue)
        assertEquals(LocalDate.of(2025, 7, 7), fab.dueDate)
        assertEquals(LocalDate.of(2025, 6, 11), fab.statementDate)
    }

    @Test fun shared_ignored_formats() {
        val cases = listOf(
            "EmiratesNBD" to "DO NOT SHARE! The Auth code is 976688 for AED 200.00 at SMART DUBAI GOVERNMENT for card ending 9940. Use in 5 mins. If not requested call +971600540000",
            "EmiratesNBD" to "Payment for Credit Card ending 9940 is due on 03/09/26. Total Amt - AED 2862.26; Min Amt - AED 132.61. Please pay by due date to avoid charges.",
            "EmiratesNBD" to "Payment for Emirates NBD card ending 0866 is due on 28/02/25 and will be deducted from your bank account per your standing instruction. Please ensure the account is funded. Total Amt Due AED 110.09, Min Amt Due AED 100.00.",
            "EmiratesNBD" to "*Convert now* Pay as low as AED 51.36 per month for the purchase of AED 1289.81 at BNKNT UTLTY PYMNT-DEWA with credit card ending 9940 via clicking https://www.emiratesnbd.com/en/ipp/?ipp=901742725537144452647307139052",
            "EmiratesNBD" to "Brighten your summer! Get up to AED 4M personal loan with low rates & a payment holiday. Visit https://emiratesnbd.com/plsum Optout https://emiratesnbd.com/opto",
            "ADCBAlert" to "Ecommerce Transactions setting for your Credit Card XXX3538 updated on 11-06-2026 09:34:50. Call 600502030 if you did not initiate this request.",
            "ADCBAlert" to "Special Offer. Earn AED1,000 cashback on a minimum spend of AED15,000 with your ADCB Credit Card, from 1Apr24 to 30Jun24. To enrol, SMS SPD to 2626. adcb.com/spd",
            "ADCBAlert" to "Transaction of AED658.41 made at AGODA.COM LUMIAN HOT NA AE on 01/10/2025 07:27:02 on your Cr.Card XXX3538 could not be completed as you have exceeded your set daily limit.",
            "ADCBAlert" to "Review your transaction\nTap to approve your transaction at ADNOC for AED2.00 on your ADCB Card XXX3538, valid for 10 minutes.",
            "ADCBAlert" to "Enjoy easy monthly instalments on your purchase of AED 658.41 at AGODA.COM LUMIAN HOT INTERNET with an attractive interest rate and zero processing fee adcb.com/mobileapp",
            "ADCBAlert" to "Get 0.03 XAU when you open a Gold Account with 1 XAU or increase your balance by 1 XAU. Valid until 31 Dec 2025. For details visit: adcb.com/goldsilver",
            "ADCBAlert" to "Your digital card assigned to ADCB Credit Card XXX3538 for Samsung Pay has been de-activated. Please call 600502030 if you have not initiated this request.",
            "FAB" to "Get up to AED 150 on your international spends this winter. Spend AED 1000 equivalent in non-AED to get AED 50, and boost it to AED 150 when you reach AED 1500 with your FAB Card ending 3115. SMS INTL to 2121 to register. Conditions apply: bit.ly/47x0u8J",
            "FAB" to "Congratulations! You have successfully redeemed 2000 FAB Rewards.\nRedemption Type: Games\nRequest ID: 2982416245\nTime: 2026-09-01 06:02:04\nAvailable Balance: 18196 FAB Rewards",
            "Mashreq" to "Request received for fund transfer of AED 20000 (excl. applicable charges) with ref. no. MLC2908261552448. Contact  +971 4 424 4444 if you haven't initiated this.",
            "Mashreq" to "Your beneficiary has been added. It will be activated within the next 04:00 hour(s), until then you can do 2 transaction(s) up to AED 2,000.",
            "Mashreq" to "حول رصيدك المستحق على البطاقة الائتمانية المنتهية بـ 4680 إلى أقساط شهرية. استمتع بفائدة 0.49% شهرياً دون رسوم. سجل الدخول إلى المشرق موبايل. تطبق الشروط STOP4250",
        )
        for ((sender, body) in cases) {
            val r = SmsParser.parse(sender, body, received)
            assertIs<ParseResult.Ignored>(r, "Expected ignored: $body -> $r")
        }
        // Auth codes are OTPs: never stored.
        val auth = SmsParser.parse("EmiratesNBD", cases[0].second, received)
        assertIs<ParseResult.Ignored>(auth)
        assertEquals(false, auth.store)
    }

    // ------------------------------------------------ v1.1 formats (Review list)

    @Test fun v11_transaction_formats() {
        val cases = listOf(
            TxnCase("EmiratesNBD", "AED 50.00 has been debited from your Credit Card 0866 to top up your Nol e-purse. Avl.limit AED 2,345.00", TxnType.PURCHASE, "0866", "50.00", merchant = "Nol top-up"),
            TxnCase("EmiratesNBD", "Payment of AED 2,000.00 towards your Credit Card ending 9940 on 03/09/2026 was received through Online Banking. Thank you.", TxnType.PAYMENT, "9940", "2000.00"),
            TxnCase("EmiratesNBD", "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED -120.50", TxnType.PURCHASE, "3944", "28.72"),
            TxnCase("AlHilal", "Refund of 1 AED from CAREEM PLUS on 08-SEP-2026, 07:42:23 AM has been credited to your card ending with 3976.", TxnType.REFUND, "3976", "1", merchant = "CAREEM PLUS"),
            TxnCase("Mashreq", "Mashreq Credit Card ending 4680 was used for a transaction of AED 47,830.00 at LAND DEPARTMENT on Tuesday, 7 July 2026, 3:16 pm. Available limit: AED 12,170.00", TxnType.PURCHASE, "4680", "47830.00", merchant = "LAND DEPARTMENT"),
            TxnCase("Mashreq", "Dear Customer, your Aani payment of AED 120.00 to AN** KAI*** is successful.", TxnType.TRANSFER_OUT, null, "120.00", cardType = CardType.ACCOUNT),
            TxnCase("Mashreq", "Your Card ending with 0933 was used for cash withdrawal of AED 2,500.00 at MASHREQ ATM DXB on 02-MAY-2026 07:14 PM. Avl bal AED 5,000.00", TxnType.PURCHASE, "0933", "2500.00", merchant = "ATM cash withdrawal", cardType = CardType.DEBIT),
            TxnCase("Mashreq", "An amount of AED 10000.00 has been credited to your Mashreq account no. XXXXXXXX7639 for Inward Transfer. Login to Online Banking for details.", TxnType.TRANSFER_IN, "7639", "10000.00", merchant = "Inward Transfer"),
            TxnCase("ADCBAlert", "AED10900.00 transferred via ADCB Personal Internet Banking / Mobile App from acc. no. XXX810001 on May 25 2026 11:02AM. Avl. bal. AED 1,234.00", TxnType.TRANSFER_OUT, "0001", "10900.00", cardType = CardType.ACCOUNT),
            TxnCase("ADCBAlert", "Thank you and Congratulations on your new card. Your credit card xxx3538 was used for AED 257.00 on 12/06/2026 18:22:10 at CARREFOUR CITY,DUBAI- AE. Available credit limit is now AED 4,743.00", TxnType.PURCHASE, "3538", "257.00", merchant = "CARREFOUR CITY"),
            TxnCase("ADCBAlert", "A purchase transaction of USD265.00 has been performed on your Credit Card XXX3538 on 03/05/2026 10:11:12 at IIA STORE,NEW YORK-US. Available credit limit is now AED 3,000.00", TxnType.PURCHASE, "3538", "265.00", currency = "USD", merchant = "IIA STORE"),
            TxnCase("ADCBAlert", "AED308.34 debited from Acc/Cr.Card XXX3538 for DEWA on 21-08-2023 14:43:18", TxnType.PURCHASE, "3538", "308.34", merchant = "DEWA"),
            TxnCase("FAB", "Dear Customer, a debit of AED 500.00 has been made from your account XXXX8001 against your request for UAE PGS payment to JOHN SMITH through FAB Online on 25/Mar/2026 01:40", TxnType.TRANSFER_OUT, "8001", "500.00", merchant = "JOHN SMITH", cardType = CardType.ACCOUNT),
            TxnCase("FAB", "Your Dubai First card payment request of AED 8,000.00 to IBAN/Account/Card XXXX8623 was processed successfully from your account/card XXXX8001 on 02/09/2026 10:15", TxnType.TRANSFER_OUT, "8001", "8000.00", merchant = "Transfer to ·8623", to = "8623"),
            TxnCase("FAB", "Debit Card Purchase\nDebit Account XXXX8001\nCard XXXX5919\nAED 185.38\nAmazon.ae   Dubai  AE\n12/11/25 22:03\nAvailable Balance AED 9,000.00", TxnType.PURCHASE, "8001", "185.38", merchant = "Amazon.ae", cardType = CardType.ACCOUNT),
            TxnCase("FAB", "Congratulations! You have successfully redeemed 20000 FAB Al Futtaim Rewards to save on your bills. Value: AED 50", TxnType.REFUND, null, "50"),
            TxnCase("FAB", "Out of total amount due of AED 1308.34, payment of AED 1000 to DEWA for consumer number 2001234567 has been processed on 16/08/2023", TxnType.PURCHASE, null, "1000", merchant = "DEWA"),
            TxnCase("HSBC-UAE", "Your Credit Card ending *** 5258 was used for AED 8.00 at DRAGON ICE CAFE. Your available limit is AED 1,234.00", TxnType.PURCHASE, "5258", "8.00", merchant = "DRAGON ICE CAFE"),
        )
        for (c in cases) {
            val t = txn(c.sender, c.body)
            assertEquals(c.type, t.type, c.body)
            assertEquals(c.card, t.cardLast4, c.body)
            assertEquals(0, bd(c.amount).compareTo(t.amount), "amount: ${c.body}")
            assertEquals(c.currency, t.currency, c.body)
            c.merchant?.let { assertEquals(it, t.merchant, c.body) }
            c.cardType?.let { assertEquals(it, t.cardType, c.body) }
            c.to?.let { assertEquals(it, t.toLast4, c.body) }
        }
        // Times with AM/PM and weekday names
        assertEquals(ts(2026, 9, 8, 7, 42, 23), txn("AlHilal", cases[3].body).timestamp)
        assertEquals(ts(2026, 7, 7, 15, 16), txn("Mashreq", cases[4].body).timestamp)
        assertEquals(ts(2026, 5, 2, 19, 14), txn("Mashreq", cases[6].body).timestamp)
        assertEquals(ts(2026, 5, 25, 11, 2), txn("ADCBAlert", cases[8].body).timestamp)
        assertEquals(ts(2026, 3, 25, 1, 40), txn("FAB", cases[12].body).timestamp)
        assertEquals(bd("-120.50"), txn("EmiratesNBD", cases[2].body).availableLimit)
    }

    @Test fun v11_statement_formats() {
        val a = stmt("AlHilal", "Payment of AED 1423.09 for credit card ending with (3976) is due on 25 August 2026. Please pay on time.")
        assertEquals("3976", a.cardLast4)
        assertEquals(bd("1423.09"), a.statementBalance)
        assertNull(a.minimumDue)
        assertEquals(LocalDate.of(2026, 8, 25), a.dueDate)

        val f = stmt("FAB", "Dear Customer, the payment due date of your FAB Credit Card ending with 2784 is 06-12-2023. The total amount due is AED 3,734.00 and the Minimum due amount is AED 186.70.")
        assertEquals("2784", f.cardLast4)
        assertEquals(bd("3734.00"), f.statementBalance)
        assertEquals(bd("186.70"), f.minimumDue)
        assertEquals(LocalDate.of(2023, 12, 6), f.dueDate)

        val neg = stmt(
            "FAB",
            "Your statement of the card ending with 3115 dated 11Jun25 has been sent to you. The total amount due is AED -61.38. Minimum due is AED 0.00. Due date is 07Jul25",
        )
        assertEquals(bd("-61.38"), neg.statementBalance)
    }

    @Test fun v11_ignored_formats() {
        val cases = listOf(
            "EmiratesNBD" to "Your purchase of AED 1,200.00 at SHARAF DG has been converted to 12 monthly installments.",
            "ADCBAlert" to "Your transaction of AED 3,000 has been converted into installments. Monthly amount AED 250.",
            "Mashreq" to "Your beneficiary for AED transfers will be activated within 4 hours.",
            "FAB" to "Dear Customer, your mortgage loan of AED 1,000,000.00 has been disbursed to the seller.",
            "EmiratesNBD" to "Your Loan on Card request of AED 10,000.00 is approved and will be credited to your card.",
            "HSBC-UAE" to "You will now receive push notifications instead of SMS for transactions above AED 0.",
            "Mashreq" to "Congratulations! Your account has been opened. Your first deposit of AED 3000 is due.",
            "ADCBAlert" to "Your request for transfer of AED 5,000.00 is under process.",
            "FAB" to "Your transfer of AED 700.00 to account XXXX1234 has failed. Please try again.",
            "FAB" to "Your IPO subscription request for AED 10,000.00 has been received.",
            "EmiratesNBD" to "A provisional credit limit of AED 5,000 has been set on your new card.",
            "ADCBAlert" to "Last Stmt Bal. of Cr.Card XXX3538 is AED 1,263.92. Avl. limit AED 4,000.00",
            "FAB" to "Your Standing Instruction for AED 500.00 to card XXXX3115 has been registered.",
            "EmiratesNBD" to "Your invoice of AED 99.00 for DU is ready.",
            "ADCBAlert" to "The limit change of AED 2,000 for supplementary card XXX1234 is complete.",
            "HSBC-UAE" to "Spend AED 5,000 this quarter to unlock free lounge access. T&Cs apply.",
            "EmiratesNBD" to "Enjoy 10% off up to AED 100 at noon. Visit offers.emiratesnbd.com",
            "ADCBAlert" to "Get AED 500 cashback. Visit adcb.com/offers. TCs apply",
            "EmiratesNBD" to "Dine and save up to AED 200 this weekend. Opt out: SMS STOP",
            "FAB" to "Your summer deals: shop and save AED 250. TnC apply.",
            "Mashreq" to "Set up an installment plan for your AED 3,000 purchase. please visit mashreq.com",
        )
        for ((sender, body) in cases) {
            val r = SmsParser.parse(sender, body, received)
            assertIs<ParseResult.Ignored>(r, "Expected ignored: $body -> $r")
        }
        // HSBC transaction PIN is an OTP: never stored
        val pin = SmsParser.parse("HSBC-UAE", "PIN for transaction of AED 250.00 at AMAZON on card ending 5258 is 137781. Do not share.", received)
        assertIs<ParseResult.Ignored>(pin)
        assertEquals(false, pin.store)
    }

    @Test fun v11_date_parsing() {
        assertEquals(LocalDateTime.of(2026, 9, 8, 7, 42, 23) to true, SmsParser.parseDateTime("08-SEP-2026, 07:42:23 AM"))
        assertEquals(LocalDateTime.of(2026, 5, 2, 19, 14) to true, SmsParser.parseDateTime("02-MAY-2026 07:14 PM"))
        assertEquals(LocalDateTime.of(2026, 7, 7, 15, 16) to true, SmsParser.parseDateTime("Tuesday, 7 July 2026, 3:16 pm"))
        assertEquals(LocalDateTime.of(2026, 5, 25, 11, 2) to true, SmsParser.parseDateTime("May 25 2026 11:02AM"))
        assertEquals(LocalDateTime.of(2026, 3, 25, 1, 40) to true, SmsParser.parseDateTime("25/Mar/2026 01:40"))
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 5) to true, SmsParser.parseDateTime("01/01/2026 12:05 AM"))
        assertEquals(LocalDateTime.of(2026, 9, 13, 11, 58, 47) to true, SmsParser.parseDateTime("13/09/2026 11:58:47"))
        assertEquals(LocalDateTime.of(2025, 7, 7, 0, 0) to false, SmsParser.parseDateTime("07Jul25"))
    }

    // ------------------------------------------------ Mashreq (smart reader)

    @Test fun mashreq_unknown_card_format_read_by_smart_reader() {
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
        // Would otherwise be read as a purchase.
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
        assertEquals(ManualEntry("parking", bd("30"), "AED", null, TxnType.PURCHASE), ManualEntryParser.parse("dhs 30 parking"))
        assertEquals(ManualEntry("paid rent", bd("3000"), "AED", null, TxnType.PURCHASE), ManualEntryParser.parse("paid rent 3000"))
        assertEquals(ManualEntry("Card payment", bd("500"), "AED", "1234", TxnType.PAYMENT), ManualEntryParser.parse("card payment 500 #1234"))
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
