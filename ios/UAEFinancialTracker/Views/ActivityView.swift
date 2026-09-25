import SwiftUI
import SwiftData

struct ActivityView: View {
    var body: some View {
        NavigationStack {
            ActivityList(title: "Activity", filter: nil)
        }
    }
}

/// Transactions grouped by day, with search. [filter] narrows it (a category, a card).
struct ActivityList: View {
    let title: String
    let filter: ((Txn) -> Bool)?

    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var cards: [Card]
    @State private var search = ""
    @State private var adding = false
    @State private var importing = false

    var body: some View {
        let cardMap = SpendMath.cardMap(cards)
        let q = search.trimmingCharacters(in: .whitespaces)
        let shown = txns.filter { t in
            (filter?(t) ?? true) && (q.isEmpty || t.merchant.localizedCaseInsensitiveContains(q) ||
                Categories.name(t.categoryId).localizedCaseInsensitiveContains(q) ||
                (t.cardKey.flatMap { cardMap[$0]?.label } ?? "").localizedCaseInsensitiveContains(q))
        }
        let days = Dictionary(grouping: shown) { Calendar.current.startOfDay(for: $0.timestamp) }
            .sorted { $0.key > $1.key }

        List {
            if shown.isEmpty {
                ContentUnavailableView(
                    q.isEmpty ? "No transactions yet" : "Nothing matches",
                    systemImage: q.isEmpty ? "tray" : "magnifyingglass",
                    description: Text(q.isEmpty ? "Bank messages you add appear here. Tap + to type a cash spend." : "Try another word.")
                )
            }
            ForEach(days, id: \.key) { day in
                Section {
                    ForEach(day.value) { t in
                        NavigationLink { TxnDetailView(txn: t) } label: { TxnRow(txn: t, card: t.cardKey.flatMap { cardMap[$0] }) }
                    }
                } header: {
                    HStack {
                        Text(Dates.dayShort.string(from: day.key))
                        Spacer()
                        let spent = day.value.reduce(Int64(0)) { $0 + SpendMath.contribution($1, cards: cardMap) }
                        if spent != 0 { Text(MoneyText.amount(spent)).monospacedDigit() }
                    }
                }
            }
        }
        .searchable(text: $search, prompt: "Merchant, category or card")
        .navigationTitle(title)
        .toolbar {
            if filter == nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { adding = true } label: { Label("Type a spend", systemImage: "keyboard") }
                        Button { importing = true } label: { Label("Paste bank messages", systemImage: "doc.on.clipboard") }
                    } label: { Image(systemName: "plus") }
                }
            }
        }
        .sheet(isPresented: $adding) { AddTypedView() }
        .sheet(isPresented: $importing) { NavigationStack { PasteView() } }
    }
}

struct TxnRow: View {
    let txn: Txn
    let card: Card?

    var body: some View {
        let type = txn.txnType
        let incoming = type == .refund || type == .transferIn
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.body)
                .frame(width: 34, height: 34)
                .background(Color.accentColor.opacity(0.12), in: Circle())
                .foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(txn.merchant).font(.subheadline.weight(.medium)).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text((incoming ? "+" : "") + MoneyText.amount(txn.amountMinor))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(incoming ? .green : (type == .purchase ? .primary : .secondary))
                if txn.currency.uppercased() != "AED" {
                    Text(txn.aedMinor >= 0 ? "\(txn.currency) · ≈ AED \(MoneyText.amount(txn.aedMinor))" : "\(txn.currency) · no rate")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var icon: String {
        switch txn.txnType {
        case .payment: return "checkmark.circle"
        case .transferOut: return "arrow.up.right"
        case .transferIn: return "arrow.down.left"
        case .refund: return "arrow.uturn.left"
        case .purchase: return Categories.symbol(txn.categoryId)
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        if txn.txnType != .purchase { parts.append(txn.txnType.label) } else { parts.append(Categories.name(txn.categoryId)) }
        if let c = card { parts.append(c.label) } else if txn.source == "Typed" { parts.append("Typed") }
        parts.append(Dates.time.string(from: txn.timestamp))
        return parts.joined(separator: " · ")
    }
}

struct TxnDetailView: View {
    @Bindable var txn: Txn
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query private var messages: [SmsRecord]
    @Query private var cards: [Card]
    @State private var learn = true
    @State private var confirmDelete = false

    var body: some View {
        let sms = txn.smsId.flatMap { id in messages.first { $0.id == id } }
        let card = txn.cardKey.flatMap { k in cards.first { $0.key == k } }
        Form {
            Section {
                LabeledContent("Amount", value: MoneyText.text(txn.amountMinor, txn.currency))
                if txn.currency.uppercased() != "AED" {
                    LabeledContent("In AED", value: txn.aedMinor >= 0 ? "≈ " + MoneyText.text(txn.aedMinor) : "Add a rate in More → Exchange rates")
                }
                LabeledContent("Type", value: txn.txnType.label)
                LabeledContent("When", value: Dates.day.string(from: txn.timestamp) + " " + Dates.time.string(from: txn.timestamp))
                LabeledContent("Card", value: card?.label ?? (txn.cardLast4.map { "·\($0)" } ?? "None"))
                if let a = txn.availableMinor { LabeledContent("Available after", value: MoneyText.text(a)) }
            } header: {
                Text(txn.merchant).font(.headline).textCase(nil).foregroundStyle(.primary)
            }
            if txn.txnType != .payment {
                Section {
                    Picker("Category", selection: Binding(
                        get: { txn.categoryId ?? -1 },
                        set: { id in if id >= 0 { model.engine.setCategory(txn, categoryId: id, applyToMerchant: learn) } }
                    )) {
                        Text("No category").tag(Int64(-1))
                        ForEach(Categories.all, id: \.id) { c in
                            Label(c.name, systemImage: Categories.symbol(c.id)).tag(c.id)
                        }
                    }
                    Toggle("Use for this merchant from now on", isOn: $learn)
                } footer: {
                    Text("The app learns from your choice and applies it to this merchant's other transactions.")
                }
            }
            if let sms {
                Section("Original message") {
                    Text(sms.body).font(.callout).textSelection(.enabled)
                    LabeledContent("From", value: sms.sender)
                    if let n = sms.note { Text(n).font(.caption).foregroundStyle(.secondary) }
                }
            } else if txn.source == "Statement" {
                Section { Text("Added from a statement PDF.").foregroundStyle(.secondary) }
            }
            Section {
                Button("Delete transaction", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationTitle("Transaction")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Delete this transaction?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                model.engine.delete(txn)
                dismiss()
            }
        } message: {
            Text(sms == nil ? "It will be removed." : "Its message won't be read again.")
        }
    }
}

struct AddTypedView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var date = Date()
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("e.g. lunch 45", text: $text)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(save)
                    DatePicker("When", selection: $date, in: ...Date())
                } footer: {
                    Text("Write what and how much: \"lunch 45\", \"taxi 32.5\", \"usd 20 netflix #1234\" (the last 4 digits of a card), \"refund amazon 50\".")
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("Type a spend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Add", action: save).disabled(text.trimmingCharacters(in: .whitespaces).isEmpty) }
            }
            .onAppear { focused = true }
        }
    }

    private func save() {
        if model.engine.addTyped(text, date: date) {
            dismiss()
        } else {
            error = "Add an amount, e.g. \"lunch 45\"."
        }
    }
}
