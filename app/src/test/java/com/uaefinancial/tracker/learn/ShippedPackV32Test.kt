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
        assertTrue(out.pack.rules.size >= 40)
        assertTrue(out.pack.ignore.size >= 54)
        assertTrue(out.pack.rules.any { it.rule.id == id })
    }
}
