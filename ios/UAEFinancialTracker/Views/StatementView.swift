import SwiftUI
import SwiftData
import PDFKit
import UniformTypeIdentifiers
import Shared

/// Opens a statement PDF (asking for its password if it has one), reads it, checks it against the app, and saves it
/// to a card.
struct StatementView: View {
    let url: URL?
    var preselectedCard: String? = nil
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Card.order) private var cards: [Card]
    @Query(sort: \Txn.timestamp, order: .reverse) private var txns: [Txn]
    @Query private var statements: [StatementRecord]

    @State private var fileURL: URL?
    @State private var picking = false
    @State private var doc: PDFDocument?
    @State private var locked = false
    @State private var password = ""
    @State private var wrongPassword = false
    @State private var reading: StatementReading?
    @State private var failed: String?
    @State private var extractedText: String?

    @State private var cardKey = ""
    @State private var newBank = ""
    @State private var newLast4 = ""
    @State private var newName = ""
    @State private var newFamily = true
    @State private var selectedMissing: Set<Int> = []
    @State private var showMatched = false
    @State private var saved: [String]?
    @State private var sharing = false

    private static let newCard = "__new__"

    var body: some View {
        Group {
            if let failed {
                VStack(spacing: 16) {
                    ContentUnavailableView("Couldn't read this PDF", systemImage: "doc.questionmark", description: Text(failed))
                    if extractedText != nil {
                        Button("Share extracted text") { sharing = true }.buttonStyle(.bordered)
                    }
                    Button("Choose another PDF") { reset(); picking = true }.buttonStyle(.bordered)
                }
            } else if fileURL == nil {
                ContentUnavailableView {
                    Label("Check a statement", systemImage: "doc.text.magnifyingglass")
                } description: {
                    Text("Choose a card or account statement PDF. The app reads the amounts and every transaction on your iPhone, checks they add up, and compares them with what it recorded.")
                } actions: {
                    Button("Choose statement PDF") { picking = true }.buttonStyle(.borderedProminent)
                }
            } else if locked {
                passwordForm
            } else if let reading {
                result(reading)
            } else {
                ProgressView("Reading the statement…")
            }
        }
        .themedScreen()
        .navigationTitle("Check a statement")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(saved == nil ? "Close" : "Done") { dismiss() } }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.pdf]) { result in
            if case .success(let picked) = result {
                let access = picked.startAccessingSecurityScopedResource()
                defer { if access { picked.stopAccessingSecurityScopedResource() } }
                let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".pdf")
                if (try? FileManager.default.copyItem(at: picked, to: copy)) != nil {
                    reset()
                    fileURL = copy
                    load()
                }
            }
        }
        .sheet(isPresented: $sharing) { ShareSheet(items: [extractedText ?? ""]) }
        .task {
            if fileURL == nil, let url { fileURL = url; load() }
        }
    }

    private func reset() {
        doc = nil; locked = false; password = ""; wrongPassword = false; reading = nil; failed = nil; extractedText = nil
        saved = nil; selectedMissing = []
    }

    // MARK: Password

    private var passwordForm: some View {
        Form {
            Section {
                Label(fileURL?.lastPathComponent ?? "Statement", systemImage: "lock.doc")
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit(unlock)
                if wrongPassword { Text("Wrong password, try again.").foregroundStyle(.red).font(.footnote) }
                Button("Open", action: unlock).disabled(password.isEmpty)
            } header: {
                Text("This statement has a password")
            } footer: {
                Text("UAE banks usually use part of your date of birth or name, as their email explains. It's only used to open the file and isn't saved.")
            }
        }
    }

    private func load() {
        guard let fileURL, doc == nil else { return }
        guard let d = PDFDocument(url: fileURL) else {
            failed = "The file isn't a PDF the iPhone can open."
            return
        }
        doc = d
        if d.isLocked { locked = true } else { read(d) }
    }

    private func unlock() {
        guard let d = doc else { return }
        if d.unlock(withPassword: password) {
            locked = false
            wrongPassword = false
            read(d)
        } else {
            wrongPassword = true
        }
    }

    private func read(_ d: PDFDocument) {
        let r = PdfStatement.read(d)
        if r.rows.isEmpty && r.totalDueMinor < 0 && r.dueEpochDay < 0 {
            extractedText = d.string
            failed = d.string?.isEmpty == false
                ? "The layout wasn't recognised. You can share the extracted text so the reader can be improved."
                : "No text was found. If it is a scanned image, the app can't read it."
            return
        }
        reading = r
        if let pre = preselectedCard, cards.contains(where: { $0.key == pre }) {
            cardKey = pre
        } else if let l4 = r.cardLast4, cards.filter({ $0.last4 == l4 }).count == 1, let c = cards.first(where: { $0.last4 == l4 }) {
            cardKey = c.key
        } else {
            cardKey = Self.newCard
        }
        newBank = BankList.names.first { $0.caseInsensitiveCompare(r.bank ?? "") == .orderedSame } ?? (r.bank ?? "")
        newLast4 = r.cardLast4 ?? ""
        refreshMissing()
    }

    private func refreshMissing() {
        guard let r = reading else { return }
        selectedMissing = Set(currentCheck(r).missingIdx)
    }

    struct Check {
        let matchedCount: Int
        let missingIdx: [Int]
        let onlyInApp: [Txn]
        let matchedRows: [StatementRow]
    }

    private func currentCheck(_ r: StatementReading) -> Check {
        let rows = r.rows
        if cardKey == Self.newCard || cardKey.isEmpty {
            return Check(matchedCount: 0, missingIdx: Array(rows.indices), onlyInApp: [], matchedRows: [])
        }
        let (res, byId) = model.engine.reconcile(r, cardKey: cardKey)
        let missingIdx = rows.indices.filter { i in res.missing.contains { $0 === rows[i] || $0 == rows[i] } }
        let extra = res.onlyInAppRefs.compactMap { UUID(uuidString: $0).flatMap { byId[$0] } }
        return Check(matchedCount: res.matchedRows.count, missingIdx: missingIdx, onlyInApp: extra, matchedRows: res.matchedRows)
    }

    // MARK: Result

    @ViewBuilder
    private func result(_ r: StatementReading) -> some View {
        let check = currentCheck(r)
        let card = cards.first { $0.key == cardKey }
        let appStatement = statements.filter { $0.cardKey == cardKey }.max { $0.dueEpochDay < $1.dueEpochDay }
        let appAvailable = latestAvailable(txns.filter { $0.cardKey == cardKey }.map(TxnView.init))
        List {
            Section("Card or account") {
                Picker("Card", selection: $cardKey) {
                    ForEach(cards.sorted { ($0.kind == .account ? 1 : 0) < ($1.kind == .account ? 1 : 0) }) { c in Text(c.label).tag(c.key) }
                    Text("+ A card not listed (other bank / family)").tag(Self.newCard)
                }
                .onChange(of: cardKey) { _, _ in refreshMissing() }
                if cardKey == Self.newCard {
                    TextField("Bank", text: $newBank)
                    TextField("Last 4 digits", text: $newLast4).keyboardType(.numberPad)
                    TextField("Name (optional)", text: $newName)
                    Toggle("Someone else's card I pay for", isOn: $newFamily)
                    Button("Add card and compare") {
                        cardKey = model.engine.addCard(bank: newBank, last4: newLast4, kind: r.isAccount ? .account : .credit, nickname: newName, family: newFamily && !r.isAccount)
                    }
                    .disabled(newBank.trimmingCharacters(in: .whitespaces).isEmpty || newLast4.count != 4)
                } else if let card, card.kind == .credit {
                    Toggle("Someone else's card I pay for", isOn: Binding(get: { card.family }, set: { model.engine.setFamily(card, $0) }))
                }
            }
            Section("What the statement says") {
                if r.isAccount {
                    figure("Period", r.rows.isEmpty ? nil : "\(Dates.dayText(epochDay: r.rows.map(\.epochDay).min()!)) – \(Dates.dayText(epochDay: r.rows.map(\.epochDay).max()!))", app: nil)
                    figure("Opening balance", r.previousBalanceMinor >= 0 ? MoneyText.text(r.previousBalanceMinor) : nil, app: nil)
                    figure("Closing balance", r.totalDueMinor >= 0 ? MoneyText.text(r.totalDueMinor) : nil, app: nil)
                } else {
                    figure("Statement date", r.statementEpochDay >= 0 ? Dates.dayText(epochDay: r.statementEpochDay) : nil,
                           app: appStatement?.statementEpochDay.map { Dates.dayText(epochDay: $0) })
                    figure("Due date", r.dueEpochDay >= 0 ? Dates.dayText(epochDay: r.dueEpochDay) : nil, app: appStatement.map { Dates.dayText(epochDay: $0.dueEpochDay) })
                    figure("Amount due", r.totalDueMinor >= 0 ? MoneyText.text(r.totalDueMinor) : nil, app: appStatement.map { MoneyText.text($0.balanceMinor) })
                    figure("Minimum due", r.minimumDueMinor >= 0 ? MoneyText.text(r.minimumDueMinor) : nil, app: appStatement?.minimumDueMinor.map { MoneyText.text($0) })
                    figure("Credit limit", r.creditLimitMinor >= 0 ? MoneyText.text(r.creditLimitMinor) : nil, app: card?.creditLimitMinor.map { MoneyText.text($0) })
                    figure("Available limit", r.availableMinor >= 0 ? MoneyText.text(r.availableMinor) : nil, app: appAvailable.map { MoneyText.text($0) })
                    figure("Previous balance", r.previousBalanceMinor >= 0 ? MoneyText.text(r.previousBalanceMinor) : nil, app: nil)
                }
                switch r.addsUp {
                case 1: Label("The figures add up", systemImage: "checkmark.seal.fill").foregroundStyle(Palette.green)
                case 0: Label("The figures don't add up: check the transactions below", systemImage: "exclamationmark.triangle.fill").foregroundStyle(Palette.amber)
                default: Label("Couldn't check the totals", systemImage: "questionmark.circle").foregroundStyle(.secondary)
                }
                if let c = r.check { Text(c).font(.caption).foregroundStyle(.secondary) }
                if Set(r.rows.compactMap(\.cardLast4)).count > 1 {
                    Text("This statement has supplementary cards; all their transactions are compared with this card.").font(.caption).foregroundStyle(.secondary)
                }
                if !r.isAccount, card != nil {
                    Button(saved == nil ? "Save to card profile" : "Saved") {
                        saved = model.engine.applyStatement(r, cardKey: cardKey, saveProfile: true, addRows: [])
                    }
                    .disabled(saved != nil)
                    if let saved { Text(saved.isEmpty ? "Nothing new to save" : "Saved " + saved.joined(separator: ", ")).font(.caption).foregroundStyle(Palette.green) }
                }
            }
            if cardKey != Self.newCard {
                Section {
                    HStack {
                        tile("Matched", check.matchedCount, Palette.green)
                        tile("Missing in app", check.missingIdx.count, Palette.amber)
                        tile("Only in app", check.onlyInApp.count, .secondary)
                    }
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                } header: {
                    Text("Compared with the app (same amount within 3 days)")
                }
            }
            if !check.missingIdx.isEmpty && cardKey != Self.newCard {
                Section {
                    ForEach(check.missingIdx, id: \.self) { i in
                        let row = r.rows[i]
                        Button {
                            if selectedMissing.contains(i) { selectedMissing.remove(i) } else { selectedMissing.insert(i) }
                        } label: {
                            HStack {
                                Image(systemName: selectedMissing.contains(i) ? "checkmark.circle.fill" : "circle").foregroundStyle(Color.accentColor)
                                rowView(row)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Button("Add \(selectedMissing.count) to transactions") {
                        let rows = check.missingIdx.filter { selectedMissing.contains($0) }.map { r.rows[$0] }
                        let done = model.engine.applyStatement(r, cardKey: cardKey, saveProfile: false, addRows: rows)
                        model.toast = done.isEmpty ? "Nothing added" : "Added " + done.joined(separator: ", ")
                        refreshMissing()
                    }
                    .disabled(selectedMissing.isEmpty)
                } header: {
                    Text("Missing in app")
                } footer: {
                    Text("Ticked lines are added as transactions on this card. Spends become purchases; credits become card payments or refunds.")
                }
            }
            if !check.onlyInApp.isEmpty {
                Section {
                    ForEach(check.onlyInApp) { t in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(t.merchant).font(.subheadline).lineLimit(1)
                                Text(Dates.day.string(from: t.timestamp)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(MoneyText.amount(t.amountMinor)).font(.subheadline.monospacedDigit())
                        }
                    }
                } header: {
                    Text("Only in the app")
                } footer: {
                    Text("Often a pending transaction that posts later, a message received twice, or a reversal.")
                }
            }
            if check.matchedCount > 0 {
                Section {
                    DisclosureGroup("Matched (\(check.matchedCount))", isExpanded: $showMatched) {
                        ForEach(Array(check.matchedRows.enumerated()), id: \.offset) { item in rowView(item.element) }
                    }
                }
            }
            if cardKey == Self.newCard && !r.rows.isEmpty {
                Section("Transactions (\(r.rows.count))") {
                    ForEach(Array(r.rows.enumerated()), id: \.offset) { item in rowView(item.element) }
                }
            }
        }
    }

    private func rowView(_ row: StatementRow) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.details).font(.subheadline).lineLimit(2).foregroundStyle(.primary)
                Text(Dates.dayText(epochDay: row.epochDay) + (row.cardLast4.map { " · card \($0)" } ?? "")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text((row.isCredit ? "+" : "") + MoneyText.amount(row.amountMinor))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(row.isCredit ? Palette.green : .primary)
        }
    }

    @ViewBuilder
    private func figure(_ label: String, _ value: String?, app: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            LabeledContent(label, value: value ?? "Not found on the statement")
            if let value {
                if let app {
                    let same = value.filter(\.isNumber) == app.filter(\.isNumber)
                    Text(same ? "Matches the app" : "App has \(app)").font(.caption).foregroundStyle(same ? Palette.green : Palette.amber)
                } else if cardKey != Self.newCard {
                    Text("Not in the app yet").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func tile(_ title: String, _ n: Int, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(n)").font(.title2.bold().monospacedDigit()).foregroundStyle(color)
            Text(title).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}
