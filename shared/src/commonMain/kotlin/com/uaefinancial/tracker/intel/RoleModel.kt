package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.ParsedStatement
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The role reader. Code finds every amount and date in a message; this small model only says what each one IS
 * (statement total, minimum due, due date, purchase amount, balance …) from the few words around it, so a new wording
 * such as "We'll charge a minimum amount of AED 127.78 … on 30 Jun" is understood without a rule for that bank.
 * It stores no model file: it learns from the sample messages at start-up (a few milliseconds).
 */
internal object RoleFeatures {
    private val word = Regex("""\p{L}+|\d+(?:[.,]\d+)*|[.!?;]\s""")

    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (m in word.findAll(s.lowercase())) {
            val w = m.value
            when {
                w[0].isDigit() -> if (out.lastOrNull() != "<n>") out += "<n>"
                w[0].isLetter() -> if (w.length >= 2 && w !in CURRENCY_LIKE) out += w
                else -> out += "|"
            }
        }
        return out
    }

    private val CURRENCY_LIKE = setOf("aed", "usd", "eur", "gbp", "inr", "pkr", "sar", "dhs", "dh")

    /** Words before and after one amount or date (within its sentence), plus whether it is a date and its order among its kind. */
    fun of(text: String, start: Int, end: Int, isDate: Boolean, ordinal: Int): List<String> {
        val before = tokens(text.substring(max(0, start - 140), start)).let { t ->
            val cut = t.lastIndexOf("|"); if (cut >= 0) t.subList(cut + 1, t.size) else t
        }
        val after = tokens(text.substring(end, minOf(text.length, end + 60))).let { t ->
            val cut = t.indexOf("|"); if (cut >= 0) t.subList(0, cut) else t
        }
        val f = LinkedHashSet<String>()
        f += if (isDate) "kind:date" else "kind:amount"
        f += "ord:" + minOf(ordinal, 3)
        val b = before.takeLast(8).reversed()          // b[0] is the word right before
        for ((i, w) in b.withIndex()) {
            if (i < 3) f += "b${i + 1}:$w"
            f += "bw:$w"
        }
        if (b.size >= 2) f += "bb:${b[1]}_${b[0]}"
        if (b.size >= 3) f += "bbb:${b[2]}_${b[1]}_${b[0]}"
        if (b.isEmpty()) f += "b:none"
        val a = after.take(4)
        for ((i, w) in a.withIndex()) {
            if (i < 2) f += "a${i + 1}:$w"
            f += "aw:$w"
        }
        if (a.size >= 2) f += "aa:${a[0]}_${a[1]}"
        return f.toList()
    }
}

/** A small multinomial Naive Bayes over word features with plain text labels. */
internal class RoleBayes(private val alpha: Double = 0.4) {
    private val labelCount = HashMap<String, Int>()
    private val featureCount = HashMap<String, HashMap<String, Int>>()
    private val labelTotal = HashMap<String, Int>()
    private val vocab = HashSet<String>()
    private var docs = 0

    val trained: Boolean get() = docs > 0

    fun train(rows: List<Pair<List<String>, String>>) {
        for ((features, label) in rows) {
            docs++
            labelCount[label] = (labelCount[label] ?: 0) + 1
            val m = featureCount.getOrPut(label) { HashMap() }
            for (f in features) {
                m[f] = (m[f] ?: 0) + 1
                labelTotal[label] = (labelTotal[label] ?: 0) + 1
                vocab += f
            }
        }
    }

    fun predict(features: List<String>): Map<String, Double> {
        if (!trained) return emptyMap()
        val known = features.filter { it in vocab }
        val v = vocab.size.toDouble()
        val scores = HashMap<String, Double>()
        for ((label, n) in labelCount) {
            val total = (labelTotal[label] ?: 0).toDouble()
            val m = featureCount[label]
            var s = ln(n.toDouble() / docs)
            for (f in known) s += ln(((m?.get(f) ?: 0) + alpha) / (total + alpha * v))
            scores[label] = s
        }
        val top = scores.values.max()
        val exps = scores.mapValues { exp(it.value - top) }
        val sum = exps.values.sum()
        return exps.mapValues { it.value / sum }
    }
}

/** What the role model says one amount or date is. */
internal data class RoleGuess(val role: String, val p: Double)

internal class RoleModel(samples: List<RoleCorpus.Sample>) {
    private val amount = RoleBayes()
    private val date = RoleBayes()

    init {
        val a = ArrayList<Pair<List<String>, String>>(); val d = ArrayList<Pair<List<String>, String>>()
        for (s in samples) for (m in s.mentions) (if (m.isDate) d else a) += m.features to m.role
        amount.train(a); date.train(d)
    }

    fun guess(text: String, spans: List<SmartParser.RoleSpan>): List<RoleGuess> {
        var ia = 0; var id = 0
        return spans.map { sp ->
            val ord = if (sp.isDate) id++ else ia++
            val p = (if (sp.isDate) date else amount).predict(RoleFeatures.of(text, sp.start, sp.end, sp.isDate, ord))
            p.maxByOrNull { it.value }?.let { RoleGuess(it.key, it.value) } ?: RoleGuess("OTHER", 0.0)
        }
    }
}

/**
 * Reads a statement / payment-due notice no rule or keyword reader could read: it needs a statement amount and a due date,
 * each labelled with confidence at least [CONFIDENT]. Anything less returns null (the message stays in Needs review).
 * Amounts and dates are the ones code found in the text; the model never writes a number.
 */
internal object RoleReader {
    const val CONFIDENT = 0.90

    private val model: RoleModel by lazy { RoleModel(RoleCorpus.samples) }

    fun warmUp() { model }

    fun readStatement(bank: String, text: String, zone: Int = SmsParser.UAE_ZONE, with: RoleModel = model): ParseResult.Statement? {
        if (SmsParser.isOtp(text)) return null
        val spans = SmartParser.roleSpans(text, zone)
        if (spans.size < 2) return null
        val guesses = with.guess(text, spans)
        fun best(role: String, date: Boolean): Pair<SmartParser.RoleSpan, Double>? =
            spans.indices.filter { spans[it].isDate == date && guesses[it].role == role && guesses[it].p >= CONFIDENT }
                .maxByOrNull { guesses[it].p }?.let { spans[it] to guesses[it].p }
        val total = best("TOTAL", false) ?: return null
        val due = best("DUE", true) ?: return null
        val min = best("MIN", false)
        val stmt = best("STMTDATE", true)
        val amt = total.first.amount ?: return null
        val cur = total.first.currency ?: return null
        val dueDate: CalendarDate = due.first.date ?: return null
        return ParseResult.Statement(
            ParsedStatement(
                bank = bank, cardLast4 = SmartParser.cardLast4s(text).firstOrNull(), cardType = CardType.CREDIT,
                statementBalance = amt, minimumDue = min?.first?.amount, currency = cur, dueDate = dueDate,
                statementDate = stmt?.first?.date, auto = true,
            ),
            "role-statement",
        )
    }
}

/** Labelled amounts and dates: the answer key for the role model. */
internal object RoleCorpus {
    class Mention(val features: List<String>, val role: String, val isDate: Boolean)
    class Sample(val group: String, val body: String, val mentions: List<Mention>, val hasStatement: Boolean)

    /**
     * Messages whose roles are known from a verified rule's own captured fields (AMOUNT→TXN, AVAIL, TOTAL, MIN, DUE,
     * STMTDATE, DATE→TXNDATE); an amount or date the rule did not capture is OTHER. Plus a few hand-labelled real messages.
     */
    val samples: List<Sample> by lazy { build() }

    private val map = mapOf("AMOUNT" to "TXN", "AVAIL" to "AVAIL", "TOTAL" to "TOTAL", "MIN" to "MIN", "DUE" to "DUE", "STMTDATE" to "STMTDATE", "DATE" to "TXNDATE")

    private fun label(text: String, group: String, gold: List<SmsParser.GoldSpan>): Sample {
        val spans = SmartParser.roleSpans(text)
        var ia = 0; var id = 0
        val ms = spans.map { sp ->
            val g = gold.firstOrNull { it.start < sp.end && sp.start < it.end && (it.role in DATES) == sp.isDate }
            val role = g?.let { map[it.role] } ?: "OTHER"
            RoleCorpusMention.of(text, sp, if (sp.isDate) id++ else ia++, role)
        }
        return Sample(group, text, ms, gold.any { it.role == "DUE" })
    }

    private val DATES = setOf("DUE", "STMTDATE", "DATE")

    private fun build(): List<Sample> {
        val out = ArrayList<Sample>()
        for ((sender, body) in CorpusData.raw) {
            if (sender.isEmpty()) continue
            val (text, gold) = runCatching { SmsParser.goldSpans(sender, body) }.getOrNull() ?: continue
            out += label(text, sender, gold)
        }
        for ((group, body, marks) in HAND) {
            val text = SmsParser.normalizeBody(body)
            val spans = SmartParser.roleSpans(text)
            var ia = 0; var id = 0
            val ms = spans.map { sp ->
                val role = marks.entries.firstOrNull { text.substring(sp.start, sp.end).contains(it.key) }?.value ?: "OTHER"
                RoleCorpusMention.of(text, sp, if (sp.isDate) id++ else ia++, role)
            }
            out += Sample(group, text, ms, marks.values.contains("DUE"))
        }
        return out
    }

    /** Real messages no built-in rule covers, labelled by hand (marker text → role). */
    private val HAND: List<Triple<String, String, Map<String, String>>> = listOf(
        Triple("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.",
            mapOf("9.00" to "TXN", "2488.94" to "AVAIL")),
        Triple("Tabby", "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app.",
            mapOf("511.06" to "TOTAL", "127.78" to "MIN", "30 Jun" to "DUE")),
    )
}

internal object RoleCorpusMention {
    fun of(text: String, sp: SmartParser.RoleSpan, ordinal: Int, role: String) =
        RoleCorpus.Mention(RoleFeatures.of(text, sp.start, sp.end, sp.isDate, ordinal), role, sp.isDate)
}
