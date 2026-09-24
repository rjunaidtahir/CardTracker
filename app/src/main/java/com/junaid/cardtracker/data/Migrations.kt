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

    /** v3 -> v4: categories, learning, goals, exchange rates (Phases 2-4). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN merchantKey TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_merchantKey` ON `transactions` (`merchantKey`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, `archived` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `merchant_rules` (`merchantKey` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`merchantKey`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `txn_overrides` (`dedupKey` TEXT NOT NULL, `categoryId` INTEGER, PRIMARY KEY(`dedupKey`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`targetMinor` INTEGER NOT NULL, `savedMinor` INTEGER NOT NULL, `targetDateEpochDay` INTEGER, `createdAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `fx_rates` (`currency` TEXT NOT NULL, `rateToAed` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`currency`))",
            )
        }
    }

    /** v4 -> v5: card look and order, category budgets, fixed payments. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cards ADD COLUMN themeKey TEXT")
            db.execSQL("ALTER TABLE cards ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 1000")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `budgets` (`categoryId` INTEGER NOT NULL, `monthlyLimitMinor` INTEGER NOT NULL, PRIMARY KEY(`categoryId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `fixed_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`amountMinor` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `categoryId` INTEGER, `cardKey` TEXT, " +
                    "`remind` INTEGER NOT NULL, `active` INTEGER NOT NULL, `lastPaidYm` TEXT, `createdAt` INTEGER NOT NULL)",
            )
        }
    }

    /** v5 -> v6: whose card it is (yours / family). */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cards ADD COLUMN owner TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}
