package com.uaefinancial.tracker.sms

import android.content.Context
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.core.SyncResult
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.data.Repository
import com.uaefinancial.tracker.data.SmsSource
import com.uaefinancial.tracker.notify.Alerts
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

    /** (done, total) while a Sync is running, else null. */
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)

    suspend fun run(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val since = prefs.lastSyncAt?.let { it - OVERLAP_MS } ?: 0L
            val messages = InboxReader.read(context, since)
            val total = messages.size
            progress.value = 0 to total
            val outcomes = ArrayList<IngestOutcome>(total)
            try {
                // Saved in batches of 100 (one database write each) and waiting standing-instruction messages are
                // settled once at the end: much faster than one write per message on a big inbox.
                for (chunk in messages.chunked(BATCH)) {
                    repo.inBatch {
                        for (m in chunk) {
                            // One unexpected message must not block every future Sync: count it as failed.
                            outcomes += runCatching { repo.ingestSms(m.sender, m.body, m.date, m.dateSent, SmsSource.SYNC, deferResolve = true) }
                                .getOrElse { IngestOutcome.FAILED }
                        }
                    }
                    progress.value = outcomes.size to total
                }
                runCatching { repo.finishBatch() }
            } finally { progress.value = null }
            prefs.lastSyncAt = startedAt // only reached if everything above succeeded
            runCatching { Alerts.onNewTransactions(context, repo.drainFresh()) }
            runCatching { repo.autoFillCardDays() }
            SyncResult.of(outcomes)
        }
    }

    private companion object {
        const val OVERLAP_MS = 10 * 60 * 1000L
        const val BATCH = 100
    }
}
