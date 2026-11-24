package com.uaefinancial.tracker.parser

/**
 * The signed rules file: new banks (names, sender IDs) and reading rules, as DATA. The apps download it, check its
 * signature (platform code), and hand the text to [parse]; nothing in it is ever run as code.
 *
 * A bank entry with "track": true also makes the app treat its senderIds as that bank's own (no app update needed);
 * if the name is a built-in bank, its verified rules read those messages. IDs: printed names or 3-6 digit codes only.
 *
 * Beyond bank rules the file can carry "ignore" (label + pattern: kinds of messages that are not transactions, refused
 * if they would hide an ordinary purchase) and "words" ({"debit":[…],"credit":[…],"transfer":[…]}: single words a bank
 * uses that the built-in wording lists do not know). Both are optional.
 *
 * Format (all fields below are required unless marked optional):
 * ```
 * { "version": 7, "minEngine": 5,
 *   "banks": [ { "name": "HBL", "country": "PK", "aliases": ["Habib Bank"], "senderIds": ["HBL"] } ],
 *   "rules": [ { "bank": "HBL", "id": "hbl-pos-1", "kind": "TRANSACTION", "type": "PURCHASE", "cardType": "DEBIT",
 *                "pattern": "...{CUR} {AMOUNT}...", "fixedMerchant": "optional" } ] }
 * ```
 * Safety: size and count limits; the version may never go backwards; patterns must compile, stay short and avoid
 * nested repetition; a rule only ever applies to the bank it names, after that bank's built-in rules (so it can add
 * formats but never override a verified built-in rule). Withdrawing a rule from the file withdraws it from every phone
 * the next time it checks (the kill switch). A rejected file changes nothing.
 *
 * ## 2.8: the `ext` section (read by engine 8 and later; 2.6/2.7 phones ignore unknown top-level keys)
 * Everything that uses a 2.8 token, field or section lives under `"ext": {...}` so an older phone never rejects the whole
 * file (it would then lose every later fix). The legacy sections above stay exactly as 2.7 reads them, and the legacy
 * part must keep 2.7's limits (262,144 bytes, 200 ignores, 100 words per list, 20 disables; see [legacyLimitProblems]).
 * ```
 * "ext": {
 *   "levers": {"wordingBanks": true, "phrases": true, ...},  // every 2.8 capability is OFF until its lever is true
 *   "safeMode": false,                 // true: ALL pack rules, ignores, words, wordingBanks (legacy and ext) are ignored
 *   "rules":  [ {..legacy rule fields.., "accountNotNamed": true, "pairGroup": "x", "describesDestination": true,
 *                "pairWindowMinutes": 180, "directionFromBalance": true, "needsTwin": true, "twinWindowMinutes": 1440, "checkUntilPaired": true,
 *                "direction": "IN"|"OUT"} ],          // tokens: also {ACCT}; {CUR} also takes Rs. SR KD $ … (lever curAbbrev)
 *   "ignore": [ {"label": "..", "pattern": "..", "bank": "dib", "unless": "regex", "minWords": 6} ],
 *   "words":  {"debit": [phrases], "credit": [..], "transfer": [..], "notMoney": [..]},   // lever phrases / notMoney
 *   "wordingBanks": [ {"pattern": "regex", "bank": "Emirates Islamic"} ],            // lever wordingBanks
 *   "banks":  [ ..same shape as legacy banks.. ],
 *   "notificationBanks": [ {"package": "com.x", "titlePattern": "regex", "bank": "Wallet", "isAggregator": true} ],
 *   "ask": [ {"bank": "HSBC"|"*", "sender": "DFM", "hash": "<shape hash>"|null, "fields": ["layout", ...], "until": 40} ],
 *   "readingPolicy": {"applyKeyChangeFor": ["adcb"]},   // lever keyChange
 *   "learnedExempt": ["regex"]                          // lever learnedExempt
 * }
 * ```
 * A malformed `ext` never touches the legacy part: the file is accepted, the whole `ext` is ignored and the app reports
 * `event:ext-rejected` (the safer of the two choices: the 2.7-level fixes keep working).
 */
object RulesPack {
    const val MAX_BYTES = 524_288
    private const val MAX_BANKS = 600
    private const val MAX_RULES = 3000
    private const val MAX_PATTERN = 700
    private const val MAX_IGNORE = 600
    private const val MAX_WORDS = 300
    private const val MAX_DISABLE = 100
    private const val MAX_SOFT_BANKS = 50
    private const val MAX_WORDING_BANKS = 150
    private const val MAX_ASK = 100
    private const val MAX_NOTIF_BANKS = 100
    private const val MAX_LEARNED_EXEMPT = 50

    /** What a 2.6 / 2.7 phone accepts (its own limits): the legacy part of every file must stay inside them. */
    object Legacy {
        const val MAX_BYTES = 262_144
        const val MAX_IGNORE = 200
        const val MAX_WORDS = 100
        const val MAX_DISABLE = 20
    }

    /** The 2.8 capabilities a rules file can switch on in `ext.levers`. A lever that is absent (or false) behaves as 2.7. */
    object Lever {
        const val WORDING_BANKS = "wordingBanks"
        const val PAIRS = "pairs"
        const val KEY_CHANGE = "keyChange"
        const val UNLESS = "unless"
        const val ACCT_TOKEN = "acctToken"
        const val CUR_ABBREV = "curAbbrev"
        const val PHRASES = "phrases"
        const val NOT_MONEY = "notMoney"
        const val CROSS_SOURCE_MERGE = "crossSourceMerge"
        const val NOTIFICATION_BANKS = "notificationBanks"
        const val DIRECTION_FROM_BALANCE = "directionFromBalance"
        const val NEEDS_TWIN = "needsTwin"
        const val LEARNED_EXEMPT = "learnedExempt"
        const val ASK = "ask"
        const val STMT_NAMES_FLAG = "stmtNamesFlag"
        /** Default ON (the one exception, spec 12): false restores the 2.7 HSBC Live+ statement reading. */
        const val HSBC_LIVE_PLUS_NAMES = "hsbcLivePlusNames"
        /** Default ON: false stops the 2.8 extra report keys (sid, pv, st, fr, ...) and new report kinds. */
        const val REPORT_EXTRAS = "reportExtras"
        val all = setOf(WORDING_BANKS, PAIRS, KEY_CHANGE, UNLESS, ACCT_TOKEN, CUR_ABBREV, PHRASES, NOT_MONEY, CROSS_SOURCE_MERGE,
            NOTIFICATION_BANKS, DIRECTION_FROM_BALANCE, NEEDS_TWIN, LEARNED_EXEMPT, ASK, STMT_NAMES_FLAG, HSBC_LIVE_PLUS_NAMES, REPORT_EXTRAS)
        /** Levers that are ON unless the file sets them false. */
        val defaultOn = setOf(HSBC_LIVE_PLUS_NAMES, REPORT_EXTRAS)
    }

    /** Report fields an `ask` entry can switch on (section 6). */
    val askFields = setOf("fullerWords", "layout", "senderClass", "stage", "fieldsRead", "wholeAmount", "walletInfo", "pairInfo")

    /** Ordinary purchase and debit messages that no ignore rule may swallow. */
    private val canaries = listOf(
        "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00",
        "AED 100.00 debited from your account XXXX1234 on 12/10/2026",
        "Dear Customer, You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026",
    )

    data class PackRule(val bank: String, val rule: Rule)
    class Words(val debit: List<String> = emptyList(), val credit: List<String> = emptyList(), val transfer: List<String> = emptyList(), val notMoney: List<String> = emptyList())
    data class WordingBank(val pattern: String, val bank: String)
    data class NotificationBank(val pkg: String?, val titlePattern: String?, val bank: String, val isAggregator: Boolean)
    data class Ask(val bank: String?, val sender: String?, val hash: String?, val fields: Set<String>, val until: Int)
    class Levers(private val values: Map<String, Boolean> = emptyMap()) {
        fun on(name: String): Boolean = values[name] ?: (name in Lever.defaultOn)
        override fun toString() = values.toString()
    }
    /** The 2.8 part of a file (`ext`). Null when the file has none or it was rejected ([Pack.extRejected] says why). */
    data class Ext(
        val levers: Levers = Levers(),
        val safeMode: Boolean = false,
        val rules: List<PackRule> = emptyList(),
        val ignore: List<IgnoreRule> = emptyList(),
        val words: Words = Words(),
        val wordingBanks: List<WordingBank> = emptyList(),
        val banks: List<GlobalBanks.DirBank> = emptyList(),
        val notificationBanks: List<NotificationBank> = emptyList(),
        val ask: List<Ask> = emptyList(),
        val applyKeyChangeFor: Set<String> = emptySet(),
        val learnedExempt: List<String> = emptyList(),
    )
    data class Pack(
        val version: Int, val banks: List<GlobalBanks.DirBank>, val rules: List<PackRule>,
        val ignore: List<IgnoreRule> = emptyList(), val words: Words = Words(),
        val categories: List<Pair<Long, List<String>>> = emptyList(),
        val sendMore: SendMore = SendMore(),
        /** Ids of built-in reading rules to switch off (remote off switch). Only ids of existing built-in rules are accepted. */
        val disable: Set<String> = emptySet(),
        /** Banks (lower case) whose date/time- or merchant-only re-read changes apply the new reading instead of keeping the old one. */
        val softNew: Set<String> = emptySet(),
        /** Labels of built-in "not a transaction" (ignore) rules to switch off, so wrongly hidden messages can be read again. */
        val disableIgnore: Set<String> = emptySet(),
        /** 2.8 section, or null. */
        val ext: Ext? = null,
        /** Why `ext` was ignored (the legacy part is still in use), or null. The app reports `event:ext-rejected`. */
        val extRejected: String? = null,
    )

    /** A request to send certain still-unread messages again with more words kept: by the shape's hash, or for every unread message of a bank. */
    data class SendMore(val hashes: Set<String> = emptySet(), val banks: Set<String> = emptySet(), val layoutBanks: Set<String> = emptySet())

    sealed class Outcome {
        data class Ok(val pack: Pack) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    private class Bad(val reason: String) : Exception(reason)
    private fun bad(reason: String): Nothing = throw Bad(reason)

    /**
     * Parses and fully validates [json]. [currentVersion] is the newest version this phone already accepted.
     * [legacyOnly] reads the file the way a 2.7 phone does (no `ext`, 2.7 limits): used by tests and the audit to prove
     * that older phones keep accepting a 2.8 file.
     */
    fun parse(json: String, currentVersion: Int = 0, legacyOnly: Boolean = false): Outcome {
        val maxBytes = if (legacyOnly) Legacy.MAX_BYTES else MAX_BYTES
        if (json.length > maxBytes) return Outcome.Rejected("file too large")
        val root = try { MiniJson.parse(json) } catch (e: MiniJson.JsonError) { return Outcome.Rejected("not valid JSON: ${e.message}") }
        val o = root as? Map<*, *> ?: return Outcome.Rejected("not an object")
        val version = (o["version"] as? Long)?.toInt() ?: return Outcome.Rejected("no version")
        if (version < currentVersion) return Outcome.Rejected("older than the rules already in use")
        val minEngine = (o["minEngine"] as? Long)?.toInt() ?: 0
        if (minEngine > SmsParser.ENGINE_VERSION) return Outcome.Rejected("needs a newer app")
        val legacy = try { parseLegacy(o, version, legacyOnly) } catch (e: Bad) { return Outcome.Rejected(e.reason) }
        if (legacyOnly || o["ext"] == null) return Outcome.Ok(legacy)
        // The 2.8 part: a problem in it never touches the legacy part (the safer choice; reported as event:ext-rejected).
        return try {
            Outcome.Ok(legacy.copy(ext = parseExt(o["ext"], legacy)))
        } catch (e: Bad) {
            Outcome.Ok(legacy.copy(extRejected = e.reason.take(80)))
        } catch (e: Exception) {
            Outcome.Ok(legacy.copy(extRejected = "unreadable"))
        }
    }

    /** Problems that would make a 2.6 / 2.7 phone reject [json] (empty when it is fine for them). Used by the shipped-pack tests and the audit. */
    fun legacyLimitProblems(json: String): List<String> {
        val out = mutableListOf<String>()
        if (json.length > Legacy.MAX_BYTES) out += "file larger than ${Legacy.MAX_BYTES} bytes"
        val o = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return listOf("not an object")
        if (((o["ignore"] as? List<*>)?.size ?: 0) > Legacy.MAX_IGNORE) out += "more than ${Legacy.MAX_IGNORE} legacy ignores"
        val w = o["words"] as? Map<*, *>
        for (k in listOf("debit", "credit", "transfer")) if (((w?.get(k) as? List<*>)?.size ?: 0) > Legacy.MAX_WORDS) out += "more than ${Legacy.MAX_WORDS} legacy $k words"
        if (((o["disable"] as? List<*>)?.size ?: 0) > Legacy.MAX_DISABLE) out += "more than ${Legacy.MAX_DISABLE} legacy disables"
        if (((o["disableIgnore"] as? List<*>)?.size ?: 0) > Legacy.MAX_DISABLE) out += "more than ${Legacy.MAX_DISABLE} legacy disableIgnore"
        if (parse(json, 0, legacyOnly = true) is Outcome.Rejected) out += "rejected by the 2.7 reading: " + (parse(json, 0, legacyOnly = true) as Outcome.Rejected).reason
        return out
    }

    private fun parseBanks(list: Any?): List<GlobalBanks.DirBank> {
        val banks = mutableListOf<GlobalBanks.DirBank>()
        val bankList = list as? List<*> ?: emptyList<Any?>()
        if (bankList.size > MAX_BANKS) bad("too many banks")
        for (b in bankList) {
            val m = b as? Map<*, *> ?: bad("bad bank entry")
            val name = cleanText(m["name"], 60) ?: bad("bank without a name")
            val country = (m["country"] as? String)?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
                ?: bad("bank $name: bad country")
            val aliases = strings(m["aliases"], 20, 60) ?: bad("bank $name: bad aliases")
            val ids = strings(m["senderIds"], 20, 30) ?: bad("bank $name: bad sender IDs")
            // A sender ID of two letters or fewer would match far too much.
            if (ids.any { SmsParser.normalizeSender(it).length < 3 }) bad("bank $name: sender ID too short")
            val track = m["track"] == true
            if (track) {
                if (ids.isEmpty()) bad("bank $name: tracked but no sender IDs")
                // Tracked IDs must be a printed sender name or a 3-6 digit short code, never a phone number.
                if (ids.any { !trackableId(it) }) bad("bank $name: sender ID not allowed")
            }
            banks += GlobalBanks.DirBank(name, country, aliases, ids, track)
        }
        return banks
    }

    private fun knownNames(extra: List<GlobalBanks.DirBank>): Set<String> =
        (BankRules.banks.map { it.name } + GlobalBanks.banks.map { it.name } + extra.map { it.name }).map { it.trim().lowercase() }.toSet()

    private fun parseRules(list: Any?, known: Set<String>, ext: Boolean, ids: MutableSet<String>): List<PackRule> {
        val rules = mutableListOf<PackRule>()
        val ruleList = list as? List<*> ?: emptyList<Any?>()
        if (ruleList.size > MAX_RULES) bad("too many rules")
        for (r in ruleList) {
            val m = r as? Map<*, *> ?: bad("bad rule entry")
            val bank = cleanText(m["bank"], 60) ?: bad("rule without a bank")
            if (bank.lowercase() !in known) bad("rule for unknown bank $bank")
            val id = (cleanText(m["id"], 64) ?: bad("rule without an id"))
            if (!ids.add(id)) bad("duplicate rule id $id")
            val kind = enumOf<RuleKind>(m["kind"]) ?: bad("rule $id: bad kind")
            val type = if (m["type"] == null) TxnType.PURCHASE else enumOf<TxnType>(m["type"]) ?: bad("rule $id: bad type")
            val cardType = if (m["cardType"] == null) CardType.CREDIT else enumOf<CardType>(m["cardType"]) ?: bad("rule $id: bad card type")
            val pattern = m["pattern"] as? String ?: bad("rule $id: no pattern")
            checkPattern(pattern, kind, ext)?.let { bad("rule $id: $it") }
            val fixed = if (m["fixedMerchant"] == null) null else cleanText(m["fixedMerchant"], 60) ?: bad("rule $id: bad merchant")
            var rule = Rule(id = "pack:$id", kind = kind, pattern = pattern, type = type, fixedMerchant = fixed, cardType = cardType)
            if (ext) {
                // 2.8 fields (only here: a 2.7 phone reading them in the legacy section would silently drop their meaning).
                fun bool(k: String): Boolean = when (val v = m[k]) { null -> false; is Boolean -> v; else -> bad("rule $id: bad $k") }
                fun int(k: String, range: IntRange, def: Int): Int = when (val v = m[k]) {
                    null -> def
                    is Long -> v.toInt().takeIf { it in range } ?: bad("rule $id: $k out of range")
                    else -> bad("rule $id: bad $k")
                }
                val pair = if (m["pairGroup"] == null) null else cleanText(m["pairGroup"], 40) ?: bad("rule $id: bad pairGroup")
                val direction = when (val d = m["direction"]) { null -> null; "IN", "OUT" -> d as String; else -> bad("rule $id: bad direction") }
                if (kind == RuleKind.STATEMENT && (pair != null || bool("needsTwin"))) bad("rule $id: a statement rule cannot pair")
                rule = rule.copy(
                    accountNotNamed = bool("accountNotNamed"),
                    pairGroup = pair,
                    describesDestination = if (m["describesDestination"] == null) null else bool("describesDestination"),
                    pairWindowMinutes = int("pairWindowMinutes", 1..1440, 180),
                    directionFromBalance = bool("directionFromBalance"),
                    needsTwin = bool("needsTwin"),
                    twinWindowMinutes = int("twinWindowMinutes", 1..10080, 1440),
                    checkUntilPaired = bool("checkUntilPaired"),
                    direction = direction,
                    fromExt = true,
                )
            }
            rules += PackRule(bank, rule)
        }
        return rules
    }

    private val nestedRepetition = Regex("""\((?:[^()]|\([^()]*\))*[+*}](?:\?)?\)[+*{]""")

    /** A regex from the rules file that is used on message text (ignores, wordingBanks, unless): same safety checks as ignores. */
    private fun safeRegex(pattern: String, what: String, minLen: Int, maxLen: Int, canaryCheck: Boolean): Regex {
        if (pattern.length < minLen || pattern.length > maxLen) bad("$what: bad length")
        if (nestedRepetition.containsMatchIn(pattern)) bad("$what: nested repetition")
        val re = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { bad("$what: does not compile") }
        if (canaryCheck && canaries.any { re.containsMatchIn(it) }) bad("$what: would hide ordinary transactions")
        return re
    }

    /** Distinct literal words (3+ letters) in a regex, ignoring escapes, classes and group syntax: how specific an ignore is. */
    internal fun literalWords(pattern: String): Int {
        val t = pattern.replace(Regex("""\\[a-zA-Z]"""), " ").replace(Regex("""\[[^\]]*\]"""), " ").replace(Regex("""\(\?[:=!<>a-z]*"""), " ")
        return Regex("""[A-Za-z]{3,}""").findAll(t).map { it.value.lowercase() }.toSet().size
    }

    private fun parseIgnores(list: Any?, ext: Boolean, max: Int, known: Set<String>): List<IgnoreRule> {
        val ignore = mutableListOf<IgnoreRule>()
        val ignoreList = list as? List<*> ?: emptyList<Any?>()
        if (ignoreList.size > max) bad("too many ignore rules")
        for (r in ignoreList) {
            val m = r as? Map<*, *> ?: bad("bad ignore entry")
            val label = cleanText(m["label"], 40) ?: bad("ignore rule without a label")
            val pattern = m["pattern"] as? String ?: bad("ignore rule $label: no pattern")
            safeRegex(pattern, "ignore rule $label", 8, 300, canaryCheck = true)
            if (!ext) { ignore += IgnoreRule(label, pattern); continue }
            val bank = if (m["bank"] == null) null else (cleanText(m["bank"], 60)?.lowercase() ?: bad("ignore rule $label: bad bank"))
            if (bank != null && bank !in known) bad("ignore rule $label: unknown bank $bank")
            val unless = if (m["unless"] == null) null else (m["unless"] as? String ?: bad("ignore rule $label: bad unless"))
            unless?.let { safeRegex(it, "ignore rule $label unless", 3, 200, canaryCheck = false) }
            val minWords = when (val v = m["minWords"]) { null -> 0; is Long -> v.toInt().takeIf { it in 0..20 } ?: bad("ignore rule $label: bad minWords"); else -> bad("ignore rule $label: bad minWords") }
            if (literalWords(pattern) < minWords) bad("ignore rule $label: fewer than $minWords distinctive words")
            ignore += IgnoreRule(label, pattern, bank = bank, unless = unless)
        }
        return ignore
    }

    private val phraseRe = Regex("""[\p{L}][\p{L} .']{1,38}[\p{L}.]""")

    private fun parseWords(obj: Any?, phrases: Boolean, max: Int): Words {
        val wordsObj = obj as? Map<*, *>
        if (obj != null && wordsObj == null) bad("bad words")
        fun wordList(key: String): List<String> {
            val l = wordsObj?.get(key) ?: return emptyList()
            val list = l as? List<*> ?: bad("bad $key words")
            if (list.size > max) bad("bad $key words")
            return list.map { w ->
                val t = (w as? String)?.trim()?.lowercase()?.replace(Regex("""\s+"""), " ") ?: bad("bad $key words")
                val ok = if (phrases) t.length in 3..40 && phraseRe.matches(t) else t.length in 3..20 && t.all(Char::isLetter)
                if (!ok) bad("bad $key words")
                t
            }
        }
        return Words(wordList("debit"), wordList("credit"), wordList("transfer"), if (phrases) wordList("notMoney") else emptyList())
    }

    private fun parseLegacy(o: Map<*, *>, version: Int, legacyOnly: Boolean): Pack {
        val banks = parseBanks(o["banks"])
        val knownNames = knownNames(banks)
        val rules = parseRules(o["rules"], knownNames, ext = false, ids = mutableSetOf())
        // ---- ignore rules: "this kind of message is not a transaction"
        val ignore = parseIgnores(o["ignore"], ext = false, max = if (legacyOnly) Legacy.MAX_IGNORE else MAX_IGNORE, known = knownNames)
        // ---- extra wording (single words)
        val words = parseWords(o["words"], phrases = false, max = if (legacyOnly) Legacy.MAX_WORDS else MAX_WORDS)

        // ---- category words: plain shop-name words (never patterns) for names the built-in keywords file under Other
        val categories = mutableListOf<Pair<Long, List<String>>>()
        val catList = o["categories"] as? List<*> ?: emptyList<Any?>()
        if (catList.size > MAX_CATEGORY_GROUPS) bad("too many category groups")
        var wordTotal = 0
        for (c in catList) {
            val m = c as? Map<*, *> ?: bad("bad category entry")
            val name = (m["category"] as? String)?.trim() ?: bad("category entry without a category")
            val id = CategoryRules.defaults.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.takeIf { it in CategoryRules.packCategoryIds }
                ?: bad("category not allowed: $name")
            val raw = m["words"] as? List<*> ?: bad("category $name: no words")
            val words2 = raw.map { w -> checkCategoryWord(w as? String) ?: bad("category $name: bad word") }
            wordTotal += words2.size
            if (wordTotal > MAX_CATEGORY_WORDS) bad("too many category words")
            categories += id to words2
        }

        val sm = o["sendMore"] as? Map<*, *>
        fun smList(key: String, maxItems: Int, maxLen: Int): Set<String>? {
            val l = sm?.get(key) ?: return emptySet()
            val list = l as? List<*> ?: return null
            if (list.size > maxItems) return null
            return list.map { (it as? String)?.trim()?.takeIf { x -> x.length in 3..maxLen } ?: return null }.toSet()
        }
        val smHashes = smList("hashes", 500, 40) ?: bad("bad sendMore hashes")
        val smBanks = smList("banks", 50, 60) ?: bad("bad sendMore banks")
        val smLayout = smList("layoutBanks", 20, 60) ?: bad("bad sendMore layoutBanks")

        val maxDisable = if (legacyOnly) Legacy.MAX_DISABLE else MAX_DISABLE
        // ---- remote off switch: ids of BUILT-IN reading rules only (never ignore entries, never a typo that matches nothing)
        val builtInIds = BankRules.banks.flatMap { b -> b.rules.map { it.id } }.toSet()
        val disableList = (o["disable"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad disable list")
        if (disableList.size > maxDisable) bad("too many disabled rules")
        val disable = disableList.map { d ->
            val id = (d as? String)?.trim() ?: bad("bad disabled rule id")
            if (id !in builtInIds) bad("disable: no built-in rule $id")
            id
        }.toSet()

        // ---- built-in ignore rules to switch off (a wrongly hidden message class), by exact label of an existing built-in ignore rule
        val builtInIgnoreLabels = (BankRules.banks.flatMap { b -> b.ignore.map { it.label } } + BankRules.globalIgnore.map { it.label }).toSet()
        val diList = (o["disableIgnore"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad disableIgnore list")
        if (diList.size > maxDisable) bad("too many disabled ignore rules")
        val disableIgnore = diList.map { d ->
            val label = (d as? String)?.trim() ?: bad("bad disabled ignore label")
            if (label !in builtInIgnoreLabels) bad("disableIgnore: no built-in ignore rule $label")
            label
        }.toSet()

        // ---- re-read policy: banks whose harmless (date/time, merchant) re-read changes apply the new reading
        val softNew = bankNameList((o["readingPolicy"] as? Map<*, *>)?.get("applyNewFor"), knownNames, "readingPolicy")

        return Pack(version, banks, rules, ignore, words, categories, SendMore(smHashes, smBanks.map { it.lowercase() }.toSet(), smLayout.map { it.lowercase() }.toSet()), disable, softNew, disableIgnore)
    }

    private fun bankNameList(raw: Any?, known: Set<String>, what: String): Set<String> {
        val list = (raw ?: emptyList<Any?>()) as? List<*> ?: bad("bad $what")
        if (list.size > MAX_SOFT_BANKS) bad("too many banks in $what")
        return list.map { b ->
            val name = (b as? String)?.trim()?.lowercase() ?: bad("bad $what bank")
            if (name !in known) bad("$what: unknown bank $name")
            name
        }.toSet()
    }

    private fun parseExt(raw: Any?, legacy: Pack): Ext {
        val e = raw as? Map<*, *> ?: bad("ext is not an object")
        val leverMap = (e["levers"] ?: emptyMap<Any?, Any?>()) as? Map<*, *> ?: bad("bad levers")
        val levers = leverMap.entries.associate { (k, v) ->
            val name = k as? String ?: bad("bad lever")
            if (name !in Lever.all) bad("unknown lever $name")
            name to (v as? Boolean ?: bad("lever $name: not true/false"))
        }
        val safeMode = when (val v = e["safeMode"]) { null -> false; is Boolean -> v; else -> bad("bad safeMode") }
        val banks = parseBanks(e["banks"])
        val known = knownNames(legacy.banks + banks)
        val rules = parseRules(e["rules"], known, ext = true, ids = legacy.rules.map { it.rule.id.removePrefix("pack:") }.toMutableSet())
        val ignore = parseIgnores(e["ignore"], ext = true, max = MAX_IGNORE, known = known)
        val words = parseWords(e["words"], phrases = true, max = MAX_WORDS)
        val wbList = (e["wordingBanks"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad wordingBanks")
        if (wbList.size > MAX_WORDING_BANKS) bad("too many wordingBanks")
        val wordingBanks = wbList.map { w ->
            val m = w as? Map<*, *> ?: bad("bad wordingBanks entry")
            val bank = cleanText(m["bank"], 60) ?: bad("wordingBanks entry without a bank")
            if (bank.lowercase() !in known) bad("wordingBanks: unknown bank $bank")
            val pattern = m["pattern"] as? String ?: bad("wordingBanks $bank: no pattern")
            safeRegex(pattern, "wordingBanks $bank", 8, 300, canaryCheck = false)
            WordingBank(pattern, bank)
        }
        val nbList = (e["notificationBanks"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad notificationBanks")
        if (nbList.size > MAX_NOTIF_BANKS) bad("too many notificationBanks")
        val notificationBanks = nbList.map { w ->
            val m = w as? Map<*, *> ?: bad("bad notificationBanks entry")
            val bank = cleanText(m["bank"], 60) ?: bad("notificationBanks entry without a bank")
            if (bank.lowercase() !in known) bad("notificationBanks: unknown bank $bank")
            val pkg = if (m["package"] == null) null else (m["package"] as? String)?.trim()?.takeIf { Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""").matches(it) && it.length <= 120 } ?: bad("notificationBanks $bank: bad package")
            val title = if (m["titlePattern"] == null) null else (m["titlePattern"] as? String ?: bad("notificationBanks $bank: bad titlePattern"))
            title?.let { safeRegex(it, "notificationBanks $bank title", 3, 200, canaryCheck = false) }
            if (pkg == null && title == null) bad("notificationBanks $bank: needs a package or a titlePattern")
            NotificationBank(pkg, title, bank, m["isAggregator"] == true)
        }
        val askList = (e["ask"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad ask")
        if (askList.size > MAX_ASK) bad("too many ask entries")
        val ask = askList.map { w ->
            val m = w as? Map<*, *> ?: bad("bad ask entry")
            val bank = (m["bank"] as? String)?.trim()?.takeIf { it == "*" || it.lowercase() in known }
                ?: if (m["bank"] == null) null else bad("ask: unknown bank ${m["bank"]}")
            val sender = if (m["sender"] == null) null else (m["sender"] as? String)?.trim()?.takeIf { trackableId(it) } ?: bad("ask: bad sender")
            val hash = if (m["hash"] == null) null else (m["hash"] as? String)?.trim()?.takeIf { it.length in 3..40 } ?: bad("ask: bad hash")
            if (bank == null && sender == null && hash == null) bad("ask: needs a bank, a sender or a hash")
            val fields = (m["fields"] as? List<*> ?: bad("ask: no fields")).map { f -> (f as? String)?.takeIf { it in askFields } ?: bad("ask: unknown field $f") }.toSet()
            val until = (m["until"] as? Long)?.toInt() ?: bad("ask: no until")
            Ask(bank?.let { if (it == "*") "*" else it.lowercase() }, sender?.uppercase(), hash, fields, until)
        }
        val keyChange = bankNameList((e["readingPolicy"] as? Map<*, *>)?.get("applyKeyChangeFor"), known, "applyKeyChangeFor")
        val exList = (e["learnedExempt"] ?: emptyList<Any?>()) as? List<*> ?: bad("bad learnedExempt")
        if (exList.size > MAX_LEARNED_EXEMPT) bad("too many learnedExempt")
        val learnedExempt = exList.map { x -> (x as? String ?: bad("bad learnedExempt")).also { safeRegex(it, "learnedExempt", 8, 300, canaryCheck = false) } }
        return Ext(Levers(levers), safeMode, rules, ignore, words, wordingBanks, banks, notificationBanks, ask, keyChange, learnedExempt)
    }

    private const val MAX_CATEGORY_GROUPS = 40
    private const val MAX_CATEGORY_WORDS = 3000

    /** Words so common that a shop-name keyword made of them would catch unrelated names. */
    private val genericCategoryWords = setOf(
        "STORE", "STORES", "SHOP", "SHOPS", "MARKET", "TRADING", "CENTER", "CENTRE", "GROUP", "LLC", "FZE", "FZCO", "EST", "COMPANY",
        "SERVICES", "SERVICE", "DUBAI", "ABU DHABI", "SHARJAH", "AJMAN", "UAE", "AL", "THE", "AND", "INTERNATIONAL", "GENERAL", "BRANCH",
    )

    /** The word tidied (upper case), or null when it is not an acceptable shop-name keyword. */
    internal fun checkCategoryWord(w: String?): String? {
        val s = w?.trim()?.uppercase()?.replace(Regex("\\s+"), " ") ?: return null
        if (s.length !in 4..30) return null
        if (!Regex("[A-Z0-9][A-Z0-9 &'.-]*").matches(s)) return null
        if (s.count { it.isLetter() } < 4) return null
        if (s in genericCategoryWords) return null
        return s
    }

    private val trackName = Regex("[A-Za-z][A-Za-z0-9._& -]{2,29}")
    private val trackCode = Regex("[0-9]{3,6}")

    internal fun trackableId(id: String): Boolean {
        val s = id.trim()
        if (trackCode.matches(s)) return true
        if (!trackName.matches(s)) return false
        return s.count { it.isDigit() } <= s.length / 2
    }

    /** Null when the pattern is acceptable, otherwise why not. */
    internal fun checkPattern(pattern: String, kind: RuleKind, ext: Boolean = false): String? {
        if (pattern.length > MAX_PATTERN) return "pattern too long"
        if (kind == RuleKind.TRANSACTION && !pattern.contains("{AMOUNT}")) return "a transaction rule needs {AMOUNT}"
        // Nested repetition such as (a+)+ or (.*)* can make a regex take forever.
        if (Regex("""\((?:[^()]|\([^()]*\))*[+*}](?:\?)?\)[+*{]""").containsMatchIn(pattern)) return "nested repetition"
        if (Regex("""\.\*.*\.\*.*\.\*.*\.\*""").containsMatchIn(pattern)) return "too many wildcards"
        return try { SmsParser.compile(pattern, ext); null } catch (e: Exception) { "pattern does not compile" }
    }

    private fun cleanText(v: Any?, max: Int): String? =
        (v as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c < ' ' } }

    private fun strings(v: Any?, maxCount: Int, maxLen: Int): List<String>? {
        if (v == null) return emptyList()
        val l = v as? List<*> ?: return null
        if (l.size > maxCount) return null
        return l.map { cleanText(it, maxLen) ?: return null }
    }

    private inline fun <reified E : Enum<E>> enumOf(v: Any?): E? =
        (v as? String)?.let { s -> enumValues<E>().firstOrNull { it.name == s } }

    /** Lower-case bank names whose still-unread messages are resent in layout form (digit lengths kept, amounts not collapsed). */
    @kotlin.concurrent.Volatile
    var layoutBanks: Set<String> = emptySet()
        private set

    /** The 2.8 switches in force (all off without an `ext` section, except [Lever.defaultOn]). */
    @kotlin.concurrent.Volatile
    var levers: Levers = Levers()
        private set

    /** True while the file in use asks for safe mode (every pack rule, ignore, word and wordingBank is ignored). */
    @kotlin.concurrent.Volatile
    var safeMode: Boolean = false
        private set

    /** `ask` entries in force (empty unless the ask lever is on). */
    @kotlin.concurrent.Volatile
    var ask: List<Ask> = emptyList()
        private set

    /** Notification sources declared by the file (empty unless the notificationBanks lever is on). */
    @kotlin.concurrent.Volatile
    var notificationBanks: List<NotificationBank> = emptyList()
        private set

    /**
     * The bank a notification belongs to (2.8 spec 4.3), for an app the user already chose to read: the first entry whose
     * package is the app's and whose title pattern matches (each when given) the notification title. Null when the lever is off or nothing matches,
     * and the app's own name is used as before.
     */
    fun notificationBankFor(pkg: String, title: String): NotificationBank? = notificationBanks.firstOrNull { nb ->
        (nb.pkg != null || nb.titlePattern != null) &&
            (nb.pkg == null || nb.pkg == pkg) &&
            (nb.titlePattern == null || runCatching { Regex(nb.titlePattern, RegexOption.IGNORE_CASE).containsMatchIn(title) }.getOrDefault(false))
    }

    /** Version of the file in use (0 when none). */
    @kotlin.concurrent.Volatile
    var version: Int = 0
        private set

    /** Puts [pack] to work (or clears it, with null). */
    /**
     * 2.8 spec 15, audit warning: tracked rules-file banks with no ignore entry of their own (an `ext.ignore` scoped to the
     * bank, or a legacy ignore whose label or pattern names the bank or one of its sender IDs). Such a bank's notices
     * (rewards, expiry, marketing) would flood Needs review, so the loop must ship its ignores before `track: true`.
     */
    fun trackedWithoutIgnore(pack: Pack): List<String> {
        val all = pack.banks + pack.ext?.banks.orEmpty()
        val scoped = pack.ext?.ignore.orEmpty().mapNotNull { it.bank?.trim()?.lowercase() }.toSet()
        val legacy = (pack.ignore + pack.ext?.ignore.orEmpty().filter { it.bank == null }).map { (it.label + " " + it.pattern).lowercase() }
        return all.filter { it.track }.map { it.name }.distinct().filter { name ->
            val b = all.filter { it.name == name }
            val words = (listOf(name) + b.flatMap { it.senderIds }).map { it.trim().lowercase() }.filter { it.length >= 3 }
            name.trim().lowercase() !in scoped && legacy.none { text -> words.any { it in text } }
        }.sorted()
    }

    fun apply(pack: Pack?) {
        val ext = pack?.ext
        val safe = ext?.safeMode == true
        val lv = ext?.levers ?: Levers()
        version = pack?.version ?: 0
        safeMode = safe
        levers = if (safe) Levers() else lv
        layoutBanks = pack?.sendMore?.layoutBanks.orEmpty()
        val allBanks = pack?.banks.orEmpty() + ext?.banks.orEmpty()
        GlobalBanks.setExtra(allBanks)
        SmsParser.setPackSenders(allBanks.filter { it.track }.groupBy({ it.name }, { it.senderIds }).mapValues { (_, l) -> l.flatten().distinct() })
        if (safe) {
            // Safe mode: back to the built-in rules exactly as shipped (no pack rule, ignore, word, wordingBank, off switch or policy).
            SmsParser.setPackRules(emptyMap())
            SmsParser.setPackIgnore(emptyList())
            SmsParser.setWordingBanks(emptyList())
            SmsParser.setDisabledRules(emptySet())
            SmsParser.setDisabledIgnore(emptySet())
            SmsParser.setLearnedExempt(emptyList())
            ReadingPolicy.softNewBanks = emptySet()
            ReadingPolicy.keyChangeBanks = emptySet()
            SmartParser.setExtraWords(emptyList(), emptyList(), emptyList())
            ask = emptyList(); notificationBanks = emptyList()
            SmsParser.setAggregatorBanks(emptySet())
            CategoryRules.setPackKeywords(pack?.categories.orEmpty(), pack?.version ?: 0)
            return
        }
        // 2.8 rules: {ACCT} only with its lever; {CUR} abbreviations only with theirs (otherwise the 2.7 {CUR}).
        val extRules = ext?.rules.orEmpty().filter { lv.on(Lever.ACCT_TOKEN) || !it.rule.pattern.contains("{ACCT}") }
            // A rule that proves its direction from the balance, or that counts only with a twin, is left out while its lever
            // is off (read as written it could guess a direction or count an unattached transfer).
            .filter { !it.rule.directionFromBalance || lv.on(Lever.DIRECTION_FROM_BALANCE) }
            .filter { !it.rule.needsTwin || lv.on(Lever.NEEDS_TWIN) }
        SmsParser.setPackRules(
            pack?.rules.orEmpty().groupBy({ it.bank.trim().lowercase() }, { it.rule }),
            extRules.groupBy({ it.bank.trim().lowercase() }, { it.rule }),
            curAbbrev = lv.on(Lever.CUR_ABBREV),
        )
        // An ext ignore that relies on "unless" is left out entirely while that lever is off (never hide more than intended).
        val extIgnore = ext?.ignore.orEmpty().filter { it.unless == null || lv.on(Lever.UNLESS) }
        SmsParser.setPackIgnore(pack?.ignore.orEmpty() + extIgnore)
        SmsParser.setWordingBanks(if (lv.on(Lever.WORDING_BANKS)) ext?.wordingBanks.orEmpty() else emptyList())
        SmsParser.setWordingLever(lv.on(Lever.WORDING_BANKS))
        CategoryRules.setPackKeywords(pack?.categories.orEmpty(), pack?.version ?: 0)
        SmsParser.setDisabledRules(pack?.disable.orEmpty())
        SmsParser.setDisabledIgnore(pack?.disableIgnore.orEmpty())
        SmsParser.setLearnedExempt(if (lv.on(Lever.LEARNED_EXEMPT)) ext?.learnedExempt.orEmpty() else emptyList())
        ReadingPolicy.softNewBanks = pack?.softNew.orEmpty()
        ReadingPolicy.keyChangeBanks = if (lv.on(Lever.KEY_CHANGE)) ext?.applyKeyChangeFor.orEmpty() else emptySet()
        val ew = ext?.words
        SmartParser.setExtraWords(
            pack?.words?.debit.orEmpty() + (if (lv.on(Lever.PHRASES)) ew?.debit.orEmpty() else emptyList()),
            pack?.words?.credit.orEmpty() + (if (lv.on(Lever.PHRASES)) ew?.credit.orEmpty() else emptyList()),
            pack?.words?.transfer.orEmpty() + (if (lv.on(Lever.PHRASES)) ew?.transfer.orEmpty() else emptyList()),
            if (lv.on(Lever.NOT_MONEY)) ew?.notMoney.orEmpty() else emptyList(),
        )
        ask = if (lv.on(Lever.ASK)) ext?.ask.orEmpty() else emptyList()
        notificationBanks = if (lv.on(Lever.NOTIFICATION_BANKS)) ext?.notificationBanks.orEmpty() else emptyList()
        SmsParser.setAggregatorBanks(notificationBanks.filter { it.isAggregator }.map { it.bank.trim().lowercase() }.toSet())
    }
}

/** Re-read policy delivered by the signed rules file. */
object ReadingPolicy {
    /** Lower-case bank names whose date/time- or merchant-only re-read changes apply the new reading. */
    @kotlin.concurrent.Volatile
    var softNewBanks: Set<String> = emptySet()

    /** 2.8 (`ext.readingPolicy.applyKeyChangeFor`, lever keyChange): banks whose re-reads that change ONLY the card key / last four are applied. */
    @kotlin.concurrent.Volatile
    var keyChangeBanks: Set<String> = emptySet()
}
