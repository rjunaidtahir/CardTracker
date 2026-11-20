package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import kotlin.test.Test

/**
 * How well the generic (keyword) reader that is already in the app tells money out from money in, against what the
 * verified bank rules say about the same messages. Prints "SMARTDIR ..." lines for the CI log. Caveat printed with it:
 * these fixtures were also used to develop the generic reader, so this is an optimistic number.
 */
class SmartDirectionReport {
    private fun dir(t: TxnType) = when (t) {
        TxnType.PURCHASE, TxnType.TRANSFER_OUT -> "out"
        TxnType.REFUND, TxnType.TRANSFER_IN -> "in"
        TxnType.PAYMENT -> "payment"
    }

    @Test fun report() {
        val zone = SmsParser.UAE_ZONE
        val at = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis(zone)
        var n = 0; var right = 0; var wrong = 0; var silent = 0; var ignored = 0
        val wrongList = ArrayList<String>()
        for ((sender, body) in CorpusData.raw) {
            val gold = runCatching {
                if (sender.isNotEmpty() && SmsParser.bankFor(sender) != null) SmsParser.parse(sender, body, at, zone)
                else SmsParser.parseAsBank(sender.ifEmpty { "Unknown" }, body, at, zone)
            }.getOrNull() as? ParseResult.Transaction ?: continue
            if (gold.txn.auto) continue
            val g = dir(gold.txn.type)
            if (g == "payment") continue
            n++
            val r = runCatching { SmartParser.read("X", SmsParser.normalizeBody(body), at, zone) }.getOrNull()
            when (r) {
                is ParseResult.Transaction -> {
                    val p = dir(r.txn.type)
                    if (p == g) right++ else { wrong++; wrongList += "gold=$g read=$p :: ${SmsParser.normalizeBody(body).take(160)}" }
                }
                is ParseResult.Statement -> { wrong++; wrongList += "gold=$g read=STATEMENT :: ${SmsParser.normalizeBody(body).take(160)}" }
                is ParseResult.Ignored -> ignored++
                else -> silent++
            }
        }
        println("SMARTDIR messages=$n right=$right wrong=$wrong ignoredByReader=$ignored noAnswer=$silent")
        wrongList.forEach { println("SMARTDIR WRONG $it") }
    }
}
