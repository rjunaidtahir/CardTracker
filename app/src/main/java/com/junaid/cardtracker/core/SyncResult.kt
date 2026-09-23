package com.junaid.cardtracker.core

/** What happened to one SMS offered to the repository. */
enum class IngestOutcome { TRANSACTION, STATEMENT, FAILED, IGNORED, OTP_SKIPPED, DUPLICATE, NOT_BANK }

data class SyncResult(val scanned: Int, val outcomes: Map<IngestOutcome, Int>) {
    fun count(o: IngestOutcome) = outcomes[o] ?: 0

    /** e.g. "12 new transactions, 1 statement, 2 couldn't be parsed" */
    fun summary(): String {
        val parts = mutableListOf<String>()
        fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        count(IngestOutcome.TRANSACTION).takeIf { it > 0 }?.let { parts += plural(it, "new transaction", "new transactions") }
        count(IngestOutcome.STATEMENT).takeIf { it > 0 }?.let { parts += plural(it, "statement", "statements") }
        count(IngestOutcome.FAILED).takeIf { it > 0 }?.let { parts += "$it couldn't be parsed" }
        return if (parts.isEmpty()) "Nothing new" else parts.joinToString(", ")
    }

    companion object {
        fun of(outcomes: List<IngestOutcome>) = SyncResult(outcomes.size, outcomes.groupingBy { it }.eachCount())
    }
}
