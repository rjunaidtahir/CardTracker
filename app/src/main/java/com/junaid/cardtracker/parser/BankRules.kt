package com.junaid.cardtracker.parser

import java.math.BigDecimal

/**
 * ============================================================================
 *  BANK PARSING RULES — the one file to edit when a bank changes its SMS format
 * ============================================================================
 *
 * How it works
 *  - An SMS is only looked at if its sender matches one of a bank's [Bank.senderIds].
 *  - Line breaks in the SMS are turned into single spaces before matching.
 *  - The bank's rules are tried top to bottom; the first one that matches wins.
 *  - If nothing matches, the ignore list is checked (OTP, declined, limit changes...).
 *    OTP messages are dropped completely (store = false); other ignored SMS are kept for reference.
 *  - Anything still unmatched that contains an amount shows up on the Review tab.
 *  - Patterns are case-insensitive Kotlin/Java regex. Write "\s+" for "one or more spaces".
 *
 * Placeholder tokens you can use inside a pattern
 *   {CUR}       3-letter currency, e.g. AED, USD          -> currency
 *   {AMOUNT}    1,234.56 / 90.90 / 4300                   -> transaction amount
 *   {CARDTYPE}  "Credit" or "Debit" -> sets the card type (otherwise the rule's cardType, default CREDIT)
 *   {CARD}      XXXX0831 / XX3538 / *** 5258 / 3944 / 529106******3976  -> last 4 digits
 *   {MERCHANT}  merchant text (shortest match that fits)
 *   {CITY}      optional ", DUBAI" style suffix after a merchant (ignored)
 *   {DATETIME}  19/09/26 17:48 / 13/09/2026 11:58:47 / 14-09-2026, 10:20:46 / 03/09/2026
 *   {AVAIL}     available limit / balance amount (optional info)
 *   {TOTAL}     statement balance / total due
 *   {MIN}       minimum due
 *   {DUE}       due date: 26/09/2026 or Oct 14 2026
 *   {STMTDATE}  statement date
 *   {ANYCUR}    a currency code that isn't captured (e.g. before an available limit)
 *
 * Adding a new format: copy the SMS into ParserTest.kt, add a Rule here, run the tests.
 * After installing the new build, use Settings → "Re-parse all stored SMS" to apply new rules
 * to messages already stored.
 */
object BankRules {

    val banks: List<Bank> = listOf(

        // ---------------------------------------------------------------- FAB
        Bank(
            name = "FAB",
            senderIds = listOf("FAB"),
            rules = listOf(
                // Credit Card Purchase / Card No XXXX0831 / AED 5.00 / PICCADILLY WHIPPY CAFE DUBAI ARE /
                // 19/09/26 17:48 / Avl Bal AED 678.88 / ...
                Rule(
                    id = "fab-purchase",
                    kind = RuleKind.TRANSACTION,
                    // Debit variant ("Debit Card Purchase") assumed to follow the same layout: not yet verified.
                    pattern = """{CARDTYPE} Card Purchase\s+Card No\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+{MERCHANT}\s+{DATETIME}(?:\s+Avl Bal\s+{ANYCUR}\s*{AVAIL})?""",
                ),
            ),
        ),

        // -------------------------------------------------------- Emirates NBD
        Bank(
            name = "Emirates NBD",
            senderIds = listOf("EmiratesNBD", "ENBD"),
            rules = listOf(
                // Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI.
                // Avl Cr. Limit is AED 885.12
                Rule(
                    id = "enbd-purchase",
                    kind = RuleKind.TRANSACTION,
                    // Debit variant assumed ("with Debit Card ending ... Avl Bal"): not yet verified.
                    pattern = """Purchase of {CUR}\s*{AMOUNT} with {CARDTYPE} Card ending {CARD} at {MERCHANT}{CITY}\.\s+Avl (?:Cr\.? Limit|Bal(?:ance)?) is {ANYCUR}\s*{AVAIL}""",
                ),
            ),
        ),

        // --------------------------------------------------------------- ADCB
        Bank(
            name = "ADCB",
            senderIds = listOf("ADCBAlert", "ADCB"),
            rules = listOf(
                // Credit Card XX3538 was used for AED90.90 on 13/09/2026 11:58:47 at talabat.com, DUBAI-AE.
                // Available limit AED4736.08
                Rule(
                    id = "adcb-purchase",
                    kind = RuleKind.TRANSACTION,
                    // Debit variant assumed ("Debit Card XX1234 ... Available balance"): not yet verified.
                    pattern = """{CARDTYPE} Card {CARD} was used for {CUR}\s*{AMOUNT} on {DATETIME} at {MERCHANT}{CITY}\.\s+Available (?:limit|balance)\s*{ANYCUR}\s*{AVAIL}""",
                ),
                // Cr.Card XXX3538 Billing alert: Total due to avoid fin. charges: AED1263.92. Due date Oct 14 2026;
                // Pay min. AED100.00 by due date ...
                Rule(
                    id = "adcb-statement",
                    kind = RuleKind.STATEMENT,
                    pattern = """Cr\.?\s*Card {CARD} Billing alert:\s*Total due.*?:\s*{CUR}\s*{TOTAL}\.?\s+Due date {DUE};?\s+Pay min\.?\s*{ANYCUR}\s*{MIN}""",
                ),
            ),
        ),

        // ----------------------------------------------------------- Al Hilal
        Bank(
            name = "Al Hilal",
            senderIds = listOf("AlHilal", "AlHilalBank"),
            rules = listOf(
                // Purchase of 3,589.95 AED at LIVA INS B S C CLOSED    ABU DHABI    AE on 14-09-2026,  10:20:46,
                // card 3976. Limit: 338.04 AED.
                Rule(
                    id = "alhilal-purchase",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Purchase of {AMOUNT}\s*{CUR} at {MERCHANT} on {DATETIME},?\s+card {CARD}(?:\.\s+Limit:\s*{AVAIL})?""",
                ),
            ),
        ),

        // --------------------------------------------------------------- HSBC
        Bank(
            name = "HSBC",
            senderIds = listOf("HSBC-UAE", "HSBC"),
            rules = listOf(
                // HSBC Credit Card ending *** 5258 Statement Date 11/08/2026. Total Amt Due AED 79.20,
                // Due Date 05/09/2026. Min. Amt Due AED 33.70. ...
                Rule(
                    id = "hsbc-statement",
                    kind = RuleKind.STATEMENT,
                    pattern = """Credit Card ending {CARD}\s+Statement Date {STMTDATE}\.?\s+Total Amt Due {CUR}\s*{TOTAL},?\s+Due Date {DUE}\.?\s+Min\.? Amt Due {ANYCUR}\s*{MIN}""",
                ),
                // Thank you for the payment of AED 900.00 on 03/09/2026 towards your Credit Card ending *** 5258.
                Rule(
                    id = "hsbc-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PAYMENT,
                    fixedMerchant = "Card payment",
                    pattern = """payment of {CUR}\s*{AMOUNT} on {DATETIME} towards your Credit Card ending {CARD}(?:.*?available balance is {ANYCUR}\s*{AVAIL})?""",
                ),
                // You have earned cashback of AED 0.39 credited to your card ending *** 5258.
                Rule(
                    id = "hsbc-cashback",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    fixedMerchant = "Cashback",
                    pattern = """earned cashback of {CUR}\s*{AMOUNT} credited to your card ending {CARD}""",
                ),
            ),
        ),

        // ------------------------------------------------------------ Mashreq
        // No verified sample yet: relies on the generic rules below. Check the sender ID on your phone.
        Bank(
            name = "Mashreq",
            senderIds = listOf("Mashreq", "MashreqBank"),
            rules = emptyList(),
            useGenericRules = true,
        ),
    )

    /** Best-effort patterns for banks without their own verified rules (useGenericRules = true). */
    val genericRules: List<Rule> = listOf(
        Rule(
            id = "generic-purchase-card-at",
            kind = RuleKind.TRANSACTION,
            pattern = """(?:purchase|transaction|spent)\s+of\s+{CUR}\s*{AMOUNT}.*?card\s+(?:ending|no\.?|number)?\s*(?:with\s+)?{CARD}\s+at\s+{MERCHANT}(?:\s+on\s+{DATETIME})?(?:[.,;]|$)""",
        ),
        Rule(
            id = "generic-card-used-for",
            kind = RuleKind.TRANSACTION,
            pattern = """card\s+(?:ending\s+)?{CARD}\s+(?:was|has been)\s+used\s+for\s+{CUR}\s*{AMOUNT}.*?\bat\s+{MERCHANT}(?:[.,;]|$)""",
        ),
        Rule(
            id = "generic-statement",
            kind = RuleKind.STATEMENT,
            pattern = """card.*?{CARD}.*?(?:statement balance|total (?:amount )?due|total amt due)\D*?{CUR}\s*{TOTAL}.*?(?:min(?:imum)?\.?\s*(?:amount|amt)?\s*due|pay min)\D*?{ANYCUR}\s*{MIN}.*?due (?:date|on)\s*:?\s*{DUE}""",
        ),
    )

    /**
     * Checked BEFORE any rule: a message with an OTP keyword AND something that looks like a code
     * ("123456 is your OTP", "OTP: 4821", "...password is 482913"). Dropped, never stored.
     * A plain "never share your OTP" footer on a purchase alert doesn't match (no code), so the
     * purchase is still parsed.
     */
    val otpPreCheck = IgnoreRule(
        "OTP",
        """^(?=.*\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode)\b)(?=.*(\b\d{6,8}\b|\b(is|:)\s*\d{4,8}\b|\b\d{4,8}\s+is\s+(your|the)\b)).*""",
        store = false,
    )

    /** Checked only when no transaction/statement rule matched. Applies to every bank. */
    val globalIgnore: List<IgnoreRule> = listOf(
        IgnoreRule("OTP", """\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode|PIN\s+is)\b""", store = false),
        IgnoreRule("Declined transaction", """\b(declined|unsuccessful|was not successful)\b"""),
        IgnoreRule("Limit change", """\blimit\b.*\b(has been|was)\s+(changed|updated|increased|decreased|set)\b"""),
    )

    /**
     * Approximate AED rates for foreign-currency SMS (bank SMS rarely include the AED
     * equivalent). Conversions using these are flagged as estimates. Edit freely.
     */
    val fxToAed: Map<String, BigDecimal> = mapOf(
        "AED" to "1",
        "USD" to "3.6725",
        "EUR" to "4.00",
        "GBP" to "4.70",
        "SAR" to "0.979",
        "QAR" to "1.009",
        "OMR" to "9.54",
        "BHD" to "9.74",
        "KWD" to "12.00",
        "INR" to "0.042",
        "PKR" to "0.013",
        "TRY" to "0.09",
        "THB" to "0.11",
        "JPY" to "0.024",
        "CHF" to "4.30",
        "CAD" to "2.65",
        "AUD" to "2.40",
        "EGP" to "0.075",
    ).mapValues { BigDecimal(it.value) }

    const val BASE_CURRENCY = "AED"
}
