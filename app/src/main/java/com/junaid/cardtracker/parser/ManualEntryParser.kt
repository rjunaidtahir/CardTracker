package com.junaid.cardtracker.parser

import java.math.BigDecimal

data class ManualEntry(
    val description: String,
    val amount: BigDecimal,
    val currency: String,
    val cardLast4: String?,
    val type: TxnType,
)

/**
 * Parses quick typed entries:
 *   "lunch 45 aed"          -> lunch, 45.00 AED
 *   "coffee 12.5"           -> coffee, 12.50 AED (AED is the default)
 *   "usd 20 netflix #3944"  -> netflix, 20.00 USD on card ending 3944
 *   "refund amazon 50"      -> amazon, 50.00 AED, REFUND
 *   "45aed taxi"            -> taxi, 45.00 AED
 */
object ManualEntryParser {
    private val amountToken = Regex("""^([A-Za-z]{3})?(\d[\d,]*(?:\.\d+)?)([A-Za-z]{3})?$""")
    private val cardToken = Regex("""^[#*](\d{4})$""")

    fun parse(input: String): ManualEntry? {
        val tokens = input.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null

        var amount: BigDecimal? = null
        var currency: String? = null
        var card: String? = null
        var type = TxnType.PURCHASE
        val words = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val lower = t.lowercase()
            val amt = amountToken.matchEntire(t)
            val cardMatch = cardToken.matchEntire(t)
            when {
                cardMatch != null -> card = cardMatch.groupValues[1]
                lower == "card" && i + 1 < tokens.size && tokens[i + 1].matches(Regex("""\d{4}""")) -> {
                    card = tokens[i + 1]; i++
                }
                amount == null && amt != null && isCurrencyOrEmpty(amt.groupValues[1]) && isCurrencyOrEmpty(amt.groupValues[3]) -> {
                    amount = SmsParser.parseAmount(amt.groupValues[2])
                    val c = amt.groupValues[1].ifEmpty { amt.groupValues[3] }
                    if (c.isNotEmpty()) currency = c.uppercase()
                }
                currency == null && isCurrency(t) -> currency = t.uppercase()
                lower == "refund" || lower == "refunded" -> type = TxnType.REFUND
                lower == "payment" || lower == "paid" && words.isEmpty() -> type = TxnType.PAYMENT
                else -> words += t
            }
            i++
        }
        val amt = amount ?: return null
        if (amt.signum() <= 0) return null
        val description = words.joinToString(" ").ifBlank {
            when (type) { TxnType.REFUND -> "Refund"; TxnType.PAYMENT -> "Card payment"; else -> "Manual entry" }
        }
        return ManualEntry(description, amt, currency ?: BankRules.BASE_CURRENCY, card, type)
    }

    private fun isCurrency(t: String) = t.length == 3 && BankRules.fxToAed.containsKey(t.uppercase())
    private fun isCurrencyOrEmpty(t: String) = t.isEmpty() || isCurrency(t)
}
