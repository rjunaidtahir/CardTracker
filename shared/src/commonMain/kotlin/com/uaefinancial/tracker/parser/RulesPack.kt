package com.uaefinancial.tracker.parser

/**
 * The signed rules file: new banks (names, sender IDs) and reading rules, as DATA. The apps download it, check its
 * signature (platform code), and hand the text to [parse]; nothing in it is ever run as code.
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

    data class PackRule(val bank: String, val rule: Rule)
    data class Pack(val version: Int, val banks: List<GlobalBanks.DirBank>, val rules: List<PackRule>)

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
            banks += GlobalBanks.DirBank(name, country, aliases, ids)
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
        return Outcome.Ok(Pack(version, banks, rules))
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
    fun apply(pack: Pack?) {
        GlobalBanks.setExtra(pack?.banks.orEmpty())
        SmsParser.setPackRules(pack?.rules.orEmpty().groupBy({ it.bank.trim().lowercase() }, { it.rule }))
    }
}
