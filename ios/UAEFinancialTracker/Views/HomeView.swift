import SwiftUI
import SwiftData
import Shared

/// Spending rules shared with Android: payments to your cards, transfers between your accounts and money in never count.
enum SpendMath {
    static func contribution(_ t: Txn, cards: [String: Card]) -> Int64 {
        let counted = t.cardKey.flatMap { cards[$0]?.counted } ?? true
        return Bridge.shared.spendingContribution(type: t.type, aedMinor: t.aedMinor, cardCounted: counted)
    }

    static func cardMap(_ cards: [Card]) -> [String: Card] {
        Dictionary(cards.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
    }
}

enum Period: String, CaseIterable, Identifiable {
    case thisMonth = "This month", lastMonth = "Last month", last30 = "Last 30 days", thisYear = "This year"
    var id: String { rawValue }

    /// [start, end) of this period, and of the one before it (for the comparison).
    func range(now: Date = Date()) -> (DateInterval, DateInterval) {
        let cal = Calendar.current
        let startOfMonth = cal.date(from: cal.dateComponents([.year, .month], from: now))!
        switch self {
        case .thisMonth:
            let prev = cal.date(byAdding: .month, value: -1, to: startOfMonth)!
            let prevEnd = cal.date(byAdding: .second, value: Int(now.timeIntervalSince(startOfMonth)), to: prev)!
            return (DateInterval(start: startOfMonth, end: now), DateInterval(start: prev, end: min(prevEnd, startOfMonth)))
        case .lastMonth:
            let start = cal.date(byAdding: .month, value: -1, to: startOfMonth)!
            let before = cal.date(byAdding: .month, value: -2, to: startOfMonth)!
            return (DateInterval(start: start, end: startOfMonth), DateInterval(start: before, end: start))
        case .last30:
            let start = cal.date(byAdding: .day, value: -30, to: now)!
            let before = cal.date(byAdding: .day, value: -60, to: now)!
            return (DateInterval(start: start, end: now), DateInterval(start: before, end: start))
        case .thisYear:
            let start = cal.date(from: cal.dateComponents([.year], from: now))!
            let prev = cal.date(byAdding: .year, value: -1, to: start)!
            let prevEnd = cal.date(byAdding: .year, value: -1, to: now)!
            return (DateInterval(start: start, end: now), DateInterval(start: prev, end: prevEnd))
        }
    }
}

struct HomeView: View {
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var cards: [Card]
    @Query private var statements: [StatementRecord]
    @Query private var messages: [SmsRecord]
    @AppStorage("homePeriod") private var periodRaw = Period.thisMonth.rawValue

    private var period: Period { Period(rawValue: periodRaw) ?? .thisMonth }

    var body: some View {
        let cardMap = SpendMath.cardMap(cards)
        let ranges = period.range()
        let inNow = txns.filter { ranges.0.contains($0.timestamp) }
        let spent = inNow.reduce(Int64(0)) { $0 + SpendMath.contribution($1, cards: cardMap) }
        let spentBefore = txns.filter { ranges.1.contains($0.timestamp) }.reduce(Int64(0)) { $0 + SpendMath.contribution($1, cards: cardMap) }
        let review = messages.filter { $0.status == SmsStatus.failed }.count

        NavigationStack {
            List {
                if txns.isEmpty && messages.isEmpty {
                    Section { EmptyStart() }
                }
                if review > 0 {
                    Section {
                        NavigationLink { ReviewView() } label: {
                            Label("\(review) message\(review == 1 ? "" : "s") to review", systemImage: "exclamationmark.bubble")
                                .foregroundStyle(.orange)
                        }
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Picker("Period", selection: $periodRaw) {
                            ForEach(Period.allCases) { Text($0.rawValue).tag($0.rawValue) }
                        }
                        .pickerStyle(.menu)
                        .labelsHidden()
                        .padding(.leading, -12)
                        Text(MoneyText.text(spent))
                            .font(.system(size: 38, weight: .bold, design: .rounded))
                            .minimumScaleFactor(0.5)
                            .lineLimit(1)
                        Comparison(now: spent, before: spentBefore)
                    }
                    .padding(.vertical, 4)
                }
                PaymentsDue(statements: statements, txns: txns, cards: cardMap)
                ByCategory(txns: inNow, cards: cardMap, total: spent)
                TopMerchants(txns: inNow, cards: cardMap)
            }
            .navigationTitle("Home")
        }
    }
}

private struct EmptyStart: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Add your bank messages").font(.headline)
            Text("iPhone apps can't read your SMS on their own. Set up the one-minute Shortcuts automation, and each bank SMS is added as it arrives. You can also paste messages or import a file.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            NavigationLink { AutomationGuideView() } label: {
                Label("Set up automatic import", systemImage: "wand.and.stars")
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.vertical, 6)
    }
}

private struct Comparison: View {
    let now: Int64
    let before: Int64
    var body: some View {
        if before > 0 {
            let diff = Double(now - before) / Double(before) * 100
            let up = diff >= 0
            Label(String(format: "%.0f%% %@ than the period before (%@)", abs(diff), up ? "more" : "less", MoneyText.text(before)),
                  systemImage: up ? "arrow.up.right" : "arrow.down.right")
                .font(.footnote)
                .foregroundStyle(up ? .red : .green)
        } else {
            Text("Spending on your counted cards").font(.footnote).foregroundStyle(.secondary)
        }
    }
}

/// Latest statement of each card with its due date and whether it looks paid.
struct PaymentsDue: View {
    let statements: [StatementRecord]
    let txns: [Txn]
    let cards: [String: Card]

    struct Due: Identifiable {
        let id: String
        let label: String
        let dueDay: Int64
        let balance: Int64
        let minimum: Int64?
        let paid: Int64
    }

    var dues: [Due] {
        let today = Dates.epochDay(Date())
        var out: [Due] = []
        for (key, list) in Dictionary(grouping: statements, by: { $0.cardKey }) {
            guard let s = list.max(by: { $0.dueEpochDay < $1.dueEpochDay }) else { continue }
            if s.dueEpochDay < today - 7 || s.balanceMinor <= 0 { continue }
            let from = s.statementEpochDay ?? Dates.epochDay(s.receivedAt)
            let paid = txns.filter {
                ($0.cardKey == key && ($0.txnType == .payment || ($0.txnType == .refund && $0.source == "Statement")) ||
                    ($0.counterpartyKey == key)) && Dates.epochDay($0.timestamp) > from
            }.filter { $0.txnType != .purchase }.reduce(Int64(0)) { $0 + max($1.aedMinor, 0) }
            out.append(Due(id: key, label: cards[key]?.label ?? key, dueDay: s.dueEpochDay, balance: s.balanceMinor, minimum: s.minimumDueMinor, paid: paid))
        }
        return out.sorted { $0.dueDay < $1.dueDay }
    }

    var body: some View {
        let list = dues
        if !list.isEmpty {
            Section("Payments due") {
                ForEach(list) { d in
                    let left = d.balance - d.paid
                    let days = d.dueDay - Dates.epochDay(Date())
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.label).font(.subheadline.weight(.semibold))
                            Text("Due \(Dates.dayText(epochDay: d.dueDay))" + (d.minimum.map { " · minimum \(MoneyText.amount($0))" } ?? ""))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(MoneyText.text(d.balance)).font(.subheadline.monospacedDigit())
                            if left <= 0 {
                                Text("Paid").font(.caption.bold()).foregroundStyle(.green)
                            } else if days < 0 {
                                Text("Overdue").font(.caption.bold()).foregroundStyle(.red)
                            } else {
                                Text(days == 0 ? "Due today" : "in \(days) day\(days == 1 ? "" : "s")")
                                    .font(.caption.bold()).foregroundStyle(days <= 3 ? .orange : .secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct ByCategory: View {
    let txns: [Txn]
    let cards: [String: Card]
    let total: Int64

    var body: some View {
        var sums: [Int64: Int64] = [:]
        for t in txns {
            let c = SpendMath.contribution(t, cards: cards)
            if c != 0 { sums[t.categoryId ?? -1, default: 0] += c }
        }
        let rows = sums.filter { $0.value > 0 }.sorted { $0.value > $1.value }
        return Group {
            if !rows.isEmpty {
                Section("By category") {
                    ForEach(rows, id: \.key) { row in
                        let id = row.key
                        let sum = row.value
                        let catId: Int64? = id < 0 ? nil : id
                        NavigationLink {
                            ActivityList(title: Categories.name(catId), filter: { t in
                                (t.categoryId ?? -1) == id && txns.contains(where: { $0.id == t.id })
                            })
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Label(Categories.name(catId), systemImage: Categories.symbol(catId))
                                    Spacer()
                                    Text(MoneyText.amount(sum)).monospacedDigit()
                                }
                                .font(.subheadline)
                                ProgressView(value: Double(sum), total: Double(max(total, sum)))
                                    .tint(.accentColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct TopMerchants: View {
    let txns: [Txn]
    let cards: [String: Card]

    var body: some View {
        var sums: [String: (name: String, sum: Int64, count: Int)] = [:]
        for t in txns {
            let c = SpendMath.contribution(t, cards: cards)
            guard c > 0 else { continue }
            let key = t.merchantKey.isEmpty ? t.merchant.uppercased() : t.merchantKey
            let old = sums[key] ?? (name: t.merchant, sum: 0, count: 0)
            sums[key] = (name: old.name, sum: old.sum + c, count: old.count + 1)
        }
        let top = sums.values.sorted { $0.sum > $1.sum }.prefix(5)
        return Group {
            if !top.isEmpty {
                Section("Top merchants") {
                    ForEach(Array(top.enumerated()), id: \.offset) { item in
                        let m = item.element
                        HStack {
                            VStack(alignment: .leading) {
                                Text(m.name).font(.subheadline).lineLimit(1)
                                Text("\(m.count) time\(m.count == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(MoneyText.amount(m.sum)).font(.subheadline.monospacedDigit())
                        }
                    }
                }
            }
        }
    }
}
