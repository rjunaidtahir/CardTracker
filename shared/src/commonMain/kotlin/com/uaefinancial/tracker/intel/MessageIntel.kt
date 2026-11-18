package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser

enum class Verdict { AGREE, DISAGREE, UNSURE }

/** What the second opinion thinks a message says. A suggestion only: it never creates or changes a record. */
data class Suggestion(
    val label: Label,
    val confidence: Double,
    val amount: Decimal?,
    val currency: String?,
    val merchant: String?,
    val last4: String?,
    val findings: List<Finding>,
    /** True when it passed every BLOCK check and may be shown as a ready-to-use prefill. */
    val usable: Boolean,
)

/**
 * Layers 3 and 4 of the engine (small classifier and merchant tagger), run beside the verified rules, never above them.
 * Code reads every number (SmartParser); the models only decide the kind and point at merchant words in the text.
 */
object MessageIntel {
    /** A model may only say "the rules look wrong" at or above this probability (measured: ~5% wrong at this level on unseen banks, ~23% at 0.80). */
    const val DISAGREE_AT = 0.999
    /** Below this the suggestion is not offered as a prefill. */
    const val USABLE_AT = 0.90

    private val nb: NaiveBayes by lazy {
        NaiveBayes().also { m -> m.train(Corpus.samples.map { Features.of(it.body) to it.label }) }
    }
    private val tagger: MerchantTagger by lazy {
        MerchantTagger().also { t ->
            t.train(Corpus.samples.filter { !it.merchant.isNullOrBlank() && it.label.toTxnType() != null }
                .map { it.body to it.merchant!! })
        }
    }

    /** Builds both models now (call from a background thread at start-up so the first message is not slow). */
    fun warmUp() { nb; tagger }

    fun opinion(body: String): Map<Label, Double> =
        runCatching { nb.predict(Features.of(body)) }.getOrDefault(emptyMap())

    fun top(body: String): Pair<Label, Double>? = opinion(body).maxByOrNull { it.value }?.toPair()

    /** Compares the classifier with what the rules concluded. DISAGREE only when it is clearly confident. */
    fun secondOpinion(body: String, ruleLabel: Label): Verdict {
        val (l, p) = top(body) ?: return Verdict.UNSURE
        return when {
            l == ruleLabel -> Verdict.AGREE
            p >= DISAGREE_AT -> Verdict.DISAGREE
            else -> Verdict.UNSURE
        }
    }

    /** A full reading for a message the rules could not settle, or a candidate from an unfamiliar sender. */
    fun suggest(sender: String, body: String, receivedAt: Long): Suggestion? {
        val text = SmsParser.normalizeBody(body)
        val (label, p) = top(text) ?: return null
        if (label == Label.NOT_TXN || label == Label.STATEMENT) return null
        val keyword = runCatching { SmartParser.suggest(sender.ifEmpty { "Unknown" }, text, receivedAt) }.getOrNull()
        val mentions = runCatching { SmartParser.amountMentions(text) }.getOrDefault(emptyList())
        val txn = mentions.firstOrNull { it.role == "TXN" && it.amount.signum() > 0 }
        val amount = keyword?.amount ?: txn?.amount
        val currency = keyword?.currency ?: txn?.currency
        val last4 = keyword?.cardLast4 ?: runCatching { SmartParser.cardLast4s(text).firstOrNull() }.getOrNull()
        val merchant = tagger.find(text)?.takeIf { it.margin > 0.0 }?.text
            ?: keyword?.merchant?.takeIf { it.isNotBlank() && text.contains(it, ignoreCase = true) }
        val findings = Validators.check(text, Proposal(label, amount, currency, merchant, last4))
        val usable = p >= USABLE_AT && !Validators.blocks(findings)
        return Suggestion(label, p, amount, currency, merchant, last4, findings, usable)
    }
}
