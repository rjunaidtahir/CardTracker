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
 * v18 (22 Nov 2026 loop run, 18:00): four reading rules for real transactions that the app hid (Mashreq cashback credit,
 * HDFC AutoPay purchase, HDFC credit-card spend at a "lounge" merchant, PNB UPI debit) and four narrow ignore entries.
 * Every wording below is invented from the masked shape; look-alikes that must NOT change are checked next to each one.
 */
class ShippedPackV18Test {
    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val zone = SmsParser.UAE_ZONE
    private val received = 1_795_000_000_000L

    @BeforeTest fun load() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 18)
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

    @Test fun mashreqCashbackCreditedForSpendsIsReadAsCashbackRefund() {
        for ((month, amount, card) in listOf(Triple("Jul'26", "12.50", "1234"), Triple("Sept'26", "7.00", "XX4321"))) {
            val body = "Cashback of AED $amount is credited to your Credit Card ending $card for $month e-commerce spends and will reflect in your next statement. T&C: https://x.y/z"
            val r = read("Mashreq", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:mashreq-cashback-credited-spends-20261122", r.ruleId)
            assertEquals(TxnType.REFUND, r.txn.type)
            assertEquals(CardType.CREDIT, r.txn.cardType)
            assertEquals(card.takeLast(4), r.txn.cardLast4)
            assertEquals(Decimal(amount), r.txn.amount)
            assertEquals("Cashback", r.txn.merchant)
        }
    }

    @Test fun mashreqCashbackLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "Cashback of AED 5.00 is credited to your Credit Card ending 1234 as a welcome offer.",
            "Earn cashback of AED 50.00 on e-commerce spends with your Credit Card ending 1234. T&C apply.",
        )) {
            val r = read("Mashreq", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:mashreq-cashback-credited-spends-20261122", r.ruleId, body)
        }
    }

    @Test fun hdfcAutopayEmandateSuccessIsReadAsCreditCardPurchase() {
        for (body in listOf(
            "AutoPay (E-mandate) Successful! For NETFLIX Current Txn Amt: AED 49.00 Date:21-11-2026 Via: E-mandate on HDFC Bank Credit Card 1234 SI Hub ID: ABC123 Manage: https://x.y TnC",
            "AutoPay (E-mandate) Success! For NETFLIX Txn Amt:AED 49.00 Dt:21-11-2026 Via:HDFC Bank CC 1234 SI Hub ID: ABC123 TnC",
            "AutoPay (E-mandate) Success! For SPOTIFY INDIA .. Txn Amt:AED 49.00 Dt:21-11-2026 Via:HDFC Bank CC 1234 SI Hub ID: ABC123 TnC",
        )) {
            val r = read("HDFC Bank", body)
            assertIs<ParseResult.Transaction>(r, "got $r for: $body")
            assertEquals("pack:hdfc-autopay-emandate-success-20261122", r.ruleId)
            assertEquals(TxnType.PURCHASE, r.txn.type)
            assertEquals(CardType.CREDIT, r.txn.cardType)
            assertEquals("1234", r.txn.cardLast4)
            assertEquals(Decimal("49.00"), r.txn.amount)
            assertTrue(r.txn.merchant.startsWith("NETFLIX") || r.txn.merchant.startsWith("SPOTIFY"), r.txn.merchant)
            assertTrue(!r.txn.merchant.contains(".."), r.txn.merchant)
        }
    }

    @Test fun hdfcAutopayLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "Autopay Failed: AED 49.00 on 21-11-2026 via HDFC Bank Card 1234 for NETFLIX ID:ABC123 Manage:https://x.y",
            "Alert: AED 49.00 will be debited on 22-11-2026 from HDFC Bank Card 1234 for NETFLIX ID:ABC123 Act:https://x.y",
            "Set up AutoPay (E-mandate) on your HDFC Bank Credit Card 1234 for NETFLIX and enjoy rewards. TnC apply",
        )) {
            val r = read("HDFC Bank", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:hdfc-autopay-emandate-success-20261122", r.ruleId, body)
        }
    }

    @Test fun hdfcCreditCardSpendAtLoungeMerchantIsReadNotHidden() {
        val body = "You've spent AED 45.00 On HDFC Bank CREDIT Card 1234 At BLR DOMESTIC LOUNGE On 21-11-2026:14:22:10Avl bal: AED 9,000.50 Curr O/s: AED 1,000.00 Not you?Call 8002586161"
        val r = read("HDFC Bank", body)
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("pack:hdfc-credit-card-spent-on-at-20261122", r.ruleId)
        assertEquals(TxnType.PURCHASE, r.txn.type)
        assertEquals(CardType.CREDIT, r.txn.cardType)
        assertEquals("1234", r.txn.cardLast4)
        assertEquals(Decimal("45.00"), r.txn.amount)
        assertEquals("BLR DOMESTIC LOUNGE", r.txn.merchant)
    }

    @Test fun hdfcSpendLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "Enjoy free airport lounge access when you've spent AED 5,000.00 On HDFC Bank CREDIT Card in a quarter. T&C apply.",
            "Your HDFC Bank Credit Card 1234 limit is now AED 20,000.00. You've spent AED 45.00 this month.",
        )) {
            val r = read("HDFC Bank", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:hdfc-credit-card-spent-on-at-20261122", r.ruleId, body)
        }
    }

    @Test fun pnbAccountDebitedThruUpiIsReadAsUpiDebit() {
        val body = "A/c XXXXXXXXXX1234 has been debited with INR 250.00 on 21-11-2026 14:22:10thru UPI. Helpline 18001802222.Register for e-statement,if not done-PNB"
        val r = read("Punjab National Bank", body)
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("pack:pnb-account-debited-thru-upi-20261122", r.ruleId)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type)
        assertEquals(CardType.ACCOUNT, r.txn.cardType)
        assertEquals("1234", r.txn.cardLast4)
        assertEquals(Decimal("250.00"), r.txn.amount)
        assertEquals("UPI payment", r.txn.merchant)
    }

    @Test fun pnbLookAlikesDoNotUseTheNewRule() {
        for (body in listOf(
            "A/c XXXXXXXXXX1234 has been credited with INR 250.00 on 21-11-2026 14:22:10thru UPI. Helpline 18001802222.-PNB",
            "Your A/c XXXXXXXXXX1234 will be debited with INR 250.00 on 22-11-2026 thru UPI Autopay.-PNB",
        )) {
            val r = read("Punjab National Bank", body)
            if (r is ParseResult.Transaction) assertNotEquals("pack:pnb-account-debited-thru-upi-20261122", r.ruleId, body)
        }
    }

    // ---- ignore entries -------------------------------------------------------------------------------------------

    @Test fun hdfcAutopayFailedNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Failed: HDFC autopay notice")
        assertTrue(re.containsMatchIn("Autopay Failed: AED 49.00 on 21-11-2026 via HDFC Bank Card 1234 for NETFLIX ID:ABC123 Manage:https://x.y"))
        assertTrue(re.containsMatchIn("Autopay Failed: Rs.199.00 on 21-11-2026 via HDFC Bank Card 1234 for Some Services Ltd ID:ABC123 Manage:https://x.y"))
        assertTrue(!re.containsMatchIn("AutoPay (E-mandate) Success! For NETFLIX Txn Amt:AED 49.00 Dt:21-11-2026 Via:HDFC Bank CC 1234 SI Hub ID: ABC123 TnC"))
        assertTrue(!re.containsMatchIn("Rs.199.00 debited via HDFC Bank Card 1234 on 21-11-2026 for NETFLIX"))
    }

    @Test fun hdfcCardTransactionFailedNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Failed: HDFC card transaction notice")
        assertTrue(re.containsMatchIn("Transaction failed! For AED 199.00 To NETFLIX On HDFC Bank Credit Card 1234. Reason: Non compliant Recurring or e-Mandate. Use an alternate source to pay the bill."))
        assertTrue(!re.containsMatchIn("Transaction of AED 199.00 To NETFLIX On HDFC Bank Credit Card 1234 was successful. Avl bal AED 5,000.00"))
        assertTrue(!re.containsMatchIn("You've spent AED 199.00 On HDFC Bank CREDIT Card 1234 At NETFLIX On 21-11-2026:14:22:10Avl bal: AED 9,000.50"))
    }

    @Test fun pnbFailedAtmChargeNoticeIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Notice: PNB failed-ATM charge")
        assertTrue(re.containsMatchIn("Dear Customer, w.e.f. 01-12-2026, charge of Rs. 25.00+GST shall be levied on failed domestic ATM cash withdrawal transaction due to insufficient fund-PNB"))
        assertTrue(!re.containsMatchIn("A/c XXXXXXXXXX1234 has been debited with INR 25.00 on 01-12-2026 14:22:10 towards ATM charges. Helpline 18001802222.-PNB"))
        assertTrue(!re.containsMatchIn("INR 5,000.00 withdrawn from ATM using card 1234 on 01-12-2026. Avl bal INR 9,000.00"))
    }

    @Test fun livFeesChangedAdvertIsIgnoredAndLookAlikesAreNot() {
        val re = entry("Advert: Liv fees changed")
        assertTrue(re.containsMatchIn("Our fees have changed! Send money to India, Pakistan, Sri Lanka or Philippines for only AED 10.00, starting 01/12/2026. Enjoy great rates by sending money now."))
        assertTrue(!re.containsMatchIn("You sent money to India: AED 500.00 debited from your Liv account 1234 on 01/12/2026. Fee AED 10.00. Balance AED 1,000.00"))
        assertTrue(!re.containsMatchIn("Funds transfer of AED 10.00 has been made using your debit card ending 1234 at WESTERN UNION. Your current balance is AED 900.00"))
    }

    @Test fun packCountsAfterV18() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.rules.size >= 28)
        assertTrue(out.pack.ignore.size >= 40)
    }
}
