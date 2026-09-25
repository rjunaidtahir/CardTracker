import Foundation

// MARK: - Budgets

struct BudgetStatus: Identifiable {
    let categoryId: Int64
    let limitMinor: Int64
    let spentMinor: Int64
    var id: Int64 { categoryId }
    var percent: Int { limitMinor <= 0 ? 0 : Int(spentMinor * 100 / limitMinor) }
    var remainingMinor: Int64 { limitMinor - spentMinor }
}

enum Budgets {
    /// Every budget with a limit, fullest first. [spent] is net spending per category this month.
    static func status(limits: [Int64: Int64], spent: [Int64?: Int64]) -> [BudgetStatus] {
        limits.filter { $0.value > 0 }
            .map { BudgetStatus(categoryId: $0.key, limitMinor: $0.value, spentMinor: max(0, spent[$0.key] ?? 0)) }
            .sorted { $0.percent != $1.percent ? $0.percent > $1.percent : $0.categoryId < $1.categoryId }
    }

    /// The alert level reached: 100 (used up), 80 (close) or nil.
    static func threshold(_ percent: Int) -> Int? {
        if percent >= 100 { return 100 }
        if percent >= 80 { return 80 }
        return nil
    }
}

// MARK: - Fixed payments

enum FixedSchedule {
    /// The payment day in a month; day 31 becomes the last day of shorter months.
    static func dueDate(day: Int, year: Int, month: Int) -> Int64 {
        Dates.epochDay(year: year, month: month, day: min(max(day, 1), Dates.daysInMonth(year: year, month: month)))
    }

    static func paidThisMonth(lastPaidYm: String?, today: Int64) -> Bool {
        guard let l = lastPaidYm else { return false }
        return l >= Dates.ym(today)
    }

    /// This month's date, or next month's once this month is paid.
    static func nextDue(day: Int, today: Int64, lastPaidYm: String?) -> Int64 {
        let p = Dates.parts(paidThisMonth(lastPaidYm: lastPaidYm, today: today) ? Dates.plusMonths(today, 1) : today)
        return dueDate(day: day, year: p.year, month: p.month)
    }

    struct MonthTxn {
        let cardKey: String?
        let amountMinor: Int64
        let categoryId: Int64?
        let type: TxnKind
    }

    /// A bank-message transaction this month that looks like this payment: same card or account (if one is set),
    /// amount within 5%, a spend or money out, and the same category (or none).
    static func autoPaid(amountMinor: Int64, cardKey: String?, categoryId: Int64?, monthTxns: [MonthTxn]) -> Bool {
        monthTxns.contains { t in
            (t.type == .purchase || t.type == .transferOut) &&
                (cardKey == nil || t.cardKey == cardKey) &&
                abs(t.amountMinor - amountMinor) * 20 <= amountMinor &&
                (categoryId == nil || t.categoryId == nil || t.categoryId == categoryId)
        }
    }
}

// MARK: - Card payments and statement status

struct CardPayment: Hashable {
    let day: Int64
    let amountMinor: Int64
    let fromBankSms: Bool
}

enum DueState {
    case paid, minPaid, unpaid, overdue, nothingDue
}

struct StatementStatus {
    let state: DueState
    let paidMinor: Int64
    let remainingMinor: Int64
    let daysLeft: Int64

    var text: String {
        switch state {
        case .paid: return "Paid in full"
        case .minPaid: return "Minimum paid · \(MoneyText.text(remainingMinor)) still to pay"
        case .overdue: return "Overdue by \(-daysLeft) day\(daysLeft == -1 ? "" : "s")"
        case .nothingDue: return "Nothing due"
        case .unpaid:
            if daysLeft == 0 { return "Due today" }
            if daysLeft == 1 { return "Due tomorrow" }
            return "Due in \(daysLeft) days"
        }
    }

    var needsAttention: Bool { state == .unpaid || state == .overdue || state == .minPaid }
}

enum CardPayments {
    /// Bank "payment received" messages plus transfers from your accounts, without counting one payment twice
    /// (a transfer the bank also reported, same amount within 3 days, counts once).
    static func combine(bank: [CardPayment], transfers: [CardPayment]) -> [CardPayment] {
        let kept = transfers.filter { t in !bank.contains { $0.amountMinor == t.amountMinor && abs($0.day - t.day) <= 3 } }
        return (bank + kept).sorted { $0.day < $1.day }
    }

    /// Payments reaching each card.
    static func byCard(_ txns: [TxnView]) -> [String: [CardPayment]] {
        var bank: [String: [CardPayment]] = [:]
        var transfers: [String: [CardPayment]] = [:]
        for t in txns {
            if t.type == .payment, let k = t.cardKey { bank[k, default: []].append(CardPayment(day: t.day, amountMinor: t.amountMinor, fromBankSms: true)) }
            if let k = t.counterpartyKey { transfers[k, default: []].append(CardPayment(day: t.day, amountMinor: t.amountMinor, fromBankSms: false)) }
        }
        var out: [String: [CardPayment]] = [:]
        for k in Set(bank.keys).union(transfers.keys) { out[k] = combine(bank: bank[k] ?? [], transfers: transfers[k] ?? []) }
        return out
    }
}

enum StatementStatusCalc {
    /// Payments count from the statement date (or 5 days before the statement SMS when it gives no date).
    static func of(balance: Int64, minimumDue: Int64?, dueDay: Int64, statementDay: Int64?, receivedDay: Int64,
                   payments: [CardPayment], today: Int64) -> StatementStatus {
        let from = statementDay ?? (receivedDay - 5)
        let paid = payments.filter { $0.day >= from }.reduce(0) { $0 + $1.amountMinor }
        let daysLeft = dueDay - today
        let remaining = max(0, balance - paid)
        let minimum = minimumDue ?? balance
        let state: DueState
        if balance <= 0 { state = .nothingDue }
        else if paid >= balance { state = .paid }
        else if paid >= minimum { state = .minPaid }
        else if daysLeft < 0 { state = .overdue }
        else { state = .unpaid }
        return StatementStatus(state: state, paidMinor: paid, remainingMinor: remaining, daysLeft: daysLeft)
    }
}

/// A card's latest statement with its paid / due status.
struct CardDue: Identifiable {
    let cardKey: String
    let label: String
    let statement: StatementSnapshot
    let status: StatementStatus
    let payments: [CardPayment]
    var id: String { cardKey }
}

struct StatementSnapshot {
    let balanceMinor: Int64
    let minimumDueMinor: Int64?
    let dueDay: Int64
    let statementDay: Int64?
    let receivedDay: Int64
    let receivedAt: Date
}

enum CardDues {
    @MainActor
    static func compute(cards: [Card], statements: [StatementRecord], txns: [TxnView], today: Int64 = Dates.today()) -> [CardDue] {
        let payments = CardPayments.byCard(txns)
        var latest: [String: StatementRecord] = [:]
        for s in statements {
            if let cur = latest[s.cardKey] {
                if (s.dueEpochDay, s.receivedAt) > (cur.dueEpochDay, cur.receivedAt) { latest[s.cardKey] = s }
            } else {
                latest[s.cardKey] = s
            }
        }
        return cards.compactMap { c -> CardDue? in
            guard let s = latest[c.key] else { return nil }
            let snap = StatementSnapshot(
                balanceMinor: s.balanceMinor, minimumDueMinor: s.minimumDueMinor, dueDay: s.dueEpochDay,
                statementDay: s.statementEpochDay, receivedDay: Dates.epochDay(s.receivedAt), receivedAt: s.receivedAt
            )
            let p = payments[c.key] ?? []
            let st = StatementStatusCalc.of(
                balance: snap.balanceMinor, minimumDue: snap.minimumDueMinor, dueDay: snap.dueDay, statementDay: snap.statementDay,
                receivedDay: snap.receivedDay, payments: p, today: today
            )
            return CardDue(cardKey: c.key, label: c.label, statement: snap, status: st, payments: p)
        }.sorted { $0.status.daysLeft < $1.status.daysLeft }
    }
}

/// Available limit, utilisation and outstanding for a credit card.
struct Utilisation {
    let limitMinor: Int64
    let availableMinor: Int64
    var usedMinor: Int64 { max(0, limitMinor - availableMinor) }
    /// Rounded; can exceed 100 when over the limit.
    var percent: Int { limitMinor <= 0 ? 0 : Int((usedMinor * 100 + limitMinor / 2) / limitMinor) }
}

// MARK: - Statement day / due day and "since last statement"

enum CardDays {
    /// Statement day and due day from the newest statement received in the last 30 days.
    static func from(statements: [(statementDay: Int64?, dueDay: Int64, receivedDay: Int64)], today: Int64, withinDays: Int64 = 30)
        -> (statementDay: Int, dueDay: Int, fromDay: Int64)? {
        guard let recent = statements.filter({ (0...withinDays).contains(today - $0.receivedDay) }).max(by: { $0.receivedDay < $1.receivedDay }) else { return nil }
        return (Dates.parts(recent.statementDay ?? recent.receivedDay).day, Dates.parts(recent.dueDay).day, recent.receivedDay)
    }
}

enum SinceStatement {
    /// The day after the statement date (or after the day the statement SMS arrived).
    static func startDay(statementDay: Int64?, receivedDay: Int64) -> Int64 { (statementDay ?? receivedDay) + 1 }

    static func spend(_ txns: [TxnView], from start: Int64) -> Int64 {
        txns.filter { $0.day >= start }.reduce(0) { $0 + SpendRules.contribution($1.type, $1.aed) }
    }
}

// MARK: - Savings goals

enum Goals {
    /// Monthly amount still needed to reach the target by its date (at least one month).
    static func perMonth(targetMinor: Int64, savedMinor: Int64, targetDay: Int64, today: Int64) -> Int64 {
        let a = Dates.parts(today), b = Dates.parts(targetDay)
        let months = max(1, (b.year - a.year) * 12 + (b.month - a.month))
        return max(0, targetMinor - savedMinor) / Int64(months)
    }
}
