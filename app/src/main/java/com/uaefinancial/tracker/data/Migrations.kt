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

    /** 2.6: transactions placed from a standing-instruction message that had no debit message (nothing is removed). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN inferred INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 2.6: merged cards ("these two are one card"); nothing is removed. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `card_aliases` (`fromKey` TEXT NOT NULL, `toKey` TEXT NOT NULL, PRIMARY KEY(`fromKey`))")
        }
    }

    /** 2.7: re-reads that would change an existing reading are held for your review; nothing is removed. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sms_readings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`smsId` INTEGER NOT NULL, `engineVersion` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`keptSummary` TEXT NOT NULL, `newSummary` TEXT NOT NULL, `state` TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_readings_smsId` ON `sms_readings` (`smsId`)")
            // Fixes made before this keep protecting every field (the old behaviour).
            db.execSQL("ALTER TABLE sms_fixes ADD COLUMN lockedFields TEXT NOT NULL DEFAULT 'ALL'")
        }
    }

    /** 2.7: messages from senders the app doesn't know yet, kept for your review; nothing is removed. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sms_candidates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`dedupKey` TEXT NOT NULL, `sender` TEXT NOT NULL, `senderKey` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                    "`receivedAt` INTEGER NOT NULL, `sentAt` INTEGER, `source` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`reading` TEXT, `shapeKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `state` TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sms_candidates_dedupKey` ON `sms_candidates` (`dedupKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_candidates_senderKey` ON `sms_candidates` (`senderKey`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
