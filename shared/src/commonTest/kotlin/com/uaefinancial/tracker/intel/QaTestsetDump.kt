package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prints the labelled test set for the question-answering measurement (tools/qa/eval.py), one "QASET {json}" line per message.
 * Gold labels come ONLY from verified bank rules (never from the keyword/learned readers), so they are an independent answer key.
 * Plus the two real Tabby messages, labelled by hand.
 */
class QaTestsetDump {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append(""); '\t' -> append(" ")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }

    private fun line(group: String, body: String, kind: String, direction: String?, instrument: String?,
                     amount: String?, currency: String?, merchant: String?, total: String?, min: String?, due: Long?): String {
        fun q(v: String?) = if (v == null) "null" else "\"${esc(v)}\""
        return "QASET {\"group\":${q(group)},\"body\":${q(body)},\"kind\":${q(kind)},\"direction\":${q(direction)}," +
            "\"instrument\":${q(instrument)},\"amount\":${q(amount)},\"currency\":${q(currency)},\"merchant\":${q(merchant)}," +
            "\"total\":${q(total)},\"min\":${q(min)},\"due\":${due ?: "null"}}"
    }

    @Test fun dump() {
        val zone = SmsParser.UAE_ZONE
        val at = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis(zone)
        var n = 0
        for ((sender, body) in CorpusData.raw) {
            val r = runCatching {
                if (sender.isNotEmpty() && SmsParser.bankFor(sender) != null) SmsParser.parse(sender, body, at, zone)
                else SmsParser.parseAsBank(sender.ifEmpty { "Unknown" }, body, at, zone)
            }.getOrNull() ?: continue
            val text = SmsParser.normalizeBody(body)
            when (r) {
                is ParseResult.Transaction -> {
                    val t = r.txn
                    if (t.auto) continue
                    val dir = when (t.type) {
                        TxnType.PURCHASE, TxnType.TRANSFER_OUT -> "out"
                        TxnType.REFUND, TxnType.TRANSFER_IN -> "in"
                        TxnType.PAYMENT -> null
                    }
                    println(line(t.bank, text, t.type.name, dir, t.cardType.name, t.amount.toPlainString(), t.currency, t.merchant, null, null, null)); n++
                }
                is ParseResult.Statement -> {
                    val s = r.statement
                    if (s.auto) continue
                    println(line(s.bank, text, "STATEMENT", null, s.cardType.name, null, s.currency, null,
                        s.statementBalance.toPlainString(), s.minimumDue?.toPlainString(), s.dueDate.toEpochDay())); n++
                }
                is ParseResult.Ignored -> if (r.store) { println(line(r.bank, text, "NOT_TXN", null, null, null, null, null, null, null, null)); n++ }
                else -> {}
            }
        }
        // Real Tabby messages, labelled by hand (no built-in rule reads them).
        println(line("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.",
            "PURCHASE", "out", "CREDIT", "9.00", "AED", "ISLAND SHELL CAFETERIA", null, null, null)); n++
        val due = com.uaefinancial.tracker.core.CalendarDate.of(2026, 6, 30).toEpochDay()
        println(line("Tabby", "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026. For more, go to the Tabby app.",
            "STATEMENT", null, "CREDIT", null, "AED", null, "511.06", "127.78", due)); n++
        println("QASET_COUNT $n")
        assertTrue(n > 100, "expected a sizeable labelled set, got $n")
    }
}
