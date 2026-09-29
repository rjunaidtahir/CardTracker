package com.uaefinancial.tracker.parser
import com.uaefinancial.tracker.core.Decimal


data class ManualEntry(
    val description: String,
    val amount: Decimal,
    val currency: String,
    val cardLast4: String?,
    val type: TxnType,
)

/**
 * Parses quick typed entries (your home currency is the default; AED in these examples):
 *   "lunch 45 aed"          -> lunch, 45.00 AED
 *   "coffee 12.5"           -> coffee, 12.50 AED
 *   "\$12 uber" / "€9 lunch" -> 12.00 in your dollar (or USD) / 9.00 EUR
 *   "usd 20 netflix #1234"  -> netflix, 20.00 USD on card ending 1234
 *   "refund amazon 50"      -> amazon, 50.00 AED, REFUND
 *   "45aed taxi"            -> taxi, 45.00 AED
 *   "dhs 30 parking"        -> parking, 30.00 AED
 *   "paid rent 3000"        -> paid rent, 3000.00 AED (a spend)
 *   "card payment 500 #1234"-> a payment made TO card ending 1234 (not spending)
 */
object ManualEntryParser {
    private val amountToken = Regex("""^([A-Za-z]{2,3}|[$€£₹¥₨]|[A-Z]{1,2}\$)?(\d[\d,]*(?:\.\d+)?)([A-Za-z]{2,3}|[€£₹¥₨])?$""")
    private val cardToken = Regex("""^[#*](\d{4})$""")

    fun parse(input: String): ManualEntry? {
        val tokens = input.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.toMutableList()
        if (tokens.isEmpty()) return null

        var amount: Decimal? = null
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
                    val c = amt.groupValues[1].ifEmpty { amt.groupValues[3] }
                    if (c.isNotEmpty()) currency = SmsParser.normalizeCurrency(c)
                    amount = SmsParser.parseAmount(amt.groupValues[2], currency)
                }
                currency == null && isCurrency(t) -> currency = SmsParser.normalizeCurrency(t)
                lower == "refund" || lower == "refunded" -> type = TxnType.REFUND
                else -> words += t
            }
            i++
        }
        val amt = amount ?: return null
        if (amt.signum() <= 0) return null
        // "card payment 500 #1234": paying off a card (never spending). Needs a card, so "paid rent" stays a spend.
        if (type == TxnType.PURCHASE && card != null && words.any { it.equals("payment", true) } &&
            words.any { it.equals("card", true) || it.equals("cc", true) }
        ) {
            type = TxnType.PAYMENT
            words.removeAll { it.equals("payment", true) || it.equals("card", true) || it.equals("cc", true) }
        }
        val description = words.joinToString(" ").ifBlank {
            when (type) { TxnType.REFUND -> "Refund"; TxnType.PAYMENT -> "Card payment"; else -> "Manual entry" }
        }
        return ManualEntry(description, amt, currency ?: SmsParser.homeCurrency, card, type)
    }

    /** Codes people type in lower case. Other codes count only in capitals, so words like "try" or "mad" stay words. */
    private val typedCodes = setOf(
        "aed", "usd", "eur", "gbp", "inr", "pkr", "sar", "qar", "kwd", "bhd", "omr", "jod", "egp", "aud", "cad", "sgd",
        "hkd", "nzd", "chf", "jpy", "cny", "sek", "nok", "dkk", "pln", "czk", "huf", "ron", "bdt", "lkr", "npr", "myr",
        "idr", "php", "thb", "zar",
    )

    private fun isCurrency(t: String): Boolean {
        if (t.uppercase() in BankRules.aedAliases) return true
        if (t.length == 3 && Currencies.isCode(t)) return t.all { it.isUpperCase() } || t.lowercase() in typedCodes
        if (t.equals("rs", ignoreCase = true)) return true
        // Symbols and capital abbreviations: "$", "€", "SR", "KD", "R$".
        return t.length <= 3 && t.none { it.isLetter() && it.isLowerCase() } && Currencies.codeFor(t, SmsParser.homeCurrency) != null
    }

    private fun isCurrencyOrEmpty(t: String) = t.isEmpty() || isCurrency(t)
}
