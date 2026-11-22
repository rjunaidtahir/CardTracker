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

    /** The on-device second opinion check (internal; no screen shows it). Off unless switched on in code. */
    var aiSecondOpinion: Boolean
        get() = sp.getBoolean("ai_second_opinion", false)
        set(v) { sp.edit { putBoolean("ai_second_opinion", v) } }

    /**
     * Lets the small on-device merchant reader fill in a purchase whose merchant no pattern found (marked "Check this").
     * Internal; no screen shows it. On by default; with it off the app shows the placeholder, as before.
     */
    var aiMerchantFill: Boolean
        get() = sp.getBoolean("ai_merchant_fill", true)
        set(v) { sp.edit { putBoolean("ai_merchant_fill", v) } }

    /** Shop names waiting to be sent (names-only report), as "hash<US>name". Newest last, capped. */
    var merchantQueue: List<String>
        get() = (sp.getString("merchant_queue", "") ?: "").split('\u001e').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("merchant_queue", v.takeLast(300).joinToString("\u001e")) } }

    /** "epochDay:count" of shop names sent today (own cap, so they never crowd out the other reports). */
    var sentMerchantsToday: String
        get() = sp.getString("sent_merchants_today", "") ?: ""
        set(v) { sp.edit { putString("sent_merchants_today", v) } }

    /** Which category keyword set the stored "Other" rows were last re-checked against (see Repository.reapplyAutoCategories). */
    var categoryKeywordsApplied: String
        get() = sp.getString("category_keywords_applied", "") ?: ""
        set(v) { sp.edit { putString("category_keywords_applied", v) } }

    /** Set once the messages hidden as "not a transaction" were checked against the reader (see Repository.queueHiddenForLearning). */
    var hiddenScanDone: String
        get() = sp.getString("hidden_scan_done", "") ?: ""
        set(v) { sp.edit { putString("hidden_scan_done", v) } }

    /** Set once the waiting unknown-sender messages of an older build were closed (22 Nov decision: no unsolicited list). */
    var unknownCleanupDone: Boolean
        get() = sp.getBoolean("unknown_cleanup_done", false)
        set(v) { sp.edit { putBoolean("unknown_cleanup_done", v) } }

    /**
     * Sender IDs whose OLDER inbox messages still have to be read (a sender was just added or newly tracked). The next
     * Sync reads only those senders' messages from the whole inbox, not everyone's.
     */
    var pendingSenderReads: Set<String>
        get() = sp.getStringSet("pending_sender_reads", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("pending_sender_reads", v) } }

    /** Whether you want new bank SMS recorded as they arrive. On unless you turn it off; it only takes effect once SMS permission is granted. */
    var liveListening: Boolean
        get() = sp.getBoolean(KEY_LIVE, true)
        set(v) { sp.edit { putBoolean(KEY_LIVE, v) } }

    /** Bank apps whose notifications are read, as "package<TAB>app name". Empty = notifications are ignored. */
    var notifApps: Set<String>
        get() = sp.getStringSet("notif_apps", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("notif_apps", v) } }

    /** Apps seen posting a notification (name only, never the text), so you can pick your bank apps. Max 80. */
    var seenNotifApps: Set<String>
        get() = sp.getStringSet("seen_notif_apps", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("seen_notif_apps", v) } }

    /** Ids of transactions whose "check this" mark you cleared with "It's correct". */
    var dismissedChecks: Set<String>
        get() = sp.getStringSet("dismissed_checks", emptySet()) ?: emptySet()
        set(v) { sp.edit { putStringSet("dismissed_checks", v) } }

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
        get() = sp.getString("theme_id", "sand") ?: "sand"
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

    // --- home currency and region
    /** Your home currency (ISO code) if chosen or set once; null = not set yet (taken from the phone's region). */
    var homeCurrency: String?
        get() = sp.getString("home_currency", null)
        set(v) { sp.edit { if (v == null) remove("home_currency") else putString("home_currency", v) } }

    /** The country (ISO 3166 alpha-2) whose date style the app reads ("US" writes the month first); null = not set yet. */
    var dateRegion: String?
        get() = sp.getString("date_region", null)
        set(v) { sp.edit { if (v == null) remove("date_region") else putString("date_region", v) } }

    /** Your country (ISO 3166 alpha-2), chosen at first run; used to put your banks first. Null = not chosen. */
    var homeCountry: String?
        get() = sp.getString("home_country", null)
        set(v) { sp.edit { if (v == null) remove("home_country") else putString("home_country", v) } }

    // --- chats that look like banks (sender and a count only; never message text)
    /** "key<US>sender<US>count" entries for unknown senders whose messages look like bank alerts. */
    var candidateCounts: Set<String>
        get() = sp.getStringSet("candidate_counts", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit { putStringSet("candidate_counts", v) } }

    /** Sender keys you said are not banks. */
    var dismissedCandidates: Set<String>
        get() = sp.getStringSet("dismissed_candidates", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit { putStringSet("dismissed_candidates", v) } }

    // --- helping the app learn (shape reports)
    /** 0 = not asked yet, 1 = on, 2 = off. Nothing is ever sent while this is 0 or 2. */
    var shareConsent: Int
        get() = sp.getInt("share_consent", 0)
        set(v) { sp.edit { putInt("share_consent", v) } }

    /** When Needs review last offered sharing to someone who said no (0 = never offered), and whether they chose "Never". */
    var shareNudgeAt: Long
        get() = sp.getLong("share_nudge_at", 0L)
        set(v) { sp.edit { putLong("share_nudge_at", v) } }
    var shareNudgeNever: Boolean
        get() = sp.getBoolean("share_nudge_never", false)
        set(v) { sp.edit { putBoolean("share_nudge_never", v) } }

    /** Hashes of shapes already sent (so the same shape is never sent twice), newest last, capped. */
    var sentShapeHashes: List<String>
        get() = (sp.getString("sent_shapes", "") ?: "").split(',').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("sent_shapes", v.takeLast(2000).joinToString(",")) } }

    /** Shapes sent again because a new rules file did not clear them: "hash:attempts:packVersionOfLastSend", capped. */
    var resendState: List<String>
        get() = (sp.getString("resend_state", "") ?: "").split(',').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("resend_state", v.takeLast(500).joinToString(",")) } }

    /** The last few shapes sent, so More → Settings can show exactly what leaves the phone. */
    var recentShared: List<String>
        get() = (sp.getString("recent_shared", "") ?: "").split('\u001E').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("recent_shared", v.takeLast(5).joinToString("\u001E")) } }

    /** "epochDay:count" of shapes sent today (daily cap). */
    var sentToday: String
        get() = sp.getString("sent_today", "") ?: ""
        set(v) { sp.edit { putString("sent_today", v) } }

    /** Shapes waiting to be sent: "hash<US>bank<US>country<US>kind<US>shape". Held until consent is on. */
    var shapeQueue: List<String>
        get() = (sp.getString("shape_queue", "") ?: "").split('\u001E').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("shape_queue", v.takeLast(200).joinToString("\u001E")) } }

    /**
     * Messages that could not be masked safely (so were never sent), counted per day, bank and reason: "epochDay<US>bank<US>reason<US>count"
     * lines joined by RS. Only the count and the fixed reason ("digits left", "mostly unknown words" ...) are ever reported, never any text.
     */
    var unmaskCounts: List<String>
        get() = (sp.getString("unmask_counts", "") ?: "").split('\u001E').filter { it.isNotEmpty() }
        set(v) { sp.edit { putString("unmask_counts", v.takeLast(200).joinToString("\u001E")) } }

    // --- rules file
    /** Newest version of the signed rules file this phone accepted; 0 = none. */
    var packVersion: Int
        get() = sp.getInt("pack_version", 0)
        set(v) { sp.edit { putInt("pack_version", v) } }

    var lastPackCheck: Long
        get() = sp.getLong("last_pack_check", 0L)
        set(v) { sp.edit { putLong("last_pack_check", v) } }

    /** Messages the app has learned to read from your Fix, shown in More → Settings. */
    var learnedCount: Int
        get() = sp.getInt("learned_count", 0)
        set(v) { sp.edit { putInt("learned_count", v) } }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LIVE = "live_listening"
        const val KEY_BATTERY_TIP = "battery_tip_shown"
        const val SEP = "\u001F"
    }
}
