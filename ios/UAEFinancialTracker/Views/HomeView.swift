import SwiftUI
import SwiftData

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var cards: [Card]
    @Query private var statements: [StatementRecord]
    @Query(filter: #Predicate<SmsRecord> { $0.status == "failed" }) private var toReview: [SmsRecord]
    @Query private var budgets: [Budget]
    @Query(sort: \FixedPayment.dayOfMonth) private var fixed: [FixedPayment]
    @Query(sort: \Goal.createdAt) private var goals: [Goal]
    @AppStorage("homeChart") private var chartKind = "donut"
    @State private var selectedSlice: Int64??
    @State private var editingBudgets = false
    @State private var exporting = false
    @State private var goalEditor: GoalEditor.Target?
    @State private var addingMoney: Goal?

    var body: some View {
        @Bindable var model = model
        let views = txns.map(TxnView.init)
        let cardMap = SpendMath.cardMap(cards)
        let excluded = SpendMath.excluded(cards)
        let period = model.period
        let today = Dates.today()
        let inPeriod = views.filter { period.contains($0.day) }
        let spent = SpendRules.total(inPeriod, excluded: excluded)
        let prev = period.previous()
        let prevSpent = prev.map { p in SpendRules.total(views.filter { p.contains($0.day) }, excluded: excluded) }
        let firstDay = period.start ?? (views.map(\.day).min() ?? today)
        let lastDay = min(period.end ?? today, today)
        let perDay = spent / max(1, lastDay - firstDay + 1)
        let spends = inPeriod.filter { SpendRules.counted($0, excluded: excluded) && ($0.type == .purchase || $0.type == .refund) }.count
        let moneyIn = inPeriod.filter { $0.type == .transferIn }.reduce(Int64(0)) { $0 + ($1.aed ?? 0) }
        let byCategory = Insights.byCategory(inPeriod, excluded: excluded)
        let dues = CardDues.compute(cards: cards, statements: statements, txns: views.filter { $0.day >= today - 400 }, today: today)

        NavigationStack {
            List {
                if txns.isEmpty && cards.isEmpty {
                    Section { WelcomePanel() }
                }
                if !toReview.isEmpty {
                    Section {
                        NavigationLink { ReviewView() } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Label("\(toReview.count) bank message\(toReview.count == 1 ? "" : "s") need a look", systemImage: "exclamationmark.bubble.fill")
                                    .foregroundStyle(Palette.amber).font(.subheadline.weight(.semibold))
                                Text("The app couldn't read them. Tap to fix or dismiss.").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("TOTAL SPENT").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        Text(MoneyText.text(spent))
                            .font(.system(size: 36, weight: .bold, design: .rounded))
                            .minimumScaleFactor(0.5).lineLimit(1)
                        if let prev, let ps = prevSpent, ps > 0 {
                            let pct = abs(spent - ps) * 100 / ps
                            let up = spent >= ps
                            Label("\(pct)% \(up ? "more" : "less") than \(prev.label) · \(MoneyText.text(ps))", systemImage: up ? "arrow.up.right" : "arrow.down.right")
                                .font(.footnote).foregroundStyle(up ? Palette.red : Palette.green)
                        }
                        HStack {
                            Stat(title: "Per day", value: MoneyText.compact(perDay))
                            Stat(title: "Spends", value: "\(spends)")
                            Stat(title: "Money in", value: MoneyText.compact(moneyIn))
                        }
                        Divider()
                        PeriodBar(period: $model.period)
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    Picker("Chart", selection: $chartKind) {
                        Image(systemName: "chart.pie").tag("donut")
                        Image(systemName: "chart.xyaxis.line").tag("trend")
                    }
                    .pickerStyle(.segmented)
                    if chartKind == "donut" {
                        DonutChart(slices: byCategory, total: spent, selected: $selectedSlice)
                        if let sel = selectedSlice {
                            HStack {
                                Button("See \(Categories.name(sel)) transactions") { model.showActivity(category: .some(sel)) }
                                Spacer()
                                Button("Show all") { selectedSlice = nil }.foregroundStyle(.secondary)
                            }
                            .buttonStyle(.borderless).font(.subheadline)
                        }
                    } else if let s = period.start ?? views.map(\.day).min() {
                        let e = min(period.end ?? today, today)
                        TrendChart(points: Insights.timeline(views, excluded: excluded, start: s, end: max(s, e)), bucket: Bucket.of(start: s, end: max(s, e)))
                    }
                } header: {
                    Text(chartKind == "donut" ? "By category" : "Over time")
                }
                if !byCategory.isEmpty {
                    Section {
                        let top = byCategory.first?.amount ?? 1
                        ForEach(byCategory, id: \.categoryId) { c in
                            Button { model.showActivity(category: .some(c.categoryId)) } label: {
                                HStack(spacing: 12) {
                                    CategoryIcon(categoryId: c.categoryId)
                                    VStack(alignment: .leading, spacing: 5) {
                                        HStack {
                                            Text(Categories.name(c.categoryId)).font(.subheadline)
                                            Spacer()
                                            Text(MoneyText.amount(c.amount)).font(.subheadline.monospacedDigit())
                                        }
                                        HStack(spacing: 8) {
                                            LevelBar(fraction: Double(c.amount) / Double(max(top, 1)), color: Categories.color(c.categoryId))
                                            Text(spent > 0 ? "\(c.amount * 100 / spent)%" : "").font(.caption2.monospacedDigit()).foregroundStyle(.secondary).frame(width: 34, alignment: .trailing)
                                        }
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                BudgetsSection(budgets: budgets, views: views, excluded: excluded, onEdit: { editingBudgets = true })
                FixedPaymentsSection(fixed: fixed, views: views)
                PaymentsDueSection(dues: dues.filter { d in (cardMap[d.cardKey]?.counted ?? false) && d.status.needsAttention }, anyStatements: !statements.isEmpty, fixed: fixed, views: views)
                Section("Last 12 months") {
                    let months = Insights.byMonth(views, excluded: excluded, endDay: today)
                    let selYm = period.kind == .month ? period.start.map { Dates.ym($0) } : nil
                    if let ym = selYm, let m = months.first(where: { $0.ym == ym }) {
                        Text("\(Dates.month.string(from: Dates.date(epochDay: m.start))): \(MoneyText.text(m.amount))").font(.subheadline.weight(.semibold))
                    } else {
                        Text("Tap a month to open it").font(.subheadline).foregroundStyle(.secondary)
                    }
                    MonthBars(months: months, selectedYm: selYm) { start in
                        let p = Dates.parts(start)
                        model.period = .month(year: p.year, month: p.month)
                    }
                }
                let merchants = Insights.topMerchants(inPeriod, excluded: excluded)
                if !merchants.isEmpty {
                    Section("Top merchants") {
                        ForEach(Array(merchants.enumerated()), id: \.offset) { item in
                            let m = item.element
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(m.name).font(.subheadline).lineLimit(1)
                                    Text("\(m.count) time\(m.count == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(MoneyText.amount(m.amount)).font(.subheadline.monospacedDigit())
                            }
                        }
                    }
                }
                let byCard = Insights.byCard(inPeriod, excluded: excluded)
                if !byCard.isEmpty {
                    Section("By card") {
                        ForEach(byCard, id: \.cardKey) { c in
                            if c.cardKey.isEmpty {
                                LabeledContent("Typed entries", value: MoneyText.amount(c.amount))
                            } else {
                                Button { model.showActivity(card: c.cardKey) } label: {
                                    LabeledContent(cardMap[c.cardKey]?.label ?? c.cardKey, value: MoneyText.amount(c.amount))
                                }
                                .foregroundStyle(.primary)
                            }
                        }
                    }
                }
                RecurringSection(views: views, cards: cards, today: today)
                let foreign = Insights.byCurrency(inPeriod, excluded: excluded)
                if !foreign.isEmpty {
                    Section("Foreign currency (in AED)") {
                        ForEach(foreign) { f in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("\(f.currency) \(MoneyText.amount(f.originalMinor))").font(.subheadline)
                                    Text("\(f.count) transaction\(f.count == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("≈ \(MoneyText.text(f.aedMinor))").font(.subheadline.monospacedDigit())
                            }
                        }
                    }
                }
                GoalsSection(goals: goals, onAdd: { goalEditor = .new }, onEdit: { goalEditor = .edit($0) }, onAddMoney: { addingMoney = $0 })
            }
            .themedScreen()
            .navigationTitle(AppInfo.name)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { exporting = true } label: { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Export report")
                }
            }
            .sheet(isPresented: $editingBudgets) { BudgetsEditor(budgets: budgets) }
            .sheet(isPresented: $exporting) { ExportReportView(period: model.period) }
            .sheet(item: $goalEditor) { GoalEditor(target: $0) }
            .sheet(item: $addingMoney) { AddMoneyView(goal: $0) }
        }
    }
}

private struct WelcomePanel: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Add your bank messages").font(.headline)
            Text("iPhone apps can't read your SMS on their own. Set up the one-minute Shortcuts automation, and each bank SMS is added as it arrives. You can also paste messages, add screenshots, or import a file.")
                .font(.subheadline).foregroundStyle(.secondary)
            NavigationLink { AutomationGuideView() } label: { Label("Set up automatic import", systemImage: "wand.and.stars") }
                .buttonStyle(.borderedProminent)
            HStack {
                NavigationLink { PasteView() } label: { Label("Paste", systemImage: "doc.on.clipboard") }
                NavigationLink { ScreenshotImportView() } label: { Label("Screenshots", systemImage: "photo.on.rectangle") }
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 6)
    }
}

// MARK: - Budgets

private struct BudgetsSection: View {
    let budgets: [Budget]
    let views: [TxnView]
    let excluded: Set<String>
    let onEdit: () -> Void

    var body: some View {
        let month = Period.thisMonth()
        var spent: [Int64?: Int64] = [:]
        for c in Insights.byCategory(views.filter { month.contains($0.day) }, excluded: excluded) { spent[c.categoryId] = c.amount }
        let status = Budgets.status(limits: Dictionary(budgets.map { ($0.categoryId, $0.limitMinor) }, uniquingKeysWith: { a, _ in a }), spent: spent)
        return Section {
            if status.isEmpty {
                Text("Set a monthly limit for any category and get a warning at 80% and 100%.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(status) { b in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        CategoryIcon(categoryId: b.categoryId, size: 26)
                        Text(Categories.name(b.categoryId)).font(.subheadline)
                        Spacer()
                        Text("\(b.percent)%").font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(levelColor(b.percent))
                    }
                    LevelBar(fraction: Double(b.percent) / 100, color: levelColor(b.percent))
                    Text("\(MoneyText.amount(b.spentMinor)) of \(MoneyText.amount(b.limitMinor)) · " +
                         (b.remainingMinor >= 0 ? "\(MoneyText.amount(b.remainingMinor)) left" : "\(MoneyText.amount(-b.remainingMinor)) over"))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        } header: {
            HStack {
                Text("Budgets · \(Dates.month.string(from: Date()))")
                Spacer()
                Button(status.isEmpty ? "Set budgets" : "Edit", action: onEdit).font(.caption.weight(.semibold)).textCase(nil)
            }
        }
    }
}

struct BudgetsEditor: View {
    let budgets: [Budget]
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var texts: [Int64: String] = [:]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(Categories.all.filter { $0.id != Categories.incomeId }, id: \.id) { c in
                        HStack {
                            CategoryIcon(categoryId: c.id, size: 26)
                            Text(c.name)
                            Spacer()
                            TextField("No limit", text: Binding(get: { texts[c.id] ?? "" }, set: { texts[c.id] = $0 }))
                                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 110)
                        }
                    }
                } footer: {
                    Text("Monthly limits in AED. Leave empty for no budget. You get a warning at 80% and when a budget is used up (switch alerts on in More → Notifications).")
                }
            }
            .navigationTitle("Monthly budgets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var limits: [Int64: Int64?] = [:]
                        for c in Categories.all { limits[c.id] = texts[c.id].flatMap { MoneyText.parse($0) } }
                        model.engine.setBudgets(limits)
                        dismiss()
                    }
                }
            }
            .onAppear {
                for b in budgets { texts[b.categoryId] = MoneyText.plain(b.limitMinor) }
            }
        }
    }
}

// MARK: - Fixed payments

private struct FixedPaymentsSection: View {
    let fixed: [FixedPayment]
    let views: [TxnView]
    @Environment(AppModel.self) private var model

    var body: some View {
        let today = Dates.today()
        let active = fixed.filter(\.active)
        let rows = active.map { FixedRow.Info(f: $0, today: today, views: views) }
            .sorted { ($0.paid ? 1 : 0, $0.next) < ($1.paid ? 1 : 0, $1.next) }
        let toPay = rows.filter { !$0.paid }.reduce(Int64(0)) { $0 + $1.f.amountMinor }
        return Section {
            if rows.isEmpty {
                Text("Rent, school fees, loans without SMS: add them to see what's still to pay and get reminders.").font(.subheadline).foregroundStyle(.secondary)
            } else {
                Text("\(MoneyText.text(toPay)) still to pay this month").font(.subheadline.weight(.semibold))
            }
            ForEach(rows.prefix(6), id: \.f.id) { r in FixedRow(info: r) }
        } header: {
            HStack {
                Text("Fixed payments")
                Spacer()
                NavigationLink { FixedPaymentsView() } label: { Text(rows.isEmpty ? "Add" : "Manage") }
                    .font(.caption.weight(.semibold)).textCase(nil)
            }
        }
    }
}

struct FixedRow: View {
    struct Info {
        let f: FixedPayment
        let paid: Bool
        let autoPaid: Bool
        let next: Int64
        let daysLeft: Int64

        @MainActor
        init(f: FixedPayment, today: Int64, views: [TxnView]) {
            self.f = f
            let month = views.filter { $0.fromSms && Dates.ym($0.day) == Dates.ym(today) }
                .map { FixedSchedule.MonthTxn(cardKey: $0.cardKey, amountMinor: $0.amountMinor, categoryId: $0.categoryId, type: $0.type) }
            autoPaid = FixedSchedule.autoPaid(amountMinor: f.amountMinor, cardKey: f.cardKey, categoryId: f.categoryId, monthTxns: month)
            let marked = FixedSchedule.paidThisMonth(lastPaidYm: f.lastPaidYm, today: today)
            paid = marked || autoPaid
            next = FixedSchedule.nextDue(day: f.dayOfMonth, today: today, lastPaidYm: paid ? Dates.ym(today) : f.lastPaidYm)
            daysLeft = next - today
        }
    }

    let info: Info
    @Environment(AppModel.self) private var model

    var body: some View {
        let f = info.f
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(f.name).font(.subheadline.weight(.medium))
                Text(status).font(.caption).foregroundStyle(color)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(MoneyText.amount(f.amountMinor)).font(.subheadline.monospacedDigit())
                if !info.paid {
                    Button("Mark paid") { model.engine.markPaid(f) }
                        .font(.caption.weight(.semibold)).buttonStyle(.borderless)
                }
            }
        }
    }

    private var status: String {
        let date = Dates.dayText(epochDay: info.next)
        if info.autoPaid { return "Paid (seen in your bank SMS) · next \(date)" }
        if info.paid { return "Paid for \(Dates.month.string(from: Date())) · next \(date)" }
        if info.daysLeft < 0 { return "Overdue by \(-info.daysLeft) day\(info.daysLeft == -1 ? "" : "s") (\(date))" }
        if info.daysLeft == 0 { return "Due today" }
        if info.daysLeft == 1 { return "Due tomorrow" }
        return "Due \(date) · in \(info.daysLeft) days"
    }

    private var color: Color {
        if info.paid { return Palette.green }
        if info.daysLeft < 0 { return Palette.red }
        if info.daysLeft <= 3 { return Palette.amber }
        return .secondary
    }
}

// MARK: - Payments due

private struct PaymentsDueSection: View {
    let dues: [CardDue]
    let anyStatements: Bool
    let fixed: [FixedPayment]
    let views: [TxnView]

    var body: some View {
        let today = Dates.today()
        let fixedDue = fixed.filter(\.active).map { FixedRow.Info(f: $0, today: today, views: views) }.filter { !$0.paid && $0.daysLeft <= 10 }
        return Section("Payments due") {
            if dues.isEmpty && fixedDue.isEmpty {
                Text(anyStatements ? "All card statements are paid." : "Nothing due. Card statements show here when your bank sends a statement SMS, or when you check a statement PDF.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(dues) { d in
                NavigationLink { CardDetailLoader(cardKey: d.cardKey) } label: {
                    HStack(spacing: 10) {
                        RoundedRectangle(cornerRadius: 2).fill(d.status.state == .minPaid ? Palette.amber : Palette.red).frame(width: 4, height: 38)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(d.label).font(.subheadline.weight(.semibold))
                            Text("Due \(Dates.dayText(epochDay: d.statement.dueDay))" + (d.statement.minimumDueMinor.map { " · min \(MoneyText.amount($0))" } ?? ""))
                                .font(.caption).foregroundStyle(.secondary)
                            Text(d.status.text).font(.caption.weight(.semibold)).foregroundStyle(d.status.state == .minPaid ? Palette.amber : Palette.red)
                        }
                        Spacer()
                        Text(MoneyText.text(d.status.remainingMinor)).font(.subheadline.monospacedDigit())
                    }
                }
            }
            ForEach(fixedDue, id: \.f.id) { r in FixedRow(info: r) }
        }
    }
}

// MARK: - Recurring

private struct RecurringSection: View {
    let views: [TxnView]
    let cards: [Card]
    let today: Int64
    @Environment(AppModel.self) private var model

    var body: some View {
        let accounts = Set(cards.filter { $0.kind == .account }.map(\.key))
        let counted = Set(cards.filter(\.counted).map(\.key))
        let input = views.filter { t in t.day >= today - 400 && (t.cardKey == nil || counted.contains(t.cardKey!) || accounts.contains(t.cardKey!)) }
        let list = Insights.recurring(input, today: today)
        let labels = SpendMath.cardMap(cards)
        return Section("Recurring payments") {
            if list.isEmpty {
                Text("None detected yet (needs 3 or more roughly monthly charges).").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(list) { r in
                let generic = r.merchantKey.hasPrefix("ACCOUNT DEBIT") || r.merchantKey.hasPrefix("TRANSFER") || r.merchantKey.hasPrefix("PAYMENT TO")
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(generic && r.categoryId != nil ? Categories.name(r.categoryId) : r.merchant).font(.subheadline.weight(.medium)).lineLimit(1)
                        Spacer()
                        Text(MoneyText.amount(r.averageMinor)).font(.subheadline.monospacedDigit())
                    }
                    Text("\(r.occurrences) times · next about \(Dates.dayText(epochDay: r.nextExpected))" + (r.cardKey.flatMap { labels[$0]?.label }.map { " · \($0)" } ?? ""))
                        .font(.caption).foregroundStyle(.secondary)
                    if model.engine.isTracked(r) {
                        Label("In fixed payments: due reminders on", systemImage: "checkmark.circle").font(.caption).foregroundStyle(Palette.green)
                    } else {
                        Button("+ Track as fixed payment") {
                            model.toast = model.engine.trackRecurring(r) ? "Added to fixed payments" : "Already in fixed payments"
                        }
                        .font(.caption.weight(.semibold)).buttonStyle(.borderless)
                    }
                }
            }
        }
    }
}

// MARK: - Goals

private struct GoalsSection: View {
    let goals: [Goal]
    let onAdd: () -> Void
    let onEdit: (Goal) -> Void
    let onAddMoney: (Goal) -> Void

    var body: some View {
        Section {
            if goals.isEmpty {
                Text("Saving for something? Add a goal and track how close you are.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(goals) { g in
                let pct = g.targetMinor > 0 ? Int(g.savedMinor * 100 / g.targetMinor) : 0
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(g.name).font(.subheadline.weight(.semibold))
                        Spacer()
                        Text("\(pct)%").font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(Color.accentColor)
                    }
                    LevelBar(fraction: Double(pct) / 100)
                    Text("\(MoneyText.amount(g.savedMinor)) of \(MoneyText.amount(g.targetMinor))" +
                         (g.targetEpochDay.map { " · about \(MoneyText.amount(Goals.perMonth(targetMinor: g.targetMinor, savedMinor: g.savedMinor, targetDay: $0, today: Dates.today()))) a month to reach it by \(Dates.dayText(epochDay: $0))" } ?? ""))
                        .font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Button("Add money") { onAddMoney(g) }
                        Button("Edit") { onEdit(g) }.foregroundStyle(.secondary)
                    }
                    .font(.caption.weight(.semibold)).buttonStyle(.borderless)
                }
            }
        } header: {
            HStack {
                Text("Savings goals")
                Spacer()
                Button("Add goal", action: onAdd).font(.caption.weight(.semibold)).textCase(nil)
            }
        }
    }
}

struct GoalEditor: View {
    enum Target: Identifiable {
        case new, edit(Goal)
        var id: String {
            switch self {
            case .new: return "new"
            case .edit(let g): return g.id.uuidString
            }
        }
    }

    let target: Target
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var targetText = ""
    @State private var savedText = ""
    @State private var hasDate = false
    @State private var date = Calendar.current.date(byAdding: .year, value: 1, to: Date()) ?? Date()
    @State private var error: String?
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name, e.g. Holiday", text: $name)
                TextField("Target (AED)", text: $targetText).keyboardType(.decimalPad)
                TextField("Saved so far (AED)", text: $savedText).keyboardType(.decimalPad)
                Toggle("Target date", isOn: $hasDate)
                if hasDate { DatePicker("Reach it by", selection: $date, in: Date()..., displayedComponents: .date) }
                if let error { Text(error).foregroundStyle(.red) }
                if case .edit = target {
                    Button("Delete goal", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(isNew ? "New goal" : "Edit goal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save) }
            }
            .confirmationDialog("Delete this goal?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) {
                    if case .edit(let g) = target { model.engine.deleteGoal(g) }
                    dismiss()
                }
            }
            .onAppear {
                if case .edit(let g) = target {
                    name = g.name
                    targetText = MoneyText.plain(g.targetMinor)
                    savedText = MoneyText.plain(g.savedMinor)
                    if let d = g.targetEpochDay { hasDate = true; date = Dates.date(epochDay: d) }
                }
            }
        }
    }

    private var isNew: Bool { if case .new = target { return true } else { return false } }

    private func save() {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { error = "Give it a name"; return }
        guard let t = MoneyText.parse(targetText) else { error = "Enter a target amount"; return }
        let saved = savedText.trimmingCharacters(in: .whitespaces).isEmpty ? 0 : MoneyText.parseSigned(savedText)
        guard let s = saved, s >= 0 else { error = "Saved amount isn't a number"; return }
        let day = hasDate ? Dates.epochDay(date) : nil
        switch target {
        case .new:
            model.engine.addGoal(name: n, targetMinor: t, savedMinor: s, targetDay: day)
        case .edit(let g):
            g.name = n
            g.targetMinor = t
            g.savedMinor = s
            g.targetEpochDay = day
            model.engine.save()
        }
        dismiss()
    }
}

struct AddMoneyView: View {
    let goal: Goal
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Amount (AED)", text: $text).keyboardType(.numbersAndPunctuation)
                } footer: {
                    Text("Use a minus sign to take money out, e.g. -200.")
                }
            }
            .navigationTitle(goal.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if let v = MoneyText.parseSigned(text) { model.engine.addToGoal(goal, v) }
                        dismiss()
                    }
                    .disabled(MoneyText.parseSigned(text) == nil)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// Opens a card's details by its key (for links from Home).
struct CardDetailLoader: View {
    let cardKey: String
    @Query private var cards: [Card]
    var body: some View {
        if let c = cards.first(where: { $0.key == cardKey }) { CardDetailView(card: c) } else { Text("Card not found") }
    }
}
