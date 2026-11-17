package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The safe re-read against a real Room database on the emulator.
 * A "wrong new reading" is simulated by changing the stored message text the reader sees (the reader is deterministic,
 * so a different text is the same as a different reader), then re-reading.
 */
@RunWith(AndroidJUnit4::class)
class ReReadGuardDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
    }

    @After fun tearDown() { db.close() }

    private fun at(d: Int, m: Int, y: Int, h: Int = 12): Long = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val purchase = "An amount of AED 3865.95 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08"
    private val purchaseOther = "An amount of AED 38.65 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08"
    private val otherCard = "An amount of AED 3865.95 has been debited from your FAB account XXXX7777 on 24/01/25 .Your Available Balance is AED 28186.08"
    private val laterDay = "An amount of AED 3865.95 has been debited from your FAB account XXXX8001 on 25/01/25 .Your Available Balance is AED 28186.08"
    private val notFinancial = "Your verification code is 482913. Do not share it with anyone."

    private fun ingest(body: String, t: Long = at(24, 1, 2025)) = runBlocking { repo.ingestSms("FAB", body, t, t, "SMS") }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedBy { it.id }
    private fun held() = runBlocking { db.dao().heldReadingsNow() }
    private fun setBody(id: Long, body: String) = db.openHelper.writableDatabase.execSQL("UPDATE sms SET body = ? WHERE id = ?", arrayOf(body, id))
    private fun smsId() = runBlocking { db.dao().allSms().single().id }
    private fun reread(force: Boolean = false) = runBlocking { repo.reparseAll(force) }

    private fun cardTypeOf(t: TransactionEntity) =
        CardType.valueOf(runBlocking { db.dao().allCards() }.first { it.cardKey == t.cardKey }.cardType)

    private fun baseline(): TransactionEntity {
        assertEquals(IngestOutcome.TRANSACTION, ingest(purchase))
        return txns().single()
    }

    // ---- safe re-read and rollback, one test per critical field
    @Test fun a_changed_amount_is_held_and_the_old_reading_stays() {
        val before = baseline()
        setBody(smsId(), purchaseOther)
        reread()
        assertEquals(before, txns().single())
        assertEquals(1, held().size)
        assertTrue(held().single().newSummary, held().single().newSummary.contains("amount"))
    }
    @Test fun a_changed_card_is_held() {
        val before = baseline(); setBody(smsId(), otherCard); reread()
        assertEquals(before, txns().single()); assertEquals(1, held().size)
        assertTrue(held().single().newSummary.contains("card"))
    }
    @Test fun a_changed_date_is_held() {
        val before = baseline(); setBody(smsId(), laterDay); reread()
        assertEquals(before, txns().single()); assertEquals(1, held().size)
        assertTrue(held().single().newSummary.contains("date"))
    }
    @Test fun a_message_the_new_reader_cannot_read_keeps_its_transaction() {
        val before = baseline(); setBody(smsId(), "Hello, balance enquiry received."); reread()
        assertEquals(before, txns().single()); assertEquals(1, held().size)
    }
    @Test fun an_unchanged_message_is_not_held_and_reread_twice_is_identical() {
        baseline()
        val a = reread(); val first = txns().map { it.copy(id = 0) }
        val b = reread(); val second = txns().map { it.copy(id = 0) }
        assertEquals(first, second); assertTrue(held().isEmpty())
        assertEquals(a, b)
        assertEquals("no duplicate rows", 1, second.size)
    }
    @Test fun a_wrong_new_reading_never_reaches_the_ledger_without_a_decision() {
        val before = baseline()
        setBody(smsId(), "Refund of AED 3865.95 credited to your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08")
        reread()
        assertEquals("still the original debit", before, txns().single())
    }

    // ---- Keep mine / Use new
    @Test fun use_new_applies_the_held_reading() {
        baseline(); val id = smsId(); setBody(id, purchaseOther); reread()
        assertTrue(runBlocking { repo.useNewReading(id) })
        assertEquals(3865L, txns().single().amountMinor)
        assertTrue(held().isEmpty())
    }
    @Test fun keep_mine_saves_a_fix_that_later_rereads_respect() {
        val before = baseline(); val id = smsId(); setBody(id, purchaseOther); reread()
        assertTrue(runBlocking { repo.keepCurrentReading(id) })
        assertTrue(held().isEmpty())
        reread(force = true)
        assertEquals("even a forced re-read keeps your reading", before.amountMinor, txns().single().amountMinor)
        assertEquals("ALL", runBlocking { db.dao().fixFor(db.dao().allSms().single().dedupKey) }!!.lockedFields)
    }
    @Test fun a_forced_reread_applies_everything() {
        baseline(); setBody(smsId(), purchaseOther); reread(force = true)
        assertEquals(3865L, txns().single().amountMinor); assertTrue(held().isEmpty())
    }

    // ---- manual fix protection, field by field
    @Test fun a_fix_protects_only_the_fields_you_changed() {
        val before = baseline(); val id = smsId()
        // you correct only the merchant
        runBlocking {
            repo.saveFix(id, TxnType.PURCHASE, before.amountMinor, before.currency, "My Landlord", before.cardLast4, cardTypeOf(before), before.timestamp)
        }
        val fix = runBlocking { db.dao().fixFor(db.dao().allSms().single().dedupKey) }!!
        assertEquals("MERCHANT", fix.lockedFields)
        // the reader later says a different amount: the amount follows the reader (and is held), the merchant stays yours
        setBody(id, purchaseOther); reread(force = true)
        val t = txns().single()
        assertEquals("My Landlord", t.merchant)
        assertEquals(3865L, t.amountMinor)
    }
    @Test fun a_fix_that_changes_the_amount_keeps_the_amount_against_a_new_reader() {
        val before = baseline(); val id = smsId()
        runBlocking { repo.saveFix(id, TxnType.PURCHASE, 500_00, before.currency, before.merchant, before.cardLast4, cardTypeOf(before), before.timestamp) }
        assertEquals("AMOUNT", runBlocking { db.dao().fixFor(db.dao().allSms().single().dedupKey) }!!.lockedFields)
        setBody(id, purchaseOther); reread(force = true)
        assertEquals(500_00L, txns().single().amountMinor)
    }
    @Test fun an_old_fix_without_field_list_protects_everything() {
        val before = baseline(); val id = smsId(); val key = db.dao().let { runBlocking { it.allSms().single().dedupKey } }
        runBlocking {
            db.dao().upsertFixes(listOf(SmsFixEntity(key, "PURCHASE", 777_00, "AED", "Old fix", before.cardLast4, "ACCOUNT", before.timestamp)))
        }
        setBody(id, purchaseOther); reread(force = true)
        val t = txns().single()
        assertEquals(777_00L, t.amountMinor); assertEquals("Old fix", t.merchant)
    }
    @Test fun a_category_you_chose_survives_a_forced_reread() {
        val before = baseline()
        runBlocking { repo.setCategory(before, 3L, applyToMerchant = false) }
        reread(force = true)
        val t = txns().single(); assertEquals(3L, t.categoryId); assertTrue(t.categoryUserSet)
    }

    // ---- one-time-code rule
    @Test fun a_message_later_recognised_as_a_code_never_deletes_its_transaction() {
        val before = baseline(); val id = smsId()
        setBody(id, notFinancial); reread()
        assertEquals("transaction survives", before, txns().single())
        assertNotNull("the message itself is kept", runBlocking { db.dao().smsById(id) })
    }
    // ---- interrupted processing
    @Test fun a_crash_in_the_middle_of_a_reread_changes_nothing() {
        val before = baseline(); val id = smsId()
        setBody(id, purchaseOther)
        try {
            runBlocking { db.withTransaction { repo.reparseAll(true); throw IllegalStateException("phone died") } }
        } catch (_: IllegalStateException) {}
        // whole re-read was inside one transaction that rolled back
        assertEquals(before, txns().single())
    }
    @Test fun restarting_after_a_held_change_keeps_the_hold() {
        baseline(); setBody(smsId(), purchaseOther); reread()
        val h = held()
        // "app restart": a new Repository on the same database
        val again = Repository(db)
        runBlocking { again.reparseAll() }
        assertEquals(1, held().size)
        assertEquals(h.single().keptSummary, held().single().keptSummary)
    }

    // ---- integrity
    @Test fun the_database_passes_its_own_integrity_check_after_all_this() {
        baseline(); setBody(smsId(), purchaseOther); reread(); reread(force = true)
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { c -> c.moveToFirst(); assertEquals("ok", c.getString(0)) }
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { c -> assertFalse(c.moveToFirst()) }
    }
}
