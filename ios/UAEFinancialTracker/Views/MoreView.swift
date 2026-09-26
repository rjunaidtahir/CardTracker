import SwiftUI
import SwiftData
import UIKit
import PhotosUI
import UniformTypeIdentifiers

struct MoreView: View {
    @Environment(AppModel.self) private var model
    @Query(filter: #Predicate<SmsRecord> { $0.status == "failed" }) private var toReview: [SmsRecord]
    @Query private var messages: [SmsRecord]
    @State private var picking = false
    @State private var pickTypes: [UTType] = [.pdf]
    @State private var statement = false
    @State private var exporting = false
    @State private var confirmReread = false
    @State private var backupFile: URL?
    @State private var confirmRestore: URL?

    var body: some View {
        NavigationStack {
            List {
                Section("Add bank messages") {
                    NavigationLink { AutomationGuideView() } label: { Label("Automatic import (Shortcuts)", systemImage: "wand.and.stars") }
                    NavigationLink { PasteView() } label: { Label("Paste messages", systemImage: "doc.on.clipboard") }
                    NavigationLink { ScreenshotImportView() } label: { Label("Screenshots of messages", systemImage: "photo.on.rectangle") }
                    Button { pickTypes = [.xml, .plainText, .text, .commaSeparatedText]; picking = true } label: {
                        RowLabel("Import a messages file", "square.and.arrow.down")
                    }
                }
                Section("Your data") {
                    NavigationLink { ReviewView() } label: {
                        LabeledContent {
                            if !toReview.isEmpty { Text("\(toReview.count)").foregroundStyle(Palette.amber) }
                        } label: { Label("Needs review", systemImage: "exclamationmark.bubble") }
                    }
                    NavigationLink { SendersView() } label: { Label("Bank senders", systemImage: "person.crop.rectangle.stack") }
                    NavigationLink { FixedPaymentsView() } label: { Label("Fixed payments", systemImage: "calendar.badge.clock") }
                    Button { statement = true } label: { RowLabel("Check a statement PDF", "doc.text.magnifyingglass") }
                    Button { exporting = true } label: { RowLabel("Export report (PDF or Excel)", "chart.bar.doc.horizontal") }
                    NavigationLink { RatesView() } label: { Label("Exchange rates", systemImage: "dollarsign.arrow.circlepath") }
                    Button { confirmReread = true } label: { RowLabel("Re-read stored messages", "arrow.clockwise") }
                }
                Section("Notifications") {
                    NavigationLink { NotificationsView() } label: { Label("Reminders and alerts", systemImage: "bell.badge") }
                }
                Section("Security") {
                    NavigationLink { SecurityView() } label: {
                        LabeledContent {
                            Text(Settings.lockEnabled ? "On" : "Off").foregroundStyle(.secondary)
                        } label: { Label("App lock", systemImage: "lock") }
                    }
                }
                Section {
                    Button {
                        backupFile = temporaryFile(Backup.fileName(), Backup.export(engine: model.engine))
                    } label: { RowLabel("Back up", "externaldrive.badge.plus") }
                    Button { pickTypes = [.zip]; picking = true } label: { RowLabel("Restore a backup", "externaldrive.badge.timemachine") }
                } header: {
                    Text("Backup")
                } footer: {
                    Text("A backup is a .zip file you keep (in Files, iCloud Drive or email). It opens in the Android app too, and the Android app's backups restore here. \(messages.count) messages stored.")
                }
                Section("Appearance") {
                    NavigationLink { AppearanceView() } label: { Label("Theme and name on reports", systemImage: "paintpalette") }
                }
                Section("Help") {
                    NavigationLink { HelpView() } label: { Label("Help & questions", systemImage: "questionmark.circle") }
                    Button { model.showOnboarding = true } label: { RowLabel("Run the setup again", "sparkles") }
                }
                Section {
                    Text("\(AppInfo.fullName). Your data stays on this iPhone: no account, no ads, no tracking, and nothing is sent anywhere or backed up to iCloud. One-time passwords (OTPs) are never stored.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .themedScreen()
            .navigationTitle("More")
            .fileImporter(isPresented: $picking, allowedContentTypes: pickTypes) { result in
                if case .success(let url) = result { model.open(url) }
            }
            .sheet(isPresented: $statement) { NavigationStack { StatementView(url: nil) } }
            .sheet(isPresented: $exporting) { ExportReportView(period: model.period) }
            .sheet(item: Binding(get: { backupFile.map(IdentifiedURL.init) }, set: { backupFile = $0?.url })) { ShareSheet(items: [$0.url]) }
            .confirmationDialog("Re-read every stored message?", isPresented: $confirmReread, titleVisibility: .visible) {
                Button("Re-read") {
                    let t = model.engine.rereadAll()
                    model.toast = "Re-read: " + t.summary
                }
            } message: {
                Text("Uses the latest reading rules. Your categories, fixes and typed entries are kept.")
            }
        }
    }
}

/// A list row for a button, styled like the navigation rows (plain text, tinted icon).
struct RowLabel: View {
    let title: String
    let symbol: String
    init(_ title: String, _ symbol: String) {
        self.title = title
        self.symbol = symbol
    }
    var body: some View {
        Label { Text(title).foregroundStyle(Color.primary) } icon: { Image(systemName: symbol) }
    }
}

// MARK: - Adding messages

struct PasteView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var result: Engine.Tally?

    var body: some View {
        Form {
            if let result {
                ImportResultSection(tally: result, note: nil, addMoreTitle: "Paste more messages") { self.result = nil }
            } else {
                Section {
                    TextEditor(text: $text)
                        .frame(minHeight: 200)
                        .font(.callout)
                    if text.isEmpty {
                        Button {
                            if let s = UIPasteboard.general.string { text = s }
                        } label: { Label("Paste from clipboard", systemImage: "doc.on.clipboard") }
                    } else {
                        Button {
                            result = MessageFiles.importPaste(text, engine: model.engine)
                            text = ""
                        } label: {
                            Text("Add messages").font(.headline).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }
                } footer: {
                    Text("In Messages, touch and hold a bank SMS → Copy, then paste it here. For several messages, leave an empty line between them. OTPs are never stored, and messages already in the app are skipped.")
                }
            }
        }
        .themedScreen()
        .navigationTitle("Paste messages")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if result != nil { Button("Done") { dismiss() }.fontWeight(.semibold) }
            }
        }
    }
}

/// What an import added, with clear next steps: see the transactions, review what couldn't be read, add more, or finish.
struct ImportResultSection: View {
    let tally: Engine.Tally
    let note: String?
    let addMoreTitle: String
    let addMore: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let nothing = tally.transactions + tally.merged + tally.statements + tally.review == 0
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Label(nothing ? "Nothing new added" : "Done", systemImage: nothing ? "info.circle.fill" : "checkmark.circle.fill")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(nothing ? Palette.amber : Palette.green)
                if let note { Text(note).font(.footnote).foregroundStyle(.secondary) }
            }
            .padding(.vertical, 4)
            if tally.transactions + tally.merged > 0 {
                countRow("Transactions added", tally.transactions + tally.merged, "list.bullet.rectangle", Palette.green)
            }
            if tally.statements > 0 { countRow("Card statements added", tally.statements, "doc.text", Palette.green) }
            if tally.review > 0 { countRow("Need a look", tally.review, "exclamationmark.bubble", Palette.amber) }
            if tally.duplicates > 0 { countRow("Already in the app (skipped)", tally.duplicates, "checkmark.circle", .secondary) }
            if tally.skipped > 0 { countRow("Not needed: OTPs, adverts, other messages", tally.skipped, "minus.circle", .secondary) }
        }
        Section {
            if tally.transactions + tally.merged + tally.statements > 0 {
                Button {
                    model.tab = .activity
                    dismiss()
                } label: {
                    Text("See transactions").font(.headline).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
            if tally.review > 0 {
                NavigationLink {
                    ReviewView()
                } label: {
                    Label("Review \(tally.review) message\(tally.review == 1 ? "" : "s")", systemImage: "exclamationmark.bubble")
                }
            }
            Button(action: addMore) { Label(addMoreTitle, systemImage: "plus.circle") }
            Button { dismiss() } label: { Label("Done", systemImage: "checkmark") }
        }
    }

    private func countRow(_ title: String, _ n: Int, _ symbol: String, _ color: Color) -> some View {
        HStack {
            Label { Text(title) } icon: { Image(systemName: symbol).foregroundStyle(color) }
            Spacer()
            Text("\(n)").font(.headline.monospacedDigit())
        }
    }
}

struct ScreenshotImportView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var items: [PhotosPickerItem] = []
    @State private var working = false
    @State private var result: MessageFiles.ScreenshotResult?

    var body: some View {
        Form {
            if working {
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Reading your screenshots…")
                    }
                    .padding(.vertical, 6)
                }
            } else if let result {
                if result.found == 0 {
                    Section {
                        Label("No bank messages found", systemImage: "exclamationmark.triangle.fill")
                            .font(.headline).foregroundStyle(Palette.amber)
                        Text("Use screenshots of a bank's conversation in Messages, with the message bubbles clearly visible.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Section { picker(title: "Try other screenshots") }
                } else {
                    ImportResultSection(
                        tally: result.tally,
                        note: result.undated > 0
                            ? "\(result.undated) message\(result.undated == 1 ? " had" : "s had") no time on the screenshot, so the date inside the message (or today) was used."
                            : nil,
                        addMoreTitle: "Add more screenshots"
                    ) { self.result = nil }
                }
            } else {
                Section {
                    picker(title: "Choose screenshots")
                } footer: {
                    Text("Take screenshots of a bank's conversation in Messages (scroll so the time labels, like \"Yesterday 21:05\", are visible). Choose them here, then tap Add at the top of the photo picker. The text is read on your iPhone.")
                }
                Section("Tips") {
                    Label("One bank per screenshot works best.", systemImage: "1.circle")
                    Label("Messages without a visible time use the date inside the message, or today.", systemImage: "clock.badge.questionmark")
                    Label("Messages already in the app are skipped, so it's fine to include old ones.", systemImage: "checkmark.circle")
                    Label("You can also share screenshots from Photos straight to \(AppInfo.name).", systemImage: "square.and.arrow.up")
                }
                .font(.subheadline)
            }
        }
        .themedScreen()
        .navigationTitle("Screenshots")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if result != nil && !working { Button("Done") { dismiss() }.fontWeight(.semibold) }
            }
        }
        .onChange(of: items) { _, picked in
            guard !picked.isEmpty else { return }
            working = true
            result = nil
            Task {
                var images: [UIImage] = []
                for item in picked {
                    if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) { images.append(img) }
                }
                result = await MessageFiles.importScreenshots(images, engine: model.engine)
                working = false
                items = []
            }
        }
    }

    private func picker(title: String) -> some View {
        PhotosPicker(selection: $items, maxSelectionCount: 20, matching: .screenshots) {
            Label(title, systemImage: "photo.on.rectangle.angled").font(.headline)
        }
    }
}

struct AutomationGuideView: View {
    private let steps: [(String, String)] = [
        ("Open Shortcuts", "Open Apple's Shortcuts app and tap Automation at the bottom."),
        ("New automation", "Tap + (or New Automation), then choose Message."),
        ("Which messages", "Tap Message Contains and type AED. Leave Sender empty so every bank is included. Choose Run Immediately, then Next."),
        ("Add this app's action", "Tap New Blank Automation → Add Action, search for \(AppInfo.name) and pick Add Bank Message."),
        ("Pass the message", "Tap Message in the action and choose Shortcut Input. Then tap Sender, choose Shortcut Input again and pick Sender."),
        ("Done", "Tap Done. From now on each bank SMS that mentions AED is added in the background. Messages from friends are ignored, and OTPs are never stored."),
    ]

    var body: some View {
        List {
            Section {
                Text("iPhone apps can't read your SMS themselves. A Shortcuts automation hands each new bank SMS to the app instead. It takes about a minute to set up, once.")
                    .font(.callout)
            }
            Section("Steps") {
                ForEach(Array(steps.enumerated()), id: \.offset) { item in
                    HStack(alignment: .top, spacing: 12) {
                        Text("\(item.offset + 1)")
                            .font(.headline)
                            .frame(width: 28, height: 28)
                            .background(Color.accentColor.opacity(0.15), in: Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.element.0).font(.subheadline.weight(.semibold))
                            Text(item.element.1).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
                Button {
                    if let url = URL(string: "shortcuts://") { UIApplication.shared.open(url) }
                } label: {
                    Label("Open Shortcuts", systemImage: "arrow.up.forward.app")
                }
            }
            Section {
                Text("Some banks write amounts as \"Dhs\" or only in another currency. Add a second automation with that word (for example Dhs, USD or card) if some messages are missed.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Older messages") {
                NavigationLink { PasteView() } label: { Label("Paste messages you already have", systemImage: "doc.on.clipboard") }
                NavigationLink { ScreenshotImportView() } label: { Label("Add screenshots of them", systemImage: "photo.on.rectangle") }
            }
        }
        .themedScreen()
        .navigationTitle("Automatic import")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Needs review

struct ReviewView: View {
    @Query(sort: \SmsRecord.receivedAt, order: .reverse) private var messages: [SmsRecord]
    @Environment(AppModel.self) private var model
    @State private var sharing = false

    var body: some View {
        let failed = messages.filter { $0.status == SmsStatus.failed }
        let counts = Dictionary(grouping: messages, by: \.status).mapValues(\.count)
        List {
            if failed.isEmpty {
                ContentUnavailableView("Nothing to review", systemImage: "checkmark.bubble", description: Text("Bank messages the app couldn't read appear here."))
            } else {
                Section {
                    Text("These bank messages mention an amount, but the app couldn't read them for sure. Tap one to say what it was (the app remembers), or swipe left to dismiss it.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button { sharing = true } label: { Label("Share these messages", systemImage: "square.and.arrow.up") }
                }
            }
            ForEach(failed) { sms in
                NavigationLink { FixView(sms: sms) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(sms.bank ?? sms.sender).font(.caption.weight(.semibold))
                            Spacer()
                            Text(Dates.day.string(from: sms.receivedAt)).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(sms.body).font(.callout).lineLimit(6)
                        if let n = sms.note { Text(n).font(.caption).foregroundStyle(Palette.amber) }
                    }
                    .padding(.vertical, 4)
                }
                .swipeActions {
                    Button("Dismiss") { model.engine.dismiss(sms) }.tint(.gray)
                    Button("Not a transaction") {
                        model.engine.saveFix(sms, type: nil, amountMinor: 0, currency: "AED", merchant: "", cardLast4: nil, cardType: .credit, date: sms.receivedAt)
                    }.tint(.orange)
                }
            }
            Section("Stored messages") {
                Text("Transactions \(counts[SmsStatus.transaction] ?? 0) · statements \(counts[SmsStatus.statement] ?? 0) · notices \(counts[SmsStatus.ignored] ?? 0) · to review \(counts[SmsStatus.failed] ?? 0) · dismissed \(counts[SmsStatus.dismissed] ?? 0)")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .themedScreen()
        .navigationTitle("Needs review")
        .sheet(isPresented: $sharing) {
            ShareSheet(items: [failed.map { "\($0.sender) · \(Dates.day.string(from: $0.receivedAt))\n\($0.body)" }.joined(separator: "\n\n")])
        }
    }
}

struct FixView: View {
    let sms: SmsRecord
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var type: TxnKind = .purchase
    @State private var amount = ""
    @State private var currency = "AED"
    @State private var merchant = ""
    @State private var last4 = ""
    @State private var kind = CardKind.credit
    @State private var date = Date()
    @State private var loaded = false

    var body: some View {
        Form {
            Section { Text(sms.body).font(.callout) }
            Section("What was it?") {
                Picker("Type", selection: $type) {
                    ForEach(TxnKind.allCases) { Text($0.label).tag($0) }
                }
                HStack {
                    TextField("Amount", text: $amount).keyboardType(.decimalPad)
                    TextField("AED", text: $currency)
                        .frame(width: 60)
                        .textInputAutocapitalization(.characters)
                }
                TextField("Merchant or description", text: $merchant)
                TextField("Card or account, last 4 digits", text: $last4).keyboardType(.numberPad)
                Picker("Card type", selection: $kind) {
                    ForEach(CardKind.allCases) { Text($0.label).tag($0) }
                }
                DatePicker("When", selection: $date)
            }
            Section {
                Button("Save") {
                    guard let minor = MoneyText.parse(amount) else { return }
                    model.engine.saveFix(sms, type: type, amountMinor: minor, currency: currency, merchant: merchant, cardLast4: last4, cardType: kind, date: date)
                    dismiss()
                }
                .disabled(MoneyText.parse(amount) == nil || currency.trimmingCharacters(in: .whitespaces).count != 3 || !(last4.isEmpty || (3...4).contains(last4.count)))
                Button("Not a transaction", role: .destructive) {
                    model.engine.saveFix(sms, type: nil, amountMinor: 0, currency: "AED", merchant: "", cardLast4: nil, cardType: kind, date: date)
                    dismiss()
                }
            } footer: {
                Text("Your answer is kept for this message, also after an app update.")
            }
        }
        .themedScreen()
        .navigationTitle("Fix")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            date = sms.receivedAt
            guard let g = model.engine.guess(sms) else { return }
            type = TxnKind(rawValue: g.type ?? "") ?? .purchase
            amount = MoneyText.plain(g.amountMinor)
            currency = g.currency ?? "AED"
            merchant = g.merchant ?? ""
            last4 = g.cardLast4 ?? ""
            kind = CardKind(rawValue: g.cardType ?? "") ?? .credit
        }
    }
}

// MARK: - Senders and rates

struct SendersView: View {
    @Query(sort: \BankSender.sender) private var senders: [BankSender]
    @Environment(AppModel.self) private var model
    @State private var sender = ""
    @State private var bank = ""

    var body: some View {
        Form {
            Section {
                Text("The app knows the usual senders of UAE banks. If your bank's messages come from another name, add it here exactly as Messages shows it, then use More → Re-read stored messages.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Add a sender") {
                TextField("Sender name as shown in Messages", text: $sender)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Picker("Bank", selection: $bank) {
                    Text("Choose…").tag("")
                    ForEach(BankList.names, id: \.self) { Text($0).tag($0) }
                }
                Button("Add") {
                    model.engine.addSender(sender, bank: bank)
                    sender = ""
                }
                .disabled(sender.trimmingCharacters(in: .whitespaces).isEmpty || bank.isEmpty)
            }
            if !senders.isEmpty {
                Section("Your senders") {
                    ForEach(senders) { s in LabeledContent(s.sender, value: s.bank) }
                        .onDelete { idx in idx.map { senders[$0] }.forEach(model.engine.removeSender) }
                }
            }
            Section("Banks the app knows") {
                Text(BankList.names.dropLast().joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .themedScreen()
        .navigationTitle("Bank senders")
    }
}

struct RatesView: View {
    @Environment(AppModel.self) private var model
    @State private var rates: [(String, String)] = []
    @State private var newCurrency = ""
    @State private var newRate = ""

    var body: some View {
        Form {
            Section {
                Text("Spends in other currencies are shown in AED with these approximate rates (marked ≈). Changing a rate updates past transactions in that currency.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("1 unit in AED") {
                ForEach(rates, id: \.0) { r in
                    HStack {
                        Text(r.0).font(.body.monospaced())
                        Spacer()
                        TextField("Rate", text: Binding(
                            get: { rates.first { $0.0 == r.0 }?.1 ?? r.1 },
                            set: { v in
                                if let i = rates.firstIndex(where: { $0.0 == r.0 }) { rates[i].1 = v }
                                if let d = Double(v), d > 0 { model.engine.setRate(currency: r.0, rate: v) }
                            }
                        ))
                        .keyboardType(.decimalPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 110)
                    }
                }
            }
            Section("Add a currency") {
                TextField("Code, e.g. INR", text: $newCurrency).textInputAutocapitalization(.characters)
                TextField("AED for 1 unit", text: $newRate).keyboardType(.decimalPad)
                Button("Add") {
                    model.engine.setRate(currency: newCurrency, rate: newRate)
                    newCurrency = ""
                    newRate = ""
                    load()
                }
                .disabled(newCurrency.trimmingCharacters(in: .whitespaces).count != 3 || (Double(newRate) ?? 0) <= 0)
            }
        }
        .themedScreen()
        .navigationTitle("Exchange rates")
        .onAppear(perform: load)
    }

    private func load() {
        rates = Settings.rates.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
}

// MARK: - Fixed payments

struct FixedPaymentsView: View {
    @Query(sort: \FixedPayment.dayOfMonth) private var fixed: [FixedPayment]
    @Environment(AppModel.self) private var model
    @State private var editing: FixedPaymentEditor.Target?

    var body: some View {
        List {
            Section {
                Text("Monthly payments that don't come with a bank SMS: rent, school fees, a loan, a maid's salary. The app shows what's still to pay, reminds you before each one, and spots them in your bank messages when it can.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button { editing = .new } label: { Label("Add fixed payment", systemImage: "plus.circle.fill") }
            }
            if !fixed.isEmpty {
                Section {
                    ForEach(fixed) { f in
                        Button { editing = .edit(f) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.name).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
                                    Text("Every month on day \(f.dayOfMonth)" + (f.active ? "" : " · Paused") + (f.lastPaidYm == Dates.ym(Dates.today()) ? " · paid this month" : ""))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(MoneyText.amount(f.amountMinor)).font(.subheadline.monospacedDigit()).foregroundStyle(.primary)
                            }
                        }
                        .swipeActions {
                            if f.lastPaidYm == Dates.ym(Dates.today()) {
                                Button("Undo paid") { model.engine.undoPaid(f) }.tint(.gray)
                            } else {
                                Button("Mark paid") { model.engine.markPaid(f) }.tint(.green)
                            }
                        }
                    }
                } header: {
                    Text("Monthly total \(MoneyText.text(fixed.filter(\.active).reduce(Int64(0)) { $0 + $1.amountMinor }))")
                }
            }
        }
        .themedScreen()
        .navigationTitle("Fixed payments")
        .sheet(item: $editing) { FixedPaymentEditor(target: $0) }
    }
}

struct FixedPaymentEditor: View {
    enum Target: Identifiable {
        case new, edit(FixedPayment)
        var id: String {
            switch self {
            case .new: return "new"
            case .edit(let f): return f.id.uuidString
            }
        }
    }

    let target: Target
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Card.order) private var cards: [Card]
    @State private var name = ""
    @State private var amount = ""
    @State private var day = 1
    @State private var categoryId: Int64?
    @State private var cardKey: String?
    @State private var remind = true
    @State private var active = true
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name, e.g. Rent", text: $name)
                    TextField("Amount (AED)", text: $amount).keyboardType(.decimalPad)
                    Picker("Day of the month", selection: $day) {
                        ForEach(1...31, id: \.self) { Text("\($0)").tag($0) }
                    }
                }
                Section("Category") {
                    Picker("Category", selection: $categoryId) {
                        Text("None").tag(Int64?.none)
                        ForEach(Categories.all, id: \.id) { Text($0.name).tag(Int64?.some($0.id)) }
                    }
                }
                Section {
                    Picker("Paid from", selection: $cardKey) {
                        Text("Not set").tag(String?.none)
                        ForEach(cards) { Text($0.label).tag(String?.some($0.key)) }
                    }
                } footer: {
                    Text("Optional. When a bank SMS this month shows a payment of about this amount from that card or account, it's marked paid by itself.")
                }
                Section {
                    Toggle("Remind me", isOn: $remind)
                    Toggle("Active", isOn: $active)
                }
                if case .edit = target {
                    Button("Delete", role: .destructive) { confirmDelete = true }
                }
            }
            .navigationTitle(isNew ? "New fixed payment" : "Fixed payment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || MoneyText.parse(amount) == nil)
                }
            }
            .confirmationDialog("Delete this fixed payment?", isPresented: $confirmDelete) {
                Button("Delete", role: .destructive) {
                    if case .edit(let f) = target { model.engine.deleteFixedPayment(f) }
                    dismiss()
                }
            }
            .onAppear {
                if case .edit(let f) = target {
                    name = f.name
                    amount = MoneyText.plain(f.amountMinor)
                    day = f.dayOfMonth
                    categoryId = f.categoryId
                    cardKey = f.cardKey
                    remind = f.remind
                    active = f.active
                }
            }
        }
    }

    private var isNew: Bool { if case .new = target { return true } else { return false } }

    private func save() {
        guard let minor = MoneyText.parse(amount) else { return }
        let n = name.trimmingCharacters(in: .whitespaces)
        switch target {
        case .new:
            let f = FixedPayment(name: n, amountMinor: minor, dayOfMonth: day)
            f.categoryId = categoryId
            f.cardKey = cardKey
            f.remind = remind
            f.active = active
            model.engine.addFixedPayment(f)
        case .edit(let f):
            f.name = n
            f.amountMinor = minor
            f.dayOfMonth = day
            f.categoryId = categoryId
            f.cardKey = cardKey
            f.remind = remind
            f.active = active
            model.engine.save()
            model.engine.changed()
        }
        dismiss()
    }
}

// MARK: - Reports

struct ExportReportView: View {
    let period: Period
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var file: URL?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { make(pdf: true) } label: { Label("PDF report", systemImage: "doc.richtext") }
                    Button { make(pdf: false) } label: { Label("Excel (CSV)", systemImage: "tablecells") }
                } footer: {
                    Text("For \(period.label) (change the period on Home first). The report has totals, categories, budgets, cards, top merchants and every transaction.")
                }
            }
            .navigationTitle("Export report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .sheet(item: Binding(get: { file.map(IdentifiedURL.init) }, set: { file = $0?.url })) { ShareSheet(items: [$0.url]) }
        }
        .presentationDetents([.medium])
    }

    private func make(pdf: Bool) {
        let content = Reports.collect(engine: model.engine, period: period)
        let base = Reports.fileBase(period)
        file = pdf ? temporaryFile(base + ".pdf", Reports.pdf(content)) : temporaryFile(base + ".csv", Data(Reports.csv(content).utf8))
    }
}

// MARK: - Notifications

struct NotificationsView: View {
    @Environment(AppModel.self) private var model
    @State private var reminders = Settings.remindersEnabled
    @State private var alerts = Settings.alertsEnabled
    @State private var budgetAlerts = Settings.budgetAlerts
    @State private var big = MoneyText.plain(Settings.bigSpendMinor)
    @State private var lowAccount = MoneyText.plain(Settings.lowAccountMinor)
    @State private var lowCard = MoneyText.plain(Settings.lowCardMinor)
    @State private var denied = false

    var body: some View {
        Form {
            Section {
                Toggle("Card due-date reminders", isOn: $reminders)
                    .onChange(of: reminders) { _, on in
                        Settings.remindersEnabled = on
                        if on { ask() }
                        Notifier.refresh(engine: model.engine)
                    }
            } footer: {
                Text("3 days before, the day before and on the due day at 9:00, while a card's minimum isn't paid, and for your fixed payments. Each card can switch its reminders off.")
            }
            Section {
                Toggle("Spending alerts", isOn: $alerts)
                    .onChange(of: alerts) { _, on in
                        Settings.alertsEnabled = on
                        if on { ask() }
                    }
                if alerts {
                    amountRow("Big spend from (AED)", $big) { Settings.bigSpendMinor = $0 }
                    amountRow("Account balance below (AED)", $lowAccount) { Settings.lowAccountMinor = $0 }
                    amountRow("Card available limit below (AED)", $lowCard) { Settings.lowCardMinor = $0 }
                    Toggle("Budget at 80% and 100%", isOn: $budgetAlerts).onChange(of: budgetAlerts) { _, v in Settings.budgetAlerts = v }
                }
            } footer: {
                Text("When a new bank message arrives. Set an amount to 0 to switch that alert off.")
            }
            if denied {
                Section {
                    Text("Notifications are off for \(AppInfo.name) in the iPhone's Settings.").foregroundStyle(Palette.amber)
                    Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                }
            }
        }
        .themedScreen()
        .navigationTitle("Reminders and alerts")
    }

    private func amountRow(_ title: String, _ text: Binding<String>, save: @escaping (Int64) -> Void) -> some View {
        HStack {
            Text(title).font(.subheadline)
            Spacer()
            TextField("0", text: text)
                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100)
                .onChange(of: text.wrappedValue) { _, v in
                    let trimmed = v.trimmingCharacters(in: .whitespaces)
                    if trimmed.isEmpty || trimmed == "0" { save(0) } else if let m = MoneyText.parse(trimmed) { save(m) }
                }
        }
    }

    private func ask() {
        Task { denied = !(await Notifier.requestPermission()) }
    }
}

// MARK: - Appearance

struct AppearanceView: View {
    @AppStorage("themeId") private var themeId = "system"
    @State private var name = Settings.displayName

    var body: some View {
        Form {
            Section("Theme") {
                ForEach(AppTheme.all) { t in
                    Button { themeId = t.id } label: {
                        HStack {
                            Circle().fill(t.bg ?? Color(.systemBackground)).overlay(Circle().fill(t.accent ?? Palette.violet).padding(8))
                                .overlay(Circle().stroke(Color.secondary.opacity(0.3))).frame(width: 30, height: 30)
                            Text(t.name).foregroundStyle(.primary)
                            Spacer()
                            if themeId == t.id { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                        }
                    }
                }
            }
            Section {
                TextField("Your name (optional)", text: $name).onChange(of: name) { _, v in Settings.displayName = v }
            } header: {
                Text("Name on reports")
            }
        }
        .themedScreen()
        .navigationTitle("Appearance")
    }
}

// MARK: - Help

struct HelpView: View {
    private let items: [(String, String)] = [
        ("How does the app know what I spend?",
         "Your bank sends an SMS for every card spend, payment and transfer. iPhone apps can't read SMS, so a Shortcuts automation (More → Automatic import) hands each bank SMS to the app as it arrives. You can also paste messages, add screenshots of them, or import a file."),
        ("Is my data safe?",
         "Yes. Everything stays on this iPhone: there is no account and no server, and the data isn't backed up to iCloud. One-time passwords (OTPs) are never stored. Backups are files you save yourself, and you can lock the app with a PIN and Face ID."),
        ("My bank's messages don't show up",
         "Check the automation in Shortcuts runs (it needs \"Run Immediately\"). If your bank writes amounts without \"AED\", add a second automation with another word. If the sender name is new, add it in More → Bank senders."),
        ("A transaction is wrong",
         "Tap it on the Activity tab to see the original SMS. Change its category there, or delete it. Messages the app couldn't read are in More → Needs review, where you can tell the app what they were (it remembers)."),
        ("What counts as spending?",
         "Purchases, minus refunds and cashback. Paying off a card, transfers between your accounts and money coming in never count. Each card has a \"Show & count\" switch: debit cards and bank accounts are off by default, so money isn't counted twice when you pay a card from your account."),
        ("Foreign currency",
         "Spends in other currencies are shown in AED with approximate rates (marked ≈). Set your own rates in More → Exchange rates."),
        ("Card due dates and statements",
         "When your bank sends a statement SMS, the card shows the amount due, the due date and whether it's paid. You can also check a statement PDF (Share → \(AppInfo.name) from Mail or Files) to compare it with what the app recorded and add anything missing."),
        ("Adding things by hand",
         "On Activity, tap + and type it like you'd say it: \"lunch 45\", \"taxi 30 aed\", \"usd 20 netflix #1234\" (#1234 = the card's last 4 digits), \"refund amazon 50\"."),
        ("Screenshots",
         "Screenshots of a bank's conversation in Messages are read on your iPhone. Keep the time labels (\"Yesterday 21:05\") visible so each message gets its date; messages already in the app are skipped."),
        ("Moving from the Android app",
         "In the Android app, More → Back up. Send the .zip to your iPhone and open it with \(AppInfo.name) (or More → Restore a backup). Your messages, cards, categories, budgets and fixed payments come across."),
    ]
    @State private var open: Set<Int> = []

    var body: some View {
        List {
            ForEach(Array(items.enumerated()), id: \.offset) { item in
                DisclosureGroup(isExpanded: Binding(get: { open.contains(item.offset) }, set: { v in if v { open.insert(item.offset) } else { open.remove(item.offset) } })) {
                    Text(item.element.1).font(.subheadline).foregroundStyle(.secondary)
                } label: {
                    Text(item.element.0).font(.subheadline.weight(.semibold))
                }
            }
        }
        .themedScreen()
        .navigationTitle("Help & questions")
    }
}
