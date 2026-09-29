package com.uaefinancial.tracker.parser
import com.uaefinancial.tracker.core.Decimal


/**
 * ============================================================================
 *  BANK PARSING RULES — the one file to edit when a bank changes its SMS format
 * ============================================================================
 *
 * How it works
 *  - An SMS is only looked at if its sender matches one of a bank's [Bank.senderIds], or a sender the
 *    user added in the app (More → Bank senders).
 *  - Line breaks in the SMS are turned into single spaces before matching.
 *  - The bank's rules are tried top to bottom; the first one that matches wins.
 *  - If nothing matches, the ignore list is checked (OTP, declined, limit changes, adverts...).
 *    OTP messages are dropped completely (store = false); other ignored SMS are kept for reference.
 *  - Still nothing: the smart reader (SmartParser.kt) reads the SMS by its wording. This is how banks
 *    without their own rules work. Its results are marked "auto".
 *  - Anything still unread that contains an amount goes to "Needs review", where the user can fix it by hand.
 *  - Patterns are case-insensitive Kotlin/Java regex. Write "\s+" for "one or more spaces".
 *
 * Placeholder tokens you can use inside a pattern
 *   {CUR}       3-letter currency, e.g. AED, USD          -> currency
 *   {AMOUNT}    1,234.56 / 90.90 / 4300                   -> transaction amount
 *   {CARDTYPE}  "Credit" or "Debit" -> sets the card type (otherwise the rule's cardType, default CREDIT)
 *   {CARD}      XXXX0831 / XX3538 / *** 5258 / 3944 / 529106******3976  -> last 4 digits
 *   {MERCHANT}  merchant text (shortest match that fits)
 *   {CITY}      optional ", DUBAI" style suffix after a merchant (ignored)
 *   {DATETIME}  19/09/26 17:48 / 13/09/2026 11:58:47 / 14-09-2026, 10:20:46 / 03/09/2026 /
 *               08-SEP-2026, 07:42:23 AM / Tuesday, 7 July 2026, 3:16 pm / May 25 2026 11:02AM / 25/Mar/2026 01:40
 *   {AVAIL}     available limit / balance amount (optional info; may be negative)
 *   {TOTAL}     statement balance / total due (may be negative = credit balance)
 *   {MIN}       minimum due
 *   {DUE}       due date: 26/09/2026 or Oct 14 2026
 *   {STMTDATE}  statement date
 *   {ANYCUR}    a currency code that isn't captured (e.g. before an available limit)
 *
 * Adding a new format: copy the SMS into ParserTest.kt, add a Rule here, run the tests.
 * After installing the new build, use More → "Re-read stored messages" to apply new rules
 * to messages already stored.
 */
object BankRules {

    fun ruleById(id: String?): Rule? = id?.let { rid -> banks.asSequence().flatMap { it.rules }.firstOrNull { it.id == rid } }

    val banks: List<Bank> = listOf(

        // ---------------------------------------------------------------- FAB
        Bank(
            name = "FAB",
            senderIds = listOf("FAB"),
            rules = listOf(
                // Credit Card Purchase / Card No XXXX0831 / AED 5.00 / PICCADILLY WHIPPY CAFE DUBAI ARE /
                // 19/09/26 17:48 / Avl Bal AED 678.88 / ...
                // Debit Card Purchase / Debit Account XXXX8001 / Card XXXX5919 / AED 185.38 / Amazon.ae   Dubai  AE /
                // 12/11/25 22:03 / Available Balance AED ...   (a debit card spend: a PURCHASE on the account)
                Rule(
                    id = "fab-debit-card-purchase",
                    kind = RuleKind.TRANSACTION,
                    cardType = CardType.ACCOUNT,
                    pattern = """Debit Card Purchase\s*/?\s*Debit\s+Account\s+{CARD}\s+Card\s+[X*\d]+\s+{CUR}\s*{AMOUNT}\s+{MERCHANT}\s+{DATETIME}(?:\s+(?:Available\s+)?Balance\s+{ANYCUR}\s*{AVAIL})?""",
                ),
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
                // Your Dubai First card payment request of AED 8,000.00 to IBAN/Account/Card XXXX8623 was processed
                // successfully from your account/card XXXX8001 on 02/09/2026 10:15
                Rule(
                    id = "fab-card-payment-request",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pairGroup = "fab-transfer",
                    pattern = """card payment request of\s+{CUR}\s*{AMOUNT}\s+to\s+IBAN/Account/Card\s+{TO}\s+(?:was|has been) processed successfully from your account/card\s+{CARD}(?:\s+on\s+{DATETIME})?""",
                ),
                // Dear Customer, a debit of AED 500.00 has been made from your account XXXX8001 against your request for
                // UAE PGS payment to JOHN SMITH through FAB Online on 25/Mar/2026 01:40
                Rule(
                    id = "fab-pgs-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    pairGroup = "fab-transfer",
                    describesDestination = true,
                    pattern = """a debit of\s+{CUR}\s*{AMOUNT}\s+has been made from your account\s+{CARD}\s+against your request for\s+.*?payment to\s+{MERCHANT}\s+through FAB\b.*?\bon\s+{DATETIME}""",
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
                    accountNotNamed = true,
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
                // Dear Customer, the payment due date of your FAB Credit Card ending with 2784 is 06-12-2023. The total amount
                // due is AED 3,734.00 and the Minimum due amount is AED 186.70.
                Rule(
                    id = "fab-statement-due",
                    kind = RuleKind.STATEMENT,
                    pattern = """payment due date of your FAB Credit Card ending with\s+{CARD}\s+is\s+{DUE}\.?\s+The total amount due is\s+{CUR}\s*{TOTAL}\s+and the Minimum due amount is\s+{ANYCUR}\s*{MIN}""",
                ),
                // Your statement of the card ending with 3115 dated 11Jun25 has been sent ... The total amount due is AED 8,101.92.
                // Minimum due is AED 405.10. Due date is 07Jul25
                Rule(
                    id = "fab-statement",
                    kind = RuleKind.STATEMENT,
                    pattern = """statement of the card ending with\s+{CARD}\s+dated\s+{STMTDATE}\b.*?total amount due is\s+{CUR}\s*{TOTAL}\.?\s+Minimum due is\s+{ANYCUR}\s*{MIN}\.?\s+Due date is\s+{DUE}""",
                ),
                // Dear Customer, Your payment instructions of AED 500.00 to 5425********0831 has been processed on 15/09/2026 06:24
                // (paying a card from the account; the SMS doesn't say which account: accountNotNamed)
                Rule(
                    id = "fab-card-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    accountNotNamed = true,
                    pairGroup = "fab-transfer",
                    pattern = """payment instructions of\s+{CUR}\s*{AMOUNT}\s+to\s+{TO}\s+has been processed on\s+{DATETIME}""",
                ),
                // Dear Customer, Your payment instructions of AED 313.95 to HomeInternet for consumer number 045923079
                // has been processed on 15/09/2026 14:23   (a bill paid from the account: a PURCHASE on the account)
                // Also: Out of total amount due of AED 1308.34, payment of AED 1000 to DEWA for consumer number ... has been processed on 16/08/2023
                Rule(
                    id = "fab-bill-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PURCHASE,
                    cardType = CardType.ACCOUNT,
                    accountNotNamed = true,
                    pattern = """payment(?: instructions)? of\s+{CUR}\s*{AMOUNT}\s+to\s+{MERCHANT}\s+for consumer number\s+\S+\s+has been processed on\s+{DATETIME}""",
                ),
                // Congratulations! You have successfully redeemed 20000 FAB Rewards to save on your bills. Value: AED 50 ...
                // Logged as cashback (REFUND), with no card, so it reduces spending.
                Rule(
                    id = "fab-rewards-redemption",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    fixedMerchant = "FAB Rewards redemption",
                    pattern = """redeemed\s+[\d,]+\s+FAB(?: [A-Za-z ]+?)? Rewards.*?Value:\s*{CUR}\s*{AMOUNT}""",
                ),
            ),
            ignore = listOf(
                // "...has been scheduled. Transfer of AED 500.0 will be done on 28/09/2026" / "deregistered your standing
                // Instruction": nothing has moved yet; the "processed" SMS is recorded when it happens.
                IgnoreRule("Scheduled transfer", """has been scheduled"""),
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
                // Payment of AED 2,000.00 towards your Credit Card ending 9940 on 03/09/2026 was received through ... Thank you.
                Rule(
                    id = "enbd-card-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.PAYMENT,
                    fixedMerchant = "Card payment received",
                    pattern = """Payment of {CUR}\s*{AMOUNT} towards your {CARDTYPE} Card ending {CARD}(?: on {DATETIME})? was received""",
                ),
                // AED 50.00 has been debited from your Credit Card 0866 to top up your Nol e-purse. Avl.limit AED 1,234.00
                Rule(
                    id = "enbd-nol-topup",
                    kind = RuleKind.TRANSACTION,
                    fixedMerchant = "Nol top-up",
                    pattern = """{CUR}\s*{AMOUNT} has been debited from your {CARDTYPE} Card (?:ending\s+)?{CARD} to top up your Nol""",
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
                                        // Also "Your credit card xxx3538 was used for AED 257.00 on ... at ...,DUBAI- AE. Available credit limit is now AED ..."
                    pattern = """{CARDTYPE} Card {CARD} was used for {CUR}\s*{AMOUNT} on {DATETIME} at {MERCHANT}{CITY}\.\s+Available (?:credit\s+)?(?:limit|balance)(?:\s+is)?(?:\s+now)?\s*{ANYCUR}\s*{AVAIL}""",
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
                // A purchase transaction of USD265.00 has been performed on your Credit Card XXX3538 on 03/05/2026 10:11:12
                // at IIA STORE,NEW YORK-US. Available credit limit is now AED ...
                Rule(
                    id = "adcb-purchase-performed",
                    kind = RuleKind.TRANSACTION,
                    pattern = """purchase transaction of {CUR}\s*{AMOUNT} has been performed on your {CARDTYPE} Card {CARD} on {DATETIME} at {MERCHANT}{CITY}\.(?:\s+Available (?:credit\s+)?limit(?:\s+is)?(?:\s+now)?\s*{ANYCUR}\s*{AVAIL})?""",
                ),
                // AED308.34 debited from Acc/Cr.Card XXX3538 for DEWA on 21-08-2023 14:43:18
                Rule(
                    id = "adcb-bill-debit",
                    kind = RuleKind.TRANSACTION,
                    pattern = """{CUR}\s*{AMOUNT} debited from Acc/Cr\.?\s*Card {CARD} for {MERCHANT} on {DATETIME}""",
                ),
                // AED10900.00 transferred via ADCB Personal Internet Banking / Mobile App from acc. no. XXX810001 on May 25 2026 11:02AM. Avl. bal. AED ...
                Rule(
                    id = "adcb-account-transfer",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    fixedMerchant = "Transfer (ADCB online banking)",
                    pattern = """{CUR}\s*{AMOUNT} transferred via ADCB .*?from acc\.?\s*no\.?\s*{CARD} on {DATETIME}(?:\.?\s+Avl\.?\s*bal\.?\s*(?:is\s*)?{ANYCUR}\s*{AVAIL})?""",
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
                // Refund of 1 AED from CAREEM PLUS on 08-SEP-2026, 07:42:23 AM has been credited to your card ending with 3976.
                Rule(
                    id = "alhilal-refund",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.REFUND,
                    pattern = """Refund of {AMOUNT}\s*{CUR} from {MERCHANT} on {DATETIME} has been credited to your (?:credit )?card ending(?: with)? {CARD}""",
                ),
                // Payment of AED 1423.09 for credit card ending with (3976) is due on 25 August 2026. (the due amount; no minimum given)
                Rule(
                    id = "alhilal-statement-due",
                    kind = RuleKind.STATEMENT,
                    pattern = """Payment of {CUR}\s*{TOTAL} for credit card ending with \(?{CARD}\)? is due on {DUE}""",
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
                // Your Credit Card ending *** 5258 was used for AED 8.00 at DRAGON ICE CAFE. Your available limit is AED 1,234.00 (older format, no date)
                Rule(
                    id = "hsbc-purchase-old",
                    kind = RuleKind.TRANSACTION,
                    pattern = """Credit Card ending {CARD} was used for {CUR}\s*{AMOUNT} at {MERCHANT}\.\s+Your available limit is {ANYCUR}\s*{AVAIL}""",
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
                    pairGroup = "mashreq-transfer",
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
                    pairGroup = "mashreq-transfer",
                    pattern = """Amount of {CUR}\s*{AMOUNT} has been debited from your Mashreq account no\.?\s*{CARD} for {MERCHANT}\.\s+Login""",
                ),
                // Dear Customer, your Aani payment of AED 120.00 to AN** KAI*** is successful. (names the payee; merged with the
                // "AC No ... is debited" SMS for the same money)
                Rule(
                    id = "mashreq-aani-payment",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_OUT,
                    cardType = CardType.ACCOUNT,
                    accountNotNamed = true,
                    pairGroup = "mashreq-transfer",
                    describesDestination = true,
                    pattern = """Aani payment of {CUR}\s*{AMOUNT} to {MERCHANT} (?:is|was|has been) successful""",
                ),
                // An amount of AED 10000.00 has been credited to your Mashreq account no. XXXXXXXX7639 for Inward Transfer.
                Rule(
                    id = "mashreq-account-credit-2",
                    kind = RuleKind.TRANSACTION,
                    type = TxnType.TRANSFER_IN,
                    cardType = CardType.ACCOUNT,
                    pattern = """amount of {CUR}\s*{AMOUNT} has been credited to your Mashreq account no\.?\s*{CARD} for {MERCHANT}(?:\.\s|\.?$)""",
                ),
                // Mashreq Credit Card ending 4680 was used for a transaction of AED 47,830.00 at LAND DEPARTMENT on
                // Tuesday, 7 July 2026, 3:16 pm. Available limit: AED 1,234.00
                Rule(
                    id = "mashreq-card-purchase",
                    kind = RuleKind.TRANSACTION,
                    pattern = """{CARDTYPE} Card ending {CARD} was used for a transaction of {CUR}\s*{AMOUNT} at {MERCHANT} on {DATETIME}\.?(?:\s+Available (?:limit|balance):?\s*{ANYCUR}\s*{AVAIL})?""",
                ),
                // Your Card ending with 0933 was used for cash withdrawal of AED 2,500.00 at MASHREQ ATM on 02-MAY-2026 07:14 PM. Avl bal AED ...
                Rule(
                    id = "mashreq-atm",
                    kind = RuleKind.TRANSACTION,
                    cardType = CardType.DEBIT,
                    fixedMerchant = "ATM cash withdrawal",
                    pattern = """Card ending(?: with)? {CARD} was used for cash withdrawal of {CUR}\s*{AMOUNT}(?: at .+?)? on {DATETIME}""",
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
        ),

        // ------------------------------------------------ Other UAE banks
        // No bank-specific rules yet: the smart reader (SmartParser.kt) reads their SMS. When you have
        // real samples, add Rules here exactly like the banks above and put the samples in ParserTest.kt.
        // Sender IDs you find on your phone can also be added in the app (More → Bank senders).
        Bank("Dubai Islamic Bank", listOf("DIB", "DIBAlerts", "DubaiIslamic", "DIB-UAE"), emptyList()),
        Bank("Emirates Islamic", listOf("EIB", "EmiratesIslamic", "EIBank", "EI-Bank"), emptyList()),
        Bank("ADIB", listOf("ADIB", "ADIBAlert", "ADIB-UAE"), emptyList()),
        Bank("RAKBANK", listOf("RAKBANK", "RAKBankUAE", "RAK-BANK"), emptyList()),
        Bank("CBD", listOf("CBD", "CBDBank", "CBD-UAE", "CBDAlerts"), emptyList()),
        Bank("Citibank", listOf("Citibank", "CitiUAE", "CITI"), emptyList()),
        Bank("Standard Chartered", listOf("StanChart", "SCBUAE", "SC-UAE", "StandardChartered"), emptyList()),
        Bank("NBF", listOf("NBF", "NBFBank"), emptyList()),
        Bank("Ajman Bank", listOf("AjmanBank"), emptyList()),
        Bank("Dubai First", listOf("DubaiFirst", "DFirst"), emptyList()),
        Bank("Sharjah Islamic Bank", listOf("SIB", "SIBank", "SharjahIslamic"), emptyList()),
        Bank("Bank of Sharjah", listOf("BankOfSharjah", "BOS"), emptyList()),
        Bank("UAB", listOf("UAB", "UABBank"), emptyList()),
        Bank("Arab Bank", listOf("ArabBank"), emptyList()),
        Bank("CBI", listOf("CBI", "CBIBank"), emptyList()),
        Bank("Al Masraf", listOf("AlMasraf", "ARBIFT"), emptyList()),
        Bank("InvestBank", listOf("InvestBank"), emptyList()),
        Bank("Liv", listOf("Liv", "LivBank", "LIV-ENBD"), emptyList()),
        Bank("Wio", listOf("Wio", "WioBank", "WioPersonal"), emptyList()),
        Bank("Mashreq Neo", listOf("MashreqNeo"), emptyList()),
        Bank("Zand", listOf("Zand", "ZandBank"), emptyList()),
        Bank("Ruya", listOf("Ruya", "RuyaBank"), emptyList()),
        Bank("Emirates Post / Al Maryah", listOf("MBank", "AlMaryah"), emptyList()),
    )

    /**
     * Checked BEFORE any rule: a message with an OTP keyword AND something that looks like a code
     * ("123456 is your OTP", "OTP: 4821", "...password is 482913"). Dropped, never stored.
     * A plain "never share your OTP" footer on a purchase alert doesn't match (no code), so the
     * purchase is still parsed.
     */
    val otpPreCheck = IgnoreRule(
        "OTP",
        """^(?=.*\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode|PIN|auth(?:entication|ori[sz]ation)?\s+code)\b)(?=.*(\b\d{6,8}\b|\b(is|:)\s*\d{4,8}\b|\b\d{4,8}\s+is\s+(your|the)\b)).*""",
        store = false,
    )

    /**
     * Also checked BEFORE any rule: one-time codes that don't use the word "OTP", e.g. Wio's
     * "Use code 022765 to pay AED 80.74 at Noon with card 2093". Dropped, never stored.
     * Needs the code itself (4-8 digits) right after "use/enter code", after a named code type ("security code 1234"),
     * or a code followed by what it's for ("... to pay / to confirm"). So footers like "never share your security code",
     * "reference code 12345678", "merchant code 5411" and promo codes don't match.
     */
    val otpCodePreCheck = IgnoreRule(
        "OTP",
        """\b(?:use|enter|type|quote)\s+(?:the\s+)?(?:(?:security|secure|verification|confirmation|login|access|one[\s-]?time)\s+)?(?:code|pin)\s*(?:is|:|#|-)?\s*\d{4,8}\b""" +
            """|\b(?:use|enter|type)\s+\d{4,8}\b.{0,60}\bto\s+(?:pay|complete|confirm|verify|authori[sz]e|approve|log\s*in|sign\s*in|proceed|continue|activate|reset)\b""" +
            """|\b(?:security|secure|verification|confirmation|login|access|one[\s-]?time|3-?d\s*secure|3ds)\s+code\s*(?:is|:|-)?\s*\d{4,8}\b""" +
            """|\b\d{4,8}\s+is\s+(?:your|the)\b.{0,40}\bcode\b""" +
            """|\bcode\s*(?:is|:)?\s*\d{4,8}\b.{0,80}\bto\s+(?:pay|complete|confirm|verify|authori[sz]e|approve|log\s*in|sign\s*in|proceed|continue|activate)\b""",
        store = false,
    )

    /** Checked only when no transaction/statement rule matched. Applies to every bank. */
    val globalIgnore: List<IgnoreRule> = listOf(
        IgnoreRule("OTP", """\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|activation\s+code|passcode|PIN\s+is)\b""", store = false),
        IgnoreRule("Declined transaction", """\b(declined|unsuccessful|was not successful|could not be completed|has failed|have failed)\b"""),
        IgnoreRule("Approval request", """\btap to approve\b|\bSecurePass\b|\bapprove it\b"""),
        IgnoreRule("Payment reminder", """Payment for .{0,40}card ending \d{4} is due"""),
        IgnoreRule(
            "Card setting / service notice",
            """\bsetting for your Credit Card\b|\bdigital card\b|\bService Request\b|\bbeneficiary has been added\b|""" +
                """\bbeneficiary\b.{0,80}\bactivat|\bpush notification|\baccount has been opened\b|\bunder process\b|""" +
                """\bprovisional\b.{0,40}\blimit\b|\bLast Stmt\b|\binvoice\b|\blounge\b|\bsupplementary\b.{0,80}\blimit\b""",
        ),
        IgnoreRule("Instalment conversion / loan", """\bconverted\s+(?:in)?to\b.{0,60}\binstal+ments?\b|\bLoan on Card\b|\bmortgage\b.{0,120}\bdisburs"""),
        IgnoreRule("IPO subscription", """\bIPO\b"""),
        IgnoreRule("Standing instruction", """standing\s+instruction"""),
        IgnoreRule("Transfer request (not yet processed)", """\bRequest received for fund transfer\b"""),
        IgnoreRule(
            "Advert",
            """STOP\s*(?:to\s*)?\d{3,5}\b|\bT&C|\bT(?:n|&)?Cs?\s+apply\b|\bTnCs?\b|\bTCs\b|\bapply\s+(now|via|to|for|on)\b|\beligible\b|\bpre-?approved\b|\bConvert now\b|""" +
                """\bOpt[- ]?out\b|\bConditions apply\b|\bSMS\s+\w+\s+to\s+\d{4}\b|\bSpecial Offer\b|\beasy monthly instalments\b|""" +
                """\bValid until\b|\bOffer valid\b|\bLearn more\b|\bPay as low as\b|\binstal+ment plan\b|\bconvert your\b|""" +
                """offers\.emiratesnbd\.com|\bplease visit\b|\bVisit adcb|""" +
                """^(?:Enjoy|Earn|Get|Win|Shop|Spend|Dine|Grow|Gift|Travel|School|Hassle|Make|Your summer|Limited time|Tailored|Repay|Amazon\.ae|Branch Teller|Congrats)\b""",
        ),
        IgnoreRule("Limit change", """\blimit\b.*\b(has been|was)\s+(changed|updated|increased|decreased|set)\b|\blimit change\b"""),
    )

    /** Approximate rates, "1 unit = x AED", for every currency the app knows (see Currencies.kt). Edit freely in the app. */
    val fxToAed: Map<String, Decimal> get() = Currencies.rateToAed

    /** Internal pivot of the rate table. Amounts are shown in your home currency (SmsParser.homeCurrency). */
    const val BASE_CURRENCY = "AED"

    /** Other ways UAE banks write dirhams in SMS ("Dhs 120.00", "DH 45"). Treated as AED. */
    val aedAliases: List<String> = listOf("DHS", "DH", "DIRHAM", "DIRHAMS")
}
