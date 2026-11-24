package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.parser.RulesPack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.8 spec 12, first start: rows added from a statement PDF whose saved shop name is a place line (city, country, currency
 * pair, digits; invented here) are only marked "Check this". A row you categorised, a row with a normal shop name, a row
 * typed by hand and a row that already has a mark are untouched; nothing else of any row changes. Counts only in the report.
 */
@RunWith(AndroidJUnit4::class)
class StmtNamesFlagDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        repo.onEvent = { kind, _ -> events += kind }
    }

    @After fun tearDown() { RulesPack.apply(null); db.close() }

    private fun row(merchant: String, note: String? = Repository.STATEMENT_NOTE, userSet: Boolean = false, check: String? = null, amount: Long = 1_234L) =
        TransactionEntity(
            smsId = null, source = "MANUAL", timestamp = 1_790_000_000_000L + amount, bank = "HSBC", cardLast4 = "4321", cardKey = "HSBC ·4321",
            merchant = merchant, amountMinor = amount, currency = "AED", amountAedMinor = amount, fxEstimated = false, type = "PURCHASE",
            categoryUserSet = userSet, note = note, checkNote = check,
        )

    private fun insert(t: TransactionEntity) = runBlocking { db.dao().insertTxn(t) }
    private fun all() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.associateBy { it.id }

    @Test fun onlyUneditedPlaceLineRowsAreMarkedAndNothingElseChanges() {
        val garbled = insert(row("SALALAH OM OMR/AED .104790419", amount = 1_000L))
        val edited = insert(row("NIZWA OM OMR/AED .104790420", userSet = true, amount = 2_000L))
        val normal = insert(row("CARREFOUR CITY CENTRE", amount = 3_000L))
        val typed = insert(row("SOHAR OM OMR/AED .104790421", note = null, amount = 4_000L))
        val marked = insert(row("SUR OM OMR/AED .104790422", check = "gap:merchant", amount = 5_000L))
        val before = all()

        assertEquals(1, runBlocking { repo.flagGarbledStatementNames() })
        val after = all()
        assertEquals(Repository.STMT_NAME_NOTE, after.getValue(garbled).checkNote)
        assertNull(after.getValue(edited).checkNote)
        assertNull(after.getValue(normal).checkNote)
        assertNull(after.getValue(typed).checkNote)
        assertEquals("gap:merchant", after.getValue(marked).checkNote)
        // Never an amount, date, card, type or name.
        for ((id, b) in before) {
            val a = after.getValue(id)
            assertEquals(listOf(b.amountMinor, b.timestamp, b.cardKey, b.type, b.merchant, b.currency), listOf(a.amountMinor, a.timestamp, a.cardKey, a.type, a.merchant, a.currency))
        }
        assertEquals(listOf("event:stmt-names-flagged|n=1"), events)

        // A second run finds nothing new to mark and reports nothing.
        assertEquals(0, runBlocking { repo.flagGarbledStatementNames() })
        assertEquals(1, events.size)
    }

    @Test fun theKillSwitchLeavesEveryRowAlone() {
        val out = RulesPack.parse("""{"version":40,"minEngine":5,"banks":[],"rules":[],"ext":{"levers":{"stmtNamesFlag":false}}}""")
        RulesPack.apply((out as RulesPack.Outcome.Ok).pack)
        val garbled = insert(row("SALALAH OM OMR/AED .104790419"))
        assertEquals(-1, runBlocking { repo.flagGarbledStatementNames() })
        assertNull(all().getValue(garbled).checkNote)
        assertEquals(emptyList<String>(), events)
    }
}
