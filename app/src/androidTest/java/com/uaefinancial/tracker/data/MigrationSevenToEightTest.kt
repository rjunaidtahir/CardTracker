package com.uaefinancial.tracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The version 7 layout is the Phase 1d beta (app/schemas/.../7.json). */
@RunWith(AndroidJUnit4::class)
class MigrationSevenToEightTest {
    private val name = "migration-test-78"

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existing_data_survives_and_ai_checks_starts_empty() {
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO sms (id, dedupKey, sender, body, bodyHash, receivedAt, sentAt, source, bank, status, ruleId, note) VALUES (1,'k1','FAB','body one','h1',1000,1000,'SYNC','FAB','TRANSACTION','r',NULL)")
            execSQL("INSERT INTO sms_candidates (dedupKey, sender, senderKey, body, receivedAt, sentAt, source, kind, reading, shapeKey, createdAt, state) VALUES ('d1','X','X','b',1,NULL,'SYNC','UNFAMILIAR',NULL,'',1,'OPEN')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 8, true, Migrations.MIGRATION_7_8)
        db.query("SELECT COUNT(*) FROM sms").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM sms_candidates").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM ai_checks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.execSQL("INSERT INTO ai_checks (smsId, ruleLabel, aiLabel, confidencePct, verdict, createdAt) VALUES (1,'PURCHASE','REFUND',90,'DISAGREE',1)")
        db.query("SELECT COUNT(*) FROM ai_checks").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
    }
}
