package com.uaefinancial.tracker.parser

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * The "smart reader": reads a bank SMS by its wording instead of a fixed pattern, so the app works with banks
 * (and new formats) that have no rule in BankRules.kt yet.
 *
 * It looks for:
 *  - amounts next to a currency ("AED 120.50", "120.50 AED", "Dhs 45"), and what each amount is by the words just
 *    before it: available limit / balance, minimum due, total due, or the transaction itself;
 *  - the card or account ("XXXX1234", "ending 1234", "card no. 1234", "a/c ****1234");
 *  - what happened (purchase, refund, card payment received, money in, transfer out, ATM) from the verbs;
 *  - the merchant ("at CARREFOUR", "to DEWA", "from AMAZON"), and the date.
 *
 * It is deliberately careful: when a message doesn't clearly describe a completed transaction or a statement it
 * returns null, and the message goes to "Needs review" instead of producing a wrong transaction.
 * Everything it reads is marked auto = true so the app can show it was read automatically.
 */
object SmartParser {

    private val I = RegexOption.IGNORE_CASE

    /** Anything this big is a reference number, not money. */
    private val MAX_AMOUNT = BigDecimal("100000000000")

    // ------------------------------------------------------------------ amounts

    private enum class Role { TXN, AVAILABLE, MINIMUM, TOTAL, FEE }

    private data class Mention(val start: Int, val end: Int, val amount: BigDecimal, val currency: String, val role: Role)

    private val money: Regex by lazy {
        val cur = SmsParser.currencyAlternation
        val amt = SmsParser.AMT
        // Not IGNORE_CASE: currency codes are upper case in bank SMS ([currencyAlternation] handles "Dhs").
        Regex("""(?<![A-Za-z])(?<c1>$cur)\.?\s?(?<a1>-?\s?$amt)|(?<![A-Za-z0-9*•#.,])(?<a2>-?$amt)\s?(?<c2>$cur)(?![A-Za-z])""")
    }

    private val availLabel = Regex(
        """\b(?:avl\.?|avail(?:able)?|a/?v\.?\s*bal|bal(?:ance)?|limit|lmt|funds|remaining)\b[^a-z0-9]*""" +
            """(?:(?:is|now|of|cr|credit|remaining|amount|balance|limit)\b[^a-z0-9]*){0,3}$""",
    )
    /** "available balance in your A/C XXXX1234 is AED ..." */
    private val availLabelLoose = Regex("""\b(?:available|avl\.?|avail)\s+(?:bal(?:ance)?|limit|credit(?:\s+limit)?)\b.{0,40}?\b(?:is|:)\s*(?:now\s*)?$""")
    private val minLabel = Regex(
        """\b(?:min(?:imum)?\.?\s*(?:amount|amt|payment|pay)?\.?\s*(?:due|payable)?|pay\s+min\.?)[^a-z0-9]*(?:(?:is|of|amount)\b[^a-z0-9]*){0,2}$""",
    )
    private val totalLabel = Regex(
        """\b(?:total\s+(?:amount\s+|amt\.?\s+)?(?:due|outstanding|payable)|statement\s+(?:balance|amount)|stmt\.?\s+(?:bal(?:ance)?|amt)|""" +
            """outstanding(?:\s+balance)?|closing\s+balance|amount\s+due|new\s+balance|total\s+due[^:]{0,40}:?)""" +
            """[^a-z0-9]*(?:(?:is|of|amount)\b[^a-z0-9]*){0,2}$""",
    )
    private val feeLabel = Regex("""\b(?:fee|charges?|vat|\+\s*[\d.]+\s*%)[^a-z0-9]*(?:(?:of|is)\b[^a-z0-9]*)?$""")

    private fun mentions(text: String): List<Mention> = money.findAll(text).mapNotNull { m ->
        val rawAmt = (m.groups["a1"]?.value ?: m.groups["a2"]?.value)?.replace(" ", "") ?: return@mapNotNull null
        val cur = m.groups["c1"]?.value ?: m.groups["c2"]?.value ?: return@mapNotNull null
        val amount = runCatching { SmsParser.parseAmount(rawAmt) }.getOrNull()?.takeIf { it.abs() < MAX_AMOUNT } ?: return@mapNotNull null
        val before = text.substring(maxOf(0, m.range.first - 45), m.range.first).lowercase()
        val role = when {
            minLabel.containsMatchIn(before) -> Role.MINIMUM
            totalLabel.containsMatchIn(before) -> Role.TOTAL
            availLabel.containsMatchIn(before) || availLabelLoose.containsMatchIn(before) -> Role.AVAILABLE
            feeLabel.containsMatchIn(before) -> Role.FEE
            else -> Role.TXN
        }
        Mention(m.range.first, m.range.last + 1, amount, SmsParser.normalizeCurrency(cur), role)
    }.toList()

    // ------------------------------------------------------------ cards/accounts

    private data class Ref(val start: Int, val last4: String, val context: String)

    private val refPatterns = listOf(
        Regex("""(?<![A-Za-z0-9])[X*x•#]{2,}[\s-]?(\d{3,})\b"""),               // XXXX1234, *** 5258, xx3538, XXX810001
        Regex("""\b\d{4,6}[X*x•]{4,}(\d{4})\b"""),                               // 529106******3976
        Regex("""\b(?:ending(?:\s+(?:with|in))?|ends?\s+with)\s*[:#-]?\s*\(?\s*(?:[X*x•]+\s*)?(\d{4})\)?""", I), // ending 1234, ending with (3976)
        Regex("""\b(?:card|a/?c|acc(?:oun)?t|account|acct)\.?\s*(?:no\.?|number|num|#)?\s*[:.#-]?\s*(\d{4})\b""", I), // card 3976, A/C 1234
    )

    private fun refs(text: String): List<Ref> {
        val out = mutableListOf<Ref>()
        for (p in refPatterns) {
            for (m in p.findAll(text)) {
                val digits = m.groupValues[1]
                if (digits.length < 3) continue
                if (out.any { kotlin.math.abs(it.start - m.range.first) < 12 }) continue
                val ctx = text.substring(maxOf(0, m.range.first - 28), m.range.first).lowercase()
                out += Ref(m.range.first, digits.takeLast(4), ctx)
            }
        }
        return out.sortedBy { it.start }
    }

    private val accountWord = Regex("""\b(?:a/?c|acc(?:oun)?t|account|acct|acc(?=\.|\s+no\b))\b|\bacc\.""", I)
    private val cardWord = Regex("""\bcard\b|\bcr\.?\s*card|\bcc\b""", I)
    private val toWord = Regex("""\b(?:to|beneficiary|towards)\b[^.]*$""")

    // ------------------------------------------------------------------ dates

    private val dateRegex: Regex by lazy {
        val d = "(?<![\\d/])(?:${SmsParser.NUMDATE}|${SmsParser.WORDDATE_T}|\\d{1,2}[A-Za-z]{3}\\d{2,4})(?![\\d/])"
        Regex("""${SmsParser.WEEKDAY}$d(?:,?\s+(?:at\s+)?${SmsParser.TIME})?""", I)
    }
    private val timeThenDate: Regex by lazy {
        Regex("""\bat\s+(${SmsParser.TIME})\s+on\s+(${SmsParser.NUMDATE})""", I)
    }
    private val dueContext = Regex("""(?:due(?:\s+date)?|pay(?:ment)?\s+by|by|before|on or before)\W*(?:is|on|:)?\W*$|\bdue date\b.{0,60}?\bis\s*$""")
    private val statementDateContext = Regex("""(?:statement\s+date|stmt\.?\s+date|dated|statement\s+of|generated\s+on)\W*(?:is|on|:)?\W*$""")

    private data class FoundDate(val at: Int, val millis: Long, val hasTime: Boolean, val date: java.time.LocalDate, val kind: String)

    private fun dates(text: String, zone: ZoneId): List<FoundDate> {
        val out = mutableListOf<FoundDate>()
        timeThenDate.find(text)?.let { m ->
            SmsParser.parseDateTime("${m.groupValues[2]} ${m.groupValues[1]}")?.let { (dt, _) ->
                out += FoundDate(m.range.first, dt.atZone(zone).toInstant().toEpochMilli(), true, dt.toLocalDate(), "txn")
            }
        }
        for (m in dateRegex.findAll(text)) {
            if (out.any { m.range.first in it.at..(it.at + 40) }) continue
            val (dt, hasTime) = SmsParser.parseDateTime(m.value.trim().trimEnd(',', '.')) ?: continue
            val before = text.substring(maxOf(0, m.range.first - 70), m.range.first).lowercase()
            val near = before.takeLast(25)
            val kind = when {
                statementDateContext.containsMatchIn(near) -> "statement"
                dueContext.containsMatchIn(near) || Regex("""\bdue date\b.{0,60}?\bis\s*$""").containsMatchIn(before) -> "due"
                else -> "txn"
            }
            out += FoundDate(m.range.first, dt.atZone(zone).toInstant().toEpochMilli(), hasTime, dt.toLocalDate(), kind)
        }
        return out.sortedBy { it.at }
    }

    // ------------------------------------------------------------ wording

    private val notDone = Regex(
        """\b(?:will be (?:debited|charged|processed|credited|deducted)|scheduled|request (?:has been |is )?received|on hold|""" +
            """blocked|failed|declined|rejected|unsuccessful|insufficient|not (?:been )?(?:processed|successful|completed|approved)|""" +
            """pre-?approved|eligible|apply now|offer|win\b|chance to|was cancell?ed|has been cancell?ed|authori[sz]ation request)""",
        I,
    )
    private val statementWord = Regex("""\b(?:statement|stmt|billing alert|bill summary)\b""", I)
    private val isDue = Regex("""\b(?:is|are) due\b|\bdue (?:on|date|by)\b|\bpayment due\b""", I)
    private val refundWord = Regex("""\b(?:refund(?:ed)?|revers(?:ed|al)|cash\s?back|charge\s?back|credited back)\b""", I)
    private val paymentReceived = Regex(
        """\bthank(?:s| you) for (?:your |the )?payment\b|\b(?:payment|paid)\b.{0,80}\b(?:received|towards|against)\b|\breceived\b.{0,40}\bpayment\b""",
        I,
    )
    private val atmWord = Regex("""\b(?:atm|cash withdrawal|withdrawn|withdrawal|cash advance)\b""", I)
    private val creditWord = Regex("""\b(?:credited|deposited|received|deposit|salary|inward|incoming)\b""", I)
    private val debitWord = Regex(
        """\b(?:purchase[ds]?|spent|used|paid|payment of|debited|charged|transaction of|pos|txn|deducted|bought|transferred|sent|withdrawn|""" +
            """a debit of|dr\.?\s+transaction|outward remittance)\b|\b(?:transfer|payment|remittance)\b.{0,120}\b(?:processed|successful(?:ly)?|completed)\b""",
        I,
    )
    private val transferWord = Regex("""\b(?:transfer(?:red)?|remittance|remitted|sent|ipp|aani|iban|beneficiary|standing order|wire|pgs)\b""", I)
    /** Paying one of your cards from an account: "payment of AED 100 for card 5492XXXXXXXX3115 has been processed". */
    private val payingACard = Regex(
        """\bcard payment request\b|\bpayment (?:of\s+\S+\s+\S+\s+)?(?:for|to|towards)\s+(?:your\s+)?(?:credit\s+)?card\b.{0,60}\b(?:processed|successful)""",
        I,
    )
    private val strongTransfer = Regex(
        """\bfunds?\s+transfer\b|\btransfer(?:red)?\b.{0,40}\b(?:to|from)\s+(?:your\s+)?(?:iban|account|a/c|acc)\b|\bremittance\b|\baani\b|\bipp\b|\biban\b|\bpgs\b""",
        I,
    )
    private val spendWord = Regex("""\b(?:purchase|used for|spent|pos)\b""", I)
    private val creditSignal = Regex("""\bcr\.?\s+transaction\b|\bcredit(?:ed)?\s+transaction\b|\binward remittance\b|\bcash deposit\b""", I)
    private val salaryWord = Regex("""\bsalary|payroll|wps\b""", I)

    private val merchantEnd =
        """(?=\s+on\s|\s+dated\s|\s*[.;](?:\s|$)|,\s|\s+(?:avl|available|avail|bal(?:ance)?|using|via|with|ref|txn|reference|card|from your|for your|has been|was|is|through|for (?:consumer|customer|account|contract|mobile|bill|ref))\b|\s+\d{1,2}[/-]\d{1,2}|\s+\d{1,2}-[A-Za-z]{3}|\s*$)"""
    private val atMerchant = Regex("""\b(?:at|@)\s+(.+?)$merchantEnd""", I)
    private val toMerchant = Regex(
        """\b(?:to|towards)\s+(?!your\b|(?:online|mobile|internet|net)\s+banking|log\s*in|top\s*-?\s*up|view|know|avoid|call|report|check|enjoy|the card\b|iban\b|account\b|a/c\b|card\b)(.+?)$merchantEnd""",
        I,
    )
    private val topUpMerchant = Regex("""\bto\s+top\s*-?\s*up\s+(?:your\s+)?(.+?)$merchantEnd""", I)
    private val asMerchant = Regex("""\bas\s+(?!per\b|of\b|on\b)(.+?)$merchantEnd""", I)
    private val fromMerchant = Regex("""\b(?:from|by)\s+(?!your\b)(.+?)$merchantEnd""", I)
    private val forMerchant = Regex(
        """\bfor\s+(?!your\b|card\b|the card\b|details\b|more\b|further\b|any\b|queries\b|information\b|assistance\b)(.+?)$merchantEnd""",
        I,
    )
    private val bracketMerchant = Regex("""\(([^)\d][^)]{1,40})\)""")

    private fun clean(raw: String?): String? {
        raw ?: return null
        var m = SmsParser.cleanMerchant(raw).let { r -> r.substringBefore(",").takeIf { it.count(Char::isLetter) >= 2 } ?: r }
            .replace(Regex("""[,\s]+(?:DUBAI|ABU DHABI|SHARJAH|AJMAN|AL AIN|RAS AL KHAIMAH|FUJAIRAH|UMM AL QUWAIN)(?:\s*-\s*AE)?$""", I), "")
            .replace(Regex("""-\s*[A-Z]{2}$"""), "")
            .trim().trimEnd(',', '.', '-', ':').trim()
        if (m.length > 50) m = m.take(50).trim()
        if (!m.any { it.isLetter() }) return null
        if (Regex("""^(?:your|the|a|an|card|account|a/c|acc)\b""", I).containsMatchIn(m)) return null
        if (money.containsMatchIn(m) || Regex("""[X*]{3,}""").containsMatchIn(m)) return null
        return m
    }

    // ------------------------------------------------------------------ read

    /** Returns a transaction or statement, or null when the message isn't clearly one. [text] is already normalized. */
    fun read(bank: String, text: String, receivedAt: Long, zone: ZoneId = SmsParser.UAE_ZONE): ParseResult? {
        val all = mentions(text)
        if (all.isEmpty()) return null
        // Only balances / limits (e.g. a balance update): nothing to record.
        if (all.all { it.role == Role.AVAILABLE }) return ParseResult.Ignored(bank, "Balance update")
        val found = dates(text, zone)
        val references = refs(text)
        val lower = text.lowercase()

        // ---- statement / payment due
        val total = all.firstOrNull { it.role == Role.TOTAL }
        val minimum = all.firstOrNull { it.role == Role.MINIMUM }
        val due = found.firstOrNull { it.kind == "due" }
        val statementLike = total != null || minimum != null || isDue.containsMatchIn(text)
        if (statementLike && due != null && (total != null || minimum != null || !hasPurchaseShape(text))) {
            val balance = total ?: all.firstOrNull { it.role == Role.TXN } ?: return null
            return ParseResult.Statement(
                ParsedStatement(
                    bank = bank,
                    cardLast4 = references.firstOrNull()?.last4,
                    cardType = CardType.CREDIT,
                    statementBalance = balance.amount,
                    minimumDue = minimum?.amount,
                    currency = balance.currency,
                    dueDate = due.date,
                    statementDate = found.firstOrNull { it.kind == "statement" }?.date,
                    auto = true,
                ),
                "auto-statement",
            )
        }
        if (total != null && minimum != null) return null // a statement without a due date: let the user review it

        // ---- transaction
        if (notDone.containsMatchIn(text)) return null
        val txnAmount = all.firstOrNull { it.role == Role.TXN } ?: return null
        if (txnAmount.amount.signum() <= 0) return null
        val available = all.firstOrNull { it.role == Role.AVAILABLE && it.start > txnAmount.start }

        val cardCtx = cardWord.containsMatchIn(text)
        val accountCtx = accountWord.containsMatchIn(text)
        val credit = creditWord.find(text)
        val debit = debitWord.find(text)
        // "credited to your account" vs "debited from": whichever the message says first.
        val creditFirst = (credit != null && (debit == null || credit.range.first < debit.range.first)) ||
            creditSignal.containsMatchIn(text)
        val cashbackCard = Regex("""cash\s?back\s+(?:credit\s+)?card""", I).containsMatchIn(text)

        val type: TxnType
        var fixed: String? = null
        when {
            cardCtx && paymentReceived.containsMatchIn(text) -> { type = TxnType.PAYMENT; fixed = "Card payment received" }
            refundWord.containsMatchIn(text) && !cashbackCard -> {
                type = TxnType.REFUND
                if (Regex("""cash\s?back""", I).containsMatchIn(text)) fixed = "Cashback"
            }
            payingACard.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            atmWord.containsMatchIn(text) && !creditFirst -> { type = TxnType.PURCHASE; fixed = "ATM cash withdrawal" }
            creditFirst -> when {
                accountCtx && !Regex("""\bcredit card\b""", I).containsMatchIn(text) -> type = TxnType.TRANSFER_IN
                !cardCtx -> type = TxnType.TRANSFER_IN
                Regex("""\bpayment\b|\bfrom\s+[A-Z]*\d{6,}""", I).containsMatchIn(text) -> { type = TxnType.PAYMENT; fixed = "Card payment received" }
                else -> type = TxnType.REFUND
            }
            debit != null && strongTransfer.containsMatchIn(text) && !spendWord.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            debit != null && !cardCtx && transferWord.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            debit != null -> type = TxnType.PURCHASE
            else -> return null
        }

        // ---- which card / account
        val debitCard = Regex("""\b(?:debit|dr\.?|prepaid)\s*card\b""", I).containsMatchIn(text)
        val creditCard = Regex("""\bcredit\s*card\b|\bcr\.?\s*card\b|\bcredit\b.{0,20}\bcard\b""", I).containsMatchIn(text)
        val destination = if (type == TxnType.TRANSFER_OUT) {
            references.firstOrNull { toWord.containsMatchIn(it.context) }
                ?: if (payingACard.containsMatchIn(text)) references.firstOrNull { Regex("""\b(?:for|to|towards)\b[^.]*card[^.]*$""").containsMatchIn(it.context) } else null
        } else null
        val candidates = references.filter { it != destination }
        val accountRef = candidates.firstOrNull { accountWord.containsMatchIn(it.context) }
        val cardRef = candidates.firstOrNull { cardWord.containsMatchIn(it.context) || it.context.contains("ending") }
        val (ref, cardType) = when {
            // Debit card spend that names the account it came out of: record it on the account.
            debitCard && accountRef != null -> accountRef to CardType.ACCOUNT
            // Paying a card: the money leaves an account (named or not).
            type == TxnType.TRANSFER_OUT -> (accountRef ?: candidates.firstOrNull()) to CardType.ACCOUNT
            creditCard -> (cardRef ?: candidates.firstOrNull()) to CardType.CREDIT
            debitCard -> (cardRef ?: candidates.firstOrNull()) to CardType.DEBIT
            cardCtx && !accountCtx -> (cardRef ?: candidates.firstOrNull()) to (if (fixed == "ATM cash withdrawal") CardType.DEBIT else CardType.CREDIT)
            accountCtx -> (accountRef ?: candidates.firstOrNull()) to CardType.ACCOUNT
            type == TxnType.TRANSFER_OUT || type == TxnType.TRANSFER_IN -> candidates.firstOrNull() to CardType.ACCOUNT
            else -> candidates.firstOrNull() to CardType.CREDIT
        }

        // ---- merchant
        val merchant = fixed ?: when (type) {
            TxnType.PURCHASE -> clean(atMerchant.find(text)?.groupValues?.get(1))
                ?: clean(topUpMerchant.find(text)?.groupValues?.get(1))?.let { "$it top-up" }
                ?: clean(toMerchant.find(text)?.groupValues?.get(1))
                ?: clean(forMerchant.find(text)?.groupValues?.get(1))
                ?: clean(bracketMerchant.find(text)?.groupValues?.get(1))
                ?: if (cardType == CardType.ACCOUNT) "Account debit" else "Card purchase"
            TxnType.REFUND -> clean(fromMerchant.find(text)?.groupValues?.get(1))
                ?: clean(atMerchant.find(text)?.groupValues?.get(1))
                ?: clean(bracketMerchant.find(text)?.groupValues?.get(1))
                ?: "Refund"
            TxnType.TRANSFER_OUT -> destination?.let { SmsParser.transferLabel(it.last4) }
                ?: clean(toMerchant.find(text)?.groupValues?.get(1))?.let { "Transfer to $it" }
                ?: clean(forMerchant.find(text)?.groupValues?.get(1))
                ?: "Money out (transfer)"
            TxnType.TRANSFER_IN -> when {
                salaryWord.containsMatchIn(text) -> "Salary"
                Regex("""\bcash deposit\b""", I).containsMatchIn(text) -> "Cash deposit"
                Regex("""\binward remittance\b""", I).containsMatchIn(text) -> "Inward remittance"
                else -> clean(asMerchant.find(text)?.groupValues?.get(1))
                    ?: clean(forMerchant.find(text)?.groupValues?.get(1))
                    ?: clean(fromMerchant.find(text)?.groupValues?.get(1))?.let { "From $it" }
                    ?: "Money in"
            }
            TxnType.PAYMENT -> "Card payment received"
        }

        // ---- date (a date in the message; never a due date, never in the future)
        val txnDate = found.firstOrNull { it.kind == "txn" }
            ?.takeIf { it.millis <= receivedAt + 36 * 3600_000L && it.millis >= receivedAt - 3L * 365 * 24 * 3600_000L }
        val ts = when {
            txnDate == null -> receivedAt
            txnDate.hasTime -> txnDate.millis
            Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate() == txnDate.date -> receivedAt
            else -> txnDate.date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        }

        return ParseResult.Transaction(
            ParsedTransaction(
                bank = bank,
                cardLast4 = ref?.last4,
                cardType = cardType,
                merchant = merchant,
                amount = txnAmount.amount.abs(),
                currency = txnAmount.currency,
                type = type,
                timestamp = ts,
                dateFromSms = txnDate != null,
                availableLimit = available?.amount,
                toLast4 = destination?.last4,
                accountNotNamed = ref == null && cardType == CardType.ACCOUNT,
                auto = true,
            ),
            "auto-" + type.name.lowercase(),
        )
    }

    /** "Purchase of AED 5 at X ... statement due on 26/09" is a purchase with a footer, not a statement. */
    private fun hasPurchaseShape(text: String): Boolean =
        Regex("""\b(?:purchase|spent|used for|transaction of|debited|paid)\b""", I).containsMatchIn(text) &&
            (atMerchant.containsMatchIn(text) || Regex("""\bcard\b""", I).containsMatchIn(text))

    // ------------------------------------------------------------------ senders

    /**
     * True when an SMS looks like a bank transaction alert (used to suggest senders the app doesn't know yet):
     * an amount with a currency AND a masked card/account number or clear banking words.
     */
    fun looksLikeBankAlert(body: String): Boolean {
        val text = SmsParser.normalizeBody(body)
        if (!SmsParser.looksFinancial(text)) return false
        val bankish = refs(text).isNotEmpty() ||
            Regex("""\b(?:debited|credited|available (?:balance|limit)|avl\.? (?:bal|limit)|credit card|debit card|a/c|account)\b""", I).containsMatchIn(text)
        return bankish && !Regex("""\b(?:OTP|one[\s-]?time\s+pass)""", I).containsMatchIn(text)
    }
}
