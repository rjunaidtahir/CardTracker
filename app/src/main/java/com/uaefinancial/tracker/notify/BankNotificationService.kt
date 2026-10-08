package com.uaefinancial.tracker.notify

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.data.SmsSource
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.sms.ProcessSmsWorker

/**
 * Reads notifications from the bank apps you ticked (More → Settings → Bank app notifications), for banks that
 * announce spends only inside their app. Every other app's notification is ignored: only its name is noted, so it
 * can be offered in the list. The text goes through the same reader as an SMS and is never uploaded.
 */
class BankNotificationService : NotificationListenerService() {

    /** On connect, note the apps that already have notifications showing, so the list isn't empty. */
    override fun onListenerConnected() {
        runCatching {
            val prefs = (applicationContext as TrackerApp).prefs
            activeNotifications?.forEach { sbn ->
                val pkg = sbn.packageName ?: return@forEach
                if (pkg != packageName && pkg != "android" && pkg != "com.android.systemui") {
                    NotifApps.noteSeen(prefs, pkg, NotifApps.labelOf(applicationContext, pkg))
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return
        val prefs = (applicationContext as TrackerApp).prefs
        val chosen = prefs.notifApps.firstOrNull { it.substringBefore('\t') == pkg }
        if (chosen == null) {
            NotifApps.noteSeen(prefs, pkg, NotifApps.labelOf(applicationContext, pkg))
            return
        }
        val label = chosen.substringAfter('\t', pkg)
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        val body = if (title.isNotEmpty() && !text.contains(title, ignoreCase = true)) "$title. $text" else text
        val now = System.currentTimeMillis()
        // One-time codes never reach the work queue.
        val quick = SmsParser.parse(label, body, now)
        if (quick is ParseResult.Ignored && !quick.store) return
        val req = OneTimeWorkRequestBuilder<ProcessSmsWorker>()
            .setInputData(workDataOf("sender" to label, "body" to body, "receivedAt" to now, "sentAt" to sbn.postTime, "source" to SmsSource.NOTIF))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(req)
    }
}

/** Which apps' notifications are read. Lists hold "package<TAB>app name". */
object NotifApps {
    fun hasAccess(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun labelOf(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun noteSeen(prefs: Prefs, pkg: String, label: String) {
        val seen = prefs.seenNotifApps
        if (seen.any { it.substringBefore('\t') == pkg }) return
        if (seen.size >= 80) return
        prefs.seenNotifApps = seen + "$pkg\t$label"
    }
}
