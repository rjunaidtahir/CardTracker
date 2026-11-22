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
 * v17 (22 Nov 2026 loop run): two reading rules (ADCB debit card "linked to acc ... was used for", which the global
 * "lounge" ignore hid; Mashreq Everyday Cashback credit) and four narrow ignore entries. Every wording below is
 * invented from the masked shape (placeholders filled with realistic values); look-alikes that must NOT change are
 * checked next to each one.
 */
class ShippedPackV17Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 17)
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

    @Test fun adcbDebitCardPurchaseAtLoungeIsReadNotHidden() {
        for ((merchant, amount) in listOf("SHISHA LOUNGE C,AE" to "45.50", "THE LOUNGE LLC,AE" to "120.00", "KANA LOUNGE DUBAI AE " to "30.00")) {
            val body = "Your debit card 1234 linked to acc. 123456789012 was used for AED $amount on 21/11/2026 20:15:33 at $merchant. Avl.Bal AED 1,234.56."
            val r = read("ADCB", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:adcb-debit-card-linked-used-20261122", r.ruleId)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(CardType.DEBIT, r.txn.cardType)
            assertEquals("1234", r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertTrue(r.txn.merchant.contains("LOUNGE", ignoreCase = true), r.txn.merchant)
        }
    }

    @Test fun adcbLoungeLookAlikesStayAsTheyWere() {
        // not a purchase wording: no "was used for ... at ... Avl.Bal" -> the new rule must not read it
        val notice = "Enjoy complimentary airport lounge access with your ADCB card. T&C apply."
        val r = read("ADCB", notice)
        assertTrue(r !is ParseResult.Transaction, "got $r")
        // a credit card wording keeps its own built-in rule, not the new one
        val cc = read("ADCB", "Your Cr.Card XXX3538 was used for AED59.50 on 18/08/2026 14:06:42 at talabat.com,DUBAI-AE. Avl. Cr.limit is AED5897.94")
        if (cc is ParseResult.Transaction) assertNotEquals("pack:adcb-debit-card-linked-used-20261122", cc.ruleId)
    }

    @Test fun mashreqEverydayCashbackCreditIsReadAsCashbackRefund() {
        val body = "Congrats, AED 12.50 has been credited to your Credit Card ending with XX1234 as part of Everyday Cashback offers. View cashback statement for more https://x.y/z"
        val r = read("Mashreq", body)
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("pack:mashreq-everyday-cashback-credit-20261122", r.ruleId)
        assertEquals(TxnType.REFUND, r.txn.type)
        assertEquals(CardType.CREDIT, r.txn.cardType)
        assertEquals("1234", r.txn.cardLast4)
        assertEquals(Decimal("12.50"), r.txn.amount)
        assertEquals("Cashback", r.txn.merchant)
    }

    @Test fun mashreqCashbackLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "Congrats, AED 12.50 has been credited to your Credit Card ending with XX1234 as Welcome Bonus. Login to Mashreq Mobile App for details.",
            "AED 500.00 has been credited to your Credit Card ending 1234 by payment received",
        )) {
            val r = read("Mashreq", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:mashreq-everyday-cashback-credit-20261122", r.ruleId, body)
        }
    }

    // ---- ignore entries -------------------------------------------------------------------------------------------

    @Test fun hdfcThirdPartyLimitNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: HDFC third-party limit")
        assertTrue(re.containsMatchIn("Alert! Your HDFC Bank Third Party Transfer limit is now set to Rs. 50,000.00. Limit applicable per Customer ID per Day. Not you?Call 18002586161"))
        assertTrue(!re.containsMatchIn("Rs 5,000.00 debited from HDFC Bank A/c XX1234 on 21-11-26 to Third Party Transfer. Avl bal Rs 9,000"))
        assertTrue(!re.containsMatchIn("Your HDFC Bank Third Party Transfer of Rs 5,000.00 to A/c XX9876 is successful"))
    }

    @Test fun upiCollectThroughUpiIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Request: UPI collect (through UPI)")
        assertTrue(re.containsMatchIn("Swiggy has requested Rs 250.00 from you through UPI. To authorize debit from your account please login to your UPI App. To pay instantly click here https://x.y"))
        assertTrue(re.containsMatchIn("IndianClearingCorporation has requested Rs 50.00 from you through UPI. To authorize debit from your account please login to your UPI App.-PNB"))
        assertTrue(!re.containsMatchIn("Rs 250.00 debited from your HDFC Bank A/c XX1234 to SWIGGY through UPI. UPI Ref 123456789012"))
        assertTrue(!re.containsMatchIn("Rs 250.00 paid to Swiggy through UPI. Ref 12345"))
    }

    @Test fun fabEasyPaymentPlanClosedNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: FAB Easy Payment Plan closed")
        assertTrue(re.containsMatchIn("Dear Customer, your request 1234567890123 dated 12/11/2026 for an Easy Payment Plan for 6 months with a 1.5% interest rate and an AED 99.00 processing fee was approved and closed on 13/11/2026. For more information, please call 600525500."))
        assertTrue(!re.containsMatchIn("Your FAB credit card purchase of AED 500.00 at CARREFOUR was converted to an Easy Payment Plan for 6 months. A processing fee of AED 99.00 was debited"))
        assertTrue(!re.containsMatchIn("Purchase of AED 99.00 at FAB EASY PAYMENT PLAN on card ending 1234 was approved"))
    }

    @Test fun rakbankCashbackApprovedNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: RAKBANK cashback approved")
        assertTrue(re.containsMatchIn("Hello. Just to let you know, your cashback redemption request SRM-12345678901 of AED 50.00 is approved and will be credited to your card account shortly."))
        assertTrue(!re.containsMatchIn("Your cashback of AED 50.00 has been credited to your card account."))
        assertTrue(!re.containsMatchIn("Your payment of AED 100.00 is approved and will be debited from your card account"))
    }

    @Test fun packCountsAfterV17() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        // two reading rules and four ignore entries added to the 22 rules and 32 ignores of v15/v16
        assertTrue(out.pack.rules.size >= 24)
        assertTrue(out.pack.ignore.size >= 36)
    }
}
