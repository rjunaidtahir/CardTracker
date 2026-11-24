package com.uaefinancial.tracker.qa

import com.uaefinancial.tracker.parser.IgnoreRule
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 2.8 spec 8.1 and 8.2: the replay audit and the ignore regression gate. Runs only when AUDIT_INPUT points at decrypted real
 * data (the `audit.yml` workflow decrypts it with QA_DATA_KEY at runtime; nothing real is ever committed). Every normal build
 * skips it; [renderIsDeterministic] and [csvAndExportAreBothRead] always run, with invented shapes.
 *
 * Input, either format (auto-detected):
 *  - the accuracy sheet CSV export (header: Timestamp, shape, bank, country, kind, app);
 *  - the masked export used by qa-real.yml (tab separated: n, bank, kind, direction, instr, source, shape, ...).
 * Each distinct (bank, shape) group becomes one message (placeholders filled deterministically) read by the REAL engine with
 * the shipped rules/pack.json applied (legacy and ext, safe mode off). Output: counts and hashes only, never text:
 * `build/audit-report.json` and `build/audit-summary.md` (the job summary).
 */
class AuditReplayTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    data class Row(val bank: String, val shape: String, val kind: String, val build: String, val n: Int)
    data class Group(val id: String, val bank: String, val shape: String, val kinds: Set<String>, val builds: Set<String>, val n: Int)

    // ------------------------------------------------------------------ input

    /** Splits one CSV line (quotes and doubled quotes). */
    private fun csv(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** CSV records may span lines inside quotes. */
    private fun csvRecords(text: String): List<List<String>> {
        val recs = mutableListOf<List<String>>(); val cur = StringBuilder(); var quotes = 0
        for (line in text.lines()) {
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line); quotes += line.count { it == '"' }
            if (quotes % 2 == 0) { if (cur.isNotBlank()) recs += csv(cur.toString()); cur.clear(); quotes = 0 }
        }
        return recs
    }

    /** "2.7/e7", "2.6.1", "Fils 2.8-beta" -> "2.7", "2.6", "2.8"; anything else "older". */
    fun buildOf(app: String): String = Regex("""(\d+)\.(\d+)""").find(app)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: "older"

    /** The kind family the loop parses: "unread", "hidden", "held", "softchange", "fixed", ... */
    fun kindFamily(kind: String): String = kind.trim().split(':', '|', '/').first().removeSuffix("-again").removeSuffix("-more").ifEmpty { "unknown" }

    fun readInput(text: String): Pair<String, List<Row>> {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (first.contains("shape", ignoreCase = true) && first.contains(",")) {
            val recs = csvRecords(text)
            val head = recs.first().map { it.trim().lowercase() }
            fun col(name: String) = head.indexOf(name)
            val cs = col("shape"); val cb = col("bank"); val ck = col("kind"); val ca = col("app")
            require(cs >= 0 && cb >= 0 && ck >= 0) { "sheet CSV without shape/bank/kind columns" }
            return "sheet" to recs.drop(1).mapNotNull { r ->
                val shape = r.getOrNull(cs)?.trim().orEmpty()
                if (shape.isEmpty()) null else Row(r.getOrNull(cb).orEmpty().trim(), shape, kindFamily(r.getOrNull(ck).orEmpty()), buildOf(r.getOrNull(ca).orEmpty()), 1)
            }
        }
        return "export" to text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull {
            val p = it.split('\t')
            if (p.size < 7) null else Row(p[1], p[6], if (p[2] == "UNREAD") "unread" else "decided:${p[2]}", "export", p[0].toIntOrNull() ?: 1)
        }
    }

    fun groups(rows: List<Row>): List<Group> = rows.groupBy { it.bank to it.shape }.map { (k, rs) ->
        Group(hash("${k.first}|${k.second}"), k.first, k.second, rs.map { it.kind }.toSet(), rs.map { it.build }.toSet(), rs.sumOf { it.n })
    }.sortedBy { it.id }

    // ------------------------------------------------------------------ rendering

    /** Fills the masked placeholders with fixed stand-ins (same output every run). */
    fun render(shape: String): String {
        var amounts = 0
        var s = shape
        s = Regex("""\{AMT:([0-9,.']+)\}""").replace(s) { m -> layoutNumber(m.groupValues[1]) }
        s = Regex("""\{AMOUNT\}""").replace(s) { listOf("100.00", "2,500.00", "7,500.00").getOrElse(amounts++) { "1.00" } }
        s = s.replace("{CUR}", "AED").replace("{ANYCUR}", "AED")
        s = Regex("""\{CARD:(\d+)\}""").replace(s) { m -> val n = m.groupValues[1].toInt(); if (n <= 4) "1234".takeLast(n.coerceAtLeast(1)) else "XX" + "X".repeat((n - 6).coerceAtLeast(0)) + "1234" }
        s = Regex("""\{CARD\}""").replace(s, "XX1234")
        s = Regex("""\{NUM:(\d+)\}""").replace(s) { m -> "7".repeat(m.groupValues[1].toInt().coerceIn(1, 20)) }
        s = Regex("""\{NUM\}""").replace(s, "7")
        s = Regex("""\{DATE:([^{}\s]+)\}""").replace(s) { m -> layoutDate(m.groupValues[1]) }
        s = s.replace("{DATE}", "21/09/2026").replace("{TIME}", "12:30").replace("{MERCHANT}", "ZENITH BAKERY")
            .replace("{EMAIL}", "info@example.com").replace("{URL}", "www.example.com").replace("{TEXT}", "Zorbo")
            .replace("{NAME}", "Zorbo")
        return s
    }

    /** "1,3.2" -> "1,000.00" style: digit groups filled with a fixed digit. */
    private fun layoutNumber(layout: String): String = buildString {
        var first = true
        for (ch in layout) when {
            ch.isDigit() -> { val n = ch.digitToInt(); append((if (first) "1" else "0") + "0".repeat((n - 1).coerceAtLeast(0))); first = false }
            else -> append(ch)
        }
    }

    private fun layoutDate(layout: String): String = layout.replace('_', ' ')
        .replace("yyyy", "2026").replace("yy", "26").replace("MON", "SEP").replace("mm", "09").replace("dd", "21").replace("d", "21")

    // ------------------------------------------------------------------ reading

    data class Outcome(val state: String, val detail: String, val uncertain: Boolean)

    private fun senderFor(bank: String): String = Regex("""^Other \((.+)\)$""").find(bank)?.groupValues?.get(1)
        ?: if (bank == "Other" || bank.isBlank()) "My Chat" else bank

    fun outcome(g: Group): Outcome {
        val text = render(g.shape)
        val r = runCatching {
            if (g.bank.startsWith("Other")) SmsParser.parse(senderFor(g.bank), text, AT) else SmsParser.parseAsBank(g.bank, text, AT, SmsParser.UAE_ZONE)
        }.getOrElse { return Outcome("FAILED", "ERROR", true) }
        val uncertain = g.shape.contains("{TEXT}") || g.shape.contains("{NAME}")
        return when (r) {
            is ParseResult.Transaction -> {
                val t = r.txn
                val f = buildString { append("ACD"); if (t.cardLast4 != null || t.accountNotNamed) append('K'); if (t.merchant.isNotBlank()) append('M'); if (t.dateFromSms) append('T') }
                Outcome("READ", f, uncertain)
            }
            is ParseResult.Statement -> Outcome("READ", "S", uncertain)
            is ParseResult.Ignored -> Outcome("IGNORED", hash("ignore|" + r.reason).take(12), uncertain)
            is ParseResult.Failed -> Outcome("FAILED", r.stage ?: "UNKNOWN", uncertain)
            ParseResult.NotBank -> Outcome("FAILED", "NO_BANK", uncertain)
        }
    }

    private fun applyShipped(withPackIgnores: Boolean = true): RulesPack.Pack {
        val out = RulesPack.parse(packFile().readText(), 0)
        check(out is RulesPack.Outcome.Ok) { "shipped pack rejected: $out" }
        var p = out.pack
        p = p.copy(ext = p.ext?.copy(safeMode = false))
        if (!withPackIgnores) p = p.copy(ignore = emptyList(), ext = p.ext?.copy(ignore = emptyList()))
        RulesPack.apply(p)
        return out.pack
    }

    private fun packFile() = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    // ------------------------------------------------------------------ the audit (8.1)

    @Test fun audit() {
        val path = System.getenv("AUDIT_INPUT")
        assumeTrue("AUDIT_INPUT is not set; audit skipped", !path.isNullOrBlank())
        val inputs = path!!.split(File.pathSeparatorChar).map { File(it) }.filter { it.exists() && it.length() > 0 }
        assumeTrue("no audit input file found", inputs.isNotEmpty())
        val pack = applyShipped()
        val levers = readLevers()
        val baseline = readBaseline()
        val summary = StringBuilder("## Replay audit (pack v${pack.version}, engine ${SmsParser.ENGINE_VERSION})\n\nCounts and group hashes only; no message text.\n\n")
        val json = StringBuilder("{\"packVersion\":${pack.version},\"engine\":${SmsParser.ENGINE_VERSION},\"inputs\":{")
        val problems = mutableListOf<String>()
        inputs.forEachIndexed { idx, file ->
            val (type, rows) = readInput(file.readText())
            val gs = groups(rows)
            val results = gs.associateWith { outcome(it) }
            val byState = results.values.groupingBy { it.state }.eachCount()
            val read = byState["READ"] ?: 0
            summary.append("### Input: $type (${rows.size} rows, ${gs.size} groups)\n\n")
            summary.append("| state | groups | of which uncertain |\n|---|---|---|\n")
            for (st in listOf("READ", "IGNORED", "FAILED")) summary.append("| $st | ${byState[st] ?: 0} | ${results.values.count { it.state == st && it.uncertain }} |\n")
            // per bank and stage
            summary.append("\n| bank | READ | IGNORED | FAILED | failed stages |\n|---|---|---|---|---|\n")
            results.entries.groupBy { it.key.bank.ifBlank { "?" } }.toSortedMap().forEach { (b, es) ->
                val c = es.groupingBy { it.value.state }.eachCount()
                val stages = es.filter { it.value.state == "FAILED" }.groupingBy { it.value.detail }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
                summary.append("| ${b.replace('|', '/')} | ${c["READ"] ?: 0} | ${c["IGNORED"] ?: 0} | ${c["FAILED"] ?: 0} | $stages |\n")
            }
            // per kind and per build
            summary.append("\n| kind | groups | READ now | IGNORED now | FAILED now |\n|---|---|---|---|---|\n")
            results.entries.flatMap { e -> e.key.kinds.map { it to e.value } }.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (k, os) ->
                summary.append("| $k | ${os.size} | ${os.count { it.state == "READ" }} | ${os.count { it.state == "IGNORED" }} | ${os.count { it.state == "FAILED" }} |\n")
            }
            summary.append("\n| build | groups | READ now | FAILED now |\n|---|---|---|---|\n")
            results.entries.flatMap { e -> e.key.builds.map { it to e.value } }.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (b, os) ->
                summary.append("| $b | ${os.size} | ${os.count { it.state == "READ" }} | ${os.count { it.state == "FAILED" }} |\n")
            }
            // failed groups with the lever that should fix them
            val failed = results.filter { it.value.state == "FAILED" }
            summary.append("\nFAILED groups (id, stage, lever):\n\n")
            failed.entries.take(300).forEach { (g, o) -> summary.append("- `${g.id}` ${o.detail} ${leverFor(levers, g.id, o.detail) ?: "**no lever**"}${if (o.uncertain) " (uncertain)" else ""}\n") }
            // gate 1: a group seen unread on a 2.7+ build that still fails with no lever entry
            val gated = failed.filter { (g, o) -> g.kinds.any { it == "unread" } && g.builds.any { it == "2.7" || it == "2.8" || newer(it) } && leverFor(levers, g.id, o.detail) == null }
            if (gated.isNotEmpty()) problems += "$type: ${gated.size} group(s) from 2.7+ still FAILED with no lever entry: " + gated.keys.take(20).joinToString(",") { it.id }
            // gate 2: READ must not drop below the committed baseline (compared only when the input has at least as many groups)
            baseline[type]?.let { (bGroups, bRead) -> if (gs.size >= bGroups && read < bRead) problems += "$type: READ groups dropped from $bRead to $read" }
            if (idx > 0) json.append(',')
            json.append("\"$type\":{\"rows\":${rows.size},\"groups\":${gs.size},\"read\":$read,\"ignored\":${byState["IGNORED"] ?: 0},\"failed\":${byState["FAILED"] ?: 0},")
            json.append("\"failedGroups\":[" + failed.entries.joinToString(",") { (g, o) -> "{\"id\":\"${g.id}\",\"stage\":\"${o.detail}\",\"lever\":${leverFor(levers, g.id, o.detail)?.let { "\"$it\"" } ?: "null"},\"uncertain\":${o.uncertain}}" } + "]}")
        }
        json.append("},\"problems\":${problems.size}}")
        summary.append(if (problems.isEmpty()) "\n**Audit gate: passed.**\n" else "\n**Audit gate: FAILED**\n\n" + problems.joinToString("\n") { "- $it" } + "\n")
        File("build").mkdirs()
        File("build/audit-report.json").writeText(json.toString())
        File("build/audit-summary.md").writeText(summary.toString())
        summary.lines().forEach { println("AUDIT $it") }
        if (problems.isNotEmpty()) fail(problems.joinToString("; "))
    }

    private fun newer(build: String): Boolean = build.split('.').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) * 100 + (it.getOrNull(1)?.toIntOrNull() ?: 0) } >= 207

    /** known-levers.json: {"groups": {"<group id>": "<lever>"}, "stages": {"<stage>": "<lever>"}}. Maintained by the loop. */
    private fun readLevers(): Pair<Map<String, String>, Map<String, String>> {
        val f = listOf(File("../tools/audit/known-levers.json"), File("tools/audit/known-levers.json")).firstOrNull { it.exists() } ?: return emptyMap<String, String>() to emptyMap()
        val text = f.readText()
        // A flat two-level JSON file: read with plain patterns (the app's JSON reader is internal to the shared module).
        fun m(k: String) = Regex("\"$k\"\\s*:\\s*\\{([^}]*)\\}").find(text)?.groupValues?.get(1).orEmpty()
            .let { body -> Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).associate { it.groupValues[1] to it.groupValues[2] } }
        return m("groups") to m("stages")
    }

    private fun leverFor(levers: Pair<Map<String, String>, Map<String, String>>, id: String, stage: String): String? = levers.first[id] ?: levers.second[stage]

    /** baseline-counts.json: {"<input type>": {"groups": N, "read": N}}; a missing entry means "first run, nothing to compare". */
    private fun readBaseline(): Map<String, Pair<Int, Int>> {
        val f = listOf(File("../tools/audit/baseline-counts.json"), File("tools/audit/baseline-counts.json")).firstOrNull { it.exists() } ?: return emptyMap()
        return Regex("\"([A-Za-z0-9_-]+)\"\\s*:\\s*\\{\\s*\"groups\"\\s*:\\s*(\\d+)\\s*,\\s*\"read\"\\s*:\\s*(\\d+)").findAll(f.readText())
            .associate { it.groupValues[1] to (it.groupValues[2].toInt() to it.groupValues[3].toInt()) }
    }

    // ------------------------------------------------------------------ the ignore regression gate (8.2)

    @Test fun ignoreGate() {
        val path = System.getenv("AUDIT_INPUT")
        assumeTrue("AUDIT_INPUT is not set; ignore gate skipped", !path.isNullOrBlank())
        val inputs = path!!.split(File.pathSeparatorChar).map { File(it) }.filter { it.exists() && it.length() > 0 }
        assumeTrue("no audit input file found", inputs.isNotEmpty())
        val full = applyShipped(withPackIgnores = false)
        val ignores: List<IgnoreRule> = full.ignore + full.ext?.ignore.orEmpty()
        val compiled = ignores.mapNotNull { r -> runCatching { Triple(r, Regex(r.pattern, RegexOption.IGNORE_CASE), r.unless?.let { Regex(it, RegexOption.IGNORE_CASE) }) }.getOrNull() }
        val hits = sortedMapOf<String, Int>()
        var moneyGroups = 0
        for (file in inputs) for (g in groups(readInput(file.readText()).second)) {
            val text = render(g.shape)
            val r = runCatching {
                if (g.bank.startsWith("Other")) SmsParser.parse(senderFor(g.bank), text, AT) else SmsParser.parseAsBank(g.bank, text, AT, SmsParser.UAE_ZONE)
            }.getOrNull()
            if (r !is ParseResult.Transaction) continue
            moneyGroups++
            val norm = SmsParser.normalizeBody(text)
            val bank = (r.attributedBank ?: g.bank).trim().lowercase()
            for ((rule, re, unless) in compiled) {
                if (rule.bank != null && rule.bank != bank) continue
                if (!re.containsMatchIn(norm)) continue
                if (unless != null && unless.containsMatchIn(norm)) continue
                val key = hash("ignore|" + rule.label).take(12)
                hits[key] = (hits[key] ?: 0) + 1
            }
        }
        val report = "## Ignore regression gate\n\n${compiled.size} pack ignores checked against $moneyGroups groups that read as a transaction.\n\n" +
            (if (hits.isEmpty()) "No pack ignore hides any of them. **Passed.**\n" else hits.entries.joinToString("\n") { "- ignore `${it.key}` would hide ${it.value} group(s)" } + "\n\n**FAILED.**\n")
        File("build").mkdirs()
        File("build/ignore-gate.md").writeText(report)
        report.lines().forEach { println("AUDIT $it") }
        assertTrue(hits.isEmpty(), "pack ignores would hide transactions: $hits")
    }

    // ------------------------------------------------------------------ always-run checks (invented shapes)

    @Test fun renderIsDeterministic() {
        val shape = "Your Visa card ending {CARD:4} was used for {CUR} {AMOUNT} at {MERCHANT} on {DATE}. Avl bal {CUR} {AMOUNT}"
        assertEquals(render(shape), render(shape))
        assertEquals("Your Visa card ending 1234 was used for AED 100.00 at ZENITH BAKERY on 21/09/2026. Avl bal AED 2,500.00", render(shape))
        assertEquals("AED 1,000.00- on 21-SEP-26", render("AED {AMT:1,3.2}- on {DATE:dd-MON-yy}"))
        assertEquals(hash("A|b"), groups(listOf(Row("A", "b", "unread", "2.7", 1))).single().id)
    }

    @Test fun csvAndExportAreBothRead() {
        val sheet = "Timestamp,shape,bank,country,kind,app\n" +
            "2026-11-20,\"Card {CARD:4} used, {CUR} {AMOUNT} at {MERCHANT}\",ADCB,AE,unread|sid=ab,2.7/e7\n" +
            "2026-11-21,\"Card {CARD:4} used, {CUR} {AMOUNT} at {MERCHANT}\",ADCB,AE,unread-again/1/x,2.8/e8\n"
        val (t1, r1) = readInput(sheet)
        assertEquals("sheet", t1); assertEquals(2, r1.size); assertEquals("unread", r1[0].kind); assertEquals("2.7", r1[0].build)
        assertEquals(1, groups(r1).size)
        val export = "3\tADCB\tPURCHASE\tout\tCREDIT\trule\tCard {CARD:4} used {CUR} {AMOUNT}\n1\tHSBC\tUNREAD\t-\t-\tnone\tSome {TEXT}\n"
        val (t2, r2) = readInput(export)
        assertEquals("export", t2); assertEquals(listOf("decided:PURCHASE", "unread"), r2.map { it.kind })
        assertEquals("2.7", buildOf("Fils 2.7.1-beta")); assertEquals("older", buildOf(""))
    }

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object { const val AT = 1_795_000_000_000L }
}
