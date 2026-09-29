import XCTest
import Shared
import SwiftData
import UIKit
import PDFKit
@testable import UAEFinancialTracker

@MainActor
final class EngineTests: XCTestCase {
    private func makeEngine() throws -> Engine {
        let schema = Schema(AppModels.all)
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        containers.append(container)
        // Tests are written for a UAE phone, whatever region the simulator is set to.
        Bridge.shared.setHomeCurrency(code: "AED")
        Bridge.shared.setMonthFirstDates(value: false)
        MoneyText.home = "AED"
        return Engine(context: container.mainContext)
    }

    private var containers: [ModelContainer] = []

    private let enbd = "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12"

    func testBankMessageFromShortcut() throws {
        let engine = try makeEngine()
        let o = engine.ingest(body: enbd, sender: "EmiratesNBD", receivedAt: Date(), timeKnown: true, source: "Shortcut", requireBankLike: true)
        XCTAssertEqual(o, .transaction)
        let txns = engine.fetchAll(Txn.self)
        XCTAssertEqual(txns.count, 1)
        XCTAssertEqual(txns[0].amountMinor, 2872)
        XCTAssertEqual(txns[0].aedMinor, 2872)
        XCTAssertEqual(txns[0].cardLast4, "3944")
        XCTAssertEqual(txns[0].availableMinor, 88512)
        let cards = engine.fetchAll(Card.self)
        XCTAssertEqual(cards.map(\.key), ["Emirates NBD ·3944"])
        XCTAssertTrue(cards[0].counted)
    }

    func testDuplicatesOtpsAndOtherMessages() throws {
        let engine = try makeEngine()
        let now = Date()
        XCTAssertEqual(engine.ingest(body: enbd, sender: "EmiratesNBD", receivedAt: now, timeKnown: true, source: "Shortcut"), .transaction)
        // The same message pasted later is recognised.
        XCTAssertEqual(engine.ingest(body: enbd, sender: nil, receivedAt: now.addingTimeInterval(3600), timeKnown: false, source: "Paste"), .duplicate)
        XCTAssertEqual(engine.ingest(body: "123456 is your OTP for AED 50.00 on card ending 1234", sender: nil, receivedAt: now, timeKnown: false, source: "Paste"), .otp)
        XCTAssertEqual(engine.ingest(body: "See you at 7, dinner is AED 50 each", sender: "+971501234567", receivedAt: now, timeKnown: true, source: "Shortcut", requireBankLike: true), .notBank)
        XCTAssertEqual(engine.fetchAll(SmsRecord.self).count, 1)
        XCTAssertEqual(engine.fetchAll(Txn.self).count, 1)
    }

    func testPasteSplitsMessagesAndReadsAnyBank() throws {
        let engine = try makeEngine()
        let paste = """
        \(enbd)

        Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00.

        RAKBANK: AED 1,200.00 debited from your A/C XXXX5566 on 19/09/2026 for DEWA BILL PAYMENT.
        """
        XCTAssertEqual(MessageFiles.splitPaste(paste).count, 3)
        let tally = MessageFiles.importPaste(paste, engine: engine)
        XCTAssertEqual(tally.transactions, 3)
        let banks = Set(engine.fetchAll(Txn.self).map(\.bank))
        XCTAssertEqual(banks, ["Emirates NBD", "Other bank", "RAKBANK"])
    }

    func testStatementSmsAndCategoryLearning() throws {
        let engine = try makeEngine()
        let o = engine.ingest(
            body: "HSBC Credit Card ending *** 5258 Statement Date 11/08/2026. Total Amt Due AED 79.20, Due Date 05/09/2026. Min. Amt Due AED 33.70.",
            sender: "HSBC-UAE", receivedAt: Date(), timeKnown: true, source: "Shortcut"
        )
        XCTAssertEqual(o, .statement)
        let st = try XCTUnwrap(engine.fetchAll(StatementRecord.self).first)
        XCTAssertEqual(st.balanceMinor, 7920)
        XCTAssertEqual(st.minimumDueMinor, 3370)

        engine.ingest(body: enbd, sender: "EmiratesNBD", receivedAt: Date(), timeKnown: true, source: "Shortcut")
        let t = try XCTUnwrap(engine.fetchAll(Txn.self).first)
        engine.setCategory(t, categoryId: 7, applyToMerchant: true)
        // A re-read keeps your category.
        _ = engine.rereadAll()
        XCTAssertEqual(engine.fetchAll(Txn.self).first?.categoryId, 7)
    }

    func testFixAndNotATransaction() throws {
        let engine = try makeEngine()
        let o = engine.ingest(body: "Dear customer, AED 75.00 has been processed on your account. Ref 99812.", sender: "EmiratesNBD", receivedAt: Date(), timeKnown: true, source: "Shortcut")
        if o == .failed {
            let sms = try XCTUnwrap(engine.fetchAll(SmsRecord.self).first)
            engine.saveFix(sms, type: .purchase, amountMinor: 7500, currency: "aed", merchant: "Gym", cardLast4: "3944", cardType: .credit, date: Date())
            XCTAssertEqual(engine.fetchAll(Txn.self).first?.merchant, "Gym")
            XCTAssertEqual(sms.status, SmsStatus.transaction)
            engine.saveFix(sms, type: nil, amountMinor: 0, currency: "AED", merchant: "", cardLast4: nil, cardType: .credit, date: Date())
            XCTAssertEqual(engine.fetchAll(Txn.self).count, 0)
            XCTAssertEqual(sms.status, SmsStatus.ignored)
        }
    }

    func testTypedEntryAndRates() throws {
        let engine = try makeEngine()
        XCTAssertTrue(engine.addTyped("lunch 45"))
        XCTAssertFalse(engine.addTyped("lunch"))
        XCTAssertTrue(engine.addTyped("usd 10 netflix"))
        let usd = try XCTUnwrap(engine.fetchAll(Txn.self).first { $0.currency == "USD" })
        engine.setRate(currency: "USD", rate: "4")
        XCTAssertEqual(usd.aedMinor, 4000)
        XCTAssertEqual(MoneyText.parse("1,234.5"), 123450)
        XCTAssertEqual(MoneyText.amount(123450), "1,234.50")
    }

    func testSmsBackupFile() throws {
        let engine = try makeEngine()
        let xml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <smses count="3">
          <sms protocol="0" address="EmiratesNBD" date="1758100000000" type="1" body="\(enbd)" read="1" />
          <sms protocol="0" address="EmiratesNBD" date="1758100000000" type="2" body="sent by me" read="1" />
          <sms protocol="0" address="Mum" date="1758100000000" type="1" body="Call me" read="1" />
        </smses>
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("backup.xml")
        try xml.data(using: .utf8)!.write(to: url)
        let tally = MessageFiles.importFile(url, engine: engine)
        XCTAssertEqual(tally.transactions, 1)
        XCTAssertEqual(engine.fetchAll(SmsRecord.self).count, 1)
    }

    /// Draws a statement into a real PDF and reads it back through PDFKit, as when a user opens one.
    func testStatementPdf() throws {
        let engine = try makeEngine()
        let pdf = FileManager.default.temporaryDirectory.appendingPathComponent("statement.pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842))
        try renderer.writePDF(to: pdf) { ctx in
            ctx.beginPage()
            let font = UIFont(name: "Helvetica", size: 10)!
            let bold = UIFont(name: "Helvetica-Bold", size: 14)!
            func put(_ s: String, _ x: CGFloat, _ y: CGFloat, _ f: UIFont = font, right: Bool = false) {
                let a: [NSAttributedString.Key: Any] = [.font: f]
                let w = (s as NSString).size(withAttributes: a).width
                (s as NSString).draw(at: CGPoint(x: right ? x - w : x, y: y), withAttributes: a)
            }
            put("Credit Card Statement", 40, 40, bold)
            put("Card Number", 40, 80); put("XXXX XXXX XXXX 4321", 200, 80)
            put("Statement Date", 40, 96); put("10/09/2026", 200, 96)
            put("Payment Due Date", 40, 112); put("05/10/2026", 200, 112)
            put("Credit Limit", 40, 128); put("AED 20,000.00", 200, 128)
            put("Previous Balance", 40, 144); put("AED 0.00", 200, 144)
            put("Total Amount Due", 40, 160); put("AED 1,250.00", 200, 160)
            put("Minimum Amount Due", 40, 176); put("AED 62.50", 200, 176)
            put("Date", 40, 220); put("Description", 120, 220); put("Amount (AED)", 550, 220, right: true)
            put("12/08/2026", 40, 238); put("CARREFOUR CITY CENTRE DUBAI", 120, 238); put("250.00", 550, 238, right: true)
            put("20/08/2026", 40, 254); put("NOON.COM", 120, 254); put("1,000.00", 550, 254, right: true)
        }
        let doc = try XCTUnwrap(PDFDocument(url: pdf))
        let r = PdfStatement.read(doc)
        XCTAssertEqual(r.cardLast4, "4321")
        XCTAssertEqual(r.totalDueMinor, 125000)
        XCTAssertEqual(r.minimumDueMinor, 6250)
        XCTAssertEqual(r.creditLimitMinor, 2000000)
        XCTAssertEqual(r.rows.count, 2)
        XCTAssertEqual(r.addsUp, 1)

        let key = engine.addCard(bank: "FAB", last4: "4321", kind: .credit, nickname: nil, family: false)
        let missing = engine.missingRows(r, cardKey: key)
        XCTAssertEqual(missing.count, 2)
        let done = engine.applyStatement(r, cardKey: key, addRows: missing)
        XCTAssertTrue(done.contains("2 missing transactions"))
        XCTAssertEqual(engine.missingRows(r, cardKey: key).count, 0)
        XCTAssertEqual(engine.fetchAll(StatementRecord.self).first?.balanceMinor, 125000)
    }
}
