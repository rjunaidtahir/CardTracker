package com.uaefinancial.tracker.sms

import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.parser.GlobalBanks
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser

/**
 * Chats that look like a bank but that the app doesn't read yet. Only the sender and how many bank-like messages it
 * sent are kept (never the text), and the app offers them with one tap: "Is HDFC Bank a bank you use?".
 * A phone number is never a candidate: banks send from named senders or short codes.
 */
object Candidates {
    private const val SEP = '\u001F'

    data class Candidate(val key: String, val sender: String, val count: Int, val bankName: String?)

    /** Notes one message from an unknown sender. Cheap: does nothing unless the message looks like a bank alert. */
    fun note(prefs: Prefs, sender: String, body: String) {
        val addr = sender.trim()
        if (addr.isEmpty()) return
        if (!addr.any { it.isLetter() } && !SmsParser.isShortCode(addr)) return
        if (SmsParser.anyBankFor(addr) != null) return
        val key = SmsParser.senderKey(addr)
        if (key in prefs.dismissedCandidates) return
        if (!SmartParser.looksLikeBankAlert(body)) return
        val rows = prefs.candidateCounts.associate { e ->
            val p = e.split(SEP)
            p[0] to (p.getOrNull(1).orEmpty() to (p.getOrNull(2)?.toIntOrNull() ?: 1))
        }.toMutableMap()
        val old = rows[key]
        rows[key] = (old?.first ?: addr) to ((old?.second ?: 0) + 1)
        // Keep the list small: the 40 busiest.
        prefs.candidateCounts = rows.entries.sortedByDescending { it.value.second }.take(40)
            .map { (k, v) -> "$k$SEP${v.first}$SEP${v.second}" }.toSet()
    }

    /** What to offer now: bank-like chats not already added or dismissed. One alert is enough when the name is a known bank. */
    fun list(prefs: Prefs): List<Candidate> {
        val dismissed = prefs.dismissedCandidates
        return prefs.candidateCounts.mapNotNull { e ->
            val p = e.split(SEP)
            val key = p[0]
            val sender = p.getOrNull(1) ?: return@mapNotNull null
            val count = p.getOrNull(2)?.toIntOrNull() ?: 1
            if (key in dismissed || SmsParser.anyBankFor(sender) != null) return@mapNotNull null
            val dir = GlobalBanks.match(sender, null, prefs.homeCountry)
            if (count < 2 && dir == null) return@mapNotNull null
            Candidate(key, sender, count, dir?.name)
        }.sortedWith(compareByDescending<Candidate> { it.bankName != null }.thenByDescending { it.count }).take(3)
    }

    fun dismiss(prefs: Prefs, key: String) {
        prefs.dismissedCandidates = prefs.dismissedCandidates + key
        prefs.candidateCounts = prefs.candidateCounts.filterNot { it.startsWith("$key$SEP") }.toSet()
    }

    /** A sender you added or dismissed no longer needs remembering. */
    fun forget(prefs: Prefs, sender: String) {
        val key = SmsParser.senderKey(sender)
        prefs.candidateCounts = prefs.candidateCounts.filterNot { it.startsWith("$key$SEP") }.toSet()
    }
}
