package com.junaid.cardtracker.sms

import android.content.Context
import com.junaid.cardtracker.core.IngestOutcome
import com.junaid.cardtracker.core.SyncResult
import com.junaid.cardtracker.data.Prefs
import com.junaid.cardtracker.data.Repository
import com.junaid.cardtracker.data.SmsSource
import com.junaid.cardtracker.notify.Alerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manual Sync: reads the inbox from the last successful sync onward (everything on the first run).
 * A 10-minute overlap re-reads the edge; de-duplication makes that harmless.
 */
class SmsSync(private val context: Context, private val repo: Repository, private val prefs: Prefs) {
    private val mutex = Mutex()

    suspend fun run(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val since = prefs.lastSyncAt?.let { it - OVERLAP_MS } ?: 0L
            val messages = InboxReader.read(context, since)
            val outcomes = messages.map { m ->
                // One unexpected message must not block every future Sync: count it as failed.
                runCatching { repo.ingestSms(m.sender, m.body, m.date, m.dateSent, SmsSource.SYNC) }
                    .getOrElse { IngestOutcome.FAILED }
            }
            prefs.lastSyncAt = startedAt // only reached if everything above succeeded
            runCatching { Alerts.onNewTransactions(context, repo.drainFresh()) }
            SyncResult.of(outcomes)
        }
    }

    private companion object {
        const val OVERLAP_MS = 10 * 60 * 1000L
    }
}
