package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.parser.Currencies
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser

enum class Severity { BLOCK, WARN }

/** One thing a check found wrong or doubtful about a proposed reading. */
data class Finding(val code: String, val severity: Severity, val text: String)

/** A reading to be checked: what some layer thinks the message says. */
data class Proposal(
    val label: Label,
    val amount: Decimal?,
    val currency: String?,
    val merchant: String?,
    val last4: String?,
)

/**
 * Checks a proposed reading against the message itself. A BLOCK means the reading must not be offered as ready to use
 * (it goes to Needs review as a plain unread message); a WARN is shown beside it. Nothing here changes any record.
 */
object Validators {
    private val outWords = Regex(
        """\b(?:debited|debit|spent|purchase|purchased|withdrawn|withdrawal|paid|payment of|deducted|charged|sent|transferred|used for|used at|utilised|utilized)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val inWords = Regex(
        """\b(?:credited|credit of|received|refund(?:ed)?|deposited|deposit|reversed|reversal|added to|salary|cash\s*back)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val notDone = Regex(
        """\b(?:declined|failed|unsuccessful|rejected|insufficient|cancell?ed|not\s+(?:processed|successful|completed)|unable\s+to|could\s+not|couldn't|reminder|will be debited|will be charged|is due|are due|pending approval|request(?:ed)?\s+(?:to|for))\b""",
        RegexOption.IGNORE_CASE,
    )

    fun check(body: String, p: Proposal): List<Finding> {
        val text = SmsParser.normalizeBody(body)
        val out = ArrayList<Finding>()
        if (SmsParser.isOtp(text)) out += Finding("OTP", Severity.BLOCK, "This looks like a one-time code")
        val mentions = runCatching { SmartParser.amountMentions(text) }.getOrDefault(emptyList())
        val txn = p.label.toTxnType() != null

        if (txn) {
            val amount = p.amount
            if (amount == null || amount.signum() <= 0) {
                out += Finding("AMOUNT_MISSING", Severity.BLOCK, "No amount to use")
            } else {
                val same = mentions.filter { it.amount.abs().compareTo(amount.abs()) == 0 && (p.currency == null || it.currency == p.currency) }
                if (same.isEmpty()) out += Finding("AMOUNT_NOT_IN_TEXT", Severity.BLOCK, "The amount is not written in the message")
                else if (same.all { it.role != "TXN" }) out += Finding("AMOUNT_IS_BALANCE", Severity.BLOCK, "The amount looks like a balance, limit or fee, not the transaction")
                val others = mentions.filter { it.role == "TXN" && it.amount.abs().compareTo(amount.abs()) != 0 }
                if (others.isNotEmpty()) out += Finding("OTHER_AMOUNT", Severity.WARN, "The message has another amount too")
            }
            val cur = p.currency
            if (cur == null || !Currencies.isCode(cur)) out += Finding("CURRENCY_UNKNOWN", Severity.BLOCK, "The currency is not recognised")
            if (notDone.containsMatchIn(text)) out += Finding("NOT_DONE", Severity.BLOCK, "The wording says it was declined, failed, pending or only a reminder")

            val outHit = outWords.containsMatchIn(text)
            val inHit = inWords.containsMatchIn(text)
            val dir = p.label.direction
            if (dir < 0 && inHit && !outHit) out += Finding("DIRECTION", Severity.BLOCK, "The wording is about money coming in, not going out")
            else if (dir > 0 && outHit && !inHit) out += Finding("DIRECTION", Severity.BLOCK, "The wording is about money going out, not coming in")
            else if (outHit && inHit) out += Finding("DIRECTION_MIXED", Severity.WARN, "The message mentions both money in and money out")
            else if (!outHit && !inHit) out += Finding("DIRECTION_UNCLEAR", Severity.WARN, "No clear money-in or money-out word")
        }
        p.merchant?.takeIf { it.isNotBlank() }?.let { m ->
            if (!text.contains(m, ignoreCase = true)) out += Finding("MERCHANT_NOT_IN_TEXT", Severity.WARN, "The merchant words are not in the message")
        }
        p.last4?.takeIf { it.isNotBlank() }?.let { l ->
            if (!text.contains(l)) out += Finding("LAST4_NOT_IN_TEXT", Severity.WARN, "The card digits are not in the message")
        }
        return out
    }

    fun blocks(findings: List<Finding>): Boolean = findings.any { it.severity == Severity.BLOCK }
}
