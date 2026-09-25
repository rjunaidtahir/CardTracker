import SwiftUI
import Charts

enum SpendMath {
    @MainActor
    static func cardMap(_ cards: [Card]) -> [String: Card] {
        Dictionary(cards.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
    }

    @MainActor
    static func excluded(_ cards: [Card]) -> Set<String> { Set(cards.filter { !$0.counted }.map(\.key)) }
}

// MARK: - Period selector

/// The period chips shared by Home, Activity and Cards, with arrows to step back and forward.
struct PeriodBar: View {
    @Binding var period: Period
    @State private var customOpen = false
    @State private var from = Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date()
    @State private var to = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Period.Kind.allCases) { k in
                        let selected = period.kind == k
                        Button {
                            if k == .custom { customOpen = true } else { period = Period.of(k) }
                        } label: {
                            Text(k.rawValue)
                                .font(.subheadline.weight(selected ? .semibold : .regular))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(selected ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.1), in: Capsule())
                                .foregroundStyle(selected ? Color.accentColor : .primary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack {
                Button { if let p = period.shift(-1) { period = p } } label: { Image(systemName: "chevron.left") }
                    .disabled(!period.isBounded)
                Spacer()
                Text(period.label).font(.subheadline.weight(.semibold))
                Spacer()
                Button { if let p = period.shift(1) { period = p } } label: { Image(systemName: "chevron.right") }
                    .disabled(!period.isBounded || (period.end ?? 0) >= Dates.today())
            }
            .buttonStyle(.borderless)
        }
        .sheet(isPresented: $customOpen) {
            NavigationStack {
                Form {
                    DatePicker("From", selection: $from, in: ...Date(), displayedComponents: .date)
                    DatePicker("To", selection: $to, in: ...Date(), displayedComponents: .date)
                }
                .navigationTitle("Your own dates")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { customOpen = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Show") {
                            period = Period.custom(Dates.epochDay(from), Dates.epochDay(to))
                            customOpen = false
                        }
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }
}

// MARK: - Small pieces

struct CategoryIcon: View {
    let categoryId: Int64?
    var size: CGFloat = 32

    var body: some View {
        Image(systemName: Categories.symbol(categoryId))
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(Categories.color(categoryId))
            .frame(width: size, height: size)
            .background(Categories.color(categoryId).opacity(0.15), in: Circle())
    }
}

struct BankBadge: View {
    let bank: String
    var body: some View {
        let (label, color) = CardArt.badge(bank)
        Text(label)
            .font(.caption2.weight(.heavy))
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 5))
            .foregroundStyle(color)
    }
}

/// A figure with a small caption above it.
struct Stat: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            Text(value).font(.subheadline.weight(.semibold).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A thin progress bar with a colour for the level.
struct LevelBar: View {
    let fraction: Double
    var color: Color = .accentColor
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.15))
                Capsule().fill(color).frame(width: max(4, g.size.width * min(1, max(0, fraction))))
            }
        }
        .frame(height: 6)
    }
}

func levelColor(_ percent: Int, warn: Int = 80, over: Int = 100) -> Color {
    percent >= over ? Palette.red : percent >= warn ? Palette.amber : Palette.green
}

/// Utilisation colours: under 30% green, under 70% amber, otherwise red.
func utilisationColor(_ percent: Int) -> Color {
    percent < 30 ? Palette.green : percent < 70 ? Palette.amber : Palette.red
}

// MARK: - Charts

/// Spending by category as a donut; tap a slice to see its figure.
struct DonutChart: View {
    let slices: [(categoryId: Int64?, amount: Int64)]
    let total: Int64
    @Binding var selected: Int64??

    var body: some View {
        let sel: (categoryId: Int64?, amount: Int64)? = selected.flatMap { s in slices.first { $0.categoryId == s } }
        Chart(Array(slices.enumerated()), id: \.offset) { item in
            SectorMark(angle: .value("Amount", item.element.amount), innerRadius: .ratio(0.62), angularInset: 1.5)
                .cornerRadius(3)
                .foregroundStyle(Categories.color(item.element.categoryId))
                .opacity(sel == nil || sel?.categoryId == item.element.categoryId ? 1 : 0.35)
        }
        .chartAngleSelection(value: Binding<Int64?>(
            get: { nil },
            set: { v in
                guard let v else { return }
                var acc: Int64 = 0
                for s in slices {
                    acc += s.amount
                    if v <= acc { selected = .some(s.categoryId); break }
                }
            }
        ))
        .chartBackground { _ in
            VStack(spacing: 2) {
                if let sel {
                    Text(Categories.name(sel.categoryId)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(MoneyText.compact(sel.amount)).font(.headline.monospacedDigit())
                    Text(total > 0 ? "\(sel.amount * 100 / total)% of spend" : "").font(.caption2).foregroundStyle(.secondary)
                } else {
                    Text("Total").font(.caption).foregroundStyle(.secondary)
                    Text(MoneyText.compact(total)).font(.headline.monospacedDigit())
                    Text(slices.isEmpty ? "No spending" : "\(slices.count) categories").font(.caption2).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: 120)
        }
        .frame(height: 210)
    }
}

/// Spending over time: daily, weekly or monthly.
struct TrendChart: View {
    let points: [TimePoint]
    let bucket: Bucket
    @State private var selectedDate: Date?

    var body: some View {
        let avg = points.isEmpty ? 0 : points.reduce(Int64(0)) { $0 + $1.amountMinor } / Int64(points.count)
        let sel = selectedDate.flatMap { d in points.min { abs(Dates.date(epochDay: $0.start).timeIntervalSince(d)) < abs(Dates.date(epochDay: $1.start).timeIntervalSince(d)) } }
        VStack(alignment: .leading, spacing: 6) {
            if let sel {
                Text("\(label(sel.start)): \(MoneyText.text(sel.amountMinor))").font(.subheadline.weight(.semibold))
            } else {
                Text("\(bucket == .day ? "Daily" : bucket == .week ? "Weekly" : "Monthly") spend · avg \(MoneyText.compact(avg))")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            Chart(points) { p in
                AreaMark(x: .value("Date", Dates.date(epochDay: p.start)), y: .value("AED", Double(p.amountMinor) / 100))
                    .foregroundStyle(LinearGradient(colors: [Color.accentColor.opacity(0.35), Color.accentColor.opacity(0.02)], startPoint: .top, endPoint: .bottom))
                    .interpolationMethod(.monotone)
                LineMark(x: .value("Date", Dates.date(epochDay: p.start)), y: .value("AED", Double(p.amountMinor) / 100))
                    .foregroundStyle(Color.accentColor)
                    .interpolationMethod(.monotone)
                if let sel, sel.start == p.start {
                    PointMark(x: .value("Date", Dates.date(epochDay: p.start)), y: .value("AED", Double(p.amountMinor) / 100))
                        .foregroundStyle(Color.accentColor)
                }
            }
            .chartXSelection(value: $selectedDate)
            .chartYAxis { AxisMarks(position: .leading) }
            .frame(height: 180)
        }
    }

    private func label(_ day: Int64) -> String {
        let d = Dates.date(epochDay: day)
        switch bucket {
        case .day: return Dates.dayShort.string(from: d)
        case .week: return "Week of \(Dates.dayMonth.string(from: d))"
        case .month: return Dates.month.string(from: d)
        }
    }
}

/// The last 12 months as bars; tap a month to open it.
struct MonthBars: View {
    let months: [(ym: String, start: Int64, amount: Int64)]
    let selectedYm: String?
    let onPick: (Int64) -> Void

    var body: some View {
        Chart(months, id: \.ym) { m in
            BarMark(x: .value("Month", Dates.monthShort.string(from: Dates.date(epochDay: m.start))), y: .value("AED", Double(m.amount) / 100))
                .foregroundStyle(m.ym == selectedYm ? Color.accentColor : Color.accentColor.opacity(0.35))
                .cornerRadius(4)
        }
        .chartXSelection(value: Binding<String?>(
            get: { nil },
            set: { v in if let v, let m = months.first(where: { Dates.monthShort.string(from: Dates.date(epochDay: $0.start)) == v }) { onPick(m.start) } }
        ))
        .chartYAxis(.hidden)
        .frame(height: 150)
    }
}

/// Share sheet for a file.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// Writes data to a temporary file with a proper name, for sharing.
func temporaryFile(_ name: String, _ data: Data) -> URL? {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    do {
        try data.write(to: url)
        return url
    } catch {
        return nil
    }
}
