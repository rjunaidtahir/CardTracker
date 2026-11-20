package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import kotlin.test.Test

/** Diagnostic only: how the app reads the real InvestBank card purchase sample (card digits replaced by 1234). */
class InvestSampleReport {
    private val samples = listOf(
        "You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
        "You have made a Purchase transaction on your Credit Card ending 1234 from CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
        "You have made a Purchase transaction on your Credit Card ending 1234 at CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
    )

    private fun show(tag: String, r: ParseResult?) {
        when (r) {
            is ParseResult.Transaction -> println("INVEST $tag txn type=${r.txn.type} merchant='${r.txn.merchant}' amount=${r.txn.amount} card=${r.txn.cardLast4} cardType=${r.txn.cardType} rule=${r.ruleId}")
            null -> println("INVEST $tag null")
            else -> println("INVEST $tag other=${r::class.simpleName} $r")
        }
    }

    @Test fun report() {
        val zone = SmsParser.UAE_ZONE
        val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
        for ((i, s) in samples.withIndex()) {
            val body = SmsParser.normalizeBody(s)
            println("INVEST #$i normalized: $body")
            show("#$i smart", runCatching { SmartParser.read("InvestBank", body, at, zone) }.getOrNull())
            show("#$i asBank", runCatching { SmsParser.parseAsBank("InvestBank", s, at, zone) }.getOrNull())
        }
    }
}
