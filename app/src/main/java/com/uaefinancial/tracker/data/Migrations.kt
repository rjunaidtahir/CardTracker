package com.uaefinancial.tracker.data

import androidx.room.migration.Migration

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
    val ALL: Array<Migration> = emptyArray()
}
