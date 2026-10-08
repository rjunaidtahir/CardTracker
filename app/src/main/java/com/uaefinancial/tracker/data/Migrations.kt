package com.uaefinancial.tracker.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations, so people's data survives app updates.
 *
 * The schema starts again at version 1 in Fils 2.0 (a new app ID, so there is nothing to migrate
 * from). From now on, every schema change must:
 *  1. bump the version in AppDatabase,
 *  2. add a Migration(n, n + 1) here that ALTERs / CREATEs what changed (never drop user data),
 *  3. add it to [ALL].
 * Never use a destructive fallback: it would wipe everyone's history on update.
 */
object Migrations {
    /** 2.3: the available limit printed on a saved statement, so a card's available limit can be rolled forward from it. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cards ADD COLUMN statementAvailMinor INTEGER")
            db.execSQL("ALTER TABLE cards ADD COLUMN statementAvailEpochDay INTEGER")
        }
    }

    /** 2.4: a check note on transactions, and instalment plans read from statements. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN checkNote TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS instalment_plans (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardKey TEXT NOT NULL, kind TEXT NOT NULL, " +
                    "bookedEpochDay INTEGER, originalMinor INTEGER, outstandingMinor INTEGER, instalmentsLeft INTEGER, " +
                    "tenure INTEGER, monthlyMinor INTEGER, endEpochDay INTEGER, readEpochDay INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_instalment_plans_cardKey ON instalment_plans (cardKey)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
