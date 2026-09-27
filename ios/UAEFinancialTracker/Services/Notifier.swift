import Foundation
import UserNotifications
import WidgetKit

/// Due-date reminders, spending alerts and the widget's figures.
@MainActor
enum Notifier {
    private static var center: UNUserNotificationCenter { UNUserNotificationCenter.current() }

    /// Asks for permission to show notifications. Returns whether they're allowed.
    static func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Recalculates reminders and the widget after data changed.
    static func refresh(engine: Engine) {
        let txns = engine.fetchAll(Txn.self).map(TxnView.init)
        let cards = engine.fetchAll(Card.self)
        let dues = CardDues.compute(cards: cards, statements: engine.fetchAll(StatementRecord.self), txns: txns)
        scheduleReminders(engine: engine, cards: cards, dues: dues, txns: txns)
        WidgetData.write(engine: engine, cards: cards, dues: dues, txns: txns)
    }

    // MARK: - Due reminders

    /// Reminders 3 days before, the day before and on the due day at 9:00, while a card's minimum isn't paid or a fixed
    /// payment isn't paid this month. Replaced each time data changes, so a payment cancels its reminders.
    static func scheduleReminders(engine: Engine, cards: [Card], dues: [CardDue], txns: [TxnView]) {
        let planned = Settings.remindersEnabled ? plannedReminders(engine: engine, cards: cards, dues: dues, txns: txns) : []
        Task {
            let pending = await center.pendingNotificationRequests()
            center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { $0.hasPrefix("due-") })
            for r in planned {
                let content = UNMutableNotificationContent()
                content.title = r.title
                content.body = r.body
                content.sound = .default
                var comps = Calendar.current.dateComponents([.year, .month, .day], from: Dates.date(epochDay: r.day))
                comps.hour = 9
                let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
                try? await center.add(UNNotificationRequest(identifier: r.id, content: content, trigger: trigger))
            }
        }
    }

    struct Planned {
        let id: String
        let day: Int64
        let title: String
        let body: String
    }

    /// The reminders to schedule from today on (also used by the tests).
    static func plannedReminders(engine: Engine, cards: [Card], dues: [CardDue], txns: [TxnView], today: Int64 = Dates.today(), now: Date = Date()) -> [Planned] {
        var out: [Planned] = []
        let nineToday = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: now) ?? now
        func upcoming(_ day: Int64) -> Bool { day > today || (day == today && now < nineToday) }
        let byKey = SpendMath.cardMap(cards)
        for d in dues {
            guard let c = byKey[d.cardKey], c.remindersEnabled, c.counted, d.status.state == .unpaid else { continue }
            for offset in [3, 1, 0] {
                let day = d.statement.dueDay - Int64(offset)
                guard upcoming(day) else { continue }
                let when = offset == 0 ? "today" : offset == 1 ? "tomorrow" : "in \(offset) days"
                let min = d.statement.minimumDueMinor.map { " · minimum \(MoneyText.text($0))" } ?? ""
                out.append(Planned(
                    id: "due-st-\(d.cardKey)-\(d.statement.dueDay)-\(offset)", day: day, title: "Card payment due",
                    body: "\(d.label) payment due \(when): \(MoneyText.text(d.status.remainingMinor))\(min)"
                ))
            }
        }
        let monthTxns = txns.filter { $0.fromSms && Dates.ym($0.day) == Dates.ym(today) }
            .map { FixedSchedule.MonthTxn(cardKey: $0.cardKey, amountMinor: $0.amountMinor, categoryId: $0.categoryId, type: $0.type) }
        for f in engine.fetchAll(FixedPayment.self) where f.active && f.remind {
            let paid = FixedSchedule.paidThisMonth(lastPaidYm: f.lastPaidYm, today: today) ||
                FixedSchedule.autoPaid(amountMinor: f.amountMinor, cardKey: f.cardKey, categoryId: f.categoryId, monthTxns: monthTxns)
            // This month's payment (if not paid) and next month's.
            let p = Dates.parts(today)
            let thisDue = FixedSchedule.dueDate(day: f.dayOfMonth, year: p.year, month: p.month)
            let n = Dates.parts(Dates.plusMonths(Dates.epochDay(year: p.year, month: p.month, day: 1), 1))
            let nextDue = FixedSchedule.dueDate(day: f.dayOfMonth, year: n.year, month: n.month)
            for due in (paid ? [nextDue] : [thisDue, nextDue]) {
                for offset in [3, 1, 0] {
                    let day = due - Int64(offset)
                    guard upcoming(day) else { continue }
                    let when = offset == 0 ? "today" : offset == 1 ? "tomorrow" : "in \(offset) days"
                    out.append(Planned(
                        id: "due-fp-\(f.id.uuidString)-\(due)-\(offset)", day: day, title: "Fixed payment due",
                        body: "\(f.name) \(MoneyText.text(f.amountMinor)) is due \(when). Mark it paid in \(AppInfo.name) once done."
                    ))
                }
            }
        }
        // iOS keeps at most 64 pending notifications: the soonest ones win.
        return Array(out.sorted { $0.day < $1.day }.prefix(60))
    }

    // MARK: - Spending alerts

    struct Alert {
        let tag: String
        let title: String
        let body: String
    }

    /// Alerts for new transactions: a big spend, a low balance or available limit, and budgets at 80% and 100%.
    static func alerts(for newTxns: [Txn], engine: Engine) {
        let live = newTxns.filter { !$0.isDeleted && $0.modelContext != nil }
        guard Settings.alertsEnabled, !live.isEmpty else { return }
        var sent = Settings.sentAlerts
        let list = computeAlerts(newTxns: live.map(TxnView.init), engine: engine, sent: &sent)
        Settings.sentAlerts = sent
        for a in list {
            let content = UNMutableNotificationContent()
            content.title = a.title
            content.body = a.body
            content.sound = .default
            center.add(UNNotificationRequest(identifier: "alert-" + a.tag, content: content, trigger: nil))
        }
    }

    static func computeAlerts(newTxns: [TxnView], engine: Engine, sent: inout Set<String>, now: Date = Date()) -> [Alert] {
        var out: [Alert] = []
        let cards = SpendMath.cardMap(engine.fetchAll(Card.self))
        let fresh = newTxns.filter { $0.timestamp >= now.addingTimeInterval(-86_400) && $0.timestamp <= now.addingTimeInterval(3600) }
        for t in fresh {
            let card = t.cardKey.flatMap { cards[$0] }
            let counted = card?.counted ?? true
            let big = Settings.bigSpendMinor
            if big > 0, counted, t.type == .purchase, let aed = t.aed, aed >= big {
                out.append(Alert(tag: "big-\(t.id.uuidString)", title: "Big spend: \(MoneyText.text(aed))", body: "\(t.merchant) · \(card?.label ?? "typed")"))
            }
            if let c = card, let bal = t.availableMinor {
                let isAccount = c.kind != .credit
                let threshold = isAccount ? Settings.lowAccountMinor : Settings.lowCardMinor
                let tag = "low:\(c.key):\(t.day)"
                if threshold > 0, bal < threshold, !sent.contains(tag) {
                    sent.insert(tag)
                    out.append(Alert(
                        tag: tag, title: isAccount ? "Balance low on \(c.label)" : "Available limit low on \(c.label)",
                        body: "\(isAccount ? "Balance" : "Available limit") is \(MoneyText.text(bal)) after \(t.merchant)"
                    ))
                }
            }
        }
        if Settings.budgetAlerts, !fresh.isEmpty {
            let today = Dates.today()
            let month = Period.thisMonth(today: today)
            let excluded = Set(cards.values.filter { !$0.counted }.map(\.key))
            let monthTxns = engine.fetchAll(Txn.self).map(TxnView.init).filter { month.contains($0.day) }
            var spent: [Int64?: Int64] = [:]
            for s in Insights.byCategory(monthTxns, excluded: excluded) { spent[s.categoryId] = s.amount }
            let limits = Dictionary(engine.fetchAll(Budget.self).map { ($0.categoryId, $0.limitMinor) }, uniquingKeysWith: { a, _ in a })
            for b in Budgets.status(limits: limits, spent: spent) {
                guard let level = Budgets.threshold(b.percent) else { continue }
                let tag = "budget:\(b.categoryId):\(Dates.ym(today)):\(level)"
                if sent.contains(tag) { continue }
                sent.insert(tag)
                if level == 100 { sent.insert("budget:\(b.categoryId):\(Dates.ym(today)):80") }
                let name = Categories.name(b.categoryId)
                out.append(Alert(
                    tag: tag, title: level == 100 ? "\(name) budget used up" : "\(name) budget at \(b.percent)%",
                    body: "\(MoneyText.text(b.spentMinor)) of \(MoneyText.text(b.limitMinor)) this month"
                ))
            }
        }
        return out
    }
}

/// The figures the home-screen widget shows, shared through the app group.
enum WidgetData {
    struct Snapshot: Codable {
        var month: String
        var spentMinor: Int64
        var dueLine: String
        var updated: Date
    }

    static let key = "widgetSnapshot"

    static var shared: UserDefaults? { UserDefaults(suiteName: AppInfo.appGroup) }

    @MainActor
    static func write(engine: Engine, cards: [Card], dues: [CardDue], txns: [TxnView]) {
        let month = Period.thisMonth()
        let excluded = Set(cards.filter { !$0.counted }.map(\.key))
        let spent = SpendRules.total(txns.filter { month.contains($0.day) }, excluded: excluded)
        let counted = Set(cards.filter(\.counted).map(\.key))
        let next = dues.filter { counted.contains($0.cardKey) && ($0.status.state == .unpaid || $0.status.state == .minPaid) && $0.status.daysLeft >= 0 }
            .min { $0.status.daysLeft < $1.status.daysLeft }
        let line = next.map { "\($0.label): \(MoneyText.text($0.status.remainingMinor)) due \(Dates.dayText(epochDay: $0.statement.dueDay))" } ?? "No card payments due"
        let snap = Snapshot(month: Dates.monthShort.string(from: Date()), spentMinor: spent, dueLine: line, updated: Date())
        if let data = try? JSONEncoder().encode(snap) {
            shared?.set(data, forKey: key)
            UserDefaults.standard.set(data, forKey: key)
        }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
