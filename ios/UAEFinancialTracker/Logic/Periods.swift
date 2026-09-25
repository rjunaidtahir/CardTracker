import Foundation

/// The period chips (the same as Android): a calendar month with arrows, rolling windows ending today, everything,
/// or your own range. Days are epoch days, both ends inclusive; nil only for All.
struct Period: Equatable, Hashable {
    enum Kind: String, CaseIterable, Identifiable {
        case month = "Month", w1 = "1W", m1 = "1M", m3 = "3M", m6 = "6M", m12 = "12M", all = "All", custom = "Custom"
        var id: String { rawValue }
    }

    var kind: Kind
    var start: Int64?
    var end: Int64?

    var isBounded: Bool { start != nil && end != nil }

    var days: Int64? {
        guard let s = start, let e = end else { return nil }
        return e - s + 1
    }

    func contains(_ day: Int64) -> Bool { (start.map { day >= $0 } ?? true) && (end.map { day <= $0 } ?? true) }

    func contains(date: Date) -> Bool { contains(Dates.epochDay(date)) }

    /// The period just before this one (the previous calendar month for Month). Nil for All.
    func previous() -> Period? { shift(-1) }

    /// Moves by the period's own length. All can't move.
    func shift(_ steps: Int) -> Period? {
        guard let s = start, let e = end else { return nil }
        if kind == .month {
            let m = Dates.plusMonths(s, steps)
            let p = Dates.parts(m)
            return Period.month(year: p.year, month: p.month)
        }
        let len = e - s + 1
        return Period(kind: kind, start: s + len * Int64(steps), end: e + len * Int64(steps))
    }

    /// "September 2026", "17 – 23 Sep 2026", "1 Jul – 23 Sep 2026", "1 Jul 2025 – 23 Sep 2026", "All time".
    var label: String {
        guard let s = start, let e = end else { return "All time" }
        if kind == .month { return Dates.month.string(from: Dates.date(epochDay: s)) }
        let a = Dates.parts(s), b = Dates.parts(e)
        let endText = Dates.day.string(from: Dates.date(epochDay: e))
        if s == e { return endText }
        if a.year == b.year && a.month == b.month { return "\(a.day) – \(endText)" }
        if a.year == b.year { return "\(Dates.dayMonth.string(from: Dates.date(epochDay: s))) – \(endText)" }
        return "\(Dates.day.string(from: Dates.date(epochDay: s))) – \(endText)"
    }

    static func month(year: Int, month: Int) -> Period {
        let s = Dates.epochDay(year: year, month: month, day: 1)
        return Period(kind: .month, start: s, end: s + Int64(Dates.daysInMonth(year: year, month: month)) - 1)
    }

    static func thisMonth(today: Int64 = Dates.today()) -> Period {
        let p = Dates.parts(today)
        return month(year: p.year, month: p.month)
    }

    static let all = Period(kind: .all, start: nil, end: nil)

    static func custom(_ a: Int64, _ b: Int64) -> Period {
        Period(kind: .custom, start: min(a, b), end: max(a, b))
    }

    /// The period for a chip, relative to today. Custom needs dates, so it falls back to this month.
    static func of(_ kind: Kind, today: Int64 = Dates.today()) -> Period {
        switch kind {
        case .month, .custom: return thisMonth(today: today)
        case .w1: return Period(kind: kind, start: today - 6, end: today)
        case .m1: return Period(kind: kind, start: Dates.plusMonths(today, -1) + 1, end: today)
        case .m3: return Period(kind: kind, start: Dates.plusMonths(today, -3) + 1, end: today)
        case .m6: return Period(kind: kind, start: Dates.plusMonths(today, -6) + 1, end: today)
        case .m12: return Period(kind: kind, start: Dates.plusMonths(today, -12) + 1, end: today)
        case .all: return .all
        }
    }
}

/// How a timeline chart groups days: days up to a month, weeks up to about 4 months, months beyond that.
enum Bucket {
    case day, week, month

    static func of(start: Int64, end: Int64) -> Bucket {
        let days = end - start + 1
        if days <= 31 { return .day }
        if days <= 120 { return .week }
        return .month
    }
}

struct TimePoint: Identifiable {
    let start: Int64
    let amountMinor: Int64
    var id: Int64 { start }
}
