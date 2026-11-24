package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.ignore
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.read
import com.uaefinancial.tracker.parser.P28.rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Section 10 of the 2.8 spec: the groups the loop will fix after release, each proven with the generic capabilities only
 * (rules file entries, no app code). Every wording is invented from masked shapes; the real entries are added by the loop.
 */
class CapabilityByBankTest {
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.setHomeCurrency("AED") }

    private val all = levers("acctToken", "curAbbrev", "phrases", "notMoney", "unless", "wordingBanks", "pairs")

    private fun txn(r: ParseResult, id: String): ParsedTransaction {
        assertIs<ParseResult.Transaction>(r, "expected $id, got $r")
        assertEquals("pack:$id", r.ruleId, "got $r")
        return r.txn
    }

    @Test fun dibDrawnFromAccountByReason() {
        val dib = "Dubai Islamic Bank"
        val head = """{CUR} {AMOUNT} has been drawn from A/C No\. {ACCT} as """
        val rules = listOf(
            rule(dib, "dib-own", head + "OWN ACCOUNT TRANSFER", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Own account transfer\",\"pairGroup\":\"dib-own\""),
            rule(dib, "dib-fees", head + "(?:PROCESSING FEES|CASH .{0,30}SERVICE CHARGES|CHEQUE .{0,30}CHARGES)", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Bank fees\""),
            rule(dib, "dib-loan", head + "PYMT OF", type = "PAYMENT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Loan payment\",\"direction\":\"OUT\""),
            rule(dib, "dib-out", head + "OUTWARD UAE FUNDS", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Outward transfer\""),
        ).joinToString(",")
        val bank = ",\"banks\":[{\"name\":\"Dubai Islamic Bank\",\"country\":\"AE\",\"senderIds\":[\"DIB-SMS\"],\"track\":true}]"
        apply(file(ext = "$all,\"rules\":[$rules]$bank"))
        val tail = ". Available balance is AED 4,000.00"
        val own = txn(read(dib, "AED 1,000.00 has been drawn from A/C No. 123-456789XXXX-01 as OWN ACCOUNT TRANSFER$tail"), "dib-own")
        assertEquals(TxnType.TRANSFER_OUT, own.type); assertEquals("89XXXX01", own.cardLast4)
        assertEquals("Bank fees", txn(read(dib, "AED 26.25 has been drawn from A/C No. 123-456789XXXX-01 as PROCESSING FEES$tail"), "dib-fees").merchant)
        assertEquals("Bank fees", txn(read(dib, "AED 5.25 has been drawn from A/C No. 123-456789XXXX-01 as CASH WITHDRAWAL SERVICE CHARGES$tail"), "dib-fees").merchant)
        assertEquals(TxnType.PAYMENT, txn(read(dib, "AED 2,345.00 has been drawn from A/C No. 123-456789XXXX-01 as PYMT OF FINANCE INSTALMENT$tail"), "dib-loan").type)
        assertEquals(TxnType.TRANSFER_OUT, txn(read(dib, "AED 3,000.00 has been drawn from A/C No. 123-456789XXXX-01 as OUTWARD UAE FUNDS TRANSFER$tail"), "dib-out").type)
        // DIB-SMS becomes a tracked sender of Dubai Islamic Bank (no more "Other (DIB-SMS)")
        assertEquals("Dubai Islamic Bank", SmsParser.bankFor("DIB-SMS")?.name)
    }

    @Test fun hsbcGlobalMoneyAndTtWithOneAccount() {
        val rules = listOf(
            rule("HSBC", "hsbc-tt", """TT Payment from {ACCT}\s*-?\s*{CUR} {AMOUNT}""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"TT payment\""),
            rule("HSBC", "hsbc-gma", """Global Money Account: {CUR} {AMOUNT} sent to {MERCHANT}\. Your available balance""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"accountNotNamed\":true"),
        ).joinToString(",")
        apply(file(ext = "$all,\"rules\":[$rules]"))
        val tt = txn(read("HSBC", "TT Payment from 123456***-AED 100.00 2,500.00- Your available balance is AED 2,400.00"), "hsbc-tt")
        assertEquals(Decimal("100.00"), tt.amount, "the transaction amount, never the balance")
        val gma = txn(read("HSBC", "Global Money Account: USD 250.00 sent to JOHN SMITH. Your available balance is USD 1,000.00"), "hsbc-gma")
        assertTrue(gma.accountNotNamed); assertEquals(null, gma.cardLast4)
    }

    @Test fun sabSibAlHilalKotakIdfcRakbankAndRemittances() {
        SmsParser.setHomeCurrency("INR")
        val rules = listOf(
            rule("SABB", "sab-pos", """Purchase {CUR} {AMOUNT} card {CARD} at {MERCHANT} on""", cardType = "DEBIT"),
            rule("Sharjah Islamic Bank", "sib-within", """Within UAE Funds Transfer of {CUR} {AMOUNT} from account {ACCT} to {MERCHANT}\.""", type = "TRANSFER_OUT", cardType = "ACCOUNT"),
            rule("Al Hilal", "alhilal-own", """Own account transfer of {CUR} {AMOUNT} from {ACCT} to {TO}""", type = "TRANSFER_OUT", cardType = "ACCOUNT"),
            rule("Kotak Mahindra Bank", "kotak-upi", """Sent {CUR} {AMOUNT} from Kotak Bank AC {ACCT} to {MERCHANT} on""", type = "TRANSFER_OUT", cardType = "ACCOUNT"),
            rule("IDFC FIRST Bank", "idfc-debit", """Your A/C {ACCT} is debited by {CUR} {AMOUNT} for {MERCHANT}\.""", cardType = "ACCOUNT"),
            rule("RAKBANK", "rak-utility", """Utility payment of {CUR} {AMOUNT} to {MERCHANT} from account {ACCT} is successful""", cardType = "ACCOUNT"),
            rule("HDFC Bank", "hdfc-reload", """Prepaid card {TO} reloaded with {CUR} {AMOUNT} from A/c {ACCT}""", type = "TRANSFER_OUT", cardType = "ACCOUNT"),
            rule("Emirates NBD", "enbd-remit", """Remittance of {CUR} {AMOUNT} to {MERCHANT} from account {ACCT} has been remitted""", type = "TRANSFER_OUT", cardType = "ACCOUNT"),
        ).joinToString(",")
        apply(file(ext = "$all,\"rules\":[$rules]"))
        assertEquals("SAR", txn(read("SABB", "Purchase SAR 45.50 card 3001 at COFFEE HOUSE on 05/11/26"), "sab-pos").currency)
        assertEquals(TxnType.TRANSFER_OUT, txn(read("Sharjah Islamic Bank", "Within UAE Funds Transfer of AED 700.00 from account XXXX1234 to SOME PAYEE. Ref 9988"), "sib-within").type)
        assertEquals("Transfer to ·9876", txn(read("Al Hilal", "Own account transfer of AED 50.00 from XXXX1234 to XXXX9876"), "alhilal-own").merchant)
        assertEquals("INR", txn(read("Kotak Mahindra Bank", "Sent Rs. 250.00 from Kotak Bank AC X4321 to someone@okbank on 03-11-26"), "kotak-upi").currency)
        assertEquals(Decimal("1200.00"), txn(read("IDFC FIRST Bank", "Your A/C XXXXXXX4321 is debited by INR 1,200.00 for BILL PAY."), "idfc-debit").amount)
        assertEquals("DEWA", txn(read("RAKBANK", "Utility payment of AED 310.00 to DEWA from account XXXX1234 is successful"), "rak-utility").merchant)
        val reload = txn(read("HDFC Bank", "Prepaid card XX5566 reloaded with Rs. 2,000.00 from A/c XX4321"), "hdfc-reload")
        assertEquals(TxnType.TRANSFER_OUT, reload.type, "a card reload is a transfer, never spending"); assertEquals("5566", reload.toLast4)
        assertEquals(TxnType.TRANSFER_OUT, txn(read("Emirates NBD", "Remittance of AED 900.00 to FAMILY NAME from account XXXX1234 has been remitted"), "enbd-remit").type)
    }

    @Test fun noticesAreHiddenUnlessTheyCarryAStatement() {
        val ig = listOf(
            ignore("Overdue notice", """\bpayment is overdue\b""", ",\"unless\":\"\\\\bminimum (?:amount )?due\\\\b\""),
            ignore("Over-limit notice", """\bover (?:your|the) credit limit\b"""),
        ).joinToString(",")
        val st = rule("Emirates NBD", "enbd-overdue-stmt", """payment is overdue\. Total due {CUR} {TOTAL}, minimum due {ANYCUR} {MIN} by {DUE}""", kind = "STATEMENT", cardType = "CREDIT")
        apply(file(ext = "$all,\"ignore\":[$ig],\"rules\":[$st]"))
        assertIs<ParseResult.Ignored>(read("Mashreq", "Your card payment is overdue. Please pay now to avoid charges."))
        assertIs<ParseResult.Ignored>(read("Mashreq", "You are over your credit limit on card ending 1234. Please pay AED 100.00 to restore it."))
        assertIs<ParseResult.Statement>(read("Emirates NBD", "Your card 1234 payment is overdue. Total due AED 5,000.00, minimum due AED 250.00 by 10/12/2026"))
    }

    @Test fun tabbyHidesPlansAndReadsOnlyCompletedPayments() {
        val ig = listOf(
            ignore("Tabby order confirmed", """\border (?:is )?confirmed\b"""),
            ignore("Tabby due tomorrow", """\bis due tomorrow\b"""),
            ignore("Tabby will be charged", """\bwill be charged\b"""),
            ignore("Tabby overdue", """\b(?:is overdue|collection)\b"""),
        ).joinToString(",")
        val r = rule("Tabby", "tabby-paid", """We received your payment of {CUR} {AMOUNT} for {MERCHANT} with card {CARD}""", type = "PURCHASE", cardType = "CREDIT", extra = ",\"direction\":\"OUT\"")
        apply(file(ext = "$all,\"ignore\":[$ig],\"rules\":[$r]"))
        for (b in listOf(
            "Your order is confirmed: AED 400.00 split in 4 payments.",
            "Your payment of AED 100.00 is due tomorrow.",
            "AED 100.00 will be charged to your card ending 1234 on 05/12.",
            "Your payment of AED 100.00 is overdue.",
        )) assertIs<ParseResult.Ignored>(read("Tabby", b), b)
        assertEquals(Decimal("100.00"), txn(read("Tabby", "We received your payment of AED 100.00 for SHOP NAME with card 1234. Thanks!"), "tabby-paid").amount)
    }

    @Test fun walletAndEandMoneyMoneyInAndOut() {
        val bank = "\"banks\":[{\"name\":\"e& money\",\"country\":\"AE\",\"senderIds\":[\"EANDMONEY\"],\"track\":true}]"
        val ig = ignore("Wallet reward", """\b(?:cashback reward|points (?:will )?expire|share the love)\b""")
        val rules = listOf(
            rule("e& money", "eand-in", """Good news! {CUR} {AMOUNT} from {MERCHANT} in your account""", type = "TRANSFER_IN", cardType = "ACCOUNT", extra = ",\"accountNotNamed\":true"),
            rule("e& money", "eand-out", """You sent {CUR} {AMOUNT} to {MERCHANT} from your account""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"accountNotNamed\":true"),
        ).joinToString(",")
        apply(file(ext = "$all,$bank,\"ignore\":[$ig],\"rules\":[$rules]"))
        val inn = txn(SmsParser.parse("EANDMONEY", "Good news! AED 150.00 from A FRIEND in your account. Check your new balance in the app.", P28.AT), "eand-in")
        assertEquals(TxnType.TRANSFER_IN, inn.type); assertTrue(inn.accountNotNamed)
        assertEquals(TxnType.TRANSFER_OUT, txn(SmsParser.parse("EANDMONEY", "You sent AED 20.00 to A FRIEND from your account.", P28.AT), "eand-out").type)
        assertIs<ParseResult.Ignored>(SmsParser.parse("EANDMONEY", "You earned AED 5.00 cashback reward! Share the love with friends.", P28.AT))
    }

    @Test fun wioIpoSubscriptionIsReadByARuleWithoutDisablingTheIgnoreForEveryone() {
        val rules = listOf(
            rule("Wio", "wio-ipo", """{CUR} {AMOUNT} has been debited from your account {ACCT} for IPO subscription""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"IPO subscription\""),
            rule("Wio", "wio-ipo-refund", """{CUR} {AMOUNT} IPO refund has been credited to your account {ACCT}""", type = "TRANSFER_IN", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"IPO refund\""),
        ).joinToString(",")
        apply(file(ext = "$all,\"rules\":[$rules]"))
        assertEquals(TxnType.TRANSFER_OUT, txn(read("Wio", "AED 10,000.00 has been debited from your account XXXX1234 for IPO subscription of SOME CO"), "wio-ipo").type)
        assertEquals(TxnType.TRANSFER_IN, txn(read("Wio", "AED 6,000.00 IPO refund has been credited to your account XXXX1234"), "wio-ipo-refund").type)
        // the built-in label exists (the alternative lever: disableIgnore "IPO subscription")
        assertTrue(BankRules.globalIgnore.any { it.label == "IPO subscription" })
        assertIs<ParseResult.Ignored>(read("Mashreq", "Apply for the SOME CO IPO now in the app"))
    }

    @Test fun cbdTapToApproveIsHiddenButTheDebitIsRead() {
        assertIs<ParseResult.Ignored>(read("CBD", "Tap to approve your transfer of AED 300.00 in the CBD app"))
        val debit = read("CBD", "AED 300.00 has been debited from your account XXXX1234 for transfer. Avl bal AED 700.00")
        assertNotEquals(true, debit is ParseResult.Ignored, "$debit")
    }

    @Test fun alfalahFeeCardStaysAsReadByTheGuard() {
        // The fee card (250 + tax 40 = 290) is a reading change that ReadingGuard holds; no pack lever applies it automatically.
        assertFalse(RulesPack.levers.on(RulesPack.Lever.KEY_CHANGE))
    }
}
