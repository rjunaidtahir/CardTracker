package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.parser.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 2.8 spec 9 (`ext.readingPolicy.applyKeyChangeFor`) on a real Room database. A re-read that moves the same money to another
 * card or account key is applied for a listed bank: money unchanged, history kept under the new key, your card settings kept.
 * A bank not in the list is held exactly as before. The "new reading" is simulated by changing the stored text, as in
 * ReReadGuardDbTest (the reader is deterministic).
 */
@RunWith(AndroidJUnit4::class)
class KeyChangeDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        repo.onEvent = { kind, _ -> events += kind }
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
    }

    @After fun tearDown() { Pack28.reset(); db.close() }

    private fun at(d: Int, h: Int = 12): Long = LocalDateTime.of(2025, 1, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun debit(acct: String, amount: String, day: Int) =
        "An amount of AED $amount has been debited from your FAB account XXXX$acct on $day/01/25 .Your Available Balance is AED 28186.08"

    private fun ingest(body: String, t: Long) = runBlocking { repo.ingestSms("FAB", body, t, t, "SMS") }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedBy { it.id }
    /** A re-read stores the unchanged rows again under new ids (as in ReReadGuardDbTest), so compare without the id. */
    private fun rows() = txns().map { it.copy(id = 0) }
    private fun cards() = runBlocking { db.dao().allCards() }
    private fun held() = runBlocking { db.dao().heldReadingsNow() }
    private fun setBody(id: Long, body: String) = db.openHelper.writableDatabase.execSQL("UPDATE sms SET body = ? WHERE id = ?", arrayOf(body, id))
    private fun reread() = runBlocking { repo.reparseAll() }
    private fun money() = txns().map { listOf(it.amountMinor, it.currency, it.type, it.bank) }.sortedBy { it.toString() }

    /** Two debits on ·8001; you named the card and set a limit; then the reader reads the first one as ·7777. */
    private fun story(): Long {
        assertEquals(IngestOutcome.TRANSACTION, ingest(debit("8001", "3865.95", 24), at(24)))
        assertEquals(IngestOutcome.TRANSACTION, ingest(debit("8001", "120.00", 25), at(25)))
        runBlocking { db.dao().updateCardProfile("FAB ·8001", "Salary account", 500_000L, 1, 20, true) }
        val first = runBlocking { db.dao().allSms() }.minByOrNull { it.id }!!.id
        setBody(first, debit("7777", "3865.95", 24))
        return first
    }

    @Test fun a_listed_bank_applies_the_key_change_and_money_is_unchanged() {
        Pack28.apply(listOf("keyChange"), more = "\"readingPolicy\":{\"applyKeyChangeFor\":[\"FAB\"]}")
        story()
        val moneyBefore = money()
        reread()
        assertTrue("nothing held", held().isEmpty())
        assertEquals(moneyBefore, money())
        assertEquals(setOf("FAB ·7777"), txns().map { it.cardKey }.toSet())
        assertEquals(listOf("event:key-change|bank=FAB|n=1"), events)
    }

    @Test fun history_stays_attached_and_your_card_settings_are_kept() {
        Pack28.apply(listOf("keyChange"), more = "\"readingPolicy\":{\"applyKeyChangeFor\":[\"FAB\"]}")
        story()
        reread()
        assertEquals("both debits are on the new key", 2, txns().count { it.cardKey == "FAB ·7777" })
        val c = cards().single { it.cardKey == "FAB ·7777" }
        assertEquals("Salary account", c.nickname); assertEquals(500_000L, c.creditLimitMinor)
        assertTrue("no duplicate card", cards().none { it.cardKey == "FAB ·8001" })
        // A later message for the old digits lands on the new card, and a second re-read changes nothing.
        ingest(debit("8001", "10.00", 26), at(26))
        assertEquals(3, txns().count { it.cardKey == "FAB ·7777" })
        reread()
        assertTrue(held().isEmpty()); assertEquals(3, txns().size)
    }

    @Test fun a_bank_not_in_the_list_is_held_exactly_as_before() {
        Pack28.apply(listOf("keyChange"), more = "\"readingPolicy\":{\"applyKeyChangeFor\":[\"ADCB\"]}")
        story()
        val before = rows()
        reread()
        assertEquals(before, rows())
        assertEquals(1, held().size)
        assertTrue(events.isEmpty())
    }

    @Test fun lever_off_holds_as_in_2_7() {
        Pack28.apply(emptyList(), more = "\"readingPolicy\":{\"applyKeyChangeFor\":[\"FAB\"]}")
        story()
        val before = rows()
        reread()
        assertEquals(before, rows()); assertEquals(1, held().size)
    }
}
