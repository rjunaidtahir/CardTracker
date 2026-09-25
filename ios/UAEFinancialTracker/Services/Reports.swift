import Foundation
import UIKit

/// Spending reports for a period: PDF (A4) and CSV for Excel, the same content as the Android app's.
@MainActor
enum Reports {
    struct Content {
        let period: Period
        let previous: Period?
        let spent: Int64
        let previousSpent: Int64?
        let moneyIn: Int64
        let spends: Int
        let perDay: Int64
        let categories: [(categoryId: Int64?, amount: Int64)]
        let budgets: [BudgetStatus]
        let cards: [(label: String, amount: Int64)]
        let merchants: [(name: String, amount: Int64, count: Int)]
        let txns: [Txn]
        let cardLabels: [String: String]
    }

    static func collect(engine: Engine, period: Period) -> Content {
        let cards = engine.fetchAll(Card.self)
        let cardMap = SpendMath.cardMap(cards)
        let excluded = Set(cards.filter { !$0.counted }.map(\.key))
        let all = engine.fetchAll(Txn.self)
        let views = all.map(TxnView.init)
        let inPeriod = views.filter { period.contains($0.day) }
        let prev = period.previous()
        let spent = SpendRules.total(inPeriod, excluded: excluded)
        let prevSpent = prev.map { p in SpendRules.total(views.filter { p.contains($0.day) }, excluded: excluded) }
        let firstDay = period.start ?? (views.map(\.day).min() ?? Dates.today())
        let lastDay = min(period.end ?? Dates.today(), Dates.today())
        let days = max(1, lastDay - firstDay + 1)
        let month = Period.thisMonth()
        var monthSpent: [Int64?: Int64] = [:]
        for c in Insights.byCategory(views.filter { month.contains($0.day) }, excluded: excluded) { monthSpent[c.categoryId] = c.amount }
        let limits = Dictionary(engine.fetchAll(Budget.self).map { ($0.categoryId, $0.limitMinor) }, uniquingKeysWith: { a, _ in a })
        let ids = Set(inPeriod.map(\.id))
        return Content(
            period: period, previous: prev, spent: spent, previousSpent: prevSpent,
            moneyIn: inPeriod.filter { $0.type == .transferIn }.reduce(0) { $0 + ($1.aed ?? 0) },
            spends: inPeriod.filter { SpendRules.counted($0, excluded: excluded) && ($0.type == .purchase || $0.type == .refund) }.count,
            perDay: spent / days,
            categories: Insights.byCategory(inPeriod, excluded: excluded),
            budgets: Budgets.status(limits: limits, spent: monthSpent),
            cards: Insights.byCard(inPeriod, excluded: excluded).map { (label: $0.cardKey.isEmpty ? "Typed" : (cardMap[$0.cardKey]?.label ?? $0.cardKey), amount: $0.amount) },
            merchants: Insights.topMerchants(inPeriod, excluded: excluded),
            txns: all.filter { ids.contains($0.id) && !($0.cardKey.map { excluded.contains($0) } ?? false) }.sorted { $0.timestamp > $1.timestamp },
            cardLabels: cardMap.mapValues(\.label)
        )
    }

    static func fileBase(_ p: Period) -> String {
        let slug = p.label.lowercased().map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined()
            .replacingOccurrences(of: "--", with: "-").trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return "spending-report-\(slug)"
    }

    private static func typeText(_ t: Txn) -> String {
        switch t.txnType {
        case .purchase: return "Spend"
        case .refund: return "Refund"
        case .payment: return "Card payment"
        case .transferIn: return "Money in"
        case .transferOut: return "Money out"
        }
    }

    private static var title: String {
        let n = Settings.displayName.trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? "Spending report" : "Spending report · \(n)"
    }

    // MARK: CSV

    static func csv(_ d: Content) -> String {
        let plain = MoneyText.plain
        var rows: [[String?]] = []
        rows.append([title])
        rows.append(["Period", d.period.label])
        rows.append(["Generated", Dates.dayTime.string(from: Date())])
        rows.append(["Total spent (AED)", plain(d.spent)])
        if let p = d.previous, let ps = d.previousSpent { rows.append(["Previous period", p.label, plain(ps)]) }
        rows.append(["Money in (AED)", plain(d.moneyIn)])
        rows.append(["Spends", String(d.spends)])
        rows.append(["Average per day (AED)", plain(d.perDay)])
        rows.append([])
        rows.append(["Category", "Amount (AED)", "Share %"])
        for c in d.categories {
            rows.append([Categories.name(c.categoryId), plain(c.amount), d.spent > 0 ? String(c.amount * 100 / d.spent) : "0"])
        }
        rows.append([])
        if !d.budgets.isEmpty {
            rows.append(["Budget (this month)", "Spent (AED)", "Limit (AED)", "Used %"])
            for b in d.budgets { rows.append([Categories.name(b.categoryId), plain(b.spentMinor), plain(b.limitMinor), String(b.percent)]) }
            rows.append([])
        }
        rows.append(["Card", "Spent (AED)"])
        for c in d.cards { rows.append([c.label, plain(c.amount)]) }
        rows.append([])
        rows.append(["Date", "Merchant", "Category", "Card", "Type", "Amount", "Amount (AED)"])
        let f = DateFormatter()
        f.dateFormat = "dd MMM yy"
        f.locale = Locale(identifier: "en_GB")
        for t in d.txns {
            let sign = t.txnType == .purchase || t.txnType == .transferOut ? "-" : "+"
            let cat = t.txnType == .purchase || t.txnType == .refund ? Categories.name(t.categoryId) : ""
            rows.append([
                f.string(from: t.timestamp), t.merchant, cat, t.cardKey.flatMap { d.cardLabels[$0] } ?? "Typed", typeText(t),
                "\(sign)\(t.currency) \(plain(t.amountMinor))", t.aedMinor >= 0 ? plain(t.aedMinor) : "",
            ])
        }
        return rows.map { r in r.map { CSV.escape($0) }.joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
    }

    // MARK: PDF

    static func pdf(_ d: Content) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let margin: CGFloat = 40
        let width = page.width - margin * 2
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        let accent = UIColor(red: 0.05, green: 0.54, blue: 0.29, alpha: 1)
        let muted = UIColor(white: 0.4, alpha: 1)
        let red = UIColor(red: 0.78, green: 0.16, blue: 0.16, alpha: 1)
        let amber = UIColor(red: 0.64, green: 0.37, blue: 0, alpha: 1)
        let green = UIColor(red: 0.05, green: 0.54, blue: 0.29, alpha: 1)

        func font(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont { .systemFont(ofSize: size, weight: weight) }
        @discardableResult
        func text(_ s: String, _ x: CGFloat, _ y: CGFloat, _ f: UIFont, _ c: UIColor = .black, width w: CGFloat? = nil, right: Bool = false) -> CGFloat {
            let attrs: [NSAttributedString.Key: Any] = [.font: f, .foregroundColor: c]
            let ns = s as NSString
            var size = ns.size(withAttributes: attrs)
            var str = s
            if let w, size.width > w {
                while str.count > 1 && (str + "…" as NSString).size(withAttributes: attrs).width > w { str.removeLast() }
                str += "…"
                size = (str as NSString).size(withAttributes: attrs)
            }
            (str as NSString).draw(at: CGPoint(x: right ? x - size.width : x, y: y), withAttributes: attrs)
            return size.height
        }

        return renderer.pdfData { ctx in
            var pageNo = 1
            ctx.beginPage()
            let cg = ctx.cgContext
            // Header band
            accent.setFill()
            cg.fill(CGRect(x: 0, y: 0, width: page.width, height: 150))
            text(title, margin, 30, font(20, .bold), .white)
            text(d.period.label, margin, 58, font(12), .white)
            text(MoneyText.text(d.spent), margin, 78, font(30, .bold), .white)
            text("Generated \(Dates.dayTime.string(from: Date()))", page.width - margin, 32, font(9), .white, right: true)
            if let prev = d.previous, let ps = d.previousSpent, ps > 0 {
                let pct = abs(d.spent - ps) * 100 / ps
                text("\(pct)% \(d.spent >= ps ? "more" : "less") than \(prev.label)", margin, 118, font(11), .white)
            }
            var y: CGFloat = 170
            // Stat boxes
            let boxes = [("Spends", "\(d.spends)"), ("Per day", MoneyText.text(d.perDay)), ("Money in", MoneyText.text(d.moneyIn))]
            let bw = (width - 20) / 3
            for (i, b) in boxes.enumerated() {
                let r = CGRect(x: margin + CGFloat(i) * (bw + 10), y: y, width: bw, height: 54)
                UIColor(white: 0.95, alpha: 1).setFill()
                UIBezierPath(roundedRect: r, cornerRadius: 8).fill()
                text(b.0.uppercased(), r.minX + 10, r.minY + 8, font(8, .semibold), muted)
                text(b.1, r.minX + 10, r.minY + 24, font(15, .bold))
            }
            y += 74

            @MainActor func newPageIfNeeded(_ needed: CGFloat) {
                if y + needed > page.height - margin {
                    ctx.beginPage()
                    pageNo += 1
                    text(title + " · " + d.period.label, margin, 20, font(9), muted)
                    text("Page \(pageNo)", page.width - margin, 20, font(9), muted, right: true)
                    y = 44
                }
            }

            // Categories: donut + table
            text("Spending by category", margin, y, font(13, .bold))
            y += 22
            let top = Array(d.categories.prefix(14))
            let donutCenter = CGPoint(x: margin + 70, y: y + 70)
            var startAngle = -CGFloat.pi / 2
            let total = max(1, top.reduce(0) { $0 + $1.amount })
            for c in top {
                let a = CGFloat(Double(c.amount) / Double(total)) * .pi * 2
                let path = UIBezierPath()
                path.addArc(withCenter: donutCenter, radius: 60, startAngle: startAngle, endAngle: startAngle + a, clockwise: true)
                path.lineWidth = 22
                UIColor(Categories.color(c.categoryId)).setStroke()
                path.stroke()
                startAngle += a
            }
            if top.isEmpty { text("No spending in this period", margin, y, font(10), muted) }
            var ty = y
            for c in top {
                let x = margin + 170
                UIColor(Categories.color(c.categoryId)).setFill()
                UIBezierPath(roundedRect: CGRect(x: x, y: ty + 3, width: 8, height: 8), cornerRadius: 2).fill()
                text(Categories.name(c.categoryId), x + 14, ty, font(10), width: 170)
                text(d.spent > 0 ? "\(c.amount * 100 / d.spent)%" : "", x + 230, ty, font(10), muted, right: true)
                text(MoneyText.amount(c.amount), page.width - margin, ty, font(10, .semibold), right: true)
                ty += 15
            }
            y = max(y + 150, ty + 10)

            // Budgets
            if !d.budgets.isEmpty {
                newPageIfNeeded(40 + CGFloat(d.budgets.count) * 26)
                text("Budgets (this month)", margin, y, font(13, .bold))
                y += 22
                for b in d.budgets {
                    text(Categories.name(b.categoryId), margin, y, font(10), width: 200)
                    text("\(MoneyText.amount(b.spentMinor)) of \(MoneyText.amount(b.limitMinor)) · \(b.percent)%", page.width - margin, y, font(10), muted, right: true)
                    let bar = CGRect(x: margin, y: y + 14, width: width, height: 5)
                    UIColor(white: 0.9, alpha: 1).setFill()
                    UIBezierPath(roundedRect: bar, cornerRadius: 2.5).fill()
                    (b.percent >= 100 ? red : b.percent >= 80 ? amber : green).setFill()
                    UIBezierPath(roundedRect: CGRect(x: bar.minX, y: bar.minY, width: bar.width * min(1, CGFloat(b.percent) / 100), height: 5), cornerRadius: 2.5).fill()
                    y += 26
                }
                y += 8
            }

            // Cards and merchants
            newPageIfNeeded(60 + CGFloat(max(min(d.cards.count, 10), d.merchants.count)) * 15)
            text("By card", margin, y, font(13, .bold))
            text("Top merchants", margin + width / 2 + 10, y, font(13, .bold))
            y += 22
            var cy = y, my = y
            for c in d.cards.prefix(10) {
                text(c.label, margin, cy, font(10), width: width / 2 - 90)
                text(MoneyText.amount(c.amount), margin + width / 2 - 10, cy, font(10, .semibold), right: true)
                cy += 15
            }
            for m in d.merchants {
                text(m.name, margin + width / 2 + 10, my, font(10), width: width / 2 - 100)
                text(MoneyText.amount(m.amount), page.width - margin, my, font(10, .semibold), right: true)
                my += 15
            }
            y = max(cy, my) + 16

            // Transactions
            newPageIfNeeded(60)
            text("Transactions (\(d.txns.count))", margin, y, font(13, .bold))
            y += 22
            let cols: [CGFloat] = [margin, margin + 60, margin + 250, margin + 360]
            @MainActor func header() {
                for (i, h) in ["DATE", "MERCHANT", "CATEGORY", "CARD"].enumerated() { text(h, cols[i], y, font(8, .semibold), muted) }
                text("AMOUNT", page.width - margin, y, font(8, .semibold), muted, right: true)
                y += 14
            }
            header()
            let f = DateFormatter()
            f.dateFormat = "dd MMM yy"
            f.locale = Locale(identifier: "en_GB")
            for t in d.txns {
                if y + 14 > page.height - margin {
                    newPageIfNeeded(1000)
                    header()
                }
                let incoming = t.txnType.isIncoming
                text(f.string(from: t.timestamp), cols[0], y, font(9), muted)
                text(t.merchant, cols[1], y, font(9), width: 180)
                text(t.txnType == .purchase || t.txnType == .refund ? Categories.name(t.categoryId) : typeText(t), cols[2], y, font(9), muted, width: 100)
                text(t.cardKey.flatMap { d.cardLabels[$0] } ?? "Typed", cols[3], y, font(9), muted, width: 85)
                let amount = (incoming ? "+" : "") + (t.currency == "AED" ? "" : t.currency + " ") + MoneyText.amount(t.amountMinor)
                text(amount, page.width - margin, y, font(9, .semibold), incoming ? green : .black, right: true)
                y += 14
            }
        }
    }
}
