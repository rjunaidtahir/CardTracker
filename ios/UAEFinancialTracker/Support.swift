import Foundation
import SwiftUI
import Shared

/// App name as shown to people.
enum AppInfo {
    static let name = "Fils"
    static let fullName = "Fils - Cards & Spend Tracker"
    /// Shared with the widget and the Share extension.
    static let appGroup = "group.com.uaefinancial.tracker"
    /// The ready-made Shortcuts automation (iOS 27 and later): "When I receive a message containing AED → Add Bank
    /// Message to Fils". Opening it adds the shortcut in the Shortcuts app.
    static let automationShortcut = URL(string: "https://www.icloud.com/shortcuts/53d0ba37867348a78566067761e526a5")!
    /// Shortcuts can share automations from iOS 27.
    static var canInstallSharedAutomation: Bool {
        // "-sharedAutomation YES" at launch shows the iOS 27 screen on an older simulator (screenshots only).
        ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 27 || UserDefaults.standard.bool(forKey: "sharedAutomation")
    }
    /// "1.0 (3)", from the build's Info.plist.
    static var version: String {
        let short = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(short) (\(build))"
    }
}

/// Whether the Shortcuts automation is handing bank messages to the app.
enum AutomationStatus: Equatable {
    /// Never ran.
    case off
    /// You opened the ready-made shortcut, but no message has come through yet.
    case waiting
    /// Ran; [last] is the last time it passed a message.
    case on(last: Date)

    var isOn: Bool {
        if case .on = self { return true }
        return false
    }

    static var current: AutomationStatus {
        if let last = Settings.automationLastRun { return .on(last: last) }
        if Settings.automationLinkOpenedAt != nil { return .waiting }
        return .off
    }
}

/// Small settings kept on the phone.
enum Settings {
    private static var defaults: UserDefaults { UserDefaults.standard }

    /// Currency → AED rate, as text ("3.6725"). Starts with the built-in approximate rates.
    static var rates: [String: String] {
        get { (defaults.dictionary(forKey: "rates") as? [String: String]) ?? Bridge.shared.defaultRates() }
        set { defaults.set(newValue, forKey: "rates") }
    }

    /// The last time the Shortcuts automation passed a message to the app (any message, bank or not).
    static var automationLastRun: Date? {
        get { defaults.object(forKey: "automationLastRun") as? Date }
        set { defaults.set(newValue, forKey: "automationLastRun") }
    }

    /// When you opened the ready-made automation from the app.
    static var automationLinkOpenedAt: Date? {
        get { defaults.object(forKey: "automationLinkOpenedAt") as? Date }
        set { defaults.set(newValue, forKey: "automationLinkOpenedAt") }
    }

    /// "Not now" on Home's automatic-import card.
    static var automationCardDismissed: Bool {
        get { defaults.bool(forKey: "automationCardDismissed") }
        set { defaults.set(newValue, forKey: "automationCardDismissed") }
    }

    /// Version of the stored data's layout; raising it makes the app re-read everything once after an update.
    static var dataVersion: Int {
        get { defaults.integer(forKey: "dataVersion") }
        set { defaults.set(newValue, forKey: "dataVersion") }
    }

    static var onboarded: Bool {
        get { defaults.bool(forKey: "onboarded") }
        set { defaults.set(newValue, forKey: "onboarded") }
    }

    /// Shown on reports.
    static var displayName: String {
        get { defaults.string(forKey: "displayName") ?? "" }
        set { defaults.set(newValue, forKey: "displayName") }
    }

    static var themeId: String {
        get { defaults.string(forKey: "themeId") ?? "system" }
        set { defaults.set(newValue, forKey: "themeId") }
    }

    // Notifications
    static var remindersEnabled: Bool {
        get { defaults.bool(forKey: "remindersEnabled") }
        set { defaults.set(newValue, forKey: "remindersEnabled") }
    }
    static var alertsEnabled: Bool {
        get { defaults.bool(forKey: "alertsEnabled") }
        set { defaults.set(newValue, forKey: "alertsEnabled") }
    }
    static var bigSpendMinor: Int64 {
        get { (defaults.object(forKey: "bigSpendMinor") as? NSNumber)?.int64Value ?? 100_000 }
        set { defaults.set(NSNumber(value: newValue), forKey: "bigSpendMinor") }
    }
    static var lowAccountMinor: Int64 {
        get { (defaults.object(forKey: "lowAccountMinor") as? NSNumber)?.int64Value ?? 200_000 }
        set { defaults.set(NSNumber(value: newValue), forKey: "lowAccountMinor") }
    }
    static var lowCardMinor: Int64 {
        get { (defaults.object(forKey: "lowCardMinor") as? NSNumber)?.int64Value ?? 100_000 }
        set { defaults.set(NSNumber(value: newValue), forKey: "lowCardMinor") }
    }
    static var budgetAlerts: Bool {
        get { defaults.object(forKey: "budgetAlerts") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "budgetAlerts") }
    }
    /// Alerts already sent (tags), so each is sent once.
    static var sentAlerts: Set<String> {
        get { Set(defaults.stringArray(forKey: "sentAlerts") ?? []) }
        set { defaults.set(Array(newValue.suffix(400)), forKey: "sentAlerts") }
    }

    // App lock
    static var lockEnabled: Bool {
        get { defaults.bool(forKey: "lockEnabled") }
        set { defaults.set(newValue, forKey: "lockEnabled") }
    }
    static var pinSalt: String? {
        get { defaults.string(forKey: "pinSalt") }
        set { defaults.set(newValue, forKey: "pinSalt") }
    }
    static var pinHash: String? {
        get { defaults.string(forKey: "pinHash") }
        set { defaults.set(newValue, forKey: "pinHash") }
    }
    static var biometricEnabled: Bool {
        get { defaults.object(forKey: "biometricEnabled") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "biometricEnabled") }
    }
    /// Seconds in the background before the app locks again.
    static var lockTimeout: Int {
        get { defaults.object(forKey: "lockTimeout") as? Int ?? 60 }
        set { defaults.set(newValue, forKey: "lockTimeout") }
    }
}

/// Dates as whole days (the engine counts days since 1 January 1970).
enum Dates {
    static var calendar: Calendar { Calendar.current }

    private static let utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    /// Local midnight of an epoch day.
    static func date(epochDay: Int64) -> Date {
        let d = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let c = utc.dateComponents([.year, .month, .day], from: d)
        return calendar.date(from: c) ?? d
    }

    static func epochDay(_ date: Date) -> Int64 {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        guard let d = utc.date(from: c) else { return 0 }
        return Int64((d.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    static func epochDay(year: Int, month: Int, day: Int) -> Int64 {
        guard let d = utc.date(from: DateComponents(year: year, month: month, day: day)) else { return 0 }
        return Int64((d.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    static func today() -> Int64 { epochDay(Date()) }

    /// (year, month, day) of an epoch day.
    static func parts(_ epochDay: Int64) -> (year: Int, month: Int, day: Int) {
        let c = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(epochDay) * 86_400))
        return (c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    static func daysInMonth(year: Int, month: Int) -> Int {
        let d = utc.date(from: DateComponents(year: year, month: month, day: 1))!
        return utc.range(of: .day, in: .month, for: d)?.count ?? 30
    }

    /// Adds months, keeping the day where possible (31 Jan + 1 month = 28/29 Feb).
    static func plusMonths(_ epochDay: Int64, _ months: Int) -> Int64 {
        let p = parts(epochDay)
        let total = p.year * 12 + (p.month - 1) + months
        let y = total / 12, m = total % 12 + 1
        return Self.epochDay(year: y, month: m, day: min(p.day, daysInMonth(year: y, month: m)))
    }

    private static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_GB")
        f.dateFormat = pattern
        return f
    }

    static let day = formatter("d MMM yyyy")
    static let dayShort = formatter("EEE d MMM")
    static let dayMonth = formatter("d MMM")
    static let month = formatter("MMMM yyyy")
    static let monthShort = formatter("MMM")
    static let time = formatter("HH:mm")
    static let dayTime = formatter("d MMM, HH:mm")

    static func dayText(epochDay: Int64) -> String { day.string(from: date(epochDay: epochDay)) }

    /// "Today", "Yesterday" or "Mon, 21 Sep 2026".
    static func dayHeader(_ date: Date) -> String {
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return formatter("EEE, d MMM yyyy").string(from: date)
    }

    /// "2026-09"
    static func ym(_ epochDay: Int64) -> String {
        let p = parts(epochDay)
        return String(format: "%04d-%02d", p.year, p.month)
    }
}

/// Money as people read it: "AED 1,234.50".
enum MoneyText {
    private static let number: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f.locale = Locale(identifier: "en_US_POSIX")
        f.usesGroupingSeparator = true
        f.groupingSeparator = ","
        f.groupingSize = 3
        f.decimalSeparator = "."
        return f
    }()

    static func amount(_ minor: Int64) -> String {
        number.string(from: NSDecimalNumber(mantissa: UInt64(abs(minor)), exponent: -2, isNegative: minor < 0)) ?? "\(Double(minor) / 100)"
    }

    static func text(_ minor: Int64, _ currency: String = "AED") -> String { "\(currency) \(amount(minor))" }

    /// "AED 3,413", "AED 125.4K", "AED 1.2M".
    static func compact(_ minor: Int64) -> String {
        let v = Double(minor) / 100
        if abs(v) >= 1_000_000 { return String(format: "AED %.1fM", v / 1_000_000) }
        if abs(v) >= 100_000 { return String(format: "AED %.1fK", v / 1_000) }
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        f.locale = Locale(identifier: "en_US_POSIX")
        f.groupingSeparator = ","
        f.usesGroupingSeparator = true
        return "AED " + (f.string(from: NSNumber(value: v.rounded())) ?? "\(Int(v))")
    }

    /// For text fields: "1234.5" (no grouping).
    static func plain(_ minor: Int64) -> String { amount(minor).replacingOccurrences(of: ",", with: "") }

    /// "123.45" or "1,234" typed by you → fils. Nil when it isn't a positive number.
    static func parse(_ text: String) -> Int64? {
        guard let v = parseSigned(text), v > 0 else { return nil }
        return v
    }

    /// Like parse, but negative numbers are allowed ("-200" to take money out of a goal).
    static func parseSigned(_ text: String) -> Int64? {
        let clean = text.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty, let d = Foundation.Decimal(string: clean, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        var scaled = d * 100
        var rounded = Foundation.Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        return NSDecimalNumber(decimal: rounded).int64Value
    }
}

/// Categories: the built-in ones (the same as Android) plus yours.
@MainActor
enum Categories {
    static let builtIn: [(id: Int64, name: String)] = Bridge.shared.categories().map { ($0.id, $0.name) }
    /// Your own categories, loaded by the engine.
    static var custom: [(id: Int64, name: String)] = []

    static var all: [(id: Int64, name: String)] {
        // Built-in spending categories, then yours, then income and family.
        let spend = builtIn.filter { $0.id < 100 }
        let special = builtIn.filter { $0.id >= 100 }
        return spend + custom + special
    }

    static func name(_ id: Int64?) -> String {
        guard let id else { return "Uncategorised" }
        return all.first { $0.id == id }?.name ?? "Other"
    }

    static let familyId: Int64 = Bridge.shared.familyCategoryId()
    static let incomeId: Int64 = Bridge.shared.incomeCategoryId()

    static func symbol(_ id: Int64?) -> String {
        switch id {
        case 1: return "cart.fill"
        case 2: return "fork.knife"
        case 3: return "fuelpump.fill"
        case 4: return "lightbulb.fill"
        case 5: return "bag.fill"
        case 6: return "airplane"
        case 7: return "cross.case.fill"
        case 8: return "film.fill"
        case 9: return "building.columns.fill"
        case 10: return "graduationcap.fill"
        case 11: return "banknote.fill"
        case 12: return "dollarsign.circle.fill"
        case 13: return "shield.fill"
        case 14: return "square.grid.2x2.fill"
        case 101: return "arrow.down.circle.fill"
        case 102: return "person.2.fill"
        case nil: return "questionmark.circle"
        default: return "tag.fill"
        }
    }

    /// One fixed colour per category, the same as on Android.
    static func color(_ id: Int64?) -> Color {
        let fixed: [Int64: UInt32] = [
            1: 0x199E70, 2: 0xD95926, 3: 0xC98500, 4: 0x3987E5, 5: 0xD55181, 6: 0x9085E9, 7: 0xE66767, 8: 0x2FA84F,
            9: 0x7D8BA3, 10: 0x3FB2C0, 11: 0xB07CC6, 12: 0x9CA83A, 13: 0x5C7CFA, 14: 0x8A8F98, 101: 0x22C55E, 102: 0xEC4899,
        ]
        let extra: [UInt32] = [0x3987E5, 0xD95926, 0x199E70, 0xC98500, 0xD55181, 0x9085E9, 0xE66767]
        guard let id else { return Color(hex: 0x6B7280) }
        if let c = fixed[id] { return Color(hex: c) }
        let i = Int(((id % Int64(extra.count)) + Int64(extra.count)) % Int64(extra.count))
        return Color(hex: extra[i])
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}
