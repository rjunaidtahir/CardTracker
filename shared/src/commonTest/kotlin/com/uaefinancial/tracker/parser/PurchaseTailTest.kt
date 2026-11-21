package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/** FAB credit-card purchases followed by a bank promotion (21 Nov 2026 export: 377 messages the generic reader did not read). Invented merchant/amounts. */
class PurchaseTailTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
    private val head = "Credit Card Purchase Card No XXXX1234 AED 45.50 ZENITH BAKERY ARE 19/11/2026 10:15"
    private val tails = listOf(
        "",
        "Avl Bal AED 1,200.00.19/11/2026statement due on 25/12/2026 Pay school fees in 6 instalments at 0% interest with no fees. Conditions apply.",
        "Apply for a 0% EPP on school fees for up to 12 months. Learn more www.example.com T&C apply.",
        "Your August statement payment due date is 25/08/2026 Get up to 30% off at any Zorbo DG Store and online with FAB cards. Learn more www.example.com T&C apply",
        "Your July statement payment due date is 25/07/2026 Get up to 20% cashback on online shopping with ZORBOSMART. Visit Offers Page on the FAB Mobile app",
        "Save 20% on Zorbo Air flights with FAB till 31/12/2026 www.example.com T&C apply.",
        "Available Balance AED 900.00 Apply for a 0% EPP on school fees for up to 12 months. Learn more www.example.com T&C apply.",
    )
    @Test fun purchaseWithPromotionIsStillAPurchase() {
        val out = tails.map { t ->
            val text = SmsParser.normalizeBody("$head$t")
            val r = SmsParser.parseAsBank("Unknown Test Bank", text, at, zone)
            "${t.take(25)} -> ${r::class.simpleName}"
        }
        assertTrue(out.all { it.contains("-> Transaction") }, out.joinToString("\n"))
    }

    @Test fun aPureAdvertIsStillNotARecordedTransaction() {
        val r = SmsParser.parseAsBank("Unknown Test Bank", SmsParser.normalizeBody("Enjoy 20% off at Zorbo Bakery with your credit card. Spend AED 500 or more. Learn more www.example.com T&C apply."), at, zone)
        assertTrue(r !is ParseResult.Transaction, "advert was recorded: $r")
    }
}
