package com.junaid.cardtracker

import android.app.Application
import androidx.work.WorkManager
import com.junaid.cardtracker.data.AppDatabase
import com.junaid.cardtracker.data.Prefs
import com.junaid.cardtracker.data.Repository
import com.junaid.cardtracker.sms.LiveListening
import com.junaid.cardtracker.sms.SmsSync

class CardTrackerApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repo: Repository by lazy { Repository(db) }
    val prefs: Prefs by lazy { Prefs(this) }
    val smsSync: SmsSync by lazy { SmsSync(this, repo, prefs) }

    override fun onCreate() {
        super.onCreate()
        LiveListening.reconcile(this, prefs)
        // Earlier test builds scheduled a background inbox scan; make sure it's gone.
        WorkManager.getInstance(this).cancelUniqueWork("inbox-periodic-sync")
    }
}
