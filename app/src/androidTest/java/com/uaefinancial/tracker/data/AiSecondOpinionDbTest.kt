package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.parser.SmsParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The AI second opinion is advisory: it may only add rows to ai_checks, and never touch anything else. */
@RunWith(AndroidJUnit4::class)
class AiSecondOpinionDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = Prefs(ctx)
        prefs.aiSecondOpinion = true
        repo = Repository(db, prefs)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
        SmsParser.setCustomSenders(emptyMap())
        SmsParser.setExcludedBanks(emptySet())
    }

    @After fun tearDown() { prefs.aiSecondOpinion = true; db.close() }

    private val t = LocalDateTime.of(2026, 9, 21, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val bodies = listOf(
        "Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00.",
        "Your card ending 9090 was used for AED 15.00 at SPINNEYS on 22/09/2026 13:00.",
        "Your card ending 9090 was used for USD 20.00 at AMAZON on 23/09/2026 09:00.",
        "AED 500.00 has been credited to your account ending 1234 on 24/09/2026.",
        "Your OTP is 123456 for payment of AED 100.00. Do not share it.",
        "Reminder: your invoice of AED 350.00 is waiting for you.",
        "Hello, are we still on for dinner tonight?",
    )

    private fun load() = runBlocking { bodies.forEachIndexed { i, b -> repo.ingestSms("FAB", b, t + i * 60_000L, t + i * 60_000L, "SYNC") } }

    private fun ledger(): List<String> = runBlocking {
        val tx = db.dao().allTxns().map { "${it.smsId}|${it.type}|${it.amountMinor}|${it.currency}|${it.merchant}|${it.cardKey}|${it.timestamp}|${it.categoryId}" }
        val sms = db.dao().allSms().map { "${it.id}|${it.status}|${it.body}|${it.note}" }
        val st = db.dao().allStatements().map { "${it.smsId}|${it.id}" }
        tx + sms + st
    }

    private fun checks() = runBlocking { db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ai_checks").use { it.moveToFirst(); it.getInt(0) } }
    private fun integrity() = db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { it.moveToFirst(); it.getString(0) }

    @Test fun the_ledger_is_identical_with_the_second_opinion_on_and_off() {
        load()
        val before = ledger()
        assertTrue(before.isNotEmpty())
        runBlocking { repo.updateSecondOpinions() }
        val on = ledger()
        assertEquals(before, on)
        val rows = checks()
        assertTrue("expected a check for each read message, got $rows", rows >= 1)
        prefs.aiSecondOpinion = false
        runBlocking { repo.updateSecondOpinions() }
        assertEquals(before, ledger())
        assertEquals(0, checks())
        assertEquals("ok", integrity())
    }

    @Test fun running_it_twice_gives_the_same_rows_and_pruning_follows_deleted_messages() {
        load()
        runBlocking { repo.updateSecondOpinions() }
        val a = checks()
        runBlocking { repo.updateSecondOpinions() }
        assertEquals(a, checks())
        val someSms = runBlocking { db.dao().allSms() }.first { it.status == SmsStatus.TRANSACTION }
        runBlocking { db.dao().deleteSms(someSms.id); repo.updateSecondOpinions() }
        assertEquals(a - 1, checks())
    }

    @Test fun messages_the_rules_did_not_read_get_no_check_and_otp_never_does() {
        load()
        runBlocking { repo.updateSecondOpinions() }
        val checkedIds = runBlocking {
            db.openHelper.readableDatabase.query("SELECT smsId FROM ai_checks").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }
        val unread = runBlocking { db.dao().allSms() }.filter { it.status != SmsStatus.TRANSACTION && it.status != SmsStatus.STATEMENT }
        assertTrue(unread.none { it.id in checkedIds })
    }

    @Test fun disagreements_flow_only_lists_clear_disagreements() {
        load()
        runBlocking { repo.updateSecondOpinions() }
        val dis = runBlocking { repo.aiDisagreements().first() }
        assertTrue(dis.all { it.verdict == "DISAGREE" && it.confidencePct >= 80 })
    }

    @Test fun a_suggestion_for_an_unread_message_never_saves_anything() {
        load()
        val before = ledger()
        val sms = runBlocking { db.dao().allSms() }.firstOrNull { it.status == SmsStatus.FAILED || it.status == SmsStatus.IGNORED }
        if (sms != null) runBlocking { repo.aiSuggestion(sms) }
        assertEquals(before, ledger())
    }
}
