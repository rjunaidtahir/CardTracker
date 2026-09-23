package com.junaid.cardtracker

import android.app.Application
import androidx.work.WorkManager
import com.junaid.cardtracker.data.AppDatabase
import com.junaid.cardtracker.data.Backup
import com.junaid.cardtracker.data.CardDues
import com.junaid.cardtracker.data.Prefs
import com.junaid.cardtracker.data.Repository
import com.junaid.cardtracker.notify.DueReminders
import com.junaid.cardtracker.sms.LiveListening
import com.junaid.cardtracker.sms.SmsSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CardTrackerApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repo: Repository by lazy { Repository(db) }
    val prefs: Prefs by lazy { Prefs(this) }
    val smsSync: SmsSync by lazy { SmsSync(this, repo, prefs) }
    val cardDues: CardDues by lazy { CardDues(repo.dao) }
    val backup: Backup by lazy { Backup(db, repo) }

    /** For short app-wide background jobs (seeding defaults, widget refresh). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LiveListening.reconcile(this, prefs)
        // Earlier test builds scheduled a background inbox scan; make sure it's gone.
        WorkManager.getInstance(this).cancelUniqueWork("inbox-periodic-sync")
        if (prefs.remindersEnabled) DueReminders.setEnabled(this, true)
        appScope.launch { runCatching { repo.ensureDefaults() } }
    }
}
