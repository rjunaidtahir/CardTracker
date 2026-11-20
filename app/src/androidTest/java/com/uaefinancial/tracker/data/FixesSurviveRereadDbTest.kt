package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
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
 * Re-reading stored messages (which happens by itself when a new rules file arrives) rebuilds every SMS-based row from
 * scratch. What you decided by hand must come back unchanged: a message you fixed in Needs review, its category, and a
 * message you marked "not a transaction". Real Room database on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class FixesSurviveRereadDbTest {
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
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedBy { it.id }
    private fun smsList() = runBlocking { db.dao().smsForReparse() }

    @Test fun a_fix_a_category_and_a_not_a_transaction_mark_all_survive_a_reread() {
        val t = at(19, 11, 2026)
        // Two messages the app cannot read (made-up wording with no rule), one we fix, one we mark as not a transaction.
        runBlocking {
            repo.ingestSms("FAB", "Zorp quux AED 123.45 blarg 9999 wibble", t, t, "SMS")
            repo.ingestSms("FAB", "Frobnicate notice AED 77.00 gizmo 5555 thing", t + 60_000, t + 60_000, "SMS")
        }
        val sms = smsList().sortedBy { it.id }
        assertEquals(2, sms.size)
        val (fixedSms, ignoredSms) = sms

        runBlocking {
            repo.saveFix(fixedSms.id, TxnType.PURCHASE, 12_345L, "AED", "MY OWN SHOP", "9999", CardType.CREDIT, t)
            repo.saveFix(ignoredSms.id, null, 7_700L, "AED", "x", null, CardType.CREDIT, t + 60_000)
        }
        val mine = txns().single { it.merchant == "MY OWN SHOP" }
        runBlocking { repo.setCategory(mine, 2L, false) }
        assertEquals(2L, txns().single { it.merchant == "MY OWN SHOP" }.categoryId)

        // The re-read that a new rules file triggers, twice (it must be repeatable).
        runBlocking { repo.reparseAll() }
        runBlocking { repo.reparseAll() }

        val after = txns()
        assertEquals("only the fixed message is a transaction", 1, after.size)
        val x = after.single()
        assertEquals("MY OWN SHOP", x.merchant)
        assertEquals(12_345L, x.amountMinor)
        assertEquals("AED", x.currency)
        assertEquals("9999", x.cardLast4)
        assertEquals("the category you chose is kept", 2L, x.categoryId)
        val ign = smsList().single { it.id == ignoredSms.id }
        assertEquals("still ignored", SmsStatus.IGNORED, ign.status)
        assertTrue(ign.note?.contains("not a transaction", ignoreCase = true) == true)
    }
}
