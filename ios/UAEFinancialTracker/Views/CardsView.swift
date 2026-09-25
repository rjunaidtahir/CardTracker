import SwiftUI
import SwiftData
import Shared

struct CardsView: View {
    @Query(sort: \Card.order) private var cards: [Card]
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var statements: [StatementRecord]
    @State private var adding = false

    var body: some View {
        NavigationStack {
            List {
                if cards.isEmpty {
                    ContentUnavailableView(
                        "No cards yet", systemImage: "creditcard",
                        description: Text("Cards and accounts appear here from your bank messages. You can also add one yourself.")
                    )
                }
                ForEach(cards) { card in
                    // The whole card opens its details (no list chevron beside it).
                    CardTile(card: card, txns: txns.filter { $0.cardKey == card.key }, statement: latestStatement(card.key))
                        .background(NavigationLink { CardDetailView(card: card) } label: { EmptyView() }.opacity(0))
                    .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                    .listRowSeparator(.hidden)
                }
                .onMove { from, to in
                    var list = cards
                    list.move(fromOffsets: from, toOffset: to)
                    for (i, c) in list.enumerated() { c.order = i }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Cards")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { if cards.count > 1 { EditButton() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Add card or account")
                }
            }
            .sheet(isPresented: $adding) { AddCardView() }
        }
    }

    private func latestStatement(_ key: String) -> StatementRecord? {
        statements.filter { $0.cardKey == key }.max { $0.dueEpochDay < $1.dueEpochDay }
    }
}

private struct CardTile: View {
    let card: Card
    let txns: [Txn]
    let statement: StatementRecord?

    var body: some View {
        let monthStart = Calendar.current.date(from: Calendar.current.dateComponents([.year, .month], from: Date()))!
        let month = txns.filter { $0.timestamp >= monthStart }
            .reduce(Int64(0)) { $0 + ($1.txnType == .purchase ? max($1.aedMinor, 0) : ($1.txnType == .refund ? -max($1.aedMinor, 0) : 0)) }
        let available = txns.first { $0.availableMinor != nil }?.availableMinor
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(card.bank).font(.caption.weight(.semibold)).textCase(.uppercase).opacity(0.85)
                Spacer()
                if card.family { Image(systemName: "person.2.fill").font(.caption) }
                if !card.counted { Text("Not counted").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(.white.opacity(0.2), in: Capsule()) }
            }
            Text(card.nickname?.isEmpty == false ? card.nickname! : card.kind.label)
                .font(.headline)
            Text("•••• \(card.last4 ?? "????")").font(.system(.title3, design: .monospaced))
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("This month").font(.caption2).opacity(0.8)
                    Text(MoneyText.text(month)).font(.subheadline.weight(.semibold).monospacedDigit())
                }
                Spacer()
                if let available {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(card.kind == .credit ? "Available" : "Balance").font(.caption2).opacity(0.8)
                        Text(MoneyText.text(available)).font(.subheadline.weight(.semibold).monospacedDigit())
                    }
                }
            }
            if let limit = card.creditLimitMinor, let available, limit > 0, card.kind == .credit {
                ProgressView(value: Double(min(max(limit - available, 0), limit)), total: Double(limit))
                    .tint(.white)
                Text("\(Int(Double(max(limit - available, 0)) / Double(limit) * 100))% of \(MoneyText.text(limit)) used").font(.caption2).opacity(0.85)
            }
            if let s = statement {
                Text("Statement: \(MoneyText.text(s.balanceMinor)) due \(Dates.dayText(epochDay: s.dueEpochDay))").font(.caption).opacity(0.9)
            }
        }
        .foregroundStyle(.white)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(gradient, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var gradient: LinearGradient {
        let palettes: [[Color]] = [
            [Color(red: 0.43, green: 0.16, blue: 0.85), Color(red: 0.86, green: 0.15, blue: 0.47)],
            [Color(red: 0.05, green: 0.46, blue: 0.43), Color(red: 0.13, green: 0.77, blue: 0.62)],
            [Color(red: 0.12, green: 0.23, blue: 0.54), Color(red: 0.23, green: 0.51, blue: 0.96)],
            [Color(red: 0.49, green: 0.18, blue: 0.07), Color(red: 0.96, green: 0.62, blue: 0.04)],
            [Color(red: 0.07, green: 0.09, blue: 0.15), Color(red: 0.29, green: 0.33, blue: 0.39)],
            [Color(red: 0.62, green: 0.07, blue: 0.22), Color(red: 0.96, green: 0.25, blue: 0.37)],
        ]
        let seed = card.bank.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        let p = palettes[seed % palettes.count]
        return LinearGradient(colors: p, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

struct CardDetailView: View {
    @Bindable var card: Card
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query private var txns: [Txn]
    @Query private var statements: [StatementRecord]
    @State private var limitText = ""
    @State private var cantDelete = false

    var body: some View {
        let key = card.key
        let mine = txns.filter { $0.cardKey == key }
        Form {
            Section {
                TextField("Nickname (optional)", text: Binding(get: { card.nickname ?? "" }, set: { card.nickname = $0.isEmpty ? nil : $0 }))
                Picker("Type", selection: Binding(get: { card.kind }, set: { card.cardType = $0.rawValue })) {
                    ForEach(CardKind.allCases) { Text($0.label).tag($0) }
                }
                Toggle("Show & count in spending", isOn: $card.counted)
                Toggle("A card I pay for someone else", isOn: $card.family)
                if card.kind == .credit {
                    TextField("Credit limit (AED)", text: $limitText)
                        .keyboardType(.decimalPad)
                        .onChange(of: limitText) { _, v in card.creditLimitMinor = MoneyText.parse(v) }
                }
            } footer: {
                Text("Debit cards and bank accounts usually aren't counted, so money isn't counted twice when you pay a card from your account. Spends on a card you pay for someone else go to the Family category.")
            }
            Section {
                NavigationLink("\(mine.count) transaction\(mine.count == 1 ? "" : "s")") {
                    ActivityList(title: card.label, filter: { $0.cardKey == key })
                }
            }
            let sts = statements.filter { $0.cardKey == key }.sorted { $0.dueEpochDay > $1.dueEpochDay }
            if !sts.isEmpty {
                Section("Statements") {
                    ForEach(sts) { s in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(MoneyText.text(s.balanceMinor)) due \(Dates.dayText(epochDay: s.dueEpochDay))")
                            if let m = s.minimumDueMinor {
                                Text("Minimum \(MoneyText.text(m))" + (s.fromPdf ? " · from PDF" : "")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            Section {
                Button("Delete card", role: .destructive) {
                    if mine.isEmpty && !statements.contains(where: { $0.cardKey == key }) {
                        model.engine.context.delete(card)
                        model.engine.save()
                        dismiss()
                    } else {
                        cantDelete = true
                    }
                }
            }
        }
        .navigationTitle(card.label)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { limitText = card.creditLimitMinor.map { MoneyText.amount($0).replacingOccurrences(of: ",", with: "") } ?? "" }
        .onDisappear { model.engine.save() }
        .alert("This card has transactions", isPresented: $cantDelete) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Only a card with nothing recorded on it can be deleted. Switch off \"Show & count\" to hide its spending instead.")
        }
    }
}

struct AddCardView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var bank = ""
    @State private var last4 = ""
    @State private var kind = CardKind.credit
    @State private var nickname = ""
    @State private var family = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Bank", selection: $bank) {
                        Text("Choose…").tag("")
                        ForEach(BankList.names, id: \.self) { Text($0).tag($0) }
                    }
                    TextField("Last 4 digits", text: $last4)
                        .keyboardType(.numberPad)
                        .onChange(of: last4) { _, v in last4 = String(v.filter(\.isNumber).prefix(4)) }
                    Picker("Type", selection: $kind) {
                        ForEach(CardKind.allCases) { Text($0.label).tag($0) }
                    }
                    TextField("Nickname (optional)", text: $nickname)
                    Toggle("A card I pay for someone else", isOn: $family)
                }
            }
            .navigationTitle("Add card or account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        model.engine.addCard(bank: bank, last4: last4, kind: kind, nickname: nickname, family: family)
                        dismiss()
                    }
                    .disabled(bank.isEmpty || (kind != .account && last4.count != 4))
                }
            }
        }
    }
}

/// The banks the app knows, for pickers.
enum BankList {
    static let names: [String] = Bridge.shared.bankNames() + [Bridge.shared.UNKNOWN_BANK]
}
