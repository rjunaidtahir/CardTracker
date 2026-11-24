package com.uaefinancial.tracker.feedback

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Share feedback" (2.8 spec 18.3). Pure logic so it is tested without a phone: which fields are sent, the risky-digit block, the
 * daily limit and the result of one send. Sent: the category (exact form text, or nothing), the message, "anonymous" or "email",
 * the email only in email mode, and the app version name + engine version. Never a device id, phone number, account data,
 * messages, card data, location or logs. The user presses Send; there is no background retry.
 */
object Feedback {
    /** The exact texts the form's Category dropdown accepts (sent unchanged). */
    val CATEGORIES = listOf("Something is wrong with a message", "Idea or request", "Something else")
    const val MAX_MESSAGE = 2_000
    const val DAILY_LIMIT = 3

    enum class Identity { ANONYMOUS, EMAIL }

    data class Input(val category: String?, val message: String, val identity: Identity = Identity.ANONYMOUS, val email: String = "")

    sealed interface Result {
        data object Sent : Result
        data object Empty : Result
        data object RiskyDigits : Result
        data object BadEmail : Result
        data object LimitReached : Result
        data object Failed : Result
    }

    /** Send is allowed with at least one non-space character in the message OR a category chosen. */
    fun canSend(category: String?, message: String): Boolean = message.isNotBlank() || category != null

    private val longRun = Regex("""\d(?:[ \-.]?\d){7,}""")
    private val otp = Regex("""(?i)\b(?:otp|one[- ]time (?:password|code)|passcode)\b.{0,30}\d{3,}|\d{3,}.{0,30}\b(?:otp|one[- ]time (?:password|code)|passcode)\b""")

    /** A run of 8 or more digits (spaces or dashes between them count as one run: card-like and phone-like numbers), or OTP with digits. */
    fun hasRiskyDigits(text: String): Boolean = longRun.containsMatchIn(text) || otp.containsMatchIn(text)

    private val emailFormat = Regex("""[^@\s]+@[^@\s]+\.[^@\s]{2,}""")
    fun emailLooksValid(email: String): Boolean = emailFormat.matches(email.trim())

    /** The form fields for one send; email mode with an empty email box is sent as anonymous, and anonymous never carries an email. */
    fun fields(input: Input, app: String): List<Pair<String, String>> {
        val email = input.email.trim().takeIf { input.identity == Identity.EMAIL && it.isNotEmpty() }
        return buildList {
            input.category?.takeIf { it in CATEGORIES }?.let { add(FeedbackConfig.F_CATEGORY to it) }
            add(FeedbackConfig.F_MESSAGE to input.message.trim().take(MAX_MESSAGE))
            add(FeedbackConfig.F_IDENTITY to if (email != null) "email" else "anonymous")
            if (email != null) add(FeedbackConfig.F_EMAIL to email)
            add(FeedbackConfig.F_APP to app)
        }
    }

    /** "2.8 engine 8": the only thing sent about the app (version name without a build suffix, and the reading engine). */
    fun appLine(versionName: String, engine: Int): String = "${versionName.substringBefore('-').trim()} engine $engine"

    /** Sends counted per day ("epochDay:count"). */
    interface DayCounter { fun get(): String; fun set(v: String) }

    fun sentToday(counter: DayCounter, day: Long): Int {
        val (d, n) = counter.get().split(':').let { (it.getOrNull(0)?.toLongOrNull() ?: -1L) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        return if (d == day) n else 0
    }

    /**
     * Checks and sends one feedback. Nothing is altered silently: a risky number blocks the send and the text stays as typed.
     * [post] does the network call (true when the form accepted it).
     */
    fun send(input: Input, app: String, counter: DayCounter, day: Long, post: (List<Pair<String, String>>) -> Boolean): Result {
        if (!canSend(input.category, input.message)) return Result.Empty
        if (hasRiskyDigits(input.message)) return Result.RiskyDigits
        if (input.identity == Identity.EMAIL && input.email.isNotBlank() && !emailLooksValid(input.email)) return Result.BadEmail
        val n = sentToday(counter, day)
        if (n >= DAILY_LIMIT) return Result.LimitReached
        if (!post(fields(input, app))) return Result.Failed
        counter.set("$day:${n + 1}")
        return Result.Sent
    }

    /** POST to the feedback form (the only network call this adds; made when the user presses Send). */
    fun postToForm(fields: List<Pair<String, String>>): Boolean = try {
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val c = URL(FeedbackConfig.FEEDBACK_FORM_POST).openConnection() as HttpURLConnection
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
}
