package com.uaefinancial.tracker.parser

/**
 * Turns a bank SMS into its "shape": the wording of the message with everything personal taken out. This is what the
 * app can send, with your permission, to help it learn new bank formats.
 *
 *   "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00"
 *   -> "Your Visa card ending {CARD} was used for {CUR} {AMOUNT} at {TEXT} on {DATE}. Avl bal {CUR} {AMOUNT}"
 *
 * It works from a whitelist: only everyday banking words (and the bank's own name) survive. Every other word, such as a
 * merchant, a person's name or a place, becomes {TEXT}. Amounts, currencies, card and account numbers, dates, times,
 * links, e-mail addresses and phone numbers become tokens. Anything that still looks personal makes the result unsafe,
 * and an unsafe shape is never sent. Messages in languages the whitelist does not cover come out mostly {TEXT} and are
 * therefore not sent either.
 */
object Masker {

    data class Result(val shape: String, val safe: Boolean, val reason: String? = null)

    private const val MAX_LEN = 400
    private const val MIN_WORDS = 4
    private const val MAX_TEXT_SHARE = 0.34

    private val I = setOf(RegexOption.IGNORE_CASE)

    private val url = Regex("""(?:https?://|www\.)\S+|\b[\w-]+(?:\.[\w-]+)*\.(?:com|net|org|ae|in|pk|co|io|me|ly|uk|app|link|bank)\b(?:/\S*)?""", I)
    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val months = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val dateNumeric = Regex("""\b\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}\b""")
    private val dateNamed = Regex("""\b\d{1,2}(?:st|nd|rd|th)?[-\s,]*(?:$months)[a-z]*\.?[-\s,]*(?:\d{2,4})?\b|\b(?:$months)[a-z]*\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s*\d{2,4})?\b""", I)
    private val time = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s?(?:am|pm)?\b""", I)
    private val cardRef = Regex("""(?<lead>\bx{2,}|\*{2,}|•{2,}|·|#|\bending(?:\s+in|\s+with)?\s+|\bcard\s+no\.?\s*|\bcard\s+number\s*|\ba/c(?:\s+no\.?)?\s*|\bacct?\.?(?:\s+no\.?)?\s*|\baccount(?:\s+(?:no\.?|number))?\s*)[\s:]*(?<num>[xX*•]*\d{2,}[xX*•\d]*)""", I)
    private val money: Regex by lazy {
        val cur = SmsParser.currencyAlternation
        val amt = SmsParser.AMT
        Regex("""(?<![A-Za-z])(?:$cur)\.?\s?-?\s?$amt|(?<![A-Za-z0-9*•#.,])-?$amt\s?(?:$cur)(?![A-Za-z])""")
    }
    private val longDigits = Regex("""\d[\d\s-]{4,}\d""")
    private val anyDigits = Regex("""\d+""")
    private val wordOrToken = Regex("""\{[A-Z]+\}|\p{L}+(?:['’]\p{L}+)?""")
    private val tokenText = Regex("""(?:\{TEXT\}[\s,.:;!?()\-]*){2,}""")

    /** Words that carry the structure of a bank message. Everything else is {TEXT}. */
    private val whitelist: Set<String> = (
        "a an the of on at in to for from by with and or is are was were be been has have had will would your you our we us it its this that these those as " +
        "not no yes if has been dear customer client sir madam mr mrs ms valued " +
        "card cards credit debit visa mastercard master amex american express platinum gold titanium signature classic world prepaid virtual " +
        "account accounts acct ac a/c savings current salary iban number no ending last digits digit " +
        "purchase purchases purchased spent spend spending used use payment payments paid pay pos atm ecom online internet contactless tap swipe " +
        "transaction transactions txn trx transfer transferred transfers remittance remit inward outward incoming outgoing instant " +
        "debited credited debit credit withdrawal withdrawn withdraw deposit deposited refund refunded reversal reversed cashback cash back " +
        "available avail avl balance bal limit limits outstanding total due minimum min amount amt statement bill bills " +
        "date time dated through via ref reference rrn utr id merchant beneficiary payee sender receiver recipient " +
        "successful successfully processed completed complete approved declined failed failure rejected pending insufficient funds " +
        "standing instruction order executed scheduled recurring installment instalment emi loan interest fee fees charge charges charged vat tax " +
        "foreign international local domestic currency exchange rate conversion " +
        "thank thanks thank you please contact call visit app mobile banking bank branch customer care service helpline support " +
        "never share pin cvv password otp one-time code security alert fraud report immediately block lost stolen " +
        "salary received sent receive sending withdraw cheque check clearance returned bounced mandate autopay auto-debit direct " +
        "wallet upi imps neft rtgs ach wire swift " +
        "new old updated update has been is was now just only also kindly note reminder due payable overdue minimum pay before after within " +
        "dh dhs rs inr aed usd eur gbp sar qar kwd bd omr pkr " +
        "today yesterday monthly daily weekly annual annually reward rewards points point miles " +
        "login log signed sign registered register activated activate deactivated blocked unblocked limit increase decrease changed change " +
        "txns ending end begin c ac pre one auto x starting from till until " +
        "pre-authorised preauthorised authorised authorized hold held release released " +
        "emirates dubai abu dhabi sharjah uae pakistan india saudi qatar kuwait bahrain oman " +
        "ltd limited plc llc inc pjsc psc"
        ).split(' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun mask(body: String, bankName: String? = null): Result {
        var t = SmsParser.normalizeBody(body)
        if (t.isBlank()) return Result("", false, "empty")
        t = t.replace(email, "{EMAIL}").replace(url, "{URL}")
        t = t.replace(dateNamed, "{DATE}").replace(dateNumeric, "{DATE}").replace(time, "{TIME}")
        t = t.replace(cardRef) { m ->
            // Keep the words in front of the number ("card ending"); a bare "xx"/"**"/"#" prefix is dropped.
            val lead = m.groups["lead"]?.value?.trim().orEmpty()
            if (lead.all { it in "xX*•·#" }) "{CARD}" else "$lead {CARD}"
        }
        t = t.replace(money, "{CUR} {AMOUNT}")
        t = t.replace(longDigits, "{NUM}").replace(anyDigits, "{NUM}")

        val keep = bankName.orEmpty().split(Regex("""[^\p{L}\d]+""")).map { it.lowercase() }.filter { it.length >= 2 }.toSet()
        t = t.replace(wordOrToken) { m ->
            val w = m.value
            when {
                w.startsWith("{") -> w
                w.lowercase().replace('’', '\'') in whitelist || w.lowercase() in keep -> w
                else -> "{TEXT}"
            }
        }
        // Count unknown words before neighbours are merged, so a message full of them is recognised as such.
        val rawWords = Regex("""\{[A-Z]+\}|\p{L}+""").findAll(t).map { it.value }.toList()
        val textShare = rawWords.count { it == "{TEXT}" }.toDouble() / maxOf(1, rawWords.size)
        t = t.replace(tokenText, "{TEXT} ")
        t = t.replace(Regex("""\s+"""), " ").trim()

        if (t.length > MAX_LEN) return Result(t.take(MAX_LEN), false, "too long")
        // Final guard: nothing personal may remain.
        if (Regex("""\d""").containsMatchIn(t.replace(Regex("""\{[A-Z]+\}"""), ""))) return Result(t, false, "digits left")
        if (t.contains('@')) return Result(t, false, "address left")
        val words = Regex("""\{[A-Z]+\}|\p{L}+""").findAll(t).map { it.value }.toList()
        if (words.size < MIN_WORDS) return Result(t, false, "too short")
        if (textShare > MAX_TEXT_SHARE) return Result(t, false, "mostly unknown words")
        if (words.none { it == "{AMOUNT}" }) return Result(t, false, "no amount")
        return Result(t, true)
    }
}
