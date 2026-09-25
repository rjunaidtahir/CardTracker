import Foundation
import UIKit

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

    @MainActor
    static func importPaste(_ text: String, engine: Engine) -> Engine.Tally {
        engine.ingestAll(splitPaste(text).map { (body: $0, sender: String?.none, date: Date?.none) }, source: "Paste")
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
            if found == 0 { return "No bank messages found in the screenshot. Crop out the keyboard and try a clearer one." }
            var s = tally.summary
            if undated > 0 { s += ". \(undated) had no time on the screenshot, so they use the date in the message (or today)" }
            return s
        }
    }

    /// Screenshots of the Messages app: text recognition, then each received bubble as a message.
    @MainActor
    static func importScreenshots(_ images: [UIImage], engine: Engine) async -> ScreenshotResult {
        var result = ScreenshotResult()
        var items: [(body: String, sender: String?, date: Date?)] = []
        for image in images {
            let found = await ScreenshotReader.messages(in: image)
            result.found += found.count
            result.undated += found.filter { $0.date == nil }.count
            items += found.map { (body: $0.body, sender: $0.sender, date: $0.date) }
        }
        result.tally = engine.ingestAll(items, source: "Screenshot")
        return result
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
