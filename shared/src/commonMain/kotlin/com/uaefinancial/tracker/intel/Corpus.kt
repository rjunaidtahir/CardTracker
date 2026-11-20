package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser

/**
 * The messages the second opinion learns from. Each raw message (CorpusData, generated from the engine's test fixtures)
 * is labelled by the verified rule reader when the app starts, so a label is always what the rules say today.
 * Messages the rules cannot read, and one-time codes, are left out. Source of every sample: reconstructed test fixture.
 */
internal object Corpus {
    data class Sample(
        val group: String,
        val body: String,
        val label: Label,
        /** The merchant the rule reader found (transactions only); training data for the merchant tagger. */
        val merchant: String?,
    )

    val samples: List<Sample> by lazy { build() }

    private fun build(): List<Sample> {
        val zone = SmsParser.UAE_ZONE
        val at = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis(zone)
        val out = ArrayList<Sample>()
        for ((sender, body) in CorpusData.raw) {
            val r = runCatching {
                if (sender.isNotEmpty() && SmsParser.bankFor(sender) != null) SmsParser.parse(sender, body, at, zone)
                else SmsParser.parseAsBank(sender.ifEmpty { "Unknown" }, body, at, zone)
            }.getOrNull() ?: continue
            when (r) {
                is ParseResult.Transaction -> out += Sample(r.txn.bank, body, Label.of(r.txn.type), r.txn.merchant)
                is ParseResult.Statement -> out += Sample(r.statement.bank, body, Label.STATEMENT, null)
                is ParseResult.Ignored -> if (r.store) out += Sample(r.bank, body, Label.NOT_TXN, null)
                else -> {}
            }
        }
        return out
    }
}
