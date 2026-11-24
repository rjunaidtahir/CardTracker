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
        // Home currency, date style and time zone, and the senders you added, must be known before any SMS is looked at
        // (the receiver can start the app cold).
        com.uaefinancial.tracker.data.Region.apply(this, prefs)
        SmsParser.setCustomSenders(prefs.senderCache)
        SmsParser.setExcludedBanks(prefs.excludedBanks)
        repo.onSendersChanged = { prefs.senderCache = it }
        repo.onShape = { sender, bank, body, kind, merchant -> com.uaefinancial.tracker.learn.Learning.queue(this, prefs, sender, bank, body, kind, merchant) }
        // 2.8 events with no message (counts only, e.g. event:key-change); sent only when sharing is on.
        repo.onEvent = { kind, dedupe -> runCatching { com.uaefinancial.tracker.learn.Learning.queueReport(this, prefs, kind, kind.substringAfter('|'), dedupe) } }
        repo.onCategory = { sender, bank, body, merchant, type, from, to, all ->
            com.uaefinancial.tracker.learn.Learning.queueCategory(this, prefs, sender, bank, body, merchant, type, from, to, all)
        }
        repo.onOtherMerchant = { merchant, type -> com.uaefinancial.tracker.learn.Learning.queueMerchant(this, prefs, merchant, type) }
        com.uaefinancial.tracker.learn.RulesUpdater.loadSaved(this, prefs)
        // 2.8 spec 18.1: no local copy of shared shapes is kept any more (an older build kept the last five).
        prefs.clearRecentShared()
        LiveListening.reconcile(this, prefs)
        if (prefs.remindersEnabled) DueReminders.setEnabled(this, true)
        appScope.launch { runCatching { com.uaefinancial.tracker.learn.RulesUpdater.checkIfDue(this@TrackerApp, prefs) } }
        appScope.launch {
            runCatching { repo.ensureDefaults() }
            runCatching { repo.adoptTrackedCandidates() }
            runCatching { repo.loadLearned() }
            // A newer reading engine: read every stored message again once, so Needs review and wrongly read messages
            // benefit from it (and a message now recognised as a one-time code is deleted).
            if (prefs.engineVersion != SmsParser.ENGINE_VERSION) {
                runCatching {
                    val r = repo.reparseWithReport()
                    prefs.engineVersion = SmsParser.ENGINE_VERSION
                    // First start of a new build: what the new engine cleared from Needs review (2.8 `cleared`, sharing ON only).
                    runCatching { com.uaefinancial.tracker.learn.Reports.afterReparse(this@TrackerApp, prefs, r) }
                }
                runCatching { repo.autoFillCardDays() }
            }
            // 22 Nov decision: no unsolicited list of unknown senders. Close what an older build kept, once.
            if (!prefs.unknownCleanupDone) {
                runCatching { repo.closeWaitingUnknownSenders() }.onSuccess { prefs.unknownCleanupDone = true }
            }
            // Build the on-device models once, off the main thread, then double-check what the rules read.
            runCatching { com.uaefinancial.tracker.intel.MessageIntel.warmUp() }
            runCatching { repo.updateSecondOpinions() }
            // Only after the re-read above, so a message the new engine can now read is never reported as unread.
            if (com.uaefinancial.tracker.learn.Learning.enabled(prefs)) runCatching { repo.queueUnreadForLearning() }
            // 2.8: a once-a-day count of what the app saw (counts only, sharing ON only).
            runCatching { com.uaefinancial.tracker.learn.Reports.healthIfDue(this@TrackerApp, prefs, repo) }
        }
    }
}
