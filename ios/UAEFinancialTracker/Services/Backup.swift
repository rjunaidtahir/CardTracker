import Foundation
import SwiftData
import Compression
import Shared

/// Backups: one .zip of CSV files, the same layout as the Android app, so a backup moves either way between the two.
/// Restoring adds messages, typed entries and settings (nothing is deleted), then re-reads every message.
@MainActor
enum Backup {
    struct Result {
        var sms = 0, typed = 0, cards = 0
        var summary: String { "Restored \(sms) message\(sms == 1 ? "" : "s"), \(typed) typed entr\(typed == 1 ? "y" : "ies"), \(cards) card\(cards == 1 ? "" : "s")" }
    }

    enum Failure: LocalizedError {
        case notABackup
        var errorDescription: String? { "This isn't a Fils backup (sms.csv is missing)." }
    }

    static func fileName(today: Date = Date()) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return "fils-backup-\(f.string(from: today)).zip"
    }

    // MARK: Export

    static func export(engine: Engine) -> Data {
        let ms: (Date) -> String = { String(Engine.millis($0)) }
        let smsList = engine.fetchAll(SmsRecord.self)
        let keyOf = Dictionary(smsList.map { ($0.id, key(for: $0)) }, uniquingKeysWith: { a, _ in a })
        var files: [(String, String)] = []

        files.append(("sms.csv", CSV.write(
            ["dedupKey", "sender", "body", "bodyHash", "receivedAt", "sentAt", "source", "bank", "status", "ruleId", "note"],
            smsList.sorted { $0.receivedAt < $1.receivedAt }.map {
                [keyOf[$0.id], $0.sender, $0.body, $0.bodyHash, ms($0.receivedAt), nil, $0.source, $0.bank, $0.status.uppercased(), $0.ruleId, $0.note]
            }
        )))
        files.append(("manual_transactions.csv", CSV.write(
            ["timestamp", "bank", "cardLast4", "cardKey", "merchant", "amountMinor", "currency", "amountAedMinor", "type", "categoryId", "note"],
            engine.fetchAll(Txn.self).filter { $0.smsId == nil }.sorted { $0.timestamp < $1.timestamp }.map {
                [ms($0.timestamp), $0.bank, $0.cardLast4, $0.cardKey, $0.merchant, String($0.amountMinor), $0.currency,
                 $0.aedMinor >= 0 ? String($0.aedMinor) : nil, $0.type, $0.categoryId.map(String.init), $0.note ?? noteFor($0)]
            }
        )))
        files.append(("cards.csv", CSV.write(
            ["cardKey", "bank", "last4", "cardType", "countInSpending", "nickname", "creditLimitMinor", "statementDay", "dueDay", "remindersEnabled", "archived", "createdAt", "themeKey", "sortOrder", "owner"],
            engine.fetchAll(Card.self).map {
                [$0.key, $0.bank, $0.last4, $0.cardType, String($0.counted), $0.nickname, $0.creditLimitMinor.map(String.init),
                 $0.statementDay.map(String.init), $0.dueDay.map(String.init), String($0.remindersEnabled), "false", ms($0.createdAt),
                 ($0.themeKey?.hasPrefix("img:") ?? false) ? nil : $0.themeKey, String($0.order), $0.family ? "FAMILY" : nil]
            }
        )))
        files.append(("categories.csv", CSV.write(
            ["id", "name", "sortOrder", "archived"],
            (Categories.builtIn + Categories.custom).map { [String($0.id), $0.name, String($0.id), "false"] }
        )))
        files.append(("merchant_rules.csv", CSV.write(["merchantKey", "categoryId"], engine.fetchAll(MerchantRule.self).map { [$0.key, String($0.categoryId)] })))
        files.append(("txn_overrides.csv", CSV.write(["dedupKey", "categoryId"], engine.fetchAll(CategoryOverride.self).compactMap { o in
            keyOf[o.smsId].map { [$0, String(o.categoryId)] }
        })))
        files.append(("savings_goals.csv", CSV.write(
            ["name", "targetMinor", "savedMinor", "targetDateEpochDay", "createdAt"],
            engine.fetchAll(Goal.self).map { [$0.name, String($0.targetMinor), String($0.savedMinor), $0.targetEpochDay.map(String.init), ms($0.createdAt)] }
        )))
        files.append(("budgets.csv", CSV.write(["categoryId", "monthlyLimitMinor"], engine.fetchAll(Budget.self).map { [String($0.categoryId), String($0.limitMinor)] })))
        files.append(("fixed_payments.csv", CSV.write(
            ["name", "amountMinor", "dayOfMonth", "categoryId", "cardKey", "remind", "active", "lastPaidYm", "createdAt"],
            engine.fetchAll(FixedPayment.self).map {
                [$0.name, String($0.amountMinor), String($0.dayOfMonth), $0.categoryId.map(String.init), $0.cardKey, String($0.remind), String($0.active), $0.lastPaidYm, ms($0.createdAt)]
            }
        )))
        files.append(("fx_rates.csv", CSV.write(["currency", "rateToAed", "updatedAt"], Settings.rates.sorted { $0.key < $1.key }.map { [$0.key, $0.value, "0"] })))
        files.append(("card_merges.csv", CSV.write(["fromKey", "intoKey"], engine.fetchAll(CardMerge.self).map { [$0.fromKey, $0.intoKey] })))
        files.append(("bank_senders.csv", CSV.write(["sender", "bankName", "addedAt"], engine.fetchAll(BankSender.self).map { [$0.sender, $0.bank, ms($0.addedAt)] })))
        files.append(("sms_fixes.csv", CSV.write(
            ["dedupKey", "type", "amountMinor", "currency", "merchant", "cardLast4", "cardType", "timestamp"],
            engine.fetchAll(SmsFix.self).compactMap { f in
                keyOf[f.smsId].map { [$0, f.type, String(f.amountMinor), f.currency, f.merchant, f.cardLast4, f.cardType, ms(f.timestamp)] }
            }
        )))
        let iso = DateFormatter()
        iso.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        iso.locale = Locale(identifier: "en_US_POSIX")
        files.append(("all_transactions.csv", CSV.write(
            ["date", "bank", "card", "merchant", "category", "type", "amount", "currency", "amount_aed"],
            engine.fetchAll(Txn.self).sorted { $0.timestamp < $1.timestamp }.map {
                [iso.string(from: $0.timestamp), $0.bank, $0.cardKey, $0.merchant, $0.categoryId.map { Categories.name($0) }, $0.type,
                 MoneyText.plain($0.amountMinor), $0.currency, $0.aedMinor >= 0 ? MoneyText.plain($0.aedMinor) : nil]
            }
        )))
        return Zip.write(files.map { ($0.0, Data($0.1.utf8)) })
    }

    private static func noteFor(_ t: Txn) -> String? {
        switch t.source {
        case "Statement": return "From statement PDF"
        case "Fixed": return "Fixed payment"
        default: return nil
        }
    }

    /// The Android-style key of a message (older records get one now).
    private static func key(for s: SmsRecord) -> String {
        if !s.dedupKey.isEmpty { return s.dedupKey }
        return Bridge.shared.dedupKey(sender: s.sender, sentAtMillis: 0, receivedAtMillis: Engine.millis(s.receivedAt), body: s.body)
    }

    // MARK: Import

    static func restore(_ data: Data, engine: Engine) throws -> Result {
        let entries = try Zip.read(data)
        var files: [String: [[String: String]]] = [:]
        for (name, bytes) in entries {
            files[(name as NSString).lastPathComponent] = CSV.read(String(decoding: bytes, as: UTF8.self))
        }
        guard let smsRows = files["sms.csv"] else { throw Failure.notABackup }
        func l(_ r: [String: String], _ k: String) -> Int64? { r[k].flatMap { Int64($0) } }
        func b(_ r: [String: String], _ k: String, _ d: Bool = false) -> Bool { r[k].map { $0.lowercased() == "true" } ?? d }
        func date(_ r: [String: String], _ k: String) -> Date? { l(r, k).map { Date(timeIntervalSince1970: Double($0) / 1000) } }
        var result = Result()

        // Your own categories (built-in ones are already there).
        let builtInIds = Set(Categories.builtIn.map(\.id))
        let customIds = Set(engine.fetchAll(CustomCategory.self).map(\.id))
        for r in files["categories.csv"] ?? [] {
            guard let id = l(r, "id"), let name = r["name"], !builtInIds.contains(id), !customIds.contains(id) else { continue }
            let c = CustomCategory(id: id, name: name)
            c.archived = b(r, "archived")
            engine.context.insert(c)
        }
        // Cards
        let cards = engine.fetchAll(Card.self)
        for r in files["cards.csv"] ?? [] {
            guard let key = r["cardKey"] else { continue }
            let c = cards.first { $0.key == key } ?? {
                let n = Card(key: key, bank: r["bank"] ?? "", last4: r["last4"], cardType: r["cardType"] ?? "CREDIT", counted: b(r, "countInSpending"))
                engine.context.insert(n)
                return n
            }()
            c.bank = r["bank"] ?? c.bank
            c.last4 = r["last4"]
            c.cardType = r["cardType"] ?? c.cardType
            c.counted = b(r, "countInSpending")
            c.nickname = r["nickname"]
            c.creditLimitMinor = l(r, "creditLimitMinor")
            c.statementDay = l(r, "statementDay").map { Int($0) }
            c.dueDay = l(r, "dueDay").map { Int($0) }
            c.remindersEnabled = b(r, "remindersEnabled", true)
            c.createdAt = date(r, "createdAt") ?? c.createdAt
            if let t = r["themeKey"], !t.hasPrefix("img:") { c.themeKey = t }
            c.order = l(r, "sortOrder").map { Int($0) } ?? c.order
            c.family = r["owner"] == "FAMILY"
            result.cards += 1
        }
        // Learned rules, budgets, senders, rates
        let rules = engine.fetchAll(MerchantRule.self)
        for r in files["merchant_rules.csv"] ?? [] {
            guard let k = r["merchantKey"], let cat = l(r, "categoryId") else { continue }
            if let e = rules.first(where: { $0.key == k }) { e.categoryId = cat } else { engine.context.insert(MerchantRule(key: k, categoryId: cat)) }
        }
        let budgets = engine.fetchAll(Budget.self)
        for r in files["budgets.csv"] ?? [] {
            guard let cat = l(r, "categoryId"), let lim = l(r, "monthlyLimitMinor") else { continue }
            if let e = budgets.first(where: { $0.categoryId == cat }) { e.limitMinor = lim } else { engine.context.insert(Budget(categoryId: cat, limitMinor: lim)) }
        }
        let merges = engine.fetchAll(CardMerge.self)
        for r in files["card_merges.csv"] ?? [] {
            guard let f = r["fromKey"], let i = r["intoKey"], f != i else { continue }
            if let e = merges.first(where: { $0.fromKey == f }) { e.intoKey = i } else { engine.context.insert(CardMerge(fromKey: f, intoKey: i)) }
        }
        let senders = engine.fetchAll(BankSender.self)
        for r in files["bank_senders.csv"] ?? [] {
            guard let s = r["sender"], let bank = r["bankName"] else { continue }
            if let e = senders.first(where: { $0.sender == s }) { e.bank = bank } else { engine.context.insert(BankSender(sender: s, bank: bank)) }
        }
        var rates = Settings.rates
        for r in files["fx_rates.csv"] ?? [] {
            if let c = r["currency"], let v = r["rateToAed"], Double(v) != nil { rates[c.uppercased()] = v }
        }
        Settings.rates = rates
        // Fixed payments and goals (skipped when the same one is already there)
        let fixed = engine.fetchAll(FixedPayment.self)
        for r in files["fixed_payments.csv"] ?? [] {
            guard let name = r["name"], let amount = l(r, "amountMinor") else { continue }
            if fixed.contains(where: { $0.name == name && $0.amountMinor == amount }) { continue }
            let f = FixedPayment(name: name, amountMinor: amount, dayOfMonth: l(r, "dayOfMonth").map { Int($0) } ?? 1)
            f.categoryId = l(r, "categoryId")
            f.cardKey = r["cardKey"]
            f.remind = b(r, "remind", true)
            f.active = b(r, "active", true)
            f.lastPaidYm = r["lastPaidYm"]
            f.createdAt = date(r, "createdAt") ?? Date()
            engine.context.insert(f)
        }
        let goals = engine.fetchAll(Goal.self)
        for r in files["savings_goals.csv"] ?? [] {
            guard let name = r["name"], let target = l(r, "targetMinor") else { continue }
            if goals.contains(where: { $0.name == name && $0.targetMinor == target }) { continue }
            let g = Goal(name: name, targetMinor: target, savedMinor: l(r, "savedMinor") ?? 0, targetEpochDay: l(r, "targetDateEpochDay"))
            g.createdAt = date(r, "createdAt") ?? Date()
            engine.context.insert(g)
        }
        // Messages
        var existing = Set(engine.fetchAll(SmsRecord.self).map { $0.dedupKey.isEmpty ? key(for: $0) : $0.dedupKey })
        let hashes = Set(engine.fetchAll(SmsRecord.self).map(\.bodyHash))
        var smsByKey: [String: SmsRecord] = [:]
        for s in engine.fetchAll(SmsRecord.self) { smsByKey[s.dedupKey.isEmpty ? key(for: s) : s.dedupKey] = s }
        for r in smsRows {
            guard let key = r["dedupKey"], let body = r["body"], !existing.contains(key) else { continue }
            let hash = r["bodyHash"] ?? Bridge.shared.bodyHash(body: body)
            let received = date(r, "receivedAt") ?? Date()
            if hashes.contains(hash), engine.fetchAll(SmsRecord.self).contains(where: { $0.bodyHash == hash && abs($0.receivedAt.timeIntervalSince(received)) < 600 }) { continue }
            let s = SmsRecord(sender: r["sender"] ?? "", body: body, bodyHash: hash, receivedAt: received, timeKnown: true,
                              source: r["source"].map { $0 == "SYNC" || $0 == "LIVE" ? "Android" : $0 } ?? "Backup", bank: r["bank"])
            s.dedupKey = key
            s.status = (r["status"] ?? "").uppercased() == "DISMISSED" ? SmsStatus.dismissed : SmsStatus.failed
            s.ruleId = r["ruleId"]
            s.note = r["note"]
            engine.context.insert(s)
            existing.insert(key)
            smsByKey[key] = s
            result.sms += 1
        }
        // Your choices per message
        let overrides = engine.fetchAll(CategoryOverride.self)
        for r in files["txn_overrides.csv"] ?? [] {
            guard let k = r["dedupKey"], let cat = l(r, "categoryId"), let s = smsByKey[k] else { continue }
            if let o = overrides.first(where: { $0.smsId == s.id }) { o.categoryId = cat } else { engine.context.insert(CategoryOverride(smsId: s.id, categoryId: cat)) }
        }
        let fixes = engine.fetchAll(SmsFix.self)
        for r in files["sms_fixes.csv"] ?? [] {
            guard let k = r["dedupKey"], let type = r["type"], let s = smsByKey[k] else { continue }
            if fixes.contains(where: { $0.smsId == s.id }) { continue }
            engine.context.insert(SmsFix(
                smsId: s.id, type: type, amountMinor: l(r, "amountMinor") ?? 0, currency: r["currency"] ?? "AED", merchant: r["merchant"] ?? "Transaction",
                cardLast4: r["cardLast4"], cardType: r["cardType"] ?? "CREDIT", timestamp: date(r, "timestamp") ?? s.receivedAt
            ))
        }
        // Typed entries
        let typed = engine.fetchAll(Txn.self).filter { $0.smsId == nil }
        for r in files["manual_transactions.csv"] ?? [] {
            guard let ts = date(r, "timestamp"), let amount = l(r, "amountMinor"), let merchant = r["merchant"] else { continue }
            if typed.contains(where: { $0.timestamp == ts && $0.amountMinor == amount && $0.merchant == merchant }) { continue }
            let currency = r["currency"] ?? "AED"
            let note = r["note"]
            let source = note == "From statement PDF" ? "Statement" : note == "Fixed payment" ? "Fixed" : "Typed"
            let t = Txn(source: source, timestamp: ts, bank: r["bank"] ?? "Manual", merchant: merchant, amountMinor: amount, currency: currency,
                        aedMinor: l(r, "amountAedMinor") ?? -1, type: r["type"] ?? "PURCHASE")
            t.cardLast4 = r["cardLast4"]
            t.cardKey = r["cardKey"]
            t.fxEstimated = currency != "AED"
            t.categoryId = l(r, "categoryId")
            t.categoryUserSet = t.categoryId != nil
            t.note = note
            t.merchantKey = Bridge.shared.merchantKey(merchant: merchant)
            engine.context.insert(t)
            result.typed += 1
        }
        engine.save()
        engine.loadCategories()
        _ = engine.rereadAll()
        return result
    }
}

// MARK: - CSV (RFC 4180, like the Android app)

enum CSV {
    static func escape(_ v: String?) -> String {
        guard let v else { return "" }
        if v.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            return "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return v
    }

    static func write(_ header: [String], _ rows: [[String?]]) -> String {
        var out = header.map { escape($0) }.joined(separator: ",") + "\r\n"
        for r in rows { out += r.map { escape($0) }.joined(separator: ",") + "\r\n" }
        return out
    }

    /// Rows keyed by the header; empty fields are left out.
    static func read(_ text: String) -> [[String: String]] {
        let rows = parse(text)
        guard let header = rows.first else { return [] }
        return rows.dropFirst().filter { $0.contains { !$0.isEmpty } }.map { r in
            var m: [String: String] = [:]
            for (i, h) in header.enumerated() where i < r.count && !r[i].isEmpty { m[h] = r[i] }
            return m
        }
    }

    static func parse(_ text: String) -> [[String]] {
        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var chars = Array(text.unicodeScalars)[...]
        if chars.first == "\u{FEFF}" { chars = chars.dropFirst() }
        var it = chars.makeIterator()
        var pending: Unicode.Scalar? = nil
        while let c = pending ?? it.next() {
            pending = nil
            if inQuotes {
                if c == "\"" {
                    if let n = it.next() {
                        if n == "\"" { field.unicodeScalars.append("\"") } else { inQuotes = false; pending = n }
                    } else { inQuotes = false }
                } else { field.unicodeScalars.append(c) }
            } else {
                switch c {
                case "\"": inQuotes = true
                case ",": row.append(field); field = ""
                case "\r": break
                case "\n": row.append(field); field = ""; rows.append(row); row = []
                default: field.unicodeScalars.append(c)
                }
            }
        }
        if !field.isEmpty || !row.isEmpty { row.append(field); rows.append(row) }
        return rows
    }
}

// MARK: - Zip (stored entries when writing; stored or deflated when reading)

enum Zip {
    enum Failure: LocalizedError {
        case notAZip, unsupported
        var errorDescription: String? { "The file isn't a readable .zip backup." }
    }

    static func write(_ files: [(String, Data)]) -> Data {
        var out = Data()
        var central = Data()
        for (name, data) in files {
            let nameBytes = Data(name.utf8)
            let crc = CRC32.checksum(data)
            let offset = UInt32(out.count)
            var local = Data()
            local.append(le32: 0x04034b50)
            local.append(le16: 20); local.append(le16: 0x0800); local.append(le16: 0) // version, UTF-8 names, stored
            local.append(le16: 0); local.append(le16: 0x21) // time, date (1 Jan 1980)
            local.append(le32: crc); local.append(le32: UInt32(data.count)); local.append(le32: UInt32(data.count))
            local.append(le16: UInt16(nameBytes.count)); local.append(le16: 0)
            local.append(nameBytes)
            out.append(local)
            out.append(data)

            central.append(le32: 0x02014b50)
            central.append(le16: 20); central.append(le16: 20); central.append(le16: 0x0800); central.append(le16: 0)
            central.append(le16: 0); central.append(le16: 0x21)
            central.append(le32: crc); central.append(le32: UInt32(data.count)); central.append(le32: UInt32(data.count))
            central.append(le16: UInt16(nameBytes.count)); central.append(le16: 0); central.append(le16: 0)
            central.append(le16: 0); central.append(le16: 0); central.append(le32: 0)
            central.append(le32: offset)
            central.append(nameBytes)
        }
        let centralOffset = UInt32(out.count)
        out.append(central)
        out.append(le32: 0x06054b50)
        out.append(le16: 0); out.append(le16: 0)
        out.append(le16: UInt16(files.count)); out.append(le16: UInt16(files.count))
        out.append(le32: UInt32(central.count)); out.append(le32: centralOffset)
        out.append(le16: 0)
        return out
    }

    static func read(_ data: Data) throws -> [(String, Data)] {
        let bytes = [UInt8](data)
        func u16(_ o: Int) -> Int { o + 1 < bytes.count ? Int(bytes[o]) | Int(bytes[o + 1]) << 8 : 0 }
        func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }
        // End of central directory: search back from the end.
        var eocd = -1
        var i = bytes.count - 22
        while i >= max(0, bytes.count - 65_557) {
            if u32(i) == 0x06054b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw Failure.notAZip }
        let count = u16(eocd + 10)
        var p = u32(eocd + 16)
        var out: [(String, Data)] = []
        for _ in 0..<count {
            guard u32(p) == 0x02014b50 else { throw Failure.notAZip }
            let method = u16(p + 10)
            let compSize = u32(p + 20)
            let size = u32(p + 24)
            let nameLen = u16(p + 28), extraLen = u16(p + 30), commentLen = u16(p + 32)
            let localOffset = u32(p + 42)
            let name = String(decoding: bytes[(p + 46)..<(p + 46 + nameLen)], as: UTF8.self)
            p += 46 + nameLen + extraLen + commentLen
            guard u32(localOffset) == 0x04034b50 else { throw Failure.notAZip }
            let start = localOffset + 30 + u16(localOffset + 26) + u16(localOffset + 28)
            guard start + compSize <= bytes.count else { throw Failure.notAZip }
            let raw = Data(bytes[start..<(start + compSize)])
            if name.hasSuffix("/") { continue }
            switch method {
            case 0: out.append((name, raw))
            case 8: out.append((name, try inflate(raw, size: size)))
            default: throw Failure.unsupported
            }
        }
        return out
    }

    /// Raw DEFLATE (what zip files use) with Apple's Compression framework.
    private static func inflate(_ data: Data, size: Int) throws -> Data {
        if size == 0 { return Data() }
        var out = Data(count: size)
        let written = out.withUnsafeMutableBytes { dst in
            data.withUnsafeBytes { src in
                compression_decode_buffer(
                    dst.bindMemory(to: UInt8.self).baseAddress!, size,
                    src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == size else { throw Failure.unsupported }
        return out
    }
}

enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func checksum(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in data { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }
}

private extension Data {
    mutating func append(le16 v: UInt16) { append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8)]) }
    mutating func append(le32 v: UInt32) { append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
}
