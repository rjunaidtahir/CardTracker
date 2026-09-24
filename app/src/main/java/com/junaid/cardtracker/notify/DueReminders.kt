package com.junaid.cardtracker.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.junaid.cardtracker.CardTrackerApp
import com.junaid.cardtracker.R
import com.junaid.cardtracker.core.FixedSchedule
import com.junaid.cardtracker.core.Reminders
import com.junaid.cardtracker.ui.MainActivity
import com.junaid.cardtracker.ui.fmtMoney
import java.util.concurrent.TimeUnit

/**
 * Due-date reminders: a WorkManager job twice a day, only while reminders are switched on in Settings.
 * Notifies 3 days before, 1 day before and on the due day while the minimum isn't paid.
 */
object DueReminders {
    private const val WORK = "due-reminders"
    const val CHANNEL = "due_reminders"

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext as CardTrackerApp
        app.prefs.remindersEnabled = on
        val wm = WorkManager.getInstance(context)
        if (on) {
            ensureChannel(context)
            wm.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DueReminderWorker>(12, TimeUnit.HOURS).build(),
            )
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Card due dates", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

class DueReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CardTrackerApp
        if (!app.prefs.remindersEnabled || !DueReminders.canNotify(applicationContext)) return Result.success()
        val dues = app.cardDues.currentDues()
        val sent = app.prefs.sentReminders.toMutableSet()
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (d in dues) {
            if (!d.card.remindersEnabled || !d.card.countInSpending) continue
            val offset = Reminders.offsetToday(d.status) ?: continue
            val tag = "${d.statement.id}:$offset"
            if (tag in sent) continue
            val text = Reminders.message(d.label, d.status, d.statement.minimumDueMinor) { fmtMoney(it, d.statement.currency) }
            val n = NotificationCompat.Builder(applicationContext, DueReminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_card)
                .setContentTitle("Card payment due")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(d.card.cardKey.hashCode(), n)
                sent += tag
            } catch (_: SecurityException) {
                return Result.success()
            }
        }
        // Fixed payments you added by hand (rent, school fees...): same 3 / 1 / 0 days rhythm until marked paid.
        val today = java.time.LocalDate.now()
        val monthStart = java.time.YearMonth.from(today).atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val monthTxns = app.repo.dao.txnsSince(monthStart).filter { it.source == "SMS" }.map {
            FixedSchedule.MonthTxn(it.cardKey, it.amountMinor, it.categoryId, runCatching { com.junaid.cardtracker.parser.TxnType.valueOf(it.type) }.getOrDefault(com.junaid.cardtracker.parser.TxnType.PURCHASE))
        }
        for (f in app.repo.dao.allFixedPayments()) {
            if (!f.active || !f.remind) continue
            // Already paid this month according to your bank SMS: no reminder.
            if (FixedSchedule.autoPaid(f.amountMinor, f.cardKey, f.categoryId, monthTxns)) continue
            val lastPaid = f.lastPaidYm?.let { runCatching { java.time.YearMonth.parse(it) }.getOrNull() }
            val offset = FixedSchedule.reminderOffsetToday(f.dayOfMonth, today, lastPaid) ?: continue
            val tag = "fp:${f.id}:${java.time.YearMonth.from(today)}:$offset"
            if (tag in sent) continue
            val whenText = when (offset) { 0L -> "today"; 1L -> "tomorrow"; else -> "in $offset days" }
            val text = "${f.name} ${fmtMoney(f.amountMinor)} is due $whenText. Mark it paid in the app once done."
            val n = NotificationCompat.Builder(applicationContext, DueReminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_card)
                .setContentTitle("Fixed payment due")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(tag.hashCode(), n)
                sent += tag
            } catch (_: SecurityException) {
                break
            }
        }
        // Keep only this and last month's tags.
        val keep = setOf(java.time.YearMonth.now().toString(), java.time.YearMonth.now().minusMonths(1).toString())
        app.prefs.sentReminders = sent.filter { !it.startsWith("fp:") || keep.any { m -> it.contains(m) } }.toSet()
        return Result.success()
    }
}
