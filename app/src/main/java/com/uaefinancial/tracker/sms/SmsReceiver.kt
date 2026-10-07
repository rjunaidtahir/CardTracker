package com.uaefinancial.tracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.data.SmsSource
import com.uaefinancial.tracker.notify.Alerts
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser

/**
 * Live capture. Disabled in the manifest (android:enabled="false") and only switched on
 * by the Settings toggle via LiveListening.setEnabled().
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val now = System.currentTimeMillis()
        parts.filterNotNull()
            .groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
            .forEach { (sender, pieces) ->
                val body = pieces.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                if (SmsParser.bankFor(sender) == null) return@forEach // only your bank sender IDs
                val sentAt = pieces.first().timestampMillis
                // Drop OTPs here so their text never even reaches WorkManager's queue.
                val quick = SmsParser.parse(sender, body, now)
                if (quick is ParseResult.Ignored && !quick.store) return@forEach
                val req = OneTimeWorkRequestBuilder<ProcessSmsWorker>()
                    .setInputData(workDataOf("sender" to sender, "body" to body, "receivedAt" to now, "sentAt" to sentAt))
                    .build()
                WorkManager.getInstance(context).enqueue(req)
            }
    }
}

/** Parses + stores one live SMS off the main thread (same path as Sync). */
class ProcessSmsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val sender = inputData.getString("sender") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val receivedAt = inputData.getLong("receivedAt", System.currentTimeMillis())
        val sentAt = inputData.getLong("sentAt", 0L).takeIf { it > 0 }
        val app = applicationContext as TrackerApp
        app.repo.ingestSms(sender, body, receivedAt, sentAt, SmsSource.LIVE)
        runCatching { Alerts.onNewTransactions(applicationContext, app.repo.drainFresh()) }
        runCatching { app.repo.autoFillCardDays() }
        return Result.success()
    }
}
