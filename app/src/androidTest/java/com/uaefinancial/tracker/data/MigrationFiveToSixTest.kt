package com.uaefinancial.tracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The version 5 layout is the one the current Play build exports (app/schemas/.../5.json). */
@RunWith(AndroidJUnit4::class)
class MigrationFiveToSixTest {
    private val name = "migration-test"

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existing_data_survives_and_old_fixes_still_protect_everything() {
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO sms (id, dedupKey, sender, body, bodyHash, receivedAt, sentAt, source, bank, status, ruleId, note) VALUES (1,'k1','FAB','body one','h1',1000,1000,'SYNC','FAB','TRANSACTION','r',NULL)")
            execSQL("INSERT INTO sms (id, dedupKey, sender, body, bodyHash, receivedAt, sentAt, source, bank, status, ruleId, note) VALUES (2,'k2','FAB','body two','h2',2000,2000,'LIVE','FAB','FAILED',NULL,'x')")
            execSQL("INSERT INTO sms_fixes (dedupKey, type, amountMinor, currency, merchant, cardLast4, cardType, timestamp) VALUES ('k2','PURCHASE',12345,'AED','Shop','1234','CREDIT',2000)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 6, true, Migrations.MIGRATION_5_6)
        db.query("SELECT COUNT(*) FROM sms").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        db.query("SELECT amountMinor, lockedFields FROM sms_fixes WHERE dedupKey = 'k2'").use {
            it.moveToFirst(); assertEquals(12345L, it.getLong(0)); assertEquals("ALL", it.getString(1))
        }
        db.query("SELECT COUNT(*) FROM sms_readings").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
    }
}
