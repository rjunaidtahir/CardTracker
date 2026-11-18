package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** What a message is, for the on-device second opinion. */
enum class Label {
    PURCHASE, REFUND, PAYMENT, TRANSFER_OUT, TRANSFER_IN, STATEMENT, NOT_TXN;

    /** Money direction: -1 out, +1 in, 0 none. Used to compare readings that differ only in kind (purchase vs transfer out). */
    val direction: Int get() = when (this) {
        PURCHASE, TRANSFER_OUT, PAYMENT -> -1
        REFUND, TRANSFER_IN -> 1
        STATEMENT, NOT_TXN -> 0
    }

    fun toTxnType(): TxnType? = when (this) {
        PURCHASE -> TxnType.PURCHASE
        REFUND -> TxnType.REFUND
        PAYMENT -> TxnType.PAYMENT
        TRANSFER_OUT -> TxnType.TRANSFER_OUT
        TRANSFER_IN -> TxnType.TRANSFER_IN
        else -> null
    }

    companion object {
        fun of(t: TxnType): Label = when (t) {
            TxnType.PURCHASE -> PURCHASE
            TxnType.REFUND -> REFUND
            TxnType.PAYMENT -> PAYMENT
            TxnType.TRANSFER_OUT -> TRANSFER_OUT
            TxnType.TRANSFER_IN -> TRANSFER_IN
        }
    }
}

/** The words (and word pairs) of a message, with every number and card mask replaced, so the model learns wording, not figures. */
internal object Features {
    private val word = Regex("""(?<![\p{L}])[x*•#]{2,}(?![\p{L}])|\p{L}+|\d+(?:[.,]\d+)*""")

    fun tokens(body: String): List<String> {
        val text = SmsParser.normalizeBody(body).lowercase()
        val out = ArrayList<String>()
        for (m in word.findAll(text)) {
            val w = m.value
            when {
                w[0].isDigit() -> if (out.lastOrNull() != "<n>") out += "<n>"
                !w[0].isLetter() || (w.length >= 2 && w.all { it == 'x' || it == '*' || it == '•' || it == '#' }) -> out += "<mask>"
                w.length >= 2 -> out += w
            }
        }
        return out
    }

    fun of(body: String): List<String> {
        val toks = tokens(body)
        val f = LinkedHashSet<String>()
        f.addAll(toks)
        for (i in 0 until toks.size - 1) f += toks[i] + " " + toks[i + 1]
        // Which kinds of amount the message has (a message with only a balance is not a purchase).
        val roles = runCatching { SmartParser.amountMentions(SmsParser.normalizeBody(body)) }.getOrDefault(emptyList())
        for (r in roles.map { it.role }.distinct()) f += "role:$r"
        f += "n_txn:" + minOf(3, roles.count { it.role == "TXN" })
        return f.toList()
    }
}

/** A small multinomial Naive Bayes over word features. Trains in milliseconds on a few hundred messages. */
internal class NaiveBayes(private val alpha: Double = 0.5) {
    private val labelCount = HashMap<Label, Int>()
    private val featureCount = HashMap<Label, HashMap<String, Int>>()
    private val labelFeatureTotal = HashMap<Label, Int>()
    private val vocab = HashSet<String>()
    private var docs = 0

    val trained: Boolean get() = docs > 0

    fun train(rows: List<Pair<List<String>, Label>>) {
        for ((features, label) in rows) {
            docs++
            labelCount[label] = (labelCount[label] ?: 0) + 1
            val m = featureCount.getOrPut(label) { HashMap() }
            for (f in features) {
                m[f] = (m[f] ?: 0) + 1
                labelFeatureTotal[label] = (labelFeatureTotal[label] ?: 0) + 1
                vocab += f
            }
        }
    }

    /** Probability of each label (sums to 1). Long messages are damped so the probabilities are not extreme. */
    fun predict(features: List<String>): Map<Label, Double> {
        if (!trained) return emptyMap()
        val known = features.filter { it in vocab }
        val damp = DAMP / max(DAMP, known.size.toDouble())
        val v = vocab.size.toDouble()
        val scores = HashMap<Label, Double>()
        for (label in Label.entries) {
            val n = labelCount[label] ?: 0
            if (n == 0) continue
            val total = (labelFeatureTotal[label] ?: 0).toDouble()
            val m = featureCount[label]
            var s = 0.0
            for (f in known) s += ln(((m?.get(f) ?: 0) + alpha) / (total + alpha * v))
            scores[label] = ln(n.toDouble() / docs) + s * damp
        }
        val top = scores.values.max()
        val exps = scores.mapValues { exp(it.value - top) }
        val sum = exps.values.sum()
        return exps.mapValues { it.value / sum }
    }

    private companion object { const val DAMP = 12.0 }
}
