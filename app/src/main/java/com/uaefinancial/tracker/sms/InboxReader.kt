package com.uaefinancial.tracker.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.uaefinancial.tracker.parser.CandidateScreen
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser

data class InboxSms(val sender: String, val body: String, val date: Long, val dateSent: Long?)

/** A sender on the phone whose messages look like bank alerts but that the app doesn't know yet. */
data class SenderSuggestion(val sender: String, val alerts: Int, val sample: String, val name: String? = null)

/** What a scan of the inbox found: known banks (with message counts) and senders worth adding. */
data class SenderScan(val known: List<Pair<String, Int>>, val suggestions: List<SenderSuggestion>)

/** Any sender found in the inbox (for "choose a bank from my messages"). */
data class InboxSender(val sender: String, val key: String, val count: Int, val sample: String, val last: Long)

object InboxReader {
    /**
     * Every sender in the inbox that is not already one of your banks, newest first (named senders and short codes
     * before phone numbers). Nothing is stored. Used by the manual "choose a bank from my messages" picker.
     */
    fun allSenders(context: Context, limit: Int = 20000): List<InboxSender> {
        if (!hasPermission(context)) throw SecurityException("READ_SMS not granted")
        val count = mutableMapOf<String, Int>()
        val name = mutableMapOf<String, String>()
        val sample = mutableMapOf<String, String>()
        val last = mutableMapOf<String, Long>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            val iA = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iB = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iD = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val addr = c.getString(iA)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (SmsParser.anyBankFor(addr) != null) continue
                val key = SmsParser.senderKey(addr)
                count[key] = (count[key] ?: 0) + 1
                if (key !in name) { name[key] = addr; sample[key] = (c.getString(iB) ?: "").take(160); last[key] = c.getLong(iD) }
            }
        }
        return count.keys.map { k -> InboxSender(name[k]!!, k, count[k]!!, sample[k] ?: "", last[k] ?: 0L) }
            .sortedWith(compareBy<InboxSender> { s -> !(s.sender.any { it.isLetter() } || SmsParser.isShortCode(s.sender)) }.thenByDescending { it.last })
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Inbox SMS from your bank senders received at or after [sinceMillis], oldest first. */
    fun read(context: Context, sinceMillis: Long): List<InboxSms> {
        if (!hasPermission(context)) throw SecurityException("READ_SMS not granted")
        val out = mutableListOf<InboxSms>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iSent = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                val addr = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: ""
                val date = c.getLong(iDate)
                // Your banks, plus any other sender whose message could be financial (kept for review, never posted).
                if (SmsParser.bankFor(addr) == null && CandidateScreen.screen(addr, body, date) == null) continue
                val sent = if (c.isNull(iSent)) null else c.getLong(iSent).takeIf { it > 0 }
                out += InboxSms(addr, body, date, sent)
            }
        }
        return out
    }

    /**
     * Looks through the newest messages in the inbox (up to [limit]) and reports which known banks send you SMS and
     * which other named senders (or short codes) look like banks. Phone numbers are skipped: banks use named sender IDs
     * or short codes.
     * Nothing is stored.
     */
    fun scanSenders(context: Context, limit: Int = 8000): SenderScan {
        if (!hasPermission(context)) throw SecurityException("READ_SMS not granted")
        val known = mutableMapOf<String, Int>()
        val alerts = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            null, null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val addr = c.getString(iAddr)?.trim() ?: continue
                // Banks you unticked are still listed (with the box unticked), so you can tick them again.
                val bank = SmsParser.anyBankFor(addr)
                if (bank != null) {
                    known[bank.name] = (known[bank.name] ?: 0) + 1
                    continue
                }
                // A phone number, not a named sender. Short codes ("24273") are how US, Canadian and UK banks send alerts.
                if (!addr.any { it.isLetter() } && !SmsParser.isShortCode(addr)) continue
                val body = c.getString(iBody) ?: continue
                if (!SmartParser.looksLikeBankAlert(body)) continue
                val key = SmsParser.senderKey(addr) // "AX-HDFCBK-S" and "VM-HDFCBK" are one sender
                names.putIfAbsent(key, addr)
                alerts[key] = (alerts[key] ?: 0) + 1
                samples.putIfAbsent(key, body)
            }
        }
        val country = com.uaefinancial.tracker.data.Prefs(context).homeCountry
        val all = alerts.entries.map { e ->
            val sender = names[e.key] ?: e.key
            val dir = com.uaefinancial.tracker.parser.GlobalBanks.match(sender, samples[e.key], country)
            SenderSuggestion(sender, e.value, samples[e.key] ?: "", dir?.name)
        }
        return SenderScan(
            known = known.entries.sortedByDescending { it.value }.map { it.key to it.value },
            // One odd message isn't enough to call a sender a bank, unless its name is a bank we know.
            suggestions = all.filter { it.alerts >= 2 || it.name != null }
                .sortedWith(compareByDescending<SenderSuggestion> { it.name != null }.thenByDescending { it.alerts }),
        )
    }
}
