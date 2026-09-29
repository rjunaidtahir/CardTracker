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

/** "Apply to similar messages" (Needs review → Fix). */
class LearnedFormatsTest {

    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 9, 29, 7, 0).toEpochMillis(zone)

    @AfterTest fun reset() {
        LearnedFormats.setAll(emptyList())
        SmsParser.setCustomSenders(emptyMap())
    }

    private fun src(
        body: String, type: TxnType?, amount: String?, merchant: String,
        bank: String = "Acme Bank", last4: String? = null, cardType: CardType = CardType.ACCOUNT,
    ) = LearnedFormats.Source(
        key = body.hashCode().toString(), bank = bank, body = body, type = type,
        amount = amount?.let { Decimal(it) }, currency = "AED", merchant = merchant, cardLast4 = last4, cardType = cardType,
    )

    /** A sender with no bank rules, so only the smart reader and learned formats can read it. */
    private fun useAcme() = SmsParser.setCustomSenders(mapOf("ACMEBANK" to "Acme Bank"))

    private fun parse(body: String) = SmsParser.parse("ACMEBANK", body, received, zone)

    @Test fun a_fixed_message_teaches_similar_ones_with_their_own_amount() {
        useAcme()
        val first = "Your Acme saver pot Holiday grew by AED 12.40 this month"
        // The smart reader can't read this wording.
        assertIs<ParseResult.Failed>(parse(first))
        LearnedFormats.setAll(listOf(src(first, TxnType.TRANSFER_IN, "12.40", "Savings growth")))
        assertEquals(1, LearnedFormats.count())

        val r = parse("Your Acme saver pot Car fund grew by AED 7.15 this month")
        assertIs<ParseResult.Transaction>(r)
        assertEquals(LearnedFormats.RULE_ID, r.ruleId)
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
        assertEquals(0, Decimal("7.15").compareTo(r.txn.amount))
        assertEquals("AED", r.txn.currency)
        assertEquals("Savings growth", r.txn.merchant)
        assertEquals(CardType.ACCOUNT, r.txn.cardType)
        assertTrue(r.txn.accountNotNamed)
        assertEquals(received, r.txn.timestamp)
    }

    @Test fun the_merchant_is_taken_from_the_message_when_your_description_came_from_it() {
        useAcme()
        val first = "Acme card 2093 paid AED 80.74 at Noon with Apple Pay"
        LearnedFormats.setAll(listOf(src(first, TxnType.PURCHASE, "80.74", "Noon", last4 = "2093", cardType = CardType.CREDIT)))
        val r = parse("Acme card 7711 paid AED 15.00 at Talabat with Apple Pay")
        assertIs<ParseResult.Transaction>(r)
        assertEquals("Talabat", r.txn.merchant)
        assertEquals("7711", r.txn.cardLast4, "the new message's own card")
        assertEquals(CardType.CREDIT, r.txn.cardType)
    }

    @Test fun a_card_number_you_typed_is_used_when_the_message_has_none() {
        useAcme()
        val first = "Your Acme saver pot Holiday grew by AED 12.40 this month"
        LearnedFormats.setAll(listOf(src(first, TxnType.TRANSFER_IN, "12.40", "Savings growth", last4 = "4455")))
        val r = parse("Your Acme saver pot Car fund grew by AED 7.15 this month")
        assertIs<ParseResult.Transaction>(r)
        assertEquals("4455", r.txn.cardLast4)
    }

    @Test fun not_a_transaction_is_learned_too() {
        useAcme()
        val first = "Acme weekly summary: you spent AED 410.20 across 12 purchases"
        LearnedFormats.setAll(listOf(src(first, null, null, "")))
        val r = parse("Acme weekly summary: you spent AED 95.00 across 3 purchases")
        assertIs<ParseResult.Ignored>(r)
        assertTrue(r.store, "kept (just not a transaction)")
    }

    @Test fun money_in_is_never_applied_to_money_out() {
        useAcme()
        val first = "AED 250.00 has been credited to your Acme account from Salman"
        LearnedFormats.setAll(listOf(src(first, TxnType.TRANSFER_IN, "250.00", "From Salman")))
        val r = parse("AED 250.00 has been debited from your Acme account to Salman")
        assertFalse(r is ParseResult.Transaction && r.ruleId == LearnedFormats.RULE_ID, "must not reuse a money-in fix: $r")
    }

    @Test fun only_the_same_bank_and_similar_wording_match() {
        useAcme()
        val first = "Your Acme saver pot Holiday grew by AED 12.40 this month"
        LearnedFormats.setAll(listOf(src(first, TxnType.TRANSFER_IN, "12.40", "Savings growth", bank = "Other Bank")))
        assertFalse((parse(first) as? ParseResult.Transaction)?.ruleId == LearnedFormats.RULE_ID, "different bank")

        LearnedFormats.setAll(listOf(src(first, TxnType.TRANSFER_IN, "12.40", "Savings growth")))
        val different = parse("Acme: your card statement of AED 1,200.00 is ready, minimum AED 60.00")
        assertFalse((different as? ParseResult.Transaction)?.ruleId == LearnedFormats.RULE_ID, "different wording")
    }

    @Test fun otps_are_never_learned_or_matched() {
        useAcme()
        val otp = "Use code 022765 to pay AED 80.74 at Noon with card 2093"
        assertFalse(LearnedFormats.canLearn(src(otp, TxnType.PURCHASE, "80.74", "Noon")))
        // Even if something similar was learned, the OTP check runs first.
        LearnedFormats.setAll(listOf(src("Please use this reference 022765 to pay AED 80.74 at Noon with card 2093", TxnType.PURCHASE, "80.74", "Noon")))
        val r = parse(otp)
        assertIs<ParseResult.Ignored>(r)
        assertFalse(r.store)
    }

    @Test fun too_short_or_amountless_messages_are_not_learned() {
        assertFalse(LearnedFormats.canLearn(src("AED 5.00 paid", TxnType.PURCHASE, "5.00", "X")))
        assertFalse(LearnedFormats.canLearn(src("Your Acme card was used at Noon today", TxnType.PURCHASE, "5.00", "X")))
        assertTrue(LearnedFormats.canLearn(src("Your Acme saver pot Holiday grew by AED 12.40 this month", TxnType.TRANSFER_IN, "12.40", "X")))
        assertNull(LearnedFormats.read("Acme Bank", "Your Acme saver pot grew by AED 1.00", received))
    }
}
