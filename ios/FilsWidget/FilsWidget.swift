import WidgetKit
import SwiftUI

/// Home-screen widget: spending this month and the next card payment due. The app writes the figures to the shared
/// app group whenever they change.
struct Snapshot: Codable {
    var month: String
    var spentMinor: Int64
    var dueLine: String
    var updated: Date

    static let placeholder = Snapshot(month: "Sep", spentMinor: 341_310, dueLine: "HSBC ·5258: AED 2,379.20 due 1 Oct", updated: Date())

    static func load() -> Snapshot? {
        guard let data = UserDefaults(suiteName: "group.com.uaefinancial.tracker")?.data(forKey: "widgetSnapshot") else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: data)
    }
}

struct Entry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snapshot: .placeholder) }
    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(date: Date(), snapshot: context.isPreview ? .placeholder : Snapshot.load()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let next = Calendar.current.date(byAdding: .hour, value: 3, to: Date()) ?? Date()
        completion(Timeline(entries: [Entry(date: Date(), snapshot: Snapshot.load())], policy: .after(next)))
    }
}

struct FilsWidgetView: View {
    let entry: Entry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let s = entry.snapshot {
                Text("Spent in \(s.month)").font(.caption).foregroundStyle(.white.opacity(0.85))
                Text(amount(s.spentMinor))
                    .font(.system(size: family == .systemSmall ? 24 : 30, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.5).lineLimit(1).foregroundStyle(.white)
                Spacer(minLength: 0)
                Text(s.dueLine).font(.caption2).foregroundStyle(.white.opacity(0.9)).lineLimit(family == .systemSmall ? 3 : 2)
            } else {
                Text("Fils").font(.headline).foregroundStyle(.white)
                Text("Open the app to see your spending here.").font(.caption).foregroundStyle(.white.opacity(0.9))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(for: .widget) {
            LinearGradient(colors: [Color(red: 0.43, green: 0.16, blue: 0.85), Color(red: 0.86, green: 0.15, blue: 0.47)], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    private func amount(_ minor: Int64) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        f.locale = Locale(identifier: "en_US_POSIX")
        f.groupingSeparator = ","
        f.usesGroupingSeparator = true
        return "AED " + (f.string(from: NSNumber(value: Double(minor) / 100)) ?? "0")
    }
}

@main
struct FilsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "FilsSpending", provider: Provider()) { entry in
            FilsWidgetView(entry: entry)
        }
        .configurationDisplayName("Spending")
        .description("This month's spending and the next card payment due.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
