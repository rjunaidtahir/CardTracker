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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 2.8 spec 7.1, 16.4 and 16.5 on a real Room database: pairs declared in the rules file (non-FAB bank), the CBD
 * blocked-card notice in all three orders, and `needsTwin` ("now on its way" counts only with its transfer). Invented wordings.
 */
@RunWith(AndroidJUnit4::class)
class PackPairDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
    }

    @After fun tearDown() { Pack28.reset(); db.close() }

    private fun at(d: Int, h: Int, min: Int = 0): Long = LocalDateTime.of(2026, 11, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun ingest(sender: String, body: String, t: Long) = runBlocking { repo.ingestSms(sender, body, t, null, "SMS") }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedBy { it.id }
    private fun sms() = runBlocking { db.dao().allSms() }.sortedBy { it.id }
    private fun reread() = runBlocking { repo.reparseAll() }

    // ---- CBD blocked-card notice (16.4): a PURCHASE in one pair group with the CBD debit message
    private val notice = Pack28.rule("CBD", "cbd-blocked", """Your card {CARD} has been blocked after a transaction of {CUR} {AMOUNT} at {MERCHANT}\.""",
        "PURCHASE", "DEBIT", ",\"pairGroup\":\"cbd-block\",\"describesDestination\":true,\"pairWindowMinutes\":1440,\"checkUntilPaired\":true")
    private val debit = Pack28.rule("CBD", "cbd-debit", """Purchase of {CUR} {AMOUNT} on debit card {CARD} at {MERCHANT}\. Avl bal {ANYCUR} {AVAIL}""",
        "PURCHASE", "DEBIT", ",\"pairGroup\":\"cbd-block\",\"describesDestination\":false,\"pairWindowMinutes\":1440")
    private val noticeBody = "Your card XXXX4321 has been blocked after a transaction of AED 250.00 at SOME ONLINE SHOP. Call us if this was not you."
    private val debitBody = "Purchase of AED 250.00 on debit card XXXX4321 at SOME ONLINE SHOP. Avl bal AED 1,750.00"
    private fun cbdPairs() = Pack28.apply(listOf("pairs"), listOf(notice, debit))

    @Test fun debit_then_blocked_notice_is_one_purchase() {
        cbdPairs()
        assertEquals(IngestOutcome.TRANSACTION, ingest("CBD", debitBody, at(10, 9)))
        assertNull("a debit message on its own is not marked", txns().single().checkNote)
        assertEquals(IngestOutcome.MERGED, ingest("CBD", noticeBody, at(10, 9, 2)))
        val t = txns().single()
        assertEquals(25_000L, t.amountMinor); assertEquals("PURCHASE", t.type); assertEquals(175_000L, t.availableLimitMinor)
        assertNull(t.checkNote)
    }

    @Test fun blocked_notice_alone_counts_once_and_is_marked_check_this() {
        cbdPairs()
        assertEquals(IngestOutcome.TRANSACTION, ingest("CBD", noticeBody, at(10, 9)))
        val t = txns().single()
        assertEquals(25_000L, t.amountMinor)
        assertEquals(Repository.UNPAIRED_NOTE, t.checkNote)
    }

    @Test fun blocked_notice_then_debit_later_merges_and_clears_the_mark() {
        cbdPairs()
        ingest("CBD", noticeBody, at(10, 9))
        assertEquals(IngestOutcome.MERGED, ingest("CBD", debitBody, at(10, 20)))
        val t = txns().single()
        assertEquals(25_000L, t.amountMinor); assertNull(t.checkNote)
        // A re-read of everything ends the same way.
        reread()
        assertEquals(1, txns().size); assertEquals(25_000L, txns().single().amountMinor)
    }

    @Test fun pairs_lever_off_keeps_both_rows_as_before() {
        Pack28.apply(emptyList(), listOf(notice, debit))
        // Without the lever the rules file cannot pair (2.7 behaviour): both messages are counted.
        ingest("CBD", debitBody, at(10, 9)); ingest("CBD", noticeBody, at(10, 9, 2))
        assertEquals(2, txns().size)
    }

    @Test fun different_merchants_of_the_same_amount_stay_two() {
        cbdPairs()
        ingest("CBD", debitBody, at(10, 9))
        ingest("CBD", noticeBody.replace("SOME ONLINE SHOP", "GARDEN TOOLS STORE"), at(10, 9, 5))
        assertEquals(2, txns().size)
    }

    @Test fun fab_pairs_are_unchanged_with_every_lever_on() {
        Pack28.apply(listOf("pairs", "crossSourceMerge", "needsTwin", "keyChange", "directionFromBalance"))
        ingest("FAB", "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate 17/11/2026\nBalance AED 2386.00", at(17, 9))
        assertEquals(
            IngestOutcome.MERGED,
            ingest("FAB", "Dear Customer, your funds transfer request of  AED 1,000.00 to IBAN/Account/Card XXXX9940  has been processed " +
                "successfully from your account/card XXXX8001 on 17/11/2026 21:45", at(17, 21, 45)),
        )
        val t = txns().single()
        assertEquals("TRANSFER_OUT", t.type); assertEquals(100_000L, t.amountMinor)
    }

    // ---- needsTwin (16.5)
    private val onWay = Pack28.rule("CBD", "cbd-on-way", """Your transfer of {CUR} {AMOUNT} is now on its way""",
        "TRANSFER_OUT", "ACCOUNT", ",\"fixedMerchant\":\"Transfer\",\"needsTwin\":true,\"twinWindowMinutes\":1440")
    private val out = Pack28.rule("CBD", "cbd-out", """{CUR} {AMOUNT} has been transferred from your account {CARD} to {MERCHANT}\.""",
        "TRANSFER_OUT", "ACCOUNT")
    private val onWayBody = "Your transfer of AED 900.00 is now on its way to S***** A****."
    private val outBody = "AED 900.00 has been transferred from your account XXXX5555 to SOME PERSON. Thank you."
    private fun twins() = Pack28.apply(listOf("needsTwin"), listOf(onWay, out))

    @Test fun on_its_way_with_its_transfer_already_there_is_one_row() {
        twins()
        ingest("CBD", outBody, at(12, 9))
        assertEquals(IngestOutcome.MERGED, ingest("CBD", onWayBody, at(12, 9, 1)))
        val t = txns().single()
        assertEquals(90_000L, t.amountMinor)
        assertEquals(sms().last().id, t.pairedSmsId)
    }

    @Test fun on_its_way_waits_and_joins_the_transfer_that_arrives_later() {
        twins()
        assertEquals(IngestOutcome.FAILED, ingest("CBD", onWayBody, at(12, 9)))
        assertTrue(txns().isEmpty())
        assertEquals(Repository.NEEDS_TWIN_NOTE, sms().single().note)
        ingest("CBD", outBody, at(12, 11))
        assertEquals(1, txns().size)
        assertEquals(SmsStatus.TRANSACTION, sms().first().status)
        assertEquals(sms().first().id, txns().single().pairedSmsId)
        reread()
        assertEquals(1, txns().size)
    }

    @Test fun on_its_way_with_no_transfer_never_counts() {
        twins()
        ingest("CBD", onWayBody, at(12, 9))
        reread()
        assertTrue(txns().isEmpty())
        assertEquals(SmsStatus.FAILED, sms().single().status)
    }

    @Test fun two_equal_transfers_the_message_joins_the_nearest_one_only() {
        twins()
        ingest("CBD", outBody, at(12, 9))
        ingest("CBD", outBody.replace("SOME PERSON", "OTHER PERSON"), at(12, 15))
        ingest("CBD", onWayBody, at(12, 15, 2))
        val rows = txns()
        assertEquals(2, rows.size)
        assertNull(rows[0].pairedSmsId)
        assertEquals(sms().last().id, rows[1].pairedSmsId)
    }
}
