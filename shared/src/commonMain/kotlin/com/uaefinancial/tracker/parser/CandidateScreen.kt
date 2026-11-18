package com.uaefinancial.tracker.parser

/** How a message from a sender the app doesn't know yet looks to the generic reader. */
enum class CandidateKind {
    /** The generic reader can find a transaction or statement in it (amount, direction). Still goes to Needs review first. */
    RECOGNISABLE,

    /** It has an amount but the generic reader cannot make sense of it. Goes to Needs review as an unfamiliar format. */
    UNFAMILIAR,
}

data class Screened(
    val kind: CandidateKind,
    /** One line describing what the generic reader found ("Spent AED 45.00 at NOON"), only for [CandidateKind.RECOGNISABLE]. */
    val reading: String?,
    /** The masked wording of the message (no personal data), empty when it cannot be masked safely. Lets a verified format be matched later. */
    val shapeKey: String,
)

/**
 * First look at a message from a sender that is not one of your banks. Only a message that reads as a real transaction
 * (amount, currency, direction, not a one-time code) is kept (locally) for you to look at; everything else, including
 * promotions, bills and reminders that merely mention an amount, is dropped unseen. A message from a phone number must also read like a bank alert, so ordinary
 * chat about money ("can you send 50 dirhams") is not kept.
 *
 * Nothing here posts anything: a kept message is a candidate in Needs review until you confirm the sender.
 */
object CandidateScreen {
    fun screen(sender: String?, body: String, receivedAt: Long): Screened? {
        val text = SmsParser.normalizeBody(body)
        if (text.isBlank()) return null
        if (!SmsParser.looksFinancial(text)) return null // cheapest test first: most messages stop here
        if (SmsParser.isOtp(text)) return null
        val addr = sender?.trim().orEmpty()
        val named = addr.any { it.isLetter() } || SmsParser.isShortCode(addr)
        if (!named && !SmartParser.looksLikeBankAlert(text)) return null

        val read = runCatching { SmartParser.read(addr.ifEmpty { "Unknown" }, text, receivedAt) }.getOrNull()
        // Only a message the generic reader reads as an actual transaction is offered. Promos, bills, reminders and
        // anything that "has an amount" but fits no reading are dropped unseen, so Needs review stays small.
        if (read !is ParseResult.Transaction) return null
        val summary = summarise(read) ?: return null
        val shape = Masker.mask(text).let { if (it.safe) it.shape else "" }
        return Screened(CandidateKind.RECOGNISABLE, summary, shape)
    }

    private fun summarise(r: ParseResult?): String? = when (r) {
        is ParseResult.Transaction -> {
            val t = r.txn
            val what = when (t.type) {
                TxnType.PURCHASE -> "Spent"
                TxnType.REFUND -> "Refund of"
                TxnType.TRANSFER_IN -> "Money in:"
                TxnType.TRANSFER_OUT -> "Money out:"
                else -> t.type.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            val at = t.merchant.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty()
            "$what ${t.currency} ${t.amount}$at"
        }
        is ParseResult.Statement -> "Statement or payment due"
        else -> null
    }
}
