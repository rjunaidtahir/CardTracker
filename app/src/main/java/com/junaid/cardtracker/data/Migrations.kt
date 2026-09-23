package com.junaid.cardtracker.data

import androidx.room.migration.Migration

/**
 * Real migrations from v2 onward, so your data survives upgrades. Planned (see ROADMAP.md):
 *  - v3 (Phase 2): reminders table, any extra card-profile fields
 *  - v4 (Phase 3): categories + merchant_category_rules tables
 *  - v5 (Phase 4): savings_goals table
 * Example:
 *   val MIGRATION_2_3 = object : Migration(2, 3) {
 *       override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE ...") }
 *   }
 */
object Migrations {
    val ALL: Array<Migration> = arrayOf()
}
