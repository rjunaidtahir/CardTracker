package com.uaefinancial.tracker.parser

/**
 * One name per bank, whatever way it's written: "RAK BANK", "RAKBank UAE" and "National Bank of Ras Al Khaimah" are all
 * RAKBANK. Used wherever a bank name comes from outside the rules (a statement PDF, the name at the top of a Messages
 * screenshot, a card added by hand), so the same bank never turns into two banks with two copies of a card.
 */
object BankNames {
    /** Other ways people and banks write a bank's name, in plain words (matched ignoring case, spaces and dashes). */
    private val aliases: Map<String, List<String>> = mapOf(
        "FAB" to listOf("First Abu Dhabi Bank", "First Abu Dhabi", "FAB Bank", "NBAD", "National Bank of Abu Dhabi", "FGB"),
        "Emirates NBD" to listOf("Emirates NBD Bank", "ENBD", "Emirates National Bank of Dubai"),
        "ADCB" to listOf("Abu Dhabi Commercial Bank", "ADCB Bank"),
        "Al Hilal" to listOf("Al Hilal Bank", "AlHilal"),
        "HSBC" to listOf("HSBC Bank Middle East", "HSBC Bank", "HSBC UAE"),
        "Mashreq" to listOf("Mashreq Bank", "Mashreqbank"),
        "Dubai Islamic Bank" to listOf("Dubai Islamic", "DIB Bank"),
        "Emirates Islamic" to listOf("Emirates Islamic Bank"),
        "ADIB" to listOf("Abu Dhabi Islamic Bank", "ADIB Bank"),
        "RAKBANK" to listOf("RAK BANK", "RAK Bank UAE", "National Bank of Ras Al Khaimah", "National Bank of Ras Al-Khaimah", "RAKBANK UAE"),
        "CBD" to listOf("Commercial Bank of Dubai", "CBD Bank"),
        "Citibank" to listOf("Citi", "Citibank NA", "Citi Bank"),
        "Standard Chartered" to listOf("Standard Chartered Bank", "StanChart", "SCB"),
        "NBF" to listOf("National Bank of Fujairah"),
        "Ajman Bank" to listOf("Ajman"),
        "Dubai First" to listOf("Dubai First Bank"),
        "Sharjah Islamic Bank" to listOf("Sharjah Islamic"),
        "Bank of Sharjah" to listOf("BOS Bank"),
        "UAB" to listOf("United Arab Bank"),
        "Arab Bank" to listOf("Arab Bank UAE"),
        "CBI" to listOf("Commercial Bank International"),
        "Al Masraf" to listOf("Arab Bank for Investment and Foreign Trade", "Al Masraf Bank"),
        "Liv" to listOf("Liv Bank", "Liv by Emirates NBD"),
        "Wio" to listOf("Wio Bank"),
        "Mashreq Neo" to listOf("Neo by Mashreq", "Mashreq NEO"),
        "Zand" to listOf("Zand Bank"),
        "Ruya" to listOf("Ruya Bank"),
    )

    fun normalize(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /** Normalised spelling → bank name. Built-in names, sender IDs and the aliases above. */
    private val lookup: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        for (b in BankRules.banks) {
            m.getOrPut(normalize(b.name)) { b.name }
            for (id in b.senderIds) m.getOrPut(normalize(id)) { b.name }
        }
        for ((bank, list) in aliases) for (a in list) m.getOrPut(normalize(a)) { bank }
        m
    }

    /** Words a Messages conversation name or a statement adds around the bank's name. */
    private val noise = listOf("UAE", "AE", "ALERTS", "ALERT", "BANK", "PJSC", "PSC", "LTD", "LIMITED", "NA")

    /**
     * The bank's usual name for any spelling ("RAK BANK" → "RAKBANK", "AD-ADCBAlert" → "ADCB"), or null when it isn't a
     * bank the app knows. Senders you added in the app count too.
     */
    fun canonical(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().trimEnd('>', '›', '〉', ' ').trim()
        val n = normalize(cleaned)
        if (n.isEmpty()) return null
        lookup[n]?.let { return it }
        SmsParser.bankFor(cleaned)?.name?.let { return lookup[normalize(it)] ?: it }
        // "RAKBANKUAE", "FABBANK": a known spelling plus noise words.
        for (w in noise) {
            if (n.endsWith(w) && n.length > w.length) lookup[n.dropLast(w.length)]?.let { return it }
        }
        return null
    }

    /** Spellings that are also ordinary words or place names (CITI CENTRE, AJMAN): used by [canonical] only, never searched for in text. */
    private val notSearched = setOf("CITI", "AJMAN", "SCB", "FGB", "NBAD", "LIV", "WIO")

    /** Readable spellings to look for in text, longest first so "Emirates Islamic" wins over "Emirates". */
    private val searchable: List<Pair<Regex, String>> by lazy {
        val pairs = ArrayList<Pair<String, String>>()
        for (b in BankRules.banks) {
            pairs += b.name to b.name
            for (id in b.senderIds) if (id.length >= 4 && id.any { it.isLetter() }) pairs += id to b.name
        }
        for ((bank, list) in aliases) for (a in list) if (a.length >= 4) pairs += a to bank
        pairs.filter { normalize(it.first) !in notSearched || it.first == it.second && it.first.length > 3 }
            .distinctBy { it.first.uppercase() }
            .sortedByDescending { it.first.length }
            .map { (spelling, bank) ->
                // Spaces and dashes in a name may be written or left out.
                val pattern = spelling.trim().split(Regex("""[\s\-]+""")).joinToString("""[\s\-]*""") { Regex.escape(it) }
                Regex("""(?<![A-Za-z0-9])$pattern(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE) to bank
            }
    }

    /** The bank named in a text (a message, a screenshot's conversation name, a statement), or null. */
    fun namedIn(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return searchable.firstOrNull { it.first.containsMatchIn(text) }?.second
    }
}
