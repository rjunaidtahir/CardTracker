package com.uaefinancial.tracker.data

import android.content.Context
import androidx.core.content.edit

/** Small settings kept in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    /** First-run setup finished (or skipped). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit { putBoolean("onboarded", v) } }

    /**
     * Copy of the bank senders you added (sender -> bank). The database is the source of truth; this copy lets the
     * SMS receiver recognise them straight away, even when the app process was just started for an incoming SMS.
     */
    var senderCache: Map<String, String>
        get() = (sp.getStringSet("sender_cache", emptySet()) ?: emptySet())
            .mapNotNull { e -> e.split(SEP).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        set(v) { sp.edit { putStringSet("sender_cache", v.map { (k, b) -> k + SEP + b }.toSet()) } }

    /** Your name, shown on PDF reports (optional). */
    var displayName: String
        get() = sp.getString("display_name", "") ?: ""
        set(v) { sp.edit { putString("display_name", v.trim()) } }

    /** Start time of the last successful Sync, or null if Sync has never run. */
    var lastSyncAt: Long?
        get() = sp.getLong(KEY_LAST_SYNC, -1L).takeIf { it >= 0 }
        set(v) { sp.edit { putLong(KEY_LAST_SYNC, v ?: -1L) } }

    var liveListening: Boolean
        get() = sp.getBoolean(KEY_LIVE, false)
        set(v) { sp.edit { putBoolean(KEY_LIVE, v) } }

    /** The "keep the app from sleeping" tip was shown once. */
    var batteryTipShown: Boolean
        get() = sp.getBoolean(KEY_BATTERY_TIP, false)
        set(v) { sp.edit { putBoolean(KEY_BATTERY_TIP, v) } }

    // --- due-date reminders (off until you switch them on)
    var remindersEnabled: Boolean
        get() = sp.getBoolean("reminders_enabled", false)
        set(v) { sp.edit { putBoolean("reminders_enabled", v) } }

    /** "statementId:offset" entries already notified, so each reminder fires once. */
    var sentReminders: Set<String>
        get() = sp.getStringSet("sent_reminders", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("sent_reminders", v) } }

    // --- app lock
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) { sp.edit { putBoolean("lock_enabled", v) } }

    var pinSalt: String?
        get() = sp.getString("pin_salt", null)
        set(v) { sp.edit { putString("pin_salt", v) } }

    var pinHash: String?
        get() = sp.getString("pin_hash", null)
        set(v) { sp.edit { putString("pin_hash", v) } }

    var biometricEnabled: Boolean
        get() = sp.getBoolean("biometric_enabled", true)
        set(v) { sp.edit { putBoolean("biometric_enabled", v) } }

    /** Re-lock after the app has been in the background this long. */
    var lockTimeoutMs: Long
        get() = sp.getLong("lock_timeout_ms", 60_000L)
        set(v) { sp.edit { putLong("lock_timeout_ms", v) } }

    // --- look
    /** App colour theme id (see ui/Theme.kt AppThemes). */
    var themeId: String
        get() = sp.getString("theme_id", "neon") ?: "neon"
        set(v) { sp.edit { putString("theme_id", v) } }

    // --- alerts (a threshold of 0 = off)
    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts_enabled", false)
        set(v) { sp.edit { putBoolean("alerts_enabled", v) } }

    var bigSpendMinor: Long
        get() = sp.getLong("big_spend_minor", 100_000L)
        set(v) { sp.edit { putLong("big_spend_minor", v) } }

    var lowAccountBalanceMinor: Long
        get() = sp.getLong("low_account_minor", 200_000L)
        set(v) { sp.edit { putLong("low_account_minor", v) } }

    var lowCardAvailableMinor: Long
        get() = sp.getLong("low_card_minor", 100_000L)
        set(v) { sp.edit { putLong("low_card_minor", v) } }

    var budgetAlerts: Boolean
        get() = sp.getBoolean("budget_alerts", true)
        set(v) { sp.edit { putBoolean("budget_alerts", v) } }

    /** Budget / low-balance alerts already sent ("budget:5:2026-09:80"), so each fires once. */
    var sentAlerts: Set<String>
        get() = sp.getStringSet("sent_alerts", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("sent_alerts", v) } }

    // --- reading messages
    /**
     * Fixes (by SMS dedupKey) you asked the app to apply to similar messages (Needs review → Fix). The fix itself and
     * the message are in the database; this only marks which fixes are templates.
     */
    var learnedFixKeys: Set<String>
        get() = sp.getStringSet("learned_fix_keys", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit { putStringSet("learned_fix_keys", v) } }

    /** Banks you chose not to track (by bank name), e.g. unticked in setup. Needed before any SMS is looked at. */
    var excludedBanks: Set<String>
        get() = sp.getStringSet("excluded_banks", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit { putStringSet("excluded_banks", v) } }

    /** SmsParser.ENGINE_VERSION the stored messages were last read with; 0 = never. */
    var engineVersion: Int
        get() = sp.getInt("engine_version", 0)
        set(v) { sp.edit { putInt("engine_version", v) } }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LIVE = "live_listening"
        const val KEY_BATTERY_TIP = "battery_tip_shown"
        const val SEP = "\u001F"
    }
}
