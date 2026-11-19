package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Runs on the code that is on main (the Play version): what does an old build do with rules pack v5? */
class OldBuildPackV5Test {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val packV5 = """{
  "version": 5,
  "minEngine": 5,
  "banks": [
    { "name": "Tabby", "country": "AE", "aliases": ["Tabby Card"], "senderIds": ["Tabby"], "track": true },
    { "name": "ADCB", "country": "AE", "aliases": ["SimplyLife"], "senderIds": ["SIMPLYLIFE"], "track": true },
    { "name": "Tamara", "country": "AE", "aliases": ["Tamara Pay Later"] },
    { "name": "Postpay", "country": "AE" },
    { "name": "Spotii", "country": "AE" },
    { "name": "Cashew", "country": "AE" },
    { "name": "CBD Now", "country": "AE", "aliases": ["Commercial Bank of Dubai"] },
    { "name": "ADCB Hayyak", "country": "AE", "aliases": ["Hayyak"] },
    { "name": "E20", "country": "AE", "aliases": ["Emirates Islamic E20"] },
    { "name": "Reem Community Bank", "country": "AE" },
    { "name": "YAP", "country": "AE" },
    { "name": "Xpence", "country": "AE" }
  ],
  "rules": [
    {
      "bank": "Mashreq",
      "id": "mashreq-thank-you-debit-20261116",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Debit Card(?: Card)? ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"
    },
    {
      "bank": "Mashreq",
      "id": "mashreq-thank-you-purchase-20261116",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "CREDIT",
      "pattern": "Thank you for\\s+\\w+\\s+(?:your\\s+)?(?:[^.]{0,40}?\\s)?Card ending\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+(?:Avl\\.?\\s*(?:Limit|Bal)|Available (?:Limit|Balance))\\s*(?:is|:)?\\s*{ANYCUR}\\s*{AVAIL})?"
    },
    {
      "bank": "HDFC Bank",
      "id": "hdfc-aed-card-purchase",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "Thanks for\\s+\\w+\\s+HDFC Bank\\s+[^.]{0,40}?Card\\s+{CARD}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+at\\s+{MERCHANT}\\s+on\\s+{DATETIME}\\.?(?:\\s+Card Bal:?\\s*{ANYCUR}\\s*[\\d,.]+)?"
    },
    {
      "bank": "Emirates NBD",
      "id": "enbd-debit-card-funds-transfer",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"
    },
    {
      "bank": "Liv",
      "id": "liv-debit-card-funds-transfer",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "Funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+has been made\\s+\\w+(?:\\s+\\w+)?\\s+your debit card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\.\\s+Your current balance is\\s+{ANYCUR}\\s*{AVAIL}"
    },
    {
      "bank": "ADIB",
      "id": "adib-trx-approved",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "Trx\\.? of\\s+{CUR}\\s*{AMOUNT}\\s+on your card ending\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+is approved\\.?(?:\\s+Avl\\.?\\s*card bal(?:ance)? is\\s+[\\d,.]+\\.?)?(?:\\s+Trx\\.? date:?\\s*{DATETIME})?"
    },
    {
      "bank": "Sharjah Islamic Bank",
      "id": "sib-outward-remit",
      "kind": "TRANSACTION",
      "type": "TRANSFER_OUT",
      "cardType": "ACCOUNT",
      "pattern": "A/C\\s+{CARD}\\s+outward remit\\s+{CUR}\\s*{AMOUNT}\\.?(?:\\s+Available balance is\\s+{ANYCUR}\\s*\\+?{AVAIL})?",
      "fixedMerchant": "Outward remittance"
    },
    {
      "bank": "Sharjah Islamic Bank",
      "id": "sib-card-txn-approved",
      "kind": "TRANSACTION",
      "type": "PURCHASE",
      "cardType": "DEBIT",
      "pattern": "A txn on your card\\s+{CARD}\\s+at\\s+{MERCHANT}\\s+for\\s+{CUR}\\s*{AMOUNT}\\s+on\\s+{DATETIME}(?:\\s*at\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?\\s+is approved\\.?(?:\\s+Your available balance is\\s+[\\d,.]+)?"
    },
    {
      "bank": "CBD",
      "id": "cbd-credit-card-payment",
      "kind": "TRANSACTION",
      "type": "PAYMENT",
      "cardType": "CREDIT",
      "pattern": "thank you for your credit card payment via funds transfer of\\s+{CUR}\\s*{AMOUNT}\\s+for card ending\\s+{CARD}(?:\\s+on\\s+{DATETIME})?",
      "fixedMerchant": "Card payment received"
    },
    {
      "bank": "ADCB",
      "id": "adcb-debit-card-refund",
      "kind": "TRANSACTION",
      "type": "REFUND",
      "cardType": "DEBIT",
      "pattern": "{CUR}\\s*{AMOUNT}\\s+has been refunded to your debit card\\s+{CARD}\\s+on\\s+{DATETIME}\\s*by\\s+{MERCHANT}\\.\\s*The amount will be credited"
    }
  ],
  "ignore": [
    {
      "label": "Declined: insufficient balance",
      "pattern": "cannot be processed due to insufficient balance"
    },
    {
      "label": "Notice: transfer limit raised",
      "pattern": "is pleased to .{1,120} in your account\\. For .{1,30} call .{1,20} or visit your .{1,30} branch"
    },
    {
      "label": "Notice: IBFT limit advert",
      "pattern": "now .{1,30} IBFT transactions up to [A-Z]{3} [\\d,.]+/-"
    },
    {
      "label": "Advert: instant credit branches",
      "pattern": "branches for instant credit to your account"
    },
    {
      "label": "Telecom bill reminder",
      "pattern": "Total payable are [A-Z]{3} [\\d,.]+"
    },
    {
      "label": "Telecom bill due notice",
      "pattern": "Your total bill for .{1,40} is [A-Z]{3} [\\d,.]+, Please pay by"
    },
    {
      "label": "Failed payment notice",
      "pattern": "Your payment of [A-Z]{3} [\\d,.]+ is failed"
    },
    {
      "label": "UPI payment request",
      "pattern": "has requested .{1,40}? from you on your .{1,30}? UPI\\."
    },
    {
      "label": "Payee added notice",
      "pattern": "Payee .{1,60}? added\\. Transfer funds after"
    },
    {
      "label": "Card spend promo",
      "pattern": "spend is on us!"
    },
    {
      "label": "Currency rate alert",
      "pattern": "we have a new update for your currency .{1,20}? alert"
    },
    {
      "label": "Future-dated payment set up",
      "pattern": "you have .{1,10}? up a .{1,15}? dated (?:payment|transfer) through online/mobile banking"
    },
    {
      "label": "Credit card approved notice",
      "pattern": "your FAB credit card has been approved with a limit of"
    }
  ]
}
"""

    @Test fun oldBuildAcceptsV5AndOffersTabbyAndSimplyLife() {
        val out = RulesPack.parse(packV5, currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        RulesPack.apply(out.pack)
        println("OLDBUILD bankFor(Tabby)=" + SmsParser.bankFor("Tabby")?.name + " bankFor(SIMPLYLIFE)=" + SmsParser.bankFor("SIMPLYLIFE")?.name)
        println("OLDBUILD match(Tabby)=" + GlobalBanks.match("Tabby", null, "AE")?.name + " match(SIMPLYLIFE)=" + GlobalBanks.match("SIMPLYLIFE", null, "AE")?.name)
        val tabby = "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94."
        val simply = "Your Cr.Card XXX5886 was used for USD30.60 on 04/08/2023 11:04:30 at OW *passcert.com,London-GB. Avl. Cr.limit is AED2882.95"
        println("OLDBUILD looksLikeBankAlert tabby=" + SmartParser.looksLikeBankAlert(tabby) + " simplylife=" + SmartParser.looksLikeBankAlert(simply))
        val report = "bankFor=" + SmsParser.bankFor("Tabby")?.name + "/" + SmsParser.bankFor("SIMPLYLIFE")?.name +
            " match=" + GlobalBanks.match("Tabby", null, "AE")?.name + "/" + GlobalBanks.match("SIMPLYLIFE", null, "AE")?.name +
            " alert=" + SmartParser.looksLikeBankAlert(tabby) + "/" + SmartParser.looksLikeBankAlert(simply)
        assertEquals("REPORT", report)
    }
}
