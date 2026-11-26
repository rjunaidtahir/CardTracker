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
 * 26 Nov 2026 (03:23 slot, second part). Pack v34: Dubai Islamic Bank "has been drawn from A/C No. ... as <reason>" (fees, bill
 * payments, outward / mobile-banking transfers; ext rules with the acctToken lever) and the SAB credit-card refund
 * "إسترداد مبلغ" (legacy rule). Wordings invented from masked loose-masked shapes (2.8/e8 and 2.7/e7 reports).
 * Own-account transfers, loan payments and direct debits of the same DIB shape are NOT read (owner policy / words masked).
 */
class ShippedPackV34Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L
    private val dib = "Dubai Islamic Bank"
    private val tail = ". Available balance is 24,000.00"

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.extRejected == null, "ext rejected: ${out.pack.extRejected}")
        assertTrue(out.pack.version >= 34)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)
    private fun drawn(amount: String, reason: String) = "AED $amount has been drawn from A/C No. 123-456789XXXX-01 as $reason$tail"

    private fun txn(bank: String, body: String, id: String): com.uaefinancial.tracker.parser.ParsedTransaction {
        val r = read(bank, body)
        assertIs<ParseResult.Transaction>(r, "got $r for $body")
        assertEquals("pack:$id", r.ruleId, "got $r")
        return r.txn
    }

    @Test fun dibFeesAreReadAsBankFees() {
        for ((reason, amount) in listOf(
            "PROCESSING FEES" to "26.25", "CASH MGMT. SERVICE CHARGES" to "5.25",
            "OUTWARD UAE FUNDS TRF CHGS IPI" to "12.60", "CHEQUE BOOK ISSUANCE CHARGES" to "262.50",
        )) {
            val t = txn(dib, drawn(amount, reason), "dib-drawn-fees-20261126")
            assertEquals(TxnType.PURCHASE, t.type); assertEquals(amount, t.amount.toPlainString()); assertEquals("AED", t.currency)
            assertEquals("89XXXX01", t.cardLast4); assertEquals("Bank fees", t.merchant)
        }
    }

    @Test fun dibBillPaymentsAreReadWithTheBillerAsMerchant() {
        val a = txn(dib, drawn("310.00", "ETISALAT PAYMENT"), "dib-drawn-bill-payment-20261126")
        assertEquals(TxnType.PURCHASE, a.type); assertEquals("310.00", a.amount.toPlainString()); assertEquals("89XXXX01", a.cardLast4)
        assertTrue(a.merchant.equals("ETISALAT", ignoreCase = true), "merchant was ${a.merchant}")
        val b = txn(dib, drawn("98.40", "SEWA PAYMENT"), "dib-drawn-bill-payment-20261126")
        assertTrue(b.merchant.equals("SEWA", ignoreCase = true), "merchant was ${b.merchant}")
    }

    @Test fun dibOutwardAndMobileBankingTransfersAreTransfersOut() {
        for ((reason, amount) in listOf("MB FUND TRANSFER DEBIT" to "1500.00", "OUTWARD UAE FUNDS TRANS IPI" to "3000.00")) {
            val t = txn(dib, drawn(amount, reason), "dib-drawn-outward-transfer-20261126")
            assertEquals(TxnType.TRANSFER_OUT, t.type); assertEquals(amount, t.amount.toPlainString()); assertEquals("89XXXX01", t.cardLast4)
        }
    }

    @Test fun dibLookAlikesAreNotReadByTheNewRules() {
        val ids = setOf("dib-drawn-fees-20261126", "dib-drawn-bill-payment-20261126", "dib-drawn-outward-transfer-20261126").map { "pack:$it" }
        for (body in listOf(
            drawn("1,000.00", "OWN ACCOUNT TRNSFER"),            // own-account transfer: owner policy
            drawn("2,345.00", "PYMT OF FINANCE INSTALMENT"),     // loan payment: not covered
            drawn("400.00", "OWN ACCOUNT PAYMENT"),
            drawn("400.00", "MB FUND TRANSFER CREDIT"),
            drawn("26.25", "PYMT OF SCHOOL FEES"),
            "AED 400.00 has been credited to A/C No. 123-456789XXXX-01 as SEWA PAYMENT$tail", // money in: never read by these rules
        )) {
            val r = read(dib, body)
            assertTrue(r !is ParseResult.Transaction || r.ruleId !in ids, "read by a new rule: $r for $body")
        }
        // the same words from another bank: the rules belong to Dubai Islamic Bank
        val other = read("Unknown Test Bank", drawn("26.25", "PROCESSING FEES"))
        assertTrue(other !is ParseResult.Transaction || other.ruleId !in ids, "read by a new rule: $other")
    }

    @Test fun sabCreditCardRefundIsReadAsRefund() {
        val id = "pack:sab-card-refund-20261126"
        val a = read("SAB", "بطاقة إئتمانية: إسترداد مبلغ بطاقة: VISA رقم: 4321 من: Amazon SA مبلغ: SAR 45.00 رصيد: SAR 1,200.00 في: 24/11/2026 14:30")
        assertIs<ParseResult.Transaction>(a, "got $a")
        assertEquals(id, a.ruleId); assertEquals(TxnType.REFUND, a.txn.type); assertEquals("45.00", a.txn.amount.toPlainString())
        assertEquals("SAR", a.txn.currency); assertEquals("4321", a.txn.cardLast4); assertEquals("Amazon SA", a.txn.merchant)
        val b = read("SAB", "بطاقة إئتمانية: إسترداد مبلغ بطاقة: MASTER رقم: 8765 من: www.example.com مبلغ: SAR 1,045.50 رصيد: SAR 12,200.00 في: 24/11/26 09:05")
        assertIs<ParseResult.Transaction>(b, "got $b")
        assertEquals(id, b.ruleId); assertEquals("1045.50", b.txn.amount.toPlainString()); assertEquals("8765", b.txn.cardLast4)
    }

    @Test fun sabLookAlikesAreNotReadByTheRefundRule() {
        val id = "pack:sab-card-refund-20261126"
        // a card payment confirmation and a purchase from the same bank: not refunds
        for (body in listOf(
            "بطاقة إئتمانية: تأكيد السداد بطاقة: 4321;VISA مبلغ: SAR 500.00 رصيد: SAR 1,200.00 في: 24/11/2026 14:30",
            "بطاقة إئتمانية: شراء بطاقة: VISA رقم: 4321 من: Amazon SA مبلغ: SAR 45.00 رصيد: SAR 1,200.00 في: 24/11/2026 14:30",
        )) {
            val r = read("SAB", body)
            assertTrue(r !is ParseResult.Transaction || r.ruleId != id, "read by the refund rule: $r")
        }
        val other = read("Unknown Test Bank", "بطاقة إئتمانية: إسترداد مبلغ بطاقة: VISA رقم: 4321 من: Amazon SA مبلغ: SAR 45.00 رصيد: SAR 1,200.00 في: 24/11/2026 14:30")
        assertTrue(other !is ParseResult.Transaction || other.ruleId != id, "read by the refund rule: $other")
    }
}
