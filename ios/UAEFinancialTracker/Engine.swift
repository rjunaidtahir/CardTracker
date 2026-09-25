import Foundation
import SwiftData
import Shared

/// Rule id of transactions you read yourself (Needs review → Fix).
let fixedByYouRule = "fixed-by-you"

/// Everything that changes the app's data (the same steps as the Android app's Repository), on top of the shared
/// engine (`Bridge`) that reads the messages.
@MainActor
final class Engine {
    let context: ModelContext
    private let bridge = Bridge.shared
    /// True while re-reading every message (no alerts then).
    private var rereading = false
    /// Transactions added since the last `takeFresh()`, for spending alerts.
    private var fresh: [Txn] = []
    /// Called after data changes (reminders, widget). Set by the app.
    var onChange: (() -> Void)?

    init(context: ModelContext) {
        self.context = context
        refreshSenders()
        loadCategories()
    }

    enum Outcome: String {
        case transaction, merged, statement, ignored, failed, duplicate, otp, notBank
    }

    struct Tally {
        var transactions = 0, merged = 0, statements = 0, review = 0, duplicates = 0, skipped = 0
        mutating func add(_ o: Outcome) {
            switch o {
            case .transaction: transactions += 1
            case .merged: merged += 1
            case .statement: statements += 1
            case .failed: review += 1
            case .duplicate: duplicates += 1
            case .ignored, .otp, .notBank: skipped += 1
            }
        }
        var summary: String {
            var parts: [String] = []
            if transactions > 0 { parts.append("\(transactions) transaction\(transactions == 1 ? "" : "s")") }
            if merged > 0 { parts.append("\(merged) merged") }
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
        var map: [String: String] = [:]
        for s in fetchAll(BankSender.self) { map[s.sender] = s.bank }
        bridge.setCustomSenders(senderToBank: map)
    }

    func loadCategories() {
        Categories.custom = fetchAll(CustomCategory.self).filter { !$0.archived }.sorted { $0.id < $1.id }.map { ($0.id, $0.name) }
    }

    /// Adds a category of your own. Returns its id (below 100, like Android).
    @discardableResult
    func addCategory(_ name: String) -> Int64? {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return nil }
        if let existing = Categories.all.first(where: { $0.name.caseInsensitiveCompare(n) == .orderedSame }) { return existing.id }
        let used = Categories.all.map(\.id).filter { $0 < 100 }
        let id = (used.max() ?? 0) + 1
        context.insert(CustomCategory(id: id, name: n))
        save()
        loadCategories()
        return id
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
        let senderName = cleanSender.isEmpty ? (reading.bank ?? "Pasted") : cleanSender
        let sms = SmsRecord(
            sender: senderName, body: body, bodyHash: hash,
            receivedAt: receivedAt, timeKnown: timeKnown, source: source, bank: reading.bank
        )
        sms.dedupKey = bridge.dedupKey(sender: senderName, sentAtMillis: 0, receivedAtMillis: millis, body: body)
        context.insert(sms)
        let outcome = apply(sms, reading)
        save()
        return outcome
    }

    /// Adds many messages (a paste, a file, screenshots), oldest first so transfers and statements line up.
    func ingestAll(_ items: [(body: String, sender: String?, date: Date?)], source: String) -> Tally {
        var tally = Tally()
        let now = Date()
        for item in items.sorted(by: { ($0.date ?? now) < ($1.date ?? now) }) {
            tally.add(ingest(body: item.body, sender: item.sender, receivedAt: item.date ?? now, timeKnown: item.date != nil, source: source))
        }
        changed()
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
        rereading = true
        defer { rereading = false }
        let all = (try? context.fetch(FetchDescriptor<SmsRecord>(sortBy: [SortDescriptor(\.receivedAt)]))) ?? []
        // Rebuild every message-based row in time order, so two-message transfers pair up cleanly.
        for t in fetchAll(Txn.self) where t.smsId != nil { context.delete(t) }
        for s in fetchAll(StatementRecord.self) where s.smsId != nil { context.delete(s) }
        var tally = Tally()
        for sms in all where sms.status != SmsStatus.dismissed {
            tally.add(apply(sms, read(sms), cleared: true))
        }
        save()
        autoFillCardDays()
        changed()
        return tally
    }

    private func read(_ sms: SmsRecord) -> SmsReading {
        let millis = Self.millis(sms.receivedAt)
        if bridge.bankFor(sender: sms.sender) != nil {
            return bridge.readSms(sender: sms.sender, body: sms.body, receivedAtMillis: millis, rates: rates)
        }
        return bridge.readSmsAnySender(body: sms.body, receivedAtMillis: millis, rates: rates)
    }

    /// Transactions added since the last call (for spending alerts).
    func takeFresh() -> [Txn] {
        defer { fresh = [] }
        return fresh
    }

    // MARK: - Applying a reading

    private func deleteDerived(_ smsId: UUID) {
        let target: UUID? = smsId
        let txns = (try? context.fetch(FetchDescriptor<Txn>(predicate: #Predicate { $0.smsId == target }))) ?? []
        for t in txns { context.delete(t) }
        let sts = (try? context.fetch(FetchDescriptor<StatementRecord>(predicate: #Predicate { $0.smsId == target }))) ?? []
        for s in sts { context.delete(s) }
        let paired = (try? context.fetch(FetchDescriptor<Txn>(predicate: #Predicate { $0.pairedSmsId == target }))) ?? []
        for t in paired { t.pairedSmsId = nil }
    }

    @discardableResult
    private func apply(_ sms: SmsRecord, _ r: SmsReading, cleared: Bool = false) -> Outcome {
        if !cleared { deleteDerived(sms.id) }
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

        // Two messages for one transfer (e.g. FAB "Outward Remittance" + "funds transfer processed"): merge them.
        // Only a message that names the destination pairs with one that doesn't.
        if let key, t.type == TxnKind.transferOut.rawValue || t.type == TxnKind.purchase.rawValue {
            let partners = Set(bridge.pairPartnerRules(ruleId: t.ruleId))
            if !partners.isEmpty {
                let window: TimeInterval = 24 * 3600
                let amount = t.amountMinor
                let candidates = fetchAll(Txn.self).filter {
                    $0.cardKey == key && $0.amountMinor == amount && $0.pairedSmsId == nil && partners.contains($0.ruleId ?? "") &&
                        abs($0.timestamp.timeIntervalSince(t.timestamp)) <= window
                }
                if let other = candidates.min(by: { abs($0.timestamp.timeIntervalSince(t.timestamp)) < abs($1.timestamp.timeIntervalSince(t.timestamp)) }) {
                    let hasTo = bridge.namesDestination(ruleId: t.ruleId)
                    other.pairedSmsId = sms.id
                    if t.toLast4 != nil { other.merchant = t.merchant; other.merchantKey = bridge.merchantKey(merchant: t.merchant) }
                    other.counterpartyKey = other.counterpartyKey ?? counterparty
                    other.availableMinor = other.availableMinor ?? t.availableMinor
                    if hasTo { other.timestamp = t.timestamp }
                    if t.type == TxnKind.transferOut.rawValue || other.type == TxnKind.transferOut.rawValue {
                        other.type = TxnKind.transferOut.rawValue
                    }
                    setResult(sms, SmsStatus.transaction, bank: t.bank, ruleId: t.ruleId, note: "Same transfer as another message (merged)")
                    return .merged
                }
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
            txn.categoryId = Categories.familyId
        } else {
            txn.categoryId = category(merchant: t.merchant, merchantKey: merchantKey, type: t.type, amountMinor: t.amountMinor)
        }
        context.insert(txn)
        if !rereading { fresh.append(txn) }
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
            return merchant.range(of: "SALARY", options: .caseInsensitive) != nil ? Categories.incomeId : nil
        }
        if !merchantKey.isEmpty, let r = rules.first(where: { $0.key == merchantKey }) { return r.categoryId }
        let g = bridge.guessCategory(merchant: merchant, type: type)
        return g < 0 ? nil : g
    }

    // MARK: - Your changes: transactions

    /// Sets a transaction's category; with [applyToMerchant] the app also learns it for this merchant, past and future
    /// (for generic texts like "Account debit", only for the same text and amount).
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
            for other in fetchAll(Txn.self) where !other.categoryUserSet && other.id != t.id && bridge.canHaveCategory(type: other.type) {
                let k = bridge.learningKey(merchant: other.merchant, amountMinor: other.amountMinor, type: other.type)
                if k == learning { other.categoryId = categoryId }
            }
        }
        save()
    }

    func isAmountSpecific(_ t: Txn) -> Bool { bridge.isAmountSpecific(merchant: t.merchant, type: t.type) }

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
        let cur = currency.trimmingCharacters(in: .whitespaces).uppercased()
        let fix = SmsFix(
            smsId: sms.id, type: type?.rawValue ?? SmsFix.ignore, amountMinor: amountMinor, currency: cur.isEmpty ? "AED" : cur,
            merchant: m.isEmpty ? "Transaction" : m, cardLast4: (last4?.isEmpty ?? true) ? nil : last4, cardType: cardType.rawValue, timestamp: date
        )
        context.insert(fix)
        apply(sms, read(sms))
        save()
        changed()
    }

    /// What the smart reader makes of a message it couldn't read for sure (pre-fills the Fix form).
    func guess(_ sms: SmsRecord) -> SmsReading? {
        bridge.guessTransaction(bank: sms.bank ?? sms.sender, body: sms.body, receivedAtMillis: Self.millis(sms.receivedAt))
    }

    func dismiss(_ sms: SmsRecord) {
        sms.status = SmsStatus.dismissed
        save()
    }

    /// Deleting a message's transaction also dismisses its message(s), so a re-read doesn't bring it back.
    func delete(_ t: Txn) {
        let ids = [t.smsId, t.pairedSmsId].compactMap { $0 }
        for sms in fetchAll(SmsRecord.self) where ids.contains(sms.id) { sms.status = SmsStatus.dismissed }
        context.delete(t)
        save()
        changed()
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
        fresh.append(t)
        save()
        changed()
        return true
    }

    // MARK: - Cards

    /// Adds a card or account by hand. Returns its key.
    @discardableResult
    func addCard(bank: String, last4: String?, kind: CardKind, nickname: String?, family: Bool) -> String {
        let b = bank.trimmingCharacters(in: .whitespaces)
        let l4 = last4?.trimmingCharacters(in: .whitespaces)
        let key = bridge.cardKey(bank: b, last4: (l4?.isEmpty ?? true) ? nil : l4)
        ensureCard(key: key, bank: b, last4: (l4?.isEmpty ?? true) ? nil : l4, type: kind.rawValue)
        if let c = card(key) {
            if family { setFamily(c, true) }
            if let n = nickname?.trimmingCharacters(in: .whitespaces), !n.isEmpty { c.nickname = n }
        }
        save()
        changed()
        return key
    }

    func card(_ key: String?) -> Card? {
        guard let key else { return nil }
        return fetchAll(Card.self).first { $0.key == key }
    }

    /// Changing the type resets "Show & count" to that type's default.
    func setCardType(_ c: Card, _ kind: CardKind) {
        guard c.kind != kind else { return }
        c.cardType = kind.rawValue
        c.counted = bridge.countsByDefault(cardType: kind.rawValue)
        save()
        changed()
    }

    /// A card you pay for someone else: its spends go to the Family category (unless you chose another).
    func setFamily(_ c: Card, _ family: Bool) {
        c.family = family
        for t in fetchAll(Txn.self) where t.cardKey == c.key && t.txnType == .purchase && !t.categoryUserSet {
            t.categoryId = family ? Categories.familyId : category(merchant: t.merchant, merchantKey: t.merchantKey, type: t.type, amountMinor: t.amountMinor)
        }
        save()
    }

    func setCounted(_ c: Card, _ counted: Bool) {
        c.counted = counted
        save()
        changed()
    }

    func updateProfile(_ c: Card, nickname: String?, limitMinor: Int64?, statementDay: Int?, dueDay: Int?, reminders: Bool) {
        let n = nickname?.trimmingCharacters(in: .whitespaces)
        c.nickname = (n?.isEmpty ?? true) ? nil : n
        c.creditLimitMinor = limitMinor
        c.statementDay = statementDay.flatMap { (1...31).contains($0) ? $0 : nil }
        c.dueDay = dueDay.flatMap { (1...31).contains($0) ? $0 : nil }
        c.remindersEnabled = reminders
        save()
        changed()
    }

    func setCardTheme(_ c: Card, _ key: String?) {
        if let old = c.themeKey, old.hasPrefix("img:"), old != key { CardPictures.delete(old) }
        c.themeKey = key
        save()
    }

    func setCardOrder(_ keys: [String]) {
        let cards = fetchAll(Card.self)
        for (i, k) in keys.enumerated() { cards.first { $0.key == k }?.order = i }
        save()
    }

    /// Deletes a card only when nothing is recorded on it. Returns false otherwise.
    func deleteCardIfEmpty(_ c: Card) -> Bool {
        let key = c.key
        if fetchAll(Txn.self).contains(where: { $0.cardKey == key || $0.counterpartyKey == key }) { return false }
        if fetchAll(StatementRecord.self).contains(where: { $0.cardKey == key }) { return false }
        if let t = c.themeKey, t.hasPrefix("img:") { CardPictures.delete(t) }
        context.delete(c)
        save()
        changed()
        return true
    }

    /// Fills each card's statement day and due day from its statement SMS of the last 30 days (never overwrites yours).
    func autoFillCardDays(today: Int64 = Dates.today()) {
        let byCard = Dictionary(grouping: fetchAll(StatementRecord.self), by: \.cardKey)
        for c in fetchAll(Card.self) where c.statementDay == nil || c.dueDay == nil {
            let list = (byCard[c.key] ?? []).map { (statementDay: $0.statementEpochDay, dueDay: $0.dueEpochDay, receivedDay: Dates.epochDay($0.receivedAt)) }
            guard let d = CardDays.from(statements: list, today: today) else { continue }
            if c.statementDay == nil { c.statementDay = d.statementDay }
            if c.dueDay == nil { c.dueDay = d.dueDay }
        }
        save()
    }

    // MARK: - Senders and rates

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
        let cur = currency.trimmingCharacters(in: .whitespaces).uppercased()
        guard cur.count == 3, let v = Double(rate), v > 0 else { return }
        var r = Settings.rates
        r[cur] = rate.trimmingCharacters(in: .whitespaces)
        Settings.rates = r
        for t in fetchAll(Txn.self) where t.currency.caseInsensitiveCompare(cur) == .orderedSame {
            t.aedMinor = bridge.toAedMinor(amountMinor: t.amountMinor, currency: t.currency, rates: r)
        }
        save()
        changed()
    }

    // MARK: - Budgets, fixed payments, goals

    /// Monthly limits per category; nil or 0 removes a budget.
    func setBudgets(_ limits: [Int64: Int64?]) {
        let existing = fetchAll(Budget.self)
        for (id, limit) in limits {
            let b = existing.first { $0.categoryId == id }
            if let limit, limit > 0 {
                if let b { b.limitMinor = limit } else { context.insert(Budget(categoryId: id, limitMinor: limit)) }
            } else if let b {
                context.delete(b)
            }
        }
        save()
    }

    func addFixedPayment(_ f: FixedPayment) {
        context.insert(f)
        save()
        changed()
    }

    func deleteFixedPayment(_ f: FixedPayment) {
        context.delete(f)
        save()
        changed()
    }

    /// Records this month's payment as a typed spend and marks it paid.
    func markPaid(_ f: FixedPayment, date: Date = Date()) {
        let t = Txn(source: "Fixed", timestamp: date, bank: "Fixed payment", merchant: f.name, amountMinor: f.amountMinor, currency: "AED", aedMinor: f.amountMinor, type: TxnKind.purchase.rawValue)
        t.note = "Fixed payment"
        t.merchantKey = bridge.merchantKey(merchant: f.name)
        t.categoryId = f.categoryId ?? category(merchant: f.name, merchantKey: t.merchantKey, type: t.type, amountMinor: f.amountMinor)
        context.insert(t)
        f.lastPaidYm = Dates.ym(Dates.epochDay(date))
        save()
        changed()
    }

    func undoPaid(_ f: FixedPayment) {
        f.lastPaidYm = nil
        save()
        changed()
    }

    /// Adds a recurring payment the app spotted as a fixed payment. False when it's already there.
    func trackRecurring(_ r: RecurringPayment) -> Bool {
        let exists = fetchAll(FixedPayment.self).contains { f in f.cardKey == r.cardKey && abs(f.amountMinor - r.averageMinor) * 20 <= r.averageMinor }
        if exists { return false }
        let generic = bridge.isAmountSpecific(merchant: r.merchant, type: TxnKind.purchase.rawValue) ||
            r.merchantKey.hasPrefix("TRANSFER") || r.merchantKey.hasPrefix("PAYMENT")
        let name = generic && r.categoryId != nil ? Categories.name(r.categoryId) : r.merchant
        let f = FixedPayment(name: name, amountMinor: r.averageMinor, dayOfMonth: Dates.parts(r.lastDay).day)
        f.categoryId = r.categoryId
        f.cardKey = r.cardKey
        addFixedPayment(f)
        return true
    }

    func isTracked(_ r: RecurringPayment) -> Bool {
        fetchAll(FixedPayment.self).contains { f in f.cardKey == r.cardKey && abs(f.amountMinor - r.averageMinor) * 20 <= r.averageMinor }
    }

    func addGoal(name: String, targetMinor: Int64, savedMinor: Int64, targetDay: Int64?) {
        context.insert(Goal(name: name, targetMinor: targetMinor, savedMinor: savedMinor, targetEpochDay: targetDay))
        save()
    }

    func addToGoal(_ g: Goal, _ amountMinor: Int64) {
        g.savedMinor = max(0, g.savedMinor + amountMinor)
        save()
    }

    func deleteGoal(_ g: Goal) {
        context.delete(g)
        save()
    }

    // MARK: - Statements from PDF

    /// Saves what a statement PDF says about a card (limit, statement / due day, the amount due) and adds the rows
    /// you chose that aren't in the app yet. Returns what was saved.
    func applyStatement(_ s: StatementReading, cardKey: String, saveProfile: Bool = true, addRows rows: [StatementRow]) -> [String] {
        guard let card = card(cardKey) else { return [] }
        var done: [String] = []
        if saveProfile && !s.isAccount {
            if s.creditLimitMinor > 0 {
                card.creditLimitMinor = s.creditLimitMinor
                done.append("credit limit")
            }
            if s.statementEpochDay >= 0 || s.dueEpochDay >= 0 {
                if s.statementEpochDay >= 0 { card.statementDay = Dates.parts(s.statementEpochDay).day }
                if s.dueEpochDay >= 0 { card.dueDay = Dates.parts(s.dueEpochDay).day }
                done.append("statement and due day")
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
        let existing = fetchAll(Txn.self).filter { $0.source == "Statement" && $0.cardKey == cardKey }
        for row in rows {
            let type: TxnKind
            if !row.isCredit { type = .purchase }
            else if card.kind == .account { type = .transferIn }
            else if row.details.range(of: "PAYMENT|THANK YOU", options: [.regularExpression, .caseInsensitive]) != nil { type = .payment }
            else { type = .refund }
            let date = Dates.date(epochDay: row.epochDay).addingTimeInterval(12 * 3600)
            if existing.contains(where: { $0.timestamp == date && $0.amountMinor == row.amountMinor && $0.merchant == row.details }) { continue }
            let t = Txn(source: "Statement", timestamp: date, bank: card.bank, merchant: row.details, amountMinor: row.amountMinor, currency: "AED", aedMinor: row.amountMinor, type: type.rawValue)
            t.cardKey = card.key
            t.cardLast4 = card.last4
            t.note = "From statement PDF"
            t.merchantKey = bridge.merchantKey(merchant: row.details)
            if card.family && type == .purchase {
                t.categoryId = Categories.familyId
                t.categoryUserSet = true
            } else {
                t.categoryId = category(merchant: row.details, merchantKey: t.merchantKey, type: type.rawValue, amountMinor: row.amountMinor)
            }
            context.insert(t)
            added += 1
        }
        if added > 0 { done.append("\(added) missing transaction\(added == 1 ? "" : "s")") }
        save()
        changed()
        return done
    }

    /// The statement checked against one card's transactions.
    func reconcile(_ s: StatementReading, cardKey: String) -> (result: ReconcileResult, txns: [UUID: Txn]) {
        let mine = fetchAll(Txn.self).filter { $0.cardKey == cardKey || $0.counterpartyKey == cardKey }
        let app = mine.map { t in
            AppTxn(
                ref: t.id.uuidString, epochDay: Dates.epochDay(t.timestamp), amountMinor: t.aedMinor >= 0 ? t.aedMinor : t.amountMinor,
                isCredit: t.txnType.isIncoming || t.counterpartyKey == cardKey, estimated: t.fxEstimated
            )
        }
        var byId: [UUID: Txn] = [:]
        for t in mine { byId[t.id] = t }
        return (bridge.reconcile(rows: s.rows, app: app), byId)
    }

    func missingRows(_ s: StatementReading, cardKey: String) -> [StatementRow] { reconcile(s, cardKey: cardKey).result.missing }

    // MARK: - Helpers

    func fetchAll<T: PersistentModel>(_ type: T.Type) -> [T] {
        (try? context.fetch(FetchDescriptor<T>())) ?? []
    }

    func save() {
        try? context.save()
    }

    /// Something changed that reminders or the widget show.
    func changed() {
        onChange?()
    }

    static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
}
