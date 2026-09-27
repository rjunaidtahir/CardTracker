import XCTest
import SwiftData
@testable import UAEFinancialTracker

/// One card stays one card, whatever order its messages arrive in and wherever they come from (the Shortcut,
/// screenshots without a bank name, a statement without a card number, a statement PDF).
@MainActor
final class CardIdentityTests: XCTestCase {
    private var containers: [ModelContainer] = []

    private func makeEngine() throws -> Engine {
        let schema = Schema(AppModels.all)
        let container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
        containers.append(container)
        return Engine(context: container.mainContext)
    }

    private let t0 = Date(timeIntervalSince1970: 1_758_700_000)
    private func at(_ minutes: Double) -> Date { t0.addingTimeInterval(minutes * 60) }

    private let named = "Your RAKBANK Credit Card ending 0552 has been used for AED 45.00 at CARREFOUR on 20/09/2026. Available limit AED 4,339.57"
    private let unnamed = "Your Credit Card ending 0552 was used for AED 22.75 at TALABAT on 21/09/2026 13:05. Available limit AED 4,384.47"
    private let statement = "Dear Customer, your Credit Card statement has been generated. Total Amount Due AED 317.11, Minimum Amount Due AED 25.00. Payment Due Date 30/09/2026."
    private let payment = "Payment of AED 318.00 received towards your credit card. Thank you."

    /// The case from the first real use: one RAKBANK card read as three.
    func testOneBankCardStaysOneCard() throws {
        let engine = try makeEngine()
        XCTAssertEqual(engine.ingest(body: named, sender: "RAKBANK", receivedAt: at(0), timeKnown: true, source: "Test"), .transaction)
        // No sender and no bank name (e.g. from a screenshot): same last 4 digits → the RAKBANK card.
        XCTAssertEqual(engine.ingest(body: unnamed, sender: nil, receivedAt: at(10), timeKnown: true, source: "Test"), .transaction)
        // No card number: the bank's only credit card.
        XCTAssertEqual(engine.ingest(body: statement, sender: "RAKBANK", receivedAt: at(20), timeKnown: true, source: "Test"), .statement)
        XCTAssertEqual(engine.ingest(body: payment, sender: "RAKBANK", receivedAt: at(30), timeKnown: true, source: "Test"), .transaction)
        // A reminder for the same statement doesn't add a second one.
        let reminder = statement.replacingOccurrences(of: "has been generated", with: "is due soon")
        XCTAssertEqual(engine.ingest(body: reminder, sender: "RAKBANK", receivedAt: at(40), timeKnown: true, source: "Test"), .statement)

        let cards = engine.fetchAll(Card.self)
        XCTAssertEqual(cards.map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(StatementRecord.self).count, 1)
        XCTAssertEqual(engine.fetchAll(StatementRecord.self).first?.cardKey, "RAKBANK ·0552")
        let txns = engine.fetchAll(Txn.self)
        XCTAssertEqual(txns.count, 3)
        XCTAssertTrue(txns.allSatisfy { $0.cardKey == "RAKBANK ·0552" }, txns.map { $0.cardKey ?? "nil" }.joined(separator: ","))

        // Reading everything again gives the same single card.
        _ = engine.rereadAll()
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(StatementRecord.self).count, 1)
        XCTAssertTrue(engine.fetchAll(Txn.self).allSatisfy { $0.cardKey == "RAKBANK ·0552" })
    }

    /// The unnamed message arrives first (so it starts as "Other bank"): it's joined up once the bank is known.
    func testOtherBankCopyIsMergedWhenTheBankShowsUp() throws {
        let engine = try makeEngine()
        engine.ingest(body: unnamed, sender: nil, receivedAt: at(0), timeKnown: true, source: "Test")
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["Other bank ·0552"])
        engine.ingest(body: named, sender: "RAKBANK", receivedAt: at(10), timeKnown: true, source: "Test")
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(Txn.self).count, 2)
        XCTAssertTrue(engine.fetchAll(Txn.self).allSatisfy { $0.cardKey == "RAKBANK ·0552" })
        // Stays merged after a re-read.
        _ = engine.rereadAll()
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
    }

    /// Data from the earlier version (three cards) is put back together.
    func testRepairOfSplitCards() throws {
        let engine = try makeEngine()
        func card(_ bank: String, _ l4: String?) -> Card {
            let c = Card(key: "\(bank) ·\(l4 ?? "????")", bank: bank, last4: l4, cardType: "CREDIT", counted: true)
            engine.context.insert(c)
            return c
        }
        _ = card("RAKBANK", "0552")
        let other = card("Other bank", "0552")
        let noNumber = card("RAKBANK", nil)
        let spelled = card("RAK BANK", "7777")
        other.nickname = "Wife's card"
        let t1 = Txn(source: "SMS", timestamp: at(0), bank: "Other bank", merchant: "TALABAT", amountMinor: 2275, currency: "AED", aedMinor: 2275, type: "PURCHASE")
        t1.cardKey = other.key
        engine.context.insert(t1)
        let s1 = StatementRecord(cardKey: noNumber.key, bank: "RAKBANK", cardLast4: nil, receivedAt: at(1), balanceMinor: 31711, currency: "AED", dueEpochDay: 20726)
        let s2 = StatementRecord(cardKey: noNumber.key, bank: "RAKBANK", cardLast4: nil, receivedAt: at(2), balanceMinor: 31711, currency: "AED", dueEpochDay: 20726)
        engine.context.insert(s1)
        engine.context.insert(s2)
        let t2 = Txn(source: "SMS", timestamp: at(3), bank: "RAK BANK", merchant: "NOON", amountMinor: 1000, currency: "AED", aedMinor: 1000, type: "PURCHASE")
        t2.cardKey = spelled.key
        engine.context.insert(t2)
        engine.save()

        engine.tidyCards()
        let keys = Set(engine.fetchAll(Card.self).map(\.key))
        // "RAK BANK" is RAKBANK; the "Other bank" copy of 0552 joins the RAKBANK card. The numberless card stays:
        // RAKBANK now has two cards, so which one it belongs to can't be told.
        XCTAssertEqual(keys, ["RAKBANK ·0552", "RAKBANK ·7777", "RAKBANK ·????"])
        XCTAssertEqual(engine.fetchAll(Txn.self).first { $0.merchant == "TALABAT" }?.cardKey, "RAKBANK ·0552")
        XCTAssertEqual(engine.fetchAll(Txn.self).first { $0.merchant == "NOON" }?.cardKey, "RAKBANK ·7777")
        let sts = engine.fetchAll(StatementRecord.self)
        XCTAssertEqual(sts.count, 1, "one statement per card and due date")
        XCTAssertEqual(engine.card("RAKBANK ·0552")?.nickname, "Wife's card")
    }

    func testNumberlessCardMergesIntoTheOnlyCard() throws {
        let engine = try makeEngine()
        let real = Card(key: "RAKBANK ·0552", bank: "RAKBANK", last4: "0552", cardType: "CREDIT", counted: true)
        let blank = Card(key: "RAKBANK ·????", bank: "RAKBANK", last4: nil, cardType: "CREDIT", counted: true)
        engine.context.insert(real)
        engine.context.insert(blank)
        let s = StatementRecord(cardKey: blank.key, bank: "RAKBANK", cardLast4: nil, receivedAt: at(0), balanceMinor: 31711, currency: "AED", dueEpochDay: 20726)
        engine.context.insert(s)
        engine.save()
        engine.tidyCards()
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(StatementRecord.self).first?.cardKey, "RAKBANK ·0552")
    }

    func testMergeByHandSticks() throws {
        let engine = try makeEngine()
        engine.ingest(body: named, sender: "RAKBANK", receivedAt: at(0), timeKnown: true, source: "Test")
        engine.ingest(body: "Purchase of AED 10.00 with Credit Card ending 9999 at NOON. Avl Cr. Limit is AED 100.00",
                      sender: "EmiratesNBD", receivedAt: at(5), timeKnown: true, source: "Test")
        XCTAssertEqual(engine.fetchAll(Card.self).count, 2)
        let from = try XCTUnwrap(engine.card("Emirates NBD ·9999"))
        let into = try XCTUnwrap(engine.card("RAKBANK ·0552"))
        engine.mergeCards(from, into: into)
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        _ = engine.rereadAll()
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(Txn.self).count, 2)
    }

    func testBankNamesAndStatementSuggestion() throws {
        let engine = try makeEngine()
        XCTAssertEqual(engine.canonicalBank("RAK BANK"), "RAKBANK")
        XCTAssertEqual(engine.canonicalBank("National Bank of Ras Al Khaimah"), "RAKBANK")
        XCTAssertEqual(engine.canonicalBank(""), "Other bank")
        let real = Card(key: "RAKBANK ·0552", bank: "RAKBANK", last4: "0552", cardType: "CREDIT", counted: true)
        let copy = Card(key: "Other bank ·0552", bank: "Other bank", last4: "0552", cardType: "CREDIT", counted: true)
        engine.context.insert(real)
        engine.context.insert(copy)
        engine.save()
        // Two cards end in 0552: the statement still goes to the RAKBANK one, never a new card.
        XCTAssertEqual(engine.suggestedCard(bank: "National Bank of Ras Al Khaimah", last4: "0552", isAccount: false)?.key, "RAKBANK ·0552")
        XCTAssertEqual(engine.suggestedCard(bank: nil, last4: "0552", isAccount: false)?.key, "RAKBANK ·0552")
        XCTAssertEqual(engine.suggestedCard(bank: "RAKBANK", last4: nil, isAccount: false)?.key, "RAKBANK ·0552")
        XCTAssertNil(engine.suggestedCard(bank: "FAB", last4: "1234", isAccount: false))
        XCTAssertEqual(engine.cards(endingIn: "0552").count, 2)
    }

    func testImportWithTheBankYouChose() throws {
        let engine = try makeEngine()
        let p = engine.preview([unnamed, statement, "123456 is your OTP for AED 50.00"], bankHint: "RAK BANK")
        XCTAssertEqual(p.lines.count, 1)
        XCTAssertEqual(p.lines.first?.count, 2)
        XCTAssertEqual(p.lines.first?.isNew, true)
        XCTAssertTrue(p.lines.first?.label.contains("RAKBANK") ?? false, p.lines.first?.label ?? "")
        XCTAssertEqual(p.skipped, 1)
        let tally = MessageFiles.importPaste("\(unnamed)\n\n\(statement)", engine: engine, bankHint: "RAKBANK")
        XCTAssertEqual(tally.transactions, 1)
        XCTAssertEqual(tally.statements, 1)
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        // Now the card exists, the preview names it.
        let again = engine.preview([unnamed], bankHint: nil)
        XCTAssertEqual(again.lines.first?.isNew, false)
        // Stays RAKBANK after a re-read (stored under the bank's sender name).
        _ = engine.rereadAll()
        XCTAssertEqual(engine.fetchAll(Card.self).map(\.key), ["RAKBANK ·0552"])
        XCTAssertEqual(engine.fetchAll(SmsRecord.self).first?.sender, "RAKBANK")
    }

    func testScreenshotHeaderAndGuess() throws {
        let engine = try makeEngine()
        typealias L = ScreenshotReader.Line
        let lines = [
            L(text: "12:11", minX: 0.08, maxX: 0.16, top: 0.015, height: 0.02),
            L(text: "RAK BANK >", minX: 0.40, maxX: 0.60, top: 0.11, height: 0.02),
            L(text: "Yesterday 21:05", minX: 0.38, maxX: 0.62, top: 0.20, height: 0.015),
            L(text: "Your Credit Card ending 0552 was used", minX: 0.06, maxX: 0.70, top: 0.23, height: 0.02),
            L(text: "for AED 22.75 at TALABAT.", minX: 0.06, maxX: 0.60, top: 0.255, height: 0.02),
        ]
        let msgs = ScreenshotReader.messages(from: lines, bankOf: { MessageFiles.bank(ofHeader: $0) })
        XCTAssertEqual(msgs.count, 1)
        XCTAssertEqual(msgs.first?.sender, "RAKBANK")
        // A message body mentioning a bank is not taken for the conversation name.
        let bodyAtTop = [L(text: "Your RAKBANK Credit Card ending 0552", minX: 0.06, maxX: 0.72, top: 0.12, height: 0.02)]
        XCTAssertNil(MessageFiles.bank(ofHeader: bodyAtTop[0].text))
        // Without a name at the top: guessed from your card with the same last 4 digits.
        XCTAssertNil(MessageFiles.guessBank(for: [unnamed], engine: engine))
        engine.ingest(body: named, sender: "RAKBANK", receivedAt: at(0), timeKnown: true, source: "Test")
        XCTAssertEqual(MessageFiles.guessBank(for: [unnamed], engine: engine), "RAKBANK")
        XCTAssertEqual(MessageFiles.guessBank(for: ["Thank you for banking with RAK BANK. AED 10 spent"], engine: engine), "RAKBANK")
    }

    func testAutomationStatus() {
        let saved = (Settings.automationLastRun, Settings.automationLinkOpenedAt)
        defer { Settings.automationLastRun = saved.0; Settings.automationLinkOpenedAt = saved.1 }
        Settings.automationLastRun = nil
        Settings.automationLinkOpenedAt = nil
        XCTAssertEqual(AutomationStatus.current, .off)
        Settings.automationLinkOpenedAt = Date()
        XCTAssertEqual(AutomationStatus.current, .waiting)
        let run = Date()
        Settings.automationLastRun = run
        XCTAssertEqual(AutomationStatus.current, .on(last: run))
        XCTAssertTrue(AutomationStatus.current.isOn)
    }
}
