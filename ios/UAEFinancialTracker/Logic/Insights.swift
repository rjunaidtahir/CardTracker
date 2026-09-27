import Foundation

/// A transaction reduced to what the calculations need (a plain value, so the rules can be unit-tested).
struct TxnView: Identifiable, Hashable {
    let id: UUID
    let day: Int64
    let timestamp: Date
    let type: TxnKind
    /// AED fils, nil when there's no exchange rate.
    let aed: Int64?
    let cardKey: String?
    let categoryId: Int64?
    let merchant: String
    let merchantKey: String
    let currency: String
    let amountMinor: Int64
    let counterpartyKey: String?
    let availableMinor: Int64?
    let fromSms: Bool

    init(id: UUID = UUID(), day: Int64, timestamp: Date? = nil, type: TxnKind, aed: Int64?, cardKey: String? = nil, categoryId: Int64? = nil,
         merchant: String = "", merchantKey: String = "", currency: String = "AED", amountMinor: Int64? = nil,
         counterpartyKey: String? = nil, availableMinor: Int64? = nil, fromSms: Bool = true) {
        self.id = id
        self.day = day
        self.timestamp = timestamp ?? Dates.date(epochDay: day).addingTimeInterval(12 * 3600)
        self.type = type
        self.aed = aed
        self.cardKey = cardKey
        self.categoryId = categoryId
        self.merchant = merchant
        self.merchantKey = merchantKey
        self.currency = currency
        self.amountMinor = amountMinor ?? aed ?? 0
        self.counterpartyKey = counterpartyKey
        self.availableMinor = availableMinor
        self.fromSms = fromSms
    }

    @MainActor
    init(_ t: Txn) {
        self.init(
            id: t.id, day: Dates.epochDay(t.timestamp), timestamp: t.timestamp, type: t.txnType, aed: t.aedMinor >= 0 ? t.aedMinor : nil,
            cardKey: t.cardKey, categoryId: t.categoryId, merchant: t.merchant, merchantKey: t.merchantKey, currency: t.currency.uppercased(),
            amountMinor: t.amountMinor, counterpartyKey: t.counterpartyKey, availableMinor: t.availableMinor, fromSms: t.smsId != nil
        )
    }
}

/// The spending rule used by every total (the same as the shared core/Spending.kt): purchases minus refunds;
/// card payments, transfers and money in never count, and neither do cards with "Show & count" off.
enum SpendRules {
    static func contribution(_ type: TxnKind, _ aed: Int64?, counted: Bool = true) -> Int64 {
        guard counted, let aed else { return 0 }
        switch type {
        case .purchase: return aed
        case .refund: return -aed
        default: return 0
        }
    }

    static func counted(_ t: TxnView, excluded: Set<String>) -> Bool { t.cardKey.map { !excluded.contains($0) } ?? true }

    static func contribution(_ t: TxnView, excluded: Set<String>) -> Int64 {
        contribution(t.type, t.aed, counted: counted(t, excluded: excluded))
    }

    static func total(_ txns: [TxnView], excluded: Set<String>) -> Int64 {
        txns.reduce(0) { $0 + contribution($1, excluded: excluded) }
    }
}

struct RecurringPayment: Identifiable, Hashable {
    let merchant: String
    let merchantKey: String
    let averageMinor: Int64
    let occurrences: Int
    let lastDay: Int64
    let nextExpected: Int64
    let cardKey: String?
    let categoryId: Int64?
    var id: String { "\(merchantKey)|\(cardKey ?? "")|\(averageMinor)" }
}

struct CurrencyTotal: Identifiable {
    let currency: String
    let originalMinor: Int64
    let aedMinor: Int64
    let count: Int
    var id: String { currency }
}

enum Insights {
    /// Net spending per category (purchases minus refunds), largest first. Zero or negative categories dropped.
    static func byCategory(_ txns: [TxnView], excluded: Set<String>) -> [(categoryId: Int64?, amount: Int64)] {
        var sums: [Int64?: Int64] = [:]
        for t in txns where SpendRules.counted(t, excluded: excluded) {
            sums[t.categoryId, default: 0] += SpendRules.contribution(t.type, t.aed)
        }
        return sums.filter { $0.value > 0 }.map { (categoryId: $0.key, amount: $0.value) }
            .sorted { $0.amount != $1.amount ? $0.amount > $1.amount : ($0.categoryId ?? -1) < ($1.categoryId ?? -1) }
    }

    /// Net spending per card ("" = typed entries without a card).
    static func byCard(_ txns: [TxnView], excluded: Set<String>) -> [(cardKey: String, amount: Int64)] {
        var sums: [String: Int64] = [:]
        for t in txns where SpendRules.counted(t, excluded: excluded) {
            sums[t.cardKey ?? "", default: 0] += SpendRules.contribution(t.type, t.aed)
        }
        return sums.filter { $0.value > 0 }.map { (cardKey: $0.key, amount: $0.value) }
            .sorted { $0.amount != $1.amount ? $0.amount > $1.amount : $0.cardKey < $1.cardKey }
    }

    /// SpendRules in each of the [months] months ending with the month of [endDay] (oldest first, zero months included).
    static func byMonth(_ txns: [TxnView], excluded: Set<String>, endDay: Int64, months: Int = 12) -> [(ym: String, start: Int64, amount: Int64)] {
        var sums: [String: Int64] = [:]
        for t in txns where SpendRules.counted(t, excluded: excluded) {
            sums[Dates.ym(t.day), default: 0] += SpendRules.contribution(t.type, t.aed)
        }
        let p = Dates.parts(endDay)
        let firstOfEnd = Dates.epochDay(year: p.year, month: p.month, day: 1)
        return (0..<months).map { i in
            let start = Dates.plusMonths(firstOfEnd, i - (months - 1))
            let ym = Dates.ym(start)
            return (ym: ym, start: start, amount: sums[ym] ?? 0)
        }
    }

    /// Top merchants by net spending.
    static func topMerchants(_ txns: [TxnView], excluded: Set<String>, limit: Int = 5) -> [(name: String, amount: Int64, count: Int)] {
        var sums: [String: (name: String, amount: Int64, count: Int)] = [:]
        for t in txns {
            let c = SpendRules.contribution(t, excluded: excluded)
            guard c != 0 else { continue }
            let key = t.merchantKey.isEmpty ? t.merchant.uppercased() : t.merchantKey
            if key.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            let old = sums[key] ?? (name: t.merchant, amount: 0, count: 0)
            sums[key] = (name: old.name, amount: old.amount + c, count: old.count + 1)
        }
        // Largest first; equal amounts by name, so the order never changes between runs.
        return Array(sums.values.filter { $0.amount > 0 }
            .sorted { $0.amount != $1.amount ? $0.amount > $1.amount : $0.name < $1.name }.prefix(limit))
    }

    /// Purchases in each non-AED currency: original total and AED equivalent.
    static func byCurrency(_ txns: [TxnView], excluded: Set<String>) -> [CurrencyTotal] {
        let foreign = txns.filter { SpendRules.counted($0, excluded: excluded) && $0.type == .purchase && $0.currency != "AED" }
        return Dictionary(grouping: foreign, by: \.currency).map { cur, l in
            CurrencyTotal(currency: cur, originalMinor: l.reduce(0) { $0 + $1.amountMinor }, aedMinor: l.reduce(0) { $0 + ($1.aed ?? 0) }, count: l.count)
        }.sorted { $0.aedMinor != $1.aedMinor ? $0.aedMinor > $1.aedMinor : $0.currency < $1.currency }
    }

    /// SpendRules per day, week or month from [start] to [end] (inclusive), oldest first, empty buckets included.
    static func timeline(_ txns: [TxnView], excluded: Set<String>, start: Int64, end: Int64, bucket: Bucket? = nil) -> [TimePoint] {
        guard end >= start else { return [] }
        let b = bucket ?? Bucket.of(start: start, end: end)
        func key(_ d: Int64) -> Int64 {
            switch b {
            case .day: return d
            case .week: return start + (d - start) / 7 * 7
            case .month:
                let p = Dates.parts(d)
                return Dates.epochDay(year: p.year, month: p.month, day: 1)
            }
        }
        var sums: [Int64: Int64] = [:]
        for t in txns where t.day >= start && t.day <= end {
            let c = SpendRules.contribution(t, excluded: excluded)
            if c != 0 { sums[key(t.day), default: 0] += c }
        }
        var out: [TimePoint] = []
        var k = key(start)
        while k <= end {
            out.append(TimePoint(start: k, amountMinor: sums[k] ?? 0))
            switch b {
            case .day: k += 1
            case .week: k += 7
            case .month: k = Dates.plusMonths(k, 1)
            }
        }
        return out
    }

    /// Recurring payments: the same merchant (or the same account-debit amount) at least 3 times, roughly monthly
    /// (25–35 days apart for 70% of the gaps), amounts within 15% of their median, and seen in the last 75 days.
    static func recurring(_ txns: [TxnView], today: Int64) -> [RecurringPayment] {
        let candidates = txns.filter {
            ($0.type == .purchase || ($0.type == .transferOut && $0.categoryId != nil)) && ($0.aed ?? 0) > 0
        }
        let groups = Dictionary(grouping: candidates) { t -> String in
            if t.type == .transferOut || t.merchantKey.hasPrefix("ACCOUNT DEBIT") {
                return "\(t.type.rawValue)|\(t.merchantKey)|\(t.cardKey ?? "null")|\(t.amountMinor)"
            }
            return "\(t.merchantKey)|\(t.cardKey ?? "null")"
        }
        var out: [RecurringPayment] = []
        for g in groups.values where g.count >= 3 {
            var seen = Set<Int64>()
            let byDay = g.sorted { $0.day < $1.day }.filter { seen.insert($0.day).inserted }
            guard byDay.count >= 3 else { continue }
            let amounts = byDay.map { $0.aed ?? 0 }.sorted()
            let median = amounts[amounts.count / 2]
            if median <= 0 || amounts.contains(where: { abs($0 - median) * 100 > median * 15 }) { continue }
            let gaps = zip(byDay, byDay.dropFirst()).map { $1.day - $0.day }
            let monthly = gaps.filter { (25...35).contains($0) }.count
            if monthly * 10 < gaps.count * 7 { continue }
            let last = byDay[byDay.count - 1]
            var next = Dates.plusMonths(last.day, 1)
            while next < today { next = Dates.plusMonths(next, 1) }
            if today - last.day > 75 { continue }
            let avg = amounts.reduce(0, +) / Int64(amounts.count)
            out.append(RecurringPayment(
                merchant: last.merchant, merchantKey: last.merchantKey, averageMinor: avg, occurrences: byDay.count,
                lastDay: last.day, nextExpected: next, cardKey: last.cardKey, categoryId: byDay.last { $0.categoryId != nil }?.categoryId
            ))
        }
        return out.sorted { $0.averageMinor != $1.averageMinor ? $0.averageMinor > $1.averageMinor : $0.merchant < $1.merchant }
    }
}
