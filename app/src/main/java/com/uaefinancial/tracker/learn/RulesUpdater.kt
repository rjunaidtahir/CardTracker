package com.uaefinancial.tracker.learn

import android.content.Context
import android.util.Base64
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.data.Prefs
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The signed rules file: new banks and reading rules as DATA, downloaded about once a day. The phone checks the file's
 * signature against the developer's public key built into the app, then validates every rule (RulesPack). A file that
 * fails any check changes nothing; the app keeps what it had. Rules only add formats for their own bank after its
 * built-in rules, so they can never override a verified built-in rule. Removing a rule from the file removes it from
 * every phone at its next check.
 */
object RulesUpdater {
    private const val BASE = "https://rjunaidtahir.github.io/CardTracker/"
    /** The developer's public key (ECDSA P-256, X.509). The matching private key never leaves the developer. */
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDhbE+lYKHG5KTFC2MWDPBZY12aa9pRjnGaPGCSQslRwCQFiQcmx9AopZkleuIJLbOUaTqDgzZ7C1Ba1GefiivQ=="
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun dir(context: Context) = File(context.filesDir, "rules").apply { mkdirs() }

    /** True when [sigBase64] is a valid signature of [data] by the developer's key. */
    fun verify(data: ByteArray, sigBase64: String, publicKeyBase64: String = PUBLIC_KEY): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(Base64.decode(sigBase64.trim(), Base64.DEFAULT))
        }
    } catch (e: Exception) { false }

    /** Puts the rules saved from the last good download to work (checking the signature again, in case the file was altered). */
    fun loadSaved(context: Context, prefs: Prefs) {
        runCatching {
            val json = File(dir(context), "pack.json").takeIf { it.exists() } ?: return
            val sig = File(dir(context), "pack.sig").takeIf { it.exists() } ?: return
            val bytes = json.readBytes()
            if (!verify(bytes, sig.readText())) return
            (RulesPack.parse(bytes.toString(Charsets.UTF_8), 0) as? RulesPack.Outcome.Ok)?.let { RulesPack.apply(it.pack) }
        }
    }

    /** Looks for a newer rules file if the last look was over a day ago. Quietly does nothing on any problem. */
    suspend fun checkIfDue(app: TrackerApp, prefs: Prefs) {
        val now = System.currentTimeMillis()
        if (now - prefs.lastPackCheck < DAY_MS) return
        val outcome = runCatching { download(app, prefs) }.getOrNull()
        if (outcome != null) prefs.lastPackCheck = now // a failed download is tried again at the next start
    }

    /** Returns null if the network failed; otherwise whether new rules were accepted. */
    private suspend fun download(app: TrackerApp, prefs: Prefs): Boolean? {
        val data = fetch(BASE + "pack.json", RulesPack.MAX_BYTES) ?: return null
        val sig = fetch(BASE + "pack.sig", 1024)?.toString(Charsets.UTF_8) ?: return null
        if (!verify(data, sig)) return false
        val out = RulesPack.parse(data.toString(Charsets.UTF_8), prefs.packVersion) as? RulesPack.Outcome.Ok ?: return false
        if (out.pack.version == prefs.packVersion && File(dir(app), "pack.json").exists()) return false // nothing new
        File(dir(app), "pack.json.tmp").writeBytes(data)
        File(dir(app), "pack.sig.tmp").writeText(sig)
        File(dir(app), "pack.json.tmp").renameTo(File(dir(app), "pack.json"))
        File(dir(app), "pack.sig.tmp").renameTo(File(dir(app), "pack.sig"))
        val trackedBefore = SmsParser.trackedIds()
        RulesPack.apply(out.pack)
        prefs.packVersion = out.pack.version
        // A sender the rules file now tracks: read what is waiting from it and re-import the inbox on the next Sync,
        // so its older messages come in too (nothing duplicates: every message has a unique key).
        if (SmsParser.trackedIds().any { it !in trackedBefore }) {
            runCatching { app.repo.adoptTrackedCandidates() }
            prefs.lastSyncAt = null
        }
        // Messages that could not be read before may be readable now.
        runCatching { app.repo.reparseAll() }
        // Whatever the new rules still could not read goes to the accuracy check again (the loop continues until Needs review is clear).
        if (com.uaefinancial.tracker.learn.Learning.enabled(prefs)) runCatching {
            // A message the rules file asked for with more words goes out in fuller form; the rest of what is still unread goes out as before.
            val ask = out.pack.sendMore
            for (m in app.repo.unreadMessages()) {
                val kind = if (com.uaefinancial.tracker.learn.Learning.wantsMore(prefs, m.sender, m.bank, m.body, ask)) "unread-more" else "unread-again"
                com.uaefinancial.tracker.learn.Learning.queue(app, prefs, m.sender, m.bank, m.body, kind)
            }
        }
        // New category words in the rules file: re-check what the app itself filed under Other.
        runCatching { app.repo.reapplyAutoCategories() }
        return true
    }

    private fun fetch(url: String, maxBytes: Int): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) null else c.inputStream.use { ins ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > maxBytes) return null // too large: refuse
            }
            buf.toByteArray()
        }
    } catch (e: Exception) { null }
}
