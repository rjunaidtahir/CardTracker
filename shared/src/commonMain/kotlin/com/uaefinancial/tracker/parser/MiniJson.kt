package com.uaefinancial.tracker.parser

/**
 * A small, strict JSON reader (objects, arrays, strings, numbers, booleans, null) so the engine needs no library.
 * Used only for the signed rules file. Depth and size are limited; anything unexpected is an error.
 */
internal object MiniJson {
    class JsonError(message: String) : Exception(message)

    private const val MAX_DEPTH = 12

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonError("extra text after the value")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun peek(): Char = if (i < s.length) s[i] else throw JsonError("unexpected end")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonError("too deeply nested")
            return when (val c = peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonError("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError("bad literal")
            i += word.length
            return v
        }

        fun num(): Any {
            val st = i
            if (peek() == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonError("bad number")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonError("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw JsonError("bad unicode escape"))
                                i += 4
                            }
                            else -> throw JsonError("bad escape")
                        }
                    }
                    c < ' ' -> throw JsonError("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                ws(); out += value(depth + 1); ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JsonError("expected , or ]")
                }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                if (peek() != '"') throw JsonError("expected a key")
                val k = str(); ws()
                if (peek() != ':') throw JsonError("expected :")
                i++; ws()
                out[k] = value(depth + 1); ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JsonError("expected , or }")
                }
            }
        }
    }
}
