package com.uaefinancial.tracker.learn

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.data.Region
import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.GlobalBanks
import com.uaefinancial.tracker.parser.Masker
import com.uaefinancial.tracker.parser.SmsParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Helping the app learn new bank formats. With your permission (asked once, at first run; on by default after that,
 * and a switch in More → Settings) the app can send the SHAPE of a message it could not read, or that you fixed:
 *
 *   "Your {TEXT} card ending {CARD} was used for {CUR} {AMOUNT} at {TEXT} on {DATE}"
 *
 * Amounts, names, merchants, card and account numbers, dates and links never leave the phone (see Masker). A shape that
 * still holds anything personal is never sent. Nothing is sent while the switch is off or before you have been asked.
 */
object Learning {
    /** Where shapes go: a form owned by the developer. It can only receive text; it cannot send anything back. */
    private const val FORM_POST = "https://docs.google.com/forms/d/e/1FAIpQLSfHo5rczeA1uCMOEaEGoboDcfudCC2RMyrlWZRhFRe2JPsKtw/formResponse"
    private const val F_SHAPE = "entry.711822273"
    private const val F_BANK = "entry.1277016209"
    private const val F_COUNTRY = "entry.214990827"
    private const val F_KIND = "entry.69027038"
    private const val F_APP = "entry.1715899320"

    const val DAILY_CAP = 10
    private const val US = '\u001F'

    const val ASKED = 0
    const val ON = 1
    const val OFF = 2

    fun enabled(prefs: Prefs) = prefs.shareConsent == ON

    /** The bank name as the public knows it, never something you typed (a custom name could be personal). */
    fun publicBankName(sender: String, bankName: String?): String? {
        bankName?.let { n -> BankRules.banks.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it.name } }
        GlobalBanks.match(sender)?.let { return it.name }
        return bankName?.let { n -> GlobalBanks.all().firstOrNull { it.name.equals(n, ignoreCase = true) }?.name }
    }

    /** What would be sent for [body], for the preview. Null when it would not be sent. */
    fun previewShape(sender: String, bankName: String?, body: String): String? {
        val r = Masker.mask(body, publicBankName(sender, bankName))
        return r.shape.takeIf { r.safe }
    }

    /** Masks [body] and queues its shape. Returns true when something was queued. Does nothing unless sharing is on. */
    fun queue(context: Context, prefs: Prefs, sender: String, bankName: String?, body: String, kind: String): Boolean {
        if (!enabled(prefs)) return false
        val bank = publicBankName(sender, bankName)
        val r = Masker.mask(body, bank)
        if (!r.safe) return false
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        val hash = hash("${bank.orEmpty()}|${r.shape}")
        if (hash in prefs.sentShapeHashes || prefs.shapeQueue.any { it.startsWith("$hash$US") }) return false
        prefs.shapeQueue = prefs.shapeQueue + listOf(listOf(hash, bank ?: "Other", country, kind, r.shape).joinToString(US.toString()))
        schedule(context)
        return true
    }

    fun schedule(context: Context) {
        val req = OneTimeWorkRequestBuilder<ShapeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("share-shapes", ExistingWorkPolicy.KEEP, req)
    }

    /** Sends what is queued, within the daily cap. Returns false when it should be retried later. */
    fun flush(context: Context, prefs: Prefs): Boolean {
        if (!enabled(prefs)) { prefs.shapeQueue = emptyList(); return true }
        val today = System.currentTimeMillis() / 86_400_000L
        val (day, n) = prefs.sentToday.split(':').let { (it.getOrNull(0)?.toLongOrNull() ?: -1L) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        var sentCount = if (day == today) n else 0
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
        var queue = prefs.shapeQueue
        var ok = true
        for (item in queue.toList()) {
            if (sentCount >= DAILY_CAP) break
            val p = item.split(US)
            if (p.size < 5) { queue = queue - item; continue }
            if (post(p[4], p[1], p[2], p[3], version)) {
                sentCount++
                queue = queue - item
                prefs.sentShapeHashes = prefs.sentShapeHashes + p[0]
                prefs.recentShared = (prefs.recentShared + p[4]).takeLast(5)
            } else { ok = false; break }
        }
        prefs.shapeQueue = queue
        prefs.sentToday = "$today:$sentCount"
        return ok
    }

    private fun post(shape: String, bank: String, country: String, kind: String, version: String): Boolean = try {
        val body = listOf(F_SHAPE to shape, F_BANK to bank, F_COUNTRY to country, F_KIND to kind, F_APP to version)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val c = URL(FORM_POST).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        code in 200..399
    } catch (e: Exception) { false }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

class ShapeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = (applicationContext as TrackerApp).prefs
        return if (Learning.flush(applicationContext, prefs)) Result.success() else Result.retry()
    }
}
