package com.uaefinancial.tracker.intel

import kotlin.test.Test

/** Diagnostic only: would the app's existing small merchant tagger (trained at start-up) have found the merchant? */
class InvestTaggerReport {
    @Test fun report() {
        val t = MerchantTagger()
        val train = Corpus.samples.filter { !it.merchant.isNullOrBlank() && it.label.toTxnType() != null }.map { it.body to it.merchant!! }
        t.train(train)
        println("TAGGER trainedOn=${train.size}")
        val samples = listOf(
            "You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
            "You have made a Purchase transaction on your Credit Card ending 1234 from CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
            "You have made a Purchase transaction on your Credit Card ending 1234 at CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
        )
        for ((i, s) in samples.withIndex()) {
            val f = t.find(s)
            println("TAGGER #$i found='${f?.text}' margin=${f?.margin}")
        }
    }
}
