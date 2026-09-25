package com.uaefinancial.tracker.parser
import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.Decimal


/**
 * PURCHASE / REFUND (incl. cashback) / PAYMENT (a card receiving a payment).
 * TRANSFER_OUT / TRANSFER_IN: money leaving / arriving in a bank account (never spending).
 */
enum class TxnType { PURCHASE, REFUND, PAYMENT, TRANSFER_OUT, TRANSFER_IN }

enum class RuleKind { TRANSACTION, STATEMENT }

/** ACCOUNT = a bank account (e.g. FAB current account) tracked like a debit card. */
enum class CardType { CREDIT, DEBIT, ACCOUNT }

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
    /**
     * The SMS doesn't say which account paid (e.g. some FAB bill payments). The app then uses your only
     * bank account at this bank, if you have exactly one.
     */
    val accountNotNamed: Boolean = false,
    /**
     * Rules whose SMS describe the SAME money movement from two angles (FAB sends both
     * "Outward Remittance Debit" and "funds transfer ... processed"). Transactions from
     * different rules in the same group, same account, same amount, within 3 hours are merged.
     */
    val pairGroup: String? = null,
    /**
     * Whether this SMS names where the money went. Within a pairGroup, only a message that names the
     * destination pairs with one that doesn't. Defaults to "the pattern has a {TO} token".
     */
    val describesDestination: Boolean? = null,
) {
    val namesDestination: Boolean get() = describesDestination ?: pattern.contains("{TO}")
}

/** [store] = false means the SMS is dropped entirely (not even the raw text is kept), e.g. OTPs. */
data class IgnoreRule(val label: String, val pattern: String, val store: Boolean = true)

data class Bank(
    val name: String,
    /** Sender IDs as shown in Messages. Matching ignores case, spaces and dashes, and allows prefixes like "AD-". */
    val senderIds: List<String>,
    val rules: List<Rule>,
    val ignore: List<IgnoreRule> = emptyList(),
)

data class ParsedTransaction(
    val bank: String,
    val cardLast4: String?,
    val cardType: CardType,
    val merchant: String,
    val amount: Decimal,
    val currency: String,
    val type: TxnType,
    /** Epoch millis. From the SMS text when present, otherwise the time the SMS arrived. */
    val timestamp: Long,
    val dateFromSms: Boolean,
    val availableLimit: Decimal? = null,
    /** Last 4 of the destination account/card for transfers and card payments. */
    val toLast4: String? = null,
    /** True when the SMS names no account; the app picks your only account at this bank. */
    val accountNotNamed: Boolean = false,
    /** Read by the smart reader (no bank-specific rule): shown so you can check it. */
    val auto: Boolean = false,
)

data class ParsedStatement(
    val bank: String,
    val cardLast4: String?,
    val cardType: CardType,
    val statementBalance: Decimal,
    val minimumDue: Decimal?,
    val currency: String,
    val dueDate: CalendarDate,
    val statementDate: CalendarDate?,
    val auto: Boolean = false,
)

sealed interface ParseResult {
    data class Transaction(val txn: ParsedTransaction, val ruleId: String) : ParseResult
    data class Statement(val statement: ParsedStatement, val ruleId: String) : ParseResult
    /** [store] = false: don't keep this SMS at all (OTP). */
    data class Ignored(val bank: String, val reason: String, val store: Boolean = true) : ParseResult
    data class Failed(val bank: String, val reason: String) : ParseResult
    data object NotBank : ParseResult
}
