package com.junaid.cardtracker.core

/**
 * Turns thousands of unparsed SMS into a short shareable text: messages with the same "shape"
 * (digits replaced by #) are grouped, most common first, with one real example each.
 */
object ReviewExport {
    data class Item(val bank: String, val body: String)

    fun shape(body: String): String =
        body.replace(Regex("""\d"""), "#").replace(Regex("""\s+"""), " ").trim()

    fun summarize(items: List<Item>, maxGroups: Int = 200, maxChars: Int = 400): String {
        val groups = items.groupBy { it.bank to shape(it.body) }.values.sortedByDescending { it.size }
        val sb = StringBuilder()
        sb.append("Card Tracker: ${items.size} unparsed SMS in ${groups.size} formats\n\n")
        groups.take(maxGroups).forEachIndexed { i, g ->
            val ex = g.first()
            sb.append("#${i + 1} · ${ex.bank} · ${g.size}×\n")
            sb.append(ex.body.trim().take(maxChars)).append("\n\n")
        }
        if (groups.size > maxGroups) sb.append("…and ${groups.size - maxGroups} more formats\n")
        return sb.toString()
    }
}
