package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.parser.SmsParser

/**
 * Finds the merchant words in a message: a small averaged-perceptron tagger (B = first merchant word, I = inside it,
 * O = other). It learns from messages whose merchant the rule reader already found, from the wording around it
 * ("at", "to", "for", position in the message), so it can pick out a merchant in a bank's wording the rules don't know.
 * It only proposes words that are in the message; nothing it says is posted without your confirmation.
 */
internal class MerchantTagger {
    data class Tok(val text: String, val start: Int, val end: Int)

    /** The merchant words, exactly as written in the message, and how far ahead of "no merchant" they scored. */
    data class Found(val text: String, val margin: Double)

    private val weights = HashMap<String, DoubleArray>()
    private val totals = HashMap<String, DoubleArray>()
    private var steps = 0L
    private var averaged = false

    val trained: Boolean get() = steps > 0

    fun train(samples: List<Pair<String, String>>, epochs: Int = 10) {
        data class Ex(val toks: List<Tok>, val tags: IntArray)
        val data = ArrayList<Ex>()
        for ((body, merchant) in samples) {
            val toks = tokenize(SmsParser.normalizeBody(body))
            val m = tokenize(merchant).map { it.text.lowercase() }
            if (m.isEmpty() || toks.size < m.size) continue
            val lower = toks.map { it.text.lowercase() }
            var at = -1
            for (i in 0..lower.size - m.size) if ((m.indices).all { lower[i + it] == m[it] }) { at = i; break }
            if (at < 0) continue
            val tags = IntArray(toks.size)
            tags[at] = B
            for (k in 1 until m.size) tags[at + k] = I
            data += Ex(toks, tags)
        }
        var seed = 12345L
        fun next(n: Int): Int { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % n).toInt() }
        val order = data.indices.toMutableList()
        repeat(epochs) {
            for (i in order.size - 1 downTo 1) { val j = next(i + 1); val t = order[i]; order[i] = order[j]; order[j] = t }
            for (idx in order) {
                val ex = data[idx]
                var prev = O
                for (i in ex.toks.indices) {
                    val f = features(ex.toks, i, prev)
                    val guess = best(f, prev)
                    val gold = ex.tags[i]
                    steps++
                    if (guess != gold) { update(f, gold, 1.0); update(f, guess, -1.0) }
                    prev = gold
                }
            }
        }
        // Average the weights over all steps: steadier than the last weights.
        for ((f, w) in weights) {
            val t = totals[f] ?: continue
            for (k in w.indices) w[k] = w[k] - t[k] / steps
        }
        averaged = true
    }

    private fun update(features: List<String>, tag: Int, delta: Double) {
        for (f in features) {
            val w = weights.getOrPut(f) { DoubleArray(3) }
            val t = totals.getOrPut(f) { DoubleArray(3) }
            w[tag] += delta
            t[tag] += steps * delta
        }
    }

    private fun scores(features: List<String>, prev: Int): DoubleArray {
        val s = DoubleArray(3)
        for (f in features) { val w = weights[f] ?: continue; for (k in 0..2) s[k] += w[k] }
        if (prev == O) s[I] -= 1000.0 // an I must follow a B or another I
        return s
    }

    private fun best(features: List<String>, prev: Int): Int {
        val s = scores(features, prev)
        var b = 0
        for (k in 1..2) if (s[k] > s[b]) b = k
        return b
    }

    /** The most likely merchant words in [body], or null when nothing stands out. */
    fun find(body: String): Found? {
        if (!trained) return null
        val text = SmsParser.normalizeBody(body)
        val toks = tokenize(text)
        var prev = O
        var bestStart = -1
        var bestEnd = -1
        var bestMargin = Double.NEGATIVE_INFINITY
        var curStart = -1
        var curMargin = 0.0
        for (i in toks.indices) {
            val s = scores(features(toks, i, prev), prev)
            var tag = 0
            for (k in 1..2) if (s[k] > s[tag]) tag = k
            when (tag) {
                B -> { // close the previous span, open a new one
                    if (curStart >= 0 && curMargin > bestMargin) { bestStart = curStart; bestEnd = i - 1; bestMargin = curMargin }
                    curStart = i; curMargin = s[B] - s[O]
                }
                I -> curMargin += s[I] - s[O]
                else -> {
                    if (curStart >= 0 && curMargin > bestMargin) { bestStart = curStart; bestEnd = i - 1; bestMargin = curMargin }
                    curStart = -1
                }
            }
            prev = tag
        }
        if (curStart >= 0 && curMargin > bestMargin) { bestStart = curStart; bestEnd = toks.size - 1; bestMargin = curMargin }
        if (bestStart < 0) return null
        val span = text.substring(toks[bestStart].start, toks[bestEnd].end).trim()
        return if (span.length < 2) null else Found(span, bestMargin)
    }

    private fun features(t: List<Tok>, i: Int, prev: Int): List<String> {
        val w = t[i].text
        val lw = w.lowercase()
        fun at(k: Int) = t.getOrNull(i + k)?.text?.lowercase() ?: if (i + k < 0) "<s>" else "</s>"
        val f = ArrayList<String>(16)
        f += "b"
        f += "w=$lw"
        f += "shape=${shape(w)}"
        f += "p1=${at(-1)}"
        f += "p2=${at(-2)}"
        f += "n1=${at(1)}"
        f += "n2=${at(2)}"
        f += "p1w=${at(-1)}|$lw"
        f += "p2p1=${at(-2)}|${at(-1)}"
        f += "tag=$prev"
        f += "tagp1=$prev|${at(-1)}"
        f += "pos=${(i * 6) / maxOf(1, t.size)}"
        if (lw in lexicon) f += "bankword"
        if (w.firstOrNull()?.isUpperCase() == true) f += "cap"
        if (w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) f += "caps"
        if (w.any { it.isDigit() }) f += "hasdigit"
        return f
    }

    private fun shape(w: String): String {
        val sb = StringBuilder()
        var last = ' '
        for (c in w) {
            val s = when { c.isUpperCase() -> 'X'; c.isLowerCase() -> 'x'; c.isDigit() -> 'd'; else -> c }
            if (s != last) sb.append(s)
            last = s
        }
        return sb.toString()
    }

    companion object {
        private const val O = 0
        private const val B = 1
        private const val I = 2
        private val lexicon = setOf(
            "aed", "usd", "card", "account", "ending", "avl", "avail", "available", "balance", "bal", "limit", "debited", "credited",
            "purchase", "spent", "used", "transaction", "txn", "payment", "transfer", "refund", "thank", "thanks", "dear", "customer",
            "your", "has", "been", "was", "is", "on", "with", "using", "visa", "mastercard", "debit", "credit",
        )

        private fun starts(c: Char) = c.isLetterOrDigit()
        private fun continues(c: Char) = c.isLetterOrDigit() || c in "&'.-/*"

        // A word starts with a letter or digit and runs on through letters, digits and & ' . - / *. Scanned by hand: the same
        // character-class regex failed to initialise on iPhone (Kotlin/Native), which silently switched the merchant tagger off there.
        fun tokenize(text: String): List<Tok> {
            val out = ArrayList<Tok>()
            var i = 0
            while (i < text.length) {
                if (!starts(text[i])) { i++; continue }
                var j = i + 1
                while (j < text.length && continues(text[j])) j++
                val t = text.substring(i, j).trimEnd('.', '-', '/', '*')
                if (t.isNotEmpty()) out.add(Tok(t, i, i + t.length))
                i = j
            }
            return out
        }
    }
}
