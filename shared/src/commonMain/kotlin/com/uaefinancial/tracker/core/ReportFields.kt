package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser

/**
 * The 2.8 extra report keys (section 6 of the 2.8 spec): `|key=value` pairs appended to the existing report kind, so the
 * kinds and prefixes the accuracy loop already parses never change. Values are only codes, counts, versions, hashes and
 * public bank names: never a sender's letters (a contact or chat name), never a phone number, never message text, never
 * a digit run beyond what the masked shape already carries. Pure logic so it is tested without a phone.
 *
 * Keys: sid (stable id = the dedupe hash), pv (rules version), ev (engine version), st (stage), fr (fields read), rule (rule or
 * ignore label hash), sc / len / nb (sender class), ab (attributed bank), amt (amount layout, ask only), w / wc / wsms
 * (wallet, ask only), mg (merge fields), n / cleared / still / held / hidden / merged / seen / confident / review / byBank / day.
 */
object ReportFields {
    /** The kind text is posted to a Google Form short-answer field: kept well inside what the form takes. */
    const val MAX_KIND = 500

    private val safeValue = Regex("""[A-Za-z0-9_.,:+\-/& ]+""")

    /** A value made safe for the kind text: no `|` or `=`, only plain characters, at most [max] long; null when nothing is left. */
    fun clean(v: String?, max: Int = 80): String? {
        val t = v?.trim()?.replace('|', '/')?.replace('=', ':') ?: return null
        val kept = t.filter { safeValue.matches(it.toString()) }.take(max).trim()
        return kept.ifEmpty { null }
    }

    /** [kind] with the fields appended as `|k=v` (empty values left out), never longer than [MAX_KIND] (later keys dropped first). */
    fun append(kind: String, fields: List<Pair<String, String?>>): String {
        val sb = StringBuilder(kind)
        for ((k, v) in fields) {
            val c = clean(v, if (k == "byBank" || k == "mg") 200 else 80) ?: continue
            val piece = "|$k=$c"
            if (sb.length + piece.length > MAX_KIND) break
            sb.append(piece)
        }
        return sb.toString()
    }

    /** Sender classes for `sc=` (never the sender's letters). */
    object SenderClass {
        const val SPACES = "SPACES"
        const val DIGITS_ONLY = "DIGITS_ONLY"
        const val SHORT_CODE = "SHORT_CODE"
        const val LONG_DIGITS = "LONG_DIGITS"
        const val USER_NAMED_CHAT = "USER_NAMED_CHAT"
        const val ODD_CHARS = "ODD_CHARS"
        const val PUBLIC_ID_UNKNOWN = "PUBLIC_ID_UNKNOWN"
    }

    fun lengthBucket(n: Int): String = when {
        n <= 3 -> "1-3"
        n <= 6 -> "4-6"
        n <= 10 -> "7-10"
        n <= 15 -> "11-15"
        else -> "16+"
    }

    /**
     * (sc, len, nb) for a sender: [publicId] is the sender as a bank prints it (null for a contact name or a phone number),
     * [addedByUser] when you added this chat by hand. nb is a built-in bank whose name the sender spells (spaces and case ignored).
     */
    fun senderClass(sender: String, publicId: String?, addedByUser: Boolean): Triple<String, String, String?> {
        val s = sender.trim()
        val digits = s.count { it.isDigit() }
        val cls = when {
            publicId != null && s.all { it.isDigit() } -> SenderClass.SHORT_CODE
            publicId != null -> SenderClass.PUBLIC_ID_UNKNOWN
            addedByUser && s.any { it.isLetter() } -> SenderClass.USER_NAMED_CHAT
            s.isNotEmpty() && s.all { it.isDigit() || it == '+' || it == ' ' || it == '-' } -> if (digits >= 10) SenderClass.LONG_DIGITS else if (digits in 3..6) SenderClass.SHORT_CODE else SenderClass.DIGITS_ONLY
            s.contains(' ') -> SenderClass.SPACES
            else -> SenderClass.ODD_CHARS
        }
        val n = SmsParser.normalizeSender(s)
        val nb = BankRules.banks.firstOrNull { SmsParser.normalizeSender(it.name) == n && n.length >= 3 }?.name
        return Triple(cls, lengthBucket(s.length), nb)
    }

    /**
     * The report fields `ask` entries switch on for a message: entries for its bank (lower case) or "*", its public sender ID
     * or its shape hash, still in force for [packVersion] (`until` >= version).
     */
    fun askFor(asks: List<RulesPack.Ask>, bankLower: String?, publicSender: String?, hash: String?, packVersion: Int): Set<String> =
        asks.filter { a ->
            a.until >= packVersion &&
                (a.bank == null || a.bank == "*" || a.bank == bankLower) &&
                (a.sender == null || a.sender.equals(publicSender, ignoreCase = true)) &&
                (a.hash == null || a.hash == hash)
        }.flatMap { it.fields }.toSet()

    /** The amount layout of a layout-masked shape: "3.2,1,3.2-" from its {AMT:..} tokens (each with a trailing sign if any). */
    fun amountLayout(layoutShape: String): String? =
        Regex("""\{AMT:([0-9,.']+)\}([+-]?)""").findAll(layoutShape).map { it.groupValues[1] + it.groupValues[2] }.toList()
            .takeIf { it.isNotEmpty() }?.joinToString(";")

    /** Card name shape + last-four shape of a wallet line ("Visa Signature 1234" -> "Visa_Signature:4"), never the digits. */
    fun walletCardShape(text: String): String? {
        val m = Regex("""\b(Visa|Mastercard|Amex|American Express|Wio|Debit|Credit)(?:\s+([A-Z][a-z]+))?\s+(?:[•*·]+\s?)?(\d{4})\b""").find(text) ?: return null
        val name = listOfNotNull(m.groupValues[1], m.groupValues[2].ifEmpty { null }).joinToString("_")
        return "$name:${m.groupValues[3].length}"
    }

    /** True when [text] holds a digit run of [max]+1 or more outside the masked tokens (a report must never carry one). */
    fun hasLongDigitRun(text: String, max: Int = 3): Boolean =
        Regex("""\d{${max + 1},}""").containsMatchIn(text.replace(Regex("""\{[A-Z]+(?::[^{}\s]+)?\}"""), ""))
}
