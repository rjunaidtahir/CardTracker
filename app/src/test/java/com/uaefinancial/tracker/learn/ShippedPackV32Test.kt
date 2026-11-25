package com.uaefinancial.tracker.learn

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
import kotlin.test.assertTrue

/**
 * PREPARED for the next loop run (25 Nov 2026, 15:23 slot found it after v31 was already published; one version bump per run).
 * Wrongly hidden real transaction: Emirates NBD Arabic "payment received for your credit card" (1 loose-masked report, 2.8,
 * reported hidden:Informational (no amount), reader would say REFUND). One reading rule, type PAYMENT, credit card, fixed merchant.
 * Wording invented from the masked shape; tested with the currency glued to the Arabic verb and spaced.
 */
class ShippedPackV32Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val id = "pack:enbd-arabic-card-payment-received-20261125"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 32)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    @Test fun arabicCardPaymentReceivedIsReadAsPayment() {
        for ((body, amount, avail) in listOf(
            Triple("تمّ استلام AED 500.00 كدفعة لصالح بطاقتك الائتمانية المنتهية أرقامها بـ 1234 في 24/11/2026 عبر الخدمات المصرفية عبر الإنترنت. الحد المتوفر لك AED 7,250.00.", "500.00", "7250.00"),
            Triple("تمّ استلامAED 1,250.50 كدفعة لصالح بطاقتك الائتمانية المنتهية أرقامها بـ 5678 في 24/11/2026 عبر الخدمات المصرفية عبر الإنترنت. الحد المتوفر لك AED 10,000.00.", "1250.50", "10000.00"),
            Triple("تم استلام AED 75.00 كدفعة لصالح بطاقتك الائتمانية المنتهية أرقامها بـ 4321 في 24/11/2026 عبر الخدمات المصرفية عبر الإنترنت.", "75.00", ""),
        )) {
            val r = read("Emirates NBD", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals(TxnType.PAYMENT, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency)
            assertEquals(4, r.txn.cardLast4?.length)
            assertEquals(id, r.ruleId)
        }
    }

    @Test fun lookAlikesAreNotReadByTheRule() {
        // another bank with the same words: the pack rule belongs to Emirates NBD only
        val other = read("Unknown Test Bank", "تمّ استلام AED 500.00 كدفعة لصالح بطاقتك الائتمانية المنتهية أرقامها بـ 1234 في 24/11/2026 عبر الخدمات المصرفية عبر الإنترنت. الحد المتوفر لك AED 7,250.00.")
        assertTrue(other !is ParseResult.Transaction || other.ruleId != id, "read by the rule: $other")
        // a debit-card transfer notice of the same bank is not read by the payment rule
        val debit = read("Emirates NBD", "لقد تمّ تحويل مبلغ AED 100.00 باستخدام بطاقة الخصم الخاصة بك والمنتهية أرقامها بـ 1234 إلى MERCHANT في 24/11/2026.")
        assertTrue(debit !is ParseResult.Transaction || debit.ruleId != id, "read by the rule: $debit")
        // a similar sentence without a card payment (no amount): not read
        val none = read("Emirates NBD", "تمّ استلام طلبك لبطاقتك الائتمانية المنتهية أرقامها بـ 1234 في 24/11/2026 عبر الخدمات المصرفية عبر الإنترنت.")
        assertTrue(none !is ParseResult.Transaction || none.ruleId != id, "read by the rule: $none")
    }

    @Test fun packCountsAfterV32() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 32)
        assertTrue(out.pack.rules.size >= 41)
        assertTrue(out.pack.ignore.size >= 57)
        assertTrue(out.pack.rules.any { it.rule.id == id })
        assertTrue(out.pack.rules.any { it.rule.id == "mashreq-finance-charges-reversed-20261125" })
    }

    // ---- 25 Nov 2026, 19:23 slot additions (same v32 bump): 1 reading rule + 3 ignore entries ----

    private val mashreqId = "pack:mashreq-finance-charges-reversed-20261125"

    @Test fun mashreqFinanceChargesReversedIsReadAsRefund() {
        // 1 loose-masked report (2.8, pv=31). Month only in the wording, no day: the received date is used.
        for ((body, amount, card) in listOf(
            Triple("In line with Mashreq's commitment to fair treatment of customers and prevailing regulations, Mar'26 finance charges of AED 50.00 have been reversed to your credit card ending 1234. No further action is required from your end.", "50.00", "1234"),
            Triple("In line with Mashreq's commitment to fair treatment of customers and prevailing regulations, Oct'26 finance charges of AED 1,250.50 have been reversed to your credit card ending 5678. No further action is required from your end.", "1250.50", "5678"),
        )) {
            val r = read("Mashreq", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals(TxnType.REFUND, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency)
            assertEquals(card, r.txn.cardLast4)
            assertEquals(mashreqId, r.ruleId)
        }
    }

    @Test fun mashreqFinanceChargesLookAlikesAreNotReadByTheRule() {
        val charged = read("Mashreq", "Mar'26 finance charges of AED 50.00 have been charged to your credit card ending 1234. Please pay the minimum due.")
        assertTrue(charged !is ParseResult.Transaction || charged.ruleId != mashreqId, "read by the rule: $charged")
        val other = read("Unknown Test Bank", "Mar'26 finance charges of AED 50.00 have been reversed to your credit card ending 1234. No further action is required from your end.")
        assertTrue(other !is ParseResult.Transaction || other.ruleId != mashreqId, "read by the rule: $other")
    }

    private fun assertIgnoredWith(label: String, r: ParseResult) {
        assertIs<ParseResult.Ignored>(r, "got $r")
        assertEquals(label, r.reason)
    }

    private fun assertNotIgnoredWith(label: String, r: ParseResult) {
        assertTrue(r !is ParseResult.Ignored || r.reason != label, "hidden by $label: $r")
    }

    @Test fun personalLoanReadyAdvertIsIgnoredButRealCreditsAreNot() {
        val label = "Advert: Personal Loan ready (confirm)"
        assertIgnoredWith(label, read("Unknown Test Bank", "Dear Customer, Rs 50,000 Personal Loan ready. Confirm to receive in your bank A/C - https://example.test/x"))
        assertIgnoredWith(label, read("Unknown Test Bank", "Rs 1,20,000 Personal Loan ready. Confirm to receive in your bank A/C - https://example.test/y"))
        assertNotIgnoredWith(label, read("Emirates NBD", "Your account ending 1234 has been credited with AED 5,000.00 on 25/11/2026. Available balance AED 9,000.00."))
        assertNotIgnoredWith(label, read("Unknown Test Bank", "AED 500.00 was credited to your bank A/C 1234 from Personal Loan disbursal on 25/11/2026."))
    }

    @Test fun deemPreauthHoldIsIgnoredButThePurchaseIsNot() {
        val label = "Notice: Deem preauth hold"
        assertIgnoredWith(label, read("Deem", "Thank you for using your Deem Credit card ending in 1234 for preauth transaction with an amount of AED 100.00 at DU QUICK RECHARGE on 25/11/2026. Your available balance is AED 1,000.00."))
        assertIgnoredWith(label, read("Deem", "Thank you for using your Deem Credit card ending in 5678 for preauth transaction with an amount of AED 32.50 at Noon Food on 25/11/2026. Your available balance is AED 2,000.00."))
        val purchase = read("Deem", "Thank you for using your Deem Credit Card ending in 1234 for AED 100.00 at DU QUICK RECHARGE on 25/11/2026. Your available balance is AED 1,000.00.")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals(TxnType.PURCHASE, purchase.txn.type)
        assertNotIgnoredWith(label, purchase)
        assertNotIgnoredWith(label, read("Deem", "Thank you for using your Deem Credit card ending in 1234 for AED 40.00 at PREAUTH CAFE on 25/11/2026. Your available balance is AED 1,000.00."))
    }

    @Test fun digitalRupeeAdvertIsIgnoredButPaymentsAreNot() {
        val label = "Advert: Digital Rupee PIN-less pay"
        assertIgnoredWith(label, read("Unknown Test Bank", "Tap. Scan. Pay faster. with PIN less payments up to Rs 5,000. Use Digital Rupee by ABC Bank."))
        assertIgnoredWith(label, read("Unknown Test Bank", "Tap. Scan. Pay faster. with PIN less payments up to AED 500. Use Digital Rupee by XYZ Bank."))
        assertNotIgnoredWith(label, read("Emirates NBD", "Purchase of AED 75.00 at DIGITAL RUPEE STORE, DUBAI with card ending 1234. Avl Cr. Limit AED 5,000.00"))
    }
}
