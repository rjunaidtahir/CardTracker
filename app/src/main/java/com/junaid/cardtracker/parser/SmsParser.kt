package com.junaid.cardtracker.parser

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Parsing engine. You normally don't need to touch this — edit BankRules.kt instead. */
object SmsParser {

    val UAE_ZONE: ZoneId = ZoneId.of("Asia/Dubai")

    private const val NUMDATE = """\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""
    /** 08-SEP-2026 / 25/Mar/2026 / 7 July 2026 / May 25 2026 */
    private const val WORDDATE_T = """\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4}"""
    private const val WEEKDAY = """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\s+)?"""
    private const val TIME = """\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]\.?M\b)?"""
    private const val WORDDATE = """[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}|\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}[A-Za-z]{3}\d{2,4}"""
    /** 1,234.56 / 90.90 / 4300 / .07 */
    private const val AMT = """(?:\d[\d,]*(?:\.\d+)?|\.\d+)"""
    /** Balances and statement totals can be negative (credit balance): AED -61.38 */
    private const val SAMT = """(?:-\s?)?$AMT"""

    private val tokens = linkedMapOf(
        "{CUR}" to "(?<currency>[A-Z]{3})",
        "{ANYCUR}" to "[A-Z]{3}",
        "{AMOUNT}" to "(?<amount>$AMT)",
        "{AVAIL}" to "(?<avail>$SAMT)",
        "{TOTAL}" to "(?<total>$SAMT)",
        "{MIN}" to "(?<min>$AMT)",
        "{CARDTYPE}" to "(?<cardtype>Credit|Debit)",
        "{CARD}" to """(?:[X*\d]+\s*)?(?<card>\d{4})\b""",
        "{TO}" to """(?:[X*\d]+\s*)?(?<to>\d{4})\b""",
        "{MERCHANT}" to "(?<merchant>.+?)",
        "{CITY}" to """(?:,\s*[^.,]+?)?""",
        "{DATETIME}" to "(?<date>$WEEKDAY(?:$NUMDATE|$WORDDATE_T)(?:,?\\s+$TIME)?)",
        "{DUE}" to "(?<due>$NUMDATE|$WORDDATE)",
        "{STMTDATE}" to "(?<stmtdate>$NUMDATE|$WORDDATE)",
    )

    fun compile(pattern: String): Regex {
        var p = pattern
        tokens.forEach { (k, v) -> p = p.replace(k, v) }
        return Regex(p, RegexOption.IGNORE_CASE)
    }

    private class CompiledBank(val bank: Bank, val rules: List<Pair<Rule, Regex>>, val ignore: List<Pair<IgnoreRule, Regex>>)

    private val compiled: List<CompiledBank> by lazy {
        val generic = BankRules.genericRules.map { it to compile(it.pattern) }
        val globalIgnore = BankRules.globalIgnore.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) }
        BankRules.banks.map { b ->
            val own = b.rules.map { it to compile(it.pattern) }
            CompiledBank(
                bank = b,
                rules = if (b.useGenericRules) own + generic else own,
                ignore = b.ignore.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) } + globalIgnore,
            )
        }
    }

    private val otpRegex by lazy { Regex(BankRules.otpPreCheck.pattern, RegexOption.IGNORE_CASE) }

    private fun normalizeSender(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Returns the bank for an SMS sender ID, or null if it isn't one of your banks. */
    fun bankFor(sender: String?): Bank? = compiledBankFor(sender)?.bank

    private fun compiledBankFor(sender: String?): CompiledBank? {
        if (sender.isNullOrBlank()) return null
        val s = normalizeSender(sender)
        return compiled.firstOrNull { cb ->
            cb.bank.senderIds.any { id -> val n = normalizeSender(id); s == n || s.endsWith(n) && s.length - n.length <= 3 }
        }
    }

    /** Line breaks become single spaces; runs of spaces inside a line are kept (they separate merchant and city). */
    fun normalizeBody(body: String): String =
        body.replace(' ', ' ').replace(Regex("""[ \t]*\r?\n[ \t]*"""), " ").trim()

    /** "Contains an amount": a known currency code next to a number (card masks like XXX3538 don't count). */
    private val looksFinancial by lazy {
        val cur = BankRules.fxToAed.keys.joinToString("|")
        Regex("""\b(?:$cur)\s?\.?\d|\d\s?(?:$cur)\b""")
    }

    fun parse(sender: String?, body: String, receivedAt: Long, zone: ZoneId = UAE_ZONE): ParseResult {
        val cb = compiledBankFor(sender) ?: return ParseResult.NotBank
        val text = normalizeBody(body)
        if (otpRegex.containsMatchIn(text)) return ParseResult.Ignored(cb.bank.name, BankRules.otpPreCheck.label, store = false)
        val errors = mutableListOf<String>()

        for ((rule, regex) in cb.rules) {
            val m = regex.find(text) ?: continue
            try {
                return when (rule.kind) {
                    RuleKind.TRANSACTION -> ParseResult.Transaction(buildTxn(cb.bank, rule, m, receivedAt, zone), rule.id)
                    RuleKind.STATEMENT -> ParseResult.Statement(buildStatement(cb.bank, rule, m), rule.id)
                }
            } catch (e: Exception) {
                errors += "${rule.id}: ${e.message}"
            }
        }

        cb.ignore.firstOrNull { it.second.containsMatchIn(text) }?.let {
            return ParseResult.Ignored(cb.bank.name, it.first.label, it.first.store)
        }
        if (errors.isNotEmpty()) return ParseResult.Failed(cb.bank.name, "Rule matched but values were invalid: " + errors.joinToString("; "))
        return if (looksFinancial.containsMatchIn(text)) {
            ParseResult.Failed(cb.bank.name, "No rule matched")
        } else {
            ParseResult.Ignored(cb.bank.name, "Informational (no amount)")
        }
    }

    private fun MatchResult.g(name: String): String? =
        try { groups[name]?.value?.trim()?.takeIf { it.isNotEmpty() } } catch (_: IllegalArgumentException) { null }

    private fun buildTxn(bank: Bank, rule: Rule, m: MatchResult, receivedAt: Long, zone: ZoneId): ParsedTransaction {
        val amount = parseAmount(m.g("amount") ?: error("no amount"))
        val currency = (m.g("currency") ?: BankRules.BASE_CURRENCY).uppercase()
        val toLast4 = m.g("to")
        val merchant = rule.fixedMerchant
            ?: m.g("merchant")?.let { cleanMerchant(it) }
            ?: toLast4?.let { BankRules.counterpartyLabel(it) }
            ?: error("no merchant")
        require(merchant.isNotBlank()) { "empty merchant" }
        val dateText = m.g("date")
        val ts = if (dateText != null) {
            val dt = parseDateTime(dateText) ?: error("bad date '$dateText'")
            if (dt.second) {
                dt.first.atZone(zone).toInstant().toEpochMilli()
            } else {
                // Date only: keep the SMS arrival time if it's the same day, else noon.
                val received = java.time.Instant.ofEpochMilli(receivedAt).atZone(zone)
                if (received.toLocalDate() == dt.first.toLocalDate()) receivedAt
                else dt.first.toLocalDate().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            }
        } else receivedAt
        return ParsedTransaction(
            bank = bank.name,
            cardLast4 = m.g("card") ?: rule.defaultCardLast4,
            cardType = cardTypeOf(rule, m),
            merchant = merchant,
            amount = amount,
            currency = currency,
            type = rule.type,
            timestamp = ts,
            dateFromSms = dateText != null,
            availableLimit = m.g("avail")?.let { parseAmount(it) },
            toLast4 = toLast4,
        )
    }

    private fun cardTypeOf(rule: Rule, m: MatchResult): CardType = when (m.g("cardtype")?.lowercase()) {
        "debit" -> CardType.DEBIT
        "credit" -> CardType.CREDIT
        else -> rule.cardType
    }

    private fun buildStatement(bank: Bank, rule: Rule, m: MatchResult): ParsedStatement {
        val dueText = m.g("due") ?: error("no due date")
        return ParsedStatement(
            bank = bank.name,
            cardLast4 = m.g("card"),
            cardType = cardTypeOf(rule, m),
            statementBalance = parseAmount(m.g("total") ?: error("no total")),
            minimumDue = m.g("min")?.let { parseAmount(it) },
            currency = (m.g("currency") ?: BankRules.BASE_CURRENCY).uppercase(),
            dueDate = parseDateTime(dueText)?.first?.toLocalDate() ?: error("bad due date '$dueText'"),
            statementDate = m.g("stmtdate")?.let { parseDateTime(it)?.first?.toLocalDate() },
        )
    }

    fun parseAmount(s: String): BigDecimal = BigDecimal(s.replace(",", "").replace(" ", "").trimEnd('.'))

    private val countrySuffix = Regex("""(?:\s+(?:ARE|AE|UAE))+$""", RegexOption.IGNORE_CASE)

    /** "LIVA INS B S C CLOSED    ABU DHABI    AE" -> "LIVA INS B S C CLOSED"; "... CAFE DUBAI ARE" -> "... CAFE DUBAI". */
    fun cleanMerchant(raw: String): String {
        val firstChunk = raw.trim().split(Regex("""\s{2,}""")).first()
        return firstChunk.replace(countrySuffix, "").trim().trimEnd(',', '.', '-').trim()
    }

    private val splitTime = Regex("""^(.*?)(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([AaPp])\.?[Mm]\.?)?)?$""")
    private val weekdayPrefix = Regex("""^(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\s+""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""^(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})$""")
    private val monthFirst = Regex("""^([A-Za-z]{3,9})\s+(\d{1,2}),?\s+(\d{4})$""")
    private val dayFirst = Regex("""^(\d{1,2})(?:\s+|[/-])([A-Za-z]{3,9}),?(?:\s+|[/-])(\d{2,4})$""")
    private val compact = Regex("""^(\d{1,2})([A-Za-z]{3})(\d{2}|\d{4})$""") // 11Jun25, 07Jul2025
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun year(raw: String) = raw.toInt().let { if (it < 100) 2000 + it else it }

    /**
     * Day-first dates as used by UAE banks, with an optional time (24h or AM/PM) and weekday:
     * 13/09/2026 11:58:47 · 08-SEP-2026, 07:42:23 AM · Tuesday, 7 July 2026, 3:16 pm · May 25 2026 11:02AM · 25/Mar/2026 01:40.
     * Returns the date-time and whether a time was present.
     */
    fun parseDateTime(raw: String): Pair<LocalDateTime, Boolean>? {
        val s = raw.trim().replace(Regex("""\s+"""), " ")
        val tm = splitTime.matchEntire(s) ?: return null
        val datePart = tm.groupValues[1].trim().trimEnd(',').replace(weekdayPrefix, "")
        val date = parseDate(datePart) ?: return null
        val h = tm.groupValues[2]
        if (h.isEmpty()) return date.atStartOfDay() to false
        var hour = h.toInt()
        when (tm.groupValues[5].lowercase()) {
            "p" -> if (hour < 12) hour += 12
            "a" -> if (hour == 12) hour = 0
        }
        val time = LocalTime.of(hour, tm.groupValues[3].toInt(), tm.groupValues[4].ifEmpty { "0" }.toInt())
        return date.atTime(time) to true
    }

    private fun parseDate(s: String): LocalDate? {
        numericDate.matchEntire(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return LocalDate.of(year(y), mo.toInt(), d.toInt())
        }
        monthFirst.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[1]) ?: return null
            return LocalDate.of(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())
        }
        compact.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[2]) ?: return null
            return LocalDate.of(year(m.groupValues[3]), mo, m.groupValues[1].toInt())
        }
        dayFirst.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[2]) ?: return null
            return LocalDate.of(year(m.groupValues[3]), mo, m.groupValues[1].toInt())
        }
        return null
    }

    private fun monthIndex(name: String): Int? =
        months.indexOf(name.take(3).lowercase()).takeIf { it >= 0 }?.plus(1)
}

/** Money helpers shared by the app. Amounts are stored as minor units (fils/cents). */
object Money {
    fun toMinor(amount: BigDecimal): Long = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    fun fromMinor(minor: Long): BigDecimal = BigDecimal.valueOf(minor, 2)

    /** Returns (AED minor units, isEstimate). Unknown currency -> null. */
    fun toAedMinor(amount: BigDecimal, currency: String, rates: Map<String, BigDecimal> = BankRules.fxToAed): Pair<Long, Boolean>? {
        if (currency.equals(BankRules.BASE_CURRENCY, ignoreCase = true)) return toMinor(amount) to false
        val rate = rates[currency.uppercase()] ?: return null
        return toMinor(amount.multiply(rate)) to true
    }
}
