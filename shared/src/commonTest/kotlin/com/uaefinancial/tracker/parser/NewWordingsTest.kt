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

/**
 * Wordings the smart reader didn't understand before engine version 2, starting with real Wio messages that ended
 * up in Needs review (29 Sep 2026), plus one-time codes that don't say "OTP".
 */
class NewWordingsTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 7, 0).toEpochMillis(zone)

    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, received, zone)

    private fun txn(sender: String, body: String): ParsedTransaction {
        val r = parse(sender, body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        return r.txn
    }

    private fun same(expected: String, actual: Decimal, msg: String) = assertEquals(0, Decimal(expected).compareTo(actual), msg)

    @AfterTest fun reset() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setExcludedBanks(emptySet())
    }

    // ------------------------------------------------------------------ Wio (real messages)

    @Test fun wio_personal_is_a_known_sender() {
        assertEquals("Wio", SmsParser.bankFor("WioPersonal")?.name)
        assertEquals("Wio", SmsParser.bankFor("Wio")?.name)
    }

    @Test fun wio_interest_earned_is_money_in() {
        for (body in listOf(
            "You have earned AED 37.05 as interest on your saving space Savings",
            "You have earned AED 30.85 as interest on your saving space EOS",
            "You have earned AED 33.06 as interest on your saving space EOS",
        )) {
            val t = txn("WioPersonal", body)
            assertEquals("Wio", t.bank)
            assertEquals(TxnType.TRANSFER_IN, t.type, body)
            assertEquals("Interest", t.merchant, body)
            assertEquals(CardType.ACCOUNT, t.cardType, body)
            assertTrue(t.accountNotNamed, body)
        }
        same("37.05", txn("WioPersonal", "You have earned AED 37.05 as interest on your saving space Savings").amount, "amount")
    }

    @Test fun wio_credit_repayment_is_a_card_payment() {
        val t = txn("WioPersonal", "You have made a credit repayment of AED 80.74.")
        assertEquals(TxnType.PAYMENT, t.type)
        same("80.74", t.amount, "amount")
        assertEquals(CardType.CREDIT, t.cardType)
        assertEquals("Card payment received", t.merchant)
        assertNull(t.cardLast4)
    }

    @Test fun wio_use_code_to_pay_is_an_otp_and_never_stored() {
        val r = parse("Wio", "Use code 022765 to pay AED 80.74 at Noon with card 2093. If it wasn't you, freeze the card and call us")
        assertEquals(ParseResult.Ignored("Wio", "OTP", store = false), r)
    }

    // ------------------------------------------------------------------ one-time codes without "OTP"

    @Test fun codes_are_otps_but_footers_and_references_are_not() {
        val otps = listOf(
            "Enter 482913 to complete your purchase of AED 120.00 at Amazon",
            "Your 3D Secure code 552190 for AED 45.00 at Careem. Do not share.",
            "Security code: 7731 for your login",
            "552190 is your verification code for AED 45 at Talabat",
            "Your code is 9921 to confirm the payment of AED 300",
        )
        for (b in otps) {
            val r = parse("Wio", b)
            assertIs<ParseResult.Ignored>(r, b)
            assertFalse(r.store, "OTP must not be stored: $b")
            assertTrue(SmsParser.isOtp(b), b)
        }
        val real = listOf(
            "Your Credit Card ending 1234 was used for AED 45.00 at CAREEM. Never share your security code with anyone. Call 8002222",
            "Transfer of AED 500.00 to Ali completed. Reference code 12345678.",
            "Merchant code 5411: AED 80.00 spent at CARREFOUR with card 2093",
            "AED 1,200.00 debited from your A/C XXXX5566 for DEWA. Ref 88231122",
        )
        for (b in real) assertFalse(SmsParser.isOtp(b), "Not an OTP: $b")
        // And the first one still reads as the purchase it is.
        val t = txn("Wio", real[0])
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("CAREEM", t.merchant)
    }

    // ------------------------------------------------------------------ interest, profit, fees, repayments

    @Test fun profit_credited_to_savings_is_money_in() {
        val t = txn("DIB", "Profit of AED 12.30 has been credited to your savings account XXXX4455.")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        assertEquals("Profit", t.merchant)
        assertEquals("4455", t.cardLast4)
        assertEquals(CardType.ACCOUNT, t.cardType)
    }

    @Test fun interest_charged_on_a_card_is_a_charge() {
        val t = txn("DIB", "Interest of AED 45.20 charged on your Credit Card ending 1234.")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("Interest / finance charge", t.merchant)
        assertEquals("1234", t.cardLast4)
        assertEquals(CardType.CREDIT, t.cardType)
    }

    @Test fun a_footer_mentioning_interest_does_not_change_a_purchase() {
        val t = txn("DIB", "Your Credit Card ending 1234 was used for AED 120.00 at CARREFOUR. Pay your full balance by the due date to avoid interest.")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("CARREFOUR", t.merchant)
    }

    @Test fun annual_fee_alone_is_a_charge() {
        val t = txn("DIB", "Annual fee of AED 300.00 has been charged to your Credit Card ending 1234.")
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals("Annual fee", t.merchant)
        same("300.00", t.amount, "amount")
        assertEquals("1234", t.cardLast4)
    }

    @Test fun loan_repayment_is_money_out() {
        val t = txn("DIB", "Your loan repayment of AED 2,000.00 has been debited from your account XXXX5566.")
        assertEquals(TxnType.TRANSFER_OUT, t.type)
        assertEquals("Loan repayment", t.merchant)
        assertEquals("5566", t.cardLast4)
    }

    @Test fun money_added_to_your_account_is_money_in() {
        val t = txn("Wio", "AED 500.00 added to your Wio account.")
        assertEquals(TxnType.TRANSFER_IN, t.type)
        same("500.00", t.amount, "amount")
    }

    // ------------------------------------------------------------------ banks you don't track

    @Test fun excluded_banks_are_not_read() {
        SmsParser.setExcludedBanks(setOf("Wio"))
        assertNull(SmsParser.bankFor("WioPersonal"))
        assertEquals(ParseResult.NotBank, parse("WioPersonal", "You have made a credit repayment of AED 80.74."))
        // Still listed (so setup can show it with an unticked box).
        assertEquals("Wio", SmsParser.anyBankFor("WioPersonal")?.name)
        SmsParser.setExcludedBanks(emptySet())
        assertEquals("Wio", SmsParser.bankFor("WioPersonal")?.name)
    }
}
