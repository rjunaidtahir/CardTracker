package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Rules file update of 2026-11-19 (version 6): reading rules for banks the app already knows, written from the real masked
 * shapes in the Fils shapes Sheet (18-19 Nov 2026), plus five notice ignores. The JSON below is the exact content of
 * rules/pack.json; each new rule is tried on a message built from its shape.
 */
class WeeklyRules20261119Test {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 19, 12, 31).toEpochMillis(zone)
    private val packJson = """{"version":6,"minEngine":5,"banks":[{"name":"Tabby","country":"AE","aliases":["Tabby Card"],"senderIds":["Tabby"],"track":true},{"name":"ADCB","country":"AE","aliases":["SimplyLife"],"senderIds":["SIMPLYLIFE"],"track":true},{"name":"Tamara","country":"AE","aliases":["Tamara Pay Later"]},{"name":"Postpay","country":"AE"},{"name":"Spotii","country":"AE"},{"name":"Cashew","country":"AE"},{"name":"CBD Now","country":"AE","aliases":["Commercial Bank of Dubai"]},{"name":"ADCB Hayyak","country":"AE","aliases":["Hayyak"]},{"name":"E20","country":"AE","aliases":["Emirates Islamic E20"]},{"name":"Reem Community Bank","country":"AE"},{"name":"YAP","country":"AE"},{"name":"Xpence","country":"AE"}],"rules":[{"bank":"Mashreq","id":"mashreq-thank-you-debit-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Debit Card(?: Card)? ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"Mashreq","id":"mashreq-thank-you-purchase-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Card ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"HDFC Bank","id":"hdfc-aed-card-purchase","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thanks for\\s+\\w+\\s+HDFC Bank\\s+[^.]{0,40}?Card\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+Card Bal:?\\s*{ANYCUR}\\s*[\\d,.]+)?"},{"bank":"Emirates NBD","id":"enbd-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Liv","id":"liv-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"ADIB","id":"adib-trx-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Trx\\.? of\\s+{CUR}\\s*{AMOUNT}\\s+on your card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+is approved\\.?(?:\\s+Avl\\.?\\s*card bal(?:ance)? is\\s+[\\d,.]+\\.?)?(?:\\s+Trx\\.? date:?\\s*{DATETIME})?"},{"bank":"Sharjah Islamic Bank","id":"sib-outward-remit","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"A/C\\s+{CARD}\\s+outward remit\\s+{CUR}\\s*{AMOUNT}\\.?(?:\\s+Available balance is\\s+{ANYCUR}\\s*\\+?{AVAIL})?","fixedMerchant":"Outward remittance"},{"bank":"Sharjah Islamic Bank","id":"sib-card-txn-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"A txn on your card\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+on\\s+{DATETIME}(?:\\s*at\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s+is approved\\.?(?:\\s+Your available balance is\\s+[\\d,.]+)?"},{"bank":"CBD","id":"cbd-credit-card-payment","kind":"TRANSACTION","type":"PAYMENT","cardType":"CREDIT","pattern":"thank you for your credit card payment via funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+for card ending\\s+{CARD}(?:\\s+on\\s+{DATETIME})?","fixedMerchant":"Card payment received"},{"bank":"ADCB","id":"adcb-debit-card-refund","kind":"TRANSACTION","type":"REFUND","cardType":"DEBIT","pattern":"{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.\\s*The amount will be credited"},{"bank":"CBD","id":"cbd-card-debited","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"A transaction of\\s+{CUR}\\s*{AMOUNT}\\s+has been debited from your\\s+[^.]{0,40}?\\s*{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Sharjah Islamic Bank","id":"sib-card-purchase-lines","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"(?:{DATETIME}\\s*)?Card\\s*:\\s*{CARD}\\s+Purchase\\s+(?:\\w+\\s+)?(?:\\w+\\s+)?of\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s*$"},{"bank":"ADCB","id":"adcb-cheque-debit","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"Cheque no\\.?\\s*\\d+\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+\\w+\\s+on\\s+{DATETIME}\\s+for debit from\\s+[^.]{0,20}\\.?\\s*no\\.?\\s*{CARD}\\.?(?:\\s+Avl Bal\\.?\\s*{ANYCUR}\\s*{AVAIL})?","fixedMerchant":"Cheque"},{"bank":"Axis Bank","id":"axis-debit-alert","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"Debit\\s+{CUR}\\s*{AMOUNT}\\s+A/c\\s+(?:no\\.?\\s*)?{CARD}\\s+{DATETIME}\\s*{MERCHANT}\\s+Bal-"},{"bank":"Emirates NBD","id":"enbd-debit-card-funds-transfer-b","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Liv","id":"liv-debit-card-funds-transfer-b","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"ADCB","id":"adcb-debit-card-refund-b","kind":"TRANSACTION","type":"REFUND","cardType":"DEBIT","pattern":"{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.?\\s*The amount will be credited"},{"bank":"Wio","id":"wio-card-payment","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"card payment\\.\\s*{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+(?:\\w+\\s+)?your Wio\\b"}],"ignore":[{"label":"Declined: insufficient balance","pattern":"cannot be processed due to insufficient balance"},{"label":"Notice: transfer limit raised","pattern":"is pleased to .{1,120} in your account\\. For .{1,30} call .{1,20} or visit your .{1,30} branch"},{"label":"Notice: IBFT limit advert","pattern":"now .{1,30} IBFT transactions up to [A-Z]{3} [\\d,.]+/-"},{"label":"Advert: instant credit branches","pattern":"branches for instant credit to your account"},{"label":"Telecom bill reminder","pattern":"Total payable are [A-Z]{3} [\\d,.]+"},{"label":"Telecom bill due notice","pattern":"Your total bill for .{1,40} is [A-Z]{3} [\\d,.]+, Please pay by"},{"label":"Failed payment notice","pattern":"Your payment of [A-Z]{3} [\\d,.]+ is failed"},{"label":"UPI payment request","pattern":"has requested .{1,40}? from you on your .{1,30}? UPI\\."},{"label":"Payee added notice","pattern":"Payee .{1,60}? added\\. Transfer funds after"},{"label":"Card spend promo","pattern":"spend is on us!"},{"label":"Currency rate alert","pattern":"we have a new update for your currency .{1,20}? alert"},{"label":"Future-dated payment set up","pattern":"you have .{1,10}? up a .{1,15}? dated (?:payment|transfer) through online/mobile banking"},{"label":"Credit card approved notice","pattern":"your FAB credit card has been approved with a limit of"},{"label":"Card purchase initiated (one-time code)","pattern":"has been initiated\\. Please log in to your Banking App"},{"label":"Notice: balance converted to instalments","pattern":"Balance Conversion of\\s+[A-Z]{3}\\s+[\\d,.]+\\s+on Credit Card ending"},{"label":"Card offer with code","pattern":"\\bUse Code:\\s*\\w+"},{"label":"Debit card offer with code","pattern":"the code ADCB\\d+"},{"label":"Notice: transfer limit changed","pattern":"Transfer limit is now .{1,30} to [A-Z]{3} [\\d,.]+"}]}"""

    @BeforeTest fun load() {
        val out = RulesPack.parse(packJson, 5)
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun check(bank: String, ruleId: String, body: String, card: String?, amount: String, merchant: String?,
                      type: TxnType, cardType: CardType) {
        val r = read(bank, body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        assertEquals("pack:$ruleId", r.ruleId, body)
        val t = r.txn
        assertEquals(type, t.type, body)
        assertEquals(cardType, t.cardType, body)
        assertEquals(card, t.cardLast4, body)
        assertEquals(Decimal(amount), t.amount, body)
        if (merchant != null) assertTrue(t.merchant.startsWith(merchant, ignoreCase = true), "merchant '${t.merchant}' for: $body")
    }

    private fun ignored(body: String, label: String) {
        val r = read("Test Bank", body)
        assertIs<ParseResult.Ignored>(r, label)
        assertEquals(label, r.reason)
    }

    @Test fun the_file_is_accepted() {
        val out = RulesPack.parse(packJson, 5)
        assertIs<RulesPack.Outcome.Ok>(out)
        assertEquals(6, out.pack.version)
        assertEquals(18, out.pack.rules.size)
        assertEquals(18, out.pack.ignore.size)
    }

    @Test fun cbd_card_debited() {
        check("CBD", "cbd-card-debited", "A transaction of AED 45.50 has been debited from your Credit Card 1234 at AMAZON*XY123. Your balance is AED 4,200.00. Please refer to monthly statement for total amount.", "1234", "45.50", "AMAZON*XY123", TxnType.PURCHASE, CardType.CREDIT)
        check("CBD", "cbd-card-debited", "A transaction of AED 12.00 has been debited from your Credit Card 5678 at CAREEM *RIDE HOLD. Your balance is AED 900.10. Please refer to monthly statement for total amount.", "5678", "12.00", "CAREEM", TxnType.PURCHASE, CardType.CREDIT)
    }

    @Test fun sib_card_purchase_lines() {
        check("Sharjah Islamic Bank", "sib-card-purchase-lines", "15/11/2026 12:30Card :5412341234 Purchase Approved of AED 45.50 at NOON.COM Abu Dhabi ARE", "1234", "45.50", "NOON.COM", TxnType.PURCHASE, CardType.DEBIT)
        check("Sharjah Islamic Bank", "sib-card-purchase-lines", "15-11-2026 12:30:10 Card :XXXX1234 Purchase of AED 8.00 at SPINNEYS", "1234", "8.00", "SPINNEYS", TxnType.PURCHASE, CardType.DEBIT)
    }

    @Test fun adcb_cheque_debit() {
        check("ADCB", "adcb-cheque-debit", "Cheque no. 000123 for AED 2,500.00 cleared on 15/11/2026 10:30 for debit from A/c.no. XX1234. Avl Bal. AED 9,000.00.", "1234", "2500.00", "Cheque", TxnType.TRANSFER_OUT, CardType.ACCOUNT)
    }

    @Test fun axis_debit_alert() {
        check("Axis Bank", "axis-debit-alert", "Debit AED 150.00 A/c XX1234 15-11-26, 12:30:10 IMPS /P2A/512345678901/JOHN SMITH Bal- AED 900.00 Not you? SMS BLOCK to 919951860002- Axis Bank", "1234", "150.00", "IMPS", TxnType.TRANSFER_OUT, CardType.ACCOUNT)
    }

    @Test fun enbd_and_liv_funds_transfer_without_the_full_stop() {
        check("Emirates NBD", "enbd-debit-card-funds-transfer-b", "Funds Transfer of AED 45.50 has been made using your Debit Card ending 1234 at AMAZON.AE Your current balance is AED 1,000.00.", "1234", "45.50", "AMAZON.AE", TxnType.PURCHASE, CardType.DEBIT)
        check("Liv", "liv-debit-card-funds-transfer-b", "Funds Transfer of AED 20.00 has been made on your Debit Card ending 1234 at AL ANSARI Exchange Your current balance is AED 80.00.", "1234", "20.00", "AL ANSARI", TxnType.PURCHASE, CardType.DEBIT)
    }

    @Test fun adcb_debit_card_refund_variant() {
        check("ADCB", "adcb-debit-card-refund-b", "AED 44.99 has been refunded to your Debit Card 1234 on 15/11/2026 12:30by AMAZON.AE The amount will be credited to your account within 3-5 working days", "1234", "44.99", "AMAZON.AE", TxnType.REFUND, CardType.DEBIT)
    }

    @Test fun wio_card_payment() {
        check("Wio", "wio-card-payment", "\u2705 Card payment. AED 12.50 at CARREFOUR on your Wio Personal card 1234 with AED funds", null, "12.50", "CARREFOUR", TxnType.PURCHASE, CardType.DEBIT)
    }

    @Test fun new_ignore_entries_hide_only_their_notices() {
        ignored("Your online transaction of AED 45.50 at NOON on 15/11/2026 12:30 on your card ending 1234 has been initiated. Please log in to your Banking App to enter your One Time Code and complete the transaction.", "Card purchase initiated (one-time code)")
        ignored("Dear Customer, your Balance Conversion of AED 1,200.00 on Credit Card ending 1234 has been processed for 6 months at 1.50 % Ref # 12345A678.", "Notice: balance converted to instalments")
        ignored("Your offer of AED 500 is valid for shopping on your HDFC Credit Card 1234 in Nov'26. Use Code: ABC123 to redeem it: example.com till 30-Nov-2026. Questions? Call: helpline.", "Card offer with code")
        ignored("Use your ADCB Mastercard Debit Card and get 10% cashback on the Talabat app using the code ADCB10. Spend of AED 50.00. example.com", "Debit card offer with code")
        ignored("Alert! Your HDFC Bank Fund Transfer limit is now increased to AED 20,000.00. Limit set by Customer ID 1234 Not you?Call 800123", "Notice: transfer limit changed")
    }

    @Test fun ordinary_purchases_are_not_hidden_by_the_new_ignore_entries() {
        for (body in listOf(
            "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00",
            "AED 100.00 debited from your account XXXX1234 on 12/10/2026",
            "Dear Customer, You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026",
        )) assertTrue(read("Test Bank", body) !is ParseResult.Ignored, body)
    }
}
