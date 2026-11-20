package com.uaefinancial.tracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The version 6 layout is the one the Phase 1a-1c beta exports (app/schemas/.../6.json). */
@RunWith(AndroidJUnit4::class)
class MigrationSixToSevenTest {
    private val name = "migration-test-67"

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existing_data_survives_and_the_candidates_table_starts_empty() {
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO sms (id, dedupKey, sender, body, bodyHash, receivedAt, sentAt, source, bank, status, ruleId, note) VALUES (1,'k1','FAB','body one','h1',1000,1000,'SYNC','FAB','TRANSACTION','r',NULL)")
            execSQL("INSERT INTO sms_fixes (dedupKey, type, amountMinor, currency, merchant, cardLast4, cardType, timestamp, lockedFields) VALUES ('k1','PURCHASE',12345,'AED','Shop','1234','CREDIT',1000,'AMOUNT')")
            execSQL("INSERT INTO sms_readings (id, smsId, engineVersion, createdAt, keptSummary, newSummary, state) VALUES (1,1,7,2000,'kept','new','HELD')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 7, true, Migrations.MIGRATION_6_7)
        db.query("SELECT COUNT(*) FROM sms").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT lockedFields FROM sms_fixes WHERE dedupKey = 'k1'").use { it.moveToFirst(); assertEquals("AMOUNT", it.getString(0)) }
        db.query("SELECT state FROM sms_readings WHERE id = 1").use { it.moveToFirst(); assertEquals("HELD", it.getString(0)) }
        db.query("SELECT COUNT(*) FROM sms_candidates").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.execSQL("INSERT INTO sms_candidates (dedupKey, sender, senderKey, body, receivedAt, sentAt, source, kind, reading, shapeKey, createdAt, state) VALUES ('d1','X','X','b',1,NULL,'SYNC','UNFAMILIAR',NULL,'',1,'OPEN')")
        db.query("SELECT COUNT(*) FROM sms_candidates").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
    }
}
