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

    const val DAILY_CAP = 300
    /** A message still unread after this many new rules files is not sent again (it stays in Needs review for you). */
    const val MAX_RESENDS = 3
    /** Shop names are sent under their own daily cap. */
    const val MERCHANT_CAP = 40
    private const val MERCHANT_KIND = "merchant-other"
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

    private val shortCode = Regex("""[0-9]{3,6}""")
    private val senderId = Regex("""[A-Za-z][A-Za-z0-9._&-]{2,39}""")

    /** The sender ID as a bank prints it (letters, digits, - . & _), or null for a phone number, a contact name or anything odd. */
    fun publicSender(sender: String): String? {
        val s = sender.trim()
        // Banks in Pakistan, India and elsewhere often text from a short numeric code (3-6 digits, e.g. "8258"). Every
        // customer sees it and it is not a phone number, so it is as public as "HBL". Longer digit strings stay out.
        if (shortCode.matches(s)) return s
        if (!senderId.matches(s)) return null
        if (s.count { it.isDigit() } > s.length / 2) return null
        return s.uppercase()
    }

    /**
     * Why a message could not be read, worked out from its masked shape only (so it is as private as the shape).
     * Lets the daily accuracy check tell "no amount in it" from "has an amount but the wording is new".
     */
    fun reason(shape: String): String = when {
        !shape.contains("{AMOUNT}") -> "no-amount"
        !shape.contains("{CUR}") -> "no-currency"
        !shape.contains("{CARD") -> "no-account"
        else -> "wording"
    }

    /** True when a rules file asks for this still-unread message again with more words (its shape hash is listed, or its bank is). */
    fun wantsMore(prefs: Prefs, sender: String, bankName: String?, body: String, req: com.uaefinancial.tracker.parser.RulesPack.SendMore): Boolean {
        if (req.hashes.isEmpty() && req.banks.isEmpty()) return false
        val bank = publicBankName(sender, bankName)
        if (bank != null && bank.lowercase() in req.banks) return true
        val r = Masker.mask(body, bank, null, loose = bank != null || Masker.looksLikeBankMessage(body))
        val label = bank ?: publicSender(sender)?.let { "Other ($it)" } ?: "Other"
        return hash("$label|${r.shape}") in req.hashes
    }

    /**
     * True when a message is still unread after [MAX_RESENDS] - 1 resends and has not yet been asked for in fuller form
     * twice: then it goes out with more words by itself, without waiting for a rules file to ask (the accuracy loop must
     * not depend on a manual step). Records the fuller send so it happens at most twice per message shape.
     */
    fun takeAutoMore(prefs: Prefs, sender: String, bankName: String?, body: String): Boolean {
        val bank = publicBankName(sender, bankName)
        val r = Masker.mask(body, bank, null, loose = bank != null || Masker.looksLikeBankMessage(body))
        val label = bank ?: publicSender(sender)?.let { "Other ($it)" } ?: "Other"
        val hash = hash("$label|${r.shape}")
        val st = prefs.resendState
        val n = st.firstOrNull { it.startsWith("$hash:") }?.split(':')?.getOrNull(1)?.toIntOrNull() ?: 0
        if (n < MAX_RESENDS - 1) return false
        val key = "M$hash:"
        val done = st.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.toIntOrNull() ?: 0
        if (done >= 2) return false
        prefs.resendState = st.filterNot { it.startsWith(key) } + (key + (done + 1))
        return true
    }

    /** What would be sent for [body], for the preview. Null when it would not be sent. */
    fun previewShape(sender: String, bankName: String?, body: String): String? {
        val b = publicBankName(sender, bankName)
        val r = Masker.mask(body, b, loose = b != null || Masker.looksLikeBankMessage(body))
        return r.shape.takeIf { r.safe }
    }

    /** Masks [body] and queues its shape. Returns true when something was queued. Does nothing unless sharing is on. */
    fun queue(context: Context, prefs: Prefs, sender: String, bankName: String?, body: String, rawKind: String, merchant: String? = null): Boolean {
        if (!enabled(prefs)) return false
        // "unread-again": a message a new rules file still could not read. It may be sent once more per new rules file (at most MAX_RESENDS times).
        val again = rawKind == "unread-again"
        // "unread-more": a rules file asked for this message again with more words kept (see Masker's fuller mode).
        val more = rawKind == "unread-more"
        val kind = if (again || more) "unread" else rawKind
        val bank = publicBankName(sender, bankName)
        // A recognised bank's message, or any message that reads like a bank alert (a bank we have not listed), keeps every word except names (loose);
        // anything else (a private chat picked by hand) stays strict.
        val r = Masker.mask(body, bank, merchant, loose = more || bank != null || Masker.looksLikeBankMessage(body), fuller = more)
        if (!r.safe) {
            // Never sent, but counted (bank + fixed reason only, no text), so the review knows how much it cannot see.
            countUnmasked(prefs, bank ?: publicSender(sender)?.let { "Other ($it)" } ?: "Other", r.reason ?: "unsafe")
            return false
        }
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        // A bank we do not know yet: send its sender ID as the bank's own name for its messages ("HBL", "AX-HDFCBK-S").
        // That is public (every customer sees it); a phone number or a contact name is never sent.
        val label = bank ?: publicSender(sender)?.let { "Other ($it)" } ?: "Other"
        var sentKind = if (more) "unread-more/" + reason(r.shape) else if (kind == "unread") "unread/" + reason(r.shape) else kind
        val hash = hash("$label|${r.shape}" + if (kind.startsWith("choice:") || kind.startsWith("held:") || kind.startsWith("softchange:")) "|$kind" else if (more) "|more${prefs.packVersion}" else "")
        if (prefs.shapeQueue.any { it.startsWith("$hash$US") }) return false
        if (hash in prefs.sentShapeHashes) {
            if (!again) return false
            val st = prefs.resendState
            val old = st.firstOrNull { it.startsWith("$hash:") }?.split(':')
            val n = old?.getOrNull(1)?.toIntOrNull() ?: 0
            if (n >= MAX_RESENDS || old?.getOrNull(2)?.toIntOrNull() == prefs.packVersion) return false
            prefs.resendState = st.filterNot { it.startsWith("$hash:") } + "$hash:${n + 1}:${prefs.packVersion}"
            sentKind = "unread-again/${n + 1}/" + reason(r.shape)
        }
        prefs.shapeQueue = prefs.shapeQueue + listOf(listOf(hash, label, country, sentKind, r.shape).joinToString(US.toString()))
        schedule(context)
        return true
    }

    private fun countUnmasked(prefs: Prefs, label: String, reason: String) {
        val today = (System.currentTimeMillis() / 86_400_000L).toString()
        val key = "$today$US$label$US$reason$US"
        val all = prefs.unmaskCounts.toMutableList()
        val i = all.indexOfFirst { it.startsWith(key) }
        if (i >= 0) all[i] = key + ((all[i].removePrefix(key).toIntOrNull() ?: 0) + 1) else all += key + "1"
        prefs.unmaskCounts = all
    }

    /** Turns the counts of earlier days into reports (one per bank and reason), so each count is complete. */
    private fun queueUnmaskedCounts(context: Context, prefs: Prefs) {
        val today = System.currentTimeMillis() / 86_400_000L
        val keep = mutableListOf<String>()
        var queue = prefs.shapeQueue
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        for (line in prefs.unmaskCounts) {
            val p = line.split(US)
            val day = p.getOrNull(0)?.toLongOrNull()
            if (p.size < 4 || day == null) continue
            if (day >= today) { keep += line; continue }
            val shape = "count=${p[3]} | reason=${p[2]} | day=$day"
            queue = queue + listOf(listOf(hash("unmasked|$day|${p[1]}|${p[2]}"), p[1], country, "unmasked", shape).joinToString(US.toString()))
        }
        prefs.shapeQueue = queue
        prefs.unmaskCounts = keep
    }

    /**
     * A person changed a transaction's category. Sends only what [com.uaefinancial.tracker.core.CategoryReport] allows: the
     * old and new built-in category, the type, and the masked wording with the merchant's place marked (never its name).
     * Needs sharing ON.
     */
    fun queueCategory(
        context: Context, prefs: Prefs, sender: String?, bankName: String?, body: String?, merchant: String,
        type: com.uaefinancial.tracker.parser.TxnType, fromId: Long?, toId: Long, applyToMerchant: Boolean,
    ): Boolean {
        if (!enabled(prefs)) return false
        val fields = com.uaefinancial.tracker.core.CategoryReport.fields(type, fromId, toId, applyToMerchant) ?: return false
        val bank = publicBankName(sender ?: "", bankName)
        val shape = body?.let { Masker.mask(it, bank, merchant).takeIf { r -> r.safe }?.shape } ?: "none"
        val label = bank ?: sender?.let { publicSender(it) }?.let { "Other ($it)" } ?: "Other"
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        val text = "$fields | shape=$shape"
        val hash = hash("category|$label|$text")
        if (hash in prefs.sentShapeHashes || prefs.shapeQueue.any { it.startsWith("$hash$US") }) return false
        prefs.shapeQueue = prefs.shapeQueue + listOf(listOf(hash, label, country, "category", text).joinToString(US.toString()))
        schedule(context)
        return true
    }

    /**
     * Names-only report: a shop name filed under Other. Nothing else is attached (no amount, date, card, bank or message).
     * Sharing must be on. Each name is queued once per phone.
     */
    fun queueMerchant(context: Context, prefs: Prefs, merchant: String, type: com.uaefinancial.tracker.parser.TxnType): Boolean {
        if (!enabled(prefs)) return false
        val name = com.uaefinancial.tracker.core.MerchantReport.clean(merchant, type) ?: return false
        val h = hash("merchant|$name")
        if (h in prefs.sentShapeHashes || prefs.merchantQueue.any { it.startsWith("$h$US") }) return false
        prefs.merchantQueue = prefs.merchantQueue + (h + US + name)
        schedule(context)
        return true
    }

    /**
     * One kind of thing a person did, kept apart in the sheet so the weekly review sees the full picture. The Kind column
     * holds "event:<name>"; the Shape column holds "key=value" pairs joined by " | ". Only public things are ever sent:
     * a sender ID as the bank prints it (never a phone number or contact name), a public bank name, a masked shape.
     *
     *   event:bank-added      sender not recognised -> you said it is a bank. sender=TABBY | chosen=Tabby | via=candidate
     *                         (via = setup, picker, scan, candidate, manual) | sample=<masked shape or none>
     *   event:sender-declined a suggested/unknown sender you left unticked or hid.  sender=ZAIN | via=setup|hide
     *   event:not-financial   a kept message you marked "not financial".  sender=NOON | sample=<masked shape>
     *
     * (Fixed transactions and unread messages keep their own kinds: "fixed:<TYPE>" and "unread/<reason>".)
     */
    fun queueEvent(context: Context, prefs: Prefs, event: String, sender: String, bankName: String?, body: String?, via: String): Boolean {
        if (!enabled(prefs)) return false
        val id = publicSender(sender) ?: return false
        val bank = publicBankName(sender, bankName)
        val sample = body?.let { b -> Masker.mask(b, bank).takeIf { it.safe }?.shape }
        val fields = buildList {
            add("sender=$id")
            if (event == "bank-added") add("chosen=" + (bank ?: "unknown"))
            add("via=$via")
            add("sample=" + (sample ?: "none"))
        }
        val shape = fields.joinToString(" | ")
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        val label = bank ?: "Other ($id)"
        val hash = hash("event|$event|$id|${bank ?: ""}|${sample ?: ""}")
        if (hash in prefs.sentShapeHashes || prefs.shapeQueue.any { it.startsWith("$hash$US") }) return false
        prefs.shapeQueue = prefs.shapeQueue + listOf(listOf(hash, label, country, "event:$event", shape).joinToString(US.toString()))
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
        val appVersion = "$version/e${SmsParser.ENGINE_VERSION}"
        queueUnmaskedCounts(context, prefs)
        var queue = prefs.shapeQueue
        var ok = true
        for (item in queue.toList()) {
            if (sentCount >= DAILY_CAP) break
            val p = item.split(US)
            if (p.size < 5) { queue = queue - item; continue }
            if (post(p[4], p[1], p[2], p[3], appVersion)) {
                sentCount++
                queue = queue - item
                prefs.sentShapeHashes = prefs.sentShapeHashes + p[0]
                prefs.recentShared = (prefs.recentShared + p[4]).takeLast(5)
            } else { ok = false; break }
        }
        prefs.shapeQueue = queue
        prefs.sentToday = "$today:$sentCount"
        if (ok) ok = flushMerchants(context, prefs, today, appVersion)
        return ok
    }

    private fun flushMerchants(context: Context, prefs: Prefs, today: Long, appVersion: String): Boolean {
        val parts = prefs.sentMerchantsToday.split(':')
        var sent = if (parts.getOrNull(0)?.toLongOrNull() == today) parts.getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val country = prefs.homeCountry ?: Region.detect(context) ?: ""
        var queue = prefs.merchantQueue
        var ok = true
        for (item in queue.toList()) {
            if (sent >= MERCHANT_CAP) break
            val p = item.split(US, limit = 2)
            if (p.size < 2) { queue = queue - item; continue }
            if (post(p[1], "merchant", country, MERCHANT_KIND, appVersion)) {
                sent++
                queue = queue - item
                prefs.sentShapeHashes = prefs.sentShapeHashes + p[0]
            } else { ok = false; break }
        }
        prefs.merchantQueue = queue
        prefs.sentMerchantsToday = "$today:$sent"
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
