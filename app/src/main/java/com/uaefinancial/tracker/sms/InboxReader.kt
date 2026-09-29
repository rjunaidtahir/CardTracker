package com.uaefinancial.tracker.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser

data class InboxSms(val sender: String, val body: String, val date: Long, val dateSent: Long?)

/** A sender on the phone whose messages look like bank alerts but that the app doesn't know yet. */
data class SenderSuggestion(val sender: String, val alerts: Int, val sample: String)

/** What a scan of the inbox found: known banks (with message counts) and senders worth adding. */
data class SenderScan(val known: List<Pair<String, Int>>, val suggestions: List<SenderSuggestion>)

object InboxReader {
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
                if (SmsParser.bankFor(addr) == null) continue // only bank senders
                val sent = if (c.isNull(iSent)) null else c.getLong(iSent).takeIf { it > 0 }
                out += InboxSms(addr, c.getString(iBody) ?: "", c.getLong(iDate), sent)
            }
        }
        return out
    }

    /**
     * Looks through the newest messages in the inbox (up to [limit]) and reports which known banks send you SMS and
     * which other named senders look like banks. Phone numbers are skipped: banks use named sender IDs.
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
                if (!addr.any { it.isLetter() }) continue // a phone number, not a named sender
                val body = c.getString(iBody) ?: continue
                if (!SmartParser.looksLikeBankAlert(body)) continue
                val key = SmsParser.normalizeSender(addr)
                names.putIfAbsent(key, addr)
                alerts[key] = (alerts[key] ?: 0) + 1
                samples.putIfAbsent(key, body)
            }
        }
        return SenderScan(
            known = known.entries.sortedByDescending { it.value }.map { it.key to it.value },
            suggestions = alerts.entries
                .filter { it.value >= 2 } // one odd message isn't enough to call a sender a bank
                .sortedByDescending { it.value }
                .map { SenderSuggestion(names[it.key] ?: it.key, it.value, samples[it.key] ?: "") },
        )
    }
}
