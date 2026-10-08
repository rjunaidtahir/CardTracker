package com.uaefinancial.tracker.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType
import org.json.JSONObject
import java.math.BigDecimal

/** What the phone's own AI proposed for a message (every field already checked against the message text). */
data class AiGuess(
    val type: TxnType,
    val amount: BigDecimal,
    val currency: String?,
    val merchant: String,
    val cardLast4: String?,
    val cardType: CardType?,
)

/**
 * Optional help for messages the rules can't read: Google's on-device Gemini Nano (ML Kit GenAI, via the phone's AICore).
 * Runs only on phones that have it, only while the app is open, only when you tap the button, and nothing leaves the
 * phone. It only fills the Fix form: you still press Save, and every figure must appear in the message itself.
 */
object PhoneAi {
    private val model by lazy { Generation.getClient() }

    /** True when this phone has the model ready. Any error counts as "no". */
    suspend fun available(): Boolean = runCatching { model.checkStatus() == FeatureStatus.AVAILABLE }.getOrDefault(false)

    suspend fun suggest(body: String): AiGuess? = runCatching {
        val prompt = buildString {
            append("Read this bank message and answer with ONE JSON object only, no other text. ")
            append("Keys: type (one of purchase, refund, card_payment, money_in, money_out), ")
            append("amount (the transaction amount as a plain number; NOT a balance, limit or fee total), ")
            append("currency (3-letter code or null), merchant (as written in the message, or empty), ")
            append("last4 (card or account last 4 digits as written, or null), ")
            append("account (credit, debit or bank). ")
            append("Use only what is written in the message; if unsure use null.\n\nMessage:\n")
            append(body.take(1500))
        }
        val text = model.generateContent(prompt).candidates.firstOrNull()?.text ?: return null
        parse(text, body)
    }.getOrNull()

    /** Turns the model's answer into a guess, dropping anything the message doesn't back up. Pure, so it is unit-tested. */
    internal fun parse(answer: String, body: String): AiGuess? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() ?: return null
        val amount = o.opt("amount")?.toString()?.replace(",", "")?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val flat = body.replace(",", "")
        val forms = setOf(amount.toPlainString(), amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), amount.stripTrailingZeros().toPlainString())
        if (forms.none { flat.contains(it) }) return null
        val type = when (o.optString("type").lowercase()) {
            "purchase" -> TxnType.PURCHASE
            "refund" -> TxnType.REFUND
            "card_payment" -> TxnType.PAYMENT
            "money_in" -> TxnType.TRANSFER_IN
            "money_out" -> TxnType.TRANSFER_OUT
            else -> return null
        }
        val cur = o.optString("currency").uppercase().takeIf { it.length == 3 && it.all(Char::isLetter) && flat.uppercase().contains(it) }
        val merchant = o.optString("merchant").trim().take(60).takeIf { it.isNotEmpty() && body.contains(it, ignoreCase = true) } ?: ""
        val last4 = o.optString("last4").filter(Char::isDigit).takeIf { it.length == 4 && body.contains(it) }
        val cardType = when (o.optString("account").lowercase()) {
            "credit" -> CardType.CREDIT
            "debit" -> CardType.DEBIT
            "bank" -> CardType.ACCOUNT
            else -> null
        }
        return AiGuess(type, amount, cur, merchant, last4, cardType)
    }
}
