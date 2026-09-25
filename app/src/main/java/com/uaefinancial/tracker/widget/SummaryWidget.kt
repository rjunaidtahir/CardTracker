package com.uaefinancial.tracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.R
import com.uaefinancial.tracker.core.DueState
import com.uaefinancial.tracker.ui.MainActivity
import com.uaefinancial.tracker.ui.fmtEpochDay
import com.uaefinancial.tracker.ui.fmtMoney
import com.uaefinancial.tracker.ui.spendingTotal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

/** Home-screen widget: this month's spending and the next card payment due. */
class SummaryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                render(context, manager, ids)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Call after data changes (Sync, re-parse, edits). Does nothing if no widget is placed. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SummaryWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch { runCatching { render(context, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext as TrackerApp
            val dao = app.repo.dao
            val zone = ZoneId.systemDefault()
            val month = YearMonth.now(zone)
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val cards = dao.allCards()
            val excluded = cards.filterNot { it.countInSpending }.map { it.cardKey }.toSet()
            val spend = spendingTotal(dao.txnsSince(from).filter { it.timestamp < to }, excluded)
            val next = app.cardDues.currentDues().filter { it.card.countInSpending }
                .filter { (it.status.state == DueState.UNPAID || it.status.state == DueState.MIN_PAID) && it.status.daysLeft >= 0 }
                .minByOrNull { it.status.daysLeft }
            val dueText = next?.let {
                "${it.label}: ${fmtMoney(it.status.remainingMinor, it.statement.currency)} due ${fmtEpochDay(it.statement.dueDateEpochDay)}"
            } ?: "No card payments due"
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_summary).apply {
                setTextViewText(R.id.widget_title, "Spent in ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }}")
                setTextViewText(R.id.widget_amount, fmtMoney(spend))
                setTextViewText(R.id.widget_due, dueText)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
