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

    /**
     * Bump this whenever a change here, in BankRules.kt or in SmartParser.kt can read a stored message differently.
     * The apps re-read every stored message once when it changes, so fixes reach messages already in Needs review
     * (and a message a newer rule recognises as an OTP is deleted).
     */
    const val ENGINE_VERSION: Int = 2

    /** UAE time: UTC+4 all year. Zones are passed as minutes ahead of UTC. */
    const val UAE_ZONE: Int = UAE_OFFSET_MINUTES

    /**
     * Minutes ahead of UTC at a moment, for dates written in messages without a zone. The apps set this from the
     * phone's time zone (with daylight saving); tests and the default use UAE time.
     */
    @kotlin.concurrent.Volatile
    var zoneAt: (Long) -> Int = { UAE_OFFSET_MINUTES }
        private set

    fun setZoneProvider(provider: (Long) -> Int) { zoneAt = provider }

    /** A fixed offset (e.g. from an app that only knows the current one). */
    fun setZoneMinutes(minutes: Int) { zoneAt = { minutes } }

    /**
     * True where numeric dates are written month first (09/28/2026 in the United States). Unambiguous dates are read
     * correctly either way (13/09 is always day first, 09/13 always month first). Set by the apps from the region.
     */
    @kotlin.concurrent.Volatile
    var monthFirstDates: Boolean = false
        private set

    fun setMonthFirstDates(value: Boolean) { monthFirstDates = value }

    /** Regions that write dates month first. */
    fun isMonthFirstRegion(countryCode: String): Boolean =
        countryCode.uppercase() in setOf("US", "PR", "GU", "AS", "VI", "UM", "MP", "FM", "MH", "PW", "PH", "BZ", "KY")

    /** 13/09/2026 · 09-13-26 · 28.09.2026 · 2026-09-28 */
    internal const val NUMDATE = """(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[./-]\d{1,2}[./-]\d{2,4})"""
    /** Month names in English, German, French, Spanish, Portuguese, Italian and Dutch (with accents). */
    private const val MON = """[A-Za-zÀ-ÿ]{3,10}\.?"""
    /** 08-SEP-2026 / 25/Mar/2026 / 7 July 2026 / May 25 2026 / 28. September 2026 / 28 de septiembre de 2026 */
    internal const val WORDDATE_T =
        """\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}\.?\s+(?:de\s+)?$MON,?\s+(?:de\s+)?\d{4}|[A-Za-z]{3,9}\.?\s+\d{1,2},?\s+\d{4}"""
    internal const val WEEKDAY = """(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\s+)?"""
    internal const val TIME = """\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]\.?M\b)?"""
    private const val WORDDATE =
        """[A-Za-z]{3,9}\.?\s+\d{1,2},?\s+\d{4}|\d{1,2}\.?\s+(?:de\s+)?$MON,?\s+(?:de\s+)?\d{4}|\d{1,2}[/-][A-Za-z]{3,9}[/-]\d{2,4}|\d{1,2}[A-Za-z]{3}\d{2,4}"""
    /**
     * An amount as banks around the world write it (the value is worked out by [parseAmount]):
     * 1,234.56 · 12,34,567.89 (India) · 1.234,56 (Europe) · 1'234.50 (Switzerland) · 1 234,56 (France, Poland) ·
     * 12.500 (Kuwait, 3 decimals) · 4300 · 90.90 · 45,00 · .07
     */
    internal const val AMT =
        """(?:\d{1,3}(?:,\d{2,3})*,\d{3}(?:\.\d{1,3})?""" +          // 1,234 / 1,234.56 / 12,34,567.89
            """|\d{1,3}(?:\.\d{3})+(?:,\d{1,3})?""" +                  // 1.234 / 1.234,56 / 12.500 (KWD)
            """|\d{1,3}(?:'\d{3})+(?:\.\d{1,2})?""" +                  // 1'234.50
            """|\d{1,3}(?:[\u00A0\u202F]\d{3})+(?:,\d{1,2})?""" +      // 1 234,56 with a non-breaking space
            """|\d{1,3}(?: \d{3})+,\d{2}""" +                           // 1 234,56 with a plain space (needs the decimals)
            """|\d+(?:\.\d{1,4}|,\d{1,3})?""" +                        // 4300 / 90.90 / 45,00 / 12.500
            """|\.\d+)"""
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
    private val otpCodeRegex by lazy { Regex(BankRules.otpCodePreCheck.pattern, RegexOption.IGNORE_CASE) }
    private val otpIntlRegex by lazy { Regex(BankRules.otpIntlPreCheck) }

    /** One-time code check on an already-normalized message (English, then other languages on the lower-cased text). */
    private fun otpIn(text: String): Boolean =
        otpRegex.containsMatchIn(text) || otpCodeRegex.containsMatchIn(text) || otpIntlRegex.containsMatchIn(text.lowercase())

    /** True when the message is a one-time code (never stored). */
    fun isOtp(body: String): Boolean = otpIn(normalizeBody(body))

    fun normalizeSender(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Indian DLT sender IDs: operator/region prefix + 6-character header + optional type ("AX-HDFCBK-S", "VM-HDFCBK"). */
    private val dltSender = Regex("""^[A-Za-z]{2}-([A-Za-z0-9]{6})(?:-[SsPpTtGg])?$""")

    /**
     * The part of a sender ID that names the sender: "AX-HDFCBK-S", "VM-HDFCBK" and "JD-HDFCBK-T" are all "HDFCBK".
     * Other IDs are just normalized ("AD-FAB" -> "ADFAB").
     */
    fun senderKey(sender: String): String {
        val t = sender.trim()
        dltSender.matchEntire(t)?.let { return it.groupValues[1].uppercase() }
        return normalizeSender(t)
    }

    /** A short code (3 to 6 digits, e.g. "24273"): how banks in the US, Canada and the UK send alerts. Not a phone number. */
    fun isShortCode(sender: String): Boolean {
        val t = sender.trim()
        return t.length in 3..6 && t.all { it in '0'..'9' }
    }

    /**
     * Senders the user added in the app: normalized sender ID -> bank name. A name that matches a built-in bank
     * uses that bank's rules; any other name is read by the smart reader only.
     */
    @kotlin.concurrent.Volatile
    private var custom: Map<String, CompiledBank> = emptyMap()

    /** Banks you chose not to track (by bank name). Their messages are treated like any other sender's. */
    @kotlin.concurrent.Volatile
    private var excluded: Set<String> = emptySet()

    fun setExcludedBanks(bankNames: Set<String>) {
        excluded = bankNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isExcluded(bankName: String): Boolean = bankName.trim().lowercase() in excluded

    /**
     * Your home currency (ISO code): amounts are shown and totalled in it, and shared symbols ("\$", "Rs", "kr", "¥")
     * mean it when it's one of theirs. Set by the apps from the phone's region (changeable in settings).
     */
    @kotlin.concurrent.Volatile
    var homeCurrency: String = BankRules.BASE_CURRENCY
        private set

    fun setHomeCurrency(code: String) {
        val c = code.trim().uppercase()
        homeCurrency = if (Currencies.isCode(c)) c else BankRules.BASE_CURRENCY
    }

    fun setCustomSenders(senderToBank: Map<String, String>) {
        custom = senderToBank.entries.associate { (sender, bankName) ->
            val known = compiled.firstOrNull { it.bank.name.equals(bankName, ignoreCase = true) }
            senderKey(sender) to (known ?: CompiledBank(Bank(bankName, listOf(sender), emptyList()), emptyList(), globalIgnore))
        }
    }

    /** Operator prefixes some phones show before a sender ID, e.g. "AD-FAB". */
    private val prefixes = setOf("AD", "AE", "UAE")

    /**
     * Sender ID match, ignoring case, spaces and dashes. Longer IDs may carry a short prefix ("AD-ADCBAlert");
     * short IDs (3 letters or fewer, like "FAB" or "DIB") only match exactly or with a known operator prefix.
     */
    fun senderMatches(sender: String, id: String): Boolean {
        val n = normalizeSender(id)
        if (n.isEmpty()) return false
        if (senderKey(sender) == n) return true
        val s = normalizeSender(sender)
        if (s == n) return true
        if (!s.endsWith(n)) return false
        val prefix = s.dropLast(n.length)
        return if (n.length >= 4) prefix.length <= 3 else prefix in prefixes
    }

    /** Returns the bank for an SMS sender ID, or null if it isn't a bank the app knows or you added. */
    fun bankFor(sender: String?): Bank? = compiledBankFor(sender)?.bank

    /** Like [bankFor] but also returns banks you chose not to track (to list them with a tick box). */
    fun anyBankFor(sender: String?): Bank? = compiledBankFor(sender, includeExcluded = true)?.bank

    /** True for built-in bank senders (not ones you added). */
    fun isBuiltInSender(sender: String?): Boolean =
        !sender.isNullOrBlank() && compiled.any { cb -> cb.bank.senderIds.any { senderMatches(sender, it) } }

    private fun compiledBankFor(sender: String?, includeExcluded: Boolean = false): CompiledBank? {
        if (sender.isNullOrBlank()) return null
        val cb = custom[senderKey(sender)] ?: custom[normalizeSender(sender)]
            ?: compiled.firstOrNull { c -> c.bank.senderIds.any { id -> senderMatches(sender, id) } }
            ?: return null
        return if (!includeExcluded && isExcluded(cb.bank.name)) null else cb
    }

    /** Line breaks become single spaces; runs of spaces inside a line are kept (they separate merchant and city). */
    fun normalizeBody(body: String): String =
        westernDigits(body).replace('\u00A0', ' ').replace(Regex("""[ \t]*\r?\n[ \t]*"""), " ").trim()

    /** Arabic-Indic (٠-٩) and Persian/Urdu (۰-۹) digits become 0-9; the Arabic decimal and thousands marks become "." and ",". */
    internal fun westernDigits(s: String): String {
        if (s.none { it in '\u0660'..'\u066C' || it in '\u06F0'..'\u06F9' }) return s
        return buildString(s.length) {
            for (c in s) append(
                when (c) {
                    in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                    '\u066B' -> '.'
                    '\u066C' -> ','
                    else -> c
                },
            )
        }
    }

    /**
     * Every way of writing a currency the app understands (Currencies.kt): ISO codes, letter abbreviations and symbols.
     * Codes must be upper case (so words like "try 2 times" aren't Turkish lira); the dirham and rupee spellings match
     * in any case.
     */
    internal val currencyAlternation: String by lazy { "$currencyWordAlternation|$currencySymbolAlternation" }

    /** Codes and letter abbreviations (AED, USD, SR, KD, Rs, Dhs…): matched as whole words. */
    internal val currencyWordAlternation: String by lazy {
        val letters = Currencies.symbols.filter { t -> t.all { it in 'A'..'Z' || it in 'a'..'z' || it == '.' } }
        (Currencies.rateToAed.keys + letters).sortedByDescending { it.length }.joinToString("|") { esc(it) } + "|(?i:dirhams?|dhs?|rs)"
    }

    /** Symbols ($, €, £, ₹, US$, zł, د.إ …): not letters, so no word boundaries around them. */
    internal val currencySymbolAlternation: String by lazy {
        Currencies.symbols.filterNot { t -> t.all { it in 'A'..'Z' || it in 'a'..'z' || it == '.' } }
            .sortedByDescending { it.length }.joinToString("|") { esc(it) }
    }

    private fun esc(t: String): String = buildString { for (c in t) { if (c in "\\^$.|?*+()[]{}") append('\\'); append(c) } }

    /** "Contains an amount": a known currency next to a number (card masks like XXX3538 don't count). */
    private val looksFinancial by lazy {
        val w = currencyWordAlternation
        val sy = currencySymbolAlternation
        Regex("""(?:\b(?:$w)|(?:$sy))\s?\.?\s?\d|\d\s?(?:(?:$w)\b|(?:$sy))""")
    }

    fun looksFinancial(text: String): Boolean = looksFinancial.containsMatchIn(text)

    /** The ISO code for a currency as written ("Dhs" → AED, "US$" → USD, "\$" → your dollar or USD); unknown ones upper-cased. */
    fun normalizeCurrency(raw: String?): String {
        val c = raw?.trim().orEmpty()
        if (c.isEmpty() || c.uppercase() in BankRules.aedAliases) return BankRules.BASE_CURRENCY
        return Currencies.codeFor(c, homeCurrency) ?: c.uppercase()
    }

    fun parse(sender: String?, body: String, receivedAt: Long, zone: Int = zoneAt(receivedAt)): ParseResult {
        val cb = compiledBankFor(sender) ?: return ParseResult.NotBank
        return parseWith(cb, body, receivedAt, zone)
    }

    /**
     * Reads a message as coming from [bankName] (when the sender isn't known, e.g. pasted text). A built-in bank uses
     * its rules; any other name is read by the smart reader only.
     */
    fun parseAsBank(bankName: String, body: String, receivedAt: Long, zone: Int = zoneAt(receivedAt)): ParseResult {
        val cb = compiled.firstOrNull { it.bank.name.equals(bankName, ignoreCase = true) }
            ?: CompiledBank(Bank(bankName, emptyList(), emptyList()), emptyList(), globalIgnore)
        return parseWith(cb, body, receivedAt, zone)
    }

    private fun parseWith(cb: CompiledBank, body: String, receivedAt: Long, zone: Int): ParseResult {
        val text = normalizeBody(body)
        if (otpIn(text)) {
            return ParseResult.Ignored(cb.bank.name, BankRules.otpPreCheck.label, store = false)
        }
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

        // A message like one you fixed by hand and asked the app to learn ("apply to similar messages").
        LearnedFormats.read(cb.bank.name, text, receivedAt)?.let { return it }

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
        val currency = normalizeCurrency(m.g("currency"))
        val amount = parseAmount(m.g("amount") ?: error("no amount"), currency)
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
            availableLimit = m.g("avail")?.let { parseAmount(it, currency) },
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

    /**
     * The value of an amount written in any of the ways [AMT] accepts. The last separator followed by 1-2 digits is the
     * decimal point ("1.234,56", "45,00", "1,234.56"). A single separator followed by exactly 3 digits is a thousands
     * separator ("1,500", "1.500"), except in 3-decimal currencies ("KWD 12.500") or after a zero ("0.500").
     */
    /**
     * An amount you typed ("1,234.50", "1.234,50", "45,5", "-200"), read the way [currency] writes amounts
     * (your home currency by default). Null when it isn't an amount.
     */
    fun parseTyped(text: String, currency: String? = homeCurrency): Decimal? {
        var t = text.trim().replace(" ", "").replace("\u00A0", "")
        val negative = t.startsWith("-")
        t = t.removePrefix("-").removePrefix("+")
        if (t.isEmpty() || t.none { it.isDigit() } || !t.all { it.isDigit() || it == '.' || it == ',' || it == '\'' }) return null
        val v = runCatching { parseAmount(t, currency) }.getOrNull() ?: return null
        return if (negative) -v else v
    }

    fun parseAmount(s: String, currency: String? = null): Decimal {
        var t = s.replace(" ", "").replace("\u00A0", "").replace("\u202F", "").replace("'", "").trimEnd('.')
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val threeDecimals = currency?.uppercase() in Currencies.threeDecimals
        fun fraction(i: Int) = t.length - i - 1
        t = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastComma > lastDot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lastComma >= 0 -> {
                val single = t.count { it == ',' } == 1
                // Gulf banks write "KWD 1,250" for one thousand two hundred and fifty: a comma is never their decimal point.
                if (single && fraction(lastComma) in 1..2) t.replace(',', '.') else t.replace(",", "")
            }
            lastDot >= 0 -> {
                val single = t.count { it == '.' } == 1
                val thousands = !single || (fraction(lastDot) == 3 && !threeDecimals && !t.startsWith("0") && !t.startsWith("."))
                if (thousands) t.replace(".", "") else t
            }
            else -> t
        }
        return Decimal(t)
    }

    private val countrySuffix = Regex("""(?:\s+(?:ARE|AE|UAE))+$""", RegexOption.IGNORE_CASE)

    /** "LIVA INS B S C CLOSED    ABU DHABI    AE" -> "LIVA INS B S C CLOSED"; "... CAFE DUBAI ARE" -> "... CAFE DUBAI". */
    fun cleanMerchant(raw: String): String {
        val firstChunk = raw.trim().split(Regex("""\s{2,}""")).first()
        return firstChunk.replace(countrySuffix, "").trim().trimEnd(',', '.', '-').trim()
    }

    private val splitTime = Regex("""^(.*?)(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([AaPp])\.?[Mm]\.?)?)?$""")
    private val weekdayPrefix = Regex("""^(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\s+""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""^(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})$""")
    private val isoDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val monthFirst = Regex("""^([A-Za-z]{3,9})\.?\s+(\d{1,2}),?\s+(\d{4})$""")
    private val dayFirst = Regex("""^(\d{1,2})\.?(?:\s+|[/-])(?:de\s+)?([A-Za-zÀ-ÿ]{3,10})\.?,?(?:\s+|[/-])(?:de\s+)?(\d{2,4})$""", RegexOption.IGNORE_CASE)
    private val compact = Regex("""^(\d{1,2})([A-Za-z]{3})(\d{2}|\d{4})$""") // 11Jun25, 07Jul2025
    /** Month name beginnings (longest match wins) in English, German, French, Spanish, Portuguese, Italian and Dutch. */
    private val monthNames: List<Pair<String, Int>> = listOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        "mär" to 3, "mrz" to 3, "mai" to 5, "okt" to 10, "dez" to 12, // German
        "janv" to 1, "févr" to 2, "fevr" to 2, "fév" to 2, "avr" to 4, "juin" to 6, "juil" to 7, "août" to 8, "aout" to 8, "déc" to 12, // French
        "ene" to 1, "abr" to 4, "ago" to 8, "dic" to 12, "sept" to 9, // Spanish
        "fev" to 2, "set" to 9, "out" to 10, // Portuguese
        "gen" to 1, "mag" to 5, "giu" to 6, "lug" to 7, "ott" to 10, // Italian
        "mrt" to 3, "mei" to 5, // Dutch
    )

    private fun year(raw: String) = raw.toInt().let { if (it < 100) 2000 + it else it }

    /**
     * Day-first dates as used by UAE banks, with an optional time (24h or AM/PM) and weekday:
     * 13/09/2026 11:58:47 · 08-SEP-2026, 07:42:23 AM · Tuesday, 7 July 2026, 3:16 pm · May 25 2026 11:02AM · 25/Mar/2026 01:40.
     * Returns the date-time and whether a time was present.
     */
    fun parseDateTime(raw: String): Pair<DateTime, Boolean>? = runCatching { parseDateTimeOrThrow(raw) }.getOrNull()

    private fun parseDateTimeOrThrow(raw: String): Pair<DateTime, Boolean>? {
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

    private fun parseDate(s: String): CalendarDate? = runCatching { parseDateOrThrow(s) }.getOrNull()

    private fun parseDateOrThrow(s: String): CalendarDate? {
        isoDate.matchEntire(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return CalendarDate.of(y.toInt(), mo.toInt(), d.toInt())
        }
        numericDate.matchEntire(s)?.let { m ->
            val (a, b, y) = m.destructured
            val first = a.toInt()
            val second = b.toInt()
            // Unambiguous dates read the same everywhere; otherwise the region's usual order.
            val (d, mo) = when {
                first > 12 -> first to second
                second > 12 -> second to first
                monthFirstDates -> second to first
                else -> first to second
            }
            return CalendarDate.of(year(y), mo, d)
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

    private fun monthIndex(name: String): Int? {
        val n = name.lowercase().trimEnd('.')
        return monthNames.filter { n.startsWith(it.first) }.maxByOrNull { it.first.length }?.second
    }
}

/** Money helpers shared by the app. Amounts are stored as minor units (fils/cents). */
object Money {
    /** Rounded half up to fils / cents. Throws ArithmeticException if it doesn't fit. */
    fun toMinor(amount: Decimal): Long = amount.toMinor()

    fun fromMinor(minor: Long): Decimal = Decimal.valueOf(minor, 2)

    /** Returns (AED minor units, isEstimate). Unknown currency -> null. */
    fun toAedMinor(amount: Decimal, currency: String, rates: Map<String, Decimal> = BankRules.fxToAed): Pair<Long, Boolean>? =
        toHomeMinor(amount, currency, rates, SmsParser.homeCurrency)

    /**
     * [amount] in [home] (minor units) and whether it's an estimate. [rates] are "1 unit = x AED" (AED is the pivot).
     * Same currency: exact. Home AED: amount × rate, exact decimals (as before). Otherwise through AED: an estimate
     * anyway, so a floating-point division is fine. Unknown currency or no rate -> null.
     */
    fun toHomeMinor(amount: Decimal, currency: String, rates: Map<String, Decimal>, home: String): Pair<Long, Boolean>? {
        val c = currency.uppercase()
        val h = home.uppercase()
        if (c == h) return toMinor(amount) to false
        val rc = if (c == Currencies.PIVOT) Decimal.ONE else rates[c] ?: return null
        if (h == Currencies.PIVOT) return toMinor(amount * rc) to true
        val rh = (if (h == Currencies.PIVOT) Decimal.ONE else rates[h] ?: return null).toDouble()
        if (rh <= 0.0) return null
        val v = (amount * rc).toDouble() / rh
        return kotlin.math.round(v * 100).toLong() to true
    }
}
