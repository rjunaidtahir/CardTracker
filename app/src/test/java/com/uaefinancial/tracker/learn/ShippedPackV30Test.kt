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
 * v30 (25 Nov 2026 loop run, 11:23 slot): three reading rules, two ignore entries, no words, no new banks.
 *  - Deem card purchase worded "... Credit Card ending in N online for AED x at SHOP on DATE" (7 loose-masked reports, 2.8).
 *  - SAB (Saudi Awwal Bank) "Funds Transfer Accepted From: A To: NAME IBAN: B Bank Amount: x Fees: y On/Date: d" (11 loose-masked
 *    reports, 2.7) read as a transfer out of the account; SAB Arabic card reversal "حوالة عكسية تم عكس المبلغ x على بطاقتك ... (N) لدى: SHOP
 *    تاريخ: d" (8 loose-masked reports) read as a refund.
 *  - Ignores: SAB Arabic one-time password for an online purchase (about 30 reports), Mashreq "e-com transaction ... was rejected ...
 *    trusted device" (3 reports). Each has a look-alike test that a real transaction is not hidden.
 * Wordings are invented from the masked shapes (the exact real date style is unknown; tested with and without the time).
 */
class ShippedPackV30Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 30)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun assertIgnoredBy(label: String, r: ParseResult) {
        assertIs<ParseResult.Ignored>(r, "got $r")
        assertEquals(label, r.reason)
    }

    private fun assertNotIgnoredBy(label: String, r: ParseResult) {
        assertTrue(r !is ParseResult.Ignored || r.reason != label, "hidden by $label: $r")
    }

    private fun assertNotReadBy(prefix: String, r: ParseResult) {
        assertTrue(r !is ParseResult.Transaction || !r.ruleId.startsWith(prefix), "read by $prefix: $r")
    }

    @Test fun deemOnlinePurchaseIsRead() {
        for ((body, amount, merchant) in listOf(
            Triple("Thank you for using your Deem Credit Card ending in 1234 online for AED 45.50 at TABBY on 24/11/2026 14:30. Your available balance is AED 4,954.50.", "45.50", "TABBY"),
            Triple("Thank you for using your Deem Credit Card ending in 7788 online for AED 1,250.00 at NOON FOOD on 24/11/2026. Your available balance is AED 3,750.00.", "1250.00", "NOON FOOD"),
        )) {
            val r = read("Deem", body)
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("AED", r.txn.currency)
            assertEquals(merchant, r.txn.merchant)
            assertEquals(4, r.txn.cardLast4?.length)
            assertTrue(r.ruleId.startsWith("pack:deem-"), "rule ${r.ruleId}")
        }
    }

    @Test fun deemLookAlikesAreNotReadByTheOnlineRule() {
        // a pre-authorisation hold is not a finished purchase: not read by the new rule
        val preauth = read("Deem", "Thank you for using your Deem Credit card ending in 1234 for preauth transaction with an amount of AED 10.00 at Noon Food on 24/11/2026. Your available balance is AED 100.00.")
        assertNotReadBy("pack:deem-credit-card-online", preauth)
        val payment = read("Deem", "Your Deem Credit Card ending in 1234 payment of AED 500.00 has been received online. Thank you.")
        assertNotReadBy("pack:deem-credit-card-online", payment)
        val other = read("Unknown Test Bank", "Thank you for using your Deem Credit Card ending in 1234 online for AED 45.50 at TABBY on 24/11/2026. Your available balance is AED 4,954.50.")
        assertNotReadBy("pack:deem-", other)
        // the earlier Deem rule still reads the plain wording
        val plain = read("Deem", "Thank you for using your Deem Credit Card ending in 1234 for AED 45.50 at NOON FOOD on 24/11/2026. Your available balance is AED 4,954.50.")
        assertIs<ParseResult.Transaction>(plain, "got $plain")
        assertEquals("pack:deem-credit-card-purchase-20261125", plain.ruleId)
    }

    @Test fun sabFundsTransferAcceptedIsReadAsTransferOut() {
        for ((body, amount) in listOf(
            "Funds Transfer Accepted From: 1234 To: AHMED ALI IBAN: 5678 Riyad Bank Amount: SAR 1,500.00 Fees: SAR 5.00 On: 24/11/2026 14:30" to "1500.00",
            "Fund Transfer Accepted From: 1234 To: JOHN SMITH IBAN: 9876 Alinma bank Amount: SAR 200.00 Fees: SAR 0.00 Date: 24/11/2026 14:30" to "200.00",
            "Funds Transfer Accepted From: 1234 To: MARIA GARCIA IBAN: 4321 Saudi Awwal Bank Amount: SAR 75.25 Fees: SAR 1.00 Date: 24/11/2026 14:30Ref: 12345678SASABBSABB1BM" to "75.25",
        )) {
            val r = read("SAB", body)
            assertIs<ParseResult.Transaction>(r, "got $r for $body")
            assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
            assertEquals(amount, r.txn.amount.toPlainString())
            assertEquals("SAR", r.txn.currency)
            assertEquals("1234", r.txn.cardLast4)
            assertTrue(!r.txn.merchant.isNullOrBlank(), "merchant missing: $r")
            assertTrue(r.ruleId.startsWith("pack:sab-funds-transfer"), "rule ${r.ruleId}")
        }
    }

    @Test fun sabTransferLookAlikesAreNotRead() {
        val rejected = read("SAB", "Funds Transfer Rejected From: 1234 To: AHMED ALI IBAN: 5678 Riyad Bank Amount: SAR 1,500.00 Fees: SAR 5.00 On: 24/11/2026 14:30")
        assertNotReadBy("pack:sab-funds-transfer", rejected)
        val other = read("Unknown Test Bank", "Funds Transfer Accepted From: 1234 To: AHMED ALI IBAN: 5678 Riyad Bank Amount: SAR 1,500.00 Fees: SAR 5.00 On: 24/11/2026 14:30")
        assertNotReadBy("pack:sab-", other)
    }

    @Test fun sabArabicReversalIsReadAsRefund() {
        for (body in listOf(
            "حوالة عكسية تم عكس المبلغ SAR 150.00 على بطاقتك Mastercard Alfursan الائتمانية (1234) لدى: SAUDI AIRLINES Je تاريخ: 24/11/2026 14:30 الرصيد: SAR 5,000.00",
            "حوالة عكسية تم عكس المبلغ SAR 150.00 على بطاقتك Mastercard Alfursan الائتمانية (1234) لدى: SAUDI AIRLINES Je تاريخ: 24/11/2026 14:30الرصيد: SAR 5,000.00",
        )) {
            val r = read("SAB", body)
            assertIs<ParseResult.Transaction>(r, "got $r")
            assertEquals(TxnType.REFUND, r.txn.type)
            assertEquals("150.00", r.txn.amount.toPlainString())
            assertEquals("SAR", r.txn.currency)
            assertEquals("1234", r.txn.cardLast4)
            assertTrue(r.txn.merchant?.startsWith("SAUDI AIRLINES") == true, "merchant ${r.txn.merchant}")
            assertEquals("pack:sab-reversal-to-card-refund-20261125", r.ruleId)
        }
    }

    @Test fun sabArabicBillPaymentIsNotReadAsARefund() {
        val bill = read("SAB", "سداد فاتورة من: 1234; VISA مبلغ: SAR 100.00 الجهة: Saudi Telecom مفوتر: 1234567890; Saudi Telecom رقم الفاتورة: 1234567890 رصيد: SAR 900.00 في: 24/11/2026 14:30")
        assertNotReadBy("pack:sab-reversal", bill)
    }

    @Test fun sabArabicOtpIsHiddenButARealMessageIsNot() {
        val label = "Notice: SAB one-time password"
        assertIgnoredBy(label, read("SAB", "كلمة مرور صالحة لمرة واحدة لـتأكيد العملية الشرائية عبر الإنترنت لبطاقة الأول Mastercard Alfursan الائتمانية (1234) رمز: 5678 المبلغ: SAR 100.00 لدى: NOON E COMMERCE تاريخ: 24/11/2026 14:30"))
        assertIgnoredBy(label, read("SAB", "كلمة مرور صالحة لمرة واحدة رمز: 5678 لـ: تأكيد العملية الشرائية عبر الإنترنت المبلغ: SAR 100.00 لدى: AIR ARABIA تاريخ: 24/11/2026 14:30"))
        assertNotIgnoredBy(label, read("SAB", "حوالة عكسية تم عكس المبلغ SAR 150.00 على بطاقتك Mastercard Alfursan الائتمانية (1234) لدى: SAUDI AIRLINES Je تاريخ: 24/11/2026 14:30 الرصيد: SAR 5,000.00"))
        assertNotIgnoredBy(label, read("SAB", "سداد فاتورة من: 1234; VISA مبلغ: SAR 100.00 الجهة: Saudi Telecom مفوتر: 1234567890 رقم الفاتورة: 1234567890 رصيد: SAR 900.00 في: 24/11/2026 14:30"))
        assertNotIgnoredBy(label, read("SAB", "Online Purchase By: 1234;mada From: 567 Amount: SAR 100.00 At: ATS TRAVEL Date: 24/11/2026 14:30"))
    }

    @Test fun mashreqRejectedEcomIsHiddenButAnApprovedOneIsNot() {
        val label = "Declined: Mashreq e-com untrusted device"
        assertIgnoredBy(label, read("Mashreq", "An e-com transaction of AED 100.00 was rejected on your card ending 1234 on 24/11/2026 14:30 as your mobile is not registered as a trusted device via Mashreq Mobile. To enable transactions, register your device here: https://www.example.com"))
        assertNotIgnoredBy(label, read("Mashreq", "An e-com transaction of AED 100.00 was approved on your card ending 1234 on 24/11/2026 14:30 via Mashreq Mobile."))
        val purchase = read("Mashreq", "Thank you for using your Credit Card ending 1234 for AED 45.50 at NOON on 24-11-2026 14:30. Available limit AED 5,000.00")
        assertIs<ParseResult.Transaction>(purchase, "got $purchase")
        assertEquals("45.50", purchase.txn.amount.toPlainString())
    }

    @Test fun packCountsAfterV30() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 30)
        assertTrue(out.pack.rules.size >= 39)
        assertTrue(out.pack.ignore.size >= 53)
        for (id in listOf("deem-credit-card-online-20261125", "sab-funds-transfer-accepted-20261125", "sab-reversal-to-card-refund-20261125"))
            assertTrue(out.pack.rules.any { it.rule.id == "pack:$id" }, id)
    }
}
