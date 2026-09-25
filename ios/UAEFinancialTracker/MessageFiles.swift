import Foundation

/// Messages that come in as text or files: a paste, an Android "SMS Backup & Restore" XML file, or a text file.
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

    /// A file opened with the app or picked in Import.
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
