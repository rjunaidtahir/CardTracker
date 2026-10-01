package com.uaefinancial.tracker.parser
import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.Decimal


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

    // Word edges that work in every alphabet: "\\b" only knows ASCII letters on some platforms ("é", "ş", Arabic).
    private const val L = """(?<![\p{L}\d])"""
    private const val R = """(?![\p{L}\d])"""

    /** Anything this big is a reference number, not money. */
    private val MAX_AMOUNT = Decimal("100000000000")

    // ------------------------------------------------------------------ amounts

    private enum class Role { TXN, AVAILABLE, MINIMUM, TOTAL, FEE }

    private data class Mention(val start: Int, val end: Int, val amount: Decimal, val currency: String, val role: Role)

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
    /** "Saldo: 1.234,56 EUR", "solde disponible", "Kontostand", "الرصيد المتاح" … */
    private val availLabelIntl = Regex(
        """(?:saldo(?:\s+(?:disponible|dispon[ií]vel|disponibile|actual|atual|attuale|beschikbaar))?|solde(?:\s+disponible)?|kontostand|""" +
            """verfügbar(?:er|e)?(?:\s+(?:betrag|saldo|rahmen|limit))?|disponible|dispon[ií]vel|disponibile|beschikbaar(?:\s+saldo)?|""" +
            """l[ií]mite\s+dispon\p{L}*|الرصيد(?:\s+المتاح)?|الحد\s+المتاح|المتاح|رصيد\p{L}*)""" +
            """[^\p{L}\d]*(?:(?:es|é|est|ist|è|is|de|du|van|von|هو)[^\p{L}\d]*){0,2}$""",
    )
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
        val code = SmsParser.normalizeCurrency(cur)
        val amount = runCatching { SmsParser.parseAmount(rawAmt, code) }.getOrNull()?.takeIf { it.abs() < MAX_AMOUNT } ?: return@mapNotNull null
        val before = text.substring(maxOf(0, m.range.first - 45), m.range.first).lowercase()
        val role = when {
            minLabel.containsMatchIn(before) -> Role.MINIMUM
            totalLabel.containsMatchIn(before) -> Role.TOTAL
            availLabel.containsMatchIn(before) || availLabelLoose.containsMatchIn(before) || availLabelIntl.containsMatchIn(before) -> Role.AVAILABLE
            feeLabel.containsMatchIn(before) -> Role.FEE
            else -> Role.TXN
        }
        Mention(m.range.first, m.range.last + 1, amount, code, role)
    }.toList()

    // ------------------------------------------------------------ cards/accounts

    private data class Ref(val start: Int, val last4: String, val context: String)

    private val refPatterns = listOf(
        Regex("""(?<![A-Za-z0-9])[X*x•#]{2,}[\s-]?(\d{3,})\b"""),               // XXXX1234, *** 5258, xx3538, XXX810001
        Regex("""\b\d{4,6}[X*x•]{4,}(\d{4})\b"""),                               // 529106******3976
        Regex("""\b(?:ending(?:\s+(?:with|in))?|ends?\s+with)\s*[:#-]?\s*\(?\s*(?:[X*x•]+\s*)?(\d{4})\)?""", I), // ending 1234, ending with (3976)
        Regex("""\b(?:card|a/?c|acc(?:oun)?t|account|acct)\.?\s*(?:no\.?|number|num|#)?\s*[:.#-]?\s*(\d{4})\b""", I), // card 3976, A/C 1234
        // Other languages: "terminada en 1234", "final 1234", "se terminant par 1234", "endet auf 1234", "المنتهية بـ 1234"
        Regex(
            """(?:$L(?:final|terminad[ao]\s+(?:en|em)|con\s+terminaci[oó]n|que\s+termina\s+(?:en|em|com)|se\s+terminant\s+par|""" +
                """finissant\s+par|termin[ée]e?\s+par|end(?:end|et)\s+(?:auf|mit)|mit\s+(?:der\s+)?endung|(?:che\s+)?termina(?:nte)?\s+(?:con|in|per)|""" +
                """eindig(?:end|t)\s+(?:op|met))|(?:المنتهية|المنتهي|تنتهي|ينتهي)\s*(?:بـ|ب|في|برقم)?)""" +
                """\s*[:#-]?\s*\(?\s*(?:[X*x•·]+\s*)?(\d{4})(?!\d)""",
            I,
        ),
        Regex("""(?<![\d·])·\s?(\d{4})(?!\d)"""), // tarjeta ·1234
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

    private val accountWord = Regex(
        """\b(?:a/?c|acc(?:oun)?t|account|acct|acc(?=\.|\s+no\b))\b|\bacc\.|$L(?:cuenta|conta|compte|conto|rekening)|konto|حساب""",
        I,
    )
    private val cardWord = Regex(
        """\bcard\b|\bcr\.?\s*card|\bcc\b|$L(?:tarjeta|cart[aã]o|carte|carta|kaart)|karte$R|(?:betaal|pin|bank)pas$R|بطاق""",
        I,
    )
    private val creditCardWord = Regex(
        """\bcredit\s*card\b|\bcr\.?\s*card\b|\bcredit\b.{0,20}\bcard\b|tarjeta\s+de\s+cr[ée]dito|cart[aã]o\s+de\s+cr[ée]dito|""" +
            """carte\s+de\s+cr[ée]dit|kreditkarte|carta\s+di\s+credito|creditcard|ائتمان""",
        I,
    )
    private val debitCardWord = Regex(
        """\b(?:debit|dr\.?|prepaid)\s*card\b|tarjeta\s+de\s+d[ée]bito|cart[aã]o\s+de\s+d[ée]bito|carte\s+de\s+d[ée]bit|debitkarte|girocard|""" +
            """ec-karte|carta\s+di\s+debito|(?:betaal|pin|bank)pas$R|بطاقة\s+(?:ال)?خصم|بطاقة\s+مدى""",
        I,
    )
    private val toWord = Regex("""\b(?:to|beneficiary|towards)\b[^.]*$""")

    // ------------------------------------------------------------------ dates

    private val dateRegex: Regex by lazy {
        // Not inside an amount like 1.234.567 (a dot before, or a dot and digit after).
        val d = "(?<![\\d/.])(?:${SmsParser.NUMDATE}|${SmsParser.WORDDATE_T}|\\d{1,2}[A-Za-z]{3}\\d{2,4})(?![\\d/]|\\.\\d)"
        Regex("""${SmsParser.WEEKDAY}$d(?:,?\s+(?:$TIME_LINK\s+)?${SmsParser.TIME})?""", I)
    }
    /** Words between a date and its time: "at", "a las", "às", "à", "um", "om", "alle", "الساعة". */
    private const val TIME_LINK = """(?:at|a\s+las|a\s+les|[àa]s|à|um|om|alle|الساعة)"""
    private val timeLinkWord = Regex("""\s+$TIME_LINK\s+""", I)

    private val timeThenDate: Regex by lazy {
        Regex("""\bat\s+(${SmsParser.TIME})\s+on\s+(${SmsParser.NUMDATE})""", I)
    }
    private val dueContext = Regex("""(?:due(?:\s+date)?|pay(?:ment)?\s+by|by|before|on or before)\W*(?:is|on|:)?\W*$|\bdue date\b.{0,60}?\bis\s*$""")
    private val statementDateContext = Regex("""(?:statement\s+date|stmt\.?\s+date|dated|statement\s+of|generated\s+on)\W*(?:is|on|:)?\W*$""")

    private data class FoundDate(val at: Int, val millis: Long, val hasTime: Boolean, val date: CalendarDate, val kind: String)

    private fun dates(text: String, zone: Int): List<FoundDate> {
        val out = mutableListOf<FoundDate>()
        timeThenDate.find(text)?.let { m ->
            SmsParser.parseDateTime("${m.groupValues[2]} ${m.groupValues[1]}")?.let { (dt, _) ->
                out += FoundDate(m.range.first, dt.toEpochMillis(zone), true, dt.toLocalDate(), "txn")
            }
        }
        for (m in dateRegex.findAll(text)) {
            if (out.any { m.range.first in it.at..(it.at + 40) }) continue
            // "28/09/2026 at 10:30" → "28/09/2026 10:30" (the date reader wants the date and time side by side).
            val (dt, hasTime) = SmsParser.parseDateTime(m.value.trim().trimEnd(',', '.').replace(timeLinkWord, " ")) ?: continue
            val before = text.substring(maxOf(0, m.range.first - 70), m.range.first).lowercase()
            val near = before.takeLast(25)
            val kind = when {
                statementDateContext.containsMatchIn(near) -> "statement"
                dueContext.containsMatchIn(near) || Regex("""\bdue date\b.{0,60}?\bis\s*$""").containsMatchIn(before) -> "due"
                else -> "txn"
            }
            out += FoundDate(m.range.first, dt.toEpochMillis(zone), hasTime, dt.toLocalDate(), kind)
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
    /** Declined / failed in other languages (English is in [notDone]). */
    internal const val NOT_DONE_INTL =
        """$L(?:rechazad[ao]|denegad[ao]|fallid[ao]|recusad[ao]|negad[ao]|n[ãa]o\s+(?:foi\s+)?(?:aprovad|autorizad|realizad)\p{L}*|""" +
            """refus[ée]e?|rejet[ée]e?|[ée]chou[ée]e?|abgelehnt|fehlgeschlagen|nicht\s+(?:ausgeführt|erfolgreich|autorisiert)|""" +
            """rifiutat[ao]|negat[ao]|non\s+(?:è\s+stat[ao]\s+)?(?:autorizzat|eseguit|riuscit)\p{L}*|geweigerd|mislukt|afgewezen)$R|""" +
            """مرفوض|تم\s+رفض|فشل|لم\s+تتم|لم\s+يتم|غير\s+ناجح"""
    private val notDoneIntl = Regex(NOT_DONE_INTL, I)
    private val statementWord = Regex("""\b(?:statement|stmt|billing alert|bill summary)\b""", I)
    private val isDue = Regex("""\b(?:is|are) due\b|\bdue (?:on|date|by)\b|\bpayment due\b""", I)
    private val refundWord = Regex(
        """\b(?:refund(?:ed)?|revers(?:ed|al)|cash\s?back|charge\s?back|credited back)\b|""" +
            """$L(?:reembolso|devoluci[oó]n|estorno|rembours[ée]e?|remboursement|r[üu]ckerstattung|erstattung|erstattet|rimborso|storno|""" +
            """terugbetaling|restitutie|teruggestort)$R|استرداد|استرجاع|مرتجع""",
        I,
    )
    private val paymentReceived = Regex(
        """\bthank(?:s| you) for (?:your |the )?payment\b|\b(?:payment|paid)\b.{0,80}\b(?:received|towards|against)\b|\breceived\b.{0,40}\bpayment\b|""" +
            """$L(?:pago\s+recibido|hemos\s+recibido\s+(?:tu|su)\s+pago|pagamento\s+(?:recebido|ricevuto)|paiement\s+re[çc]u|""" +
            """zahlung\s+(?:erhalten|eingegangen)|betaling\s+ontvangen)$R|(?:تم\s+)?استلام\s+(?:ال)?(?:دفع|سداد)|شكرا\s+ل(?:ل)?سداد""",
        I,
    )
    private val atmWord = Regex(
        """\b(?:atm|cash withdrawal|withdrawn|withdrawal|cash advance)\b|""" +
            """$L(?:cajero|retiro|retirada\s+de\s+efectivo|saque|retrait|dab|geldautomat|bargeld\p{L}*|abhebung|prelievo|geldautomaat|geldopname|opname)$R|""" +
            """سحب\s+نقدي|صراف""",
        I,
    )
    private val creditWord = Regex(
        """\b(?:credited|deposited|received|deposit|salary|inward|incoming|added to your)\b|""" +
            // Not "crédito" / "credit" alone: "tarjeta de crédito" is a credit CARD.
            """$L(?:abono|abonad[ao]|ingreso|ingresad[ao]|dep[óo]sito|depositad[ao]|recibid[ao]|creditad[ao]|recebid[ao]|cr[ée]dit[ée]e?|""" +
            """re[çc]u|versement|gutgeschrieben|gutschrift|zahlungseingang|eingang|eingegangen|accreditat[ao]|accredito|ricevut[ao]|versamento|""" +
            """bijgeschreven|bijschrijving|ontvangen|storting)$R|إيداع|ايداع|أودع|اودع|إضافة|اضافة|أضيف|اضيف|دائن|استلام|وارد""",
        I,
    )
    private val debitWord = Regex(
        """\b(?:purchase[ds]?|spent|used|paid|payment of|debited|charged|transaction of|pos|txn|deducted|bought|transferred|sent|withdrawn|""" +
            """a debit of|dr\.?\s+transaction|outward remittance)\b|\b(?:transfer|payment|remittance)\b.{0,120}\b(?:processed|successful(?:ly)?|completed)\b|""" +
            """$L(?:compras?|cargo|cobro|pago|pagad[ao]|debitad[ao]|d[ée]bito|gasto|pagamento|achat|paiement|d[ée]bit[ée]e?|pr[ée]l[èe]vement|""" +
            """pr[ée]lev[ée]e?|zahlung|kartenzahlung|einkauf|belastet|belastung|abgebucht|abbuchung|lastschrift|bezahlt|acquisto|addebit[oa]|""" +
            """addebitat[oa]|pagat[oa]|spesa|betaling|betaald|afgeschreven|afschrijving|aankoop|""" +
            // Transfers only with a direction ("transferencia enviada", "virement émis"): "Pix recebido" is money in.
            """enviad[ao]|envoy[ée]e?|[ée]mise?|gesendet|inviat[ao]|verstuurd|overgemaakt|realizad[ao]|efetuad[ao]|effectu[ée]e?)$R|""" +
            """شراء|مشتريات|خصم|دفع|مدين|صادرة""",
        I,
    )
    private val transferIntl =
        """$L(?:transferencia|transfer[êe]ncia|pix|virement|überweisung|bonifico|overboeking|overschrijving)$R|حوالة|تحويل"""
    private val transferWord = Regex(
        """\b(?:transfer(?:red)?|remittance|remitted|sent|ipp|aani|iban|beneficiary|standing order|wire|pgs)\b|$transferIntl""",
        I,
    )
    /** Paying one of your cards from an account: "payment of AED 100 for card 5492XXXXXXXX3115 has been processed". */
    private val payingACard = Regex(
        """\bcard payment request\b|\bpayment (?:of\s+\S+\s+\S+\s+)?(?:for|to|towards)\s+(?:your\s+)?(?:credit\s+)?card\b.{0,60}\b(?:processed|successful)""",
        I,
    )
    private val strongTransfer = Regex(
        """\bfunds?\s+transfer\b|\btransfer(?:red)?\b.{0,40}\b(?:to|from)\s+(?:your\s+)?(?:iban|account|a/c|acc)\b|\bremittance\b|\baani\b|\bipp\b|\biban\b|\bpgs\b|""" +
            transferIntl,
        I,
    )
    private val spendWord = Regex(
        """\b(?:purchase|used for|spent|pos)\b|$L(?:compras?|achat|einkauf|kartenzahlung|acquisto|aankoop|gasto)$R|شراء|مشتريات""",
        I,
    )
    private val creditSignal = Regex(
        """\bcr\.?\s+transaction\b|\bcredit(?:ed)?\s+transaction\b|\binward remittance\b|\bcash deposit\b|""" +
            // "Pago recibido", "Pix recebido", "Zahlung eingegangen", "bonifico in entrata": the noun is money in.
            """$L(?:pago|pagamento|paiement|zahlung|betaling|transferencia|transfer[êe]ncia|pix|virement|überweisung|bonifico|overboeking)""" +
            """\s+(?:\p{L}+\s+){0,2}?(?:recibid[ao]|recebid[ao]|re[çc]u|erhalten|eingegangen|ricevut[ao]|ontvangen|in\s+entrata|entrante)$R""",
        I,
    )
    private val salaryWord = Regex("""\bsalary|payroll|wps\b|$L(?:n[óo]mina|salario|sal[áa]rio|salaire|gehalt|lohn|stipendio|salaris)$R|راتب""", I)
    /** Interest / profit (Islamic banks) / dividends paid to you: "You have earned AED 37.05 as interest on your saving space". */
    private val returnWord = Regex("""\b(interest|profit|dividends?)\b""", I)
    private val paidToYou = Regex("""\b(?:earned|credited|received|deposited|paid (?:in)?to your|added to your)\b""", I)
    /** Interest / profit / fees the bank takes: "Interest of AED 45.20 charged on your credit card". */
    private val takenWord = Regex("""\b(?:charged|debited|deducted|applied|levied|billed|payable)\b""", I)
    /** Paying back a credit card / credit line: "You have made a credit repayment of AED 80.74." */
    private val repaymentWord = Regex("""\brepa(?:y|ym(?:ent|ents)|id)\b""", I)
    private val loanWord = Regex("""\b(?:loan|finance|financing|mortgage|emi|instal+ment)\b""", I)
    private val feeName = Regex(
        """\b((?:annual|late(?:\s+payment)?|over-?limit|service|maintenance|joining|membership|renewal|processing|transaction|atm|""" +
            """cash advance|foreign(?:\s+currency)?|fx|minimum balance|card replacement|statement)\s+(?:fee|charges?))\b""",
        I,
    )

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
    /** Where a merchant name stops in other languages: "con tu tarjeta", "mit Karte", "le 28/09", "باستخدام" … */
    private val merchantEndIntl =
        """(?=\s+(?:con|com|avec|mit|met|el|le|am|il|op|pour|para|por|per|voor|für|via|en|em|no|na|auf|sur|um|om|à|às|alle|""" +
            """saldo|solde|kontostand|disponible|ref\p{L}*|tarjeta|cart[aã]o|carte|karte|carta|kaart|cuenta|conta|compte|konto|conto|rekening)(?![\p{L}])|""" +
            """\s+(?:باستخدام|بواسطة|بتاريخ|في|يوم|عبر|من|الساعة|رصيد\p{L}*|الرصيد|المتاح|رقم)(?![\p{L}])|\s+\d{1,2}\.\d{1,2}\.|""" +
            merchantEnd.removePrefix("(?=")
    /** "en MERCADONA", "em PADARIA", "chez CARREFOUR", "bei REWE", "presso ESSELUNGA", "bij ALBERT HEIJN", "لدى كارفور". */
    private val atMerchantIntl = Regex("""(?:$L(?:en|em|chez|bei|presso|bij)|لدى|عند)\s+(.+?)$merchantEndIntl""", I)
    /** Money sent to someone: "para JUAN", "naar JAN", "إلى أحمد". */
    private val toMerchantIntl = Regex("""(?:$L(?:para|naar|a\s+favor\s+de|a\s+favore\s+di|au\s+profit\s+de|destinat[aá]rio:?)|إلى|الى)\s+(.+?)$merchantEndIntl""", I)
    /** Money from someone: "de JUAN", "von MAX", "da MARIO", "van JAN", "من أحمد". */
    private val fromMerchantIntl = Regex("""(?:$L(?:de|von|da|van|remitente:?|ordenante:?)|من)\s+(.+?)$merchantEndIntl""", I)

    /** The first match of [re] that makes a sensible merchant name (skips "en tu cuenta", "de 45,00 EUR"…). */
    private fun firstClean(re: Regex, text: String): String? = re.findAll(text).firstNotNullOfOrNull { clean(it.groupValues[1]) }

    private val bracketMerchant = Regex("""\(([^)\d][^)]{1,40})\)""")

    /** Possessives and card / account words that start a phrase, not a merchant name ("tu cuenta", "votre carte", "حسابك"). */
    private val notAName = Regex(
        """^(?:(?:su|tu|sus|tus|sua|seu|suas|seus|votre|vos|ton|ta|ihr|ihre|ihrem|ihrer|dein|deine|deinem|tuo|tua|suo|uw|je|jouw|""" +
            """cuenta|conta|compte|konto|conto|rekening|tarjeta|cart[aã]o|carte|karte|carta|kaart)(?![\p{L}\d])|حساب|بطاق|رصيد)""",
        I,
    )

    private fun clean(raw: String?): String? {
        raw ?: return null
        var m = SmsParser.cleanMerchant(raw).let { r -> r.substringBefore(",").takeIf { it.count(Char::isLetter) >= 2 } ?: r }
            .replace(Regex("""[,\s]+(?:DUBAI|ABU DHABI|SHARJAH|AJMAN|AL AIN|RAS AL KHAIMAH|FUJAIRAH|UMM AL QUWAIN)(?:\s*-\s*AE)?$""", I), "")
            .replace(Regex("""-\s*[A-Z]{2}$"""), "")
            .trim().trimEnd(',', '.', '-', ':').trim()
        if (m.length > 50) m = m.take(50).trim()
        if (!m.any { it.isLetter() }) return null
        if (Regex("""^(?:your|the|a|an|card|account|a/c|acc)\b""", I).containsMatchIn(m)) return null
        if (notAName.containsMatchIn(m)) return null
        if (money.containsMatchIn(m) || Regex("""[X*]{3,}""").containsMatchIn(m)) return null
        return m
    }

    // ------------------------------------------------------------------ read

    /** Returns a transaction or statement, or null when the message isn't clearly one. [text] is already normalized. */
    fun read(bank: String, text: String, receivedAt: Long, zone: Int = SmsParser.zoneAt(receivedAt)): ParseResult? {
        val all = mentions(text)
        if (all.isEmpty()) return null
        // Only balances / limits (e.g. a balance update): nothing to record.
        if (all.all { it.role == Role.AVAILABLE }) return ParseResult.Ignored(bank, "Balance update")
        val found = dates(text, zone)
        val references = refs(text)

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
        if (notDone.containsMatchIn(text) || notDoneIntl.containsMatchIn(text)) return null
        // Only a fee in the message ("Annual fee of AED 300 has been charged"): the fee is the transaction.
        val feeOnly = all.none { it.role == Role.TXN } && takenWord.containsMatchIn(text)
        val txnAmount = all.firstOrNull { it.role == Role.TXN } ?: all.firstOrNull { feeOnly && it.role == Role.FEE } ?: return null
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
        // Interest / profit / dividend only counts when it describes THIS amount ("AED 37.05 as interest",
        // "Interest of AED 45 charged"), not a footer like "pay in full to avoid interest".
        val near = text.substring(maxOf(0, txnAmount.start - 60), minOf(text.length, txnAmount.end + 30))
        val returnMatch = returnWord.find(near)
        val repayment = repaymentWord.containsMatchIn(text) && !isDue.containsMatchIn(text)
        when {
            // Interest / profit / dividend paid to you (not charged).
            returnMatch != null && paidToYou.containsMatchIn(near) && !takenWord.containsMatchIn(near) -> {
                type = TxnType.TRANSFER_IN
                fixed = when (returnMatch.groupValues[1].lowercase()) {
                    "profit" -> "Profit"
                    "interest" -> "Interest"
                    else -> "Dividend"
                }
            }
            // Interest / profit / finance charges the bank takes.
            returnMatch != null && takenWord.containsMatchIn(near) && !paidToYou.containsMatchIn(near) -> {
                type = TxnType.PURCHASE; fixed = "Interest / finance charge"
            }
            feeOnly -> {
                type = TxnType.PURCHASE
                fixed = feeName.find(text)?.groupValues?.get(1)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Bank fee"
            }
            // Paying back a loan (money out) vs. a credit card / credit line (a payment to that card).
            repayment && loanWord.containsMatchIn(text) -> { type = TxnType.TRANSFER_OUT; fixed = "Loan repayment" }
            repayment && !refundWord.containsMatchIn(text) -> { type = TxnType.PAYMENT; fixed = "Card payment received" }
            cardCtx && paymentReceived.containsMatchIn(text) -> { type = TxnType.PAYMENT; fixed = "Card payment received" }
            refundWord.containsMatchIn(text) && !cashbackCard -> {
                type = TxnType.REFUND
                if (Regex("""cash\s?back""", I).containsMatchIn(text)) fixed = "Cashback"
            }
            payingACard.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            atmWord.containsMatchIn(text) && !creditFirst -> { type = TxnType.PURCHASE; fixed = "ATM cash withdrawal" }
            creditFirst -> when {
                accountCtx && !creditCardWord.containsMatchIn(text) -> type = TxnType.TRANSFER_IN
                !cardCtx -> type = TxnType.TRANSFER_IN
                Regex("""\bpayment\b|\bfrom\s+[A-Z]*\d{6,}""", I).containsMatchIn(text) -> { type = TxnType.PAYMENT; fixed = "Card payment received" }
                else -> type = TxnType.REFUND
            }
            debit != null && strongTransfer.containsMatchIn(text) && !spendWord.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            debit != null && !cardCtx && transferWord.containsMatchIn(text) -> type = TxnType.TRANSFER_OUT
            debit != null -> type = TxnType.PURCHASE
            // "Your standing instruction ... has been executed successfully for AED 8000": money left the account, no debit word.
            standingDone.containsMatchIn(text) && BankRules.completedAction.containsMatchIn(text) -> {
                type = TxnType.TRANSFER_OUT; fixed = "Standing instruction"
            }
            else -> return null
        }

        // ---- which card / account
        val debitCard = debitCardWord.containsMatchIn(text)
        val creditCard = creditCardWord.containsMatchIn(text)
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
                ?: firstClean(atMerchantIntl, text)
                ?: clean(bracketMerchant.find(text)?.groupValues?.get(1))
                ?: if (cardType == CardType.ACCOUNT) "Account debit" else "Card purchase"
            TxnType.REFUND -> clean(fromMerchant.find(text)?.groupValues?.get(1))
                ?: clean(atMerchant.find(text)?.groupValues?.get(1))
                ?: firstClean(atMerchantIntl, text)
                ?: firstClean(fromMerchantIntl, text)
                ?: clean(bracketMerchant.find(text)?.groupValues?.get(1))
                ?: "Refund"
            TxnType.TRANSFER_OUT -> destination?.let { SmsParser.transferLabel(it.last4) }
                ?: clean(toMerchant.find(text)?.groupValues?.get(1))?.let { "Transfer to $it" }
                ?: clean(forMerchant.find(text)?.groupValues?.get(1))
                ?: firstClean(toMerchantIntl, text)?.let { "Transfer to $it" }
                ?: "Money out (transfer)"
            TxnType.TRANSFER_IN -> when {
                salaryWord.containsMatchIn(text) -> "Salary"
                Regex("""\bcash deposit\b""", I).containsMatchIn(text) -> "Cash deposit"
                Regex("""\binward remittance\b""", I).containsMatchIn(text) -> "Inward remittance"
                else -> clean(asMerchant.find(text)?.groupValues?.get(1))
                    ?: clean(forMerchant.find(text)?.groupValues?.get(1))
                    ?: clean(fromMerchant.find(text)?.groupValues?.get(1))?.let { "From $it" }
                    ?: firstClean(fromMerchantIntl, text)?.let { "From $it" }
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
            CalendarDate.fromEpochMillis(receivedAt, zone) == txnDate.date -> receivedAt
            else -> txnDate.date.atTime(12, 0).toEpochMillis(zone)
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

    private val standingDone = Regex("""\bstanding\s+(?:instruction|order)s?\b""", I)

    /**
     * A best guess for a message [read] could not settle, only to pre-fill the Fix form (never stored by itself):
     * the transaction amount (not a balance), its currency, the card / account digits and a likely kind. Null when the
     * message has no amount at all.
     */
    fun suggest(bank: String, text: String, receivedAt: Long): ParsedTransaction? {
        val all = mentions(text)
        val amount = all.firstOrNull { it.role == Role.TXN }?.takeIf { it.amount.signum() > 0 } ?: return null
        val available = all.firstOrNull { it.role == Role.AVAILABLE && it.start > amount.start }
        val ref = refs(text).firstOrNull()
        val accountCtx = accountWord.containsMatchIn(text)
        val cardCtx = cardWord.containsMatchIn(text)
        val credit = creditWord.find(text)
        val debit = debitWord.find(text)
        val creditFirst = (credit != null && (debit == null || credit.range.first < debit.range.first)) || creditSignal.containsMatchIn(text)
        val type = when {
            creditFirst -> if (cardCtx && !accountCtx) TxnType.REFUND else TxnType.TRANSFER_IN
            strongTransfer.containsMatchIn(text) || transferWord.containsMatchIn(text) || standingDone.containsMatchIn(text) -> TxnType.TRANSFER_OUT
            else -> TxnType.PURCHASE
        }
        val cardType = when {
            creditCardWord.containsMatchIn(text) -> CardType.CREDIT
            debitCardWord.containsMatchIn(text) -> CardType.DEBIT
            accountCtx -> CardType.ACCOUNT
            type == TxnType.TRANSFER_OUT || type == TxnType.TRANSFER_IN -> CardType.ACCOUNT
            else -> CardType.CREDIT
        }
        val merchant = when {
            standingDone.containsMatchIn(text) -> "Standing instruction"
            type == TxnType.TRANSFER_OUT -> "Money out (transfer)"
            type == TxnType.TRANSFER_IN -> "Money in"
            else -> ""
        }
        return ParsedTransaction(
            bank = bank, cardLast4 = ref?.last4, cardType = cardType, merchant = merchant, amount = amount.amount.abs(),
            currency = amount.currency, type = type, timestamp = receivedAt, dateFromSms = false,
            availableLimit = available?.amount, accountNotNamed = ref == null && cardType == CardType.ACCOUNT, auto = false,
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
        return bankish && !Regex("""\b(?:OTP|one[\s-]?time\s+pass)""", I).containsMatchIn(text) && !SmsParser.isOtp(text)
    }
}
