package com.junaid.cardtracker.parser

import java.math.BigDecimal
import java.time.LocalDate

enum class TxnType { PURCHASE, REFUND, PAYMENT }

enum class RuleKind { TRANSACTION, STATEMENT }

enum class CardType { CREDIT, DEBIT }

/** One regex template. See BankRules.kt for the placeholder tokens you can use. */
data class Rule(
    val id: String,
    val kind: RuleKind,
    val pattern: String,
    val type: TxnType = TxnType.PURCHASE,
    /** Used when the SMS has no merchant (payments, cashback). */
    val fixedMerchant: String? = null,
    /** Card type when the pattern has no {CARDTYPE} token. */
    val cardType: CardType = CardType.CREDIT,
)

/** [store] = false means the SMS is dropped entirely (not even the raw text is kept), e.g. OTPs. */
data class IgnoreRule(val label: String, val pattern: String, val store: Boolean = true)

data class Bank(
    val name: String,
    /** Sender IDs as shown in Messages. Matching ignores case, spaces and dashes, and allows prefixes like "AD-". */
    val senderIds: List<String>,
    val rules: List<Rule>,
    val ignore: List<IgnoreRule> = emptyList(),
    /** Also try the generic rules (for banks without verified samples yet). */
    val useGenericRules: Boolean = false,
)

data class ParsedTransaction(
    val bank: String,
    val cardLast4: String?,
    val cardType: CardType,
    val merchant: String,
    val amount: BigDecimal,
    val currency: String,
    val type: TxnType,
    /** Epoch millis. From the SMS text when present, otherwise the time the SMS arrived. */
    val timestamp: Long,
    val dateFromSms: Boolean,
    val availableLimit: BigDecimal? = null,
)

data class ParsedStatement(
    val bank: String,
    val cardLast4: String?,
    val cardType: CardType,
    val statementBalance: BigDecimal,
    val minimumDue: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate,
    val statementDate: LocalDate?,
)

sealed interface ParseResult {
    data class Transaction(val txn: ParsedTransaction, val ruleId: String) : ParseResult
    data class Statement(val statement: ParsedStatement, val ruleId: String) : ParseResult
    /** [store] = false: don't keep this SMS at all (OTP). */
    data class Ignored(val bank: String, val reason: String, val store: Boolean = true) : ParseResult
    data class Failed(val bank: String, val reason: String) : ParseResult
    data object NotBank : ParseResult
}
