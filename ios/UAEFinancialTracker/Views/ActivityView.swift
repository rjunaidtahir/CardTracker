import SwiftUI
import SwiftData

struct ActivityView: View {
    @Environment(AppModel.self) private var model
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query(sort: \Card.order) private var cards: [Card]
    @State private var search = ""
    @State private var adding = false

    var body: some View {
        @Bindable var model = model
        let cardMap = SpendMath.cardMap(cards)
        let excluded = SpendMath.excluded(cards)
        let period = model.period
        let terms = search.lowercased().split(whereSeparator: { $0 == "," || $0.isWhitespace }).map(String.init)
        let shown = txns.filter { t in
            guard period.contains(date: t.timestamp) else { return false }
            if let cf = model.cardFilter {
                if t.cardKey != cf && t.counterpartyKey != cf { return false }
            } else if let k = t.cardKey, excluded.contains(k) {
                return false
            }
            if let cat = model.categoryFilter, t.categoryId != cat { return false }
            if terms.isEmpty { return true }
            let card = t.cardKey.flatMap { cardMap[$0] }
            let hay = [t.merchant, t.bank, t.cardKey ?? "", Categories.name(t.categoryId), card?.nickname ?? "", MoneyText.plain(t.amountMinor), MoneyText.amount(t.amountMinor)]
                .joined(separator: " ").lowercased()
            return terms.contains { hay.contains($0) }
        }
        let views = shown.map(TxnView.init)
        let days = Dictionary(grouping: shown) { Calendar.current.startOfDay(for: $0.timestamp) }.sorted { $0.key > $1.key }
        let selectedCard = model.cardFilter.flatMap { cardMap[$0] }

        NavigationStack {
            List {
                Section {
                    PeriodBar(period: $model.period)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            pill("All cards", selected: model.cardFilter == nil) { model.cardFilter = nil }
                            ForEach(cards.filter { $0.counted || $0.key == model.cardFilter }) { c in
                                pill(c.label, selected: model.cardFilter == c.key) { model.cardFilter = c.key }
                            }
                        }
                    }
                    if let cat = model.categoryFilter {
                        HStack {
                            Label(Categories.name(cat), systemImage: Categories.symbol(cat)).font(.subheadline)
                            Spacer()
                            Button { model.categoryFilter = nil } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.borderless).foregroundStyle(.secondary)
                        }
                    }
                    SummaryPanel(views: views, excluded: excluded, card: selectedCard)
                }
                if shown.isEmpty {
                    ContentUnavailableView(
                        txns.isEmpty ? "No transactions yet" : "Nothing here",
                        systemImage: txns.isEmpty ? "tray" : "magnifyingglass",
                        description: Text(txns.isEmpty ? "Bank messages you add appear here. Tap + to type a cash spend." : "Try another period, card or search.")
                    )
                }
                ForEach(days, id: \.key) { day in
                    Section {
                        ForEach(day.value) { t in
                            NavigationLink { TxnDetailView(txn: t) } label: { TxnRow(txn: t, card: t.cardKey.flatMap { cardMap[$0] }) }
                        }
                    } header: {
                        HStack {
                            Text(Dates.dayHeader(day.key))
                            Spacer()
                            let spent = day.value.map(TxnView.init).reduce(Int64(0)) { $0 + SpendRules.contribution($1, excluded: excluded) }
                            if spent != 0 { Text(MoneyText.amount(spent)).monospacedDigit() }
                        }
                    }
                }
            }
            .themedScreen()
            .searchable(text: $search, prompt: "Merchant, category, card, amount")
            .navigationTitle("Activity")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { adding = true } label: { Label("Type a spend", systemImage: "keyboard") }
                        NavigationLink { PasteView() } label: { Label("Paste bank messages", systemImage: "doc.on.clipboard") }
                        NavigationLink { ScreenshotImportView() } label: { Label("Add screenshots", systemImage: "photo.on.rectangle") }
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $adding) { AddTypedView() }
        }
    }

    private func pill(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(.caption.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(selected ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.1), in: Capsule())
                .foregroundStyle(selected ? Color.accentColor : .primary)
        }
        .buttonStyle(.plain)
    }
}

/// Totals for what's shown: spending for cards, money in and out for a bank account.
private struct SummaryPanel: View {
    let views: [TxnView]
    let excluded: Set<String>
    let card: Card?

    var body: some View {
        if let card, card.kind == .account {
            let inflow = views.filter { $0.type == .transferIn || $0.type == .refund }.reduce(Int64(0)) { $0 + ($1.aed ?? 0) }
            let outflow = views.filter { $0.type == .transferOut || $0.type == .purchase }.reduce(Int64(0)) { $0 + ($1.aed ?? 0) }
            VStack(alignment: .leading, spacing: 6) {
                Text("NET").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text((inflow - outflow >= 0 ? "+" : "−") + MoneyText.text(abs(inflow - outflow))).font(.title2.bold().monospacedDigit())
                HStack {
                    Stat(title: "Money in", value: MoneyText.amount(inflow))
                    Stat(title: "Money out", value: MoneyText.amount(outflow))
                }
                Text("\(views.count) transactions · " + (card.counted ? "counted as spending: \(MoneyText.amount(SpendRules.total(views, excluded: [])))" : "not counted in spending"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        } else {
            let spent = card.map { c in views.filter { $0.cardKey == c.key }.reduce(Int64(0)) { $0 + SpendRules.contribution($1.type, $1.aed) } } ?? SpendRules.total(views, excluded: excluded)
            let payments = views.filter { $0.type == .payment || $0.counterpartyKey != nil }.count
            let accIn = views.filter { $0.type == .transferIn }.count
            let accOut = views.filter { $0.type == .transferOut && $0.counterpartyKey == nil }.count
            VStack(alignment: .leading, spacing: 6) {
                Text("SPENT").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(MoneyText.text(spent)).font(.title2.bold().monospacedDigit())
                HStack(spacing: 12) {
                    Stat(title: "Transactions", value: "\(views.count)")
                    Stat(title: "Card payments", value: "\(payments)")
                    Stat(title: "In / out", value: "\(accIn) / \(accOut)")
                }
            }
        }
    }
}

struct TxnRow: View {
    let txn: Txn
    let card: Card?

    var body: some View {
        let type = txn.txnType
        let incoming = type.isIncoming
        let counted = card?.counted ?? true
        HStack(spacing: 12) {
            if type == .purchase || type == .refund {
                CategoryIcon(categoryId: txn.categoryId, size: 34)
            } else {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 34, height: 34)
                    .background(Color.secondary.opacity(0.12), in: Circle())
                    .foregroundStyle(incoming ? Palette.green : .secondary)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(txn.merchant).font(.subheadline.weight(.medium)).lineLimit(1)
                Text(tags).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text((type == .purchase || type == .transferOut ? "−" : "+") + (txn.currency.uppercased() == "AED" ? "" : txn.currency + " ") + MoneyText.amount(txn.amountMinor))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(!counted ? .secondary : incoming ? Palette.green : .primary)
                if txn.currency.uppercased() != "AED" {
                    Text(txn.aedMinor >= 0 ? "≈ AED \(MoneyText.amount(txn.aedMinor))" : "no AED rate").font(.caption2).foregroundStyle(.secondary)
                } else {
                    Text(Dates.time.string(from: txn.timestamp)).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var icon: String {
        switch txn.txnType {
        case .payment: return "creditcard"
        case .transferOut: return txn.counterpartyKey != nil ? "creditcard" : "arrow.up.right"
        case .transferIn: return "arrow.down.left"
        case .refund: return "arrow.uturn.left"
        case .purchase: return "cart"
        }
    }

    private var tags: String {
        var parts: [String] = []
        switch txn.txnType {
        case .purchase: parts.append(Categories.name(txn.categoryId))
        case .refund: parts.append("Refund")
        case .payment: parts.append("Card payment")
        case .transferIn: parts.append("Money in")
        case .transferOut: parts.append(txn.counterpartyKey != nil ? "Card payment" : "Money out")
        }
        if let c = card { parts.append(c.label) } else if txn.isTyped { parts.append("typed") }
        if let c = card, !c.counted { parts.append("not counted") }
        return parts.joined(separator: " • ")
    }
}

struct TxnDetailView: View {
    @Bindable var txn: Txn
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query private var messages: [SmsRecord]
    @Query private var cards: [Card]
    @State private var picking = false
    @State private var confirmDelete = false

    var body: some View {
        let sms = txn.smsId.flatMap { id in messages.first { $0.id == id } }
        let paired = txn.pairedSmsId.flatMap { id in messages.first { $0.id == id } }
        let card = txn.cardKey.flatMap { k in cards.first { $0.key == k } }
        Form {
            Section {
                LabeledContent("Amount", value: MoneyText.text(txn.amountMinor, txn.currency))
                if txn.currency.uppercased() != "AED" {
                    LabeledContent("In AED", value: txn.aedMinor >= 0 ? "≈ " + MoneyText.text(txn.aedMinor) : "Add a rate in More → Exchange rates")
                }
                LabeledContent("Type", value: txn.txnType.label)
                LabeledContent("When", value: Dates.day.string(from: txn.timestamp) + ", " + Dates.time.string(from: txn.timestamp))
                LabeledContent("Card", value: card?.label ?? (txn.cardLast4.map { "·\($0)" } ?? "None"))
                LabeledContent("Bank", value: txn.bank)
                if let a = txn.availableMinor {
                    LabeledContent(card?.kind == .credit ? "Available limit after" : "Balance after", value: MoneyText.text(a))
                }
            } header: {
                Text(txn.merchant).font(.headline).textCase(nil).foregroundStyle(.primary)
            }
            if txn.txnType != .payment {
                Section {
                    Button { picking = true } label: {
                        HStack {
                            CategoryIcon(categoryId: txn.categoryId, size: 28)
                            Text(Categories.name(txn.categoryId)).foregroundStyle(.primary)
                            if txn.categoryUserSet { Text("(set by you)").font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            Text("Change").font(.subheadline)
                        }
                    }
                } header: { Text("Category") }
            }
            if let rule = txn.ruleId, rule.hasPrefix("auto-") {
                Section { Label("Read by the smart reader: check it looks right.", systemImage: "sparkles").font(.footnote) }
            } else if txn.ruleId == fixedByYouRule {
                Section { Label("Your fix from Needs review.", systemImage: "checkmark.seal").font(.footnote) }
            }
            if let sms {
                Section("Original message") {
                    Text(sms.body).font(.callout.monospaced()).textSelection(.enabled)
                    LabeledContent("From", value: sms.sender)
                    if !sms.timeKnown { Text("The time wasn't on the screenshot or paste, so the message's own date is used (or the day it was added).").font(.caption).foregroundStyle(.secondary) }
                    if let n = sms.note { Text(n).font(.caption).foregroundStyle(.secondary) }
                }
            }
            if let paired {
                Section("Second message about the same transfer") {
                    Text(paired.body).font(.callout.monospaced()).textSelection(.enabled)
                }
            }
            if let note = txn.note, sms == nil {
                Section { Text(note).foregroundStyle(.secondary) }
            }
            Section {
                Button("Delete transaction", role: .destructive) { confirmDelete = true }
            }
        }
        .themedScreen()
        .navigationTitle("Transaction")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $picking) { CategoryPicker(txn: txn) }
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

struct CategoryPicker: View {
    let txn: Txn
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var choice: Int64?
    @State private var newName = ""
    @State private var applyAll = true

    var body: some View {
        let specific = model.engine.isAmountSpecific(txn)
        let short = String(txn.merchant.prefix(28))
        NavigationStack {
            Form {
                Section {
                    ForEach(Categories.all, id: \.id) { c in
                        Button { choice = c.id; newName = "" } label: {
                            HStack {
                                CategoryIcon(categoryId: c.id, size: 26)
                                Text(c.name).foregroundStyle(.primary)
                                Spacer()
                                if choice == c.id && newName.isEmpty { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                            }
                        }
                    }
                }
                Section {
                    TextField("…or a new category", text: $newName)
                }
                Section {
                    Toggle(specific ? "Apply to every \"\(short)\" of \(MoneyText.amount(txn.amountMinor)) (past and future)" : "Apply to all \"\(short)\" (past and future)", isOn: $applyAll)
                }
            }
            .navigationTitle("Category")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var id = choice
                        if !newName.trimmingCharacters(in: .whitespaces).isEmpty { id = model.engine.addCategory(newName) }
                        if let id { model.engine.setCategory(txn, categoryId: id, applyToMerchant: applyAll) }
                        dismiss()
                    }
                    .disabled(choice == nil && newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { choice = txn.categoryId }
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
                    Text("Type it like you'd say it: \"lunch 45\", \"taxi 30 aed\", \"usd 20 netflix #1234\" (#1234 = the card's last 4 digits), \"refund amazon 50\".")
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
            error = "Couldn't find an amount. Try e.g. \"lunch 45 aed\"."
        }
    }
}
