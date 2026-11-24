package com.uaefinancial.tracker.parser

/**
 * Builds 2.8 rules files for tests: every wording here is invented (built from masked shapes), never a real message.
 * [j] JSON-escapes a Kotlin string so patterns can be written as plain raw strings.
 */
internal object P28 {
    fun j(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }

    fun rule(bank: String, id: String, pattern: String, type: String = "PURCHASE", cardType: String = "DEBIT", kind: String = "TRANSACTION", extra: String = ""): String =
        """{"bank":${j(bank)},"id":${j(id)},"kind":"$kind","type":"$type","cardType":"$cardType","pattern":${j(pattern)}$extra}"""

    fun ignore(label: String, pattern: String, extra: String = ""): String = """{"label":${j(label)},"pattern":${j(pattern)}$extra}"""

    /** A whole file: [legacy] is inserted as top-level fields, [ext] as the body of "ext" (null = no ext section). */
    fun file(version: Int = 30, legacy: String = "", ext: String? = null, banks: String = "[]"): String {
        val l = if (legacy.isBlank()) "" else "$legacy,"
        val e = if (ext == null) "" else ""","ext":{$ext}"""
        val rules = if (legacy.contains("\"rules\"")) "" else ",\"rules\":[]"
        return """{"version":$version,"minEngine":5,$l"banks":$banks$rules$e}"""
    }

    fun levers(vararg on: String): String = "\"levers\":{" + on.joinToString(",") { "\"$it\":true" } + "}"

    /** Parses and applies; fails loudly when the file is rejected or its ext was rejected. */
    fun apply(json: String): RulesPack.Pack {
        val out = RulesPack.parse(json)
        check(out is RulesPack.Outcome.Ok) { "rejected: $out" }
        check(out.pack.extRejected == null) { "ext rejected: ${out.pack.extRejected}" }
        RulesPack.apply(out.pack)
        return out.pack
    }

    const val AT = 1_795_000_000_000L
    val ZONE = SmsParser.UAE_ZONE

    fun read(bank: String, body: String): ParseResult = SmsParser.parseAsBank(bank, body, AT, ZONE)
}
