package com.junaid.cardtracker.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real migrations from v2 onward, so your data survives upgrades. Planned (see ROADMAP.md):
 *  - Phase 2: reminders table, any extra card-profile fields
 *  - Phase 3: categories + merchant_category_rules tables
 *  - Phase 4: savings_goals table
 */
object Migrations {
    /** v2 -> v3: transfers to your own cards + merged two-SMS transfers. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN counterpartyKey TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN pairedSmsId INTEGER")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
