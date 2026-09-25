package com.uaefinancial.tracker.parser
import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import com.uaefinancial.tracker.core.UAE_OFFSET_MINUTES


/**
 * Parsing engine: bank rules first (BankRules.kt), then the smart reader (SmartParser.kt) for anything the
 * rules don't cover. You normally don't need to touch this file: edit BankRules.kt instead.
 */
object SmsParser {

    /** UAE time: UTC+4 all year. Zones are passed as minutes ahead of UTC. */
    const val UAE_ZONE: Int = UAE_OFFSET_MINUTES

    internal const val NUMDATE = """\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""
    /** 08-SEP-2026 / 25/Mar/2026 / 7 July 2026 / May 25 2026 */
    internal const val WORDDATE_T = """\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4}"""
    internal const val WEEKDAY = """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\s+)?"""
    internal const val TIME = """\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]\.?M\b)?"""
    private const val WORDDATE = """[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}|\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}[A-Za-z]{3}\d{2,4}"""
    /** 1,234.56 / 90.90 / 4300 / .07 */
    internal const val AMT = """(?:\d[\d,]*(?:\.\d+)?|\.\d+)"""
    /** Balances and statement totals can be negative (credit balance): AED -61.38 */
    private const val SAMT = """(?:-\s?)?$AMT"""

    private val tokens = linkedMapOf(
        "{CUR}" to "(?<currency>[A-Z]{3}|Dhs?)",
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

    private val globalIgnore: List<Pair<IgnoreRule, Regex>> by lazy {
        BankRules.globalIgnore.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) }
    }

    private val compiled: List<CompiledBank> by lazy {
        BankRules.banks.map { b ->
            CompiledBank(
                bank = b,
                rules = b.rules.map { it to compile(it.pattern) },
                ignore = b.ignore.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) } + globalIgnore,
            )
        }
    }

    private val otpRegex by lazy { Regex(BankRules.otpPreCheck.pattern, RegexOption.IGNORE_CASE) }

    fun normalizeSender(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Senders the user added in the app: normalized sender ID -> bank name. A name that matches a built-in bank
     * uses that bank's rules; any other name is read by the smart reader only.
     */
    @kotlin.concurrent.Volatile
    private var custom: Map<String, CompiledBank> = emptyMap()

    fun setCustomSenders(senderToBank: Map<String, String>) {
        custom = senderToBank.entries.associate { (sender, bankName) ->
            val known = compiled.firstOrNull { it.bank.name.equals(bankName, ignoreCase = true) }
            normalizeSender(sender) to (known ?: CompiledBank(Bank(bankName, listOf(sender), emptyList()), emptyList(), globalIgnore))
        }
    }

    /** Operator prefixes some phones show before a sender ID, e.g. "AD-FAB". */
    private val prefixes = setOf("AD", "AE", "UAE")

    /**
     * Sender ID match, ignoring case, spaces and dashes. Longer IDs may carry a short prefix ("AD-ADCBAlert");
     * short IDs (3 letters or fewer, like "FAB" or "DIB") only match exactly or with a known operator prefix.
     */
    fun senderMatches(sender: String, id: String): Boolean {
        val s = normalizeSender(sender)
        val n = normalizeSender(id)
        if (n.isEmpty()) return false
        if (s == n) return true
        if (!s.endsWith(n)) return false
        val prefix = s.dropLast(n.length)
        return if (n.length >= 4) prefix.length <= 3 else prefix in prefixes
    }

    /** Returns the bank for an SMS sender ID, or null if it isn't a bank the app knows or you added. */
    fun bankFor(sender: String?): Bank? = compiledBankFor(sender)?.bank

    /** True for built-in bank senders (not ones you added). */
    fun isBuiltInSender(sender: String?): Boolean =
        !sender.isNullOrBlank() && compiled.any { cb -> cb.bank.senderIds.any { senderMatches(sender, it) } }

    private fun compiledBankFor(sender: String?): CompiledBank? {
        if (sender.isNullOrBlank()) return null
        custom[normalizeSender(sender)]?.let { return it }
        return compiled.firstOrNull { cb -> cb.bank.senderIds.any { id -> senderMatches(sender, id) } }
    }

    /** Line breaks become single spaces; runs of spaces inside a line are kept (they separate merchant and city). */
    fun normalizeBody(body: String): String =
        body.replace(' ', ' ').replace(Regex("""[ \t]*\r?\n[ \t]*"""), " ").trim()

    /**
     * Currency codes the app understands, plus the "Dhs" ways of writing AED. Codes must be upper case (so words
     * like "try 2 times" aren't Turkish lira); the dirham spellings match in any case.
     */
    internal val currencyAlternation: String by lazy {
        BankRules.fxToAed.keys.sortedByDescending { it.length }.joinToString("|") + "|(?i:dirhams?|dhs?)"
    }

    /** "Contains an amount": a known currency code next to a number (card masks like XXX3538 don't count). */
    private val looksFinancial by lazy {
        val cur = currencyAlternation
        Regex("""\b(?:$cur)\s?\.?\s?\d|\d\s?(?:$cur)\b""")
    }

    fun looksFinancial(text: String): Boolean = looksFinancial.containsMatchIn(text)

    /** "Dhs" / "DH" -> AED; anything else upper-cased. */
    fun normalizeCurrency(raw: String?): String {
        val c = raw?.trim()?.uppercase().orEmpty()
        return if (c.isEmpty() || c in BankRules.aedAliases) BankRules.BASE_CURRENCY else c
    }

    fun parse(sender: String?, body: String, receivedAt: Long, zone: Int = UAE_ZONE): ParseResult {
        val cb = compiledBankFor(sender) ?: return ParseResult.NotBank
        return parseWith(cb, body, receivedAt, zone)
    }

    /**
     * Reads a message as coming from [bankName] (when the sender isn't known, e.g. pasted text). A built-in bank uses
     * its rules; any other name is read by the smart reader only.
     */
    fun parseAsBank(bankName: String, body: String, receivedAt: Long, zone: Int = UAE_ZONE): ParseResult {
        val cb = compiled.firstOrNull { it.bank.name.equals(bankName, ignoreCase = true) }
            ?: CompiledBank(Bank(bankName, emptyList(), emptyList()), emptyList(), globalIgnore)
        return parseWith(cb, body, receivedAt, zone)
    }

    private fun parseWith(cb: CompiledBank, body: String, receivedAt: Long, zone: Int): ParseResult {
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
        if (!looksFinancial.containsMatchIn(text)) return ParseResult.Ignored(cb.bank.name, "Informational (no amount)")
        // No bank rule: let the smart reader try, by the message's wording.
        runCatching { SmartParser.read(cb.bank.name, text, receivedAt, zone) }.getOrNull()?.let { return it }
        if (errors.isNotEmpty()) return ParseResult.Failed(cb.bank.name, "A rule matched but its values were invalid: " + errors.joinToString("; "))
        return ParseResult.Failed(cb.bank.name, "Couldn't read this message automatically")
    }

    private fun MatchResult.g(name: String): String? =
        try { groups[name]?.value?.trim()?.takeIf { it.isNotEmpty() } } catch (_: IllegalArgumentException) { null }

    private fun buildTxn(bank: Bank, rule: Rule, m: MatchResult, receivedAt: Long, zone: Int): ParsedTransaction {
        val amount = parseAmount(m.g("amount") ?: error("no amount"))
        val currency = normalizeCurrency(m.g("currency"))
        val toLast4 = m.g("to")
        val merchant = rule.fixedMerchant
            ?: m.g("merchant")?.let { cleanMerchant(it) }
            ?: toLast4?.let { transferLabel(it) }
            ?: error("no merchant")
        require(merchant.isNotBlank()) { "empty merchant" }
        val dateText = m.g("date")
        val ts = if (dateText != null) {
            val dt = parseDateTime(dateText) ?: error("bad date '$dateText'")
            if (dt.second) {
                dt.first.toEpochMillis(zone)
            } else {
                // Date only: keep the SMS arrival time if it's the same day, else noon.
                if (CalendarDate.fromEpochMillis(receivedAt, zone) == dt.first.toLocalDate()) receivedAt
                else dt.first.toLocalDate().atTime(12, 0).toEpochMillis(zone)
            }
        } else receivedAt
        return ParsedTransaction(
            bank = bank.name,
            cardLast4 = m.g("card"),
            cardType = cardTypeOf(rule, m),
            merchant = merchant,
            amount = amount,
            currency = currency,
            type = rule.type,
            timestamp = ts,
            dateFromSms = dateText != null,
            availableLimit = m.g("avail")?.let { parseAmount(it) },
            toLast4 = toLast4,
            accountNotNamed = m.g("card") == null && rule.accountNotNamed,
        )
    }

    /** Merchant text for a transfer to an account/card; the app later renames it if that is one of your cards. */
    fun transferLabel(last4: String) = "Transfer to ·$last4"

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
            currency = normalizeCurrency(m.g("currency")),
            dueDate = parseDateTime(dueText)?.first?.toLocalDate() ?: error("bad due date '$dueText'"),
            statementDate = m.g("stmtdate")?.let { parseDateTime(it)?.first?.toLocalDate() },
        )
    }

    fun parseAmount(s: String): Decimal = Decimal(s.replace(",", "").replace(" ", "").trimEnd('.'))

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
    fun parseDateTime(raw: String): Pair<DateTime, Boolean>? {
        val s = raw.trim().replace(Regex("""\s+"""), " ")
        val tm = splitTime.matchEntire(s) ?: return null
        val datePart = tm.groupValues[1].trim().trimEnd(',').replace(weekdayPrefix, "")
        val date = parseDate(datePart) ?: return null
        val h = tm.groupValues[2]
        if (h.isEmpty()) return date.atTime(0, 0) to false
        var hour = h.toInt()
        when (tm.groupValues[5].lowercase()) {
            "p" -> if (hour < 12) hour += 12
            "a" -> if (hour == 12) hour = 0
        }
        return date.atTime(hour, tm.groupValues[3].toInt(), tm.groupValues[4].ifEmpty { "0" }.toInt()) to true
    }

    private fun parseDate(s: String): CalendarDate? {
        numericDate.matchEntire(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return CalendarDate.of(year(y), mo.toInt(), d.toInt())
        }
        monthFirst.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[1]) ?: return null
            return CalendarDate.of(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())
        }
        compact.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[2]) ?: return null
            return CalendarDate.of(year(m.groupValues[3]), mo, m.groupValues[1].toInt())
        }
        dayFirst.matchEntire(s)?.let { m ->
            val mo = monthIndex(m.groupValues[2]) ?: return null
            return CalendarDate.of(year(m.groupValues[3]), mo, m.groupValues[1].toInt())
        }
        return null
    }

    private fun monthIndex(name: String): Int? =
        months.indexOf(name.take(3).lowercase()).takeIf { it >= 0 }?.plus(1)
}

/** Money helpers shared by the app. Amounts are stored as minor units (fils/cents). */
object Money {
    /** Rounded half up to fils / cents. Throws ArithmeticException if it doesn't fit. */
    fun toMinor(amount: Decimal): Long = amount.toMinor()

    fun fromMinor(minor: Long): Decimal = Decimal.valueOf(minor, 2)

    /** Returns (AED minor units, isEstimate). Unknown currency -> null. */
    fun toAedMinor(amount: Decimal, currency: String, rates: Map<String, Decimal> = BankRules.fxToAed): Pair<Long, Boolean>? {
        if (currency.equals(BankRules.BASE_CURRENCY, ignoreCase = true)) return toMinor(amount) to false
        val rate = rates[currency.uppercase()] ?: return null
        return toMinor(amount * rate) to true
    }
}
