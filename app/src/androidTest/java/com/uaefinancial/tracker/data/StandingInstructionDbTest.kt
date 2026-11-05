package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.core.Checks
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.parser.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The FAB standing-instruction cases against a real Room database on the emulator: park, place from the balance gap,
 * replace by the late debit message, re-read twice, the pair in both orders, and the one-tap count in Needs review.
 * (The pure logic has its own tests in shared/; this is the storage side.)
 */
@RunWith(AndroidJUnit4::class)
class StandingInstructionDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)   // a UAE phone reads 01/11/2026 as 1 November (the emulator is set to a US region)
    }

    @After fun tearDown() { db.close() }

    private fun at(d: Int, m: Int, y: Int, h: Int = 12): Long = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun debit(amount: String, date: String, balance: String, acct: String = "8001") =
        "Outward Remittance\nDebit\nAccount XXXX$acct\nAED $amount\nDate $date\nBalance AED $balance"

    private fun standing(amount: String, payee: String, date: String) =
        "Dear Customer, your Domestic Fund transfer standing instructions of AED $amount to $payee has been processed on $date."

    private fun ingest(body: String, t: Long) = runBlocking { repo.ingestSms("FAB", body, t, t, "SMS") }

    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedWith(compareBy({ it.timestamp }, { it.id }))
    private fun waiting() = runBlocking { db.dao().failedSmsList() }

    private fun dump(): String = runBlocking {
        val cards = db.dao().allCards().joinToString { "${it.cardKey}/${it.cardType}" }
        val t = db.dao().txnsListBetween(0L, Long.MAX_VALUE).joinToString(" ; ") {
            "txn${it.id} ${it.cardKey} ${it.type} ${it.amountMinor} ts=${it.timestamp} bal=${it.availableLimitMinor} inf=${it.inferred} bank=${it.bank} cur=${it.currency}"
        }
        val sms = db.dao().smsForReparse().joinToString(" ; ") { "sms${it.id} ${it.status} rule=${it.ruleId} bank=${it.bank} note=${it.note?.take(50)}" }
        "cards: $cards || $t || $sms ||"
    }

    private fun snapshot() = txns().map { listOf(it.amountMinor, it.type, it.cardKey, it.inferred, it.merchant, it.availableLimitMinor) }

    /** The 31 Oct 3,160 debit, the 1 Nov standing instruction with no debit message, then the 1 Nov 100 debit. */
    private fun the1805Story() {
        ingest(debit("3160.00", "31/10/2026", "6383.71"), at(31, 10, 2026))
        ingest(standing("1805", "ENBD ANNUM XXXX7701", "01/11/2026"), at(1, 11, 2026, 10))
    }

    @Test fun a_standing_instruction_with_no_debit_waits_until_balances_show_where_it_goes() {
        the1805Story()
        assertEquals("only the debit is counted so far", 1, txns().size)
        assertEquals("the standing instruction waits in Needs review", 1, waiting().size)
        assertTrue(waiting().single().note!!.startsWith(Repository.SI_PENDING_NOTE_PREFIX))
    }

    @Test fun the_1805_is_placed_on_8001_between_the_3160_and_the_100_with_0_98_flagged() {
        the1805Story()
        ingest(debit("100.00", "01/11/2026", "4477.73"), at(1, 11, 2026, 12))
        val t = txns()
        assertEquals(dump(), 3, t.size)
        val placed = t.single { it.inferred }
        assertEquals(180_500L, placed.amountMinor)
        assertEquals("FAB ·8001", placed.cardKey)
        assertEquals("TRANSFER_OUT", placed.type)
        assertTrue("nothing left in Needs review", waiting().isEmpty())
        val rows = t.map { Checks.Row(it.id, it.cardKey, it.timestamp, it.type, it.amountMinor, it.currency, it.merchantKey, it.availableLimitMinor, inferred = it.inferred) }
        val flags = Checks.evaluate(rows)
        val hundred = t.single { it.amountMinor == 10_000L }
        assertFalse("the 100's balance check passes", flags[hundred.id].orEmpty().any { it.kind == Checks.Kind.BALANCE })
        val residual = flags[placed.id].orEmpty().single { it.kind == Checks.Kind.RESIDUAL }
        assertTrue(residual.message, residual.message.contains("0.98"))
    }

    @Test fun the_late_debit_message_replaces_the_placed_row_and_nothing_is_counted_twice() {
        the1805Story()
        ingest(debit("100.00", "01/11/2026", "4477.73"), at(1, 11, 2026, 12))
        val late = ingest(debit("1805.00", "01/11/2026", "4578.71"), at(1, 11, 2026, 14))
        assertEquals(IngestOutcome.MERGED, late)
        val t = txns()
        assertEquals(3, t.size)
        assertTrue("no row is marked as placed any more", t.none { it.inferred })
        assertEquals(1, t.count { it.amountMinor == 180_500L })
        assertTrue(waiting().isEmpty())
    }

    @Test fun reading_everything_again_twice_gives_identical_results() {
        the1805Story()
        ingest(debit("100.00", "01/11/2026", "4477.73"), at(1, 11, 2026, 12))
        val before = snapshot()
        runBlocking { repo.reparseAll() }
        val once = snapshot()
        runBlocking { repo.reparseAll() }
        val twice = snapshot()
        assertEquals(before, once)
        assertEquals(once, twice)
        assertEquals(1, txns().count { it.inferred })
        // and once the late debit is in, a re-read still ends with one row for it
        ingest(debit("1805.00", "01/11/2026", "4578.71"), at(1, 11, 2026, 14))
        val withLate = snapshot()
        runBlocking { repo.reparseAll() }
        assertEquals(withLate, snapshot())
        assertEquals(1, txns().count { it.amountMinor == 180_500L })
    }

    @Test fun a_standing_instruction_that_has_its_debit_adds_no_extra_transaction_in_either_order() {
        // debit first, then the standing instruction
        ingest(debit("1992.92", "01/11/2026", "11662.18"), at(1, 11, 2026, 8))
        ingest(standing("1992.92", "Junaid Mashreq CC XXXX4680", "01/11/2026"), at(1, 11, 2026, 8))
        assertEquals(1, txns().size)
        assertTrue(waiting().isEmpty())
        val first = txns().single()
        assertTrue(first.merchant, first.merchant.contains("Mashreq", ignoreCase = true) || first.merchant.contains("4680"))
        // standing instruction first, then the debit (a different account's history, fresh database state)
        db.clearAllTables()
        ingest(standing("1992.92", "Junaid Mashreq CC XXXX4680", "01/11/2026"), at(1, 11, 2026, 8))
        ingest(debit("1992.92", "01/11/2026", "11662.18"), at(1, 11, 2026, 9))
        assertEquals(1, txns().size)
        assertTrue(waiting().isEmpty())
    }

    @Test fun a_gap_that_two_accounts_could_hold_is_not_guessed() {
        // two FAB accounts, each with a hole the 1,805 could fill
        ingest(debit("3160.00", "31/10/2026", "6383.71", "8001"), at(31, 10, 2026))
        ingest(debit("350.00", "31/10/2026", "5000.00", "2407"), at(31, 10, 2026, 13))
        ingest(standing("1805", "ENBD ANNUM XXXX7701", "01/11/2026"), at(1, 11, 2026, 10))
        ingest(debit("100.00", "01/11/2026", "4477.73", "8001"), at(1, 11, 2026, 12))
        ingest(debit("50.00", "01/11/2026", "3144.50", "2407"), at(1, 11, 2026, 13))
        assertTrue("nothing was placed", txns().none { it.inferred })
        assertEquals("it stays in Needs review", 1, waiting().size)
        assertTrue(waiting().single().note!!.contains("more than one account"))
    }

    @Test fun count_all_waiting_puts_every_one_on_the_chosen_account_in_one_tap() {
        ingest(debit("3160.00", "31/10/2026", "6383.71"), at(31, 10, 2026))
        ingest(standing("1805", "ENBD ANNUM XXXX7701", "01/05/2025"), at(1, 5, 2025, 9))
        ingest(standing("6000", "ENBD Junaid CC XXXX9940", "01/06/2025"), at(2, 6, 2025, 23))
        assertEquals(2, waiting().size)
        val n = runBlocking { repo.countWaitingStandingOn("FAB ·8001") }
        assertEquals(2, n)
        assertTrue(waiting().isEmpty())
        val t = txns()
        assertEquals(3, t.size)
        assertEquals(2, t.count { it.type == "TRANSFER_OUT" && it.cardKey == "FAB ·8001" && it.amountMinor in setOf(180_500L, 600_000L) })
        // a re-read keeps them (they are your own decisions now)
        runBlocking { repo.reparseAll() }
        assertEquals(3, txns().size)
        assertTrue(waiting().isEmpty())
    }
}
