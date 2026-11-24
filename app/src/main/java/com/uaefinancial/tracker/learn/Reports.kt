package com.uaefinancial.tracker.learn

import android.content.Context
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.data.Repository
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser

/**
 * The 2.8 count and event reports (spec section 6): `cleared`, `event:update-result`, `event:reading-changed`, `event:health`,
 * `event:safe-mode`, `event:ext-rejected`. Counts, versions and public bank names only; never message text, a sender's letters or a
 * phone number. Nothing is sent unless sharing is on, and the whole set is switched off by `ext.levers.reportExtras = false`.
 */
object Reports {
    private const val DAY_MS = 86_400_000L

    /** After a re-read: one `cleared` report per distinct shape, then the once-per-rules-version summary and any reading changes. */
    fun afterReparse(context: Context, prefs: Prefs, r: Repository.ReparseReport) {
        if (!Learning.enabled(prefs)) return
        // One report per distinct shape with how many messages of that shape cleared.
        val groups = r.cleared.groupBy { (m, _) -> Learning.shapeId(m.sender, m.bank, m.body) }
        for ((sid, items) in groups) {
            if (sid == null) continue
            val (m, rule) = items.first()
            runCatching { Learning.queueCleared(context, prefs, m.sender, m.bank, m.body, rule, items.size) }
        }
        val pv = prefs.packVersion
        if (prefs.updateResultSentFor != pv) {
            val queued = Learning.queueReport(
                context, prefs,
                "event:update-result|pv=$pv|ev=${SmsParser.ENGINE_VERSION}|cleared=${r.cleared.size}|still=${r.still}|held=${r.held}|hidden=${r.hidden}|merged=${r.merged}",
                "update-result", dedupe = "update-result|$pv|${SmsParser.ENGINE_VERSION}",
            )
            if (queued) prefs.updateResultSentFor = pv
        }
        if (r.readingChanged > 0) {
            Learning.queueReport(
                context, prefs, "event:reading-changed|pv=$pv|ev=${SmsParser.ENGINE_VERSION}|n=${r.readingChanged}",
                "reading-changed", dedupe = "reading-changed|$pv|${SmsParser.ENGINE_VERSION}",
            )
        }
    }

    /** The rules file switched safe mode on, or its `ext` section was refused (the file itself still applied). Once per rules version. */
    fun packEvents(context: Context, prefs: Prefs, pack: RulesPack.Pack) {
        if (!Learning.enabled(prefs)) return
        if (pack.ext?.safeMode == true) {
            Learning.queueReport(context, prefs, "event:safe-mode|pv=${pack.version}|ev=${SmsParser.ENGINE_VERSION}", "safe-mode", dedupe = "safe-mode|${pack.version}")
        }
        pack.extRejected?.let { why ->
            Learning.queueReport(
                context, prefs, "event:ext-rejected|pv=${pack.version}|ev=${SmsParser.ENGINE_VERSION}|why=${why.take(60)}",
                "ext-rejected", dedupe = "ext-rejected|${pack.version}",
            )
        }
    }

    /** Once a day: counts of what the app saw in the last day (no text, no sender letters; bank names only when the bank is public). */
    suspend fun healthIfDue(context: Context, prefs: Prefs, repo: Repository) {
        if (!Learning.enabled(prefs)) return
        if (!RulesPack.levers.on(RulesPack.Lever.REPORT_EXTRAS)) return
        val now = System.currentTimeMillis()
        val day = now / DAY_MS
        if (prefs.healthDay == day) return
        val h = repo.healthCounts(now - DAY_MS) { sender, bank -> Learning.publicBankName(sender, bank) }
        val byBank = h.byBank.entries.sortedByDescending { it.value }.take(12).joinToString(",") { "${it.key.replace(',', ' ').replace(':', ' ')}:${it.value}" }
        val date = java.time.LocalDate.ofEpochDay(day).toString()
        val queued = Learning.queueReport(
            context, prefs,
            "event:health|day=$date|pv=${prefs.packVersion}|ev=${SmsParser.ENGINE_VERSION}|seen=${h.seen}|confident=${h.confident}|review=${h.review}|held=${h.held}|hidden=${h.hidden}|merged=${h.merged}|byBank=$byBank",
            "health", dedupe = "health|$day",
        )
        if (queued) prefs.healthDay = day
    }
}
