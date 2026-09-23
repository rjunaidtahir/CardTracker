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

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LIVE = "live_listening"
        const val KEY_SAMSUNG_TIP = "samsung_tip_shown"
    }
}
