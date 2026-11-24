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
        /** 2.8: id of the transaction this message was merged into (null when it was not merged into another one). */
        val mergedInto: Long? = null,
        /** 2.8: pair (two messages of one bank about one money movement), twin (the same money from two sources, or a needsTwin notice), none. */
        val mergeKind: String = MERGE_NONE,
        /** 2.8: the app's reading vs the generic reader on the same text: agree, disagree or n-a (see [check]). */
        val amountCheck: String = NA,
        val merchantCheck: String = NA,
        /** 2.8 per-day counts only (not printed on the line): the message's day, and the transfer transaction it counts in, if any. */
        val day: String = "",
        val transferTxnId: Long? = null,
    )

    const val MERGE_NONE = "none"
    const val MERGE_PAIR = "pair"
    const val MERGE_TWIN = "twin"
    const val AGREE = "agree"
    const val DISAGREE = "disagree"
    const val NA = "n-a"

    /** Amounts agree when the minor units are equal; n-a when either side has none. */
    fun amountCheck(app: Long?, generic: Long?): String = if (app == null || generic == null) NA else if (app == generic) AGREE else DISAGREE

    /** Merchants agree when equal after lower-casing and dropping everything but letters and digits; n-a when either is blank. */
    fun merchantCheck(app: String?, generic: String?): String {
        fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }
        val a = norm(app); val g = norm(generic)
        return if (a.isEmpty() || g.isEmpty()) NA else if (a == g) AGREE else DISAGREE
    }

    /** What kind of merge the app's note for a merged message describes; [needsTwinRule] when the message's rule is a needsTwin notice. */
    fun mergeKindOf(note: String?, needsTwinRule: Boolean): String = when {
        note == null || !note.endsWith("(merged)") -> MERGE_NONE
        needsTwinRule || note.startsWith("Same transaction as #") -> MERGE_TWIN
        else -> MERGE_PAIR
    }

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
        val grouped = rows.groupingBy { it.copy(day = "", transferTxnId = null) }.eachCount()
        val sb = StringBuilder()
        sb.append("# Fils masked message export (for testing the message reader)\n")
        sb.append("# Each line: count, bank, kind, direction, instrument, source, masked shape, reader-now, generic-reader (computed on the real text on your phone), stored status, app note. Words are kept as written; replaced: amounts, card and account numbers, other digits, dates, times, links, emails and the merchant name.\n")
        sb.append("# source: rule = verified bank rule, pack = signed rules file, learned = from your fixes, reader = generic reader, user-fix = you corrected it.\n")
        sb.append("# merged_into / merge_kind: the transaction a message was merged into (pair = two messages of one bank, twin = the same money from two sources or a notice that only counts with its transfer, none). amount_check / merchant_check: the app's reading vs the generic reader (agree, disagree, n-a).\n")
        sb.append("# Left out: $skippedUnsafe messages whose shape was not safe to export, $skippedOtp one-time-code messages.\n")
        grouped.entries.sortedWith(compareBy({ it.key.bank }, { it.key.kind }, { it.key.shape })).forEach { (r, n) ->
            sb.append("$n\t${clean(r.bank)}\t${r.kind}\t${r.direction}\t${r.instrument}\t${r.source}\t${clean(r.shape)}\t${r.now}\t${r.generic}\t${r.status}\t${clean(r.note)}" +
                "\t${r.mergedInto ?: ""}\t${r.mergeKind}\t${r.amountCheck}\t${r.merchantCheck}\n")
        }
        sb.append(perDay(rows))
        return sb.toString()
    }

    /**
     * One line per day: how many transfer messages there were and how many transfer transactions they count as, so merged
     * transfers can be seen not to be counted twice (transactions never exceed messages).
     */
    fun perDay(rows: List<Row>): String {
        val transfers = rows.filter { it.day.isNotEmpty() && it.transferTxnId != null }
        if (transfers.isEmpty()) return ""
        val sb = StringBuilder("# Per day: day, transfer messages, transfer transactions, merged messages\n")
        transfers.groupBy { it.day }.toSortedMap().forEach { (day, rs) ->
            sb.append("# day\t$day\tmessages=${rs.size}\ttransfers=${rs.mapNotNull { it.transferTxnId }.toSet().size}\tmerged=${rs.count { it.mergedInto != null }}\n")
        }
        return sb.toString()
    }
}
