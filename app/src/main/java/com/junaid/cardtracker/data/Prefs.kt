package com.junaid.cardtracker.data

import android.content.Context

/** Small settings. (Phase 2 app-lock settings will live here too.) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cardtracker", Context.MODE_PRIVATE)

    /** Start time of the last successful Sync, or null if Sync has never run. */
    var lastSyncAt: Long?
        get() = sp.getLong(KEY_LAST_SYNC, -1L).takeIf { it >= 0 }
        set(v) { sp.edit().putLong(KEY_LAST_SYNC, v ?: -1L).apply() }

    var liveListening: Boolean
        get() = sp.getBoolean(KEY_LIVE, false)
        set(v) { sp.edit().putBoolean(KEY_LIVE, v).apply() }

    var samsungTipShown: Boolean
        get() = sp.getBoolean(KEY_SAMSUNG_TIP, false)
        set(v) { sp.edit().putBoolean(KEY_SAMSUNG_TIP, v).apply() }

    // --- due-date reminders (off until you switch them on)
    var remindersEnabled: Boolean
        get() = sp.getBoolean("reminders_enabled", false)
        set(v) { sp.edit().putBoolean("reminders_enabled", v).apply() }

    /** "statementId:offset" entries already notified, so each reminder fires once. */
    var sentReminders: Set<String>
        get() = sp.getStringSet("sent_reminders", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("sent_reminders", v).apply() }

    // --- app lock
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) { sp.edit().putBoolean("lock_enabled", v).apply() }

    var pinSalt: String?
        get() = sp.getString("pin_salt", null)
        set(v) { sp.edit().putString("pin_salt", v).apply() }

    var pinHash: String?
        get() = sp.getString("pin_hash", null)
        set(v) { sp.edit().putString("pin_hash", v).apply() }

    var biometricEnabled: Boolean
        get() = sp.getBoolean("biometric_enabled", true)
        set(v) { sp.edit().putBoolean("biometric_enabled", v).apply() }

    /** Re-lock after the app has been in the background this long. */
    var lockTimeoutMs: Long
        get() = sp.getLong("lock_timeout_ms", 60_000L)
        set(v) { sp.edit().putLong("lock_timeout_ms", v).apply() }

    // --- look
    /** App colour theme id (see ui/Theme.kt AppThemes). */
    var themeId: String
        get() = sp.getString("theme_id", "neon") ?: "neon"
        set(v) { sp.edit().putString("theme_id", v).apply() }

    // --- alerts (a threshold of 0 = off)
    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts_enabled", false)
        set(v) { sp.edit().putBoolean("alerts_enabled", v).apply() }

    var bigSpendMinor: Long
        get() = sp.getLong("big_spend_minor", 100_000L)
        set(v) { sp.edit().putLong("big_spend_minor", v).apply() }

    var lowAccountBalanceMinor: Long
        get() = sp.getLong("low_account_minor", 200_000L)
        set(v) { sp.edit().putLong("low_account_minor", v).apply() }

    var lowCardAvailableMinor: Long
        get() = sp.getLong("low_card_minor", 100_000L)
        set(v) { sp.edit().putLong("low_card_minor", v).apply() }

    var budgetAlerts: Boolean
        get() = sp.getBoolean("budget_alerts", true)
        set(v) { sp.edit().putBoolean("budget_alerts", v).apply() }

    /** Budget / low-balance alerts already sent ("budget:5:2026-09:80"), so each fires once. */
    var sentAlerts: Set<String>
        get() = sp.getStringSet("sent_alerts", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("sent_alerts", v).apply() }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LIVE = "live_listening"
        const val KEY_SAMSUNG_TIP = "samsung_tip_shown"
    }
}
