import SwiftUI
import SwiftData
import PhotosUI
import Charts
import Shared

struct CardsView: View {
    @Environment(AppModel.self) private var model
    @Query(sort: \Card.order) private var cards: [Card]
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var statements: [StatementRecord]
    @State private var adding = false
    @State private var arranging = false
    @State private var checkingStatement = false

    var body: some View {
        @Bindable var model = model
        let views = txns.map(TxnView.init)
        let today = Dates.today()
        let dues = CardDues.compute(cards: cards, statements: statements, txns: views.filter { $0.day >= today - 400 }, today: today)
        let dueMap = Dictionary(dues.map { ($0.cardKey, $0) }, uniquingKeysWith: { a, _ in a })
        let byCard = Dictionary(grouping: views.filter { $0.cardKey != nil }, by: { $0.cardKey! })
        let payments = CardPayments.byCard(views)

        NavigationStack {
            List {
                if cards.isEmpty {
                    ContentUnavailableView {
                        Label("No cards yet", systemImage: "creditcard")
                    } description: {
                        Text("Cards and accounts appear here from your bank messages. You can also add one yourself.")
                    } actions: {
                        Button("Add a card or account") { adding = true }.buttonStyle(.borderedProminent)
                    }
                } else {
                    SinceStatementPanel(cards: cards, dues: dueMap, byCard: byCard, today: today)
                    AvailableCreditPanel(cards: cards, byCard: byCard, today: today)
                    Section {
                        PeriodBar(period: $model.period)
                    } header: {
                        Text("Figures for \(model.period.label)")
                    }
                    Section {
                        ForEach(cards) { card in
                            CardTileView(
                                card: card, txns: byCard[card.key] ?? [], period: model.period, due: dueMap[card.key],
                                paidIn: (payments[card.key] ?? []).filter { model.period.contains($0.day) }.reduce(Int64(0)) { $0 + $1.amountMinor }
                            )
                            .background(NavigationLink { CardDetailView(card: card) } label: { EmptyView() }.opacity(0))
                            .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                        }
                    }
                }
            }
            .themedScreen()
            .navigationTitle("Cards")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { checkingStatement = true } label: { Image(systemName: "doc.text.magnifyingglass") }
                        .accessibilityLabel("Check a statement PDF")
                    Menu {
                        Button { adding = true } label: { Label("Add card or account", systemImage: "plus") }
                        if cards.count > 1 { Button { arranging = true } label: { Label("Arrange cards", systemImage: "arrow.up.arrow.down") } }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $adding) { AddCardView() }
            .sheet(isPresented: $arranging) { ArrangeCardsView(cards: cards) }
            .sheet(isPresented: $checkingStatement) { NavigationStack { StatementView(url: nil) } }
        }
    }
}

// MARK: - Panels

private struct SinceStatementPanel: View {
    let cards: [Card]
    let dues: [String: CardDue]
    let byCard: [String: [TxnView]]
    let today: Int64

    var body: some View {
        let rows = cards.filter { $0.kind == .credit && $0.counted }.compactMap { c -> (Card, Int64, Int64, Int)? in
            guard let d = dues[c.key] else { return nil }
            let start = SinceStatement.startDay(statementDay: d.statement.statementDay, receivedDay: d.statement.receivedDay)
            let list = (byCard[c.key] ?? []).filter { $0.day >= start && $0.day <= today }
            return (c, start, SinceStatement.spend(list, from: start), list.filter { $0.type == .purchase || $0.type == .refund }.count)
        }
        if !rows.isEmpty {
            Section {
                Text(MoneyText.text(rows.reduce(Int64(0)) { $0 + $1.2 })).font(.title3.bold().monospacedDigit())
                ForEach(rows, id: \.0.key) { r in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.0.label).font(.subheadline)
                            Text("since \(Dates.dayText(epochDay: r.1)) · \(r.3) spend\(r.3 == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(MoneyText.amount(r.2)).font(.subheadline.monospacedDigit())
                    }
                }
            } header: {
                Text("Since last statement")
            } footer: {
                Text("What will appear on each card's next statement.")
            }
        }
    }
}

private struct AvailableCreditPanel: View {
    let cards: [Card]
    let byCard: [String: [TxnView]]
    let today: Int64

    var body: some View {
        let rows = cards.filter { $0.kind == .credit && $0.counted }.compactMap { c -> (Card, Utilisation)? in
            guard let limit = c.creditLimitMinor, limit > 0,
                  let avail = latestAvailable((byCard[c.key] ?? []).filter { $0.day >= today - 400 })
            else { return nil }
            return (c, Utilisation(limitMinor: limit, availableMinor: avail))
        }
        let hasCredit = cards.contains { $0.kind == .credit && $0.counted }
        if !rows.isEmpty {
            let totalLimit = rows.reduce(Int64(0)) { $0 + $1.1.limitMinor }
            let totalAvail = rows.reduce(Int64(0)) { $0 + $1.1.availableMinor }
            let all = Utilisation(limitMinor: totalLimit, availableMinor: totalAvail)
            Section("Available credit") {
                VStack(alignment: .leading, spacing: 2) {
                    Text(MoneyText.text(totalAvail)).font(.title3.bold().monospacedDigit())
                    Text("of \(MoneyText.text(totalLimit)) · \(all.percent)% used").font(.caption).foregroundStyle(.secondary)
                }
                ForEach(rows, id: \.0.key) { r in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(r.0.label).font(.subheadline)
                            Spacer()
                            Text("\(r.1.percent)%").font(.subheadline.weight(.semibold)).foregroundStyle(utilisationColor(r.1.percent))
                        }
                        LevelBar(fraction: Double(r.1.percent) / 100, color: utilisationColor(r.1.percent))
                        Text("Available \(MoneyText.amount(r.1.availableMinor)) · used \(MoneyText.amount(r.1.usedMinor)) of \(MoneyText.amount(r.1.limitMinor))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        } else if hasCredit {
            Section {
                Text("Tip: add each credit card's limit (tap the card) to see available credit and utilisation here.").font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

/// Latest available limit or balance from the card's messages.
func latestAvailable(_ txns: [TxnView]) -> Int64? {
    txns.filter { $0.availableMinor != nil }.max { $0.timestamp < $1.timestamp }?.availableMinor
}

// MARK: - Card tile

struct CardTileView: View {
    let card: Card
    let txns: [TxnView]
    let period: Period
    let due: CardDue?
    let paidIn: Int64
    @Environment(AppModel.self) private var model

    var body: some View {
        let art = CardArt.forCard(bank: card.bank, themeKey: card.themeKey)
        let picture = CardPictures.load(card.themeKey)
        let light = picture != nil || art.lightText
        let text: Color = light ? .white : Color(hex: 0x161616)
        let muted: Color = light ? .white.opacity(0.78) : Color(hex: 0x161616).opacity(0.72)
        let inPeriod = txns.filter { period.contains($0.day) }
        let available = latestAvailable(txns.filter { $0.day >= Dates.today() - 400 })

        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                BankBadge(bank: card.bank)
                Text(card.kind.label.uppercased() + (card.family ? " · FAMILY" : "")).font(.caption2.weight(.semibold)).foregroundStyle(muted)
                Spacer()
                Toggle("Show & count", isOn: Binding(get: { card.counted }, set: { model.engine.setCounted(card, $0) }))
                    .labelsHidden().tint(.green).scaleEffect(0.8)
            }
            Text(card.nickname?.isEmpty == false ? card.nickname! : "\(CardLabels.shortBank(card.bank))").font(.headline).foregroundStyle(text)
            Text("••••  \(card.last4 ?? "····")").font(.system(.title3, design: .monospaced)).foregroundStyle(text)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    if card.kind == .account {
                        let inflow = inPeriod.filter { $0.type == .transferIn || $0.type == .refund }.reduce(Int64(0)) { $0 + ($1.aed ?? 0) }
                        let outflow = inPeriod.filter { $0.type == .transferOut || $0.type == .purchase }.reduce(Int64(0)) { $0 + ($1.aed ?? 0) }
                        Text("Net · \(period.label)").font(.caption2).foregroundStyle(muted)
                        Text((inflow >= outflow ? "+" : "−") + MoneyText.text(abs(inflow - outflow))).font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(text)
                        Text("in \(MoneyText.amount(inflow)) · out \(MoneyText.amount(outflow))").font(.caption2).foregroundStyle(muted)
                    } else {
                        let spent = inPeriod.reduce(Int64(0)) { $0 + SpendRules.contribution($1.type, $1.aed) }
                        Text("Spent · \(period.label)").font(.caption2).foregroundStyle(muted)
                        Text(MoneyText.text(spent)).font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(text)
                        Text("\(inPeriod.count) txns" + (paidIn > 0 ? " · paid in \(MoneyText.amount(paidIn))" : "") + (card.counted ? "" : " · not in totals"))
                            .font(.caption2).foregroundStyle(muted)
                    }
                }
                Spacer()
                if let available {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(card.kind == .credit ? "Available" : "Balance").font(.caption2).foregroundStyle(muted)
                        Text(MoneyText.text(available)).font(.subheadline.weight(.semibold).monospacedDigit()).foregroundStyle(text)
                    }
                }
            }
            if let due, card.kind == .credit {
                HStack {
                    Text("Statement \(MoneyText.amount(due.statement.balanceMinor)) · due \(Dates.dayMonth.string(from: Dates.date(epochDay: due.statement.dueDay)))")
                    Spacer()
                    Text(due.status.text).fontWeight(.semibold)
                }
                .font(.caption2)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background((due.status.state == .unpaid || due.status.state == .overdue ? Color(hex: 0xFEE2E2) : Color(hex: 0xDCFCE7)).opacity(0.92), in: RoundedRectangle(cornerRadius: 8))
                .foregroundStyle(Color(hex: 0x1F2937))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CardBackground(art: art, picture: picture))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .opacity(card.counted ? 1 : 0.6)
    }
}

// MARK: - Card detail

struct CardDetailView: View {
    @Bindable var card: Card
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var statements: [StatementRecord]
    @State private var nickname = ""
    @State private var limitText = ""
    @State private var statementDayText = ""
    @State private var dueDayText = ""
    @State private var reminders = true
    @State private var saved = false
    @State private var cantDelete = false
    @State private var photo: PhotosPickerItem?
    @State private var checking = false
    @State private var merging = false
    @State private var mergeTarget: Card?
    @Query(sort: \Card.order) private var allCards: [Card]

    var body: some View {
        let key = card.key
        let views = txns.map(TxnView.init)
        let mine = views.filter { $0.cardKey == key }
        let today = Dates.today()
        let due = CardDues.compute(cards: [card], statements: statements, txns: views.filter { $0.day >= today - 400 }, today: today).first
        let history = availableHistory(mine.filter { $0.day >= Dates.plusMonths(today, -13) })
        let payments = (CardPayments.byCard(views)[key] ?? []).sorted { $0.day > $1.day }
        let hasStatements = statements.contains { $0.cardKey == key }

        List {
            Section {
                CardTileView(card: card, txns: mine, period: model.period, due: due, paidIn: 0)
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                HStack {
                    Button { model.showActivity(card: key); dismiss() } label: { Label("Transactions", systemImage: "list.bullet") }
                    Spacer()
                    Button { checking = true } label: { Label("Check statement", systemImage: "doc.text.magnifyingglass") }
                }
                .buttonStyle(.borderless).font(.subheadline)
            }
            if card.kind == .credit, let d = due {
                let start = SinceStatement.startDay(statementDay: d.statement.statementDay, receivedDay: d.statement.receivedDay)
                let list = mine.filter { $0.day >= start && $0.day <= today }
                let since = SinceStatement.spend(list, from: start)
                Section("Since last statement · \(Dates.dayText(epochDay: start)) to today") {
                    Text(MoneyText.text(since)).font(.title3.bold().monospacedDigit())
                    Text("\(list.filter { $0.type == .purchase || $0.type == .refund }.count) spends, will appear on the next statement" +
                         (card.creditLimitMinor.map { $0 > 0 ? " · \(since * 100 / $0)% of your limit" : "" } ?? ""))
                        .font(.caption).foregroundStyle(.secondary)
                    Button("Show these transactions") {
                        model.period = .custom(start, today)
                        model.showActivity(card: key)
                        dismiss()
                    }
                    .font(.subheadline)
                }
            }
            if history.count >= 2 {
                Section(card.kind == .credit ? "Available limit history" : "Balance history") {
                    Chart(history, id: \.0) { p in
                        LineMark(x: .value("Date", Dates.date(epochDay: p.0)), y: .value("AED", Double(p.1) / 100))
                            .interpolationMethod(.stepEnd)
                            .foregroundStyle(card.kind == .credit ? Palette.pink : Color.accentColor)
                    }
                    .chartYAxis { AxisMarks(position: .leading) }
                    .frame(height: 160)
                }
            }
            if let d = due, card.kind == .credit {
                Section("Status") {
                    if let limit = card.creditLimitMinor, limit > 0, let avail = latestAvailable(mine) {
                        let u = Utilisation(limitMinor: limit, availableMinor: avail)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Utilisation \(u.percent)% · outstanding \(MoneyText.text(u.usedMinor))").font(.subheadline)
                            LevelBar(fraction: Double(u.percent) / 100, color: utilisationColor(u.percent))
                        }
                    }
                    LabeledContent("Statement balance", value: MoneyText.text(d.statement.balanceMinor))
                    if let m = d.statement.minimumDueMinor { LabeledContent("Minimum due", value: MoneyText.text(m)) }
                    LabeledContent("Due date", value: Dates.dayText(epochDay: d.statement.dueDay))
                    LabeledContent("Paid so far", value: MoneyText.text(d.status.paidMinor))
                    Text(d.status.text).font(.subheadline.weight(.semibold))
                        .foregroundStyle(d.status.state == .paid || d.status.state == .nothingDue ? Palette.green : d.status.state == .minPaid ? Palette.amber : Palette.red)
                }
            }
            if !payments.isEmpty {
                Section("Payments to this card") {
                    ForEach(Array(payments.prefix(12).enumerated()), id: \.offset) { item in
                        let p = item.element
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Dates.dayText(epochDay: p.day)).font(.subheadline)
                                Text(p.fromBankSms ? "confirmed by the card's bank" : "transfer from your account").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(MoneyText.amount(p.amountMinor)).font(.subheadline.monospacedDigit())
                        }
                    }
                }
            }
            Section("Card look") {
                HStack {
                    PhotosPicker(selection: $photo, matching: .images) {
                        Label(CardPictures.load(card.themeKey) == nil ? "Use my picture" : "Change picture", systemImage: "photo")
                    }
                    if card.themeKey?.hasPrefix("img:") == true {
                        Spacer()
                        Button("Remove", role: .destructive) { model.engine.setCardTheme(card, nil) }
                    }
                }
                .buttonStyle(.borderless)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        swatch(nil, art: CardArt.bankDefault(card.bank), title: "Automatic")
                        ForEach(CardArt.presets) { a in swatch(a.key, art: a, title: a.name) }
                    }
                    .padding(.vertical, 4)
                }
            }
            Section {
                Picker("Type", selection: Binding(get: { card.kind }, set: { model.engine.setCardType(card, $0) })) {
                    ForEach(CardKind.allCases) { Text($0.label).tag($0) }
                }
                Toggle("Show & count in spending", isOn: Binding(get: { card.counted }, set: { model.engine.setCounted(card, $0) }))
                Toggle("Someone else's card I pay for", isOn: Binding(get: { card.family }, set: { model.engine.setFamily(card, $0) }))
            } header: {
                Text("Settings")
            } footer: {
                Text("Debit cards and bank accounts usually aren't counted, so money isn't counted twice when you pay a card from your account. Spends on a card you pay for someone else go to the Family category.")
            }
            Section {
                TextField("Nickname (optional)", text: $nickname)
                if card.kind == .credit {
                    TextField("Credit limit (AED)", text: $limitText).keyboardType(.decimalPad)
                    TextField("Statement day (1–31)", text: $statementDayText).keyboardType(.numberPad)
                    TextField("Due day (1–31)", text: $dueDayText).keyboardType(.numberPad)
                    Toggle("Due-date reminders for this card", isOn: $reminders)
                }
                Button(saved ? "Saved" : "Save") {
                    model.engine.updateProfile(
                        card, nickname: nickname, limitMinor: MoneyText.parse(limitText),
                        statementDay: Int(statementDayText), dueDay: Int(dueDayText), reminders: reminders
                    )
                    saved = true
                }
            } header: {
                Text("Profile")
            } footer: {
                if card.kind == .credit, card.statementDay != nil || card.dueDay != nil, hasStatements {
                    Text("Statement and due day are filled from the card's statement when you haven't set them.")
                }
            }
            Section {
                let others = allCards.filter { $0.key != card.key }
                if !others.isEmpty {
                    Menu {
                        ForEach(others) { other in
                            Button("\(other.label) – \(other.bank)") { mergeTarget = other }
                        }
                    } label: {
                        Label("This is the same card as…", systemImage: "arrow.triangle.merge")
                    }
                }
                Button("Remove this card", role: .destructive) {
                    if model.engine.deleteCardIfEmpty(card) { dismiss() } else { cantDelete = true }
                }
            } footer: {
                Text("If this card shows twice (for example once under \"Other bank\"), merge it into the other one. Its transactions and statements move across and stay together.")
            }
        }
        .themedScreen()
        .confirmationDialog(
            "Merge into \(mergeTarget?.label ?? "")?",
            isPresented: Binding(get: { mergeTarget != nil }, set: { if !$0 { mergeTarget = nil } }),
            titleVisibility: .visible
        ) {
            Button("Merge") {
                if let target = mergeTarget {
                    let from = card
                    let engine = model.engine
                    mergeTarget = nil
                    // Leave this screen first: the card it shows goes away.
                    dismiss()
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(400))
                        engine.mergeCards(from, into: target)
                    }
                }
            }
            Button("Cancel", role: .cancel) { mergeTarget = nil }
        } message: {
            Text("\(card.label) and \(mergeTarget?.label ?? "") become one card. This can't be undone.")
        }
        .navigationTitle(card.label)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
        .onChange(of: nickname) { _, _ in saved = false }
        .onChange(of: limitText) { _, _ in saved = false }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
                   let key = CardPictures.save(image, cardKey: card.key) {
                    model.engine.setCardTheme(card, key)
                }
                photo = nil
            }
        }
        .sheet(isPresented: $checking) { NavigationStack { StatementView(url: nil, preselectedCard: card.key) } }
        .alert("This card has transactions", isPresented: $cantDelete) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Only a card with nothing recorded on it can be removed. Switch off \"Show & count\" to hide its spending instead.")
        }
    }

    private func load() {
        nickname = card.nickname ?? ""
        limitText = card.creditLimitMinor.map { MoneyText.plain($0) } ?? ""
        statementDayText = card.statementDay.map(String.init) ?? ""
        dueDayText = card.dueDay.map(String.init) ?? ""
        reminders = card.remindersEnabled
        saved = false
    }

    /// The last available limit or balance of each day.
    private func availableHistory(_ list: [TxnView]) -> [(Int64, Int64)] {
        var byDay: [Int64: (Date, Int64)] = [:]
        for t in list {
            guard let a = t.availableMinor else { continue }
            if let cur = byDay[t.day], cur.0 > t.timestamp { continue }
            byDay[t.day] = (t.timestamp, a)
        }
        return byDay.map { ($0.key, $0.value.1) }.sorted { $0.0 < $1.0 }
    }

    private func swatch(_ key: String?, art: CardArt, title: String) -> some View {
        let selected = card.themeKey == key
        return Button { model.engine.setCardTheme(card, key) } label: {
            VStack(spacing: 4) {
                CardBackground(art: art)
                    .frame(width: 64, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 7))
                    .overlay(RoundedRectangle(cornerRadius: 7).stroke(selected ? Color.accentColor : .clear, lineWidth: 3))
                Text(title).font(.caption2).foregroundStyle(.secondary).lineLimit(1).frame(width: 66)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Add and arrange

struct AddCardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var bank = ""
    @State private var last4 = ""
    @State private var kind = CardKind.credit
    @State private var nickname = ""
    @State private var family = false

    var body: some View {
        let suggestions = bank.count >= 2 ? BankList.names.filter { $0.localizedCaseInsensitiveContains(bank) && $0 != bank }.prefix(4) : []
        NavigationStack {
            Form {
                Section {
                    TextField("Bank", text: $bank)
                    ForEach(Array(suggestions), id: \.self) { s in
                        Button(s) { bank = s }.font(.subheadline)
                    }
                    TextField("Last 4 digits", text: $last4)
                        .keyboardType(.numberPad)
                        .onChange(of: last4) { _, v in
                            let d = String(v.filter(\.isNumber).prefix(4))
                            if d != v { last4 = d }
                        }
                    Picker("Type", selection: $kind) {
                        ForEach(CardKind.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    TextField("Nickname (optional)", text: $nickname)
                    if kind == .credit { Toggle("Someone else's card I pay for", isOn: $family) }
                } footer: {
                    Text("Leave the last 4 digits empty for an account the messages don't number.")
                }
            }
            .navigationTitle("Add card or account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.engine.addCard(bank: bank, last4: last4, kind: kind, nickname: nickname, family: family && kind == .credit)
                        dismiss()
                    }
                    .disabled(bank.trimmingCharacters(in: .whitespaces).isEmpty || !(last4.isEmpty || last4.count == 4))
                }
            }
        }
    }
}

struct ArrangeCardsView: View {
    let cards: [Card]
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var order: [String] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(order, id: \.self) { key in
                    let c = cards.first { $0.key == key }
                    HStack {
                        CardBackground(art: CardArt.forCard(bank: c?.bank ?? "", themeKey: c?.themeKey), picture: CardPictures.load(c?.themeKey))
                            .frame(width: 44, height: 28).clipShape(RoundedRectangle(cornerRadius: 5))
                        Text(c?.label ?? key)
                    }
                }
                .onMove { order.move(fromOffsets: $0, toOffset: $1) }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Arrange cards")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        model.engine.setCardOrder(order)
                        dismiss()
                    }
                }
            }
            .onAppear { order = cards.map(\.key) }
        }
    }
}

/// The banks the app knows, for pickers.
enum BankList {
    static let names: [String] = Bridge.shared.bankNames() + [Bridge.shared.UNKNOWN_BANK]
}
