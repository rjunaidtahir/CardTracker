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
    private val transactionVerb = Regex(
        """\b(?:debited|credited|spent|purchase[d]?|withdrawn|withdrawal|used\s+for|paid|transfer(?:red)?|deducted|refund(?:ed)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Words that mark bills, telecom and retail notices, orders, offers and reminders: never a bank transaction from an unknown sender. */
    private val nonBankWords = Regex(
        """\b(?:bill|roaming|bundle|plans?|minutes?|calls?|data|sim|recharge|reload(?:ing)?|top[\s-]?up|offers?|vouchers?|rewards?|smiles|points|orders?|deliver(?:y|ed)|subscri(?:be|ption)|packages?|promo(?:tion)?|discount|coupon|prize|win|due|invoice|collected|shipment)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val cardOrAccountRef = Regex(
        """\b(?:card|a/c|acct|account|ac)\b[^.\n]{0,25}?(?:x{2,}|\*{2,}|\.{2,})?[\d\-*]{0,12}\d{3,4}\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun namesCardOrAccount(text: String): Boolean =
        runCatching { SmartParser.cardLast4s(text).isNotEmpty() }.getOrDefault(false) || cardOrAccountRef.containsMatchIn(text)

    /** Reason text of a message hidden because it is clearly not money (see [clearlyNotMoney]). */
    const val HIDDEN_NOTICE = "Notice or promotion from a sender you added"

    /** "Dr." / "Cr." as used by Indian bank alerts ("Rs 100 Dr. from A/C ... and Cr. to ..."): a debit or credit mark is a money verb. */
    private val debitCreditMark = Regex("""\b(?:Dr|Cr)\.(?=\s|$)""", RegexOption.IGNORE_CASE)

    /**
     * True only for a message that is clearly not a money event: it carries a promotion / bill / telecom / order word and
     * no money verb at all. Used for a sender the user added by hand, so its promotions do not clutter Needs review.
     * Anything with a money verb (debited, credited, spent, used for, paid, transfer, refund ...) is NOT clear: it stays.
     */
    fun clearlyNotMoney(normalizedText: String): Boolean =
        nonBankWords.containsMatchIn(normalizedText) && !transactionVerb.containsMatchIn(normalizedText) &&
            !debitCreditMark.containsMatchIn(normalizedText)

    fun screen(sender: String?, body: String, receivedAt: Long): Screened? {
        val text = SmsParser.normalizeBody(body)
        if (text.isBlank()) return null
        if (!SmsParser.looksFinancial(text)) return null // cheapest test first: most messages stop here
        if (SmsParser.isOtp(text)) return null
        val addr = sender?.trim().orEmpty()
        val named = addr.any { it.isLetter() } || SmsParser.isShortCode(addr)
        if (!named && !SmartParser.looksLikeBankAlert(text)) return null

        // Precision first. A message from a sender we don't know must look like a real bank alert on its own:
        //  1. it names a card or account (digits or a masked number),
        //  2. it says money was spent, debited, credited, paid or transferred,
        //  3. and it is not a bill, a telecom or retail notice, an order or an offer.
        if (nonBankWords.containsMatchIn(text)) return null
        if (!transactionVerb.containsMatchIn(text)) return null
        // A sender that is a known bank or card company ("Tabby") needs no card number in the text: the name vouches for it.
        val knownInstitution = GlobalBanks.match(addr, null, null) != null
        if (!knownInstitution && !namesCardOrAccount(text)) return null

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
