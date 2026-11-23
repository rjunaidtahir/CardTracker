package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * v20 (23 Nov 2026 loop run, 11:23 slot): four Wio reading rules (card payment with a second amount, a purchase at a merchant with
 * "lounge" in the name that the global lounge ignore hid, a cancelled payment credited back, a local transfer) and three narrow ignore
 * entries (Wio upcoming-subscription notice, CBD low-balance bill notice, CBD card transaction not processed).
 * Every wording below is invented from the masked shape; look-alikes that must NOT change are checked next to each one.
 */
class ShippedPackV20Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 20)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun entry(label: String): Regex {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out)
        val e = out.pack.ignore.first { it.label == label }
        assertTrue(e.label.length <= 40)
        return Regex(e.pattern, RegexOption.IGNORE_CASE)
    }

    // ---- reading rules -------------------------------------------------------------------------------------------

    @Test fun wioCardPaymentWithSecondAmountIsReadAsDebitCardPurchase() {
        for ((body, cur, amount, merchant) in listOf(
            listOf("✅ card payment. USD 10.00 (AED 36.73) at SETAPP* SETAPP using your Wio Personal card 1234 with Own AED funds", "USD", "10.00", "SETAPP* SETAPP"),
            listOf("✅ card payment. AED 1,250.50 (AED 1,250.50) at Quaich bar avant garde using your Wio Personal card 4321 with Own AED funds", "AED", "1250.50", "Quaich bar avant garde"),
            listOf("✅ card payment. AED 45.00 (AED 45.00) at Grab a a iht wwwwklav using your Wio Personal card 1234 with Own AED funds", "AED", "45.00", "Grab a a iht wwwwklav"),
        )) {
            val r = read("Wio", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:wio-card-payment-two-amounts-20261123", r.ruleId, body)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(CardType.DEBIT, r.txn.cardType)
            assertEquals(Regex("""card (\d{4}) with""").find(body)!!.groupValues[1], r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(cur, r.txn.currency)
            assertEquals(merchant, r.txn.merchant)
        }
    }

    @Test fun wioCardPaymentLookAlikesKeepTheirOwnReading() {
        // The one-amount wording is still read by the older rule.
        val one = read("Wio", "✅ card payment. AED 10.00 at Noon using your Wio Personal card 1234 with Own AED funds")
        assertIs<ParseResult.Transaction>(one)
        assertEquals("pack:wio-card-payment", one.ruleId)
        // The upcoming-subscription notice and the cancellation wording never use the new rule.
        for (body in listOf(
            "Upcoming Claude subscription payment. Your Claude subscription of AED 49.00 may come through around 25 Nov. If you've cancelled with Claude, you can ignore this.",
            "Payment of AED 25.00 (AED 25.00) at Uber was cancelled and the amount has been credited back to your Wio Personal card ending with 1234.",
        )) {
            val r = read("Wio", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:wio-card-payment-two-amounts-20261123", r.ruleId, body)
        }
    }

    @Test fun wioPaymentAtLoungeMerchantIsReadNotHidden() {
        for ((body, amount, merchant) in listOf(
            Triple("Payment of AED 80.00 was done at Jazz Lounge Spa using your Wio Personal card 1234 with Own AED funds", "80.00", "Jazz Lounge Spa"),
            Triple("Payment of AED 120.50 was done at Qlub jazz lounge gol using your Wio Personal card 1234 with Own AED funds", "120.50", "Qlub jazz lounge gol"),
            Triple("Payment of USD 12.00 (AED 44.07) was done at Kaliki lounge using your Wio Personal card 1234 with Own AED funds", "12.00", "Kaliki lounge"),
        )) {
            val r = read("Wio", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:wio-payment-was-done-lounge-20261123", r.ruleId, body)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(CardType.DEBIT, r.txn.cardType)
            assertEquals("1234", r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(merchant, r.txn.merchant)
        }
    }

    @Test fun wioLoungeLookAlikesDoNotUseTheNewRule() {
        // A purchase at a merchant without "lounge" does not use the lounge rule (its reading is unchanged), and a lounge advert is still hidden.
        for (body in listOf(
            "Payment of AED 80.00 was done at Noon using your Wio Personal card 1234 with Own AED funds",
            "Enjoy free airport lounge access with your Wio Personal card. Spend AED 5,000.00 in a month. T&C apply.",
            "Payment of AED 80.00 will be done at Jazz Lounge Spa using your Wio Personal card 1234",
        )) {
            val r = read("Wio", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:wio-payment-was-done-lounge-20261123", r.ruleId, body)
        }
        assertIs<ParseResult.Ignored>(read("Wio", "Enjoy free airport lounge access with your Wio Personal card. Spend AED 5,000.00 in a month. T&C apply."))
    }

    @Test fun wioCancelledPaymentCreditedBackIsReadAsRefund() {
        for ((body, amount, merchant) in listOf(
            Triple("Payment of AED 25.00 (AED 25.00) at Uber was cancelled and the amount has been credited back to your Wio Personal card ending with 1234.", "25.00", "Uber"),
            Triple("Payment of USD 9.00 (AED 33.05) at Bolt eu o was cancelled and the amount has been credited back to your Wio Personal card ending with 1234.", "9.00", "Bolt eu o"),
            Triple("Payment of AED 1,100.00 at Collectingcars com was cancelled and the amount has been credited back to your Wio Personal card ending with 1234.", "1100.00", "Collectingcars com"),
        )) {
            val r = read("Wio", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:wio-payment-cancelled-credited-back-20261123", r.ruleId, body)
            assertEquals(TxnType.REFUND, r.txn.type)
            assertEquals(CardType.DEBIT, r.txn.cardType)
            assertEquals("1234", r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals(merchant, r.txn.merchant)
        }
    }

    @Test fun wioCancelledPaymentLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "✅ card payment. AED 25.00 (AED 25.00) at Uber using your Wio Personal card 1234 with Own AED funds",
            "Payment of AED 25.00 at Uber was declined. Please check your card limit.",
            "Payment of AED 25.00 (AED 25.00) at Uber was cancelled. No amount was charged to your Wio Personal card ending with 1234.",
        )) {
            val r = read("Wio", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:wio-payment-cancelled-credited-back-20261123", r.ruleId, body)
        }
    }

    @Test fun wioLocalTransferProcessedIsReadAsTransferOut() {
        val body = "Your local transfer of AED 5,000.00 to ACME PROPERTY MANAGEMENT LLC from your account number 1234567890 was processed."
        val r = read("Wio", body)
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("pack:wio-local-transfer-processed-20261123", r.ruleId)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
        assertEquals(CardType.ACCOUNT, r.txn.cardType)
        assertEquals("7890", r.txn.cardLast4)
        assertEquals(Decimal("5000.00"), r.txn.amount)
        assertEquals("ACME PROPERTY MANAGEMENT LLC", r.txn.merchant)
    }

    @Test fun wioLocalTransferLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "Your local transfer of AED 5,000.00 to ACME LLC from your account number 1234567890 has failed.",
            "AED 5,000.00 sent by ACME LLC and has been credited into your account number 1234567890.",
        )) {
            val r = read("Wio", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:wio-local-transfer-processed-20261123", r.ruleId, body)
        }
    }

    // ---- ignore entries -------------------------------------------------------------------------------------------

    @Test fun wioUpcomingSubscriptionNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: Wio upcoming subscription")
        for (body in listOf(
            "Upcoming Claude subscription payment. Your Claude subscription of AED 49.00 may come through around 25 Nov. If you've cancelled with Claude, you can ignore this.",
            "Upcoming Amazon Prime subscription payment. Your Amazon Prime subscription of AED 19.00 may come through around 25 Nov. If you've cancelled with Amazon Prime, you can ignore this.",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("Wio", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
            assertEquals("Notice: Wio upcoming subscription", r.reason)
        }
        for (body in listOf(
            "✅ card payment. AED 49.00 (AED 49.00) at Claude using your Wio Personal card 1234 with Own AED funds",
            "Payment of AED 49.00 was done at Claude using your Wio Personal card 1234 with Own AED funds",
            "Your Claude subscription of AED 49.00 was charged to your Wio Personal card 1234.",
        )) assertTrue(!re.containsMatchIn(body), body)
        val purchase = read("Wio", "✅ card payment. AED 49.00 (AED 49.00) at Claude using your Wio Personal card 1234 with Own AED funds")
        assertIs<ParseResult.Transaction>(purchase)
    }

    @Test fun cbdLowBalanceBillNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Failed: CBD bill low balance")
        for (body in listOf(
            "Sorry, you did not have enough balance to pay your DEWA bill of AED 120.00 ,please pay your bill in the CBD app",
            "Sorry, you did not have enough balance to pay your Etisalat bill of AED 120.00, please pay your bill in the CBD app.",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("CBD", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
        }
        for (body in listOf(
            "Thank you for paying your DEWA bill of AED 120.00 from your account ending 1234.",
            "A transaction of AED 120.00 has been debited from your Credit Card 1234 at DEWA. Your balance is AED 500.00",
            "AED 120.00 debited from your account 1234 for bill payment DEWA. Your balance is AED 500.00",
        )) assertTrue(!re.containsMatchIn(body), body)
    }

    @Test fun cbdCardTransactionNotProcessedIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Failed: CBD card txn not processed")
        for (body in listOf(
            "Transaction of AED 50.00 with Credit Card ending 1234 at www.example.com could not be processed since Card is inactive. Please activate your Card.",
            "Transaction of AED 50.00 with Credit Card ending 1234 at GOOGLE *WALLET TEMP could not be processed due to invalid CVV/CVC. CVV/CVC is on the back of the Card.",
        )) {
            assertTrue(re.containsMatchIn(body), body)
            val r = read("CBD", body)
            assertIs<ParseResult.Ignored>(r, "got $r for: $body")
        }
        for (body in listOf(
            "A transaction of AED 50.00 has been debited from your Credit Card 1234 at NOON. Your balance is AED 900.00",
            "Transaction of AED 50.00 with Credit Card ending 1234 at NOON has been processed. Avl limit AED 900.00",
            "Thank you for your credit card payment via funds transfer of AED 500.00 for card ending 1234 on 21/11/2026",
        )) assertTrue(!re.containsMatchIn(body), body)
        // The nearest real purchase at the same bank is still read.
        val r = read("CBD", "A transaction of AED 50.00 has been debited from your Credit Card 1234 at NOON. Your balance is AED 900.00")
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals(Decimal("50.00"), r.txn.amount)
    }

    @Test fun packCountsAfterV20() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.rules.size >= 32)
        assertTrue(out.pack.ignore.size >= 43)
    }
}
