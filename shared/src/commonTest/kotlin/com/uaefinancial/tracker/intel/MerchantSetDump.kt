package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import kotlin.test.Test

/**
 * Measurement only (nothing ships). For every sample of the corpus: the bank, the message, the merchant the verified rule read
 * (gold, only when it is written in the message), what the app's existing small tagger says when trained WITHOUT that bank
 * (leave-one-bank-out), and what the generic pattern reader says. Prints "MSET {json}" lines for the python models and a
 * "MSETSUM" summary of the existing tagger by confidence level.
 */
class MerchantSetDump {
    private fun esc(s: String) = buildString { for (c in s) when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append(' '); '\r' -> {}; '\t' -> append(' '); else -> append(c) } }

    @Test fun dump() {
        val zone = SmsParser.UAE_ZONE
        val at = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis(zone)
        val samples = Corpus.samples
        val groups = samples.map { it.group }.distinct()
        data class Row(val group: String, val text: String, val label: String, val gold: String, val tag: String, val margin: Double, val smart: String)
        val rows = ArrayList<Row>()
        for (g in groups) {
            val tagger = MerchantTagger()
            tagger.train(samples.filter { it.group != g && !it.merchant.isNullOrBlank() && it.label.toTxnType() != null }.map { it.body to it.merchant!! })
            for (s in samples.filter { it.group == g }) {
                val text = SmsParser.normalizeBody(s.body)
                val gold = s.merchant?.takeIf { it.isNotBlank() && s.label.toTxnType() != null && text.contains(it, ignoreCase = true) } ?: ""
                val f = tagger.find(text)
                val smart = (runCatching { SmartParser.read("X", text, at, zone) }.getOrNull() as? ParseResult.Transaction)?.txn?.merchant
                    ?.takeIf { it !in com.uaefinancial.tracker.core.ReadingGaps.placeholderMerchants } ?: ""
                rows += Row(g, text, s.label.name, gold, f?.text ?: "", f?.margin ?: 0.0, smart)
            }
        }
        for (r in rows) println("MSET {\"group\":\"${esc(r.group)}\",\"text\":\"${esc(r.text)}\",\"label\":\"${r.label}\",\"gold\":\"${esc(r.gold)}\",\"tag\":\"${esc(r.tag)}\",\"margin\":${r.margin},\"smart\":\"${esc(r.smart)}\"}")
        val withGold = rows.filter { it.gold.isNotEmpty() }
        val noGold = rows.filter { it.gold.isEmpty() }
        println("MSETSUM rows=${rows.size} banks=${groups.size} withGold=${withGold.size} noGold=${noGold.size}")
        fun ok(r: Row, x: String) = x.isNotEmpty() && x.trim().equals(r.gold.trim(), ignoreCase = true)
        for (th in listOf(0.0, 5.0, 10.0, 15.0, 20.0, 30.0, 40.0)) {
            val ans = withGold.filter { it.tag.isNotEmpty() && it.margin > th }
            val right = ans.count { ok(it, it.tag) }
            val spurious = noGold.count { it.tag.isNotEmpty() && it.margin > th }
            println("MSETSUM tagger margin>$th answered=${ans.size} right=${right} wrong=${ans.size - right} unanswered=${withGold.size - ans.size} spuriousOnNoMerchantRows=$spurious/${noGold.size}")
        }
        // The same answers after the clean-up the app would apply (cut at sentence end / card digits, name cleaning, must be in the text).
        fun cleaned(r: Row) = if (r.tag.isEmpty()) "" else (MessageIntel.cleanSpan(r.text, r.tag) ?: "")
        for (th in listOf(0.0, 10.0, 15.0, 20.0, 30.0, 40.0)) {
            val ans = withGold.filter { cleaned(it).isNotEmpty() && it.margin > th }
            val right = ans.count { ok(it, cleaned(it)) }
            val spurious = noGold.count { cleaned(it).isNotEmpty() && it.margin > th }
            println("MSETSUM taggerCleaned margin>$th answered=${ans.size} right=$right wrong=${ans.size - right} unanswered=${withGold.size - ans.size} spuriousOnNoMerchantRows=$spurious/${noGold.size}")
        }
        // What the fallback would add where the patterns found nothing (the only place it is used).
        val gap = withGold.filter { it.smart.isEmpty() }
        println("MSETSUM patternsFoundNothing=${gap.size}; of those the cleaned tagger (margin>${MessageIntel.MERCHANT_FILL_MARGIN}) answers ${gap.count { cleaned(it).isNotEmpty() && it.margin > MessageIntel.MERCHANT_FILL_MARGIN }} and is right on ${gap.count { cleaned(it).isNotEmpty() && it.margin > MessageIntel.MERCHANT_FILL_MARGIN && ok(it, cleaned(it)) }}")
        println("MSETSUM wrongCleaned examples: " + withGold.filter { cleaned(it).isNotEmpty() && it.margin > 0 && !ok(it, cleaned(it)) }.take(14).joinToString(" || ") { "gold='${it.gold}' got='${cleaned(it)}' m=${oneDecimal(it.margin)}" })
        val sa = withGold.filter { it.smart.isNotEmpty() }
        println("MSETSUM patterns answered=${sa.size} right=${sa.count { ok(it, it.smart) }} wrong=${sa.count { !ok(it, it.smart) }} unanswered=${withGold.size - sa.size}")
        val comb = withGold.filter { it.smart.isNotEmpty() || it.tag.isNotEmpty() }
        println("MSETSUM wrongTagger examples: " + withGold.filter { it.tag.isNotEmpty() && it.margin > 0 && !ok(it, it.tag) }.take(12).joinToString(" || ") { "gold='${it.gold}' tag='${it.tag}' m=${oneDecimal(it.margin)}" })
    }
}

/** One decimal place without String.format (not available in common Kotlin / iOS). */
private fun oneDecimal(v: Double): String {
    val t = kotlin.math.round(v * 10).toLong()
    val neg = t < 0
    val a = kotlin.math.abs(t)
    return (if (neg) "-" else "") + (a / 10) + "." + (a % 10)
}
