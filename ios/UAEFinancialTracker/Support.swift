import Foundation
import Shared

/// Small settings kept on the phone.
enum Settings {
    private static let defaults = UserDefaults.standard

    /// Currency → AED rate, as text ("3.6725"). Starts with the built-in approximate rates.
    static var rates: [String: String] {
        get { (defaults.dictionary(forKey: "rates") as? [String: String]) ?? Bridge.shared.defaultRates() }
        set { defaults.set(newValue, forKey: "rates") }
    }

    static var onboarded: Bool {
        get { defaults.bool(forKey: "onboarded") }
        set { defaults.set(newValue, forKey: "onboarded") }
    }
}

/// Dates as whole days (the engine counts days since 1 January 1970).
enum Dates {
    static var calendar: Calendar { Calendar.current }

    static func date(epochDay: Int64) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let d = utc.date(byAdding: .day, value: Int(epochDay), to: Date(timeIntervalSince1970: 0))!
        let c = utc.dateComponents([.year, .month, .day], from: d)
        return calendar.date(from: c) ?? d
    }

    static func epochDay(_ date: Date) -> Int64 {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        guard let d = utc.date(from: c) else { return 0 }
        return Int64((d.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "d MMM yyyy"
        return f
    }()

    static let dayShort: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEE d MMM"
        return f
    }()

    static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()

    static func dayText(epochDay: Int64) -> String { day.string(from: date(epochDay: epochDay)) }
}

/// MoneyText as people read it: "AED 1,234.50".
enum MoneyText {
    private static let number: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f.locale = Locale(identifier: "en_US_POSIX")
        f.usesGroupingSeparator = true
        f.groupingSeparator = ","
        f.decimalSeparator = "."
        return f
    }()

    static func amount(_ minor: Int64) -> String {
        number.string(from: NSDecimalNumber(mantissa: UInt64(abs(minor)), exponent: -2, isNegative: minor < 0)) ?? "\(Double(minor) / 100)"
    }

    static func text(_ minor: Int64, _ currency: String = "AED") -> String { "\(currency) \(amount(minor))" }

    /// "123.45" or "1,234" typed by you → fils. Nil when it isn't a number.
    static func parse(_ text: String) -> Int64? {
        let clean = text.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty, let d = Foundation.Decimal(string: clean, locale: Locale(identifier: "en_US_POSIX")), d > 0 else { return nil }
        var scaled = d * 100
        var rounded = Foundation.Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        return NSDecimalNumber(decimal: rounded).int64Value
    }
}

/// The built-in categories (the same as the Android app).
enum Categories {
    static let all: [(id: Int64, name: String)] = Bridge.shared.categories().map { ($0.id, $0.name) }

    static func name(_ id: Int64?) -> String {
        guard let id else { return "No category" }
        return all.first { $0.id == id }?.name ?? "Other"
    }

    static func symbol(_ id: Int64?) -> String {
        switch name(id) {
        case "Groceries": return "cart"
        case "Dining & delivery": return "fork.knife"
        case "Fuel & transport": return "car"
        case "Utilities & bills": return "bolt"
        case "Shopping": return "bag"
        case "Travel": return "airplane"
        case "Health": return "cross.case"
        case "Entertainment": return "film"
        case "Government & fees": return "building.columns"
        case "Education": return "graduationcap"
        case "EMI & loans": return "creditcard"
        case "Cash (ATM)": return "banknote"
        case "Insurance": return "shield"
        case "Salary & income": return "arrow.down.circle"
        case "Family (cards I pay for)": return "person.2"
        default: return "tag"
        }
    }
}
