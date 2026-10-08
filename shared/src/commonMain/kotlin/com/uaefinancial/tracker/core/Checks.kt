package com.uaefinancial.tracker.core

import kotlin.math.abs

/**
 * Accuracy checks on what the reader saved. Nothing here changes or deletes a transaction: a check only produces a
 * short reason the app shows as "Check this", and the person decides.
 *
 * Kept in `shared/` so both apps check the same way.
 */
object Checks {
    enum class Kind { BALANCE, DUPLICATE, UNUSUAL }

    data class Flag(val kind: Kind, val message: String)

    /** The few fields the checks need from a saved transaction. Amounts are minor units (fils/cents). */
    data class Row(
        val id: Long,
        val cardKey: String?,
        val timestamp: Long,
        /** TxnType name: PURCHASE, REFUND, PAYMENT, TRANSFER_IN, TRANSFER_OUT. */
        val type: String,
        val amountMinor: Long,
        val currency: String,
        val merchantKey: String?,
        /** Available limit / balance printed in the same message, if any. */
        val availMinor: Long?,
    )

    private const val BALANCE_TOLERANCE = 100L            // 1.00: rounding, fees shown to the cent
    private const val BALANCE_MAX_GAP_MS = 7L * 24 * 3600 * 1000
    private const val DUPLICATE_WINDOW_MS = 3L * 60 * 1000
    private const val UNUSUAL_MIN_HISTORY = 8
    private const val UNUSUAL_FACTOR = 15L
    private const val UNUSUAL_FLOOR = 100_000L            // never flag under 1,000.00

    private val moneyIn = setOf("REFUND", "PAYMENT", "TRANSFER_IN")
    private val moneyOut = setOf("PURCHASE", "TRANSFER_OUT")

    /** Flags per transaction id. Only ids with at least one flag are present. */
    fun evaluate(rows: List<Row>, fmt: (Long) -> String = ::plain): Map<Long, List<Flag>> {
        val out = HashMap<Long, MutableList<Flag>>()
        fun add(id: Long, f: Flag) { out.getOrPut(id) { mutableListOf() } += f }

        val byCard = rows.groupBy { it.cardKey }
        for ((_, list) in byCard) {
            val sorted = list.sortedWith(compareBy({ it.timestamp }, { it.id }))

            // 1. The balance in this message vs the previous one on the same card.
            var prev: Row? = null
            for (r in sorted) {
                val a = r.availMinor
                if (a == null) continue
                val p = prev
                if (p != null && p.currency == r.currency && r.timestamp - p.timestamp <= BALANCE_MAX_GAP_MS) {
                    val delta = when (r.type) {
                        in moneyOut -> -r.amountMinor
                        in moneyIn -> r.amountMinor
                        else -> null
                    }
                    val pa = p.availMinor
                    if (delta != null && pa != null) {
                        val expected = pa + delta
                        val diff = a - expected
                        if (abs(diff) > BALANCE_TOLERANCE) {
                            add(r.id, Flag(Kind.BALANCE,
                                "The balance went from ${fmt(pa)} to ${fmt(a)}, but this ${if (delta < 0) "spend" else "credit"} explains " +
                                    "${fmt(abs(delta))}. ${fmt(abs(diff))} ${if (diff < 0) "more was taken" else "more came in"}: " +
                                    "a message may be missing, or the amount was misread."))
                        }
                    }
                }
                prev = r
            }

            // 2. The same amount and merchant a moment earlier on the same card.
            for (i in sorted.indices) {
                val r = sorted[i]
                val mk = r.merchantKey
                if (mk.isNullOrEmpty()) continue
                for (j in i - 1 downTo 0) {
                    val e = sorted[j]
                    if (r.timestamp - e.timestamp > DUPLICATE_WINDOW_MS) break
                    if (e.type == r.type && e.amountMinor == r.amountMinor && e.merchantKey == mk) {
                        val mins = ((r.timestamp - e.timestamp) / 60_000L).toInt()
                        add(r.id, Flag(Kind.DUPLICATE,
                            "Same amount and merchant ${if (mins <= 0) "less than a minute" else "$mins min"} earlier. A repeat, or one purchase counted twice?"))
                        break
                    }
                }
            }

            // 3. Far above what this card usually sees (a misplaced decimal point, for instance).
            val spends = sorted.filter { it.type == "PURCHASE" }
            if (spends.size >= UNUSUAL_MIN_HISTORY) {
                val amounts = spends.map { it.amountMinor }.sorted()
                val median = amounts[amounts.size / 2]
                val limit = maxOf(median * UNUSUAL_FACTOR, UNUSUAL_FLOOR)
                for (r in spends) {
                    if (r.amountMinor > limit) {
                        add(r.id, Flag(Kind.UNUSUAL, "Much larger than your usual spend on this card (usually about ${fmt(median)})."))
                    }
                }
            }
        }
        return out
    }

    private fun plain(minor: Long): String {
        val neg = minor < 0
        val m = abs(minor)
        val whole = (m / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = (m % 100).toString().padStart(2, '0')
        return (if (neg) "-" else "") + whole + "." + frac
    }

    // ------------------------------------------------------------------ amount written in the message

    private val numberToken = Regex("""\d[\d.,'’  ]*\d|\d""")

    /**
     * True when [amountMinor] is written somewhere in [body] (any of the usual separators: 1,250.50 · 1.250,50 ·
     * 1 250,50 · 1'250.50 · 1250.5). A message with no digits at all returns true (nothing to compare).
     * Pass the body after SmsParser.normalizeBody so Arabic digits are already Western.
     */
    fun amountAppearsIn(body: String, amountMinor: Long): Boolean {
        val tokens = numberToken.findAll(body).map { it.value }.toList()
        if (tokens.isEmpty()) return true
        for (raw in tokens) {
            val t = raw.trim().trimEnd('.', ',')
            for (c in candidates(t)) if (abs(c - amountMinor) <= 1L) return true
        }
        return false
    }

    /** Every minor-unit value a number token could mean. */
    private fun candidates(token: String): List<Long> {
        val compact = token.filter { it.isDigit() || it == '.' || it == ',' }
        val digitsOnly = compact.filter { it.isDigit() }
        if (digitsOnly.isEmpty() || digitsOnly.length > 15) return emptyList()
        val out = ArrayList<Long>(3)
        // As a whole number (separators are thousands marks): 1,250 · 1.250 · 1 250
        digitsOnly.toLongOrNull()?.let { out += it * 100 }
        // With a decimal part after the last separator: 1,250.50 · 1.250,5 · 1250.5 · 1.250 (3 decimals)
        val lastSep = maxOf(compact.lastIndexOf('.'), compact.lastIndexOf(','))
        if (lastSep >= 0) {
            val before = compact.substring(0, lastSep).filter { it.isDigit() }
            val after = compact.substring(lastSep + 1)
            if (after.isNotEmpty() && after.all { it.isDigit() } && after.length <= 3) {
                val whole = (if (before.isEmpty()) "0" else before).toLongOrNull()
                if (whole != null) {
                    val two = after.padEnd(3, '0')
                    val cents = two.substring(0, 2).toInt()
                    out += whole * 100 + cents
                    if (after.length == 3 && two[2] >= '5') out += whole * 100 + cents + 1
                }
            }
        }
        return out
    }
}
