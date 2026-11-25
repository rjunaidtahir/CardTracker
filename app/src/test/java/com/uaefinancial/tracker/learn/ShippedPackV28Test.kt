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
 * v28 (25 Nov 2026 loop run, 07:23 slot): one bank (Deem, sender DEEM), one reading rule, four ignore entries, no words.
 *  - Deem credit card purchase "Thank you for using your Deem Credit Card ending in N for AED x at SHOP on DATE. Your available
 *    balance is AED y." (6 loose-masked reports from 2.8, plus 21 strict-masked older ones). The shape reads amount, currency,
 *    direction, card, merchant and date; look-alike notices stay unread.
 *  - Ignores (each from at least 2 separate reports, wordings invented from the masked shapes):
 *    DIB "your request for ... balance conversion of AED x has been successfully processed on your ... Credit Card" (4 reports),
 *    "balance in your account N is AED x. Recharge via" (4 reports, Salik), Amazon "Refund ...: Amount AED x (item) will be
 *    credited to your Visa within 3-5 days" (9 reports), du "Your account has been credited successfully. Your new balance is
 *    AED x. Stay connected with the best deals" (9 reports). Each has a look-alike test that a real transaction is not hidden.
 */
class ShippedPackV28Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 28)
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

    @Test fun deemCardPurchaseIsRead() {
        for ((body, amount, merchant) in listOf(
            Triple("Thank you for using your Deem Credit Card ending in 1234 for AED 45.50 at NOON FOOD on 24/11/2026. Your available balance is AED 4,954.50.", "45.50", "NOON FOOD"),
            Triple("Thank you for using your Deem Credit Card ending in 7788 for AED 1,250.00 at TAWASUL TRANSPORT LLC on 24/11/2026. Your available balance is AED 3,750.00.", "1250.00", "TAWASUL TRANSPORT LLC"),
            Triple("Thank you for using your Deem Credit Card ending in 4821 for AED 12.00 at AL FUTTAIM STATIONERY on 24-Nov-2026. Your available balance is AED 100.25.", "12.00", "AL FUTTAIM STATIONERY"),
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
        // the chat with the sender ID DEEM belongs to the bank
        assertEquals("Deem", SmsParser.bankFor("DEEM")?.name)
        val viaSender = SmsParser.parse("DEEM", "Thank you for using your Deem Credit Card ending in 1234 for AED 45.50 at NOON FOOD on 24/11/2026. Your available balance is AED 4,954.50.", received, zone)
        assertIs<ParseResult.Transaction>(viaSender, "got $viaSender")
        assertEquals("45.50", viaSender.txn.amount.toPlainString())
    }

    @Test fun deemLookAlikesAreNotReadByTheRule() {
        val ad = read("Deem", "Thank you for choosing Deem Finance. Apply for a Credit Card today and enjoy zero annual fee. Terms apply.")
        assertTrue(ad !is ParseResult.Transaction, "advert read as money: $ad")
        val payment = read("Deem", "Your Deem Credit Card ending in 1234 payment of AED 500.00 has been received. Thank you.")
        assertTrue(payment !is ParseResult.Transaction || !payment.ruleId.startsWith("pack:deem-credit-card-purchase"), "payment read by the purchase rule: $payment")
        // the rule belongs to Deem only
        val other = read("Unknown Test Bank", "Thank you for using your Deem Credit Card ending in 1234 for AED 45.50 at NOON FOOD on 24/11/2026. Your available balance is AED 4,954.50.")
        assertTrue(other !is ParseResult.Transaction || !other.ruleId.startsWith("pack:deem-"), "other bank read by the Deem rule: $other")
    }

    @Test fun dibBalanceConversionIsHiddenButACardDebitIsNot() {
        val label = "Notice: DIB balance conversion"
        for (body in listOf(
            "Dear Customer, your request for Smart balance conversion of AED 5,000.00 has been successfully processed on your Visa Credit Card 4111111111111234 on 24/11/2026. Your monthly instalment is AED 450.00 for a total tenure of 12 months.",
            "Dear Customer, your request for balance conversion of AED 12,500.00 has been successfully processed on your Platinum Credit Card 1234 on 24/11/2026 10:30. Your monthly instalment is AED 1,100.00 for a total tenure of 12 months.",
        )) assertIgnoredBy(label, read("Dubai Islamic Bank", body))
        assertNotIgnoredBy(label, read("Dubai Islamic Bank", "Dear Customer, your request for Personal Finance of AED 5,000.00 has been successfully processed on your account."))
        val debit = read("Dubai Islamic Bank", "Dear Customer, Your Credit Card ending 1234 has been debited with AED 100.00 at NOON on 24/11/2026. Available limit AED 5,000.00.")
        assertIs<ParseResult.Transaction>(debit, "got $debit")
        assertEquals("100.00", debit.txn.amount.toPlainString())
    }

    @Test fun balanceWithRechargeLinkIsHiddenButTollAndDebitsAreNot() {
        val label = "Notice: account balance, recharge link"
        assertIgnoredBy(label, read("Other (SALIK)", "The remaining balance in your account 12345678 is AED 25.00. Recharge via: https://www.example.com"))
        assertIgnoredBy(label, read("Other (SALIK)", "The balance in your account 12345678 is AED 8.50. Recharge via: https://www.example.com"))
        assertNotIgnoredBy(label, read("Other (SALIK)", "Your Salik account 12345678 has been charged at Al Barsha gate. The new balance is AED 20.00. Thank You."))
        assertNotIgnoredBy(label, read("Other (SALIK)", "Your account 12345678 was recharged, your new balance is AED 120.00."))
        val debit = read("Dubai Islamic Bank", "Your account 12345678 has been debited with AED 50.00 on 24/11/2026. Available balance in your account 12345678 is AED 1,000.00.")
        assertIs<ParseResult.Transaction>(debit, "got $debit")
        assertEquals("50.00", debit.txn.amount.toPlainString())
    }

    @Test fun amazonRefundNoticeIsHiddenButARefundCreditIsNot() {
        val label = "Notice: Amazon refund will be credited"
        assertIgnoredBy(label, read("Other (AMAZON)", "Refund Issued: Amount AED 45.00 (Universal Shaver, 12 pcs...) will be credited to your Visa within 3-5 business days. Details https://www.example.com"))
        assertIgnoredBy(label, read("Other (AMAZON)", "Refund initiated: Amount AED 132.50 (Phone case 12 inch Black 1...) will be credited to your Master Card within 5-7 days https://www.example.com"))
        assertNotIgnoredBy(label, read("Other (AMAZON)", "Refund processed: AED 45.00 has been credited to your Visa ending 1234."))
        val bankRefund = read("ADCB", "AED 45.00 has been refunded to your debit card 1234 on 24/11/2026 by NOON. The amount will be credited to your account within 5 days.")
        assertIs<ParseResult.Transaction>(bankRefund, "got $bankRefund")
        assertEquals(TxnType.REFUND, bankRefund.txn.type)
        assertEquals("45.00", bankRefund.txn.amount.toPlainString())
    }

    @Test fun duBalanceAfterCreditIsHiddenButABankCreditIsNot() {
        val label = "Notice: du balance after credit"
        assertIgnoredBy(label, read("Other (DU.)", "Your account has been credited successfully. Your new balance is AED 55.00. Stay connected with the best deals on voice and data. Dial *135# or *999# to subscribe now."))
        assertNotIgnoredBy(label, read("Unknown Test Bank", "Your account has been credited successfully. Your new balance is AED 55.00. Thank you for banking with us."))
        val credit = read("Unknown Test Bank", "Your account 1234567 has been credited with AED 500.00 on 24/11/2026. Available balance AED 1,500.00.")
        assertIs<ParseResult.Transaction>(credit, "got $credit")
        assertEquals(TxnType.TRANSFER_IN, credit.txn.type)
        assertEquals("500.00", credit.txn.amount.toPlainString())
    }

    @Test fun existingReadingsAreUnchanged() {
        val m = read("Mashreq", "Thank you for using your Credit Card ending 1234 for AED 45.50 at NOON on 24-11-2026 14:30. Available limit AED 5,000.00")
        assertIs<ParseResult.Transaction>(m, "got $m")
        assertEquals("45.50", m.txn.amount.toPlainString())
        assertEquals("mashreq-thank-you-purchase", m.ruleId)
    }

    @Test fun packCountsAfterV28() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 28)
        assertTrue(out.pack.rules.size >= 36)
        assertTrue(out.pack.ignore.size >= 51)
        assertTrue(out.pack.rules.any { it.rule.id == "pack:deem-credit-card-purchase-20261125" })
        assertTrue(out.pack.banks.any { it.name == "Deem" && it.senderIds.contains("DEEM") })
    }
}
