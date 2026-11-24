package com.uaefinancial.tracker.data

import com.uaefinancial.tracker.parser.RulesPack

/** Builds and applies an invented 2.8 rules file for the database tests (no real messages, no shipped file). */
object Pack28 {
    private fun j(s: String) = buildString {
        append('"'); for (c in s) when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); else -> append(c) }; append('"')
    }

    fun rule(bank: String, id: String, pattern: String, type: String, cardType: String, extra: String = ""): String =
        """{"bank":${j(bank)},"id":${j(id)},"kind":"TRANSACTION","type":"$type","cardType":"$cardType","pattern":${j(pattern)}$extra}"""

    fun bank(name: String, sender: String): String = """{"name":${j(name)},"country":"AE","senderIds":[${j(sender)}],"track":true}"""

    /** Applies a file whose `ext` has [levers] switched on, [rules] and any [more] ext fields; legacy [banks]. */
    fun apply(levers: List<String>, rules: List<String> = emptyList(), banks: List<String> = emptyList(), more: String = "") {
        val lv = levers.joinToString(",") { "\"$it\":true" }
        val json = """{"version":40,"minEngine":5,"banks":[${banks.joinToString(",")}],"rules":[],""" +
            """"ext":{"levers":{$lv},"rules":[${rules.joinToString(",")}]${if (more.isBlank()) "" else ",$more"}}}"""
        val out = RulesPack.parse(json)
        check(out is RulesPack.Outcome.Ok) { "rejected: $out" }
        check(out.pack.extRejected == null) { "ext rejected: ${out.pack.extRejected}" }
        RulesPack.apply(out.pack)
    }

    fun reset() = RulesPack.apply(null)
}
