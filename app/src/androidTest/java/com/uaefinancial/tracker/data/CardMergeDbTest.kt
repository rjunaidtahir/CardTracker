package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** "These two are one card": merge moves everything, removes the card, and later messages go to the kept card. */
@RunWith(AndroidJUnit4::class)
class CardMergeDbTest {
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

    private fun at(d: Int, h: Int): Long = LocalDateTime.of(2026, 9, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun purchase(card: String, amount: String, merchant: String, day: Int) =
        "Credit Card Purchase\nCard No XXXX$card\nAED $amount\n$merchant DUBAI ARE\n$day/09/26 09:15\nAvl Bal AED 900.00"

    private fun ingest(body: String, t: Long) = runBlocking { repo.ingestSms("FAB", body, t, t, "SMS") }
    private fun cards() = runBlocking { db.dao().allCards() }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }

    @Test fun merge_moves_transactions_and_removes_the_other_card() {
        ingest(purchase("1111", "10.00", "COFFEE", 19), at(19, 9))
        ingest(purchase("2222", "20.00", "BOOKS", 20), at(20, 9))
        assertEquals(2, cards().size)
        assertTrue(runBlocking { repo.mergeCards("FAB ·2222", "FAB ·1111") })
        assertEquals(listOf("FAB ·1111"), cards().map { it.cardKey })
        assertEquals(setOf("FAB ·1111"), txns().map { it.cardKey }.toSet())
        assertEquals(2, txns().size)
    }

    @Test fun later_messages_for_the_merged_card_go_to_the_kept_card() {
        ingest(purchase("1111", "10.00", "COFFEE", 19), at(19, 9))
        ingest(purchase("2222", "20.00", "BOOKS", 20), at(20, 9))
        runBlocking { repo.mergeCards("FAB ·2222", "FAB ·1111") }
        ingest(purchase("2222", "30.00", "SHOES", 21), at(21, 9))
        assertEquals(listOf("FAB ·1111"), cards().map { it.cardKey })
        assertEquals(3, txns().size)
        assertEquals(setOf("FAB ·1111"), txns().map { it.cardKey }.toSet())
    }

    @Test fun merging_a_card_into_itself_or_a_missing_card_does_nothing() {
        ingest(purchase("1111", "10.00", "COFFEE", 19), at(19, 9))
        assertFalse(runBlocking { repo.mergeCards("FAB ·1111", "FAB ·1111") })
        assertFalse(runBlocking { repo.mergeCards("FAB ·1111", "FAB ·9999") })
        assertEquals(1, cards().size)
        assertEquals(1, txns().size)
    }

    @Test fun merge_survives_a_chain() {
        ingest(purchase("1111", "10.00", "COFFEE", 19), at(19, 9))
        ingest(purchase("2222", "20.00", "BOOKS", 20), at(20, 9))
        ingest(purchase("3333", "30.00", "SHOES", 21), at(21, 9))
        runBlocking { repo.mergeCards("FAB ·3333", "FAB ·2222") }
        runBlocking { repo.mergeCards("FAB ·2222", "FAB ·1111") }
        ingest(purchase("3333", "5.00", "TEA", 22), at(22, 9))
        assertEquals(listOf("FAB ·1111"), cards().map { it.cardKey })
        assertEquals(4, txns().size)
    }
}
