package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.ParsedTransaction
import com.uaefinancial.tracker.parser.TxnType

/**
 * Completeness check on what the generic reader found: which parts of a message fell back to a default instead of being
 * read. A default looks like a real answer ("Card purchase" looks like a merchant), so without this nothing would show
 * that the reading was incomplete. Only messages read by the generic reader are checked (a verified bank rule that names
 * no merchant is a known wording, not a gap). Nothing here changes a transaction; the result is a "Check this" mark and
 * a learning record (masked shape only).
 *
 * Kept in `shared/` so both apps check the same way.
 */
object ReadingGaps {
    /** The texts the generic reader writes when it found no merchant. */
    val placeholderMerchants = setOf("Card purchase", "Account debit")

    /** "merchant" and / or "card"; empty when nothing fell back or the message was not read by the generic reader. */
    fun of(t: ParsedTransaction): List<String> {
        if (!t.auto) return emptyList()
        return buildList {
            if (t.type == TxnType.PURCHASE && t.merchant in placeholderMerchants) add("merchant")
            if (t.cardLast4 == null && !t.accountNotNamed) add("card")
        }
    }

    /** The "Check this" text for [gaps] (as stored after "gap:"). */
    fun describe(gaps: String): String {
        val parts = gaps.split(",").filter { it.isNotBlank() }.map {
            when (it) {
                "merchant" -> "no merchant name was found in the message"
                "card" -> "no card or account number was found in the message"
                else -> it
            }
        }
        return "Incomplete reading: " + parts.joinToString(" and ") + ". Name it with Fix so it is counted and categorised right."
    }
}
