package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal

/**
 * "Apply to similar messages": when you fix a message in Needs review and ask the app to learn it, later (and
 * earlier, still unread) messages from the same bank with the same wording are read the same way.
 *
 * A learned format is built from the message you fixed and your reading of it. Two messages are "similar" when:
 *  - they come from the same bank,
 *  - they mention the same number of amounts,
 *  - their words, with amounts and numbers masked, are at least [THRESHOLD] alike (longest common subsequence), and
 *  - they use exactly the same direction words (credited / debited / refund / ...), so a fix for money coming in is
 *    never applied to a message about money going out.
 *
 * From a similar message the app takes the amount (the one in the same position as in the message you fixed), the
 * card/account number if the message has one, and the merchant when your description was words from the message
 * (e.g. "Noon" in "... at Noon with card ..."); otherwise your description is used as is.
 *
 * Nothing here is stored by the engine: the apps keep your fixes and hand them over with [setAll] at start-up.
 */
object LearnedFormats {

    /** How alike the wording must be (0..1). */
    const val THRESHOLD = 0.85

    /** Messages shorter than this (in words) are too generic to learn from. */
    private const val MIN_TOKENS = 4

    /** Your fix of one message: what the apps pass in. [type] null = "not a transaction". */
    data class Source(
        val key: String,
        val bank: String,
        val body: String,
        val type: TxnType?,
        val amount: Decimal?,
        val currency: String,
        val merchant: String,
        val cardLast4: String?,
        val cardType: CardType,
    )

    internal data class Format(
        val source: Source,
        val tokens: List<String>,
        val polarity: Set<String>,
        val moneyCount: Int,
        val moneyIndex: Int,
        /** Words right before / after your description in the fixed message, when it came from the message. */
        val merchantBefore: List<String>?,
        val merchantAfter: String?,
        /** The card/account number you gave was in the message (so take the new message's own number). */
        val last4FromText: Boolean,
    )

    @kotlin.concurrent.Volatile
    private var formats: List<Format> = emptyList()

    /** Replaces all learned formats. Sources that can't be learned from (too short, no amount) are skipped. */
    fun setAll(sources: List<Source>) {
        formats = sources.mapNotNull { build(it) }
    }

    /** How many formats are active (for tests and diagnostics). */
    fun count(): Int = formats.size

    /** True when [source] can be learned from (the Fix form only offers "apply to similar" then). */
    fun canLearn(source: Source): Boolean = build(source) != null

    // ------------------------------------------------------------------ tokens

    private val money: Regex by lazy {
        val cur = SmsParser.currencyAlternation
        val amt = SmsParser.AMT
        Regex("""(?<![A-Za-z])(?<c1>$cur)\.?\s?(?<a1>-?\s?$amt)|(?<![A-Za-z0-9*•#.,])(?<a2>-?$amt)\s?(?<c2>$cur)(?![A-Za-z])""")
    }

    private data class Money(val amount: Decimal, val currency: String)

    private data class Tokens(val words: List<String>, val money: List<Money>)

    private const val MONEY = "\u0000m"
    private const val NUMBER = "\u0000n"

    private fun tokenize(text: String): Tokens {
        val found = mutableListOf<Money>()
        val masked = money.replace(text) { m ->
            val rawAmt = (m.groups["a1"]?.value ?: m.groups["a2"]?.value)?.replace(" ", "")
            val cur = m.groups["c1"]?.value ?: m.groups["c2"]?.value
            val amount = rawAmt?.let { runCatching { SmsParser.parseAmount(it) }.getOrNull() }
            if (amount != null && cur != null) found += Money(amount.abs(), SmsParser.normalizeCurrency(cur))
            " $MONEY "
        }
        return Tokens(words(masked), found)
    }

    /** Words (any alphabet, lower-cased), numbers as one placeholder, amounts as another. No regex: same on every platform. */
    private fun words(masked: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var kind = 0 // 1 = letters, 2 = digits
        fun flush() {
            if (sb.isNotEmpty()) out.add(if (kind == 2) NUMBER else sb.toString())
            sb.clear()
            kind = 0
        }
        var i = 0
        while (i < masked.length) {
            val c = masked[i]
            when {
                c == '\u0000' -> { flush(); out += MONEY; i += 2; continue }
                c.isLetter() -> { if (kind != 1) flush(); kind = 1; sb.append(c.lowercaseChar()) }
                c.isDigit() -> { if (kind != 2) flush(); kind = 2; sb.append(c) }
                else -> flush()
            }
            i++
        }
        flush()
        return out
    }

    /** Words that say which way the money went; similar messages must use exactly the same ones. */
    private val polarityWords = setOf(
        "credited", "credit", "debited", "debit", "received", "receive", "sent", "send", "refund", "refunded", "reversed",
        "reversal", "cashback", "paid", "pay", "payment", "repayment", "withdrawn", "withdrawal", "spent", "purchase",
        "declined", "failed", "unsuccessful", "charged", "charge", "fee", "earned", "interest", "profit", "dividend",
        "deposit", "deposited", "transfer", "transferred", "salary", "added", "deducted", "used", "atm", "cash",
        "to", "from", "not", "cancelled", "canceled",
    )

    private fun polarity(words: List<String>): Set<String> = words.filter { it in polarityWords }.toSet()

    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Longest common subsequence (messages are short).
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return 2.0 * dp[a.size][b.size] / (a.size + b.size)
    }

    // ------------------------------------------------------------------ build

    private val rawWords = Regex("""\S+""")

    private val last4Ref = Regex(
        """(?:[X*x•#]{2,}[\s-]?|\b(?:card|a/?c|acc(?:oun)?t|account|acct)\.?\s*(?:no\.?|number|ending(?:\s+(?:with|in))?)?\s*[:#-]?\s*[X*x•#]*|\bending(?:\s+(?:with|in))?\s*[:#-]?\s*)(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun build(s: Source): Format? {
        val text = SmsParser.normalizeBody(s.body)
        if (SmsParser.isOtp(text)) return null
        val t = tokenize(text)
        if (t.words.size < MIN_TOKENS) return null
        var moneyIndex = 0
        if (s.type != null) {
            if (t.money.isEmpty()) return null
            // The amount you entered, where it is in the message (else the first amount).
            val i = s.amount?.let { a -> t.money.indexOfFirst { it.amount.compareTo(a.abs()) == 0 } } ?: -1
            moneyIndex = if (i >= 0) i else 0
        }
        // Your description came from the message: remember the words around it.
        var before: List<String>? = null
        var after: String? = null
        val merchant = s.merchant.trim()
        if (s.type != null && merchant.length >= 2) {
            val idx = text.indexOf(merchant, ignoreCase = true)
            if (idx >= 0) {
                // Up to two plain words right before it ("at", "paid to"), never amounts or numbers.
                val left = rawWords.findAll(text.substring(0, idx)).map { it.value }.toList()
                    .takeLastWhile { w -> w.none { it.isDigit() } }.takeLast(2)
                val right = rawWords.find(text.substring(idx + merchant.length))?.value
                if (left.isNotEmpty()) {
                    before = left
                    after = right?.takeIf { r -> r.none { it.isDigit() } && money.find(r) == null }
                }
            }
        }
        val last4FromText = s.cardLast4 != null && last4Ref.findAll(text).any { it.groupValues[1] == s.cardLast4 }
        return Format(s, t.words, polarity(t.words), t.money.size, moneyIndex, before, after, last4FromText)
    }

    // ------------------------------------------------------------------ read

    /** Reads [text] (already normalized) like a message you fixed, or returns null when none is similar enough. */
    fun read(bank: String, text: String, receivedAt: Long): ParseResult? {
        val candidates = formats.filter { it.source.bank.equals(bank, ignoreCase = true) }
        if (candidates.isEmpty()) return null
        val t = tokenize(text)
        if (t.words.size < MIN_TOKENS) return null
        val pol = polarity(t.words)
        val best = candidates
            .filter { it.moneyCount == t.money.size && it.polarity == pol }
            .map { it to similarity(it.tokens, t.words) }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first ?: return null
        val src = best.source
        val type = src.type ?: return ParseResult.Ignored(bank, "Like a message you marked as not a transaction")
        val m = t.money.getOrNull(best.moneyIndex) ?: return null
        if (m.amount.signum() <= 0) return null
        val ownLast4 = last4Ref.find(text)?.groupValues?.get(1)
        val last4 = when {
            ownLast4 != null -> ownLast4
            best.last4FromText -> null
            else -> src.cardLast4
        }
        val merchant = merchantFrom(best, text) ?: src.merchant.trim().ifEmpty { "Transaction" }
        return ParseResult.Transaction(
            ParsedTransaction(
                bank = bank,
                cardLast4 = last4,
                cardType = src.cardType,
                merchant = merchant,
                amount = m.amount,
                currency = m.currency,
                type = type,
                timestamp = receivedAt,
                dateFromSms = false,
                accountNotNamed = last4 == null && src.cardType == CardType.ACCOUNT,
            ),
            RULE_ID,
        )
    }

    private fun merchantFrom(f: Format, text: String): String? {
        val before = f.merchantBefore ?: return null
        val lead = before.joinToString("""\s+""") { Regex.escape(it) }
        val tail = f.merchantAfter?.let { """\s+${Regex.escape(it)}(?:\s|$)""" } ?: """(?:[.,;]|\s*$)"""
        val m = Regex("""(?:^|\s)$lead\s+(.+?)$tail""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val v = m.groupValues[1].trim().trimEnd(',', '.', ';', ':').trim()
        return v.takeIf { it.length in 2..50 && it.any { c -> c.isLetter() } && money.find(it) == null }
    }

    /** Rule id of transactions read this way. */
    const val RULE_ID = "learned-from-you"
}
