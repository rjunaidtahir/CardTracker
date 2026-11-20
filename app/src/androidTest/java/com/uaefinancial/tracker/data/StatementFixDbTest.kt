package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.core.CalendarDate
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

/** "Statement / payment due" in the Fix form: saved as a statement, survives re-reads, and the learning records are sent. */
@RunWith(AndroidJUnit4::class)
class StatementFixDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val kinds = mutableListOf<String>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        repo.onShape = { _, _, _, kind, _ -> kinds += kind }
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
    }

    @After fun tearDown() { db.close() }

    private fun at(d: Int, m: Int, y: Int): Long = LocalDateTime.of(y, m, d, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Wording no reader knows: nothing says "due", no amounts with roles.
    private val odd = "Hello, your card bill for this month is AED 511.06 and the smallest part you can pay is AED 127.78, pay before the end of June 2026."

    @Test fun a_statement_fix_is_saved_kept_and_reported() {
        val t = at(10, 6, 2026)
        runBlocking { repo.ingestSms("FAB", odd, t, t, "SMS") }
        val id = runBlocking { db.dao().allSms().single().id }
        val due = CalendarDate.of(2026, 6, 30)
        val r = runBlocking { repo.saveStatementFix(id, 51106, 12778, "AED", due.toEpochDay(), null, "1234") }
        assertEquals(com.uaefinancial.tracker.core.IngestOutcome.STATEMENT, r.outcome)
        val st = runBlocking { db.dao().allStatements() }.single()
        assertEquals(51106L, st.balanceMinor); assertEquals(12778L, st.minimumDueMinor); assertEquals(due.toEpochDay(), st.dueDateEpochDay)
        // a forced re-read keeps your statement
        runBlocking { repo.reparseAll(true) }
        assertEquals(51106L, runBlocking { db.dao().allStatements() }.single().balanceMinor)
        assertTrue(kinds.any { it.startsWith("fixed:STATEMENT|was=") })
    }

    private val purchase = "An amount of AED 3865.95 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08"
    private val purchaseOther = "An amount of AED 38.65 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08"

    private fun heldChange(): Long {
        val t = at(24, 1, 2025)
        runBlocking { repo.ingestSms("FAB", purchase, t, t, "SMS") }
        val id = runBlocking { db.dao().allSms().single().id }
        db.openHelper.writableDatabase.execSQL("UPDATE sms SET body = ? WHERE id = ?", arrayOf(purchaseOther, id))
        runBlocking { repo.reparseAll(false) }
        assertEquals(1, runBlocking { db.dao().heldReadingsNow() }.size)
        return id
    }

    @Test fun keep_mine_sends_a_choice_record() {
        val id = heldChange()
        runBlocking { repo.keepCurrentReading(id) }
        assertTrue(kinds.toString(), kinds.any { it.startsWith("choice:keep-mine|changes=") && it.contains("amount") })
    }

    @Test fun use_new_sends_a_choice_record() {
        val id = heldChange()
        runBlocking { repo.useNewReading(id) }
        assertTrue(kinds.toString(), kinds.any { it.startsWith("choice:use-new|changes=") })
    }
}
