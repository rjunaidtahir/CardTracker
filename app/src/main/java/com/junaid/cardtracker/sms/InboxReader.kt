package com.junaid.cardtracker.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.junaid.cardtracker.parser.SmsParser

data class InboxSms(val sender: String, val body: String, val date: Long, val dateSent: Long?)

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
                if (SmsParser.bankFor(addr) == null) continue // only your bank sender IDs
                val sent = if (c.isNull(iSent)) null else c.getLong(iSent).takeIf { it > 0 }
                out += InboxSms(addr, c.getString(iBody) ?: "", c.getLong(iDate), sent)
            }
        }
        return out
    }
}
