package com.uaefinancial.tracker.core

/**
 * Decides whether a re-read of a message changed what the app already holds for it. Any change to a financial
 * field is "material" and is held for review instead of being applied: type, direction, amount, currency, date and
 * time, bank, card or account, card last four digits, merchant. (Transaction status and reference are not stored by
 * the app yet; they join this list when the reader starts producing them.)
 * Pure logic so it can be unit-tested without a database.
 */
object ReadingGuard {
    /** One stored reading of a message, reduced to the fields that matter. */
    data class Line(
        val kind: String,
        val type: String,
        val amountMinor: Long,
        val currency: String,
        val timestamp: Long = 0,
        val bank: String = "",
        val cardKey: String = "",
        val last4: String = "",
        val merchant: String = "",
    ) {
        /** MONEY_IN, MONEY_OUT or STATEMENT; a change between these is a change of direction. */
        val direction: String get() = when (type) {
            "REFUND", "TRANSFER_IN" -> "IN"
            "STATEMENT" -> "NONE"
            else -> "OUT" // PURCHASE, PAYMENT (to a card), TRANSFER_OUT
        }
        fun text() = listOf(kind, type, direction, amountMinor, currency, timestamp, bank, cardKey, last4, merchant.trim().lowercase()).joinToString("|")
    }

    /** True when [after] is not the same set of readings as [before] and [before] was not empty. */
    fun isMaterial(before: List<Line>, after: List<Line>): Boolean {
        if (before.isEmpty()) return false
        return before.map { it.text() }.sorted() != after.map { it.text() }.sorted()
    }

    private fun core(l: Line) = listOf(l.kind, l.type, l.direction, l.amountMinor, l.currency, l.bank, l.cardKey, l.last4).joinToString("|")

    /**
     * True when the two readings agree on every money field (kind, type, direction, amount, currency, bank, card,
     * last four) and differ only in date/time and/or merchant text. Such a change is never put to the user: the
     * reading you already have is kept (see [keepOldSilently]).
     */
    fun isSoftOnly(before: List<Line>, after: List<Line>): Boolean {
        if (before.isEmpty() || before.size != after.size) return false
        return before.map { core(it) }.sorted() == after.map { core(it) }.sorted()
    }

    /** A soft change keeps the old reading, unless the old reading had no merchant at all (then the new one fills it in). */
    fun keepOldSilently(before: List<Line>, after: List<Line>): Boolean =
        isSoftOnly(before, after) && before.none { it.merchant.isBlank() }

    /**
     * 2.8 `applyKeyChangeFor` (spec 9): when the re-read changes ONLY the card or account (key and/or last four; date/time and
     * merchant may differ too) and every money field (kind, type, direction, amount, currency, bank) is equal, the pairs
     * (old card key -> new card key) that changed; null when anything else changed (then the change stays held as before).
     */
    fun keyChanges(before: List<Line>, after: List<Line>): List<Pair<String, String>>? {
        if (before.isEmpty() || before.size != after.size) return null
        fun money(l: Line) = listOf(l.kind, l.type, l.direction, l.amountMinor, l.currency, l.bank).joinToString("|")
        val b = before.sortedWith(compareBy({ money(it) }, { it.timestamp }))
        val a = after.sortedWith(compareBy({ money(it) }, { it.timestamp }))
        if (b.map { money(it) } != a.map { money(it) }) return null
        val pairs = b.zip(a).filter { (x, y) -> x.cardKey != y.cardKey || x.last4 != y.last4 }.map { (x, y) -> x.cardKey to y.cardKey }
        return pairs.takeIf { it.isNotEmpty() && it.all { (o, n) -> o.isNotBlank() && n.isNotBlank() } }
    }

    /** Names of the fields that differ (first reading of each side), for the review card. */
    fun changedFields(before: List<Line>, after: List<Line>): List<String> {
        val b = before.firstOrNull() ?: return emptyList()
        val a = after.firstOrNull() ?: return listOf("reading removed")
        if (before.size != after.size) return listOf("number of readings")
        val out = mutableListOf<String>()
        if (b.kind != a.kind) out += "kind"
        if (b.type != a.type) out += "type"
        if (b.direction != a.direction) out += "direction"
        if (b.amountMinor != a.amountMinor) out += "amount"
        if (b.currency != a.currency) out += "currency"
        if (b.timestamp != a.timestamp) out += "date and time"
        if (b.bank != a.bank) out += "bank"
        if (b.cardKey != a.cardKey) out += "card or account"
        if (b.last4 != a.last4) out += "card digits"
        if (!b.merchant.trim().equals(a.merchant.trim(), ignoreCase = true)) out += "merchant"
        return out
    }

    /**
     * True when a held card's "(changes: ...)" text names only date/time and/or merchant: the same kind of change that
     * 2.6.1 no longer puts to the user. Used to clear cards that were held before the update.
     */
    fun isSoftSummary(newSummary: String): Boolean {
        val list = Regex("""\(changes: ([^)]*)\)""").find(newSummary)?.groupValues?.get(1) ?: return false
        val parts = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it == "date and time" || it == "merchant" }
    }

    /** A short human description for the Needs review card. */
    fun describe(lines: List<Line>): String =
        if (lines.isEmpty()) "no transaction"
        else lines.joinToString("; ") { l ->
            val what = if (l.kind == "STATEMENT") "statement" else l.type.lowercase().replace('_', ' ')
            val who = if (l.merchant.isNotBlank()) " · ${l.merchant}" else ""
            val card = if (l.last4.isNotBlank()) " · ${l.bank} ${l.last4}" else ""
            "$what ${l.currency} ${l.amountMinor / 100}.${(l.amountMinor % 100).toString().padStart(2, '0')}$who$card"
        }
}
