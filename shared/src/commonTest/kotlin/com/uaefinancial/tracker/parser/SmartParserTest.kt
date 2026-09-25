package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.DateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The smart reader, used for banks without their own rules. These messages are written in the styles UAE banks
 * use (they are not copies of real SMS). When you get a real sample that reads wrongly, add it here.
 */
class SmartParserTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis(zone)
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int) = DateTime.of(y, mo, d, h, mi).toEpochMillis(zone)
    private fun noon(y: Int, mo: Int, d: Int) = ts(y, mo, d, 12, 0)
    private fun bd(s: String) = Decimal(s)

    private fun txn(sender: String, body: String): ParsedTransaction {
        val r = SmsParser.parse(sender, body, received, zone)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r")
        return r.txn
    }

    @AfterTest fun reset() = SmsParser.setCustomSenders(emptyMap())

    @Test fun dib_credit_card_purchase() {
        val t = txn("DIB", "Dear Customer, your DIB Credit Card ending 4321 has been used for AED 250.00 at CARREFOUR CITY CENTRE on 21/09/2026 18:30. Available limit AED 9,750.00")
        assertEquals("Dubai Islamic Bank", t.bank)
        assertEquals("4321", t.cardLast4)
        assertEquals(CardType.CREDIT, t.cardType)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(bd("250.00"), t.amount)
        assertEquals("AED", t.currency)
        assertEquals("CARREFOUR CITY CENTRE", t.merchant)
        assertEquals(ts(2026, 9, 21, 18, 30), t.timestamp)
        assertEquals(bd("9750.00"), t.availableLimit)
        assertTrue(t.auto)
    }

    @Test fun emirates_islamic_debit_card_purchase_date_only() {
        val t = txn("EmiratesIslamic", "Purchase of AED 89.50 with Debit Card ending 7788 at NOON.COM, DUBAI on 20-09-2026. Avl Bal AED 3,210.40")
        assertEquals("Emirates Islamic", t.bank)
        assertEquals(CardType.DEBIT, t.cardType)
        assertEquals("7788", t.cardLast4)
        assertEquals("NOON.COM", t.merchant)
        assertEquals(noon(2026, 9, 20), t.timestamp)
        assertEquals(bd("3210.40"), t.availableLimit)
    }

    @Test fun rakbank_account_bill_debit() {
        val t = txn("RAKBANK", "AED 1,200.00 debited from your A/C XXXX5566 on 19/09/2026 for DEWA BILL PAYMENT. Avl Bal: AED 8,800.00")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals("5566", t.cardLast4)
        assertEquals("DEWA BILL PAYMENT", t.merchant)
        assertEquals(bd("1200.00"), t.amount)
        assertEquals(bd("8800.00"), t.availableLimit)
    }

    @Test fun cbd_salary_credit() {
        val t = txn("CBD", "Your A/C no. XXXX9012 has been credited with AED 15,000.00 on 20/09/2026 (SALARY). Available balance AED 17,500.00")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        assertEquals(CardType.ACCOUNT, t.cardType)
        assertEquals("9012", t.cardLast4)
        assertEquals("Salary", t.merchant)
        assertEquals(bd("15000.00"), t.amount)
    }

    @Test fun adib_transfer_to_own_account() {
        val t = txn("ADIB", "AED 500.00 transferred from your account XXXX3344 to account XXXX7788 on 21/09/2026 10:15. Ref 12345")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals("3344", t.cardLast4)
        assertEquals("7788", t.toLast4)
        assertEquals("Transfer to ·7788", t.merchant)
        assertEquals(ts(2026, 9, 21, 10, 15), t.timestamp)
    }

    @Test fun citi_foreign_refund() {
        val t = txn("Citibank", "Refund of USD 25.00 from AMAZON MKTPL has been credited to your Citi Credit Card ending 1122.")
        assertEquals(TxnType.REFUND, t.type)
        assertEquals("USD", t.currency)
        assertEquals(bd("25.00"), t.amount)
        assertEquals("AMAZON MKTPL", t.merchant)
        assertEquals("1122", t.cardLast4)
        assertEquals(received, t.timestamp, "No date in the SMS: the time it arrived")
        assertFalse(t.dateFromSms)
    }

    @Test fun card_payment_received() {
        val t = txn("StanChart", "Thank you. We have received your payment of AED 3,000.00 towards your Credit Card ending 5566 on 20/09/2026.")
        assertEquals(TxnType.PAYMENT, t.type)
        assertEquals("5566", t.cardLast4)
        assertEquals(bd("3000.00"), t.amount)
    }

    @Test fun atm_withdrawal_on_debit_card() {
        val t = txn("DIB", "Cash withdrawal of AED 500.00 from your Debit Card XXXX2211 at ATM DIB MALL on 21/09/2026 14:02. Available balance AED 1,000.00")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("ATM cash withdrawal", t.merchant)
        assertEquals(CardType.DEBIT, t.cardType)
        assertEquals("2211", t.cardLast4)
    }

    @Test fun dirhams_written_as_dhs() {
        val t = txn("NBF", "Dhs 45.00 spent on your credit card XXXX6677 at SALIK on 21/09/2026")
        assertEquals("AED", t.currency)
        assertEquals(bd("45.00"), t.amount)
        assertEquals("SALIK", t.merchant)
        assertEquals("6677", t.cardLast4)
    }

    @Test fun foreign_purchase_merchant_before_date() {
        val t = txn("CBD", "Transaction of EUR 30.00 at MUSEE DU LOUVRE PARIS FR on 12/09/2026 16:44 with your credit card xxx9988 was approved.")
        assertEquals("EUR", t.currency)
        assertEquals("MUSEE DU LOUVRE PARIS FR", t.merchant)
        assertEquals("9988", t.cardLast4)
        assertEquals(ts(2026, 9, 12, 16, 44), t.timestamp)
    }

    @Test fun aani_transfer_to_a_person() {
        val t = txn("Wio", "You have sent AED 250.00 to AHMED ALI via Aani from account XXXX4455 on 21/09/2026.")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals("Transfer to AHMED ALI", t.merchant)
        assertEquals("4455", t.cardLast4)
        assertEquals(CardType.ACCOUNT, t.cardType)
    }

    @Test fun statement_with_total_minimum_and_due_date() {
        val r = SmsParser.parse("RAKBANK", "Your Credit Card statement for card ending 4321 is ready. Total amount due AED 5,432.10, minimum amount due AED 271.60. Payment due date 15/10/2026.", received, zone)
        assertIs<ParseResult.Statement>(r)
        val s = r.statement
        assertEquals("4321", s.cardLast4)
        assertEquals(bd("5432.10"), s.statementBalance)
        assertEquals(bd("271.60"), s.minimumDue)
        assertEquals(CalendarDate.of(2026, 10, 15), s.dueDate)
        assertTrue(s.auto)
    }

    @Test fun payment_due_reminder_is_a_statement() {
        val r = SmsParser.parse("ADIB", "Payment of AED 1,423.09 for your credit card ending 8899 is due on 25 October 2026.", received, zone)
        assertIs<ParseResult.Statement>(r)
        assertEquals(bd("1423.09"), r.statement.statementBalance)
        assertEquals(CalendarDate.of(2026, 10, 25), r.statement.dueDate)
    }

    @Test fun purchase_with_statement_footer_stays_a_purchase() {
        val t = txn("CBD", "Purchase of AED 12.00 on credit card XXXX1111 at CAFE NERO on 21/09/2026 09:00. Avl limit AED 900.00. Your statement is due on 26/09/2026")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("CAFE NERO", t.merchant)
    }

    @Test fun declined_and_scheduled_are_not_transactions() {
        val r1 = SmsParser.parse("DIB", "Your transaction of AED 100.00 at ZARA with card ending 4321 was declined due to insufficient funds.", received, zone)
        assertIs<ParseResult.Ignored>(r1)
        val r2 = SmsParser.parse("DIB", "Your transfer of AED 2,000.00 to account XXXX1234 will be processed on 30/09/2026.", received, zone)
        assertFalse(r2 is ParseResult.Transaction, "Not done yet: $r2")
    }

    @Test fun balance_only_message_is_ignored() {
        val r = SmsParser.parse("CBD", "The available balance in your A/C XXXX1234 is AED 5,000.00 as of 21/09/2026.", received, zone)
        assertIs<ParseResult.Ignored>(r)
    }

    @Test fun unreadable_message_goes_to_review() {
        val r = SmsParser.parse("CBD", "AED 5,000.00 XXXX1234 21/09/2026", received, zone)
        assertIs<ParseResult.Failed>(r)
    }

    @Test fun rule_bank_new_format_is_read_by_the_smart_reader() {
        val t = txn("FAB", "Credit Card Refund Card No XXXX0831 AED 20.00 NOON.COM 22/09/26 10:00")
        assertEquals(TxnType.REFUND, t.type)
        assertEquals("0831", t.cardLast4)
        assertEquals(bd("20.00"), t.amount)
        assertTrue(t.auto)
    }

    @Test fun added_sender_for_an_unknown_bank() {
        assertNull(SmsParser.bankFor("MyBank"))
        SmsParser.setCustomSenders(mapOf("MyBank" to "My Bank"))
        val t = txn("MyBank", "Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00.")
        assertEquals("My Bank", t.bank)
        assertEquals("LULU HYPERMARKET", t.merchant)
        assertEquals(ts(2026, 9, 21, 12, 0), t.timestamp)
    }

    @Test fun added_sender_for_a_known_bank_uses_its_rules() {
        SmsParser.setCustomSenders(mapOf("ADCB-NEW" to "ADCB"))
        val r = SmsParser.parse(
            "ADCB-NEW",
            "Credit Card XX3538 was used for AED90.90 on 13/09/2026 11:58:47 at talabat.com, DUBAI-AE. Available limit AED4736.08",
            received, zone,
        )
        assertIs<ParseResult.Transaction>(r)
        assertEquals("adcb-purchase", r.ruleId)
        assertEquals("ADCB", r.txn.bank)
    }

    @Test fun short_sender_ids_match_exactly() {
        assertEquals("FAB", SmsParser.bankFor("AD-FAB")?.name)
        assertEquals("Dubai Islamic Bank", SmsParser.bankFor("DIB")?.name)
        assertEquals("Dubai Islamic Bank", SmsParser.bankFor("AD-DIB")?.name)
        assertNull(SmsParser.bankFor("XYZFAB"))
        assertNull(SmsParser.bankFor("NODIB"))
        assertEquals("ADCB", SmsParser.bankFor("AD-ADCBAlert")?.name)
    }

    @Test fun lower_case_words_are_not_currencies() {
        // "try" is also the Turkish lira code: only upper-case codes count.
        val r = SmsParser.parse("CBD", "Please try 2 times later or call us. Your card ending 1234 is active.", received, zone)
        assertIs<ParseResult.Ignored>(r, "no amount in this message: $r")
    }

    @Test fun reference_numbers_are_not_amounts() {
        val r = SmartParser.read("X", "Payment of AED 123456789012345.00 with card ending 1111 at SHOP", received, zone)
        assertNull(r as? ParseResult.Transaction)
    }

    @Test fun masked_card_number_is_not_an_amount() {
        val t = txn("DIB", "Card XXXX0831 AED 5.00 spent at PICCADILLY CAFE on 19/09/2026 17:48")
        assertEquals(bd("5.00"), t.amount)
        assertEquals("0831", t.cardLast4)
    }

    @Test fun looks_like_bank_alert() {
        assertTrue(SmartParser.looksLikeBankAlert("AED 250.00 debited from A/C XXXX1234 on 21/09/2026"))
        assertTrue(SmartParser.looksLikeBankAlert("Your card ending 9090 was used for AED 60.00 at LULU"))
        assertFalse(SmartParser.looksLikeBankAlert("Get 50% off! Only AED 99 this weekend. Visit our store"))
        assertFalse(SmartParser.looksLikeBankAlert("123456 is your OTP for AED 50.00 on card ending 1234"))
        assertFalse(SmartParser.looksLikeBankAlert("See you at 5"))
    }

    /**
     * Real statement SMS from banks that DO have rules, read by the smart reader alone: the check that it copes
     * with wording it has never seen.
     */
    @Test fun smart_reader_alone_reads_real_statement_sms() {
        val cases = listOf(
            "Cr.Card XXX3538 Billing alert: Total due to avoid fin. charges: AED1263.92. Due date Oct 14 2026; Pay min. AED100.00 by due date to avoid AED241.50 late fees." to "3538 1263.92 100.00 2026-10-14",
            "HSBC Credit Card ending *** 5258 Statement Date 11/08/2026. Total Amt Due AED 79.20, Due Date 05/09/2026. Min. Amt Due AED 33.70. Please ignore if paid." to "5258 79.20 33.70 2026-09-05",
            "Emirates NBD Credit Card Mini Stmt for Card ending 9940: Statement date 09/05/26. Total Amt Due AED 7209.66, Due Date 03/06/26. Min Amt Due AED 360.48" to "9940 7209.66 360.48 2026-06-03",
            "Your statement of the card ending with 3115 dated 11Jun25 has been sent to you. The total amount due is AED 8,101.92. Minimum due is AED 405.10. Due date is 07Jul25" to "3115 8101.92 405.10 2025-07-07",
            "Payment of AED 1423.09 for credit card ending with (3976) is due on 25 August 2026. Please pay on time." to "3976 1423.09 null 2026-08-25",
            "Dear Customer, the payment due date of your FAB Credit Card ending with 2784 is 06-12-2023. The total amount due is AED 3,734.00 and the Minimum due amount is AED 186.70." to "2784 3734.00 186.70 2023-12-06",
        )
        for ((body, want) in cases) {
            val r = SmartParser.read("X", SmsParser.normalizeBody(body), received, zone)
            assertIs<ParseResult.Statement>(r, body)
            val st = r.statement
            assertEquals(want, "${st.cardLast4} ${st.statementBalance} ${st.minimumDue} ${st.dueDate}", body)
        }
    }

    /** Real transaction SMS read by the smart reader alone (bank rules bypassed). */
    @Test fun smart_reader_alone_reads_real_transaction_sms() {
        data class C(val body: String, val type: TxnType, val card: String?, val amount: String, val merchant: String?)
        val cases = listOf(
            C("Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12", TxnType.PURCHASE, "3944", "28.72", "MORE VALUE SUPERMARKE"),
            C("Credit Card XX3538 was used for AED90.90 on 13/09/2026 11:58:47 at talabat.com, DUBAI-AE. Available limit AED4736.08", TxnType.PURCHASE, "3538", "90.90", "talabat.com"),
            C("Purchase of 3,589.95 AED at LIVA INS B S C CLOSED    ABU DHABI    AE on 14-09-2026,  10:20:46, card 3976. Limit: 338.04 AED.", TxnType.PURCHASE, "3976", "3589.95", "LIVA INS B S C CLOSED"),
            C("Your Cr.Card XXX3538 was used for AZN5.00 on 23/03/2025 13:15:41 at I TICKET,BAKU-AZ. Avl. Cr.limit is AED2160.83", TxnType.PURCHASE, "3538", "5.00", "I TICKET"),
            C("Thank you for the payment of AED 900.00 on 03/09/2026 towards your Credit Card ending *** 5258.", TxnType.PAYMENT, "5258", "900.00", null),
            C("Your payment of AED 1993 has been received against your Mashreq Cashback card ending 4680 on 05/09/2026. Mashreq.com/mmb", TxnType.PAYMENT, "4680", "1993", null),
            C("An amount of AED510.15 has been reversed to your Credit Card XXX3538 on 21/12/2025 17:28:53 by AGODA.COM AL HAMRA V,INTERNET-GB.", TxnType.REFUND, "3538", "510.15", "AGODA.COM AL HAMRA V"),
            C("A cashback amount of 3.14 AED was credited to your credit card ending with 3976. Your available limit is now 4,093.50 AED.", TxnType.REFUND, "3976", "3.14", "Cashback"),
            C("Your AC No: XXXXXXXX7639 is credited with AED 166.66 as Joining Bonus. Login to Online Banking for details.", TxnType.TRANSFER_IN, "7639", "166.66", "Joining Bonus"),
            C("Inward Remittance\nCredit\nAccount XXXX8001\nAED 200.00\nDate 20/09/2026\nBalance AED 2586.00", TxnType.TRANSFER_IN, "8001", "200.00", "Inward remittance"),
            C("Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate 17/09/2026\nBalance AED 2386.00", TxnType.TRANSFER_OUT, "8001", "1000.00", null),
            C("Dear Customer, your funds transfer request of AED 6,000.00 from account XXXX8001 to account XXXX8003 has been processed on 05/08/2026 07:17.", TxnType.TRANSFER_OUT, "8001", "6000.00", "Transfer to ·8003"),
            C("Dear Customer, your payment of AED 100.00 for card 5492XXXXXXXX3115 has been processed on 06/01/2025", TxnType.TRANSFER_OUT, null, "100.00", "Transfer to ·3115"),
            C("A Cr. transaction of AED 1558.46 on your account number XXX810001 was successful.Available balance is 9643.48.", TxnType.TRANSFER_IN, "0001", "1558.46", null),
            C("Your Card ending with 0933 was used for cash withdrawal of AED 2,500.00 at MASHREQ ATM DXB on 02-MAY-2026 07:14 PM. Avl bal AED 5,000.00", TxnType.PURCHASE, "0933", "2500.00", "ATM cash withdrawal"),
        )
        for (c in cases) {
            val r = SmartParser.read("X", SmsParser.normalizeBody(c.body), received, zone)
            assertIs<ParseResult.Transaction>(r, c.body)
            val t = r.txn
            assertEquals(c.type, t.type, c.body)
            assertEquals(c.card, t.cardLast4, c.body)
            assertEquals(0, bd(c.amount).compareTo(t.amount), c.body)
            c.merchant?.let { assertEquals(it, t.merchant, c.body) }
        }
    }
}
