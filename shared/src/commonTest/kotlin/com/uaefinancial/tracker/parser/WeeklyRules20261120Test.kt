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
 * Rules file version 8 (daily accuracy report of 20 Nov 2026). The JSON below is the exact content of rules/pack.json.
 * Messages are built from the masked shapes in the Fils shapes Sheet with made-up values (no real data).
 * Two kinds of check: the new wordings are read with the right amount, card, merchant and type; and nothing that
 * was readable before changes (the v6 CBD rule, ordinary purchases never hidden by an ignore entry).
 */
class WeeklyRules20261120Test {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 19, 12, 31).toEpochMillis(zone)
    private val packJson = """{"version":8,"minEngine":5,"banks":[{"name":"Tabby","country":"AE","aliases":["Tabby Card"],"senderIds":["Tabby"],"track":true},{"name":"ADCB","country":"AE","aliases":["SimplyLife"],"senderIds":["SIMPLYLIFE"],"track":true},{"name":"Tamara","country":"AE","aliases":["Tamara Pay Later"]},{"name":"Postpay","country":"AE"},{"name":"Spotii","country":"AE"},{"name":"Cashew","country":"AE"},{"name":"CBD Now","country":"AE","aliases":["Commercial Bank of Dubai"]},{"name":"ADCB Hayyak","country":"AE","aliases":["Hayyak"]},{"name":"E20","country":"AE","aliases":["Emirates Islamic E20"]},{"name":"Reem Community Bank","country":"AE"},{"name":"YAP","country":"AE"},{"name":"Xpence","country":"AE"}],"rules":[{"bank":"Mashreq","id":"mashreq-thank-you-debit-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Debit Card(?: Card)? ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"Mashreq","id":"mashreq-thank-you-purchase-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Card ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"HDFC Bank","id":"hdfc-aed-card-purchase","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thanks for\\s+\\w+\\s+HDFC Bank\\s+[^.]{0,40}?Card\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+Card Bal:?\\s*{ANYCUR}\\s*[\\d,.]+)?"},{"bank":"Emirates NBD","id":"enbd-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Liv","id":"liv-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"ADIB","id":"adib-trx-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Trx\\.? of\\s+{CUR}\\s*{AMOUNT}\\s+on your card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+is approved\\.?(?:\\s+Avl\\.?\\s*card bal(?:ance)? is\\s+[\\d,.]+\\.?)?(?:\\s+Trx\\.? date:?\\s*{DATETIME})?"},{"bank":"Sharjah Islamic Bank","id":"sib-outward-remit","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"A/C\\s+{CARD}\\s+outward remit\\s+{CUR}\\s*{AMOUNT}\\.?(?:\\s+Available balance is\\s+{ANYCUR}\\s*\\+?{AVAIL})?","fixedMerchant":"Outward remittance"},{"bank":"Sharjah Islamic Bank","id":"sib-card-txn-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"A txn on your card\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+on\\s+{DATETIME}(?:\\s*at\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s+is approved\\.?(?:\\s+Your available balance is\\s+[\\d,.]+)?"},{"bank":"CBD","id":"cbd-credit-card-payment","kind":"TRANSACTION","type":"PAYMENT","cardType":"CREDIT","pattern":"thank you for your credit card payment via funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+for card ending\\s+{CARD}(?:\\s+on\\s+{DATETIME})?","fixedMerchant":"Card payment received"},{"bank":"ADCB","id":"adcb-debit-card-refund","kind":"TRANSACTION","type":"REFUND","cardType":"DEBIT","pattern":"{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.\\s*The amount will be credited"},{"bank":"CBD","id":"cbd-card-debited","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"A transaction of\\s+{CUR}\\s*{AMOUNT}\\s+has been debited from your\\s+[^.]{0,40}?\\s*{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Sharjah Islamic Bank","id":"sib-card-purchase-lines","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"(?:{DATETIME}\\s*)?Card\\s*:\\s*{CARD}\\s+Purchase\\s+(?:\\w+\\s+)?(?:\\w+\\s+)?of\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s*$"},{"bank":"ADCB","id":"adcb-cheque-debit","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"Cheque no\\.?\\s*\\d+\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+\\w+\\s+on\\s+{DATETIME}\\s+for debit from\\s+[^.]{0,20}\\.?\\s*no\\.?\\s*{CARD}\\.?(?:\\s+Avl Bal\\.?\\s*{ANYCUR}\\s*{AVAIL})?","fixedMerchant":"Cheque"},{"bank":"Axis Bank","id":"axis-debit-alert","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"Debit\\s+{CUR}\\s*{AMOUNT}\\s+A/c\\s+(?:no\\.?\\s*)?{CARD}\\s+{DATETIME}\\s*{MERCHANT}\\s+Bal-"},{"bank":"Emirates NBD","id":"enbd-debit-card-funds-transfer-b","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Liv","id":"liv-debit-card-funds-transfer-b","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.?\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"ADCB","id":"adcb-debit-card-refund-b","kind":"TRANSACTION","type":"REFUND","cardType":"DEBIT","pattern":"{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.?\\s*The amount will be credited"},{"bank":"Wio","id":"wio-card-payment","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"card payment\\.\\s*{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+(?:\\w+\\s+)?your Wio\\b"},{"bank":"CBD","id":"cbd-card-debited-c","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"A transaction of\\s+{CUR}\\s*{AMOUNT}\\s+(?:has been\\s+|was\\s+)?debited from your\\s+[^.]{0,40}?\\s*{CARD}\\s+(?:at\\s+)?{MERCHANT}\\.?\\s+Your balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Ajman Bank","id":"ajman-credit-card-spent","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"You have spent\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on your credit card ending with\\s+{CARD}\\s+on\\s+{DATETIME}\\.?(?:\\s+Available Limit:?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"Axis Bank","id":"axis-prepaid-recharge","kind":"TRANSACTION","type":"PURCHASE","cardType":"ACCOUNT","fixedMerchant":"Mobile recharge","pattern":"Recharge of\\s+{CUR}\\s*{AMOUNT}\\s+for\\s+[^.]{1,40}?Prepaid\\s+[\\d*X]+\\s+has been processed successfully"},{"bank":"ICICI Bank","id":"icici-card-reversal","kind":"TRANSACTION","type":"REFUND","cardType":"CREDIT","fixedMerchant":"Reversal","pattern":"Reversal of\\s+{CUR}\\s*{AMOUNT}\\s+credited to ICICI Bank Credit Card\\s+{CARD}\\s+on\\s+{DATETIME}"}],"ignore":[{"label":"Declined: insufficient balance","pattern":"cannot be processed due to insufficient balance"},{"label":"Notice: transfer limit raised","pattern":"is pleased to .{1,120} in your account\\. For .{1,30} call .{1,20} or visit your .{1,30} branch"},{"label":"Notice: IBFT limit advert","pattern":"now .{1,30} IBFT transactions up to [A-Z]{3} [\\d,.]+/-"},{"label":"Advert: instant credit branches","pattern":"branches for instant credit to your account"},{"label":"Telecom bill reminder","pattern":"Total payable are [A-Z]{3} [\\d,.]+"},{"label":"Telecom bill due notice","pattern":"Your total bill for .{1,40} is [A-Z]{3} [\\d,.]+, Please pay by"},{"label":"Failed payment notice","pattern":"Your payment of [A-Z]{3} [\\d,.]+ is failed"},{"label":"UPI payment request","pattern":"has requested .{1,40}? from you on your .{1,30}? UPI\\."},{"label":"Payee added notice","pattern":"Payee .{1,60}? added\\. Transfer funds after"},{"label":"Card spend promo","pattern":"spend is on us!"},{"label":"Currency rate alert","pattern":"we have a new update for your currency .{1,20}? alert"},{"label":"Future-dated payment set up","pattern":"you have .{1,10}? up a .{1,15}? dated (?:payment|transfer) through online/mobile banking"},{"label":"Credit card approved notice","pattern":"your FAB credit card has been approved with a limit of"},{"label":"Card purchase initiated (one-time code)","pattern":"has been initiated\\. Please log in to your Banking App"},{"label":"Notice: balance converted to instalments","pattern":"Balance Conversion of\\s+[A-Z]{3}\\s+[\\d,.]+\\s+on Credit Card ending"},{"label":"Card offer with code","pattern":"\\bUse Code:\\s*\\w+"},{"label":"Debit card offer with code","pattern":"the code ADCB\\d+"},{"label":"Notice: transfer limit changed","pattern":"Transfer limit is now .{1,30} to [A-Z]{3} [\\d,.]+"},{"label":"Notice: Tabby card limit increased","pattern":"Tabby Card total limit is now increased to [A-Z]{3}\\s*[\\d,.]+"},{"label":"Statement: nothing due (zero)","pattern":"credit balance\\s+(?:of\\s+)?[A-Z]{3}\\s*0(?:\\.0+)?\\s+with Min(?:imum)? Due\\s+[A-Z]{3}\\s*0(?:\\.0+)?(?![\\d.,]*[1-9])"},{"label":"Declined: card txn not processed","pattern":"\\btxn\\b.{1,30}\\bon card\\b.{1,120}was not processed"},{"label":"Reminder: ICICI total due","pattern":"Total Amount Due on ICICI Bank Credit Card\\s+\\S{1,12}\\s+is\\s+[A-Z]{3}"},{"label":"Reminder: Wio payment due today","pattern":"Your credit payment is due today\\. Statement amount"},{"label":"Reminder: Citibank minimum due","pattern":"(?:Pay minimum due of|a minimum due of)\\s+[A-Z]{3}\\s*[\\d,.]+"},{"label":"Reminder: RAKBANK minimum due","pattern":"pay the min due of\\s+[A-Z]{3}\\s*[\\d,.]+\\s+on your card"},{"label":"Reminder: Mashreq minimum payment","pattern":"credit card min payment of\\s+[A-Z]{3}\\s*[\\d,.]+\\s+is due"},{"label":"Notice: refund pending at merchant","pattern":"a credit refund of amount of\\s+[A-Z]{3}\\s*[\\d,.]+\\s+is notified by merchant"}]}"""

    @BeforeTest fun load() {
        val out = RulesPack.parse(packJson, 7)
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun check(bank: String, ruleId: String, body: String, card: String?, amount: String, merchant: String?,
                      type: TxnType, cardType: CardType, avail: String? = null) {
        val r = read(bank, body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        assertEquals("pack:$ruleId", r.ruleId, body)
        val t = r.txn
        assertEquals(type, t.type, body)
        assertEquals(cardType, t.cardType, body)
        assertEquals(card, t.cardLast4, body)
        assertEquals(Decimal(amount), t.amount, body)
        if (merchant != null) assertEquals(merchant.lowercase(), t.merchant.lowercase(), "merchant for: $body")
        if (avail != null) assertEquals(Decimal(avail), t.availableLimit, body)
    }

    private fun ignored(body: String) {
        val r = read("Test Bank", body)
        assertIs<ParseResult.Ignored>(r, "Expected an ignored notice but got $r for: $body")
    }

    @Test fun the_file_is_accepted_over_version_7() {
        val out = RulesPack.parse(packJson, 7)
        assertIs<RulesPack.Outcome.Ok>(out)
        assertEquals(8, out.pack.version)
        assertEquals(22, out.pack.rules.size)
        assertEquals(27, out.pack.ignore.size)
        assertTrue(out.pack.rules.any { it.rule.id == "pack:cbd-card-debited-c" })
    }

    @Test fun cbd_card_debit_every_wording_seen() {
        val c = "cbd-card-debited-c"
        // the 19 Nov wording (merchant with "&" and a second "at") is already read by the v6 rule
        check("CBD", "cbd-card-debited", "A transaction of AED 10.00 has been debited from your Credit Card 1234 at SHOP & CO at WWW.EXAMPLE.COM. Your balance is AED 500.00. Please refer to monthly statement for details.", "1234", "10.00", "SHOP & CO at WWW.EXAMPLE.COM", TxnType.PURCHASE, CardType.CREDIT, "500.00")
        // no "has been" (17 Nov)
        check("CBD", c, "A transaction of AED 12.50 debited from your Credit Card 1234 at TAP*SHOP HOLD. Your balance is AED 487.50. Please refer to monthly statement for details.", "1234", "12.50", "TAP*SHOP HOLD", TxnType.PURCHASE, CardType.CREDIT, "487.50")
        check("CBD", c, "A transaction of AED 7.00 debited from your Credit Card 1234 at WWW.SHOP.COM. Your balance is AED 480.50. Please refer to monthly statement for details.", "1234", "7.00", "WWW.SHOP.COM", TxnType.PURCHASE, CardType.CREDIT, "480.50")
        // "was debited", no "at"
        check("CBD", c, "A transaction of AED 33.00 was debited from your credit card 1234 SHOP NAME. Your balance is AED 447.50. Please refer to monthly statement for details.", "1234", "33.00", "SHOP NAME", TxnType.PURCHASE, CardType.CREDIT, "447.50")
    }

    @Test fun cbd_v6_wording_is_still_read_by_the_v6_rule_and_credits_are_not_read_as_purchases() {
        check("CBD", "cbd-card-debited", "A transaction of AED 45.50 has been debited from your Credit Card 1234 at CARREFOUR. Your balance is AED 2,310.40. Please refer to monthly statement for details.", "1234", "45.50", "CARREFOUR", TxnType.PURCHASE, CardType.CREDIT, "2310.40")
        val r = read("CBD", "A transaction of AED 10.00 has been credited to your Credit Card 1234 at SHOP. Your balance is AED 500.00.")
        assertTrue(r !is ParseResult.Transaction || r.ruleId?.startsWith("pack:cbd-card-debited") != true, "a credit must not be read by the debit rules: $r")
    }

    @Test fun ajman_bank_credit_card_spend() {
        check("Ajman Bank", "ajman-credit-card-spent", "You have spent AED 45.50 at CARREFOUR LLC on your credit card ending with 1234 on 19/11/2026 12:30. Available Limit: AED 1,200.00", "1234", "45.50", "CARREFOUR LLC", TxnType.PURCHASE, CardType.CREDIT, "1200.00")
        check("Ajman Bank", "ajman-credit-card-spent", "You have spent AED 9.00 at TAP*WALLET XYZ on your credit card ending with 5678 on 19/11/2026 08:05. Available Limit: AED 90.25", "5678", "9.00", "TAP*WALLET XYZ", TxnType.PURCHASE, CardType.CREDIT, "90.25")
    }

    @Test fun axis_bank_prepaid_recharge() {
        check("Axis Bank", "axis-prepaid-recharge", "Recharge of AED 50.00 for Etisalat Prepaid 0501234567 has been processed successfully with reference no. AB123C456 - Axis Bank", null, "50.00", "Mobile recharge", TxnType.PURCHASE, CardType.ACCOUNT)
    }

    @Test fun icici_card_reversal_is_a_refund() {
        check("ICICI Bank", "icici-card-reversal", "Reversal of AED 25.00 credited to ICICI Bank Credit Card XX1234 on 19-Nov-2026. Your total due AED 100.00, minimum due AED 10.00", "1234", "25.00", "Reversal", TxnType.REFUND, CardType.CREDIT)
    }

    @Test fun new_ignore_entries_hide_only_their_notices() {
        ignored("A txn declined on card. 1234 at SHOP pay for AED 10.00 on 19/11/2026 12:30 was not processed. For details call us on +97150")
        ignored("Total Amount Due on ICICI Bank Credit Card XX1234 is AED 100.00. Amount will be debited from your bank account on or before 20-Nov-2026. Ignore if paid.")
        ignored("Your credit payment is due today. Statement amount: AED 100.00. Auto-pay will be processed at 10:00 PM.")
        ignored("Pay minimum due of AED 50.00 by 20-Nov-2026 on card ending 1234 to avoid fees. Call 800")
        ignored("Please pay the min due of AED 50.00 on your card 1234...by 20/11/2026. Ignore if already paid.")
        ignored("Dear customer, your credit card min payment of AED 50.00 is due on 20/11/2026. To avoid payment charges kindly pay at the branch.")
        ignored("Dear customer, a credit refund of amount of AED 20.00 is notified by merchant SHOP on www.shop.com, and will be processed after receiving confirmation.")
        ignored("Tabby Card total limit is now increased to AED 5,000.00")
    }

    @Test fun ordinary_purchases_are_not_hidden() {
        for (body in listOf(
            "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00",
            "AED 100.00 debited from your account XXXX1234 on 12/10/2026",
            "Thank you for using your card ending 1234 for AED 10.00 at SHOP on 19/11/2026 12:30. Avl.Limit: AED 500.00",
        )) {
            val r = read("Mashreq", body)
            assertTrue(r !is ParseResult.Ignored, "must not be ignored: $body -> $r")
        }
    }
}
