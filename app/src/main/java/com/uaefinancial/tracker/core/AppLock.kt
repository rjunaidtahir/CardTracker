package com.uaefinancial.tracker.core

import java.security.MessageDigest
import java.security.SecureRandom

object PinHasher {
    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** SHA-256 over salt + PIN, repeated to slow down guessing. */
    fun hash(pin: String, salt: String): String {
        var bytes = (salt + pin).toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        repeat(10_000) { bytes = md.digest(bytes) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, salt: String, expectedHash: String): Boolean = hash(pin, salt) == expectedHash

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }
}

object LockPolicy {
    /** Lock again when the app comes back after being in the background for at least [timeoutMs]. */
    fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long, timeoutMs: Long): Boolean {
        if (!enabled) return false
        if (backgroundedAt == null) return true // cold start
        return now - backgroundedAt >= timeoutMs
    }
}
