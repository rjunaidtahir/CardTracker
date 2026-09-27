import Foundation
import PDFKit
import UIKit
import Shared

/// Hands the characters of a statement PDF (with their positions) to the shared statement reader.
enum PdfStatement {
    /// One line per printed character: page, x, y (from the top of the page), width, font size, text.
    ///
    /// Reads by *visual line* (PDFKit's `selectionsByLine()`) rather than by walking `page.string` character by
    /// character with `characterBounds(at:)`. Real statements are often built from many short, separately-placed
    /// runs of text per page — one per label, one per value, one per transaction field, not one continuous run per
    /// line — and when the runs aren't placed in a simple top-to-bottom reading order, `characterBounds(at:)` can
    /// silently return an empty or wrong rect for most of the page, dropped by the `isNull`/`isEmpty` guard below.
    /// `selectionsByLine()` finds each line by its own geometry instead of by indexing into one reconstructed
    /// string, so it holds up on that kind of PDF. Character positions within a line are then spread evenly across
    /// the line's own measured width: not pixel-exact, but it preserves what the shared reader actually needs —
    /// each character's rough column, and a visibly bigger gap wherever the line has one (a run of spaces between
    /// columns advances the position without emitting anything, so the next real character still lands after a
    /// gap proportional to how wide that run was).
    static func characters(_ doc: PDFDocument) -> String {
        var out = ""
        out.reserveCapacity(200_000)
        for p in 0..<doc.pageCount {
            guard let page = doc.page(at: p) else { continue }
            let box = page.bounds(for: .mediaBox)
            guard let pageSelection = page.selection(for: box) else { continue }
            for line in pageSelection.selectionsByLine() {
                guard let text = line.string, !text.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
                let bounds = line.bounds(for: page)
                guard bounds.width > 0, bounds.height > 0 else { continue }
                let y = box.maxY - bounds.minY
                let size = max(4, bounds.height * 0.8)
                let chars = Array(text)
                let step = bounds.width / CGFloat(chars.count)
                for (i, ch) in chars.enumerated() {
                    if ch.isWhitespace { continue }
                    let x = bounds.minX - box.minX + CGFloat(i) * step
                    out += "\(p)\t\(fmt(x))\t\(fmt(y))\t\(fmt(step))\t\(fmt(size))\t\(ch)\n"
                }
            }
        }
        return out
    }

    private static func fmt(_ v: CGFloat) -> String { String(format: "%.2f", Double(v)) }

    static func read(_ doc: PDFDocument) -> StatementReading {
        let year = Int32(Calendar.current.component(.year, from: Date()))
        let tsv = characters(doc)
        let reading = Bridge.shared.readStatementTsv(tsv: tsv, thisYear: year)
        // The position-based read didn't find everything (missing summary figures, say, even with some rows found):
        // fall back to a plain-text read and use whichever found more, rather than only falling back on total silence.
        if let text = doc.string, !text.isEmpty {
            let fromText = Bridge.shared.readStatementText(text: text, thisYear: year)
            let readingScore = reading.rows.count + (reading.totalDueMinor >= 0 ? 1 : 0) + (reading.dueEpochDay >= 0 ? 1 : 0)
            let textScore = fromText.rows.count + (fromText.totalDueMinor >= 0 ? 1 : 0) + (fromText.dueEpochDay >= 0 ? 1 : 0)
            if textScore > readingScore { return fromText }
        }
        return reading
    }
}
