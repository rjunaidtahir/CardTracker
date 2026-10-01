package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.TxnType

/**
 * What a credit card's "available limit" should show.
 *
 * Two kinds of figure exist: the bank's available limit in an SMS, and the one printed on a statement (true on the
 * statement date, and getting older every day). The rule is to start from the newest of them:
 *  - an SMS figure on or after the statement date is the bank's own latest word, so it is used as it is;
 *  - when the statement is newer than any SMS figure, start from the statement and move it by every transaction after
 *    the statement date: purchases take it down; payments and refunds put it back.
 *
 * Dates are epoch days so this works the same on Android and iPhone.
 */
object AvailableLimit {
    /** An available-limit figure and the day it was true. */
    data class Figure(val minor: Long, val epochDay: Long)

    /** A transaction on the card, reduced to what the arithmetic needs. [amountAedMinor] is in the card's (AED) limit currency. */
    data class Move(val epochDay: Long, val type: TxnType, val amountAedMinor: Long?)

    enum class Source { SMS, STATEMENT }

    /** [moves] is how many transactions after the statement were applied (always 0 for [Source.SMS]). */
    data class Result(val minor: Long, val source: Source, val asOfEpochDay: Long, val moves: Int)

    fun resolve(sms: Figure?, statement: Figure?, transactions: List<Move>, limitMinor: Long? = null): Result? {
        if (sms == null && statement == null) return null
        if (statement == null || (sms != null && sms.epochDay >= statement.epochDay)) {
            return Result(sms!!.minor, Source.SMS, sms.epochDay, 0)
        }
        var value = statement.minor
        var applied = 0
        for (t in transactions) {
            if (t.epochDay <= statement.epochDay) continue
            val amount = t.amountAedMinor ?: continue
            val delta = when (t.type) {
                TxnType.PURCHASE -> -amount
                TxnType.REFUND, TxnType.PAYMENT -> amount
                else -> continue
            }
            value += delta
            applied++
        }
        value = value.coerceAtLeast(0L)
        if (limitMinor != null && limitMinor > 0) value = value.coerceAtMost(limitMinor)
        return Result(value, Source.STATEMENT, statement.epochDay, applied)
    }
}
