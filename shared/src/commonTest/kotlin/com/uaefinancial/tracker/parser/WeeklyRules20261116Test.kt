package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Rules file update of 2026-11-16 (version 4): reading rules and ignore entries written from the real masked shapes in the
 * Fils shapes Sheet (15-16 Nov 2026). The JSON below is the exact content of rules/pack.json; every rule is tried on
 * messages built from its shape. Rules for banks the app already knows apply once a phone downloads the file (no Play update).
 */
class WeeklyRules20261116Test {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 15, 12, 31).toEpochMillis(zone)
    private val packJson = """{"version":4,"minEngine":5,"banks":[],"rules":[{"bank":"Mashreq","id":"mashreq-thank-you-debit-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Debit Card(?: Card)? ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"Mashreq","id":"mashreq-thank-you-purchase-20261116","kind":"TRANSACTION","type":"PURCHASE","cardType":"CREDIT","pattern":"Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Card ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"},{"bank":"HDFC Bank","id":"hdfc-aed-card-purchase","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Thanks for\\s+\\w+\\s+HDFC Bank\\s+[^.]{0,40}?Card\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+Card Bal:?\\s*{ANYCUR}\\s*[\\d,.]+)?"},{"bank":"Emirates NBD","id":"enbd-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"Liv","id":"liv-debit-card-funds-transfer","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"},{"bank":"ADIB","id":"adib-trx-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"Trx\\.? of\\s+{CUR}\\s*{AMOUNT}\\s+on your card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+is approved\\.?(?:\\s+Avl\\.?\\s*card bal(?:ance)? is\\s+[\\d,.]+\\.?)?(?:\\s+Trx\\.? date:?\\s*{DATETIME})?"},{"bank":"Sharjah Islamic Bank","id":"sib-outward-remit","kind":"TRANSACTION","type":"TRANSFER_OUT","cardType":"ACCOUNT","pattern":"A/C\\s+{CARD}\\s+outward remit\\s+{CUR}\\s*{AMOUNT}\\.?(?:\\s+Available balance is\\s+{ANYCUR}\\s*\\+?{AVAIL})?","fixedMerchant":"Outward remittance"},{"bank":"Sharjah Islamic Bank","id":"sib-card-txn-approved","kind":"TRANSACTION","type":"PURCHASE","cardType":"DEBIT","pattern":"A txn on your card\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+on\\s+{DATETIME}(?:\\s*at\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s+is approved\\.?(?:\\s+Your available balance is\\s+[\\d,.]+)?"},{"bank":"CBD","id":"cbd-credit-card-payment","kind":"TRANSACTION","type":"PAYMENT","cardType":"CREDIT","pattern":"thank you for your credit card payment via funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+for card ending\\s+{CARD}(?:\\s+on\\s+{DATETIME})?","fixedMerchant":"Card payment received"},{"bank":"ADCB","id":"adcb-debit-card-refund","kind":"TRANSACTION","type":"REFUND","cardType":"DEBIT","pattern":"{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.\\s*The amount will be credited"}],"ignore":[{"label":"Declined: insufficient balance","pattern":"cannot be processed due to insufficient balance"},{"label":"Notice: transfer limit raised","pattern":"is pleased to .{1,120} in your account\\. For .{1,30} call .{1,20} or visit your .{1,30} branch"},{"label":"Notice: IBFT limit advert","pattern":"now .{1,30} IBFT transactions up to [A-Z]{3} [\\d,.]+/-"},{"label":"Advert: instant credit branches","pattern":"branches for instant credit to your account"},{"label":"Telecom bill reminder","pattern":"Total payable are [A-Z]{3} [\\d,.]+"},{"label":"Telecom bill due notice","pattern":"Your total bill for .{1,40} is [A-Z]{3} [\\d,.]+, Please pay by"},{"label":"Failed payment notice","pattern":"Your payment of [A-Z]{3} [\\d,.]+ is failed"},{"label":"UPI payment request","pattern":"has requested .{1,40}? from you on your .{1,30}? UPI\\."},{"label":"Payee added notice","pattern":"Payee .{1,60}? added\\. Transfer funds after"},{"label":"Card spend promo","pattern":"spend is on us!"},{"label":"Currency rate alert","pattern":"we have a new update for your currency .{1,20}? alert"},{"label":"Future-dated payment set up","pattern":"you have .{1,10}? up a .{1,15}? dated (?:payment|transfer) through online/mobile banking"},{"label":"Credit card approved notice","pattern":"your FAB credit card has been approved with a limit of"},{"label":"UPI payment request","pattern":"has requested .{1,40}? from you on your .{1,30}? UPI\\."},{"label":"Payee added notice","pattern":"Payee .{1,60}? added\\. Transfer funds after"},{"label":"Card spend promo","pattern":"spend is on us!"},{"label":"Currency rate alert","pattern":"we have a new update for your currency .{1,20}? alert"},{"label":"Future-dated payment set up","pattern":"you have .{1,10}? up a .{1,15}? dated (?:payment|transfer) through online/mobile banking"},{"label":"Credit card approved notice","pattern":"your FAB credit card has been approved with a limit of"}]}"""

    @BeforeTest fun load() {
        val out = RulesPack.parse(packJson, 3)
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
    }
    @AfterTest fun reset() { RulesPack.apply(null) }

    private fun read(bank: String, body: String) = SmsParser.parseAsBank(bank, body, received, zone)

    private fun check(bank: String, ruleId: String, body: String, card: String?, amount: String, merchant: String?,
                      type: TxnType, cardType: CardType, avail: String? = null, packRule: Boolean = true) {
        val r = read(bank, body)
        assertIs<ParseResult.Transaction>(r, "Expected a transaction but got $r for: $body")
        if (packRule) assertEquals("pack:$ruleId", r.ruleId, body)
        val t = r.txn
        assertEquals(type, t.type, body)
        assertEquals(cardType, t.cardType, body)
        assertEquals(card, t.cardLast4, body)
        assertEquals(Decimal(amount), t.amount, body)
        if (merchant != null) assertTrue(t.merchant.startsWith(merchant, ignoreCase = true), "merchant '${t.merchant}' for: $body")
        if (avail != null) assertEquals(Decimal(avail), t.availableLimit, body)
    }

    @Test fun the_file_is_accepted_and_keeps_the_old_ignore_entries() {
        val out = RulesPack.parse(packJson, 3)
        assertIs<RulesPack.Outcome.Ok>(out)
        assertEquals(4, out.pack.version)
        assertEquals(10, out.pack.rules.size)
        assertEquals(19, out.pack.ignore.size)
        assertTrue(out.pack.ignore.any { it.label == "Failed payment notice" })
    }

    @Test fun mashreq_pack_rules_are_the_same_patterns_as_the_built_in_ones() {
        val out = RulesPack.parse(packJson, 3)
        assertIs<RulesPack.Outcome.Ok>(out)
        val mashreq = BankRules.banks.first { it.name == "Mashreq" }.rules
        for ((builtIn, packId) in listOf("mashreq-thank-you-debit" to "mashreq-thank-you-debit-20261116", "mashreq-thank-you-purchase" to "mashreq-thank-you-purchase-20261116")) {
            val a = mashreq.first { it.id == builtIn }.pattern
            val b = out.pack.rules.first { it.rule.id == "pack:$packId" }.rule.pattern
            assertEquals(a, b, builtIn)
        }
    }

    @Test fun mashreq_thank_you_debit_20261116() {
        check("Mashreq", "mashreq-thank-you-debit-20261116", "Thank you for using VISA Debit Card Card ending 1234 for AED 45.50 at CARREFOUR LLC on 15-NOV-2026 12:30 PM. Available Balance is AED 1,200.00", "1234", "45.50", "CARREFOUR LLC", TxnType.PURCHASE, CardType.DEBIT, "1200.00", packRule = false)
        check("Mashreq", "mashreq-thank-you-debit-20261116", "Thank you for using your Debit Card ending 5678 for AED 12.00 at TALABAT on 15-NOV-2026 09:05 AM. Avl. Bal is AED 300.10", "5678", "12.00", "TALABAT", TxnType.PURCHASE, CardType.DEBIT, "300.10", packRule = false)
    }

    @Test fun mashreq_thank_you_purchase_20261116() {
        check("Mashreq", "mashreq-thank-you-purchase-20261116", "Thank you for using your card ending 1234 for AED 45.50 at CARREFOUR LLC on 15-NOV-2026 12:30 PM. Avl.Limit: AED 5,000.00", "1234", "45.50", "CARREFOUR LLC", TxnType.PURCHASE, CardType.CREDIT, "5000.00", packRule = false)
        check("Mashreq", "mashreq-thank-you-purchase-20261116", "Thank you for using Mashreq Cashback Credit Card Card ending 4321 for AED 99.00 at NOON.COM on 15-NOV-2026 01:15 PM. Avl.Limit is AED 7,500.25.", "4321", "99.00", "NOON.COM", TxnType.PURCHASE, CardType.CREDIT, "7500.25", packRule = false)
    }

    @Test fun hdfc_aed_card_purchase() {
        check("HDFC Bank", "hdfc-aed-card-purchase", "Thanks for using HDFC Bank Visa Platinum AED Card 1234 for AED 45.50 at CARREFOUR DUBAI AE on 15-11-2026 12:30:05. Card Bal: AED 1,000.00. Not you ? Call 80012345", "1234", "45.50", "CARREFOUR DUBAI", TxnType.PURCHASE, CardType.DEBIT, null, packRule = true)
        check("HDFC Bank", "hdfc-aed-card-purchase", "Thanks for using HDFC Bank Visa AED Card 9876 for AED 7.00 at SPINNEYS on 14-11-2026 18:02:11. Card Bal: AED 88.50. Not you ? Call 80012345", "9876", "7.00", "SPINNEYS", TxnType.PURCHASE, CardType.DEBIT, null, packRule = true)
    }

    @Test fun enbd_debit_card_funds_transfer() {
        check("Emirates NBD", "enbd-debit-card-funds-transfer", "Funds transfer of AED 45.50 has been made using your debit card ending 1234 at CARREFOUR LLC. Your current balance is AED 2,310.40.", "1234", "45.50", "CARREFOUR LLC", TxnType.PURCHASE, CardType.DEBIT, "2310.40", packRule = true)
        check("Emirates NBD", "enbd-debit-card-funds-transfer", "Funds transfer of AED 120.00 has been made via your debit card ending 5555 at AMAZON*AB12. Your current balance is AED 80.15", "5555", "120.00", "AMAZON*AB12", TxnType.PURCHASE, CardType.DEBIT, "80.15", packRule = true)
    }

    @Test fun liv_debit_card_funds_transfer() {
        check("Liv", "liv-debit-card-funds-transfer", "Funds transfer of AED 30.00 has been made using your debit card ending 2468 at TALABAT. Your current balance is AED 410.00.", "2468", "30.00", "TALABAT", TxnType.PURCHASE, CardType.DEBIT, "410.00", packRule = true)
    }

    @Test fun adib_trx_approved() {
        check("ADIB", "adib-trx-approved", "Trx. of AED 45.50 on your card ending 1234 at CARREFOUR UAE is approved. Avl. card bal is 2,310.40. Trx date: 15/11/2026 12:30", "1234", "45.50", "CARREFOUR", TxnType.PURCHASE, CardType.DEBIT, null, packRule = true)
        check("ADIB", "adib-trx-approved", "Trx. of AED 8.00 on your card ending 7777 at TALABAT DUBAI, UAE is approved. Avl. card bal is 99.75. Trx date: 14/11/2026 09:10", "7777", "8.00", "TALABAT DUBAI", TxnType.PURCHASE, CardType.DEBIT, null, packRule = true)
    }

    @Test fun sib_outward_remit() {
        check("Sharjah Islamic Bank", "sib-outward-remit", "A/C 1234 outward remit AED 1,500.00. Available balance is AED +12,345.67.", "1234", "1500.00", "Outward remittance", TxnType.TRANSFER_OUT, CardType.ACCOUNT, "12345.67", packRule = true)
    }

    @Test fun sib_card_txn_approved() {
        check("Sharjah Islamic Bank", "sib-card-txn-approved", "A txn on your card 1234 at CARREFOUR for AED 45.50 on 15/11/2026at 12:30 is approved. Your available balance is 2310.40", "1234", "45.50", "CARREFOUR", TxnType.PURCHASE, CardType.DEBIT, null, packRule = true)
    }

    @Test fun cbd_credit_card_payment() {
        check("CBD", "cbd-credit-card-payment", "Dear Customer, thank you for your credit card payment via funds transfer of AED 2,000.00 for card ending 1234 on 15/11/2026 10:15.", "1234", "2000.00", "Card payment received", TxnType.PAYMENT, CardType.CREDIT, null, packRule = true)
    }

    @Test fun adcb_debit_card_refund() {
        check("ADCB", "adcb-debit-card-refund", "AED 45.50 has been refunded to your debit card 1234 on 15/11/2026 12:30by CARREFOUR LLC.The amount will be credited to your account within 3-5 working days", "1234", "45.50", "CARREFOUR LLC", TxnType.REFUND, CardType.DEBIT, null, packRule = true)
        check("ADCB", "adcb-debit-card-refund", "AED 10.00 has been refunded to your debit card 1234 on 15/11/2026 12:30 by NOON.The amount will be credited to your account within 3-5 working days", "1234", "10.00", "NOON", TxnType.REFUND, CardType.DEBIT, null, packRule = true)
    }

    @Test fun ignore_entries_hide_only_their_notices() {
        run { val r = read("Test Bank", "Sender Name has requested money from you on your HDFC UPI. AED 100.00 will be debited from your account, on approving the request."); assertIs<ParseResult.Ignored>(r, "UPI payment request"); assertEquals("UPI payment request", r.reason) }
        run { val r = read("Test Bank", "Payee John Smith added. Transfer funds after 30 mins. Send up to AED 10,000.00 for first 24hrs. Not you? Call 800-ICICI Bank"); assertIs<ParseResult.Ignored>(r, "Payee added notice"); assertEquals("Payee added notice", r.reason) }
        run { val r = read("Test Bank", "Your Apple Pay spend is on us! Add your card to Apple Pay, make 3 transactions of AED 50.00 and save up to AED 150.00 till 30-Nov-2026."); assertIs<ParseResult.Ignored>(r, "Card spend promo"); assertEquals("Card spend promo", r.reason) }
        run { val r = read("Test Bank", "Dear Customer, we have a new update for your currency rate alert: the exchange rate of INR has reached your target AED 22.50"); assertIs<ParseResult.Ignored>(r, "Currency rate alert"); assertEquals("Currency rate alert", r.reason) }
        run { val r = read("Test Bank", "You have set up a future dated payment through online/mobile banking for Emirates NBD credit cards payment to credit card number 1234 from your account 5678 of AED 500.00."); assertIs<ParseResult.Ignored>(r, "Future-dated payment set up"); assertEquals("Future-dated payment set up", r.reason) }
        run { val r = read("Test Bank", "Dear Mr. Smith your FAB credit card has been approved with a limit of AED 20,000.00. The activation amount of AED 100.00 is approved."); assertIs<ParseResult.Ignored>(r, "Credit card approved notice"); assertEquals("Credit card approved notice", r.reason) }
    }

    @Test fun ordinary_purchases_are_not_hidden_by_the_new_ignore_entries() {
        assertNotEquals(true, read("Test Bank", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00") is ParseResult.Ignored && (read("Test Bank", "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00") as ParseResult.Ignored).reason in setOf("UPI payment request", "Payee added notice", "Card spend promo", "Currency rate alert", "Future-dated payment set up", "Credit card approved notice"))
        assertNotEquals(true, read("Test Bank", "AED 100.00 debited from your account XXXX1234 on 12/10/2026") is ParseResult.Ignored && (read("Test Bank", "AED 100.00 debited from your account XXXX1234 on 12/10/2026") as ParseResult.Ignored).reason in setOf("UPI payment request", "Payee added notice", "Card spend promo", "Currency rate alert", "Future-dated payment set up", "Credit card approved notice"))
        assertNotEquals(true, read("Test Bank", "Dear Customer, You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026") is ParseResult.Ignored && (read("Test Bank", "Dear Customer, You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026") as ParseResult.Ignored).reason in setOf("UPI payment request", "Payee added notice", "Card spend promo", "Currency rate alert", "Future-dated payment set up", "Credit card approved notice"))
    }
}
