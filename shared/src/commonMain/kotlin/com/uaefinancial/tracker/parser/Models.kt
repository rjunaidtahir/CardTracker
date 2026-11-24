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
    /** Pack rules (2.8, `ext.rules`): how far apart (minutes) two messages of one [pairGroup] may be. Built-in FAB pairs keep their own windows. */
    val pairWindowMinutes: Int = 180,
    /** 2.8 (`ext.rules`): the direction may be proven from the account's balance before and after (see DirectionFromBalance). Never guessed. */
    val directionFromBalance: Boolean = false,
    /** 2.8 (`ext.rules`): the message only counts when it merges with a matching transfer-out message ([twinWindowMinutes]); otherwise it waits in Needs review. */
    val needsTwin: Boolean = false,
    val twinWindowMinutes: Int = 1440,
    /** 2.8 (`ext.rules`): the rule itself states the direction ("IN" or "OUT"), so a message carrying both credit and debit words may be read. */
    val direction: String? = null,
    /** 2.8: a rule from `ext.rules` (compiled with the 2.8 tokens, checked by the post-read sanity check). */
    val fromExt: Boolean = false,
) {
    val namesDestination: Boolean get() = describesDestination ?: pattern.contains("{TO}")
}

/** [store] = false means the SMS is dropped entirely (not even the raw text is kept), e.g. OTPs. */
data class IgnoreRule(
    val label: String,
    val pattern: String,
    val store: Boolean = true,
    /** 2.8 (`ext.ignore`): only for this bank (lower case), matched once the sender or the wording has named the bank. */
    val bank: String? = null,
    /** 2.8 (`ext.ignore`): when this also matches, the ignore does NOT apply (e.g. a notice that yields to a purchase). */
    val unless: String? = null,
)

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
    /** 2.8: something in this reading was inferred (e.g. the direction proven from the balance): the row is marked Check this. */
    val inferred: String? = null,
    /** 2.8: a rule asked for a twin message ([Rule.needsTwin]) before this counts. */
    val needsTwin: Boolean = false,
    val twinWindowMinutes: Int = 1440,
    /** 2.8: the rule may prove the direction from the balance ([Rule.directionFromBalance]). */
    val directionFromBalance: Boolean = false,
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
    /**
     * [attributedBank]/[attributedBy] (2.8): the sender has no bank of its own and the WORDING named the bank
     * ([AttributedBy]); the transaction is stored under that bank and the report says so.
     */
    data class Transaction(val txn: ParsedTransaction, val ruleId: String, val attributedBank: String? = null, val attributedBy: String? = null) : ParseResult
    data class Statement(val statement: ParsedStatement, val ruleId: String, val attributedBank: String? = null, val attributedBy: String? = null) : ParseResult
    /** [store] = false: don't keep this SMS at all (OTP). [fromPack]: the ignore came from the signed rules file. */
    data class Ignored(val bank: String, val reason: String, val store: Boolean = true, val fromPack: Boolean = false) : ParseResult
    /** [stage] (2.8): why it could not be read ([Stage]); [fieldsRead]: letters of the fields that WERE found (A C D K M T B). */
    data class Failed(val bank: String, val reason: String, val stage: String? = null, val fieldsRead: String? = null) : ParseResult
    data object NotBank : ParseResult
}

/** How a sender with no bank of its own was attributed to a bank by its wording (2.8, section 2). */
object AttributedBy {
    const val WORDING_BANKS = "WORDING_BANKS"
    const val BUILTIN_WORDING = "BUILTIN_WORDING"
    const val PACK_WORDING = "PACK_WORDING"
}

/** Why a message is in Needs review (2.8 report key `st=`). */
object Stage {
    const val NO_BANK = "NO_BANK"
    const val AMBIGUOUS_BANKS = "AMBIGUOUS_BANKS"
    const val NO_AMOUNT = "NO_AMOUNT"
    const val NO_DIRECTION = "NO_DIRECTION"
    const val NO_CARD = "NO_CARD"
    const val NO_MERCHANT = "NO_MERCHANT"
    const val NO_DATE = "NO_DATE"
    const val HIDDEN_BY_IGNORE = "HIDDEN_BY_IGNORE"
    const val HELD = "HELD"
    const val MASK_UNSAFE = "MASK_UNSAFE"
    const val SANITY = "SANITY"
    const val NEEDS_TWIN = "NEEDS_TWIN"
    const val UNKNOWN = "UNKNOWN"
}
