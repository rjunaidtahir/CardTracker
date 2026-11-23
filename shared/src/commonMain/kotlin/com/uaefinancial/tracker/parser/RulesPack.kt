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
 */
object RulesPack {
    const val MAX_BYTES = 262_144
    private const val MAX_BANKS = 600
    private const val MAX_RULES = 3000
    private const val MAX_PATTERN = 700
    private const val MAX_IGNORE = 200
    private const val MAX_WORDS = 100
    private const val MAX_DISABLE = 20
    private const val MAX_SOFT_BANKS = 50

    /** Ordinary purchase and debit messages that no ignore rule may swallow. */
    private val canaries = listOf(
        "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00",
        "AED 100.00 debited from your account XXXX1234 on 12/10/2026",
        "Dear Customer, You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to 9876****5432 on 12-Oct-2026",
    )

    data class PackRule(val bank: String, val rule: Rule)
    class Words(val debit: List<String> = emptyList(), val credit: List<String> = emptyList(), val transfer: List<String> = emptyList())
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
    )

    /** A request to send certain still-unread messages again with more words kept: by the shape's hash, or for every unread message of a bank. */
    data class SendMore(val hashes: Set<String> = emptySet(), val banks: Set<String> = emptySet(), val layoutBanks: Set<String> = emptySet())

    sealed class Outcome {
        data class Ok(val pack: Pack) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    /** Parses and fully validates [json]. [currentVersion] is the newest version this phone already accepted. */
    fun parse(json: String, currentVersion: Int = 0): Outcome {
        if (json.length > MAX_BYTES) return Outcome.Rejected("file too large")
        val root = try { MiniJson.parse(json) } catch (e: MiniJson.JsonError) { return Outcome.Rejected("not valid JSON: ${e.message}") }
        val o = root as? Map<*, *> ?: return Outcome.Rejected("not an object")
        val version = (o["version"] as? Long)?.toInt() ?: return Outcome.Rejected("no version")
        if (version < currentVersion) return Outcome.Rejected("older than the rules already in use")
        val minEngine = (o["minEngine"] as? Long)?.toInt() ?: 0
        if (minEngine > SmsParser.ENGINE_VERSION) return Outcome.Rejected("needs a newer app")

        val banks = mutableListOf<GlobalBanks.DirBank>()
        val bankList = o["banks"] as? List<*> ?: emptyList<Any?>()
        if (bankList.size > MAX_BANKS) return Outcome.Rejected("too many banks")
        for (b in bankList) {
            val m = b as? Map<*, *> ?: return Outcome.Rejected("bad bank entry")
            val name = cleanText(m["name"], 60) ?: return Outcome.Rejected("bank without a name")
            val country = (m["country"] as? String)?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
                ?: return Outcome.Rejected("bank $name: bad country")
            val aliases = strings(m["aliases"], 20, 60) ?: return Outcome.Rejected("bank $name: bad aliases")
            val ids = strings(m["senderIds"], 20, 30) ?: return Outcome.Rejected("bank $name: bad sender IDs")
            // A sender ID of two letters or fewer would match far too much.
            if (ids.any { SmsParser.normalizeSender(it).length < 3 }) return Outcome.Rejected("bank $name: sender ID too short")
            val track = m["track"] == true
            if (track) {
                if (ids.isEmpty()) return Outcome.Rejected("bank $name: tracked but no sender IDs")
                // Tracked IDs must be a printed sender name or a 3-6 digit short code, never a phone number.
                if (ids.any { !trackableId(it) }) return Outcome.Rejected("bank $name: sender ID not allowed")
            }
            banks += GlobalBanks.DirBank(name, country, aliases, ids, track)
        }

        val knownNames = (BankRules.banks.map { it.name } + GlobalBanks.banks.map { it.name } + banks.map { it.name })
            .map { it.trim().lowercase() }.toSet()
        val rules = mutableListOf<PackRule>()
        val ruleList = o["rules"] as? List<*> ?: emptyList<Any?>()
        if (ruleList.size > MAX_RULES) return Outcome.Rejected("too many rules")
        val ids = mutableSetOf<String>()
        for (r in ruleList) {
            val m = r as? Map<*, *> ?: return Outcome.Rejected("bad rule entry")
            val bank = cleanText(m["bank"], 60) ?: return Outcome.Rejected("rule without a bank")
            if (bank.lowercase() !in knownNames) return Outcome.Rejected("rule for unknown bank $bank")
            val id = (cleanText(m["id"], 64) ?: return Outcome.Rejected("rule without an id"))
            if (!ids.add(id)) return Outcome.Rejected("duplicate rule id $id")
            val kind = enumOf<RuleKind>(m["kind"]) ?: return Outcome.Rejected("rule $id: bad kind")
            val type = if (m["type"] == null) TxnType.PURCHASE else enumOf<TxnType>(m["type"]) ?: return Outcome.Rejected("rule $id: bad type")
            val cardType = if (m["cardType"] == null) CardType.CREDIT else enumOf<CardType>(m["cardType"]) ?: return Outcome.Rejected("rule $id: bad card type")
            val pattern = m["pattern"] as? String ?: return Outcome.Rejected("rule $id: no pattern")
            checkPattern(pattern, kind)?.let { return Outcome.Rejected("rule $id: $it") }
            val fixed = if (m["fixedMerchant"] == null) null else cleanText(m["fixedMerchant"], 60) ?: return Outcome.Rejected("rule $id: bad merchant")
            rules += PackRule(bank, Rule(id = "pack:$id", kind = kind, pattern = pattern, type = type, fixedMerchant = fixed, cardType = cardType))
        }
        // ---- ignore rules: "this kind of message is not a transaction"
        val ignore = mutableListOf<IgnoreRule>()
        val ignoreList = o["ignore"] as? List<*> ?: emptyList<Any?>()
        if (ignoreList.size > MAX_IGNORE) return Outcome.Rejected("too many ignore rules")
        for (r in ignoreList) {
            val m = r as? Map<*, *> ?: return Outcome.Rejected("bad ignore entry")
            val label = cleanText(m["label"], 40) ?: return Outcome.Rejected("ignore rule without a label")
            val pattern = m["pattern"] as? String ?: return Outcome.Rejected("ignore rule $label: no pattern")
            if (pattern.length < 8 || pattern.length > 300) return Outcome.Rejected("ignore rule $label: bad length")
            if (Regex("""\((?:[^()]|\([^()]*\))*[+*}](?:\?)?\)[+*{]""").containsMatchIn(pattern)) return Outcome.Rejected("ignore rule $label: nested repetition")
            val re = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (e: Exception) { return Outcome.Rejected("ignore rule $label: does not compile") }
            if (canaries.any { re.containsMatchIn(it) }) return Outcome.Rejected("ignore rule $label: would hide ordinary transactions")
            ignore += IgnoreRule(label, pattern)
        }

        // ---- extra wording
        val wordsObj = o["words"] as? Map<*, *>
        fun wordList(key: String): List<String>? {
            val l = wordsObj?.get(key) ?: return emptyList()
            val list = l as? List<*> ?: return null
            if (list.size > MAX_WORDS) return null
            return list.map { w -> (w as? String)?.trim()?.lowercase()?.takeIf { it.length in 3..20 && it.all(Char::isLetter) } ?: return null }
        }
        val debitW = wordList("debit") ?: return Outcome.Rejected("bad debit words")
        val creditW = wordList("credit") ?: return Outcome.Rejected("bad credit words")
        val transferW = wordList("transfer") ?: return Outcome.Rejected("bad transfer words")

        // ---- category words: plain shop-name words (never patterns) for names the built-in keywords file under Other
        val categories = mutableListOf<Pair<Long, List<String>>>()
        val catList = o["categories"] as? List<*> ?: emptyList<Any?>()
        if (catList.size > MAX_CATEGORY_GROUPS) return Outcome.Rejected("too many category groups")
        var wordTotal = 0
        for (c in catList) {
            val m = c as? Map<*, *> ?: return Outcome.Rejected("bad category entry")
            val name = (m["category"] as? String)?.trim() ?: return Outcome.Rejected("category entry without a category")
            val id = CategoryRules.defaults.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id?.takeIf { it in CategoryRules.packCategoryIds }
                ?: return Outcome.Rejected("category not allowed: $name")
            val raw = m["words"] as? List<*> ?: return Outcome.Rejected("category $name: no words")
            val words = raw.map { w -> checkCategoryWord(w as? String) ?: return Outcome.Rejected("category $name: bad word") }
            wordTotal += words.size
            if (wordTotal > MAX_CATEGORY_WORDS) return Outcome.Rejected("too many category words")
            categories += id to words
        }

        val sm = o["sendMore"] as? Map<*, *>
        fun smList(key: String, maxItems: Int, maxLen: Int): Set<String>? {
            val l = sm?.get(key) ?: return emptySet()
            val list = l as? List<*> ?: return null
            if (list.size > maxItems) return null
            return list.map { (it as? String)?.trim()?.takeIf { x -> x.length in 3..maxLen } ?: return null }.toSet()
        }
        val smHashes = smList("hashes", 500, 40) ?: return Outcome.Rejected("bad sendMore hashes")
        val smBanks = smList("banks", 50, 60) ?: return Outcome.Rejected("bad sendMore banks")
        val smLayout = smList("layoutBanks", 20, 60) ?: return Outcome.Rejected("bad sendMore layoutBanks")

        // ---- remote off switch: ids of BUILT-IN reading rules only (never ignore entries, never a typo that matches nothing)
        val builtInIds = BankRules.banks.flatMap { b -> b.rules.map { it.id } }.toSet()
        val disableRaw = o["disable"] ?: emptyList<Any?>()
        val disableList = disableRaw as? List<*> ?: return Outcome.Rejected("bad disable list")
        if (disableList.size > MAX_DISABLE) return Outcome.Rejected("too many disabled rules")
        val disable = disableList.map { d ->
            val id = (d as? String)?.trim() ?: return Outcome.Rejected("bad disabled rule id")
            if (id !in builtInIds) return Outcome.Rejected("disable: no built-in rule $id")
            id
        }.toSet()

        // ---- built-in ignore rules to switch off (a wrongly hidden message class), by exact label of an existing built-in ignore rule
        val builtInIgnoreLabels = (BankRules.banks.flatMap { b -> b.ignore.map { it.label } } + BankRules.globalIgnore.map { it.label }).toSet()
        val diRaw = o["disableIgnore"] ?: emptyList<Any?>()
        val diList = diRaw as? List<*> ?: return Outcome.Rejected("bad disableIgnore list")
        if (diList.size > MAX_DISABLE) return Outcome.Rejected("too many disabled ignore rules")
        val disableIgnore = diList.map { d ->
            val label = (d as? String)?.trim() ?: return Outcome.Rejected("bad disabled ignore label")
            if (label !in builtInIgnoreLabels) return Outcome.Rejected("disableIgnore: no built-in ignore rule $label")
            label
        }.toSet()

        // ---- re-read policy: banks whose harmless (date/time, merchant) re-read changes apply the new reading
        val softRaw = (o["readingPolicy"] as? Map<*, *>)?.get("applyNewFor") ?: emptyList<Any?>()
        val softList = softRaw as? List<*> ?: return Outcome.Rejected("bad readingPolicy")
        if (softList.size > MAX_SOFT_BANKS) return Outcome.Rejected("too many banks in readingPolicy")
        val softNew = softList.map { b ->
            val name = (b as? String)?.trim()?.lowercase() ?: return Outcome.Rejected("bad readingPolicy bank")
            if (name !in knownNames) return Outcome.Rejected("readingPolicy: unknown bank $name")
            name
        }.toSet()

        return Outcome.Ok(Pack(version, banks, rules, ignore, Words(debitW, creditW, transferW), categories, SendMore(smHashes, smBanks.map { it.lowercase() }.toSet(), smLayout.map { it.lowercase() }.toSet()), disable, softNew, disableIgnore))
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
    internal fun checkPattern(pattern: String, kind: RuleKind): String? {
        if (pattern.length > MAX_PATTERN) return "pattern too long"
        if (kind == RuleKind.TRANSACTION && !pattern.contains("{AMOUNT}")) return "a transaction rule needs {AMOUNT}"
        // Nested repetition such as (a+)+ or (.*)* can make a regex take forever.
        if (Regex("""\((?:[^()]|\([^()]*\))*[+*}](?:\?)?\)[+*{]""").containsMatchIn(pattern)) return "nested repetition"
        if (Regex("""\.\*.*\.\*.*\.\*.*\.\*""").containsMatchIn(pattern)) return "too many wildcards"
        return try { SmsParser.compile(pattern); null } catch (e: Exception) { "pattern does not compile" }
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

    /** Puts [pack] to work (or clears it, with null). */
    /** Lower-case bank names whose still-unread messages are resent in layout form (digit lengths kept, amounts not collapsed). */
    @kotlin.concurrent.Volatile
    var layoutBanks: Set<String> = emptySet()
        private set

    fun apply(pack: Pack?) {
        layoutBanks = pack?.sendMore?.layoutBanks.orEmpty()
        GlobalBanks.setExtra(pack?.banks.orEmpty())
        SmsParser.setPackSenders(pack?.banks.orEmpty().filter { it.track }.associate { it.name to it.senderIds })
        SmsParser.setPackRules(pack?.rules.orEmpty().groupBy({ it.bank.trim().lowercase() }, { it.rule }))
        SmsParser.setPackIgnore(pack?.ignore.orEmpty())
        CategoryRules.setPackKeywords(pack?.categories.orEmpty(), pack?.version ?: 0)
        SmsParser.setDisabledRules(pack?.disable.orEmpty())
        SmsParser.setDisabledIgnore(pack?.disableIgnore.orEmpty())
        ReadingPolicy.softNewBanks = pack?.softNew.orEmpty()
        SmartParser.setExtraWords(pack?.words?.debit.orEmpty(), pack?.words?.credit.orEmpty(), pack?.words?.transfer.orEmpty())
    }
}

/** Re-read policy delivered by the signed rules file. */
object ReadingPolicy {
    /** Lower-case bank names whose date/time- or merchant-only re-read changes apply the new reading. */
    @kotlin.concurrent.Volatile
    var softNewBanks: Set<String> = emptySet()
}
