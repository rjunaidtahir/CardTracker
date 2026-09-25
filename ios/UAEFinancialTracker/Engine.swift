import Foundation
import SwiftData
import Shared

/// Rule id of transactions you read yourself (Needs review → Fix).
let fixedByYouRule = "fixed-by-you"

/// Turns bank messages into transactions and statements (the same steps as the Android app's Repository), on top of
/// the shared engine (`Bridge`) that reads the messages.
@MainActor
final class Engine {
    let context: ModelContext
    private let bridge = Bridge.shared

    init(context: ModelContext) {
        self.context = context
        refreshSenders()
    }

    enum Outcome: String {
        case transaction, statement, ignored, failed, duplicate, otp, notBank
    }

    struct Tally {
        var transactions = 0, statements = 0, review = 0, duplicates = 0, skipped = 0
        mutating func add(_ o: Outcome) {
            switch o {
            case .transaction: transactions += 1
            case .statement: statements += 1
            case .failed: review += 1
            case .duplicate: duplicates += 1
            case .ignored, .otp, .notBank: skipped += 1
            }
        }
        var summary: String {
            var parts: [String] = []
            if transactions > 0 { parts.append("\(transactions) transaction\(transactions == 1 ? "" : "s")") }
            if statements > 0 { parts.append("\(statements) statement\(statements == 1 ? "" : "s")") }
            if review > 0 { parts.append("\(review) to review") }
            if duplicates > 0 { parts.append("\(duplicates) already in the app") }
            if skipped > 0 { parts.append("\(skipped) not needed (OTPs, adverts, other messages)") }
            return parts.isEmpty ? "Nothing found" : parts.joined(separator: ", ")
        }
    }

    // MARK: - Settings

    var rates: [String: String] { Settings.rates }

    /// Tells the engine about the senders you added as banks.
    func refreshSenders() {
        let senders = (try? context.fetch(FetchDescriptor<BankSender>())) ?? []
        var map: [String: String] = [:]
        for s in senders { map[s.sender] = s.bank }
        bridge.setCustomSenders(senderToBank: map)
    }

    // MARK: - Adding messages

    /// Adds one message.
    /// - Parameters:
    ///   - sender: who sent it, when known (the Shortcut passes it; pasted text has none).
    ///   - timeKnown: false when [receivedAt] is only the moment of import.
    ///   - requireBankLike: for messages from a sender that isn't a known bank: keep them only when they read like a bank alert.
    @discardableResult
    func ingest(body raw: String, sender: String?, receivedAt: Date, timeKnown: Bool, source: String, requireBankLike: Bool = false) -> Outcome {
        let body = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return .notBank }
        let millis = Self.millis(receivedAt)
        let cleanSender = sender?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let reading: SmsReading
        if !cleanSender.isEmpty, bridge.bankFor(sender: cleanSender) != nil {
            reading = bridge.readSms(sender: cleanSender, body: body, receivedAtMillis: millis, rates: rates)
        } else {
            reading = bridge.readSmsAnySender(body: body, receivedAtMillis: millis, rates: rates)
            let read = reading.kind == "transaction" || reading.kind == "statement"
            if reading.kind == "otp" { return .otp }
            if reading.kind == "notBank" { return .notBank }
            if (!read || requireBankLike) && !bridge.looksLikeBankAlert(body: body) { return .notBank }
        }
        if reading.kind == "otp" { return .otp }
        if reading.kind == "notBank" { return .notBank }

        let hash = bridge.bodyHash(body: body)
        if isDuplicate(hash: hash, receivedAt: receivedAt, timeKnown: timeKnown) { return .duplicate }
        let sms = SmsRecord(
            sender: cleanSender.isEmpty ? (reading.bank ?? "Pasted") : cleanSender, body: body, bodyHash: hash,
            receivedAt: receivedAt, timeKnown: timeKnown, source: source, bank: reading.bank
        )
        context.insert(sms)
        let outcome = apply(sms, reading)
        save()
        return outcome
    }

    /// Adds many messages (a paste, a file, a backup), oldest first so transfers and statements line up.
    func ingestAll(_ items: [(body: String, sender: String?, date: Date?)], source: String) -> Tally {
        var tally = Tally()
        let now = Date()
        for item in items.sorted(by: { ($0.date ?? now) < ($1.date ?? now) }) {
            tally.add(ingest(body: item.body, sender: item.sender, receivedAt: item.date ?? now, timeKnown: item.date != nil, source: source))
        }
        return tally
    }

    /// The same message twice: same text, and either close in time or one of them without a known time.
    private func isDuplicate(hash: String, receivedAt: Date, timeKnown: Bool) -> Bool {
        let d = FetchDescriptor<SmsRecord>(predicate: #Predicate { $0.bodyHash == hash })
        let same = (try? context.fetch(d)) ?? []
        return same.contains { !timeKnown || !$0.timeKnown || abs($0.receivedAt.timeIntervalSince(receivedAt)) < 10 * 60 }
    }

    /// Reads every stored message again with the current rules (after an update, or after adding a bank sender).
    func rereadAll() -> Tally {
        refreshSenders()
        let all = ((try? context.fetch(FetchDescriptor<SmsRecord>(sortBy: [SortDescriptor(\.receivedAt)]))) ?? [])
        var tally = Tally()
        for sms in all where sms.status != SmsStatus.dismissed {
            tally.add(apply(sms, read(sms)))
        }
        save()
        return tally
    }

    private func read(_ sms: SmsRecord) -> SmsReading {
        let millis = Self.millis(sms.receivedAt)
        if bridge.bankFor(sender: sms.sender) != nil {
            return bridge.readSms(sender: sms.sender, body: sms.body, receivedAtMillis: millis, rates: rates)
        }
        return bridge.readSmsAnySender(body: sms.body, receivedAtMillis: millis, rates: rates)
    }

    // MARK: - Applying a reading

    private func deleteDerived(_ smsId: UUID) {
        let target: UUID? = smsId
        let txns = (try? context.fetch(FetchDescriptor<Txn>(predicate: #Predicate { $0.smsId == target }))) ?? []
        for t in txns { context.delete(t) }
        let sts = (try? context.fetch(FetchDescriptor<StatementRecord>(predicate: #Predicate { $0.smsId == target }))) ?? []
        for s in sts { context.delete(s) }
    }

    @discardableResult
    private func apply(_ sms: SmsRecord, _ r: SmsReading) -> Outcome {
        deleteDerived(sms.id)
        let smsId = sms.id
        if let fix = (try? context.fetch(FetchDescriptor<SmsFix>(predicate: #Predicate { $0.smsId == smsId })))?.first {
            return applyFix(sms, fix)
        }
        switch r.kind {
        case "transaction":
            return applyTransaction(sms, TxnInput(r, fallbackTime: sms.receivedAt))
        case "statement":
            let bank = r.bank ?? sms.bank ?? Bridge.shared.UNKNOWN_BANK
            let key = bridge.cardKey(bank: bank, last4: r.cardLast4)
            ensureCard(key: key, bank: bank, last4: r.cardLast4, type: r.cardType ?? CardKind.credit.rawValue)
            let st = StatementRecord(
                cardKey: key, bank: bank, cardLast4: r.cardLast4, receivedAt: sms.receivedAt, balanceMinor: r.balanceMinor,
                currency: r.currency ?? "AED", dueEpochDay: r.dueEpochDay
            )
            st.smsId = sms.id
            st.minimumDueMinor = r.hasMinimumDue ? r.minimumDueMinor : nil
            st.statementEpochDay = r.hasStatementDate ? r.statementEpochDay : nil
            context.insert(st)
            setResult(sms, SmsStatus.statement, bank: bank, ruleId: r.ruleId, note: nil)
            return .statement
        case "ignored":
            setResult(sms, SmsStatus.ignored, bank: r.bank ?? sms.bank, ruleId: nil, note: r.reason)
            return .ignored
        case "otp":
            context.delete(sms)
            return .otp
        case "notBank":
            setResult(sms, SmsStatus.ignored, bank: sms.bank, ruleId: nil, note: "Not a bank message")
            return .notBank
        default:
            setResult(sms, SmsStatus.failed, bank: r.bank ?? sms.bank, ruleId: nil, note: r.reason ?? "The app couldn't read this message")
            return .failed
        }
    }

    private func setResult(_ sms: SmsRecord, _ status: String, bank: String?, ruleId: String?, note: String?) {
        sms.status = status
        sms.bank = bank
        sms.ruleId = ruleId
        sms.note = note
    }

    struct TxnInput {
        var bank: String
        var cardLast4: String?
        var cardType: String
        var merchant: String
        var amountMinor: Int64
        var currency: String
        var aedMinor: Int64
        var fxEstimated: Bool
        var timestamp: Date
        var availableMinor: Int64?
        var toLast4: String?
        var accountNotNamed: Bool
        var type: String
        var ruleId: String?

        init(_ r: SmsReading, fallbackTime: Date) {
            bank = r.bank ?? Bridge.shared.UNKNOWN_BANK
            cardLast4 = r.cardLast4
            cardType = r.cardType ?? CardKind.credit.rawValue
            merchant = r.merchant ?? "Transaction"
            amountMinor = r.amountMinor
            currency = r.currency ?? "AED"
            aedMinor = r.aedMinor
            fxEstimated = r.fxEstimated
            timestamp = r.timestampMillis > 0 ? Date(timeIntervalSince1970: Double(r.timestampMillis) / 1000) : fallbackTime
            availableMinor = r.hasAvailable ? r.availableMinor : nil
            toLast4 = r.toLast4
            accountNotNamed = r.accountNotNamed
            type = r.type ?? TxnKind.purchase.rawValue
            ruleId = r.ruleId
        }

        init(fix: SmsFix, bank: String, rates: [String: String]) {
            self.bank = bank
            cardLast4 = fix.cardLast4
            cardType = fix.cardType
            merchant = fix.merchant
            amountMinor = fix.amountMinor
            currency = fix.currency
            aedMinor = Bridge.shared.toAedMinor(amountMinor: fix.amountMinor, currency: fix.currency, rates: rates)
            fxEstimated = fix.currency.uppercased() != "AED"
            timestamp = fix.timestamp
            availableMinor = nil
            toLast4 = nil
            accountNotNamed = false
            type = fix.type
            ruleId = fixedByYouRule
        }
    }

    private func applyFix(_ sms: SmsRecord, _ fix: SmsFix) -> Outcome {
        if fix.type == SmsFix.ignore {
            setResult(sms, SmsStatus.ignored, bank: sms.bank, ruleId: nil, note: "You marked this as not a transaction")
            return .ignored
        }
        return applyTransaction(sms, TxnInput(fix: fix, bank: sms.bank ?? Bridge.shared.UNKNOWN_BANK, rates: rates))
    }

    private func applyTransaction(_ sms: SmsRecord, _ input: TxnInput) -> Outcome {
        var t = input
        // A reference number read as an amount: never let one message break an import.
        if t.amountMinor >= 10_000_000_000_000 || t.amountMinor <= 0 {
            setResult(sms, SmsStatus.failed, bank: t.bank, ruleId: t.ruleId, note: "The amount looked wrong")
            return .failed
        }
        let cards = fetchAll(Card.self)
        // The message doesn't name the account: use your only account at that bank.
        if t.cardLast4 == nil && t.accountNotNamed {
            let accounts = cards.filter { $0.bank == t.bank && $0.cardType == CardKind.account.rawValue }
            if accounts.count == 1, let only = accounts.first {
                t.cardLast4 = only.last4
                t.cardType = CardKind.account.rawValue
            }
        }
        let key: String?
        if let l4 = t.cardLast4 {
            key = bridge.cardKey(bank: t.bank, last4: l4)
        } else if t.accountNotNamed || t.cardType == CardKind.account.rawValue {
            key = bridge.cardKey(bank: t.bank, last4: nil)
        } else {
            key = nil
        }
        if let key { ensureCard(key: key, bank: t.bank, last4: t.cardLast4, type: t.cardType) }
        let card = key.flatMap { k in fetchAll(Card.self).first { $0.key == k } }

        // A transfer to one of your own cards is a payment to that card (never spending): name it after the card.
        var counterparty: String?
        if let to = t.toLast4, let dest = destination(last4: to, fromBank: t.bank) {
            if dest.kind == .credit && !dest.family && dest.key != key { counterparty = dest.key }
            if t.merchant == bridge.transferLabel(last4: to) {
                t.merchant = (counterparty != nil ? "Payment to " : "Transfer to ") + dest.label
            }
        }
        let merchantKey = bridge.merchantKey(merchant: t.merchant)
        let smsId = sms.id
        let override = (try? context.fetch(FetchDescriptor<CategoryOverride>(predicate: #Predicate { $0.smsId == smsId })))?.first
        let family = (card?.family ?? false) && t.type == TxnKind.purchase.rawValue && override == nil

        let txn = Txn(
            source: "SMS", timestamp: t.timestamp, bank: t.bank, merchant: t.merchant, amountMinor: t.amountMinor,
            currency: t.currency, aedMinor: t.aedMinor, type: t.type
        )
        txn.smsId = sms.id
        txn.cardLast4 = t.cardLast4
        txn.cardKey = key
        txn.fxEstimated = t.fxEstimated
        txn.availableMinor = t.availableMinor
        txn.ruleId = t.ruleId
        txn.counterpartyKey = counterparty
        txn.merchantKey = merchantKey
        if let override {
            txn.categoryId = override.categoryId
            txn.categoryUserSet = true
        } else if family {
            txn.categoryId = bridge.familyCategoryId()
        } else {
            txn.categoryId = category(merchant: t.merchant, merchantKey: merchantKey, type: t.type, amountMinor: t.amountMinor)
        }
        context.insert(txn)
        let note = t.aedMinor < 0 ? "No AED rate for \(t.currency): add one in More → Exchange rates" : nil
        setResult(sms, SmsStatus.transaction, bank: t.bank, ruleId: t.ruleId, note: note)
        return .transaction
    }

    /// One of your cards that a transfer went to, only when the match is unambiguous.
    private func destination(last4: String, fromBank: String) -> Card? {
        let matches = fetchAll(Card.self).filter { $0.last4 == last4 }
        if matches.count == 1 { return matches[0] }
        let credit = matches.filter { $0.kind == .credit && !$0.family }
        if credit.count == 1 { return credit[0] }
        let sameBank = matches.filter { $0.bank == fromBank }
        return sameBank.count == 1 ? sameBank[0] : nil
    }

    private func ensureCard(key: String, bank: String, last4: String?, type: String) {
        if fetchAll(Card.self).contains(where: { $0.key == key }) { return }
        let c = Card(key: key, bank: bank, last4: last4, cardType: type, counted: bridge.countsByDefault(cardType: type))
        c.order = fetchAll(Card.self).count
        context.insert(c)
    }

    /// Category for a new transaction: what you taught the app, then its keyword guess.
    func category(merchant: String, merchantKey: String, type: String, amountMinor: Int64) -> Int64? {
        guard bridge.canHaveCategory(type: type) else { return nil }
        let rules = fetchAll(MerchantRule.self)
        let learning = bridge.learningKey(merchant: merchant, amountMinor: amountMinor, type: type)
        if let r = rules.first(where: { $0.key == learning }) { return r.categoryId }
        if type == TxnKind.transferOut.rawValue { return nil }
        if type == TxnKind.transferIn.rawValue {
            return merchant.range(of: "SALARY", options: .caseInsensitive) != nil ? bridge.incomeCategoryId() : nil
        }
        if !merchantKey.isEmpty, let r = rules.first(where: { $0.key == merchantKey }) { return r.categoryId }
        let g = bridge.guessCategory(merchant: merchant, type: type)
        return g < 0 ? nil : g
    }

    // MARK: - Your changes

    /// Sets a transaction's category; with [applyToMerchant] the app also learns it for this merchant, past and future.
    func setCategory(_ t: Txn, categoryId: Int64, applyToMerchant: Bool) {
        t.categoryId = categoryId
        t.categoryUserSet = true
        if let smsId = t.smsId {
            if let o = (try? context.fetch(FetchDescriptor<CategoryOverride>(predicate: #Predicate { $0.smsId == smsId })))?.first {
                o.categoryId = categoryId
            } else {
                context.insert(CategoryOverride(smsId: smsId, categoryId: categoryId))
            }
        }
        if applyToMerchant {
            let learning = bridge.learningKey(merchant: t.merchant, amountMinor: t.amountMinor, type: t.type)
            upsertRule(key: learning, categoryId: categoryId)
            for other in fetchAll(Txn.self) where !other.categoryUserSet && other.id != t.id {
                let k = bridge.learningKey(merchant: other.merchant, amountMinor: other.amountMinor, type: other.type)
                if k == learning { other.categoryId = categoryId }
            }
        }
        save()
    }

    private func upsertRule(key: String, categoryId: Int64) {
        guard !key.isEmpty else { return }
        if let r = fetchAll(MerchantRule.self).first(where: { $0.key == key }) {
            r.categoryId = categoryId
        } else {
            context.insert(MerchantRule(key: key, categoryId: categoryId))
        }
    }

    /// Saves your reading of a message (Needs review → Fix) and applies it. [type] nil means "not a transaction".
    func saveFix(_ sms: SmsRecord, type: TxnKind?, amountMinor: Int64, currency: String, merchant: String, cardLast4: String?, cardType: CardKind, date: Date) {
        let smsId = sms.id
        for old in (try? context.fetch(FetchDescriptor<SmsFix>(predicate: #Predicate { $0.smsId == smsId }))) ?? [] { context.delete(old) }
        let last4 = cardLast4?.trimmingCharacters(in: .whitespaces)
        let m = merchant.trimmingCharacters(in: .whitespaces)
        let fix = SmsFix(
            smsId: sms.id, type: type?.rawValue ?? SmsFix.ignore, amountMinor: amountMinor, currency: currency.uppercased(),
            merchant: m.isEmpty ? "Transaction" : m, cardLast4: (last4?.isEmpty ?? true) ? nil : last4, cardType: cardType.rawValue, timestamp: date
        )
        context.insert(fix)
        apply(sms, read(sms))
        save()
    }

    /// What the smart reader makes of a message it couldn't read for sure (pre-fills the Fix form).
    func guess(_ sms: SmsRecord) -> SmsReading? {
        bridge.guessTransaction(bank: sms.bank ?? sms.sender, body: sms.body, receivedAtMillis: Self.millis(sms.receivedAt))
    }

    func dismiss(_ sms: SmsRecord) {
        sms.status = SmsStatus.dismissed
        save()
    }

    /// Deleting a message's transaction also dismisses the message, so a re-read doesn't bring it back.
    func delete(_ t: Txn) {
        if let smsId = t.smsId, let sms = fetchAll(SmsRecord.self).first(where: { $0.id == smsId }) {
            sms.status = SmsStatus.dismissed
        }
        context.delete(t)
        save()
    }

    /// A typed entry: "lunch 45", "usd 20 netflix #1234", "refund amazon 50". Returns false when there's no amount.
    func addTyped(_ text: String, date: Date = Date()) -> Bool {
        guard let e = bridge.typedEntry(text: text) else { return false }
        let card = e.cardLast4.flatMap { l4 -> Card? in
            let m = fetchAll(Card.self).filter { $0.last4 == l4 }
            return m.count == 1 ? m[0] : nil
        }
        let aed = bridge.toAedMinor(amountMinor: e.amountMinor, currency: e.currency, rates: rates)
        let t = Txn(source: "Typed", timestamp: date, bank: card?.bank ?? "Cash", merchant: e.details, amountMinor: e.amountMinor, currency: e.currency, aedMinor: aed, type: e.type)
        t.cardKey = card?.key
        t.cardLast4 = card?.last4 ?? e.cardLast4
        t.fxEstimated = e.currency.uppercased() != "AED"
        t.merchantKey = bridge.merchantKey(merchant: e.details)
        t.categoryId = category(merchant: e.details, merchantKey: t.merchantKey, type: e.type, amountMinor: e.amountMinor)
        context.insert(t)
        save()
        return true
    }

    /// Adds a card or account by hand. Returns its key.
    @discardableResult
    func addCard(bank: String, last4: String?, kind: CardKind, nickname: String?, family: Bool) -> String {
        let b = bank.trimmingCharacters(in: .whitespaces)
        let l4 = last4?.trimmingCharacters(in: .whitespaces)
        let key = bridge.cardKey(bank: b, last4: (l4?.isEmpty ?? true) ? nil : l4)
        ensureCard(key: key, bank: b, last4: (l4?.isEmpty ?? true) ? nil : l4, type: kind.rawValue)
        if let c = fetchAll(Card.self).first(where: { $0.key == key }) {
            c.family = family
            if let n = nickname?.trimmingCharacters(in: .whitespaces), !n.isEmpty { c.nickname = n }
        }
        save()
        return key
    }

    func addSender(_ sender: String, bank: String) {
        let s = sender.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty else { return }
        if let existing = fetchAll(BankSender.self).first(where: { $0.sender.caseInsensitiveCompare(s) == .orderedSame }) {
            existing.bank = bank
        } else {
            context.insert(BankSender(sender: s, bank: bank))
        }
        save()
        refreshSenders()
    }

    func removeSender(_ s: BankSender) {
        context.delete(s)
        save()
        refreshSenders()
    }

    /// Saves a rate and recalculates every transaction in that currency.
    func setRate(currency: String, rate: String) {
        var r = Settings.rates
        r[currency.uppercased()] = rate
        Settings.rates = r
        for t in fetchAll(Txn.self) where t.currency.caseInsensitiveCompare(currency) == .orderedSame {
            t.aedMinor = bridge.toAedMinor(amountMinor: t.amountMinor, currency: t.currency, rates: r)
        }
        save()
    }

    // MARK: - Statements from PDF

    /// Saves what a statement PDF says about a card, and adds the statement's rows that aren't in the app yet.
    func applyStatement(_ s: StatementReading, cardKey: String, addRows rows: [StatementRow]) -> [String] {
        guard let card = fetchAll(Card.self).first(where: { $0.key == cardKey }) else { return [] }
        var done: [String] = []
        if !s.isAccount {
            if s.creditLimitMinor > 0 {
                card.creditLimitMinor = s.creditLimitMinor
                done.append("credit limit")
            }
            if s.dueEpochDay >= 0 && s.totalDueMinor >= 0 {
                let exists = fetchAll(StatementRecord.self).contains { $0.cardKey == cardKey && $0.dueEpochDay == s.dueEpochDay }
                if !exists {
                    let st = StatementRecord(
                        cardKey: cardKey, bank: card.bank, cardLast4: card.last4, receivedAt: Date(), balanceMinor: s.totalDueMinor,
                        currency: "AED", dueEpochDay: s.dueEpochDay
                    )
                    st.minimumDueMinor = s.minimumDueMinor >= 0 ? s.minimumDueMinor : nil
                    st.statementEpochDay = s.statementEpochDay >= 0 ? s.statementEpochDay : nil
                    st.fromPdf = true
                    context.insert(st)
                    done.append("the amount due")
                }
            }
        }
        var added = 0
        for row in rows {
            let type: TxnKind
            if !row.isCredit { type = .purchase }
            else if card.kind == .account { type = .transferIn }
            else if row.details.range(of: "PAYMENT|THANK YOU", options: [.regularExpression, .caseInsensitive]) != nil { type = .payment }
            else { type = .refund }
            let date = Calendar.current.date(byAdding: .hour, value: 12, to: Dates.date(epochDay: row.epochDay)) ?? Date()
            let t = Txn(source: "Statement", timestamp: date, bank: card.bank, merchant: row.details, amountMinor: row.amountMinor, currency: "AED", aedMinor: row.amountMinor, type: type.rawValue)
            t.cardKey = card.key
            t.cardLast4 = card.last4
            t.merchantKey = bridge.merchantKey(merchant: row.details)
            if card.family && type == .purchase {
                t.categoryId = bridge.familyCategoryId()
                t.categoryUserSet = true
            } else {
                t.categoryId = category(merchant: row.details, merchantKey: t.merchantKey, type: type.rawValue, amountMinor: row.amountMinor)
            }
            context.insert(t)
            added += 1
        }
        if added > 0 { done.append("\(added) missing transaction\(added == 1 ? "" : "s")") }
        save()
        return done
    }

    /// The rows of a statement that aren't in the app yet, for one card.
    func missingRows(_ s: StatementReading, cardKey: String) -> [StatementRow] {
        let app = fetchAll(Txn.self).filter { $0.cardKey == cardKey }.map { t in
            AppTxn(
                epochDay: Dates.epochDay(t.timestamp), amountMinor: t.aedMinor >= 0 ? t.aedMinor : t.amountMinor,
                isCredit: [.refund, .payment, .transferIn].contains(t.txnType), estimated: t.fxEstimated
            )
        }
        return bridge.missingRows(rows: s.rows, app: app)
    }

    // MARK: - Helpers

    func fetchAll<T: PersistentModel>(_ type: T.Type) -> [T] {
        (try? context.fetch(FetchDescriptor<T>())) ?? []
    }

    func save() {
        try? context.save()
    }

    static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
}
