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
                "merchant-guess" -> "the merchant name was filled in by the app's small on-device reader, not by a bank rule"
                "card" -> "no card or account number was found in the message"
                "direction-from-balance" -> "the message does not say whether money went in or out; the app worked it out from your balance before and after"
                "stmt-name" -> "the shop name saved from a statement PDF looks like a city and currency, not a shop"
                "unpaired" -> "this notice usually comes with a second message for the same money, which has not arrived yet"
                else -> it
            }
        }
        return "Incomplete reading: " + parts.joinToString(" and ") + ". Check it, and use Fix if it is wrong so it is counted and categorised right."
    }
}
