package com.uaefinancial.tracker.bridge

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.Glyph
import com.uaefinancial.tracker.core.SmsKey
import com.uaefinancial.tracker.core.Spending
import com.uaefinancial.tracker.core.StatementImport
import com.uaefinancial.tracker.core.StatementReader
import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.CategoryRules
import com.uaefinancial.tracker.parser.ManualEntryParser
import com.uaefinancial.tracker.parser.Money
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType

/*
 * A small, flat API over the engine for the iPhone app (Swift sees plain strings and numbers instead of Kotlin's
 * sealed classes, enums and decimals). The Android app talks to the engine directly.
 */

/** What an SMS says. [kind]: transaction, statement, ignored, otp, failed or notBank. */
data class SmsReading(
    val kind: String,
    val bank: String?,
    val reason: String?,
    val ruleId: String?,
    // transaction
    val type: String?,
    val cardLast4: String?,
    val cardType: String?,
    val merchant: String?,
    val amountMinor: Long,
    val currency: String?,
    /** AED equivalent in fils; -1 when the currency has no rate. */
    val aedMinor: Long,
    val fxEstimated: Boolean,
    val timestampMillis: Long,
    /** Available limit / balance after the transaction; [hasAvailable] says whether the SMS gave one. */
    val availableMinor: Long,
    val hasAvailable: Boolean,
    val toLast4: String?,
    val accountNotNamed: Boolean,
    val auto: Boolean,
    // statement
    val balanceMinor: Long,
    val minimumDueMinor: Long,
    val hasMinimumDue: Boolean,
    val dueEpochDay: Long,
    val statementEpochDay: Long,
    val hasStatementDate: Boolean,
)

data class StatementRow(val epochDay: Long, val details: String, val amountMinor: Long, val isCredit: Boolean, val cardLast4: String?)

/** A statement PDF read into figures and rows. Amounts in fils; -1 means "not found on the statement". */
data class StatementReading(
    val bank: String?,
    val cardLast4: String?,
    val isAccount: Boolean,
    val statementEpochDay: Long,
    val dueEpochDay: Long,
    val totalDueMinor: Long,
    val minimumDueMinor: Long,
    val creditLimitMinor: Long,
    val availableMinor: Long,
    val previousBalanceMinor: Long,
    val rows: List<StatementRow>,
    /** "Read 23 transactions ... They add up to the statement's balance." */
    val check: String?,
    /** 1 adds up, 0 doesn't, -1 couldn't check. */
    val addsUp: Int,
)

/** A transaction already in the app, for checking a statement against it. */
data class AppTxn(val epochDay: Long, val amountMinor: Long, val isCredit: Boolean, val estimated: Boolean)

data class CategoryInfo(val id: Long, val name: String)

data class TypedEntry(val details: String, val amountMinor: Long, val currency: String, val cardLast4: String?, val type: String)

object Bridge {
    const val NONE = -1L

    fun setCustomSenders(senderToBank: Map<String, String>) = SmsParser.setCustomSenders(senderToBank)
    fun bankFor(sender: String): String? = SmsParser.bankFor(sender)?.name
    fun bankNames(): List<String> = BankRules.banks.map { it.name }
    fun senderIds(bank: String): List<String> = BankRules.banks.firstOrNull { it.name == bank }?.senderIds.orEmpty()
    fun looksLikeBankAlert(body: String): Boolean = SmartParser.looksLikeBankAlert(body)

    fun dedupKey(sender: String, sentAtMillis: Long, receivedAtMillis: Long, body: String): String =
        SmsKey.of(sender, sentAtMillis.takeIf { it > 0 }, receivedAtMillis, body)

    fun bodyHash(body: String): String = SmsKey.bodyHash(body)

    /** Reads one bank SMS. [rates]: currency -> AED rate as text ("3.6725"); built-in rates are used when empty. */
    fun readSms(sender: String?, body: String, receivedAtMillis: Long, rates: Map<String, String>): SmsReading =
        reading(SmsParser.parse(sender, body, receivedAtMillis), rates)

    /**
     * Reads a bank SMS whose sender isn't known (pasted text, or a Shortcut that didn't pass the sender): a bank's own
     * formats first, then a bank named in the text, then the smart reader.
     */
    fun readSmsAnySender(body: String, receivedAtMillis: Long, rates: Map<String, String>): SmsReading {
        var fallback: SmsReading? = null
        for (b in BankRules.banks.filter { it.rules.isNotEmpty() }) {
            val r = reading(SmsParser.parseAsBank(b.name, body, receivedAtMillis), rates)
            if (r.kind == "otp") return r
            if ((r.kind == "transaction" || r.kind == "statement") && !r.auto) return r
            if (fallback == null && r.kind == "ignored") fallback = r
        }
        val named = BankRules.banks.firstOrNull { b ->
            Regex("""\b${Regex.escape(b.name)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
                b.senderIds.any { id -> id.length >= 4 && Regex("""\b${Regex.escape(id)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) }
        }
        val r = reading(SmsParser.parseAsBank(named?.name ?: UNKNOWN_BANK, body, receivedAtMillis), rates)
        return if (r.kind == "failed" && fallback != null) fallback else r
    }

    /** Bank name used when a message can't be tied to a bank. */
    const val UNKNOWN_BANK = "Other bank"

    private fun reading(r: ParseResult, rates: Map<String, String>): SmsReading {
        fun base(kind: String, bank: String?, reason: String?) = SmsReading(
            kind, bank, reason, null, null, null, null, null, 0, null, NONE, false, 0, 0, false, null, false, false, 0, 0, false, 0, 0, false,
        )
        return when (r) {
            is ParseResult.NotBank -> base("notBank", null, null)
            is ParseResult.Ignored -> base(if (r.store) "ignored" else "otp", r.bank, r.reason)
            is ParseResult.Failed -> base("failed", r.bank, r.reason)
            is ParseResult.Statement -> {
                val s = r.statement
                base("statement", s.bank, null).copy(
                    ruleId = r.ruleId, cardLast4 = s.cardLast4, cardType = s.cardType.name, currency = s.currency,
                    balanceMinor = Money.toMinor(s.statementBalance),
                    minimumDueMinor = s.minimumDue?.let { Money.toMinor(it) } ?: 0, hasMinimumDue = s.minimumDue != null,
                    dueEpochDay = s.dueDate.toEpochDay(), statementEpochDay = s.statementDate?.toEpochDay() ?: 0, hasStatementDate = s.statementDate != null,
                    auto = s.auto,
                )
            }
            is ParseResult.Transaction -> {
                val t = r.txn
                val rateMap = rates.mapNotNull { (k, v) -> com.uaefinancial.tracker.core.Decimal.parseOrNull(v)?.let { k.uppercase() to it } }.toMap()
                val aed = runCatching { Money.toAedMinor(t.amount, t.currency, rateMap.ifEmpty { BankRules.fxToAed }) }.getOrNull()
                val amount = runCatching { Money.toMinor(t.amount) }.getOrNull() ?: return base("failed", t.bank, "The amount looked wrong")
                base("transaction", t.bank, null).copy(
                    ruleId = r.ruleId, type = t.type.name, cardLast4 = t.cardLast4, cardType = t.cardType.name, merchant = t.merchant,
                    amountMinor = amount, currency = t.currency, aedMinor = aed?.first ?: NONE, fxEstimated = aed?.second ?: false,
                    timestampMillis = t.timestamp, availableMinor = t.availableLimit?.let { runCatching { Money.toMinor(it) }.getOrNull() } ?: 0,
                    hasAvailable = t.availableLimit != null, toLast4 = t.toLast4, accountNotNamed = t.accountNotNamed, auto = t.auto,
                )
            }
        }
    }

    /**
     * Reads a statement from the characters of a PDF: parallel arrays of page, x, y (from the top), width, font size
     * and text, one entry per character (how PDFKit hands them over).
     */
    fun readStatement(
        pages: List<Int>, xs: List<Float>, ys: List<Float>, widths: List<Float>, sizes: List<Float>, texts: List<String>, thisYear: Int,
    ): StatementReading {
        val glyphs = texts.indices.map { i -> Glyph(pages[i], xs[i], ys[i], widths[i], sizes[i], texts[i]) }
        return readStatementLines(StatementReader.linesFromGlyphs(glyphs), thisYear)
    }

    /**
     * Same, from one line per character: "page<TAB>x<TAB>y<TAB>width<TAB>size<TAB>text" (the iPhone app builds this from
     * PDFKit; y is measured from the top of the page).
     */
    fun readStatementTsv(tsv: String, thisYear: Int): StatementReading {
        val glyphs = tsv.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 6 || p[5].isBlank()) null
            else runCatching { Glyph(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5]) }.getOrNull()
        }.toList()
        return readStatementLines(StatementReader.linesFromGlyphs(glyphs), thisYear)
    }

    /** Same, from plain text (e.g. a statement copied as text). */
    fun readStatementText(text: String, thisYear: Int): StatementReading = readStatementLines(StatementReader.linesFromText(text), thisYear)

    private fun readStatementLines(lines: List<com.uaefinancial.tracker.core.PrintedLine>, thisYear: Int): StatementReading {
        val a = StatementReader.analyze(lines, thisYear)
        val s = a.summary
        fun day(d: CalendarDate?) = d?.toEpochDay() ?: NONE
        return StatementReading(
            bank = s.bank, cardLast4 = s.cardLast4, isAccount = s.isAccount,
            statementEpochDay = day(s.statementDate), dueEpochDay = day(s.dueDate),
            totalDueMinor = s.totalDueMinor ?: NONE, minimumDueMinor = s.minimumDueMinor ?: NONE,
            creditLimitMinor = s.creditLimitMinor ?: NONE, availableMinor = s.availableLimitMinor ?: NONE,
            previousBalanceMinor = s.previousBalanceMinor ?: NONE,
            rows = a.lines.map { StatementRow(it.date.toEpochDay(), it.description, it.amountMinor, it.isCredit, it.cardLast4) },
            check = a.totalsCheck,
            addsUp = when (a.totalsAgree) { true -> 1; false -> 0; null -> -1 },
        )
    }

    /** The statement rows that aren't in the app yet (same amount within 3 days; foreign spends within 3%). */
    fun missingRows(rows: List<StatementRow>, app: List<AppTxn>): List<StatementRow> {
        val lines = rows.map {
            com.uaefinancial.tracker.core.StatementLine(CalendarDate.ofEpochDay(it.epochDay), it.details, it.amountMinor, it.isCredit, it.details, it.cardLast4)
        }
        val refs = app.mapIndexed { i, a -> com.uaefinancial.tracker.core.AppTxnRef(i.toLong(), CalendarDate.ofEpochDay(a.epochDay), a.amountMinor, a.isCredit, a.estimated, "") }
        val missing = StatementImport.reconcile(lines, refs).missing.toSet()
        return rows.filterIndexed { i, _ -> lines[i] in missing }
    }

    /** "lunch 45", "usd 20 netflix #1234", "refund amazon 50". Null when there's no amount. */
    fun typedEntry(text: String): TypedEntry? = ManualEntryParser.parse(text)?.let {
        TypedEntry(it.description, Money.toMinor(it.amount), it.currency, it.cardLast4, it.type.name)
    }

    fun categories(): List<CategoryInfo> = CategoryRules.defaults.map { CategoryInfo(it.id, it.name) }
    fun merchantKey(merchant: String): String = CategoryRules.merchantKey(merchant)

    /** Keyword guess for a merchant; -1 when the type has no category (payments, transfers). */
    fun guessCategory(merchant: String, type: String): Long = CategoryRules.guess(merchant, txnType(type)) ?: NONE

    fun familyCategoryId(): Long = CategoryRules.FAMILY
    fun incomeCategoryId(): Long = CategoryRules.INCOME

    /** How much one transaction adds to spending, in fils (see core/Spending.kt). */
    fun spendingContribution(type: String, aedMinor: Long, cardCounted: Boolean): Long =
        Spending.contributionAedMinor(txnType(type), aedMinor.takeIf { it != NONE }, cardCounted)

    /** Credit cards count as spending by default; debit cards and bank accounts don't. */
    fun countsByDefault(cardType: String): Boolean = Spending.defaultCountInSpending(cardTypeOf(cardType))

    fun transferLabel(last4: String): String = SmsParser.transferLabel(last4)

    /** Key of a card or account: "Bank ·1234", or "Bank ·????" when the number isn't known (same as Android). */
    fun cardKey(bank: String, last4: String?): String = "$bank ·${last4 ?: "????"}"

    /** Whether this type can carry a category (card payments can't). */
    fun canHaveCategory(type: String): Boolean = CategoryRules.canHaveCategory(txnType(type))

    /** Key a learned category is stored under: the same text and amount for generic debits, otherwise the merchant. */
    fun learningKey(merchant: String, amountMinor: Long, type: String): String = CategoryRules.learningKey(merchant, amountMinor, txnType(type))

    /** The smart reader's best guess for a message it couldn't read for sure (pre-fills the Fix form). */
    fun guessTransaction(bank: String, body: String, receivedAtMillis: Long): SmsReading? =
        runCatching { SmartParser.read(bank, SmsParser.normalizeBody(body), receivedAtMillis) }.getOrNull()
            ?.let { it as? ParseResult.Transaction }?.let { reading(it, emptyMap()) }

    /** Converts an amount in [currency] to fils of AED with the given rates; -1 when there's no rate. */
    fun toAedMinor(amountMinor: Long, currency: String, rates: Map<String, String>): Long {
        val rateMap = rates.mapNotNull { (k, v) -> com.uaefinancial.tracker.core.Decimal.parseOrNull(v)?.let { k.uppercase() to it } }.toMap()
        return runCatching { Money.toAedMinor(Money.fromMinor(amountMinor), currency.uppercase(), rateMap.ifEmpty { BankRules.fxToAed }) }
            .getOrNull()?.first ?: NONE
    }

    /** Built-in approximate rates (currency to AED), as text. */
    fun defaultRates(): Map<String, String> = BankRules.fxToAed.mapValues { it.value.toPlainString() }

    private fun txnType(name: String) = runCatching { TxnType.valueOf(name) }.getOrDefault(TxnType.PURCHASE)
    private fun cardTypeOf(name: String) = runCatching { CardType.valueOf(name) }.getOrDefault(CardType.CREDIT)
}
