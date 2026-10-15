package com.uaefinancial.tracker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.data.GoalEntity
import com.uaefinancial.tracker.data.SmsSource
import com.uaefinancial.tracker.ui.AppThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Debug only. `adb shell am broadcast -n <pkg>/com.uaefinancial.tracker.debug.DemoSeed --es theme midnight --ez seed true`
 * Skips onboarding, picks a theme, and (once) feeds made-up bank SMS through the real reading pipeline.
 */
class DemoSeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TrackerApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                intent.getStringExtra("theme")?.let { app.prefs.themeId = it; AppThemes.current = AppThemes.byId(it) }
                app.prefs.onboarded = true; app.prefs.shareConsent = 2
                app.prefs.homeCountry = "AE"; app.prefs.homeCurrency = "AED"; app.prefs.dateRegion = "AE"
                com.uaefinancial.tracker.data.Region.apply(app, app.prefs)
                app.repo.ensureDefaults()
                if (intent.getBooleanExtra("seed", false)) seed(app)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(app: TrackerApp) {
        val repo = app.repo
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rnd = Random(7)
        val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH)

        data class M(val name: String, val lo: Int, val hi: Int, val w: Int)
        val shops = listOf(
            M("CARREFOUR CITY CENTRE", 45, 380, 9), M("LULU HYPERMARKET", 60, 420, 7), M("SPINNEYS", 30, 190, 5),
            M("STARBUCKS", 18, 46, 10), M("TALABAT.COM", 35, 140, 9), M("MCDONALDS", 22, 58, 4), M("SHAKE SHACK", 55, 120, 3),
            M("ADNOC DISTRIBUTION", 90, 210, 6), M("UBER TRIP", 15, 70, 7), M("SALIK", 4, 24, 4),
            M("DEWA", 180, 560, 1), M("DU TELECOM", 140, 330, 1),
            M("AMAZON.AE", 40, 520, 6), M("NOON.COM", 35, 400, 5), M("H&M", 90, 420, 2), M("IKEA", 120, 900, 1),
            M("NETFLIX.COM", 56, 56, 1), M("VOX CINEMAS", 70, 140, 2), M("LIFE PHARMACY", 25, 160, 3), M("MEDICLINIC", 120, 460, 1),
            M("EMIRATES AIRLINE", 900, 2400, 1),
        )
        val bag = shops.flatMap { m -> List(m.w) { m } }

        for (day in 340 downTo 0) {
            val d = today.minusDays(day.toLong())
            val dow = d.dayOfWeek.value
            val n = when {
                day > 120 -> rnd.nextInt(0, 2)
                dow >= 5 -> rnd.nextInt(1, 4)
                else -> rnd.nextInt(0, 3)
            }
            repeat(n) {
                val m = bag[rnd.nextInt(bag.size)]
                val amt = m.lo + rnd.nextInt(0, (m.hi - m.lo + 1).coerceAtLeast(1)) + rnd.nextInt(0, 100) / 100.0
                val at = d.atTime(8 + rnd.nextInt(0, 14), rnd.nextInt(0, 60), rnd.nextInt(0, 60)).atZone(zone)
                val ms = at.toInstant().toEpochMilli()
                val body = when (rnd.nextInt(0, 3)) {
                    0 -> "Purchase of AED ${"%.2f".format(Locale.ENGLISH, amt)} with Credit Card ending 3944 at ${m.name}, DUBAI."
                    1 -> "Credit Card XX3538 was used for AED${"%.2f".format(Locale.ENGLISH, amt)} on ${at.format(dmy)} at ${m.name}, DUBAI-AE"
                    else -> "Purchase of AED ${"%.2f".format(Locale.ENGLISH, amt)} with Credit Card ending 3944 at ${m.name}, DUBAI."
                }
                val sender = if (body.startsWith("Credit Card XX")) "ADCBAlert" else "EmiratesNBD"
                repo.ingestSms(sender, body, ms, ms, SmsSource.SYNC)
            }
        }
        // Salary into the account each month.
        for (mo in 11 downTo 0) {
            val d = today.minusMonths(mo.toLong()).withDayOfMonth(1).plusDays(24)
            if (d.isAfter(today)) continue
            val ms = d.atTime(9, 12).atZone(zone).toInstant().toEpochMilli()
            repo.ingestSms("FAB", "Salary Credit\nAccount XXXX8001\nAED 18500.00\n${d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}\nAvailable Balance AED 31240.55", ms, ms, SmsSource.SYNC)
        }
        // A statement with a due date soon, so Payments due has something to show.
        val due = today.plusDays(6)
        val bill = "Cr.Card XXX3538 Billing alert: Total due to avoid fin. charges: AED2463.92. Due date ${due.format(DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH))}; Pay min. AED100.00 by due date to avoid AED241.50 late fees."
        val nowMs = System.currentTimeMillis()
        repo.ingestSms("ADCBAlert", bill, nowMs - 3_600_000, nowMs - 3_600_000, SmsSource.SYNC)

        // Budgets for the three biggest everyday categories, and two goals.
        val cats = repo.dao.allCategories().associateBy { it.name }
        val limits = mutableMapOf<Long, Long?>()
        cats["Groceries"]?.let { limits[it.id] = 1_200_00L }
        cats["Dining"]?.let { limits[it.id] = 900_00L }
        cats["Shopping"]?.let { limits[it.id] = 1_500_00L }
        cats["Transport"]?.let { limits[it.id] = 700_00L }
        repo.setBudgets(limits)
        repo.saveGoal(GoalEntity(name = "Holiday fund", targetMinor = 12_000_00L, savedMinor = 7_450_00L, targetDateEpochDay = today.plusDays(120).toEpochDay(), createdAt = nowMs))
        repo.saveGoal(GoalEntity(name = "New laptop", targetMinor = 6_500_00L, savedMinor = 2_100_00L, targetDateEpochDay = today.plusDays(200).toEpochDay(), createdAt = nowMs))
    }
}
