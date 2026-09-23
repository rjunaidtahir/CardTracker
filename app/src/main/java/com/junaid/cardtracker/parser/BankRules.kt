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
        KnownAccount("8003", "FAB account", AccountKind.OWN_ACCOUNT, bank = "FAB"),
        KnownAccount("8005", "FAB account", AccountKind.OWN_ACCOUNT, bank = "FAB"),
        KnownAccount("3115", "FAB credit card", AccountKind.OWN_CARD, bank = "FAB"),
        KnownAccount("3944", "ENBD credit card", AccountKind.OWN_CARD, bank = "Emirates NBD"),
        KnownAccount("3538", "ADCB credit card", AccountKind.OWN_CARD, bank = "ADCB"),
        KnownAccount("5258", "HSBC credit card", AccountKind.OWN_CARD, bank = "HSBC"),
        KnownAccount("4680", "Mashreq credit card", AccountKind.OWN_CARD, bank = "Mashreq"),
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
                    pattern = """Inward Remittance\s+Credit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+(?:Value\s+)?Date\s+{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
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
                    pattern = """Outward Remittance\s+Debit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+(?:Value\s+)?Date\s+{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
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
                // funds transfer request of 200.00 AED to IBAN/Account/Card XXXX2001  has been processed successfully from ... (amount first)
                Rule(
                    id = "fab-funds-transfer-amount-first",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """funds transfer request of\s+{AMOUNT}\s*{CUR}\s+to\s+IBAN/Account/Card\s+{TO}\s+has been processed successfully from your account/card\s+{CARD}\s+on\s+{DATETIME}""",
                ),
                // your funds transfer request of AED 6,000.00 from account XXXX8001 to account XXXX8003 has been processed on 05/08/2026 07:17
                Rule(
                    id = "fab-account-to-account",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """funds transfer request of\s+{CUR}\s*{AMOUNT}\s+from account\s+{CARD}\s+to account\s+{TO}\s+has been processed on\s+{DATETIME}""",
                ),
                // Salary Credit / Account XXXX8001 / AED 20000.00 / 23/05/25 / Available Balance AED 38841.91
                Rule(
                    id = "fab-salary",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Salary",
                    pattern = """Salary Credit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+(?:Date\s+)?{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
                // Cash Deposit / Credit / Account XXXX8001 / AED 9900.00 / Date 25/05/26
                Rule(
                    id = "fab-cash-deposit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Cash deposit",
                    pattern = """Cash Deposit\s+Credit\s+Account\s+{CARD}\s+{CUR}\s*{AMOUNT}\s+(?:Value\s+)?Date\s+{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
                // ATM Cash withdrawal / Debit Account XXXX8001 / Card XXXX5919 / AED 302.00 / 14/10/25 09:37 / Available Balance AED ...
                // Counted as spending on the account (cash you'll spend). Change type to TRANSFER_OUT if you'd rather not.
                Rule(
                    id = "fab-atm",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PURCHASE,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "ATM cash withdrawal",
                    pattern = """ATM Cash withdrawal\s*/?\s*Debit\s+Account\s+{CARD}\s+Card\s+[X*\d]+\s+{CUR}\s*{AMOUNT}\s+{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
                // An amount of AED 110.81 has been credited to your FAB account XXXX8003 on 14/06/25 .Your Available Balance is AED 21377.94
                Rule(
                    id = "fab-account-credit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Account credit",
                    pattern = """An amount of\s+{CUR}\s*{AMOUNT}\s+has been credited to your FAB account\s+{CARD}\s+on\s+{DATETIME}\s*\.?\s*Your (?:Available )?Balance is\s+{ANYCUR}\s*{AVAIL}""",
                ),
                // An amount of AED 3865.95 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08
                // Typically EMIs / direct debits: a PURCHASE on the account. If a transfer SMS for the same money
                // exists (same amount, within a day) the two are merged and it becomes a transfer instead.
                Rule(
                    id = "fab-account-debit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PURCHASE,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Account debit (EMI / direct debit)",
                    pairGroup = "fab-transfer",
                    pattern = """An amount of\s+{CUR}\s*{AMOUNT}\s+has been debited from your FAB account\s+{CARD}\s+on\s+{DATETIME}\s*\.?\s*Your (?:Available )?Balance is\s+{ANYCUR}\s*{AVAIL}""",
                ),
                // Dear Customer, your payment of AED 100.00 for card 5492XXXXXXXX3115 has been processed on 06/01/2025
                Rule(
                    id = "fab-card-payment-2",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    defaultCardLast4 = FAB_ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """your payment of\s+{CUR}\s*{AMOUNT}\s+for card\s+{TO}\s+has been processed on\s+{DATETIME}""",
                ),
                // Dear Customer, your cashback amount of AED 100.00 has been credited to your credit card account with the card number ending 5492XXXXXXXX3115
                Rule(
                    id = "fab-card-cashback",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    fixedMerchant = "Cashback",
                    pattern = """cashback amount of\s+{CUR}\s*{AMOUNT}\s+has been credited to your credit card account with the card number ending\s+{CARD}""",
                ),
                // Your statement of the card ending with 3115 dated 11Jun25 has been sent ... The total amount due is AED 8,101.92.
                // Minimum due is AED 405.10. Due date is 07Jul25
                Rule(
                    id = "fab-statement",
                    kind = RuleKind.STATEMENT,
                    pattern = """statement of the card ending with\s+{CARD}\s+dated\s+{STMTDATE}\b.*?total amount due is\s+{CUR}\s*{TOTAL}\.?\s+Minimum due is\s+{ANYCUR}\s*{MIN}\.?\s+Due date is\s+{DUE}""",
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
                // Payment of AED 300.00 to ADNOC WALLET with Credit Card ending 9940. Avl Cr. Limit is AED 51,843.37.
                Rule(
                    id = "enbd-payment-to",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Payment of {CUR}\s*{AMOUNT} to {MERCHANT} with {CARDTYPE} Card ending {CARD}\.\s+Avl (?:Cr\.? Limit|Bal(?:ance)?) is {ANYCUR}\s*{AVAIL}""",
                ),
                // Amount of AED 10,960.00 from EMIRATES0002209069196 has been credited to your card ending with 9940. (a payment from an account)
                Rule(
                    id = "enbd-card-payment-received",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PAYMENT,
                    fixedMerchant = "Card payment received",
                    pattern = """Amount of {CUR}\s*{AMOUNT} from [A-Z]*\d{8,} has been credited to your card ending(?: with)? {CARD}""",
                ),
                // Amount of AED 43.81 from Amazon.ae has been credited to your card ending with 3944. Available limit is AED 54,081.68.
                Rule(
                    id = "enbd-refund-credited",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    pattern = """Amount of {CUR}\s*{AMOUNT} from {MERCHANT} has been credited to your card ending(?: with)? {CARD}""",
                ),
                // Purchase amount of AED 1.00 at ADNOC on your Credit Card ending 9940 has been refunded to your card account.
                Rule(
                    id = "enbd-purchase-refunded",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    pattern = """Purchase amount of {CUR}\s*{AMOUNT} at {MERCHANT} on your {CARDTYPE} Card ending {CARD} has been refunded""",
                ),
                // Emirates NBD Credit Card Mini Stmt for Card ending 9940: Statement date 09/05/26. Total Amt Due AED 7209.66,
                // Due Date 03/06/26. Min Amt Due AED 360.48
                Rule(
                    id = "enbd-statement",
                    kind = RuleKind.STATEMENT,
                    pattern = """Mini Stmt for Card ending {CARD}:?\s*Statement date {STMTDATE}\.?\s+Total Amt Due {CUR}\s*{TOTAL},?\s+Due Date {DUE}\.?\s+Min Amt Due {ANYCUR}\s*{MIN}""",
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
                // Your Cr.Card XXX3538 was used for AED59.50 on 18/08/2026 14:06:42 at talabat.com,DUBAI-AE. Avl. Cr.limit is AED5897.94
                Rule(
                    id = "adcb-purchase-2",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Cr\.?\s*Card {CARD} was used for {CUR}\s*{AMOUNT} on {DATETIME} at {MERCHANT}{CITY}\.\s+Avl\.?\s*Cr\.?\s*limit is\s*{ANYCUR}\s*{AVAIL}""",
                ),
                // Credit Card XXX3538 used for AED1213.94 (+2.99% foreign txn fee) on 12/03/2026 10:00:26 at AGODA.COM AL HAMRA R,London-GB.
                // Avl. Cr.limit AED1671.82
                Rule(
                    id = "adcb-purchase-fee",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Credit Card {CARD} used for {CUR}\s*{AMOUNT}(?:\s*\([^)]*\))? on {DATETIME} at {MERCHANT}{CITY}\.\s+Avl\.?\s*Cr\.?\s*limit(?: is)?\s*{ANYCUR}\s*{AVAIL}""",
                ),
                // Your payment of AED 102 against Credit Card no. XXX3538 was received at 08:21 AM on 24/08/2026. Thank you.
                Rule(
                    id = "adcb-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PAYMENT,
                    fixedMerchant = "Card payment",
                    pattern = """Your payment of {CUR}\s*{AMOUNT} against Credit Card no\.?\s*{CARD} was received at [\d:]+\s*[AP]M on {DATETIME}""",
                ),
                // An amount of AED510.15 has been reversed to your Credit Card XXX3538 on 21/12/2025 17:28:53 by AGODA.COM AL HAMRA V,INTERNET-GB.
                Rule(
                    id = "adcb-reversal",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    pattern = """amount of {CUR}\s*{AMOUNT} has been reversed to your Credit Card {CARD} on {DATETIME} by {MERCHANT}{CITY}\.?$""",
                ),
                // A Cr. transaction of AED 1558.46 on your account number XXX810001 was successful.Available balance is 9643.48.
                Rule(
                    id = "adcb-account-credit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Account credit",
                    pattern = """A Cr\. transaction of {CUR}\s*{AMOUNT} on your account number {CARD} was successful\.?\s*(?:Available balance is\s*{AVAIL})?""",
                ),
                Rule(
                    id = "adcb-account-debit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PURCHASE,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Account debit",
                    pattern = """A Dr\. transaction of {CUR}\s*{AMOUNT} on your account number {CARD} was successful\.?\s*(?:Available balance is\s*{AVAIL})?""",
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
                // A cashback amount of 3.14 AED was credited to your credit card ending with 3976. Your available limit is now 4,093.50 AED.
                Rule(
                    id = "alhilal-cashback",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    fixedMerchant = "Cashback",
                    pattern = """cashback amount of {AMOUNT}\s*{CUR} was credited to your credit card ending with {CARD}(?:.*?available limit is now {AVAIL})?""",
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
                // From HSBC: Your Credit Card ending with *** 5258 has been used for AED 33.25 on 02/12/2024 at W Z D WEST ZONE SUPERM.
                // Your available limit is AED 3277.07
                Rule(
                    id = "hsbc-purchase",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Credit Card ending with {CARD} has been used for {CUR}\s*{AMOUNT} on {DATETIME} at {MERCHANT}\.\s+Your available limit is {ANYCUR}\s*{AVAIL}""",
                ),
                // An amount of AED 41.53 has been reversed to your HSBC card ending *** 5258 (Amazon.ae). Your available limit is AED 8,786.31.
                Rule(
                    id = "hsbc-reversal",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    pattern = """amount of {CUR}\s*{AMOUNT} has been reversed to your HSBC card ending {CARD}\s*\((?<merchant>[^)]+)\)(?:.*?available limit is {ANYCUR}\s*{AVAIL})?""",
                ),
            ),
        ),

        // ------------------------------------------------------------ Mashreq
        // No verified sample yet: relies on the generic rules below. Check the sender ID on your phone.
        Bank(
            name = "Mashreq",
            senderIds = listOf("Mashreq", "MashreqBank"),
            rules = listOf(
                // Your AC No:XXXXXXXX7639 is debited with AED 20000.00 for Aani Instant Payments (Local IPP Transfer). Login to ...
                Rule(
                    id = "mashreq-account-debit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pattern = """AC No:?\s*{CARD} is debited with {CUR}\s*{AMOUNT} for {MERCHANT}\.\s+Login""",
                ),
                // Your AC No: XXXXXXXX7639 is credited with AED 20000.00 for Salary. / ... as Joining Bonus.
                Rule(
                    id = "mashreq-account-credit",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    pattern = """AC No:?\s*{CARD} is credited with {CUR}\s*{AMOUNT} (?:for|as) {MERCHANT}\.\s+Login""",
                ),
                // Amount of AED 20000.00 has been debited from your Mashreq account no. XXXXXXXX7639 for Account to Account Transfer.
                Rule(
                    id = "mashreq-account-debit-2",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pattern = """Amount of {CUR}\s*{AMOUNT} has been debited from your Mashreq account no\.?\s*{CARD} for {MERCHANT}\.\s+Login""",
                ),
                // Your payment of AED 1993 has been received against your Mashreq Cashback card ending 4680 on 05/09/2026.
                Rule(
                    id = "mashreq-card-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PAYMENT,
                    fixedMerchant = "Card payment",
                    pattern = """payment of {CUR}\s*{AMOUNT} has been received against your Mashreq .*?card ending {CARD}(?: on {DATETIME})?""",
                ),
            ),
            // Card purchase SMS not seen yet: generic rules as a fallback until you send samples.
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
        """^(?=.*\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode|auth(?:entication|ori[sz]ation)?\s+code)\b)(?=.*(\b\d{6,8}\b|\b(is|:)\s*\d{4,8}\b|\b\d{4,8}\s+is\s+(your|the)\b)).*""",
        store = false,
    )

    /** Checked only when no transaction/statement rule matched. Applies to every bank. */
    val globalIgnore: List<IgnoreRule> = listOf(
        IgnoreRule("OTP", """\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode|PIN\s+is)\b""", store = false),
        IgnoreRule("Declined transaction", """\b(declined|unsuccessful|was not successful|could not be completed)\b"""),
        IgnoreRule("Approval request", """\btap to approve\b|\bSecurePass\b|\bapprove it\b"""),
        IgnoreRule("Payment reminder", """Payment for .{0,40}card ending \d{4} is due"""),
        IgnoreRule("Card setting / service notice", """\bsetting for your Credit Card\b|\bdigital card\b|\bService Request\b|\bbeneficiary has been added\b"""),
        IgnoreRule("Transfer request (not yet processed)", """\bRequest received for fund transfer\b"""),
        IgnoreRule(
            "Advert",
            """STOP\s*(?:to\s*)?\d{3,5}\b|\bT&C|\bapply\s+(now|via|to|for|on)\b|\beligible\b|\bpre-?approved\b|\bConvert now\b|""" +
                """\bOpt-?out\b|\bConditions apply\b|\bSMS\s+\w+\s+to\s+\d{4}\b|\bSpecial Offer\b|\beasy monthly instalments\b|""" +
                """\bValid until\b|\bOffer valid\b|\bLearn more\b|\bPay as low as\b""",
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
        "AZN" to "2.16",
    ).mapValues { BigDecimal(it.value) }

    const val BASE_CURRENCY = "AED"
}
