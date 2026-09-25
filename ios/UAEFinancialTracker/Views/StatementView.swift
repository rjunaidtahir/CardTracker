import SwiftUI
import SwiftData
import PDFKit
import Shared

/// Opens a statement PDF (asking for its password if it has one), reads it, and saves it to a card.
struct StatementView: View {
    let url: URL
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Card.order) private var cards: [Card]

    @State private var doc: PDFDocument?
    @State private var locked = false
    @State private var password = ""
    @State private var wrongPassword = false
    @State private var reading: StatementReading?
    @State private var failed: String?
    @State private var working = false

    @State private var cardKey = ""
    @State private var newBank = ""
    @State private var newLast4 = ""
    @State private var family = false
    @State private var missing: [StatementRow] = []
    @State private var addMissing = true
    @State private var saved: [String]?

    private static let newCard = "__new__"

    var body: some View {
        Group {
            if let failed {
                ContentUnavailableView("Couldn't open this PDF", systemImage: "doc.questionmark", description: Text(failed))
            } else if locked {
                passwordForm
            } else if let reading {
                result(reading)
            } else {
                ProgressView("Reading the statement…")
            }
        }
        .navigationTitle("Statement")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(saved == nil ? "Cancel" : "Done") { dismiss() } }
        }
        .task { load() }
    }

    // MARK: Password

    private var passwordForm: some View {
        Form {
            Section {
                Label(url.lastPathComponent, systemImage: "lock.doc")
                SecureField("Password", text: $password)
                    .textContentType(.password)
                    .onSubmit(unlock)
                if wrongPassword { Text("That password didn't open it. Try again.").foregroundStyle(.red).font(.footnote) }
                Button("Open", action: unlock).disabled(password.isEmpty)
            } header: {
                Text("This statement has a password")
            } footer: {
                Text("Banks usually say in the email what the password is made of (for example part of your name and date of birth). It is only used to open the file and isn't saved.")
            }
        }
    }

    private func load() {
        guard doc == nil else { return }
        guard let d = PDFDocument(url: url) else {
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
        working = true
        let r = PdfStatement.read(d)
        working = false
        if r.rows.isEmpty && r.totalDueMinor < 0 && r.dueEpochDay < 0 {
            failed = "No statement figures were found in this PDF. If it is a scanned image, the app can't read it yet."
            return
        }
        reading = r
        // The card: by its last 4 digits, else the bank's only card.
        if let l4 = r.cardLast4, let c = cards.first(where: { $0.last4 == l4 }) {
            cardKey = c.key
        } else if let b = r.bank, cards.filter({ $0.bank == b }).count == 1, let c = cards.first(where: { $0.bank == b }) {
            cardKey = c.key
        } else {
            cardKey = Self.newCard
        }
        newBank = BankList.names.first { $0.caseInsensitiveCompare(r.bank ?? "") == .orderedSame } ?? ""
        newLast4 = r.cardLast4 ?? ""
        refreshMissing()
    }

    private func refreshMissing() {
        guard let r = reading else { return }
        missing = cardKey == Self.newCard ? r.rows : model.engine.missingRows(r, cardKey: cardKey)
    }

    // MARK: Result

    @ViewBuilder
    private func result(_ r: StatementReading) -> some View {
        Form {
            Section {
                row("Bank", r.bank)
                row(r.isAccount ? "Account" : "Card", r.cardLast4.map { "···· \($0)" })
                row("Statement date", r.statementEpochDay >= 0 ? Dates.dayText(epochDay: r.statementEpochDay) : nil)
                row("Due date", r.dueEpochDay >= 0 ? Dates.dayText(epochDay: r.dueEpochDay) : nil)
                row(r.isAccount ? "Closing balance" : "Total due", r.totalDueMinor >= 0 ? MoneyText.text(r.totalDueMinor) : nil)
                row("Minimum due", r.minimumDueMinor >= 0 ? MoneyText.text(r.minimumDueMinor) : nil)
                row("Credit limit", r.creditLimitMinor >= 0 ? MoneyText.text(r.creditLimitMinor) : nil)
                row("Available", r.availableMinor >= 0 ? MoneyText.text(r.availableMinor) : nil)
                row("Previous balance", r.previousBalanceMinor >= 0 ? MoneyText.text(r.previousBalanceMinor) : nil)
            } header: {
                Text("What the statement says")
            }
            Section {
                switch r.addsUp {
                case 1: Label("The figures add up", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                case 0: Label("The figures don't add up: check the transactions below", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                default: Label("Couldn't check the totals", systemImage: "questionmark.circle").foregroundStyle(.secondary)
                }
                if let c = r.check { Text(c).font(.caption).foregroundStyle(.secondary) }
            }
            Section("Save to") {
                Picker("Card", selection: $cardKey) {
                    ForEach(cards) { c in Text(c.label).tag(c.key) }
                    Text("A new card…").tag(Self.newCard)
                }
                .onChange(of: cardKey) { _, _ in refreshMissing() }
                if cardKey == Self.newCard {
                    Picker("Bank", selection: $newBank) {
                        Text("Choose…").tag("")
                        ForEach(BankList.names, id: \.self) { Text($0).tag($0) }
                    }
                    TextField("Last 4 digits", text: $newLast4).keyboardType(.numberPad)
                    Toggle("A card I pay for someone else", isOn: $family)
                }
                if !missing.isEmpty {
                    Toggle("Add \(missing.count) transaction\(missing.count == 1 ? "" : "s") not in the app", isOn: $addMissing)
                }
                Button(saved == nil ? "Save" : "Saved") { save(r) }
                    .disabled(saved != nil || (cardKey == Self.newCard && (newBank.isEmpty || newLast4.count != 4)))
                if let saved {
                    Label(saved.isEmpty ? "Nothing new to save" : "Saved " + saved.joined(separator: ", "), systemImage: "checkmark.circle")
                        .foregroundStyle(.green)
                }
            }
            if !r.rows.isEmpty {
                Section("Transactions (\(r.rows.count))") {
                    ForEach(Array(r.rows.enumerated()), id: \.offset) { item in
                        let row = item.element
                        let isMissing = missing.contains { $0 == row }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.details).font(.subheadline).lineLimit(2)
                                Text(Dates.dayText(epochDay: row.epochDay) + (isMissing ? " · not in the app" : ""))
                                    .font(.caption).foregroundStyle(isMissing ? .orange : .secondary)
                            }
                            Spacer()
                            Text((row.isCredit ? "+" : "") + MoneyText.amount(row.amountMinor))
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(row.isCredit ? .green : .primary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func row(_ label: String, _ value: String?) -> some View {
        if let value { LabeledContent(label, value: value) }
    }

    private func save(_ r: StatementReading) {
        var key = cardKey
        if key == Self.newCard {
            key = model.engine.addCard(bank: newBank, last4: newLast4, kind: r.isAccount ? .account : .credit, nickname: nil, family: family)
        }
        saved = model.engine.applyStatement(r, cardKey: key, addRows: addMissing ? missing : [])
        cardKey = key
    }
}
