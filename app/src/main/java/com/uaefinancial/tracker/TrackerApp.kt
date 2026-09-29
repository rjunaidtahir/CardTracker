package com.uaefinancial.tracker

import android.app.Application
import com.uaefinancial.tracker.data.AppDatabase
import com.uaefinancial.tracker.data.Backup
import com.uaefinancial.tracker.data.CardDues
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.data.Repository
import com.uaefinancial.tracker.notify.DueReminders
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.sms.LiveListening
import com.uaefinancial.tracker.sms.SmsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TrackerApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repo: Repository by lazy { Repository(db, prefs) }
    val prefs: Prefs by lazy { Prefs(this) }
    val smsSync: SmsSync by lazy { SmsSync(this, repo, prefs) }
    val cardDues: CardDues by lazy { CardDues(repo.dao) }
    val backup: Backup by lazy { Backup(db, repo, prefs) }

    /** For short app-wide background jobs (seeding defaults, widget refresh). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.uaefinancial.tracker.ui.AppThemes.current = com.uaefinancial.tracker.ui.AppThemes.byId(prefs.themeId)
        // Senders you added must be known before any SMS is looked at (the receiver can start the app cold).
        SmsParser.setCustomSenders(prefs.senderCache)
        SmsParser.setExcludedBanks(prefs.excludedBanks)
        repo.onSendersChanged = { prefs.senderCache = it }
        LiveListening.reconcile(this, prefs)
        if (prefs.remindersEnabled) DueReminders.setEnabled(this, true)
        appScope.launch {
            runCatching { repo.ensureDefaults() }
            runCatching { repo.loadLearned() }
            // A newer reading engine: read every stored message again once, so Needs review and wrongly read messages
            // benefit from it (and a message now recognised as a one-time code is deleted).
            if (prefs.engineVersion != SmsParser.ENGINE_VERSION) {
                runCatching {
                    repo.reparseAll()
                    prefs.engineVersion = SmsParser.ENGINE_VERSION
                }
                runCatching { repo.autoFillCardDays() }
            }
        }
    }
}
