import Foundation
import Vision
import UIKit

/// Reads bank messages from screenshots of the Messages app: text recognition, then the received bubbles grouped into
/// messages, each dated by the nearest time header above it ("Today 09:15", "Yesterday 21:05", "Mon, 21 Sep at 10:00").
/// A message with no time header above it on the screenshot gets no date (flagged in the app).
enum ScreenshotReader {
    struct Line {
        let text: String
        /// Left and right edges, 0…1 of the width.
        let minX: Double
        let maxX: Double
        /// Top of the line, 0…1 from the top.
        let top: Double
        let height: Double
        var midX: Double { (minX + maxX) / 2 }
        var bottom: Double { top + height }
    }

    struct Message: Equatable {
        let body: String
        let date: Date?
        let sender: String?
    }

    /// Recognises the text of an image.
    static func lines(in image: UIImage) async -> [Line] {
        guard let cg = image.cgImage else { return [] }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]
        let handler = VNImageRequestHandler(cgImage: cg, orientation: .up)
        do { try handler.perform([request]) } catch { return [] }
        return (request.results ?? []).compactMap { o -> Line? in
            guard let t = o.topCandidates(1).first?.string else { return nil }
            let b = o.boundingBox
            return Line(text: t, minX: b.minX, maxX: b.maxX, top: 1 - b.maxY, height: b.height)
        }
    }

    static func messages(in image: UIImage, now: Date = Date()) async -> [Message] {
        messages(from: await lines(in: image), now: now)
    }

    // MARK: - Grouping (pure, unit-tested)

    static func messages(from raw: [Line], now: Date = Date()) -> [Message] {
        let lines = raw.sorted { $0.top < $1.top }
        // The conversation name at the top (the bank's sender name), if any.
        let header = lines.first { l in
            l.top < 0.16 && l.top > 0.03 && abs(l.midX - 0.5) < 0.12 && parseTime(l.text, now: now) == nil &&
                !isChrome(l.text) && l.text.count <= 30 && l.text.rangeOfCharacter(from: .letters) != nil
        }
        var out: [Message] = []
        var current: [Line] = []
        var currentDate: Date?
        var sawDate = false
        func flush() {
            guard !current.isEmpty else { return }
            let body = current.map(\.text).joined(separator: " ").trimmingCharacters(in: .whitespaces)
            if body.count >= 12 { out.append(Message(body: body, date: sawDate ? currentDate : nil, sender: header?.text)) }
            current = []
        }
        for l in lines {
            if let h = header, l.top <= h.bottom + 0.005 { continue }
            if l.top < 0.05 { continue } // status bar
            if let d = parseTime(l.text, now: now), abs(l.midX - 0.5) < 0.2 {
                flush()
                currentDate = d
                sawDate = true
                continue
            }
            if isChrome(l.text) { flush(); continue }
            // Received bubbles are on the left; your own replies on the right are skipped.
            guard l.minX < 0.3 else { flush(); continue }
            if let last = current.last {
                let gap = l.top - last.bottom
                if gap > max(last.height, l.height) * 0.9 { flush() }
            }
            current.append(l)
        }
        flush()
        return out
    }

    /// Screen furniture that isn't part of a message.
    private static func isChrome(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespaces).lowercased()
        let words = ["text message", "imessage", "delivered", "read", "sms", "text message • sms", "rcs message", "edit", "kill"]
        return words.contains(t) || t.hasPrefix("text message") || t.hasPrefix("the sender is not in your contact") || t.hasPrefix("report junk")
    }

    // MARK: - Time headers

    private static let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
    private static let weekdays = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]

    /// Reads a Messages time header. Nil when the text isn't one.
    static func parseTime(_ raw: String, now: Date = Date()) -> Date? {
        var s = raw.lowercased().replacingOccurrences(of: ",", with: " ").replacingOccurrences(of: "\u{202F}", with: " ")
            .replacingOccurrences(of: " at ", with: " ").trimmingCharacters(in: .whitespaces)
        while s.contains("  ") { s = s.replacingOccurrences(of: "  ", with: " ") }
        guard s.count <= 32 else { return nil }
        // The time: 21:05, 9:05 pm, 9.05am
        guard let tm = s.firstMatch(of: #/(\d{1,2})[:.](\d{2})\s*(am|pm)?$/#) else { return nil }
        var hour = Int(tm.1) ?? 0
        let minute = Int(tm.2) ?? 0
        if let ap = tm.3 {
            if ap == "pm" && hour < 12 { hour += 12 }
            if ap == "am" && hour == 12 { hour = 0 }
        }
        guard hour < 24, minute < 60 else { return nil }
        let rest = String(s[s.startIndex..<tm.range.lowerBound]).trimmingCharacters(in: .whitespaces)
        let cal = Calendar.current
        let today = cal.startOfDay(for: now)
        func at(_ day: Date) -> Date? { cal.date(bySettingHour: hour, minute: minute, second: 0, of: day) }

        if rest.isEmpty || rest == "today" { return at(today) }
        if rest == "yesterday" { return cal.date(byAdding: .day, value: -1, to: today).flatMap(at) }
        let tokens = rest.split(separator: " ").map(String.init)
        // "monday", "mon": the last such day (within the past week).
        if tokens.count == 1, let wd = weekdays.firstIndex(where: { tokens[0].hasPrefix($0) }) {
            for back in 1...7 {
                let d = cal.date(byAdding: .day, value: -back, to: today)!
                if cal.component(.weekday, from: d) - 1 == wd { return at(d) }
            }
            return nil
        }
        // "mon 21 sep", "21 sep 2026", "sep 21", "21/09/2026"
        var day: Int?, month: Int?, year: Int?
        for t in tokens {
            if weekdays.contains(where: { t.hasPrefix($0) }) { continue }
            if let m = months.firstIndex(where: { t.hasPrefix($0) }) { month = m + 1; continue }
            if let dm = t.firstMatch(of: #/^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?$/#) {
                day = Int(dm.1); month = Int(dm.2)
                if let y = dm.3.flatMap({ Int($0) }) { year = y < 100 ? 2000 + y : y }
                continue
            }
            if let n = Int(t) {
                if n > 31 { year = n } else if day == nil { day = n }
                continue
            }
            return nil
        }
        guard let d = day, let m = month, (1...31).contains(d), (1...12).contains(m) else { return nil }
        let thisYear = cal.component(.year, from: now)
        var comps = DateComponents(year: year ?? thisYear, month: m, day: d)
        guard var date = cal.date(from: comps) else { return nil }
        if year == nil && date > now {
            comps.year = thisYear - 1
            date = cal.date(from: comps) ?? date
        }
        return at(date)
    }
}
