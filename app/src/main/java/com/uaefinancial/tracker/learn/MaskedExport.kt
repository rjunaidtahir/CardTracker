package com.uaefinancial.tracker.learn

/**
 * The "export masked messages for testing" file. Off unless you tap it; nothing is sent anywhere: the app writes a text
 * file where you choose, you can read it, and you decide whether to share it.
 *
 * Each line is one message SHAPE (the same masking the learning records use: amounts, card digits, dates, links,
 * names and merchants are gone) next to what this app decided it was. Identical lines are merged with a count.
 * Messages whose shape is not safe, and one-time-code messages, are left out and only counted.
 */
object MaskedExport {
    data class Row(
        val shape: String,
        val bank: String,
        /** PURCHASE, REFUND, PAYMENT, TRANSFER_OUT, TRANSFER_IN, STATEMENT, NOT_TXN or UNREAD */
        val kind: String,
        /** out, in, payment or none */
        val direction: String,
        /** CREDIT, DEBIT, ACCOUNT or unknown */
        val instrument: String,
        /** rule (verified bank rule), pack (signed rules file), learned, reader (generic reader), user-fix, none */
        val source: String,
        /** What the reader as it is now says about the REAL text (PURCHASE, REFUND, ..., STATEMENT, none, error). */
        val now: String = "",
        /** What the generic reader alone (a bank with no rules) says about the REAL text. */
        val generic: String = "",
        /** How the app stored the message: TRANSACTION, FAILED (Needs review), IGNORED, ... */
        val status: String = "",
        /** The app's own note for the message (digits replaced by #), e.g. why it is in Needs review. */
        val note: String = "",
    )

    fun directionOf(kind: String) = when (kind) {
        "PURCHASE", "TRANSFER_OUT" -> "out"
        "REFUND", "TRANSFER_IN" -> "in"
        "PAYMENT" -> "payment"
        else -> "none"
    }

    fun sourceOf(ruleId: String?, userFixed: Boolean): String = when {
        userFixed -> "user-fix"
        ruleId == null -> "none"
        ruleId.startsWith("pack:") -> "pack"
        ruleId == "learned-from-you" -> "learned"
        ruleId.contains("smart", ignoreCase = true) || ruleId.contains("role", ignoreCase = true) || ruleId.contains("auto", ignoreCase = true) -> "reader"
        else -> "rule"
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun render(rows: List<Row>, skippedUnsafe: Int, skippedOtp: Int): String {
        val grouped = rows.groupingBy { it }.eachCount()
        val sb = StringBuilder()
        sb.append("# Fils masked message export (for testing the message reader)\n")
        sb.append("# Each line: count, bank, kind, direction, instrument, source, masked shape, reader-now, generic-reader (computed on the real text on your phone), stored status, app note. Words are kept as written; replaced: amounts, card and account numbers, other digits, dates, times, links, emails and the merchant name.\n")
        sb.append("# source: rule = verified bank rule, pack = signed rules file, learned = from your fixes, reader = generic reader, user-fix = you corrected it.\n")
        sb.append("# Left out: $skippedUnsafe messages whose shape was not safe to export, $skippedOtp one-time-code messages.\n")
        grouped.entries.sortedWith(compareBy({ it.key.bank }, { it.key.kind }, { it.key.shape })).forEach { (r, n) ->
            sb.append("$n\t${clean(r.bank)}\t${r.kind}\t${r.direction}\t${r.instrument}\t${r.source}\t${clean(r.shape)}\t${r.now}\t${r.generic}\t${r.status}\t${clean(r.note)}\n")
        }
        return sb.toString()
    }
}
