package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.TxnType

/**
 * The names-only report: a shop name the app could not file (it landed in Other). Only the name leaves the phone: no
 * amount, date, card, bank or message text. Only card purchases and refunds are reported (transfers and generic
 * account debits can carry a person's name), and a name that holds a long digit run, an email or a link is dropped.
 */
object MerchantReport {
    private val placeholders = setOf("card purchase", "account debit", "unknown", "unnamed", "purchase", "cashback")

    /** The name to report, tidied (upper case, single spaces), or null when it must not be sent. */
    fun clean(merchant: String, type: TxnType): String? {
        if (type != TxnType.PURCHASE && type != TxnType.REFUND) return null
        val name = merchant.trim().replace(Regex("\\s+"), " ").uppercase()
        if (name.length !in 3..40) return null
        if (name.lowercase() in placeholders) return null
        if (name.startsWith("FAB REWARDS")) return null
        if (name.contains('@') || name.contains("HTTP") || name.contains("WWW.")) return null
        if (Regex("\\d{4,}").containsMatchIn(name.replace(Regex("[\\s,.-]"), ""))) return null
        if (name.count { it.isLetter() } < 3) return null
        if (name.any { it < ' ' || it == '|' }) return null
        return name
    }
}
