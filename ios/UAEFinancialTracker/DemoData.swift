import Foundation

/// Made-up sample data for screenshots (launch with `-demo YES`). Never used in normal runs.
enum DemoData {
    static var isOn: Bool { UserDefaults.standard.bool(forKey: "demo") }

    @MainActor
    static func load(into engine: Engine) {
        let now = Date()
        let cal = Calendar.current
        func ago(_ days: Int, _ hour: Int, _ minute: Int = 10) -> Date {
            let d = cal.date(byAdding: .day, value: -days, to: now)!
            return cal.date(bySettingHour: hour, minute: minute, second: 0, of: d)!
        }
        let dmy = DateFormatter()
        dmy.dateFormat = "dd/MM/yyyy HH:mm:ss"

        var limit = 18_450.00
        func enbd(_ amount: Double, _ merchant: String, _ days: Int, _ hour: Int) {
            limit -= amount
            engine.ingest(
                body: String(format: "Purchase of AED %.2f with Credit Card ending 3944 at %@, DUBAI. Avl Cr. Limit is AED %.2f", amount, merchant, limit),
                sender: "EmiratesNBD", receivedAt: ago(days, hour), timeKnown: true, source: "Demo"
            )
        }
        var adcbLimit = 7_900.00
        func adcb(_ amount: Double, _ merchant: String, _ days: Int, _ hour: Int) {
            adcbLimit -= amount
            let when = ago(days, hour)
            engine.ingest(
                body: String(format: "Credit Card XX3538 was used for AED%.2f on %@ at %@, DUBAI-AE. Available limit AED%.2f", amount, dmy.string(from: when), merchant, adcbLimit),
                sender: "ADCBAlert", receivedAt: when, timeKnown: true, source: "Demo"
            )
        }

        // Last month
        enbd(412.35, "CARREFOUR MOE", 38, 19)
        enbd(96.00, "ENOC 1043", 36, 8)
        adcb(58.50, "talabat.com", 35, 21)
        enbd(1_109.53, "BNKNT UTLTY PYMNT-DEWA", 33, 10)
        enbd(249.00, "NOON.COM", 31, 22)
        // This month
        let day = cal.component(.day, from: now)
        let d = max(day - 1, 1)
        enbd(318.11, "BNKNT UTLTY PYMNT-ETISALAT", d, 9)
        enbd(64.20, "SPINNEYS JUMEIRAH", max(d - 1, 0), 18)
        adcb(89.90, "talabat.com", max(d - 2, 0), 20)
        enbd(120.00, "ENOC 1043", max(d - 3, 0), 7)
        enbd(535.75, "CARREFOUR MOE", max(d - 5, 0), 17)
        adcb(42.00, "STARBUCKS DUBAI MALL", max(d - 6, 0), 11)
        enbd(1_850.00, "EMIRATES AIRLINE", max(d - 8, 0), 13)
        adcb(210.00, "VOX CINEMAS", max(d - 9, 0), 19)
        enbd(76.40, "AMAZONUFR DI", 2, 12)
        adcb(35.00, "CAREEM", 1, 8)
        enbd(18.50, "STARBUCKS BUSINESS BAY", 0, 9)
        engine.ingest(
            body: "Purchase of USD 15.49 with Credit Card ending 3944 at NETFLIX.COM, LOS GATOS. Avl Cr. Limit is AED 15,642.21",
            sender: "EmiratesNBD", receivedAt: ago(4, 3), timeKnown: true, source: "Demo"
        )
        engine.ingest(
            body: "Amount of AED 53.72 from Amazon.ae has been credited to your card ending 3944. Available limit is AED 15,700.00.",
            sender: "EmiratesNBD", receivedAt: ago(3, 15), timeKnown: true, source: "Demo"
        )

        // A statement due soon
        let stmt = cal.date(byAdding: .day, value: -12, to: now)!
        let due = cal.date(byAdding: .day, value: 6, to: now)!
        let dd = DateFormatter()
        dd.dateFormat = "dd/MM/yyyy"
        engine.ingest(
            body: "HSBC Credit Card ending *** 5258 Statement Date \(dd.string(from: stmt)). Total Amt Due AED 2,379.20, Due Date \(dd.string(from: due)). Min. Amt Due AED 118.96.",
            sender: "HSBC-UAE", receivedAt: stmt, timeKnown: true, source: "Demo"
        )
        // One the app can't read for sure
        engine.ingest(
            body: "Dear Customer, AED 250.00 has been processed on your account. Ref 99812.",
            sender: "EmiratesNBD", receivedAt: ago(2, 14), timeKnown: true, source: "Demo"
        )
        _ = engine.addTyped("lunch 45", date: ago(1, 13))
        // A monthly subscription (shows under Recurring payments)
        for back in [72, 42, 12] {
            limit -= 21.99
            engine.ingest(
                body: String(format: "Purchase of AED 21.99 with Credit Card ending 3944 at SPOTIFY P2F3C1, STOCKHOLM. Avl Cr. Limit is AED %.2f", limit),
                sender: "EmiratesNBD", receivedAt: ago(back, 6), timeKnown: true, source: "Demo"
            )
        }
        engine.setBudgets([1: 150_000, 2: 60_000, 5: 100_000])
        let rent = FixedPayment(name: "Rent", amountMinor: 650_000, dayOfMonth: 1)
        rent.categoryId = 4
        rent.lastPaidYm = Dates.ym(Dates.today())
        engine.addFixedPayment(rent)
        let school = FixedPayment(name: "School fees", amountMinor: 220_000, dayOfMonth: min(28, cal.component(.day, from: now) + 4))
        school.categoryId = 10
        engine.addFixedPayment(school)
        engine.addGoal(name: "Summer holiday", targetMinor: 1_000_000, savedMinor: 320_000, targetDay: Dates.plusMonths(Dates.today(), 8))

        for c in engine.fetchAll(Card.self) where c.last4 == "3944" {
            c.creditLimitMinor = 2_000_000
            c.nickname = "Emirates NBD Cashback"
        }
        engine.save()
    }
}
