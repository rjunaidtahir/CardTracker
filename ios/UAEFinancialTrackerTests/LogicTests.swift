import XCTest
import Shared
import SwiftData
import UIKit
@testable import UAEFinancialTracker

/// The rules ported from the Android app (core/Period, Insights, Planning, CardStatus) and the iPhone-only pieces.
@MainActor
final class LogicTests: XCTestCase {
    private var containers: [ModelContainer] = []

    private func makeEngine() throws -> Engine {
        let schema = Schema(AppModels.all)
        let container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
        containers.append(container)
        // Tests are written for a UAE phone, whatever region the simulator is set to.
        Bridge.shared.setHomeCurrency(code: "AED")
        Bridge.shared.setMonthFirstDates(value: false)
        MoneyText.home = "AED"
        return Engine(context: container.mainContext)
    }

    private func day(_ y: Int, _ m: Int, _ d: Int) -> Int64 { Dates.epochDay(year: y, month: m, day: d) }

    func testDates() {
        XCTAssertEqual(day(1970, 1, 1), 0)
        XCTAssertEqual(day(2026, 9, 23), 20_719)
        XCTAssertEqual(Dates.plusMonths(day(2026, 1, 31), 1), day(2026, 2, 28))
        XCTAssertEqual(Dates.plusMonths(day(2026, 3, 15), -3), day(2025, 12, 15))
        XCTAssertEqual(Dates.ym(day(2026, 9, 1)), "2026-09")
        XCTAssertEqual(Dates.epochDay(Dates.date(epochDay: 20_719)), 20_719)
    }

    func testPeriods() {
        let today = day(2026, 9, 23)
        let month = Period.of(.month, today: today)
        XCTAssertEqual(month.start, day(2026, 9, 1))
        XCTAssertEqual(month.end, day(2026, 9, 30))
        XCTAssertEqual(month.label, "September 2026")
        XCTAssertEqual(month.previous()?.start, day(2026, 8, 1))
        XCTAssertEqual(month.previous()?.end, day(2026, 8, 31))
        let w = Period.of(.w1, today: today)
        XCTAssertEqual(w.start, day(2026, 9, 17))
        XCTAssertEqual(w.label, "17 – 23 Sep 2026")
        XCTAssertEqual(w.previous()?.end, day(2026, 9, 16))
        let m3 = Period.of(.m3, today: today)
        XCTAssertEqual(m3.start, day(2026, 6, 24))
        XCTAssertEqual(m3.label, "24 Jun – 23 Sep 2026")
        XCTAssertNil(Period.all.previous())
        XCTAssertEqual(Period.all.label, "All time")
        let c = Period.custom(day(2026, 9, 10), day(2026, 8, 1))
        XCTAssertEqual(c.start, day(2026, 8, 1))
        XCTAssertEqual(Bucket.of(start: 0, end: 30), .day)
        XCTAssertEqual(Bucket.of(start: 0, end: 100), .week)
        XCTAssertEqual(Bucket.of(start: 0, end: 200), .month)
    }

    func testSpendingAndInsights() {
        let d = day(2026, 9, 10)
        let txns = [
            TxnView(day: d, type: .purchase, aed: 10_000, cardKey: "A", categoryId: 1, merchant: "CARREFOUR", merchantKey: "CARREFOUR"),
            TxnView(day: d, type: .refund, aed: 2_000, cardKey: "A", categoryId: 1, merchant: "CARREFOUR", merchantKey: "CARREFOUR"),
            TxnView(day: d, type: .purchase, aed: 5_000, cardKey: "B", categoryId: 2),
            TxnView(day: d, type: .payment, aed: 50_000, cardKey: "A"),
            TxnView(day: d, type: .transferOut, aed: 7_000, cardKey: "C"),
            TxnView(day: d + 1, type: .purchase, aed: 3_000, cardKey: nil, categoryId: nil, currency: "USD", amountMinor: 816),
        ]
        XCTAssertEqual(SpendRules.total(txns, excluded: []), 16_000)
        XCTAssertEqual(SpendRules.total(txns, excluded: ["B"]), 11_000)
        let cats = Insights.byCategory(txns, excluded: [])
        XCTAssertEqual(cats.map(\.amount), [8_000, 5_000, 3_000])
        XCTAssertEqual(Insights.byCurrency(txns, excluded: []).first?.aedMinor, 3_000)
        let tl = Insights.timeline(txns, excluded: [], start: d, end: d + 2)
        XCTAssertEqual(tl.map(\.amountMinor), [13_000, 3_000, 0])
        let months = Insights.byMonth(txns, excluded: [], endDay: d)
        XCTAssertEqual(months.count, 12)
        XCTAssertEqual(months.last?.amount, 16_000)
        XCTAssertEqual(Insights.topMerchants(txns, excluded: []).first?.name, "CARREFOUR")
    }

    func testRecurring() {
        let today = day(2026, 9, 23)
        func netflix(_ d: Int64, _ amt: Int64 = 5_600) -> TxnView {
            TxnView(day: d, type: .purchase, aed: amt, cardKey: "A", categoryId: 8, merchant: "NETFLIX.COM", merchantKey: "NETFLIX COM")
        }
        let monthly = [netflix(day(2026, 6, 5)), netflix(day(2026, 7, 5)), netflix(day(2026, 8, 5)), netflix(day(2026, 9, 5))]
        let r = Insights.recurring(monthly, today: today)
        XCTAssertEqual(r.count, 1)
        XCTAssertEqual(r.first?.occurrences, 4)
        XCTAssertEqual(r.first?.nextExpected, day(2026, 10, 5))
        // Too irregular, too different in amount, or stale: not recurring.
        XCTAssertTrue(Insights.recurring([netflix(day(2026, 8, 1)), netflix(day(2026, 8, 10)), netflix(day(2026, 9, 5))], today: today).isEmpty)
        XCTAssertTrue(Insights.recurring([netflix(day(2026, 6, 5)), netflix(day(2026, 7, 5), 9_000), netflix(day(2026, 8, 5))], today: today).isEmpty)
        XCTAssertTrue(Insights.recurring([netflix(day(2026, 3, 5)), netflix(day(2026, 4, 5)), netflix(day(2026, 5, 5))], today: today).isEmpty)
    }

    func testStatementStatusAndPayments() {
        let today = day(2026, 9, 23)
        let due = day(2026, 10, 3)
        let stmt = day(2026, 9, 9)
        let bank = [CardPayment(day: day(2026, 9, 15), amountMinor: 50_000, fromBankSms: true)]
        let transfers = [CardPayment(day: day(2026, 9, 14), amountMinor: 50_000, fromBankSms: false), CardPayment(day: day(2026, 9, 20), amountMinor: 10_000, fromBankSms: false)]
        let payments = CardPayments.combine(bank: bank, transfers: transfers)
        XCTAssertEqual(payments.map(\.amountMinor), [50_000, 10_000])
        var s = StatementStatusCalc.of(balance: 100_000, minimumDue: 5_000, dueDay: due, statementDay: stmt, receivedDay: stmt, payments: payments, today: today)
        XCTAssertEqual(s.state, .minPaid)
        XCTAssertEqual(s.remainingMinor, 40_000)
        XCTAssertEqual(s.daysLeft, 10)
        s = StatementStatusCalc.of(balance: 100_000, minimumDue: 5_000, dueDay: due, statementDay: stmt, receivedDay: stmt, payments: [], today: today)
        XCTAssertEqual(s.state, .unpaid)
        XCTAssertEqual(s.text, "Due in 10 days")
        s = StatementStatusCalc.of(balance: 100_000, minimumDue: 5_000, dueDay: due, statementDay: stmt, receivedDay: stmt, payments: [], today: due + 2)
        XCTAssertEqual(s.state, .overdue)
        XCTAssertEqual(s.text, "Overdue by 2 days")
        s = StatementStatusCalc.of(balance: 0, minimumDue: nil, dueDay: due, statementDay: nil, receivedDay: stmt, payments: [], today: today)
        XCTAssertEqual(s.state, .nothingDue)
        XCTAssertEqual(Utilisation(limitMinor: 1_000_000, availableMinor: 750_000).percent, 25)
        XCTAssertEqual(Utilisation(limitMinor: 1_000_000, availableMinor: -50_000).percent, 105)
    }

    func testBudgetsFixedPaymentsGoals() {
        let b = Budgets.status(limits: [1: 100_000, 2: 50_000, 3: 0], spent: [1: 85_000, 2: 60_000, nil: 999])
        XCTAssertEqual(b.map(\.categoryId), [2, 1])
        XCTAssertEqual(b[0].percent, 120)
        XCTAssertEqual(b[0].remainingMinor, -10_000)
        XCTAssertEqual(Budgets.threshold(85), 80)
        XCTAssertEqual(Budgets.threshold(100), 100)
        XCTAssertNil(Budgets.threshold(79))

        let today = day(2026, 2, 10)
        XCTAssertEqual(FixedSchedule.dueDate(day: 31, year: 2026, month: 2), day(2026, 2, 28))
        XCTAssertEqual(FixedSchedule.nextDue(day: 5, today: today, lastPaidYm: nil), day(2026, 2, 5))
        XCTAssertEqual(FixedSchedule.nextDue(day: 5, today: today, lastPaidYm: "2026-02"), day(2026, 3, 5))
        XCTAssertFalse(FixedSchedule.paidThisMonth(lastPaidYm: "2026-01", today: today))
        let month = [FixedSchedule.MonthTxn(cardKey: "A", amountMinor: 649_000, categoryId: nil, type: .transferOut)]
        XCTAssertTrue(FixedSchedule.autoPaid(amountMinor: 650_000, cardKey: "A", categoryId: 4, monthTxns: month))
        XCTAssertFalse(FixedSchedule.autoPaid(amountMinor: 650_000, cardKey: "B", categoryId: 4, monthTxns: month))
        XCTAssertFalse(FixedSchedule.autoPaid(amountMinor: 800_000, cardKey: nil, categoryId: nil, monthTxns: month))

        XCTAssertEqual(Goals.perMonth(targetMinor: 1_000_000, savedMinor: 400_000, targetDay: day(2026, 8, 1), today: day(2026, 2, 10)), 100_000)
        XCTAssertEqual(CardDays.from(statements: [(statementDay: day(2026, 9, 9), dueDay: day(2026, 10, 3), receivedDay: day(2026, 9, 10))], today: day(2026, 9, 23))?.dueDay, 3)
        XCTAssertNil(CardDays.from(statements: [(statementDay: nil, dueDay: day(2026, 8, 14), receivedDay: day(2026, 7, 20))], today: day(2026, 9, 23)))
    }

    func testFixedPaymentsInEngine() throws {
        let engine = try makeEngine()
        let f = FixedPayment(name: "Rent", amountMinor: 650_000, dayOfMonth: 1)
        engine.addFixedPayment(f)
        engine.markPaid(f)
        XCTAssertEqual(f.lastPaidYm, Dates.ym(Dates.today()))
        XCTAssertEqual(engine.fetchAll(Txn.self).first?.source, "Fixed")
        engine.undoPaid(f)
        XCTAssertNil(f.lastPaidYm)
        let r = RecurringPayment(merchant: "NETFLIX.COM", merchantKey: "NETFLIX COM", averageMinor: 5_600, occurrences: 3, lastDay: Dates.today() - 5,
                                 nextExpected: Dates.today() + 25, cardKey: "A", categoryId: 8)
        XCTAssertTrue(engine.trackRecurring(r))
        XCTAssertFalse(engine.trackRecurring(r))
        XCTAssertTrue(engine.isTracked(r))
    }

    func testCustomCategories() throws {
        let engine = try makeEngine()
        let id = engine.addCategory("Pets")
        XCTAssertEqual(id, 15)
        XCTAssertEqual(engine.addCategory("pets"), 15)
        XCTAssertEqual(Categories.name(15), "Pets")
        XCTAssertTrue(engine.addTyped("cat food 80"))
        let t = try XCTUnwrap(engine.fetchAll(Txn.self).first)
        engine.setCategory(t, categoryId: 15, applyToMerchant: true)
        XCTAssertTrue(engine.addTyped("cat food 95"))
        XCTAssertEqual(engine.fetchAll(Txn.self).filter { $0.categoryId == 15 }.count, 2)
    }

    func testTransferPairsMerge() throws {
        let engine = try makeEngine()
        let now = Date()
        let a = engine.ingest(body: "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate \(dmy(now))\nBalance AED 2386.00", sender: "FAB", receivedAt: now, timeKnown: true, source: "Test")
        XCTAssertEqual(a, .transaction)
        let b = engine.ingest(body: "Dear Customer, your funds transfer request of  AED 1,000.00 to IBAN/Account/Card XXXX9940  has been processed successfully from your account/card XXXX8001 on \(dmy(now)) 21:45",
                              sender: "FAB", receivedAt: now.addingTimeInterval(60), timeKnown: true, source: "Test")
        XCTAssertEqual(b, .merged)
        XCTAssertEqual(engine.fetchAll(Txn.self).count, 1)
        XCTAssertEqual(engine.fetchAll(Txn.self).first?.type, TxnKind.transferOut.rawValue)
    }

    private func dmy(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "dd/MM/yyyy"
        return f.string(from: d)
    }

    func testCsvAndZip() throws {
        let csv = CSV.write(["a", "b"], [["x,y", "say \"hi\""], [nil, "line\nbreak"]])
        let rows = CSV.read(csv)
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0]["a"], "x,y")
        XCTAssertEqual(rows[0]["b"], "say \"hi\"")
        XCTAssertNil(rows[1]["a"])
        XCTAssertEqual(rows[1]["b"], "line\nbreak")
        let zip = Zip.write([("one.csv", Data("hello".utf8)), ("two.csv", Data())])
        let back = try Zip.read(zip)
        XCTAssertEqual(back.map(\.0), ["one.csv", "two.csv"])
        XCTAssertEqual(String(decoding: back[0].1, as: UTF8.self), "hello")
        XCTAssertEqual(CRC32.checksum(Data("123456789".utf8)), 0xCBF43926)
    }

    /// A deflated zip like the Android app writes (java.util.zip, made with Python's zipfile).
    func testReadsDeflatedZip() throws {
        let b64 = "UEsDBBQAAAAIAAAAIQCGphA2BwAAAAUAAAAHAAAAc21zLmNzdstIzcnJBwBQSwECFAMUAAAACAAAACEAhqYQNgcAAAAFAAAABwAAAAAAAAAAAAAAgAEAAAAAc21zLmNzdlBLBQYAAAAAAQABADUAAAAsAAAAAAA="
        let entries = try Zip.read(try XCTUnwrap(Data(base64Encoded: b64)))
        XCTAssertEqual(entries.first?.0, "sms.csv")
        XCTAssertEqual(entries.first.map { String(decoding: $0.1, as: UTF8.self) }, "hello")
    }

    func testBackupRoundTrip() throws {
        let a = try makeEngine()
        a.ingest(body: "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12",
                 sender: "EmiratesNBD", receivedAt: Date(), timeKnown: true, source: "Test")
        XCTAssertTrue(a.addTyped("lunch 45"))
        a.setBudgets([1: 150_000])
        a.addGoal(name: "Car", targetMinor: 5_000_000, savedMinor: 100_000, targetDay: nil)
        let t = try XCTUnwrap(a.fetchAll(Txn.self).first { $0.smsId != nil })
        a.setCategory(t, categoryId: 5, applyToMerchant: false)
        let data = Backup.export(engine: a)

        let b = try makeEngine()
        let r = try Backup.restore(data, engine: b)
        XCTAssertEqual(r.sms, 1)
        XCTAssertEqual(r.typed, 1)
        XCTAssertEqual(b.fetchAll(Txn.self).count, 2)
        XCTAssertEqual(b.fetchAll(Txn.self).first { $0.smsId != nil }?.categoryId, 5)
        XCTAssertEqual(b.fetchAll(Budget.self).first?.limitMinor, 150_000)
        XCTAssertEqual(b.fetchAll(Goal.self).first?.name, "Car")
        // Restoring again adds nothing.
        let again = try Backup.restore(data, engine: b)
        XCTAssertEqual(again.sms, 0)
        XCTAssertEqual(again.typed, 0)
        XCTAssertEqual(b.fetchAll(Txn.self).count, 2)
    }

    func testScreenshotTimeHeaders() {
        var cal = Calendar.current
        cal.timeZone = .current
        let now = cal.date(from: DateComponents(year: 2026, month: 9, day: 23, hour: 15, minute: 0))!   // a Wednesday
        func hm(_ d: Date?) -> String? {
            guard let d else { return nil }
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd HH:mm"
            return f.string(from: d)
        }
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Today 09:15", now: now)), "2026-09-23 09:15")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Yesterday 21:05", now: now)), "2026-09-22 21:05")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Yesterday 9:05 PM", now: now)), "2026-09-22 21:05")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Monday 10:00", now: now)), "2026-09-21 10:00")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Mon, 14 Sep at 10:00", now: now)), "2026-09-14 10:00")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("21 Dec 2025 at 18:30", now: now)), "2025-12-21 18:30")
        XCTAssertEqual(hm(ScreenshotReader.parseTime("Sat 3 Oct 08:00", now: now)), "2025-10-03 08:00")
        XCTAssertNil(ScreenshotReader.parseTime("on 21/09/2026 21:05", now: now))
        XCTAssertNil(ScreenshotReader.parseTime("Avl Cr. Limit is AED 885.12", now: now))
    }

    func testScreenshotGrouping() {
        let now = Date()
        typealias L = ScreenshotReader.Line
        let lines = [
            L(text: "9:41", minX: 0.08, maxX: 0.16, top: 0.015, height: 0.02),
            L(text: "EmiratesNBD", minX: 0.36, maxX: 0.64, top: 0.09, height: 0.02),
            L(text: "Yesterday 21:05", minX: 0.38, maxX: 0.62, top: 0.20, height: 0.015),
            L(text: "Purchase of AED 28.72 with Credit", minX: 0.06, maxX: 0.70, top: 0.23, height: 0.02),
            L(text: "Card ending 3944 at MORE VALUE", minX: 0.06, maxX: 0.68, top: 0.255, height: 0.02),
            L(text: "SUPERMARKE, DUBAI.", minX: 0.06, maxX: 0.40, top: 0.28, height: 0.02),
            L(text: "Purchase of AED 75.55 with Credit", minX: 0.06, maxX: 0.70, top: 0.34, height: 0.02),
            L(text: "Card ending 3944 at W Z D WEST", minX: 0.06, maxX: 0.66, top: 0.365, height: 0.02),
            L(text: "Thanks!", minX: 0.75, maxX: 0.93, top: 0.42, height: 0.02),
            L(text: "Text Message • SMS", minX: 0.2, maxX: 0.6, top: 0.93, height: 0.02),
        ]
        let msgs = ScreenshotReader.messages(from: lines, now: now)
        XCTAssertEqual(msgs.count, 2)
        XCTAssertEqual(msgs[0].body, "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI.")
        XCTAssertEqual(msgs[0].sender, "EmiratesNBD")
        XCTAssertNotNil(msgs[0].date)
        XCTAssertEqual(msgs[1].date, msgs[0].date)
        // Without a time header, messages get no date.
        let undated = ScreenshotReader.messages(from: Array(lines[3...5]), now: now)
        XCTAssertEqual(undated.count, 1)
        XCTAssertNil(undated[0].date)
    }

    func testScreenshotOcrOnRenderedImage() async throws {
        // Draw a Messages-like screenshot and read it back with Vision.
        let size = CGSize(width: 1170, height: 2532)
        let img = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            func put(_ s: String, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat, center: Bool = false) {
                let a: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: size), .foregroundColor: UIColor.black]
                let w = (s as NSString).size(withAttributes: a).width
                (s as NSString).draw(at: CGPoint(x: center ? (1170 - w) / 2 : x, y: y), withAttributes: a)
            }
            put("EmiratesNBD", 0, 230, 40, center: true)
            put("Yesterday 21:05", 0, 500, 34, center: true)
            UIColor(white: 0.92, alpha: 1).setFill()
            UIBezierPath(roundedRect: CGRect(x: 40, y: 560, width: 820, height: 250), cornerRadius: 40).fill()
            put("Purchase of AED 28.72 with Credit", 80, 590, 44)
            put("Card ending 3944 at MORE VALUE", 80, 650, 44)
            put("SUPERMARKE, DUBAI.", 80, 710, 44)
        }
        let msgs = await ScreenshotReader.messages(in: img)
        XCTAssertEqual(msgs.count, 1)
        XCTAssertTrue(msgs.first?.body.contains("28.72") ?? false, msgs.first?.body ?? "nothing read")
        XCTAssertNotNil(msgs.first?.date)
        let engine = try makeEngine()
        let r = await MessageFiles.importScreenshots([img], engine: engine)
        XCTAssertEqual(r.tally.transactions, 1)
        XCTAssertEqual(engine.fetchAll(Txn.self).first?.amountMinor, 2872)
    }

    func testRemindersAndAlerts() throws {
        let engine = try makeEngine()
        let today = Dates.today()
        let due = Dates.date(epochDay: today + 3)
        let f = DateFormatter()
        f.dateFormat = "dd/MM/yyyy"
        engine.ingest(body: "HSBC Credit Card ending *** 5258 Statement Date \(f.string(from: Date())). Total Amt Due AED 790.20, Due Date \(f.string(from: due)). Min. Amt Due AED 33.70.",
                      sender: "HSBC-UAE", receivedAt: Date(), timeKnown: true, source: "Test")
        let cards = engine.fetchAll(Card.self)
        let views = engine.fetchAll(Txn.self).map(TxnView.init)
        let dues = CardDues.compute(cards: cards, statements: engine.fetchAll(StatementRecord.self), txns: views)
        XCTAssertEqual(dues.first?.status.state, .unpaid)
        let early = Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: Date())!
        let planned = Notifier.plannedReminders(engine: engine, cards: cards, dues: dues, txns: views, today: today, now: early)
        XCTAssertEqual(planned.map(\.day), [today, today + 2, today + 3])
        XCTAssertTrue(planned[0].body.contains("in 3 days"))

        Settings.bigSpendMinor = 100_000
        engine.ingest(body: "Purchase of AED 1,500.00 with Credit Card ending 3944 at EMIRATES AIRLINE, DUBAI. Avl Cr. Limit is AED 500.00",
                      sender: "EmiratesNBD", receivedAt: Date(), timeKnown: true, source: "Test")
        var sent = Set<String>()
        let alerts = Notifier.computeAlerts(newTxns: engine.takeFresh().map(TxnView.init), engine: engine, sent: &sent)
        XCTAssertTrue(alerts.contains { $0.title.hasPrefix("Big spend") })
        XCTAssertTrue(alerts.contains { $0.title.hasPrefix("Available limit low") })
    }

    func testAppLockHash() {
        let h = AppLock.hash(pin: "1234", salt: "00")
        XCTAssertEqual(h.count, 64)
        XCTAssertEqual(h, AppLock.hash(pin: "1234", salt: "00"))
        XCTAssertNotEqual(h, AppLock.hash(pin: "1235", salt: "00"))
    }

    func testReports() throws {
        let engine = try makeEngine()
        XCTAssertTrue(engine.addTyped("lunch 45"))
        let content = Reports.collect(engine: engine, period: Period.thisMonth())
        XCTAssertEqual(content.spent, 4_500)
        let csv = Reports.csv(content)
        XCTAssertTrue(csv.contains("Total spent (AED),45.00"))
        let pdf = Reports.pdf(content)
        XCTAssertGreaterThan(pdf.count, 1000)
        XCTAssertEqual(Reports.fileBase(Period.month(year: 2026, month: 9)), "spending-report-september-2026")
    }
}
