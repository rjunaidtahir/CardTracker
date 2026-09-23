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

    /** Your FAB bank account (last 4). Used for FAB SMS that don't say which account paid. */
    const val FAB_ACCOUNT = "8001"

    /**
     * Destinations that appear in transfer SMS. OWN_CARD transfers are shown as a payment to that
     * card (never spending). FAMILY / OTHER transfers are just money leaving the account.
     * A destination not listed here that matches one of your credit cards is treated as OWN_CARD.
     */
    val knownAccounts: List<KnownAccount> = listOf(
        KnownAccount(FAB_ACCOUNT, "FAB account", AccountKind.OWN_ACCOUNT, bank = "FAB"),
        KnownAccount("0831", "FAB credit card", AccountKind.OWN_CARD, bank = "FAB"),
        KnownAccount("9940", "ENBD credit card", AccountKind.OWN_CARD, bank = "Emirates NBD"),
        KnownAccount("3976", "Al Hilal credit card", AccountKind.OWN_CARD, bank = "Al Hilal"),
        KnownAccount("7701", "Wife's ENBD current account", AccountKind.FAMILY),
        KnownAccount("6901", "Wife's Emirates Islamic credit card", AccountKind.FAMILY),
    )

    fun ruleById(id: String?): Rule? = id?.let { rid -> (banks.flatMap { it.rules } + genericRules).firstOrNull { it.id == rid } }

    fun knownAccount(last4: String): KnownAccount? = knownAccounts.firstOrNull { it.last4 == last4 }

    /** Merchant text for a transfer: "Payment to ENBD credit card ·9940", "Transfer to Wife's ... ·7701". */
    fun counterpartyLabel(last4: String): String {
        val k = knownAccount(last4) ?: return "Transfer to ·$last4"
        return if (k.kind == AccountKind.OWN_CARD) "Payment to ${k.label} ·$last4" else "Transfer to ${k.label} ·$last4"
    }

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

                // ---- FAB bank account (tracked as an ACCOUNT, both money in and out) ----
                // Inward Remittance / Credit / Account XXXX8001 / AED 200.00 / Date 21/09/2026 / Balance AED 2586.00
                Rule(
                    id = "fab-inward-remittance",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Inward remittance",
                    pattern = """Inward Remittance\s+Credit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+Date\s+{DATETIME}(?:\s+Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
                // Outward Remittance / Debit / Account XXXX8001 / AED 1000.00 / Date 17/09/2026 / Balance AED 2386.00
                // FAB also sends a "funds transfer ... processed" SMS for the same money: pairGroup merges them.
                Rule(
                    id = "fab-outward-remittance",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Outward remittance",
                    pairGroup = "fab-transfer",
                    pattern = """Outward Remittance\s+Debit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+Date\s+{DATETIME}(?:\s+Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
                // Dear Customer, your funds transfer request of  AED 1,000.00 to IBAN/Account/Card XXXX9940  has been
                // processed successfully from your account/card XXXX8001 on 17/09/2026 21:45
                Rule(
                    id = "fab-funds-transfer",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """funds transfer request of\s+{CUR}\s*{AMOUNT}\s+to\s+IBAN/Account/Card\s+{TO}\s+has been processed successfully from your account/card\s+{CARD}\s+on\s+{DATETIME}""",
                ),
                // Dear Customer, Your payment instructions of AED 500.00 to 5425********0831 has been processed on 15/09/2026 06:24
                // (paying a card from the account; the SMS doesn't say which account, so defaultCardLast4 is used)
                Rule(
                    id = "fab-card-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    defaultCardLast4 = FAB_ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """payment instructions of\s+{CUR}\s*{AMOUNT}\s+to\s+{TO}\s+has been processed on\s+{DATETIME}""",
                ),
                // Dear Customer, Your payment instructions of AED 313.95 to HomeInternet for consumer number 045923079
                // has been processed on 15/09/2026 14:23   (a bill paid from the account: a PURCHASE on the account)
                Rule(
                    id = "fab-bill-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PURCHASE,
                    cardType = CardType.ACCOUNT,
                    defaultCardLast4 = FAB_ACCOUNT,
                    pattern = """payment instructions of\s+{CUR}\s*{AMOUNT}\s+to\s+{MERCHANT}\s+for consumer number\s+\S+\s+has been processed on\s+{DATETIME}""",
                ),
                // Congratulations! You have successfully redeemed 20000 FAB Rewards to save on your bills. Value: AED 50 ...
                // Logged as cashback (REFUND), with no card, so it reduces spending.
                Rule(
                    id = "fab-rewards-redemption",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    fixedMerchant = "FAB Rewards redemption",
                    pattern = """redeemed\s+[\d,]+\s+FAB Rewards.*?Value:\s*{CUR}\s*{AMOUNT}""",
                ),
            ),
            ignore = listOf(
                // "...has been scheduled. Transfer of AED 500.0 will be done on 28/09/2026" / "deregistered your standing
                // Instruction": nothing has moved yet; the "processed" SMS is recorded when it happens.
                IgnoreRule("Scheduled / standing instruction", """has been scheduled|standing\s+instruction"""),
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
        IgnoreRule(
            "Advert",
            """\bSTOP\b.{0,8}\d{3,5}\b|\bT&C|\bapply\s+(now|via|to|for|on)\b|\beligible\b|\bpre-?approved\b""",
        ),
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
