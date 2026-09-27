import Foundation
import UIKit
import Shared

/// Messages that come in as text, files or screenshots.
enum MessageFiles {
    /// Splits pasted text into messages: one per paragraph (a blank line between messages).
    static func splitPaste(_ text: String) -> [String] {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        return normalized
            .split(separator: #/\n[ \t]*\n/#)
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    /// - Parameter bankHint: the bank the messages are from, when you chose one.
    @MainActor
    static func importPaste(_ text: String, engine: Engine, bankHint: String? = nil) -> Engine.Tally {
        engine.ingestAll(splitPaste(text).map { (body: $0, sender: String?.none, date: Date?.none) }, source: "Paste", bankHint: bankHint)
    }

    /// A messages file: an Android "SMS Backup & Restore" XML file, or plain text.
    @MainActor
    static func importFile(_ url: URL, engine: Engine) -> Engine.Tally {
        guard let data = try? Data(contentsOf: url) else { return Engine.Tally() }
        let head = String(decoding: data.prefix(2000), as: UTF8.self)
        if head.contains("<smses") || head.contains("<sms ") {
            let items = SmsBackupParser.parse(data)
            return engine.ingestAll(items.map { (body: $0.body, sender: Optional($0.address), date: $0.date) }, source: "Backup file")
        }
        return importPaste(String(decoding: data, as: UTF8.self), engine: engine)
    }

    struct ScreenshotResult {
        var tally = Engine.Tally()
        var found = 0
        var undated = 0
        var summary: String {
            if found == 0 { return "No bank messages found in the screenshot." }
            return tally.summary
        }
    }

    /// Messages read from screenshots of one conversation (one bank), before they're added.
    struct ScreenshotGroup: Identifiable {
        let id = UUID()
        var messages: [ScreenshotReader.Message]
        var images: Int
        /// Read from the conversation name at the top of the screenshot: certain.
        var bankFromHeader: String?
        /// The app's best guess when the name at the top couldn't be read: to be checked.
        var guessedBank: String?
        var undated: Int { messages.filter { $0.date == nil }.count }
        var suggestedBank: String? { bankFromHeader ?? guessedBank }
    }

    /// The bank for a conversation name read at the top of a screenshot ("RAKBANK", "RAK BANK >"), or nil.
    static func bank(ofHeader text: String) -> String? {
        Bridge.shared.canonicalBank(name: ScreenshotReader.cleanHeader(text))
    }

    /// Reads screenshots and groups their messages by conversation (bank), without adding anything yet.
    @MainActor
    static func readScreenshots(_ images: [UIImage], engine: Engine) async -> [ScreenshotGroup] {
        var byBank: [String: ScreenshotGroup] = [:]
        var unknown = ScreenshotGroup(messages: [], images: 0)
        for image in images {
            let found = await ScreenshotReader.messages(in: image, bankOf: { bank(ofHeader: $0) })
            guard !found.isEmpty else { continue }
            if let b = found.first?.sender.flatMap(bank(ofHeader:)) {
                var g = byBank[b] ?? ScreenshotGroup(messages: [], images: 0, bankFromHeader: b)
                g.messages += found
                g.images += 1
                byBank[b] = g
            } else {
                unknown.messages += found
                unknown.images += 1
            }
        }
        var groups = byBank.values.sorted { ($0.bankFromHeader ?? "") < ($1.bankFromHeader ?? "") }
        if !unknown.messages.isEmpty {
            unknown.guessedBank = guessBank(for: unknown.messages.map(\.body), engine: engine)
                ?? (groups.count == 1 ? groups[0].bankFromHeader : nil)
            groups.append(unknown)
        }
        return groups
    }

    /// Which bank a set of messages most likely comes from: a bank named in them, a bank's own message format, or a card
    /// of yours with the same last 4 digits. Nil when nothing points to a bank.
    @MainActor
    static func guessBank(for bodies: [String], engine: Engine) -> String? {
        let bridge = Bridge.shared
        var votes: [String: Int] = [:]
        let now = Engine.millis(Date())
        for body in bodies {
            if let named = bridge.bankNamedIn(text: body) {
                votes[named, default: 0] += 3
                continue
            }
            let r = bridge.readSmsAnySender(body: body, receivedAtMillis: now, rates: [:])
            if let b = r.bank, b != bridge.UNKNOWN_BANK {
                votes[b, default: 0] += 2
            } else if let l4 = r.cardLast4, let c = engine.suggestedCard(bank: nil, last4: l4, isAccount: false),
                      engine.canonicalBank(c.bank) != bridge.UNKNOWN_BANK {
                votes[engine.canonicalBank(c.bank), default: 0] += 2
            }
        }
        return votes.max { a, b in a.value != b.value ? a.value < b.value : a.key > b.key }?.key
    }

    /// Adds screenshot messages, each group as the bank you confirmed (nil: let the app work it out per message).
    @MainActor
    static func add(_ groups: [ScreenshotGroup], banks: [UUID: String], engine: Engine) -> ScreenshotResult {
        var result = ScreenshotResult()
        for g in groups {
            result.found += g.messages.count
            result.undated += g.undated
            let t = engine.ingestAll(g.messages.map { (body: $0.body, sender: String?.none, date: $0.date) }, source: "Screenshot",
                                     bankHint: banks[g.id] ?? g.bankFromHeader)
            result.tally.merge(t)
        }
        return result
    }

    /// Reads and adds screenshots in one go, trusting the bank at the top of each screenshot and the app's guess
    /// otherwise (used by tests; the app shows a check screen first).
    @MainActor
    static func importScreenshots(_ images: [UIImage], engine: Engine) async -> ScreenshotResult {
        let groups = await readScreenshots(images, engine: engine)
        var banks: [UUID: String] = [:]
        for g in groups { if let b = g.suggestedBank { banks[g.id] = b } }
        return add(groups, banks: banks, engine: engine)
    }
}

/// Reads an "SMS Backup & Restore" XML file (Android): received messages only.
final class SmsBackupParser: NSObject, XMLParserDelegate {
    struct Item {
        let address: String
        let body: String
        let date: Date?
    }

    private var items: [Item] = []

    static func parse(_ data: Data) -> [Item] {
        let delegate = SmsBackupParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.items
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String] = [:]) {
        guard elementName == "sms" else { return }
        // type 1 = received; skip sent messages and drafts.
        if let type = attributes["type"], type != "1" { return }
        guard let body = attributes["body"], !body.isEmpty else { return }
        let address = attributes["address"] ?? ""
        let date = attributes["date"].flatMap(Double.init).map { Date(timeIntervalSince1970: $0 / 1000) }
        items.append(Item(address: address, body: body, date: date))
    }
}

/// Files sent to the app from the Share extension wait here until the app opens.
enum Inbox {
    static var folder: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppInfo.appGroup)?.appendingPathComponent("Inbox", isDirectory: true)
    }

    static func pending() -> [URL] {
        guard let f = folder, let list = try? FileManager.default.contentsOfDirectory(at: f, includingPropertiesForKeys: [.creationDateKey]) else { return [] }
        return list.filter { !$0.lastPathComponent.hasPrefix(".") }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
}
