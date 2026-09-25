package com.uaefinancial.tracker.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.R
import com.uaefinancial.tracker.core.AlertRules
import com.uaefinancial.tracker.core.Budgets
import com.uaefinancial.tracker.core.InsightTxn
import com.uaefinancial.tracker.core.Insights
import com.uaefinancial.tracker.data.CardTypes
import com.uaefinancial.tracker.data.TransactionEntity
import com.uaefinancial.tracker.parser.TxnType
import com.uaefinancial.tracker.ui.MainActivity
import com.uaefinancial.tracker.ui.fmtMoney
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Spending alerts, checked right after new SMS are stored (Sync or live listening), only while switched on:
 *  - a single purchase at or above your "big spend" amount
 *  - an account balance / card available limit below your threshold (once per card per day)
 *  - a category budget reaching 80% and 100% (once per category per month)
 */
object Alerts {
    const val CHANNEL = "spending_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Spending alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    suspend fun onNewTransactions(context: Context, txns: List<TransactionEntity>) {
        if (txns.isEmpty()) return
        val app = context.applicationContext as TrackerApp
        val prefs = app.prefs
        if (!prefs.alertsEnabled || !DueReminders.canNotify(context)) return
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val dao = app.repo.dao
        val cards = dao.allCards().associateBy { it.cardKey }
        val sent = prefs.sentAlerts.toMutableSet()
        val today = java.time.LocalDate.now(zone).toString()

        for (t in txns.filter { AlertRules.isFresh(it.timestamp, now) }) {
            val card = t.cardKey?.let { cards[it] }
            val cardName = card?.let { it.nickname ?: it.cardKey } ?: t.bank
            val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
            // Big spend: only on cards you track (Show & count on) or typed entries.
            if ((card == null || card.countInSpending) && AlertRules.isBigSpend(type, t.amountAedMinor, prefs.bigSpendMinor)) {
                notify(context, "big:${t.smsId}:${t.timestamp}".hashCode(), "Big spend: ${fmtMoney(t.amountAedMinor ?: 0)}", "${t.merchant} · $cardName")
            }
            val threshold = if (card?.cardType == CardTypes.ACCOUNT) prefs.lowAccountBalanceMinor else prefs.lowCardAvailableMinor
            if (card != null && AlertRules.isLowBalance(t.availableLimitMinor, threshold)) {
                val tag = "low:${card.cardKey}:$today"
                if (tag !in sent) {
                    val what = if (card.cardType == CardTypes.ACCOUNT) "Balance" else "Available limit"
                    notify(context, tag.hashCode(), "$what low on $cardName", "$what is ${fmtMoney(t.availableLimitMinor ?: 0)} after ${t.merchant}")
                    sent += tag
                }
            }
        }

        // Budgets for the current calendar month.
        if (prefs.budgetAlerts) {
            val budgets = dao.allBudgets().associate { it.categoryId to it.monthlyLimitMinor }
            if (budgets.isNotEmpty()) {
                val month = YearMonth.now(zone)
                val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val excluded = cards.values.filterNot { it.countInSpending }.map { it.cardKey }.toSet()
                val monthTxns = dao.txnsSince(from).map {
                    InsightTxn(
                        Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate(),
                        runCatching { TxnType.valueOf(it.type) }.getOrDefault(TxnType.PURCHASE),
                        it.amountAedMinor, it.cardKey, it.categoryId, it.merchant, it.merchantKey ?: "", it.currency, it.amountMinor,
                    )
                }
                val spent = Insights.byCategory(monthTxns, excluded).toMap()
                val names = dao.allCategories().associate { it.id to it.name }
                for (b in Budgets.status(budgets, spent)) {
                    val level = Budgets.threshold(b.percent) ?: continue
                    val tag = "budget:${b.categoryId}:$month:$level"
                    if (tag in sent) continue
                    // Reaching 100% also covers the 80% alert.
                    if (level == 100) sent += "budget:${b.categoryId}:$month:80"
                    val name = names[b.categoryId] ?: "Category"
                    val title = if (level == 100) "$name budget used up" else "$name budget at ${b.percent}%"
                    notify(context, tag.hashCode(), title, "${fmtMoney(b.spentMinor)} of ${fmtMoney(b.limitMinor)} this month")
                    sent += tag
                }
            }
        }
        // Keep the list from growing forever: drop tags older than ~2 months.
        val keepMonths = setOf(YearMonth.now(zone).toString(), YearMonth.now(zone).minusMonths(1).toString())
        prefs.sentAlerts = sent.filter { tag ->
            when {
                tag.startsWith("budget:") -> keepMonths.any { tag.contains(it) }
                tag.startsWith("low:") -> tag.endsWith(today)
                else -> true
            }
        }.toSet()
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
