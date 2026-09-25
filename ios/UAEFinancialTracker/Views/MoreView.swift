import SwiftUI
import UIKit
import SwiftData
import UniformTypeIdentifiers

struct MoreView: View {
    @Environment(AppModel.self) private var model
    @Query private var messages: [SmsRecord]
    @State private var picking = false
    @State private var pickTypes: [UTType] = [.pdf]
    @State private var rereading = false

    var body: some View {
        let review = messages.filter { $0.status == SmsStatus.failed }.count
        NavigationStack {
            List {
                Section("Add bank messages") {
                    NavigationLink { AutomationGuideView() } label: {
                        Label("Automatic import (Shortcuts)", systemImage: "wand.and.stars")
                    }
                    NavigationLink { PasteView() } label: {
                        Label("Paste messages", systemImage: "doc.on.clipboard")
                    }
                    Button { pickTypes = [.xml, .plainText, .text, .commaSeparatedText]; picking = true } label: {
                        Label("Import a messages file", systemImage: "square.and.arrow.down")
                    }
                }
                Section("Statements") {
                    Button { pickTypes = [.pdf]; picking = true } label: {
                        Label("Check a statement PDF", systemImage: "doc.text.magnifyingglass")
                    }
                }
                Section("Messages") {
                    NavigationLink { ReviewView() } label: {
                        LabeledContent {
                            if review > 0 { Text("\(review)").foregroundStyle(.orange) }
                        } label: {
                            Label("Needs review", systemImage: "exclamationmark.bubble")
                        }
                    }
                    NavigationLink { SendersView() } label: { Label("Bank senders", systemImage: "person.crop.rectangle.stack") }
                    NavigationLink { RatesView() } label: { Label("Exchange rates", systemImage: "dollarsign.arrow.circlepath") }
                    Button {
                        rereading = true
                        let tally = model.engine.rereadAll()
                        rereading = false
                        model.toast = "Read again: " + tally.summary
                    } label: {
                        Label("Re-read stored messages", systemImage: "arrow.clockwise")
                    }
                    .disabled(rereading)
                }
                Section("App") {
                    NavigationLink { HelpView() } label: { Label("Help", systemImage: "questionmark.circle") }
                    Button { model.showOnboarding = true } label: { Label("Run the setup again", systemImage: "sparkles") }
                }
                Section {
                    Text("Your data stays on this iPhone. The app has no account, no ads and no tracking, and it is not backed up to iCloud. One-time passwords (OTPs) are never stored.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("More")
            .fileImporter(isPresented: $picking, allowedContentTypes: pickTypes) { result in
                if case .success(let url) = result { model.open(url) }
            }
        }
    }
}

struct PasteView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var result: String?

    var body: some View {
        Form {
            Section {
                TextEditor(text: $text)
                    .frame(minHeight: 200)
                    .font(.callout)
                if text.isEmpty {
                    Button {
                        if let s = UIPasteboard.general.string { text = s }
                    } label: { Label("Paste from clipboard", systemImage: "doc.on.clipboard") }
                }
            } footer: {
                Text("In Messages, touch and hold a bank SMS → Copy, then paste it here. For several messages, leave an empty line between them. OTPs are never stored, and messages already in the app are skipped.")
            }
            if let result {
                Section { Label(result, systemImage: "checkmark.circle").foregroundStyle(.green) }
            }
        }
        .navigationTitle("Paste messages")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Add") {
                    result = MessageFiles.importPaste(text, engine: model.engine).summary
                    text = ""
                }
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }
}

struct AutomationGuideView: View {

    private let steps: [(String, String)] = [
        ("Open Shortcuts", "Open Apple's Shortcuts app and tap Automation at the bottom."),
        ("New automation", "Tap + (or New Automation), then choose Message."),
        ("Which messages", "Tap Message Contains and type AED. Leave Sender empty so every bank is included. Choose Run Immediately, then Next."),
        ("Add this app's action", "Tap New Blank Automation → Add Action, search for UAE Financial Tracker and pick Add Bank Message."),
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
                    let i = item.offset
                    let step = item.element
                    HStack(alignment: .top, spacing: 12) {
                        Text("\(i + 1)")
                            .font(.headline)
                            .frame(width: 28, height: 28)
                            .background(Color.accentColor.opacity(0.15), in: Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            Text(step.0).font(.subheadline.weight(.semibold))
                            Text(step.1).font(.subheadline).foregroundStyle(.secondary)
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
            }
        }
        .navigationTitle("Automatic import")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ReviewView: View {
    @Query(sort: \SmsRecord.receivedAt, order: .reverse) private var messages: [SmsRecord]
    @Environment(AppModel.self) private var model

    var body: some View {
        let failed = messages.filter { $0.status == SmsStatus.failed }
        List {
            if failed.isEmpty {
                ContentUnavailableView("Nothing to review", systemImage: "checkmark.bubble", description: Text("Bank messages the app couldn't read appear here."))
            } else {
                Section {
                    Text("These bank messages mention an amount, but the app couldn't read them for sure. Tap one to say what it was (the app remembers), or swipe left to dismiss it.")
                        .font(.footnote).foregroundStyle(.secondary)
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
                        if let n = sms.note { Text(n).font(.caption).foregroundStyle(.orange) }
                    }
                    .padding(.vertical, 4)
                }
                .swipeActions {
                    Button("Dismiss") { model.engine.dismiss(sms) }.tint(.gray)
                }
            }
        }
        .navigationTitle("Needs review")
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
                .disabled(MoneyText.parse(amount) == nil)
                Button("Not a transaction", role: .destructive) {
                    model.engine.saveFix(sms, type: nil, amountMinor: 0, currency: "AED", merchant: "", cardLast4: nil, cardType: kind, date: date)
                    dismiss()
                }
            } footer: {
                Text("Your answer is kept for this message, also after an app update.")
            }
        }
        .navigationTitle("Fix")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            date = sms.receivedAt
            guard let g = model.engine.guess(sms) else { return }
            type = TxnKind(rawValue: g.type ?? "") ?? .purchase
            amount = MoneyText.amount(g.amountMinor).replacingOccurrences(of: ",", with: "")
            currency = g.currency ?? "AED"
            merchant = g.merchant ?? ""
            last4 = g.cardLast4 ?? ""
            kind = CardKind(rawValue: g.cardType ?? "") ?? .credit
        }
    }
}

struct SendersView: View {
    @Query(sort: \BankSender.sender) private var senders: [BankSender]
    @Environment(AppModel.self) private var model
    @State private var sender = ""
    @State private var bank = ""

    var body: some View {
        Form {
            Section {
                Text("The app knows the usual senders of UAE banks. If your bank's messages arrive from another name, add it here, then tap More → Re-read stored messages.")
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
                    ForEach(senders) { s in
                        LabeledContent(s.sender, value: s.bank)
                    }
                    .onDelete { idx in idx.map { senders[$0] }.forEach(model.engine.removeSender) }
                }
            }
        }
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
                Text("Spends in other currencies are converted to AED with these rates. They are approximate; your bank's rate may differ slightly.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("1 unit = AED") {
                ForEach(rates, id: \.0) { r in
                    HStack {
                        Text(r.0).font(.body.monospaced())
                        Spacer()
                        TextField("Rate", text: Binding(
                            get: { r.1 },
                            set: { v in
                                if let i = rates.firstIndex(where: { $0.0 == r.0 }) { rates[i].1 = v }
                                if Double(v) != nil { model.engine.setRate(currency: r.0, rate: v) }
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
                .disabled(newCurrency.count != 3 || Double(newRate) == nil)
            }
        }
        .navigationTitle("Exchange rates")
        .onAppear(perform: load)
    }

    private func load() {
        rates = Settings.rates.sorted { $0.key < $1.key }.map { ($0.key, $0.value) }
    }
}

struct HelpView: View {
    var body: some View {
        List {
            Section("Getting your bank messages in") {
                HelpRow(title: "Automatically", text: "Set up the Shortcuts automation in More → Automatic import. Each new bank SMS is then added in the background.")
                HelpRow(title: "Messages you already have", text: "Copy them in Messages and use More → Paste messages. Leave an empty line between messages.")
                HelpRow(title: "From an Android phone", text: "Export your SMS with \"SMS Backup & Restore\" on Android, send the .xml file to your iPhone and open it with this app (or More → Import a messages file).")
            }
            Section("Statements") {
                HelpRow(title: "Check a statement PDF", text: "Open a statement PDF from Mail or Files with this app (Share → UAE Financial Tracker), or use More → Check a statement PDF. The app asks for the password if there is one, reads the amounts and every transaction, checks that they add up, and adds the ones that are missing.")
            }
            Section("What counts as spending") {
                HelpRow(title: "Counted", text: "Purchases, minus refunds and cashback, on cards with \"Show & count\" switched on.")
                HelpRow(title: "Never counted", text: "Paying off a card, transfers between your accounts, and money coming in.")
                HelpRow(title: "Debit cards and accounts", text: "These start switched off, so money isn't counted twice when you pay a card from your account.")
            }
            Section("When a message isn't read") {
                HelpRow(title: "Needs review", text: "Bank messages the app couldn't read for sure. Tap Fix to say what one was, or Dismiss.")
                HelpRow(title: "A bank the app doesn't know", text: "Add its sender in More → Bank senders. Messages from any bank are read by wording, so most work without a rule.")
            }
            Section("Privacy") {
                Text("Everything stays on this iPhone. There is no account, no server, no ads and no tracking, and your data is not backed up to iCloud. OTPs are never stored.")
                    .font(.subheadline)
            }
        }
        .navigationTitle("Help")
    }
}

private struct HelpRow: View {
    let title: String
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(text).font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}
