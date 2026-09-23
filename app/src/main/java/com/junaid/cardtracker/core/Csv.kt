package com.junaid.cardtracker.core

/** Minimal RFC-4180 CSV: quotes fields containing comma, quote or newline. */
object Csv {
    fun escape(v: String?): String {
        if (v == null) return ""
        val needs = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needs) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun write(header: List<String>, rows: List<List<String?>>): String {
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { escape(it) }).append("\r\n")
        rows.forEach { r -> sb.append(r.joinToString(",") { escape(it) }).append("\r\n") }
        return sb.toString()
    }

    /** Returns rows as maps keyed by header name. Empty fields come back as null. */
    fun read(text: String): List<Map<String, String?>> {
        val rows = parse(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).filter { r -> r.any { it.isNotEmpty() } }.map { r ->
            header.indices.associate { i -> header[i] to r.getOrNull(i)?.takeIf { it.isNotEmpty() } }
        }
    }

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
