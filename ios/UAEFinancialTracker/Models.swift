import Foundation
import SwiftData

/// Everything the app keeps. Amounts are in fils (minor units, 100 = 1 AED); -1 means "not known".

enum SmsStatus {
    static let transaction = "transaction"
    static let statement = "statement"
    static let ignored = "ignored"
    static let failed = "failed"
    static let dismissed = "dismissed"
}

enum TxnKind: String, CaseIterable, Identifiable {
    case purchase = "PURCHASE", refund = "REFUND", payment = "PAYMENT", transferOut = "TRANSFER_OUT", transferIn = "TRANSFER_IN"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .purchase: return "Spend"
        case .refund: return "Refund or cashback"
        case .payment: return "Card payment"
        case .transferOut: return "MoneyText sent"
        case .transferIn: return "MoneyText received"
        }
    }
}

enum CardKind: String, CaseIterable, Identifiable {
    case credit = "CREDIT", debit = "DEBIT", account = "ACCOUNT"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .credit: return "Credit card"
        case .debit: return "Debit card"
        case .account: return "Bank account"
        }
    }
}

/// A bank message as it arrived (from the Shortcut, a paste, a file or a screenshot).
@Model final class SmsRecord {
    @Attribute(.unique) var id: UUID
    var sender: String
    var body: String
    var bodyHash: String
    var receivedAt: Date
    /// False when the time is only the moment it was imported (pasted text, files without dates).
    var timeKnown: Bool
    var source: String
    var bank: String?
    var status: String
    var note: String?
    var ruleId: String?

    init(sender: String, body: String, bodyHash: String, receivedAt: Date, timeKnown: Bool, source: String, bank: String?) {
        id = UUID()
        self.sender = sender
        self.body = body
        self.bodyHash = bodyHash
        self.receivedAt = receivedAt
        self.timeKnown = timeKnown
        self.source = source
        self.bank = bank
        status = SmsStatus.failed
    }
}

@Model final class Txn {
    @Attribute(.unique) var id: UUID
    var smsId: UUID?
    /// "SMS", "Typed" or "Statement".
    var source: String
    var timestamp: Date
    var bank: String
    var cardLast4: String?
    var cardKey: String?
    var merchant: String
    var amountMinor: Int64
    var currency: String
    /// Amount in fils of AED, or -1 when there's no exchange rate for the currency.
    var aedMinor: Int64
    var fxEstimated: Bool
    var type: String
    var availableMinor: Int64?
    var ruleId: String?
    /// Your credit card that a payment went to.
    var counterpartyKey: String?
    var merchantKey: String
    var categoryId: Int64?
    var categoryUserSet: Bool

    init(source: String, timestamp: Date, bank: String, merchant: String, amountMinor: Int64, currency: String, aedMinor: Int64, type: String) {
        id = UUID()
        self.source = source
        self.timestamp = timestamp
        self.bank = bank
        self.merchant = merchant
        self.amountMinor = amountMinor
        self.currency = currency
        self.aedMinor = aedMinor
        fxEstimated = false
        self.type = type
        merchantKey = ""
        categoryUserSet = false
    }

    var txnType: TxnKind { TxnKind(rawValue: type) ?? .purchase }
}

@Model final class Card {
    @Attribute(.unique) var key: String
    var bank: String
    var last4: String?
    var cardType: String
    /// Show & count: whether spends on it count as spending.
    var counted: Bool
    var nickname: String?
    /// A card you pay for someone else: its spends go to the Family category.
    var family: Bool
    var creditLimitMinor: Int64?
    var order: Int

    init(key: String, bank: String, last4: String?, cardType: String, counted: Bool) {
        self.key = key
        self.bank = bank
        self.last4 = last4
        self.cardType = cardType
        self.counted = counted
        family = false
        order = 0
    }

    var kind: CardKind { CardKind(rawValue: cardType) ?? .credit }

    /// "ENBD credit card ·9940", or your nickname for it.
    var label: String {
        if let n = nickname, !n.trimmingCharacters(in: .whitespaces).isEmpty { return n }
        let what: String
        switch kind {
        case .account: what = "account"
        case .debit: what = "debit card"
        case .credit: what = "credit card"
        }
        return "\(bank) \(what) ·\(last4 ?? "????")"
    }
}

/// A card statement: from a statement SMS or a statement PDF.
@Model final class StatementRecord {
    @Attribute(.unique) var id: UUID
    var smsId: UUID?
    var cardKey: String
    var bank: String
    var cardLast4: String?
    var receivedAt: Date
    var balanceMinor: Int64
    var minimumDueMinor: Int64?
    var currency: String
    var dueEpochDay: Int64
    var statementEpochDay: Int64?
    var fromPdf: Bool

    init(cardKey: String, bank: String, cardLast4: String?, receivedAt: Date, balanceMinor: Int64, currency: String, dueEpochDay: Int64) {
        id = UUID()
        self.cardKey = cardKey
        self.bank = bank
        self.cardLast4 = cardLast4
        self.receivedAt = receivedAt
        self.balanceMinor = balanceMinor
        self.currency = currency
        self.dueEpochDay = dueEpochDay
        fromPdf = false
    }
}

/// A sender you added as a bank (e.g. a bank the app doesn't know yet).
@Model final class BankSender {
    @Attribute(.unique) var sender: String
    var bank: String
    init(sender: String, bank: String) {
        self.sender = sender
        self.bank = bank
    }
}

/// A category learned from your changes: merchant key (or text + amount) → category.
@Model final class MerchantRule {
    @Attribute(.unique) var key: String
    var categoryId: Int64
    init(key: String, categoryId: Int64) {
        self.key = key
        self.categoryId = categoryId
    }
}

/// A category you chose for one message's transaction, kept across re-reads.
@Model final class CategoryOverride {
    @Attribute(.unique) var smsId: UUID
    var categoryId: Int64
    init(smsId: UUID, categoryId: Int64) {
        self.smsId = smsId
        self.categoryId = categoryId
    }
}

/// Your own reading of a message (Needs review → Fix). type "IGNORE" means "not a transaction".
@Model final class SmsFix {
    @Attribute(.unique) var smsId: UUID
    var type: String
    var amountMinor: Int64
    var currency: String
    var merchant: String
    var cardLast4: String?
    var cardType: String
    var timestamp: Date

    init(smsId: UUID, type: String, amountMinor: Int64, currency: String, merchant: String, cardLast4: String?, cardType: String, timestamp: Date) {
        self.smsId = smsId
        self.type = type
        self.amountMinor = amountMinor
        self.currency = currency
        self.merchant = merchant
        self.cardLast4 = cardLast4
        self.cardType = cardType
        self.timestamp = timestamp
    }

    static let ignore = "IGNORE"
}

enum AppModels {
    static let all: [any PersistentModel.Type] = [
        SmsRecord.self, Txn.self, Card.self, StatementRecord.self, BankSender.self, MerchantRule.self, CategoryOverride.self, SmsFix.self,
    ]
}
