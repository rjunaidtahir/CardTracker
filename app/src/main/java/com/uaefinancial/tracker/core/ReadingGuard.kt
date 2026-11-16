package com.uaefinancial.tracker.core

/**
 * Decides whether a re-read of a message changed what the app already holds for it in a way that matters.
 * A "material" change is one that moves money figures: a different type (debit vs credit), amount or currency,
 * or a reading that existed and no longer does. Merchant names and card links may improve freely.
 * Pure logic so it can be unit-tested without a database.
 */
object ReadingGuard {
    /** One stored reading of a message, reduced to the fields that matter. */
    data class Line(val kind: String, val type: String, val amountMinor: Long, val currency: String) {
        fun text() = "$kind|$type|$amountMinor|$currency"
    }

    /** True when [after] is not the same set of readings as [before] and [before] was not empty. */
    fun isMaterial(before: List<Line>, after: List<Line>): Boolean {
        if (before.isEmpty()) return false
        return before.map { it.text() }.sorted() != after.map { it.text() }.sorted()
    }

    /** A short human description for the Needs review card. */
    fun describe(lines: List<Line>): String =
        if (lines.isEmpty()) "no transaction"
        else lines.joinToString("; ") { l ->
            val what = if (l.kind == "STATEMENT") "statement" else l.type.lowercase()
            "$what ${l.currency} ${l.amountMinor / 100}.${(l.amountMinor % 100).toString().padStart(2, '0')}"
        }
}
