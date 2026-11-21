package com.uaefinancial.tracker.qa

import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * READ-ONLY MEASUREMENT. Changes nothing in the app and asserts nothing: it only reports.
 *
 * Runs only when the environment variable REAL_EXPORT points to the owner's masked export (decrypted by the manual
 * "QA reader measurement" workflow); in every normal build it is skipped.
 *
 * What it does: each masked shape ("... {CUR} {AMOUNT} at {MERCHANT} on {DATE} ...") is turned back into a message by
 * putting stand-in values where the masking removed them (amount 123.45 AED, card 1234, merchant ZENITH BAKERY, a date).
 * Because we chose the stand-ins, we know the right answer for amount, currency, card and merchant. The type, direction
 * and card kind come from the export (decided by a verified bank rule, the signed rules file or the owner's own fix).
 * Two readers are scored on every message:
 *   shipped  = the reader exactly as the app uses it for that bank (verified bank rules first, then the generic reader);
 *              on rows that a verified rule decided, the TYPE agrees by construction, so read the field rows, not the type row.
 *   generic  = the generic reader alone, as if the bank were unknown (a bank we have no rules for).
 * Limits: stand-in values are not the real ones (a bank that prints cards another way may be mis-scored on the card
 * field); words the masking replaced by {TEXT} are filled with a neutral word; amounts are scored only on messages with a
 * single {AMOUNT} (otherwise the transaction amount cannot be told from a balance). Output: counts per bank and per field
 * only. No message text is printed or stored.
 */
class RealMaskedReaderMeasureTest {
    private data class Row(val n: Int, val bank: String, val kind: String, val instr: String, val source: String, val shape: String)

    private class Tally {
        var right = 0; var wrong = 0; var none = 0; var wRight = 0; var wWrong = 0; var wNone = 0
        fun add(state: Int, w: Int) { when (state) { 1 -> { right++; wRight += w }; 0 -> { wrong++; wWrong += w }; else -> { none++; wNone += w } } }
        fun line(): String {
            val answered = right + wrong
            val rate = if (answered > 0) "%.1f%%".format(100.0 * wrong / answered) else "-"
            return "$right right, $wrong wrong, $none unanswered of ${right + wrong + none} shapes; wrong among answered $rate; by messages: $wRight / $wWrong / $wNone"
        }
    }

    private val received = 1789948800000L // 2026-09-21, only used when a message has no date

    private fun render(shape: String): String {
        var amounts = 0
        var s = shape
        s = Regex("""\{CUR\} \{AMOUNT\}""").replace(s) { val v = listOf("123.45", "5,000.00", "7,500.00", "9,000.00").getOrElse(amounts) { "1.00" }; amounts++; "AED $v" }
        s = Regex("""\{CARD:(\d+)\}""").replace(s) { m -> val n = m.groupValues[1].toInt(); if (n <= 4) "1234" else "X".repeat(n - 4) + "1234" }
        s = Regex("""\{NUM:(\d+)\}""").replace(s) { m -> val n = m.groupValues[1].toInt(); if (n <= 1) "7" else if (n == 2) "12" else "1234567890123456789".take(n) }
        s = s.replace("{DATE}", "21/09/2026").replace("{TIME}", "12:30").replace("{MERCHANT}", "ZENITH BAKERY")
            .replace("{EMAIL}", "info@example.com").replace("{URL}", "www.example.com").replace("{TEXT}", "Zorbo")
        return s
    }

    private fun dir(label: String?) = when (label) { "PURCHASE", "TRANSFER_OUT" -> "out"; "REFUND", "TRANSFER_IN" -> "in"; else -> "none" }

    @Test fun measure() {
        val path = System.getenv("REAL_EXPORT")
        assumeTrue("REAL_EXPORT is not set; measurement skipped", !path.isNullOrBlank() && File(path).exists())
        val rows = File(path!!).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull {
            val p = it.split("\t"); if (p.size < 7) null else Row(p[0].toIntOrNull() ?: 1, p[1], p[2], p[4], p[5], p[6])
        }
        val gold = rows.filter { it.kind != "UNREAD" && (it.source in setOf("rule", "pack", "user-fix") || (it.kind == "NOT_TXN" && it.source == "none")) }
        val out = StringBuilder()
        val detail = StringBuilder() // wrong answers with their wording: written to a file that the workflow encrypts, never printed
        out.append("## Reader measurement on the real masked messages\n\n")
        out.append("${rows.size} distinct shapes in the export; ${gold.size} have a trusted answer (verified rule, signed pack, your fix, or a not-a-transaction decision). ")
        out.append("Counts are distinct wordings; \"by messages\" counts every message that shares the wording (right / wrong / unanswered).\n\n")

        for (path2 in listOf("shipped", "generic")) {
            val t = linkedMapOf<String, Tally>()
            val perBankKind = sortedMapOf<String, Tally>()
            val perBankMerchant = sortedMapOf<String, Tally>()
            val confusion = sortedMapOf<String, Int>()
            val faults = sortedMapOf<String, IntArray>() // which rule (an id from our own code, never message text) produced each wrong answer
            fun note(r: Row, got: String, rid: String) { detail.append("$path2\t${r.bank}\t${r.kind}\t$got\t$rid\t${r.n}\t${r.shape}\n") }
            fun fault(key: String, w: Int) { val a = faults.getOrPut(key) { IntArray(2) }; a[0]++; a[1] += w }
            fun tally(k: String) = t.getOrPut(k) { Tally() }
            for (r in gold) {
                val text = render(r.shape)
                val bank = if (path2 == "shipped") r.bank else "Unknown Test Bank"
                val res = runCatching { SmsParser.parseAsBank(bank, text, received) }.getOrNull()
                val txn = (res as? ParseResult.Transaction)?.txn
                val rid = (res as? ParseResult.Transaction)?.ruleId ?: (res as? ParseResult.Statement)?.ruleId ?: "-"
                val isStatement = res is ParseResult.Statement
                val answered = txn != null || isStatement
                val label = when { txn != null -> txn.type.name; isStatement -> "STATEMENT"; else -> null }
                val w = r.n
                if (r.kind == "NOT_TXN") {
                    val st = if (answered) 0 else 1 // a made-up transaction is wrong; ignoring it is right
                    tally("not-a-transaction (should NOT become a transaction)").add(st, w)
                    if (answered) { note(r, "NOT_TXN->$label", rid); confusion["NOT_TXN -> $label"] = (confusion["NOT_TXN -> $label"] ?: 0) + 1; fault("${r.bank} | not-a-transaction -> $label | rule=$rid", w) }
                    continue
                }
                val kindState = if (!answered) -1 else if (label == r.kind) 1 else 0
                tally("kind of message (6 kinds)").add(kindState, w)
                tally("kind, ${r.kind}").add(kindState, w)
                perBankKind.getOrPut(r.bank) { Tally() }.add(kindState, w)
                if (answered && label != r.kind) { note(r, "$label", rid); confusion["${r.kind} -> $label"] = (confusion["${r.kind} -> $label"] ?: 0) + 1; fault("${r.bank} | ${r.kind} -> $label | rule=$rid", w) }
                if (!answered) { note(r, "unanswered", "-"); fault("${r.bank} | ${r.kind} -> unanswered | rule=-", w) }
                val gd = dir(r.kind)
                if (gd == "out" || gd == "in") tally("money out vs in").add(if (!answered || dir(label) == "none") -1 else if (dir(label) == gd) 1 else 0, w)
                if (r.kind != "STATEMENT") {
                    if (r.instr in setOf("CREDIT", "DEBIT", "ACCOUNT")) {
                        val ok = if (txn == null) -1 else if (txn.cardType == CardType.valueOf(r.instr)) 1 else 0
                        tally("credit card / debit card / account").add(ok, w)
                        if (ok == 0) fault("${r.bank} | card kind ${r.instr} -> ${txn?.cardType} | rule=$rid", w)
                    }
                    if (Regex("""\{AMOUNT\}""").findAll(r.shape).count() == 1) {
                        tally("amount (messages with one amount)").add(if (txn == null) -1 else if (txn.amount.setScale(2).toPlainString() == "123.45") 1 else 0, w)
                        tally("currency (messages with one amount)").add(if (txn == null) -1 else if (txn.currency == "AED") 1 else 0, w)
                    }
                    if (r.shape.contains("{MERCHANT}")) {
                        val st = if (txn == null) -1 else if (txn.merchant.uppercase().contains("ZENITH")) 1 else 0
                        tally("merchant (messages where the merchant was masked)").add(st, w)
                        perBankMerchant.getOrPut(r.bank) { Tally() }.add(st, w)
                    }
                    if (Regex("""\{CARD:\d+\}""").containsMatchIn(r.shape)) {
                        val ok = if (txn == null) -1 else if (txn.cardLast4 == "1234") 1 else 0
                        tally("card last 4 (indicative: stand-in card format)").add(ok, w)
                        if (ok == 0) fault("${r.bank} | card digits wrong | rule=$rid", w)
                    }
                }
            }
            out.append(if (path2 == "shipped") "### Reader as shipped (verified bank rules first, then the generic reader)\n\n" else "### Generic reader alone (as for a bank we have no rules for)\n\n")
            t.forEach { (k, v) -> out.append("- **$k:** ${v.line()}\n") }
            out.append("\nMix-ups (wanted -> got, counted in wordings):\n")
            if (confusion.isEmpty()) out.append("- none\n") else confusion.entries.sortedByDescending { it.value }.forEach { out.append("- ${it.key}: ${it.value}\n") }
            out.append("\nWhere the wrong answers came from (bank | wanted -> got | rule id; wordings, messages):\n")
            if (faults.isEmpty()) out.append("- none\n") else faults.entries.sortedByDescending { it.value[1] }.take(60).forEach { out.append("- ${it.key}: ${it.value[0]} wordings, ${it.value[1]} messages\n") }
            out.append("\nPer bank, kind of message:\n")
            perBankKind.forEach { (b, v) -> out.append("- $b: ${v.line()}\n") }
            out.append("\nPer bank, merchant:\n")
            perBankMerchant.forEach { (b, v) -> out.append("- $b: ${v.line()}\n") }
            out.append("\n")
        }
        val text = out.toString()
        File("build").mkdirs()
        File("build/qa-reader-report.md").writeText(text)
        File("build/qa-reader-detail.txt").writeText(detail.toString())
        text.lines().forEach { println("READER $it") }
    }

    /**
     * THE TRUSTWORTHY MEASUREMENT. A newer export carries two extra columns that the phone computed on the REAL text:
     * what the reader says now, and what the generic reader alone says. Nothing is rebuilt, so nothing is an artifact.
     * Gold = what a verified rule, the signed rules file or the owner's own fix decided (or "not a transaction").
     */
    @Test fun measureOnRealText() {
        val path = System.getenv("REAL_EXPORT")
        assumeTrue("REAL_EXPORT is not set; measurement skipped", !path.isNullOrBlank() && File(path!!).exists())
        class R(val n: Int, val bank: String, val kind: String, val source: String, val shape: String, val now: String, val generic: String)
        val rows = File(path).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull {
            val p = it.split("\t"); if (p.size < 9) null else R(p[0].toIntOrNull() ?: 1, p[1], p[2], p[5], p[6], p[7], p[8])
        }
        assumeTrue("the export has no reader-now / generic columns (needs a build with them)", rows.isNotEmpty())
        val gold = rows.filter { it.kind != "UNREAD" && (it.source in setOf("rule", "pack", "user-fix") || (it.kind == "NOT_TXN" && it.source == "none")) }
        val out = StringBuilder("## Reader measurement on the REAL text (computed on the phone, nothing rebuilt)\n\n")
        out.append("${rows.size} distinct wordings in the export; ${gold.size} have a trusted answer.\n\n")
        val detail = StringBuilder()
        for (which in listOf("now", "generic")) {
            val kind = Tally(); val dirT = Tally(); val notTxn = Tally()
            val conf = sortedMapOf<String, Int>()
            for (r in gold) {
                val got = if (which == "now") r.now else r.generic
                val answered = got != "none" && got != "error"
                if (r.kind == "NOT_TXN") {
                    notTxn.add(if (answered) 0 else 1, r.n)
                    if (answered) { conf["NOT_TXN -> $got"] = (conf["NOT_TXN -> $got"] ?: 0) + 1; detail.append("real-$which\t${r.bank}\tNOT_TXN\t$got\t${r.n}\t${r.shape}\n") }
                    continue
                }
                val k = if (!answered) -1 else if (got == r.kind) 1 else 0
                kind.add(k, r.n)
                val gd = dir(r.kind)
                if (gd == "out" || gd == "in") dirT.add(if (!answered || dir(got) == "none") -1 else if (dir(got) == gd) 1 else 0, r.n)
                if (k != 1) { conf["${r.kind} -> ${if (answered) got else "unanswered"}"] = (conf["${r.kind} -> ${if (answered) got else "unanswered"}"] ?: 0) + 1; detail.append("real-$which\t${r.bank}\t${r.kind}\t${if (answered) got else "unanswered"}\t${r.n}\t${r.shape}\n") }
            }
            out.append(if (which == "now") "### Reader as it is now (real text)\n\n" else "### Generic reader alone (real text)\n\n")
            out.append("- **kind of message:** ${kind.line()}\n- **money out vs in:** ${dirT.line()}\n- **not-a-transaction (should NOT become one):** ${notTxn.line()}\n\nMix-ups (wanted -> got, wordings):\n")
            if (conf.isEmpty()) out.append("- none\n") else conf.entries.sortedByDescending { it.value }.forEach { out.append("- ${it.key}: ${it.value}\n") }
            out.append("\n")
        }
        File("build").mkdirs()
        File("build/qa-reader-report-real.md").writeText(out.toString())
        File("build/qa-reader-detail-real.txt").writeText(detail.toString())
        out.toString().lines().forEach { println("READER $it") }
    }
}
