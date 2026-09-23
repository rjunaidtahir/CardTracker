package com.junaid.cardtracker.core

import java.security.MessageDigest

/**
 * Unique key per SMS, so a message captured live is skipped by Sync and vice versa:
 *   normalized sender | sent time (seconds) | SHA-256 of the normalized body
 *
 * "Sent time" is the SMS service-centre timestamp. The live receiver gets it from
 * SmsMessage.timestampMillis and the inbox stores the same value in Telephony.Sms.DATE_SENT,
 * so both paths produce the same key. (The inbox DATE column is the phone's receive time,
 * which the receiver can't know exactly, so it isn't used in the key.)
 * When a phone doesn't record DATE_SENT (0), the receive time is used instead, and the
 * repository also runs a fuzzy check (same bank + same text within 10 minutes).
 */
object SmsKey {
    fun normalizeSender(sender: String): String = sender.uppercase().filter { it.isLetterOrDigit() }

    /** Line endings/whitespace differences between the live PDU and the stored inbox copy don't change the hash. */
    fun normalizeBody(body: String): String = body.replace("\r\n", "\n").trim()

    fun bodyHash(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeBody(body).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun of(sender: String, sentAtMillis: Long?, receivedAtMillis: Long, body: String): String {
        val t = if (sentAtMillis != null && sentAtMillis > 0) "s${sentAtMillis / 1000}" else "r${receivedAtMillis / 1000}"
        return "${normalizeSender(sender)}|$t|${bodyHash(body)}"
    }
}
