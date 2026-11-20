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

/** Phase 1d: messages from senders the app doesn't know, against a real Room database. */
@RunWith(AndroidJUnit4::class)
class UnknownSenderDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setExcludedBanks(emptySet())
    }

    @After fun tearDown() {
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setExcludedBanks(emptySet())
        db.close()
    }

    private val t = LocalDateTime.of(2026, 9, 21, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val purchase = "Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00."
    private val laterPurchase = "Your card ending 9090 was used for AED 15.00 at SPINNEYS on 22/09/2026 13:00."
    private val unfamiliar = "Reminder: your invoice of AED 350.00 is waiting for you."
    private val otp = "Your OTP is 123456 for payment of AED 100.00. Do not share it."
    private val chat = "Are we still on for dinner tonight?"

    private fun ingest(sender: String, body: String, at: Long = t) = runBlocking { repo.ingestSms(sender, body, at, at, "SYNC") }
    private fun candidates() = runBlocking { db.dao().allCandidates() }
    private fun open() = candidates().filter { it.state == "OPEN" }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }
    private fun integrity() = db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) }

    @Test fun a_clear_purchase_from_an_unknown_sender_waits_for_review_and_is_not_counted() {
        assertEquals(IngestOutcome.NOT_BANK, ingest("MYBANKX", purchase))
        assertEquals(1, open().size)
        assertEquals("RECOGNISABLE", open().single().kind)
        assertEquals(0, txns().size)
        assertEquals(0, runBlocking { db.dao().allSms() }.size)
    }

    @Test fun a_message_with_an_amount_but_no_transaction_is_not_kept() {
        ingest("MYBANKX", unfamiliar)
        assertEquals(0, candidates().size)
    }

    @Test fun a_flood_of_promotions_never_reaches_review() {
        val promos = listOf(
            "du recharge offer: Recharge any du prepaid with AED 50+ and get AED 10 back instantly. T&Cs apply.",
            "Win AED 1,000 every day! Enter now: dupay.app.link/CSM25",
            "Get 30 GB data for only AED 50. Dial *055*199# to activate.",
        )
        for (i in 0 until 600) ingest("AD-Promo${i % 30}", promos[i % 3] + " ref $i", t + i)
        assertEquals(0, candidates().size)
        assertEquals(0, runBlocking { db.dao().allSms() }.size)
    }

    @Test fun real_looking_transactions_are_capped_per_sender_so_review_cannot_be_buried() {
        for (i in 0 until 40) ingest("MYBANKX", "Your card ending 9090 was used for AED ${10 + i}.00 at SHOP$i on 21/09/2026 12:00.", t + i * 1000L)
        assertEquals(MAX_OPEN_PER_SENDER, open().size)
    }

    @Test fun a_sender_declined_at_setup_never_comes_into_review() {
        runBlocking { repo.declineSenders(listOf("MYBANKX")) }
        ingest("MYBANKX", purchase)
        assertEquals(0, open().size)
    }

    @Test fun one_time_codes_and_chat_are_never_kept() {
        ingest("MYBANKX", otp)
        ingest("MYBANKX", chat)
        ingest("+971501234567", "can you send me 50 dirhams for lunch")
        assertEquals(0, candidates().size)
    }

    @Test fun the_same_message_twice_is_one_candidate() {
        ingest("MYBANKX", purchase)
        ingest("MYBANKX", purchase)
        assertEquals(1, open().size)
    }

    @Test fun accepting_the_sender_reads_its_waiting_messages_and_later_ones_go_straight_in() {
        ingest("MYBANKX", purchase)
        val n = runBlocking { repo.acceptSender("MYBANKX", "My Bank") }
        assertEquals(1, n)
        val rows = txns()
        assertEquals(1, rows.size)
        assertEquals(6000L, rows.single().amountMinor)
        assertEquals(0, open().size)
        assertEquals("ACCEPTED", candidates().single().state)
        assertEquals("", candidates().single().body)
        assertEquals(IngestOutcome.TRANSACTION, ingest("MYBANKX", laterPurchase, t + 86_400_000L))
        assertEquals(2, txns().size)
        assertEquals("ok", integrity())
    }

    @Test fun not_financial_clears_the_text_and_the_message_is_not_offered_again() {
        ingest("MYBANKX", purchase)
        runBlocking { repo.dismissCandidate(open().single().id) }
        assertEquals(0, open().size)
        assertEquals("", candidates().single().body)
        ingest("MYBANKX", purchase)
        assertEquals(1, candidates().size)
    }

    @Test fun hiding_a_sender_drops_its_waiting_messages_and_keeps_later_ones_out_until_shown_again() {
        ingest("MYBANKX", purchase)
        runBlocking { repo.muteSender("MYBANKX") }
        assertEquals(0, open().size)
        ingest("MYBANKX", laterPurchase, t + 86_400_000L)
        assertEquals(0, open().size)
        runBlocking { repo.unmuteSender(com.uaefinancial.tracker.parser.SmsParser.senderKey("MYBANKX")) }
        ingest("MYBANKX", laterPurchase, t + 86_400_000L)
        assertEquals(1, open().size)
    }

    @Test fun open_candidates_are_never_removed_by_re_reading_or_by_time() {
        ingest("MYBANKX", purchase)
        db.openHelper.writableDatabase.execSQL("UPDATE sms_candidates SET createdAt = 1000, receivedAt = 1000")
        runBlocking { repo.reparseAll(true) }
        runBlocking { repo.reparseAll(false) }
        runBlocking { repo.finishBatch() }
        assertEquals(1, open().size)
        assertEquals(purchase, open().single().body)
    }

    @Test fun a_bank_you_unticked_is_not_treated_as_unknown() {
        SmsParser.setExcludedBanks(setOf("FAB"))
        ingest("FAB", "An amount of AED 38.65 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08")
        assertEquals(0, candidates().size)
    }

    @Test fun a_known_bank_is_read_as_before_and_makes_no_candidate() {
        assertEquals(
            IngestOutcome.TRANSACTION,
            ingest("FAB", "An amount of AED 38.65 has been debited from your FAB account XXXX8001 on 24/01/25 .Your Available Balance is AED 28186.08"),
        )
        assertEquals(0, candidates().size)
        assertEquals(1, txns().size)
    }

    @Test fun the_message_is_not_counted_twice_if_sync_sees_it_again_after_accepting() {
        ingest("MYBANKX", purchase)
        runBlocking { repo.acceptSender("MYBANKX", "My Bank") }
        assertEquals(IngestOutcome.DUPLICATE, ingest("MYBANKX", purchase))
        assertEquals(1, txns().size)
    }

    @Test fun the_candidate_keeps_a_masked_shape_with_no_personal_digits() {
        ingest("MYBANKX", purchase)
        val c = open().single()
        assertTrue(c.shapeKey.isNotEmpty())
        assertTrue(!c.shapeKey.contains("9090") && !c.shapeKey.contains("60.00"))
    }
}
