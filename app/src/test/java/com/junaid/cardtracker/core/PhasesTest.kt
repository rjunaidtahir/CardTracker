package com.junaid.cardtracker.core

import com.junaid.cardtracker.parser.CategoryRules
import com.junaid.cardtracker.parser.TxnType
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhasesTest {
    private val today = LocalDate.of(2026, 9, 23)

    // ------------------------------------------------------------ categories

    @Test fun keyword_categories() {
        fun cat(m: String, t: TxnType = TxnType.PURCHASE) = CategoryRules.guess(m, t)
        assertEquals(CategoryRules.DINING, cat("talabat.com"))
        assertEquals(CategoryRules.GROCERIES, cat("MORE VALUE SUPERMARKE"))
        assertEquals(CategoryRules.GROCERIES, cat("W Z D WEST ZONE SUPERM"))
        assertEquals(CategoryRules.GROCERIES, cat("Amazon Now"))
        assertEquals(CategoryRules.SHOPPING, cat("Amazon.ae"))
        assertEquals(CategoryRules.TRANSPORT, cat("ADNOC WALLET"))
        assertEquals(CategoryRules.TRANSPORT, cat("ADNOC ROVE HOTEL 532 DUBAI"))
        assertEquals(CategoryRules.UTILITIES, cat("BNKNT UTLTY PYMNT-DEWA"))
        assertEquals(CategoryRules.UTILITIES, cat("HomeInternet"))
        assertEquals(CategoryRules.UTILITIES, cat("AL MANDOOS GAS CYLINDE"))
        assertEquals(CategoryRules.GOVERNMENT, cat("SMART DUBAI GOVERNMENT"))
        assertEquals(CategoryRules.GOVERNMENT, cat("SmartDXBGov-RTA"))
        assertEquals(CategoryRules.GOVERNMENT, cat("EMIRATES AUCTION LLC"))
        assertEquals(CategoryRules.ENTERTAINMENT, cat("THE BIG TICKET LLC"))
        assertEquals(CategoryRules.TRAVEL, cat("AGODA.COM AL HAMRA R"))
        assertEquals(CategoryRules.INSURANCE, cat("LIVA INS B S C CLOSED"))
        assertEquals(CategoryRules.HEALTH, cat("EMIRATES SPECIALITY HO"))
        assertEquals(CategoryRules.EDUCATION, cat("Oxford Brookes University"))
        assertEquals(CategoryRules.EMI_LOANS, cat("Account debit (EMI / direct debit)"))
        assertEquals(CategoryRules.CASH, cat("ATM cash withdrawal"))
        assertEquals(CategoryRules.DINING, cat("PICCADILLY WHIPPY CAFE DUBAI"))
        assertEquals(CategoryRules.OTHER, cat("SOMETHING UNKNOWN"))
        assertEquals(CategoryRules.OTHER, cat("Cashback", TxnType.REFUND))
        assertNull(cat("Payment to ENBD credit card ·9940", TxnType.TRANSFER_OUT))
        assertNull(cat("Salary", TxnType.TRANSFER_IN))
    }

    @Test fun merchant_key_for_learning() {
        assertEquals("AGODA COM AL", CategoryRules.merchantKey("AGODA.COM AL HAMRA R"))
        assertEquals("ADNOC ROVE HOTEL", CategoryRules.merchantKey("ADNOC ROVE HOTEL 532 DUBAI"))
        assertEquals("TALABAT COM", CategoryRules.merchantKey("talabat.com"))
    }

    // ------------------------------------------------------ statement status

    private fun status(balance: Long, min: Long?, due: LocalDate, pays: List<CardPayment>) =
        StatementStatusCalc.of(balance, min, due, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), pays, today)

    @Test fun statement_states() {
        val due = LocalDate.of(2026, 10, 4)
        assertEquals(DueState.UNPAID, status(607061, 129303, due, emptyList()).state)
        assertEquals(11, status(607061, 129303, due, emptyList()).daysLeft)
        val minPaid = status(607061, 129303, due, listOf(CardPayment(LocalDate.of(2026, 9, 15), 200000, true)))
        assertEquals(DueState.MIN_PAID, minPaid.state)
        assertEquals(407061, minPaid.remainingMinor)
        val paid = status(607061, 129303, due, listOf(CardPayment(LocalDate.of(2026, 10, 1), 607100, false)))
        assertEquals(DueState.PAID, paid.state)
        assertEquals(0, paid.remainingMinor)
        // Payment before the statement date doesn't count toward it.
        val early = status(607061, 129303, due, listOf(CardPayment(LocalDate.of(2026, 9, 1), 607061, true)))
        assertEquals(DueState.UNPAID, early.state)
        assertEquals(DueState.OVERDUE, status(10000, 5000, LocalDate.of(2026, 9, 20), emptyList()).state)
        assertEquals(DueState.NOTHING_DUE, status(0, 0, due, emptyList()).state)
    }

    @Test fun card_payments_not_double_counted() {
        val bank = listOf(CardPayment(LocalDate.of(2026, 9, 4), 90000, true))
        val transfers = listOf(
            CardPayment(LocalDate.of(2026, 9, 3), 90000, false), // same payment seen from the account side
            CardPayment(LocalDate.of(2026, 9, 17), 100000, false),
        )
        val all = CardPayments.combine(bank, transfers)
        assertEquals(2, all.size)
        assertEquals(190000L, all.sumOf { it.amountMinor })
    }

    @Test fun reminders_fire_3_1_0_days_before_while_unpaid() {
        fun st(days: Long, state: DueState = DueState.UNPAID) = StatementStatus(state, 0, 1000, days)
        assertEquals(3L, Reminders.offsetToday(st(3)))
        assertEquals(1L, Reminders.offsetToday(st(1)))
        assertEquals(0L, Reminders.offsetToday(st(0)))
        assertNull(Reminders.offsetToday(st(2)))
        assertNull(Reminders.offsetToday(st(1, DueState.MIN_PAID)))
        assertNull(Reminders.offsetToday(st(1, DueState.PAID)))
        assertEquals("ENBD ·9940 payment due tomorrow: AED 10 · minimum AED 5",
            Reminders.message("ENBD ·9940", st(1), 500) { "AED ${it / 100}" })
    }

    @Test fun utilisation() {
        val u = Utilisation(limitMinor = 5_000_000, availableMinor = 3_750_000)
        assertEquals(1_250_000, u.usedMinor)
        assertEquals(25, u.percent)
        assertEquals(0, Utilisation(0, 0).percent)
    }

    // ------------------------------------------------------------- insights

    private fun tx(date: String, amount: Long, cat: Long?, card: String? = "FAB ·0831", type: TxnType = TxnType.PURCHASE,
                   merchant: String = "SHOP", cur: String = "AED", orig: Long = amount) =
        InsightTxn(LocalDate.parse(date), type, amount, card, cat, merchant, CategoryRules.merchantKey(merchant), cur, orig)

    @Test fun spending_by_category_card_month_currency() {
        val txns = listOf(
            tx("2026-09-01", 10000, CategoryRules.GROCERIES),
            tx("2026-09-02", 5000, CategoryRules.GROCERIES),
            tx("2026-09-03", 2000, CategoryRules.GROCERIES, type = TxnType.REFUND),
            tx("2026-09-04", 30000, CategoryRules.TRAVEL, cur = "USD", orig = 8169),
            tx("2026-09-05", 99999, null, type = TxnType.TRANSFER_OUT),
            tx("2026-09-06", 7000, CategoryRules.DINING, card = "ENBD ·1111"), // excluded card
            tx("2026-08-10", 4000, CategoryRules.DINING),
        )
        val excl = setOf("ENBD ·1111")
        val sep = txns.filter { it.date.monthValue == 9 }
        assertEquals(listOf(CategoryRules.TRAVEL to 30000L, CategoryRules.GROCERIES to 13000L), Insights.byCategory(sep, excl))
        assertEquals(listOf(Slice("FAB ·0831", 43000)), Insights.byCard(sep, excl))
        val months = Insights.byMonth(txns, excl, YearMonth.of(2026, 9), months = 3)
        assertEquals(listOf(0L, 4000L, 43000L), months.map { it.amountMinor })
        assertEquals(YearMonth.of(2026, 7), months.first().month)
        assertEquals(listOf(CurrencyTotal("USD", 8169, 30000, 1)), Insights.byCurrency(txns, excl))
    }

    @Test fun recurring_detection() {
        val netflix = listOf("2026-05-12", "2026-06-12", "2026-07-12", "2026-08-12", "2026-09-12")
            .map { tx(it, 5600, CategoryRules.ENTERTAINMENT, merchant = "NETFLIX.COM") }
        val emi = listOf("2026-06-01", "2026-07-01", "2026-08-01", "2026-08-31")
            .map { tx(it, 481000, CategoryRules.EMI_LOANS, card = "FAB ·8001", merchant = "Account debit (EMI / direct debit)") }
        val random = listOf("2026-09-01", "2026-09-03", "2026-09-20").map { tx(it, 1500, CategoryRules.DINING, merchant = "TALABAT") }
        val r = Insights.recurring(netflix + emi + random, today)
        assertEquals(2, r.size, r.toString())
        assertEquals(481000L, r[0].averageMinor)
        assertEquals(LocalDate.of(2026, 9, 30), r[0].nextExpected)
        assertEquals("NETFLIX.COM", r[1].merchant)
        assertEquals(LocalDate.of(2026, 10, 12), r[1].nextExpected)
    }

    // ------------------------------------------------------------------ csv

    @Test fun csv_round_trip() {
        val text = Csv.write(listOf("a", "b"), listOf(listOf("plain", "has, comma"), listOf("quote \"x\"", null), listOf("multi\nline", "z")))
        val rows = Csv.read(text)
        assertEquals(3, rows.size)
        assertEquals("has, comma", rows[0]["b"])
        assertEquals("quote \"x\"", rows[1]["a"])
        assertNull(rows[1]["b"])
        assertEquals("multi\nline", rows[2]["a"])
    }

    // ------------------------------------------------------------- app lock

    @Test fun pin_and_lock_policy() {
        val salt = PinHasher.newSalt()
        val h = PinHasher.hash("4821", salt)
        assertTrue(PinHasher.verify("4821", salt, h))
        assertEquals(false, PinHasher.verify("4822", salt, h))
        assertTrue(PinHasher.isValidPin("123456"))
        assertEquals(false, PinHasher.isValidPin("12a4"))
        assertEquals(false, PinHasher.isValidPin("123"))
        assertEquals(true, LockPolicy.shouldLock(true, null, 1000, 60_000))
        assertEquals(false, LockPolicy.shouldLock(true, 1000, 30_000, 60_000))
        assertEquals(true, LockPolicy.shouldLock(true, 1000, 61_000, 60_000))
        assertEquals(false, LockPolicy.shouldLock(false, null, 1000, 0))
    }
}
