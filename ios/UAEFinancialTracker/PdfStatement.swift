import Foundation
import PDFKit
import UIKit
import Shared

/// Hands the characters of a statement PDF (with their positions) to the shared statement reader.
enum PdfStatement {
    /// One line per printed character: page, x, y (from the top of the page), width, font size, text.
    static func characters(_ doc: PDFDocument) -> String {
        var out = ""
        out.reserveCapacity(200_000)
        for p in 0..<doc.pageCount {
            guard let page = doc.page(at: p), let text = page.string else { continue }
            let ns = text as NSString
            let box = page.bounds(for: .mediaBox)
            let attributed = page.attributedString
            for i in 0..<ns.length {
                let ch = ns.substring(with: NSRange(location: i, length: 1))
                if ch.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || ch == "\t" { continue }
                let r = page.characterBounds(at: i)
                if r.isNull || r.isEmpty || r.width <= 0 { continue }
                var size = r.height * 0.8
                if let a = attributed, i < a.length, let font = a.attribute(.font, at: i, effectiveRange: nil) as? UIFont {
                    size = font.pointSize
                }
                let x = r.minX - box.minX
                let y = box.maxY - r.minY
                out += "\(p)\t\(fmt(x))\t\(fmt(y))\t\(fmt(r.width))\t\(fmt(size))\t\(ch)\n"
            }
        }
        return out
    }

    private static func fmt(_ v: CGFloat) -> String { String(format: "%.2f", Double(v)) }

    static func read(_ doc: PDFDocument) -> StatementReading {
        let year = Int32(Calendar.current.component(.year, from: Date()))
        let tsv = characters(doc)
        let reading = Bridge.shared.readStatementTsv(tsv: tsv, thisYear: year)
        if reading.rows.isEmpty && reading.totalDueMinor < 0, let text = doc.string, !text.isEmpty {
            let fromText = Bridge.shared.readStatementText(text: text, thisYear: year)
            if !fromText.rows.isEmpty || fromText.totalDueMinor >= 0 { return fromText }
        }
        return reading
    }
}
