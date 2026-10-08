package com.uaefinancial.tracker.core

/** One instalment plan printed on a card statement (EPP, easy payment, balance transfer, "deal"...). Money in minor units. */
data class InstalmentPlan(
    val kind: String,
    val bookedEpochDay: Long?,
    val originalMinor: Long?,
    val outstandingMinor: Long?,
    val instalmentsLeft: Int?,
    val tenure: Int?,
    val monthlyMinor: Long?,
    val endEpochDay: Long?,
)

/**
 * Finds the instalment-plan section of a statement ("Deal Summary", "Instalment plans", "Easy payment plan") and reads one
 * plan per row, by what the columns are called (no bank template). Two shapes are understood:
 *  - a table: a header row naming the columns, then one row per plan;
 *  - labelled lines: "Outstanding amount 41,851.24", one figure per line (a single plan).
 * Anything that can't be read with confidence yields no plan, never a guess.
 */
object InstalmentPlans {
    private val heading = Regex(
        """(?i)\b(deal\s*summary|instal+ments?\s*(plans?|summary)|easy\s*pay(ment)?\s*(plans?)?|epp\s*(summary|plans?)|plan\s*summary|balance\s*transfer\s*(plans?|summary))\b""",
    )
    private val stop = Regex("""(?i)\b(transaction\s*(details|date|list)|statement\s*of\s*account|reward|important\s*information|terms)\b""")
    private val money = Regex("""\d{1,3}(?:,\d{3})*\.\d{2}|\d+\.\d{2}""")
    private val dateRx = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}|\d{4})\b""")
    private val kindRx = Regex("""(?i)\b(EPP|easy\s*pay(?:ment)?(?:\s*plan)?|balance\s*transfer|cash\s*on\s*call|purchase\s*plan|smart\s*pay|instal+ment\s*plan)\b""")

    private enum class F { TYPE, BOOKED, ORIGINAL, OUTSTANDING, LEFT, TENURE, MONTHLY, END }

    private val headerFields: List<Pair<F, Regex>> = listOf(
        F.TYPE to Regex("""(?i)deal\s*type|plan\s*type|\btype\b"""),
        F.OUTSTANDING to Regex("""(?i)outstanding\s*(amount|amt|balance)|balance\s*outstanding|remaining\s*(amount|balance)"""),
        F.LEFT to Regex("""(?i)(outstanding|remaining|balance)\s*instal+ments?|instal+ments?\s*(left|remaining|pending)|no\.?\s*of\s*instal+ments?\s*(left|remaining)"""),
        F.MONTHLY to Regex("""(?i)instal+ment\s*(amount|amt)|monthly\s*(instal+ment|amount|payment)|emi"""),
        F.TENURE to Regex("""(?i)tenure|term\b|no\.?\s*of\s*months"""),
        F.END to Regex("""(?i)expiry|end\s*date|maturity|last\s*instal+ment"""),
        F.BOOKED to Regex("""(?i)book(ed|ing)(\s*date)?|start\s*date|deal\s*date|plan\s*date"""),
        F.ORIGINAL to Regex("""(?i)(original|deal|plan|loan|principal|booked)\s*(amount|amt)|\bamount\b"""),
    )

    fun read(lines: List<PrintedLine>): List<InstalmentPlan> {
        val texts = lines.map { it.text }
        val out = ArrayList<InstalmentPlan>()
        var i = 0
        while (i < texts.size) {
            if (!heading.containsMatchIn(texts[i])) { i++; continue }
            val end = (i + 1 until minOf(texts.size, i + 26)).firstOrNull { stop.containsMatchIn(texts[it]) && !heading.containsMatchIn(texts[it]) } ?: minOf(texts.size, i + 26)
            val section = texts.subList(i, end)
            val before = out.size
            out += readTable(section)
            if (out.size == before) readLabelled(section)?.let { out += it }
            i = end
        }
        return out.distinct()
    }

    // ------------------------------------------------------------------ table

    private fun readTable(section: List<String>): List<InstalmentPlan> {
        val hi = section.indexOfFirst { fieldsIn(it).size >= 3 }
        if (hi < 0) return emptyList()
        val fields = fieldsIn(section[hi])
        val plans = ArrayList<InstalmentPlan>()
        for (row in section.drop(hi + 1)) {
            val m = money.findAll(row).toList()
            if (m.size < 2) continue
            val dates = dateRx.findAll(row).toList()
            val rest = row.replace(money, " ").replace(dateRx, " ")
            val ints = Regex("""(?<![\d.,/-])\d{1,3}(?![\d.,/-])""").findAll(rest).map { it.value.toInt() }.toList()
            val moneyFields = fields.filter { it in setOf(F.ORIGINAL, F.OUTSTANDING, F.MONTHLY) }
            val intFields = fields.filter { it == F.LEFT || it == F.TENURE }
            val dateFields = fields.filter { it == F.BOOKED || it == F.END }
            if (m.size < moneyFields.size) continue
            val mv = HashMap<F, Long>(); val iv = HashMap<F, Int>(); val dv = HashMap<F, Long>()
            moneyFields.forEachIndexed { k, f -> toMinor(m[k].value)?.let { mv[f] = it } }
            intFields.forEachIndexed { k, f -> ints.getOrNull(k)?.let { iv[f] = it } }
            dateFields.forEachIndexed { k, f -> dates.getOrNull(k)?.let { d -> toDay(d)?.let { dv[f] = it } } }
            val plan = build(kindRx.find(row)?.value, mv, iv, dv) ?: continue
            plans += plan
        }
        return plans
    }

    /** Column names found in a header line, in the order they appear. */
    private fun fieldsIn(line: String): List<F> {
        val hits = ArrayList<Pair<Int, F>>()
        val used = ArrayList<IntRange>()
        // More specific names first so "outstanding amount" isn't also read as "amount".
        for ((f, rx) in headerFields) {
            for (mt in rx.findAll(line)) {
                if (used.any { it.first <= mt.range.last && mt.range.first <= it.last }) continue
                hits += mt.range.first to f
                used += mt.range
            }
        }
        return hits.sortedBy { it.first }.map { it.second }.distinct()
    }

    // ------------------------------------------------------------------ labelled lines

    private fun readLabelled(section: List<String>): InstalmentPlan? {
        val mv = HashMap<F, Long>(); val iv = HashMap<F, Int>(); val dv = HashMap<F, Long>()
        var kind: String? = null
        for (line in section) {
            kind = kind ?: kindRx.find(line)?.value
            val f = fieldsIn(line).firstOrNull() ?: continue
            when (f) {
                F.OUTSTANDING, F.MONTHLY, F.ORIGINAL -> money.find(line)?.let { toMinor(it.value)?.let { v -> mv.putFirst(f, v) } }
                F.LEFT, F.TENURE -> Regex("""(?<![\d.,/-])\d{1,3}(?![\d.,/-])""").findAll(line.replace(money, " ").replace(dateRx, " ")).lastOrNull()
                    ?.let { iv.putFirst(f, it.value.toInt()) }
                F.BOOKED, F.END -> dateRx.find(line)?.let { toDay(it)?.let { d -> dv.putFirst(f, d) } }
                F.TYPE -> kind = kind ?: kindRx.find(line)?.value
            }
        }
        return build(kind, mv, iv, dv)
    }

    private fun build(kind: String?, mv: Map<F, Long>, iv: Map<F, Int>, dv: Map<F, Long>): InstalmentPlan? {
        // Need enough to be useful: what's still owed and what is paid monthly (or how many are left).
        val outstanding = mv[F.OUTSTANDING]
        val monthly = mv[F.MONTHLY]
        if (outstanding == null && monthly == null) return null
        if (monthly == null && iv[F.LEFT] == null) return null
        return InstalmentPlan(
            kind = kind?.trim()?.uppercase()?.replace(Regex("""\s+"""), " ")?.let { if (it.startsWith("EASY")) "EPP" else it } ?: "PLAN",
            bookedEpochDay = dv[F.BOOKED], originalMinor = mv[F.ORIGINAL], outstandingMinor = outstanding,
            instalmentsLeft = iv[F.LEFT], tenure = iv[F.TENURE], monthlyMinor = monthly, endEpochDay = dv[F.END],
        )
    }

    /** Keeps the first value seen for a key (common code can't use the JVM's putIfAbsent). */
    private fun <K, V> MutableMap<K, V>.putFirst(k: K, v: V) { if (!containsKey(k)) put(k, v) }

    private fun toMinor(s: String): Long? = s.replace(",", "").let { v ->
        val p = v.split('.')
        if (p.size != 2) null else (p[0].toLongOrNull()?.times(100))?.plus(p[1].padEnd(2, '0').take(2).toIntOrNull() ?: return null)
    }

    private fun toDay(m: MatchResult): Long? = runCatching {
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        var y = m.groupValues[3].toInt()
        if (y < 100) y += 2000
        CalendarDate.of(y, mo, d).toEpochDay()
    }.getOrNull()
}
