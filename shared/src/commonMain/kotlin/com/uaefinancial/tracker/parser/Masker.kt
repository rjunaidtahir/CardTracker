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
    private val wordOrToken = Regex("""\{[A-Z]+(?::\d+)?\}|\p{L}+(?:['’]\p{L}+)?""")
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
        "ltd limited plc llc inc pjsc psc " +
        // everyday verbs and words banks use around a transfer or a payment (the wording that tells what happened)
        "made make makes making initiated initiate initiating placed place requested request requests done performed perform raised raise " +
        "authorised confirm confirmed confirmation notified notify informed inform advised advise update credited debited " +
        "ibft raast iban one-link 1link bill utility utilities topup top-up top up recharge mobile prepaid postpaid " +
        "fund funds title mobile/internet internet/mobile mobile/ibanking ibanking app application online net " +
        "beneficiary beneficiaries registered added removed deleted created opened closed " +
        "reversed reverse reversal returned return refunding adjusted adjustment correction " +
        "failed fail unable cannot can could should would may might must shall " +
        "transaction transactions transfer transfers sent send sending received receive receiving " +
        "dear sir madam team regards thanks thank greetings welcome " +
        "announce announced pleased profit dividend dividends credit crediting crediting disbursed disburse maturity matured " +
        "home work office branch branches atm atms counter cheque cheques deposit deposits " +
        // words that decide what a message is (they were hidden as {TEXT}, which made a shape useless for checking a rule)
        "cr dr crdr instructions orders requests repayment repayments redeemed redemption redeem conversion converted epp " +
        "installments instalments reversals refunds adjustments debits credits withdrawals standing-instruction si " +
        "salaries payroll wps remitted remitting settled settlement settle cleared declined approved authorization authorisation " +
        "purchase-return return-of cashbacks chargeback chargebacks disputed dispute pre-auth preauth bills paid-to paid-from"
        ).split(' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Short forms banks print instead of the word ("Avl Bal", "Pur"): not in a dictionary, but public and they decide what a message is. */
    private val abbreviations: Set<String> = (
        "avl avail avlbl bal balc amt txn txns trx trxn tran trans trn pur purch purchs acct acc acnt chq chqs cheq wdl wd dep depo " +
        "int intl ccy curr stmt stmnt pmt pymt paymt pmnt ref refno rrn cr dr crdr drcr cc dc fx emi ach ecs nach ib ibft cdm adm " +
        "inst instl instal msg mob tel ph nos num min mnth mth yr yrs mgmt auth autho authd decl rev revsl rtn rtrn disc cshbk cshback " +
        "pos atm sms otp pin cvv"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    /**
     * Ordinary words the dictionary list lacks, found in the owner's real export (21 Nov 2026: "details", "under", "review", month names,
     * "you've") plus the short everyday words the dictionary check skips (it ignores words under three letters). No names, no places.
     */
    private val extraWords: Set<String> = (
        "details using per into under discount representative device review input queries supplementary unavailable enquiries remind " +
        "clarifications inconvenience delivery documentation dispatch notifications upto replacement nearest telecom uninterrupted " +
        "registration read deducted deregistered discounts customised enquiry subscription apologise authenticate verification retail " +
        "reset overlimit earliest complimentary programme favourite unfrozen relocated pls intermittent solution promotion increased " +
        "highest notification interruptions resolution optout automatically cancellation unlimited centre currencies complementary " +
        "unsuccessful profile chequebook unregistered hrs solutions replaces returns incredible communication congratulations " +
        "unrecognised replaced promocode installation unmatched irresistible authentication whatsapp festivities finalise categories " +
        "inputs disregard transparency reactivate entities requesting inactive unfreeze minimise resort universities internationally " +
        "jewellery fastest entries preferential securities recover simplified communications earlier cardless countries default enrol " +
        "conducting authorise modification tnc tncs tc tcs cs " +
        "january february march april june july august september october november december jan feb mar apr jun jul aug sep sept oct nov dec " +
        "monday tuesday wednesday thursday friday saturday sunday tue tues wed thu thur thurs fri sat sun " +
        "go do am pm up us me my so we he she it if or an as at be by in is no of on to ok oh hi id tv uk " +
        "wasn't haven't you've we've didn't it's you're you'd you'll don't can't won't they've there's couldn't isn't aren't doesn't " +
        "hasn't hadn't weren't shouldn't wouldn't i'm i've i'll let's that's what's bank's merchant's"
        ).split(' ').filter { it.isNotEmpty() }.toSet()

    /** A title or "payee": the word after it is a person's name, always hidden. After "to / from / by" a lowercase ordinary word is a verb ("to avoid"), not a name. */
    private val strongLeads = setOf("dear", "mr", "mrs", "ms", "miss", "hi", "hello", "payee", "beneficiary", "name")
    private val weakLeads = setOf("to", "from", "by")

    private val nameLeads = setOf(
        "dear", "mr", "mrs", "ms", "miss", "hi", "hello", "to", "from", "by", "payee", "beneficiary", "name",
        "regards", "sincerely", "attn", "attention", "holder", "عزيزي", "عزيزتي", "السيد", "السيدة", "الأخ",
    )

    // Lengths are carried as letters (a=0 … j=9) while the digit replacing runs, so it cannot touch them.
    private fun enc(n: Int) = n.toString().map { 'a' + (it - '0') }.joinToString("")
    private fun dec(s: String) = s.map { '0' + (it - 'a') }.joinToString("")

    /**
     * [merchant] is the merchant the app read (or you typed) for this message. It is never sent: where it stands in the wording is
     * marked as {MERCHANT} instead, which is all a rule for this bank's wording needs ("... from {MERCHANT} for {CUR} {AMOUNT}").
     */
    /**
     * True when a message reads like a bank alert: an amount with a currency, a card / account reference (masked number or
     * "ending 1234"), and at least three everyday banking words. Lets a bank we have not listed (a chat the user picked by hand)
     * be masked loosely; a private chat that mentions money has no card or account reference, so it stays strict.
     */
    fun looksLikeBankMessage(body: String): Boolean {
        if (!money.containsMatchIn(body)) return false
        val cardRef = Regex("""\b(?:card|a/c|acc(?:oun)?t|acct|iban)\b[^.\n]{0,40}(?:\d{4}|[xX*•]{2,})|[xX*•]{3,}\s?\d{3,4}\b""", RegexOption.IGNORE_CASE)
        if (!cardRef.containsMatchIn(body)) return false
        val banking = setOf("debited", "credited", "balance", "available", "purchase", "transaction", "payment", "limit", "transfer", "withdrawal", "statement", "due", "refund", "reversed", "spent", "avl", "bal", "txn", "pos", "atm")
        val hits = Regex("""\p{L}+""").findAll(body.lowercase()).map { it.value }.filter { it in banking }.toSet()
        return hits.size >= 3
    }

    /**
     * [loose] (learning reports from a recognised bank sender): every word stays, so the accuracy check can read tricky wording in any
     * language, EXCEPT a name: the word after a title or a "payee / beneficiary / request for", a capitalised word after to / from / by, and
     * any run of two or more unknown capitalised words ("Rana Muhammad Junaid Tahir"). A single unknown capitalised word (a brand, a
     * place, an acronym) is kept. [fuller] (only when a rules file asks for more words for a message it could not understand): runs of
     * unknown capitalised words stay too; a name after a title / payee / to / from / by is still hidden. Never for a sender we do not recognise (a private chat): that stays strict.
     */
    fun mask(body: String, bankName: String? = null, merchant: String? = null, keepWords: Boolean = false, loose: Boolean = false, fuller: Boolean = false): Result {
        var t = SmsParser.normalizeBody(body)
        if (t.isBlank()) return Result("", false, "empty")
        val name = merchant?.trim()?.replace(Regex("""\s+"""), " ")
        if (name != null && name.length >= 3 && name !in com.uaefinancial.tracker.core.ReadingGaps.placeholderMerchants) {
            t = t.replace(Regex(Regex.escape(name).replace("\\ ", "\\s+"), RegexOption.IGNORE_CASE), "{MERCHANT}")
        }
        t = t.replace(email, "{EMAIL}").replace(url, "{URL}")
        t = t.replace(dateNamed, "{DATE}").replace(dateNumeric, "{DATE}").replace(time, "{TIME}")
        t = t.replace(cardRef) { m ->
            // Keep the words in front of the number ("card ending"); a bare "xx"/"**"/"#" prefix is dropped.
            val lead = m.groups["lead"]?.value?.trim().orEmpty()
            // How long the number is (digits and mask characters) shows how this bank prints it, and says nothing about the number.
            val len = m.groups["num"]?.value?.length ?: 0
            if (lead.all { it in "xX*•·#" }) "{CARD:${enc(len)}}" else "$lead {CARD:${enc(len)}}"
        }
        // keepWords (the local export only): the currency code stays, it is a public word; the amount never does.
        t = if (keepWords) t.replace(money) { m -> (Regex("""[A-Za-z]{2,4}""").find(m.value)?.value ?: "{CUR}") + " {AMOUNT}" } else t.replace(money, "{CUR} {AMOUNT}")
        // Only how many digits, never which: "{NUM:10}" for an account or reference, "{NUM:2}" for a day.
        t = t.replace(longDigits) { m -> "{NUM:${enc(m.value.count { it.isDigit() })}}" }.replace(anyDigits) { m -> "{NUM:${enc(m.value.length)}}" }
        // Turn the letter-coded lengths back into digits now that no digit replacing is left.
        t = t.replace(Regex("""\{(CARD|NUM):([a-j]+)\}""")) { m -> "{${m.groupValues[1]}:${dec(m.groupValues[2])}}" }

        val keep = bankName.orEmpty().split(Regex("""[^\p{L}\d]+""")).map { it.lowercase() }.filter { it.length >= 2 }.toSet()
        if (keepWords) {
            // Ordinary words are public and decide what a message is, so they all stay. Only values are gone (amounts, card and
            // account numbers, other digits, dates, times, links, emails, the merchant name).
            t = t.replace(Regex("""\s+"""), " ").trim()
            if (Regex("""\d""").containsMatchIn(t.replace(Regex("""\{[A-Z]+(?::\d+)?\}"""), ""))) return Result(t, false, "digits left")
            if (t.contains('@')) return Result(t, false, "address left")
            return Result(t, true)
        }
        // An ordinary dictionary word is kept (it is public and often decides what the message is), but it still counts toward
        // "mostly unknown words" exactly as before, so a private chat full of ordinary words is never accepted. A word that follows
        // a title or a "to / from / by" is where a person's name stands, so there only banking words are kept.
        var ordinaryKept = 0
        var prev = ""
        var prev2 = ""
        val mark = '\u0001'
        t = t.replace(wordOrToken) { m ->
            val w = m.value
            val lw = w.lowercase().replace('’', '\'')
            val previous = prev
            val previous2 = prev2
            prev2 = prev
            prev = lw
            // "beneficiary creation request for PAPPU", "on behalf of Ali": a name stands here too.
            val nameHere = previous in nameLeads || (previous == "for" && previous2 == "request") || (previous == "of" && previous2 == "behalf")
            when {
                w.startsWith("{") -> w
                lw in whitelist || lw in keep || lw in abbreviations -> w
                // "to avoid", "to enrol": a lowercase ordinary word after to / from / by is a verb, not a name.
                previous in weakLeads && w == w.lowercase() && (CommonWords.isCommon(w) || lw in extraWords) -> { ordinaryKept++; w }
                // "to Hope Smith": the whole run of non-banking words after the lead is a name, so "Smith" goes too.
                nameHere -> { prev = "name"; "{TEXT}" }
                CommonWords.isCommon(w) || lw in extraWords -> { ordinaryKept++; w }
                // Loose: keep it, unless it is a capitalised Latin word (maybe a name): marked now, kept only if it stands alone.
                loose -> if (w[0] in 'A'..'Z') "$mark$w$mark" else w
                else -> "{TEXT}"
            }
        }
        if (loose) {
            // A run of two or more unknown capitalised words is a person's name (or a long merchant): hidden. A single one stays.
            // (fuller: the runs stay too; only the internal markers are removed.)
            val run = Regex("$mark[^$mark]+$mark(?:[\\s,.&/\\-]*$mark[^$mark]+$mark)+")
            if (!fuller) t = t.replace(run, "{TEXT} ")
            t = t.replace(mark.toString(), "")
        }
        // Count unknown words before neighbours are merged, so a message full of them is recognised as such.
        val rawWords = Regex("""\{[A-Z]+(?::\d+)?\}|\p{L}+""").findAll(t).map { it.value }.toList()
        val textShare = (rawWords.count { it == "{TEXT}" } + if (loose) 0 else ordinaryKept).toDouble() / maxOf(1, rawWords.size)
        t = t.replace(tokenText, "{TEXT} ")
        t = t.replace(Regex("""\s+"""), " ").trim()

        val maxLen = if (fuller) 1000 else if (loose) 700 else MAX_LEN
        if (t.length > maxLen) return Result(t.take(maxLen), false, "too long")
        // Final guard: nothing personal may remain.
        if (Regex("""\d""").containsMatchIn(t.replace(Regex("""\{[A-Z]+(?::\d+)?\}"""), ""))) return Result(t, false, "digits left")
        if (t.contains('@')) return Result(t, false, "address left")
        val words = Regex("""\{[A-Z]+(?::\d+)?\}|\p{L}+""").findAll(t).map { it.value }.toList()
        if (words.size < MIN_WORDS) return Result(t, false, "too short")
        if (!loose && textShare > MAX_TEXT_SHARE) return Result(t, false, "mostly unknown words")
        if (words.none { it == "{AMOUNT}" }) return Result(t, false, "no amount")
        return Result(t, true)
    }
}
