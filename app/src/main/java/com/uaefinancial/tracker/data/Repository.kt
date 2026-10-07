package com.uaefinancial.tracker.data

import com.uaefinancial.tracker.toJava
import com.uaefinancial.tracker.core.toDecimalOrNull
import com.uaefinancial.tracker.core.Decimal
import androidx.room.withTransaction
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.core.SmsKey
import com.uaefinancial.tracker.core.Spending
import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.ParsedTransaction
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType
import com.uaefinancial.tracker.parser.ManualEntry
import com.uaefinancial.tracker.parser.Money
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.CategoryRules
import com.uaefinancial.tracker.parser.LearnedFormats
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by Sync and live listening: same parser, same de-duplication. */
class Repository(private val db: AppDatabase, private val prefs: Prefs? = null) {
    val dao = db.dao()
    private val lock = Mutex()

    /** Called with sender -> bank name whenever the added senders change (the app caches them for the SMS receiver). */
    var onSendersChanged: ((Map<String, String>) -> Unit)? = null

    /** Current rates, "1 unit = x AED" (from the fx_rates table; defaults from BankRules.fxToAed). */
    @Volatile
    var rates: Map<String, Decimal> = BankRules.fxToAed
        private set

    /** New transactions from Sync / live SMS since the last drain, for alerts. Not filled by Re-parse. */
    private val fresh = mutableListOf<TransactionEntity>()
    @Volatile private var reparsing = false

    fun drainFresh(): List<TransactionEntity> = synchronized(fresh) { fresh.toList().also { fresh.clear() } }

    // ------------------------------------------------------------ setup

    /** Seeds categories and exchange rates, and categorises older transactions once. Safe to call every start. */
    suspend fun ensureDefaults() {
        // Seeds categories on first run, and adds default categories introduced by later versions.
        val have = dao.allCategories()
        val missingCats = CategoryRules.defaults.withIndex()
            .filter { (_, c) -> have.none { it.id == c.id || it.name.equals(c.name, ignoreCase = true) } }
            .map { (i, c) -> CategoryEntity(c.id, c.name, i) }
        if (missingCats.isNotEmpty()) dao.upsertCategories(missingCats)
        val stored = dao.allRates()
        val missing = BankRules.fxToAed.filterKeys { k -> stored.none { it.currency == k } }
        if (missing.isNotEmpty()) {
            dao.upsertRates(missing.map { (c, r) -> FxRateEntity(c, r.toPlainString(), System.currentTimeMillis()) })
        }
        loadRates()
        loadSenders()
        runCatching { autoFillCardDays() }
        // Transactions created before categories existed (v0.4 and earlier).
        for (t in dao.txnsWithoutMerchantKey()) {
            val key = CategoryRules.merchantKey(t.merchant)
            val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
            val cat = if (t.categoryUserSet) t.categoryId else resolveCategory(null, key, t.merchant, type, t.amountMinor)
            dao.setMerchantKeyAndCategory(t.id, key, cat)
        }
    }

    private suspend fun loadRates() {
        rates = dao.allRates().mapNotNull { r -> r.rateToAed.toDecimalOrNull()?.let { r.currency to it } }.toMap() +
            (BankRules.BASE_CURRENCY to Decimal.ONE)
    }

    // ------------------------------------------------------------ bank senders

    suspend fun loadSenders() {
        val map = dao.allSenders().associate { it.sender to it.bankName }
        SmsParser.setCustomSenders(map)
        onSendersChanged?.invoke(map)
    }

    /** Adds (or renames) a sender ID. The next Sync re-reads the inbox so older messages from it are imported too. */
    suspend fun addSender(sender: String, bankName: String) {
        val s = sender.trim()
        val b = bankName.trim().ifEmpty { s }
        if (s.isEmpty()) return
        dao.upsertSenders(listOf(SenderEntity(s, b)))
        loadSenders()
    }

    suspend fun removeSender(sender: String) {
        dao.deleteSender(sender)
        loadSenders()
    }

    /** Your choice for this SMS > learned merchant rule > keyword guess. */
    private suspend fun resolveCategory(dedupKey: String?, merchantKey: String, merchant: String, type: TxnType, amountMinor: Long): Long? {
        if (!CategoryRules.canHaveCategory(type)) return null
        dedupKey?.let { dao.overrideFor(it) }?.let { return it.categoryId }
        // Learned rule: same text and amount for generic debits and transfers, otherwise the merchant.
        dao.ruleFor(CategoryRules.learningKey(merchant, amountMinor, type))?.let { return it }
        if (type == TxnType.TRANSFER_OUT) return null // transfers are only categorised when you choose it
        if (type == TxnType.TRANSFER_IN) {
            // Money in: salary is recognised, anything else only when you choose it.
            return if (Regex("SALARY", RegexOption.IGNORE_CASE).containsMatchIn(merchant)) CategoryRules.INCOME else null
        }
        if (merchantKey.isNotEmpty()) dao.ruleFor(merchantKey)?.let { return it }
        return CategoryRules.guess(merchant, type)
    }

    // ------------------------------------------------------------ learned formats ("apply to similar messages")

    @Volatile private var learnedLoaded = false

    /** Hands the fixes you asked the app to learn from to the parser. Call again whenever they change. */
    suspend fun loadLearned() {
        val keys = prefs?.learnedFixKeys.orEmpty()
        if (keys.isEmpty()) {
            LearnedFormats.setAll(emptyList())
        } else {
            val fixes = dao.allFixes().filter { it.dedupKey in keys }.associateBy { it.dedupKey }
            val sources = dao.smsByDedupKeys(keys.toList()).mapNotNull { s -> fixes[s.dedupKey]?.let { learnedSource(s, it) } }
            LearnedFormats.setAll(sources)
        }
        learnedLoaded = true
    }

    private suspend fun ensureLearned() { if (!learnedLoaded) loadLearned() }

    private fun learnedSource(s: SmsEntity, f: SmsFixEntity) = LearnedFormats.Source(
        key = s.dedupKey, bank = s.bank ?: s.sender, body = s.body,
        type = if (f.type == FIX_IGNORE) null else runCatching { TxnType.valueOf(f.type) }.getOrNull(),
        amount = if (f.amountMinor > 0) Money.fromMinor(f.amountMinor) else null,
        currency = f.currency, merchant = f.merchant, cardLast4 = f.cardLast4,
        cardType = runCatching { CardType.valueOf(f.cardType) }.getOrDefault(CardType.CREDIT),
    )

    /** Whether "apply to similar messages" can work for this reading of [sms] (for the Fix form). */
    fun canLearn(sms: SmsEntity, type: TxnType?, amountMinor: Long, merchant: String, cardLast4: String?, cardType: CardType): Boolean =
        LearnedFormats.canLearn(
            LearnedFormats.Source(
                key = sms.dedupKey, bank = sms.bank ?: sms.sender, body = sms.body, type = type,
                amount = if (amountMinor > 0) Money.fromMinor(amountMinor) else null, currency = SmsParser.homeCurrency,
                merchant = merchant, cardLast4 = cardLast4, cardType = cardType,
            ),
        )

    /** Set by the app: gets (sender, bank, body, kind) for messages worth learning from. The app masks before storing anything. */
    var onShape: ((String, String?, String, String) -> Unit)? = null

    suspend fun ingestSms(sender: String, body: String, receivedAt: Long, sentAt: Long?, source: String): IngestOutcome =
        lock.withLock {
            ensureLearned()
            val bank = SmsParser.bankFor(sender) ?: return IngestOutcome.NOT_BANK
            val parsed = SmsParser.parse(sender, body, receivedAt)
            if (parsed is ParseResult.Ignored && !parsed.store) return IngestOutcome.OTP_SKIPPED

            val hash = SmsKey.bodyHash(body)
            val sent = sentAt?.takeIf { it > 0 }
            // Safety net when one copy has no sent time (then the keys can't match exactly).
            val window = 10 * 60 * 1000L
            if (dao.countNearDuplicates(bank.name, hash, receivedAt - window, receivedAt + window, sent) > 0) {
                return IngestOutcome.DUPLICATE
            }
            db.withTransaction {
                val id = dao.insertSms(
                    SmsEntity(
                        dedupKey = SmsKey.of(sender, sent, receivedAt, body),
                        sender = sender, body = body, bodyHash = hash,
                        receivedAt = receivedAt, sentAt = sent, source = source,
                        bank = bank.name, status = SmsStatus.PENDING,
                    ),
                )
                val dedupKey = SmsKey.of(sender, sent, receivedAt, body)
                if (id == -1L) IngestOutcome.DUPLICATE else applyParse(id, dedupKey, parsed, receivedAt, bank.name)
            }.also { outcome ->
                // Could not be read: offer its masked shape for learning (only if you allowed it).
                if (outcome == IngestOutcome.FAILED) runCatching { onShape?.invoke(sender, bank.name, body, "unread") }
            }
        }

    /** Re-runs the current BankRules over every stored SMS (except dismissed ones). */
    suspend fun reparseAll(): Map<IngestOutcome, Int> = lock.withLock {
        ensureLearned()
        val all = dao.smsForReparse()
        val counts = mutableMapOf<IngestOutcome, Int>()
        reparsing = true
        try { db.withTransaction {
            // Rebuild every SMS-based row from scratch, in time order, so two-SMS transfers pair up cleanly.
            // (Typed entries are untouched; card settings are kept.)
            dao.deleteAllSmsTxns()
            dao.deleteAllStatements()
            for (s in all) {
                val o = applyParse(s.id, s.dedupKey, SmsParser.parse(s.sender, s.body, s.receivedAt), s.receivedAt, s.bank)
                counts[o] = (counts[o] ?: 0) + 1
            }
        } } finally { reparsing = false }
        counts
    }

    private fun CardType.dbName() = when (this) {
        CardType.CREDIT -> CardTypes.CREDIT
        CardType.DEBIT -> CardTypes.DEBIT
        CardType.ACCOUNT -> CardTypes.ACCOUNT
    }

    private suspend fun ensureCard(key: String, bank: String, last4: String?, type: CardType) {
        dao.insertCard(CardEntity(key, bank, last4, type.dbName(), Spending.defaultCountInSpending(type)))
    }

    /**
     * One of your cards/accounts that a transfer went to (by last 4 digits), or null. Only an unambiguous match
     * counts: if two of your cards end in the same digits, the app doesn't guess.
     */
    private suspend fun destinationFor(last4: String, fromBank: String): CardEntity? {
        val matches = dao.cardsByLast4(last4)
        return matches.singleOrNull()
            ?: matches.filter { it.cardType == CardTypes.CREDIT && it.owner == null }.singleOrNull()
            ?: matches.filter { it.bank == fromBank }.singleOrNull()
    }

    /** "ENBD credit card ·9940", or your nickname for it. */
    private fun label(c: CardEntity): String = c.nickname?.takeIf { it.isNotBlank() }
        ?: "${c.bank} " + when (c.cardType) { CardTypes.ACCOUNT -> "account"; CardTypes.DEBIT -> "debit card"; else -> "credit card" } + " ·${c.last4 ?: "????"}"

    private suspend fun applyTransaction(smsId: Long, dedupKey: String, r: ParseResult.Transaction): IngestOutcome {
        var t = r.txn
        // A reference number read as an amount: never let one message break a Sync or a re-read.
        if (t.amount.abs() >= Decimal("100000000000")) {
            dao.setSmsResult(smsId, SmsStatus.FAILED, t.bank, r.ruleId, "The amount looked wrong")
            return IngestOutcome.FAILED
        }
        // The SMS doesn't name the account (e.g. some bill payments): use your only account at that bank.
        if (t.cardLast4 == null && t.accountNotNamed) {
            dao.cardsOfBank(t.bank, CardTypes.ACCOUNT).singleOrNull()?.let { t = t.copy(cardLast4 = it.last4, cardType = CardType.ACCOUNT) }
        }
        // A card payment that doesn't say which card ("You have made a credit repayment of AED 80.74"): your only
        // credit card at that bank, if you have exactly one.
        if (t.cardLast4 == null && t.type == TxnType.PAYMENT && t.cardType == CardType.CREDIT) {
            dao.cardsOfBank(t.bank, CardTypes.CREDIT).singleOrNull()?.last4?.let { t = t.copy(cardLast4 = it) }
        }
        // No card/account number: bank-account money goes on a "Bank ·????" account (not counted as spending by
        // default); anything else has no card, like a typed entry.
        val key = when {
            t.cardLast4 != null -> cardKeyOf(t.bank, t.cardLast4)
            t.accountNotNamed || t.cardType == CardType.ACCOUNT -> cardKeyOf(t.bank, null)
            else -> null
        }
        if (key != null) ensureCard(key, t.bank, t.cardLast4, t.cardType)
        val card = key?.let { k -> dao.allCards().firstOrNull { it.cardKey == k } }
        val aed = Money.toAedMinor(t.amount, t.currency, rates)
        val amountMinor = Money.toMinor(t.amount)
        val availMinor = t.availableLimit?.let { Money.toMinor(it) }

        // A transfer to one of your own cards is a payment to that card (never spending); name it after the card.
        val to = t.toLast4
        val dest = to?.let { destinationFor(it, t.bank) }
        val counterpartyKey = dest?.takeIf { it.cardType == CardTypes.CREDIT && it.owner == null && it.cardKey != key }?.cardKey
        if (dest != null && t.merchant == SmsParser.transferLabel(to)) {
            t = t.copy(merchant = (if (counterpartyKey != null) "Payment to " else "Transfer to ") + label(dest))
        }
        val merchantKey = CategoryRules.merchantKey(t.merchant)

        // Two SMS for one transfer (e.g. FAB "Outward Remittance Debit" + "funds transfer processed"): merge.
        // Only a message that names the destination pairs with one that doesn't (never two transfers with each other).
        val rule = BankRules.ruleById(r.ruleId)
        val group = rule?.pairGroup
        // A generic "amount debited" SMS (PURCHASE, e.g. an EMI) can be the same money as a transfer SMS.
        if (rule != null && group != null && key != null && (t.type == TxnType.TRANSFER_OUT || t.type == TxnType.PURCHASE)) {
            val hasTo = rule.namesDestination
            val groupRules = BankRules.banks.flatMap { it.rules }
                .filter { it.pairGroup == group && it.namesDestination != hasTo }
                .map { it.id }
            // The remittance SMS carries only a date (possibly the next value date), so allow up to a day apart.
            val window = 24 * 60 * 60 * 1000L
            val other = dao.findTransferPair(key, amountMinor, groupRules, r.ruleId, t.timestamp - window, t.timestamp + window, t.timestamp)
            if (other != null) {
                dao.mergePair(
                    id = other.id,
                    smsId = smsId,
                    // Prefer the text that names the destination ("Payment to ENBD credit card ·1234").
                    merchant = if (t.toLast4 != null) t.merchant else other.merchant,
                    counterpartyKey = other.counterpartyKey ?: counterpartyKey,
                    availableLimitMinor = other.availableLimitMinor ?: availMinor,
                    // The "processed" SMS has the exact time; the remittance only a date.
                    timestamp = if (hasTo) t.timestamp else other.timestamp,
                    // Once a transfer SMS explains a debit, it's a transfer (not spending).
                    type = if (t.type == TxnType.TRANSFER_OUT || other.type == TxnType.TRANSFER_OUT.name) TxnType.TRANSFER_OUT.name else other.type,
                )
                dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, "Same transfer as transaction #${other.id} (merged)")
                return IngestOutcome.MERGED
            }
        }

        val override = dao.overrideFor(dedupKey)
        // Spends on a card you pay for someone else go to the Family category (unless you chose another).
        val family = card?.owner == CardOwner.FAMILY && t.type == TxnType.PURCHASE && override == null
        val entity =
            TransactionEntity(
                smsId = smsId, source = "SMS", timestamp = t.timestamp, bank = t.bank,
                cardLast4 = t.cardLast4, cardKey = key, merchant = t.merchant,
                amountMinor = amountMinor, currency = t.currency,
                amountAedMinor = aed?.first, fxEstimated = aed?.second ?: false,
                type = t.type.name, availableLimitMinor = availMinor,
                ruleId = r.ruleId, counterpartyKey = counterpartyKey,
                merchantKey = merchantKey,
                categoryId = if (family) CategoryRules.FAMILY else resolveCategory(dedupKey, merchantKey, t.merchant, t.type, amountMinor),
                categoryUserSet = override != null,
            )
        dao.insertTxn(entity)
        if (!reparsing) synchronized(fresh) { fresh += entity }
        val note = if (aed == null) "No exchange rate for ${t.currency}: add one in More → Exchange rates" else null
        dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, note)
        return IngestOutcome.TRANSACTION
    }

    private suspend fun applyParse(smsId: Long, dedupKey: String, r: ParseResult, receivedAt: Long, bank: String?): IngestOutcome {
        dao.deleteTxnsForSms(smsId)
        dao.deleteStatementsForSms(smsId)
        dao.unpairSms(smsId)
        // Your own reading of this SMS (Needs review → Fix) beats anything the app works out.
        dao.fixFor(dedupKey)?.let { fix -> return applyFix(smsId, dedupKey, fix, bank ?: "Bank") }
        return when (r) {
            is ParseResult.Transaction -> applyTransaction(smsId, dedupKey, r)
            is ParseResult.Statement -> {
                val s = r.statement
                val key = cardKeyOf(s.bank, s.cardLast4)
                ensureCard(key, s.bank, s.cardLast4, s.cardType)
                dao.insertStatement(
                    StatementEntity(
                        smsId = smsId, receivedAt = receivedAt, bank = s.bank, cardLast4 = s.cardLast4, cardKey = key,
                        balanceMinor = Money.toMinor(s.statementBalance),
                        minimumDueMinor = s.minimumDue?.let { Money.toMinor(it) },
                        currency = s.currency, dueDateEpochDay = s.dueDate.toEpochDay(),
                        statementDateEpochDay = s.statementDate?.toEpochDay(),
                    ),
                )
                dao.setSmsResult(smsId, SmsStatus.STATEMENT, s.bank, r.ruleId, null)
                IngestOutcome.STATEMENT
            }
            is ParseResult.Ignored -> {
                if (!r.store) {
                    dao.deleteSms(smsId) // e.g. an old SMS that a newer rule now recognises as OTP
                    IngestOutcome.OTP_SKIPPED
                } else {
                    dao.setSmsResult(smsId, SmsStatus.IGNORED, r.bank, null, r.reason)
                    IngestOutcome.IGNORED
                }
            }
            is ParseResult.Failed -> {
                dao.setSmsResult(smsId, SmsStatus.FAILED, r.bank, null, r.reason)
                IngestOutcome.FAILED
            }
            ParseResult.NotBank -> {
                dao.setSmsResult(smsId, SmsStatus.IGNORED, null, null, "This sender is no longer one of your bank senders")
                IngestOutcome.NOT_BANK
            }
        }
    }

    private suspend fun applyFix(smsId: Long, dedupKey: String, fix: SmsFixEntity, bank: String): IngestOutcome {
        if (fix.type == FIX_IGNORE) {
            dao.setSmsResult(smsId, SmsStatus.IGNORED, bank, null, "You marked this as not a transaction")
            return IngestOutcome.IGNORED
        }
        val type = runCatching { TxnType.valueOf(fix.type) }.getOrDefault(TxnType.PURCHASE)
        val cardType = runCatching { CardType.valueOf(fix.cardType) }.getOrDefault(CardType.CREDIT)
        val txn = ParsedTransaction(
            bank = bank, cardLast4 = fix.cardLast4, cardType = cardType, merchant = fix.merchant,
            amount = Money.fromMinor(fix.amountMinor), currency = fix.currency, type = type,
            timestamp = fix.timestamp, dateFromSms = true,
        )
        return applyTransaction(smsId, dedupKey, ParseResult.Transaction(txn, FIX_RULE))
    }

    /**
     * Saves your reading of an SMS the app couldn't read (or read wrongly), then applies it. [type] null means
     * "not a transaction": the SMS is ignored from now on.
     */
    suspend fun saveFix(
        smsId: Long, type: TxnType?, amountMinor: Long, currency: String, merchant: String,
        cardLast4: String?, cardType: CardType, timestamp: Long, applyToSimilar: Boolean = false,
    ): FixResult = lock.withLock {
        val sms = dao.smsById(smsId) ?: return@withLock FixResult(IngestOutcome.FAILED, 0)
        val outcome = db.withTransaction {
            dao.upsertFixes(
                listOf(
                    SmsFixEntity(
                        dedupKey = sms.dedupKey, type = type?.name ?: FIX_IGNORE, amountMinor = amountMinor,
                        currency = currency.uppercase(), merchant = merchant.trim().ifEmpty { "Transaction" },
                        cardLast4 = cardLast4?.trim()?.takeIf { it.isNotEmpty() }, cardType = cardType.name, timestamp = timestamp,
                    ),
                ),
            )
            applyParse(sms.id, sms.dedupKey, ParseResult.NotBank, sms.receivedAt, sms.bank ?: sms.sender)
        }
        // A fix you made teaches the app: offer the masked shape with what you said it is (only if you allowed it).
        runCatching { onShape?.invoke(sms.sender, sms.bank, sms.body, "fixed:" + (type?.name ?: "NOT_A_TRANSACTION")) }
        // Remember (or forget) this fix as a template, then read the other unread messages from this bank again.
        prefs?.let { p ->
            val keys = p.learnedFixKeys
            val wanted = applyToSimilar && canLearn(sms, type, amountMinor, merchant, cardLast4, cardType)
            if (wanted != (sms.dedupKey in keys)) p.learnedFixKeys = if (wanted) keys + sms.dedupKey else keys - sms.dedupKey
        }
        loadLearned()
        var similar = 0
        if (applyToSimilar) {
            val bank = sms.bank ?: sms.sender
            db.withTransaction {
                for (o in dao.failedSmsList()) {
                    if (o.id == sms.id || (o.bank ?: o.sender) != bank) continue
                    val r = applyParse(o.id, o.dedupKey, SmsParser.parse(o.sender, o.body, o.receivedAt), o.receivedAt, o.bank)
                    if (r != IngestOutcome.FAILED) similar++
                }
            }
        }
        FixResult(outcome, similar)
    }

    /** What saving a fix did: its own outcome, and how many other messages in Needs review it also read. */
    data class FixResult(val outcome: IngestOutcome, val similar: Int)

    /** What the smart reader makes of an SMS: used to pre-fill the Fix form. */
    fun guess(sms: SmsEntity): ParsedTransaction? {
        val bank = sms.bank ?: sms.sender
        val body = SmsParser.normalizeBody(sms.body)
        val read = (runCatching { com.uaefinancial.tracker.parser.SmartParser.read(bank, body, sms.receivedAt) }.getOrNull() as? ParseResult.Transaction)?.txn
        // Not readable as a whole: still offer what it does say (amount, currency, digits, a likely kind) rather than a blank form.
        return read ?: runCatching { com.uaefinancial.tracker.parser.SmartParser.suggest(bank, body, sms.receivedAt) }.getOrNull()
    }

    // -------------------------------------------------------------- cards you add by hand

    /** Adds a card or account by hand (e.g. before its first SMS arrives, or a family member's card). Returns its key. */
    suspend fun addCard(bank: String, last4: String?, type: CardType, nickname: String?, family: Boolean): String {
        val key = cardKeyOf(bank.trim(), last4?.trim()?.takeIf { it.isNotEmpty() })
        ensureCard(key, bank.trim(), last4?.trim()?.takeIf { it.isNotEmpty() }, type)
        dao.setCardOwner(key, if (family) CardOwner.FAMILY else null)
        if (!nickname.isNullOrBlank()) {
            val c = dao.allCards().first { it.cardKey == key }
            dao.updateCardProfile(key, nickname.trim(), c.creditLimitMinor, c.statementDay, c.dueDay, c.remindersEnabled)
        }
        return key
    }

    /** Deletes a card only when nothing is recorded on it (e.g. added by mistake). Returns false otherwise. */
    suspend fun deleteEmptyCard(key: String): Boolean = db.withTransaction {
        if (dao.countTxnsForCard(key) > 0 || dao.allStatements().any { it.cardKey == key }) return@withTransaction false
        dao.deleteCard(key)
        true
    }

    companion object {
        const val FIX_IGNORE = "IGNORE"
        const val FIX_RULE = "fixed-by-you"
    }

    suspend fun addManual(entry: ManualEntry, timestamp: Long = System.currentTimeMillis()) {
        // Attach to a known card if the last 4 digits match exactly one card.
        val card = entry.cardLast4?.let { dao.cardsByLast4(it).singleOrNull() }
        val aed = Money.toAedMinor(entry.amount, entry.currency, rates)
        val merchantKey = CategoryRules.merchantKey(entry.description)
        dao.insertTxn(
            TransactionEntity(
                smsId = null, source = "MANUAL", timestamp = timestamp,
                bank = card?.bank ?: "Manual", cardLast4 = card?.last4 ?: entry.cardLast4,
                cardKey = card?.cardKey, merchant = entry.description,
                amountMinor = Money.toMinor(entry.amount), currency = entry.currency,
                amountAedMinor = aed?.first, fxEstimated = aed?.second ?: false, type = entry.type.name,
                merchantKey = merchantKey,
                categoryId = resolveCategory(null, merchantKey, entry.description, entry.type, Money.toMinor(entry.amount)),
            ),
        )
    }

    // -------------------------------------------------------- categories

    /**
     * Sets a transaction's category. For SMS transactions the choice is remembered per SMS (survives Re-parse).
     * With [applyToMerchant], the app also learns "this merchant = this category" for the past and future.
     */
    suspend fun setCategory(t: TransactionEntity, categoryId: Long, applyToMerchant: Boolean) = db.withTransaction {
        dao.setTxnCategory(t.id, categoryId)
        t.smsId?.let { dao.dedupKeyOf(it) }?.let { dao.upsertOverrides(listOf(TxnOverrideEntity(it, categoryId))) }
        val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
        if (applyToMerchant) {
            if (CategoryRules.isAmountSpecific(t.merchant, type)) {
                // e.g. "Account debit" of AED 2,450: only debits with the same text and amount (your car EMI, not your rent).
                dao.upsertRules(listOf(MerchantRuleEntity(CategoryRules.learningKey(t.merchant, t.amountMinor, type), categoryId)))
                dao.applyRuleExact(t.merchant, t.amountMinor, t.type, categoryId)
            } else {
                val key = t.merchantKey ?: CategoryRules.merchantKey(t.merchant)
                if (key.isNotEmpty()) {
                    dao.upsertRules(listOf(MerchantRuleEntity(key, categoryId)))
                    dao.applyRule(key, categoryId)
                }
            }
        }
        Unit
    }

    suspend fun addCategory(name: String): Long {
        // Your own categories use ids below 100 (101+ are reserved for built-in ones added later).
        val id = (dao.allCategories().map { it.id }.filter { it < 100 }.maxOrNull() ?: 0L) + 1
        dao.upsertCategories(listOf(CategoryEntity(id, name.trim(), id.toInt())))
        return id
    }

    // -------------------------------------------------------------- cards

    suspend fun updateCardProfile(key: String, nickname: String?, limitMinor: Long?, statementDay: Int?, dueDay: Int?, reminders: Boolean) =
        dao.updateCardProfile(key, nickname?.trim()?.ifEmpty { null }, limitMinor, statementDay, dueDay, reminders)

    // -------------------------------------------------------------- goals

    suspend fun saveGoal(g: GoalEntity) { dao.upsertGoal(g) }
    suspend fun deleteGoal(id: Long) = dao.deleteGoal(id)

    // -------------------------------------------------------------- rates

    /**
     * Saves a rate ("1 [currency] = [rate] AED", AED being the pivot) and recalculates the home-currency amount of every
     * transaction (a change to your home currency's own rate moves every foreign amount).
     */
    suspend fun setRate(currency: String, rate: Decimal) = lock.withLock {
        db.withTransaction {
            dao.upsertRates(listOf(FxRateEntity(currency.uppercase(), rate.toPlainString(), System.currentTimeMillis())))
            loadRates()
            recomputeHomeAmountsLocked()
        }
    }

    /** 1 [currency] in your home currency, from the stored rates; null if either has no rate. */
    fun rateInHome(currency: String): java.math.BigDecimal? {
        val home = SmsParser.homeCurrency
        fun toAed(c: String): java.math.BigDecimal? =
            if (c == com.uaefinancial.tracker.parser.Currencies.PIVOT) java.math.BigDecimal.ONE else rates[c]?.let { java.math.BigDecimal(it.toPlainString()) }
        val c = toAed(currency.uppercase()) ?: return null
        val h = toAed(home)?.takeIf { it.signum() > 0 } ?: return null
        return c.divide(h, 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    }

    /**
     * Saves "1 [currency] = [perHome] in your home currency". Stored against AED: for AED itself (when your home
     * currency isn't AED) that means your home currency's rate.
     */
    suspend fun setRateInHome(currency: String, perHome: java.math.BigDecimal) {
        val home = SmsParser.homeCurrency
        val pivot = com.uaefinancial.tracker.parser.Currencies.PIVOT
        val c = currency.uppercase()
        require(perHome.signum() > 0 && c != home)
        val (code, toAed) = when {
            home == pivot -> c to perHome
            c == pivot -> home to java.math.BigDecimal.ONE.divide(perHome, 10, java.math.RoundingMode.HALF_UP)
            else -> {
                val h = rates[home]?.let { java.math.BigDecimal(it.toPlainString()) } ?: throw IllegalStateException("No rate for $home")
                c to perHome.multiply(h)
            }
        }
        val text = toAed.setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        setRate(code, text.toDecimalOrNull() ?: throw IllegalArgumentException(text))
    }

    /**
     * Changes your home currency: messages are read again (a "$" or "Rs" means your currency) and every amount is
     * recalculated in it. Budgets, goals and alert amounts keep their numbers.
     */
    suspend fun setHomeCurrency(code: String) {
        val c = code.trim().uppercase()
        require(com.uaefinancial.tracker.parser.Currencies.isCode(c)) { "Unknown currency $c" }
        prefs?.homeCurrency = c
        SmsParser.setHomeCurrency(c)
        reparseAll()
        recomputeHomeAmounts()
    }

    /** Recalculates every transaction's amount in your home currency (after it or a rate changes, or a restore). */
    suspend fun recomputeHomeAmounts() = lock.withLock { db.withTransaction { recomputeHomeAmountsLocked() } }

    private suspend fun recomputeHomeAmountsLocked() {
        for (t in dao.allTxns()) {
            val home = runCatching { Money.toAedMinor(Money.fromMinor(t.amountMinor), t.currency, rates) }.getOrNull()
            if (home?.first != t.amountAedMinor || (home?.second ?: false) != t.fxEstimated) {
                dao.setHomeAmount(t.id, home?.first, home?.second ?: false)
            }
        }
    }

    /** Deleting an SMS transaction also dismisses its SMS, so Re-parse doesn't bring it back. */
    suspend fun deleteTransaction(t: TransactionEntity) = db.withTransaction {
        dao.deleteTxn(t.id)
        t.smsId?.let { dao.setSmsStatus(it, SmsStatus.DISMISSED) }
        t.pairedSmsId?.let { dao.setSmsStatus(it, SmsStatus.DISMISSED) }
    }

    /** Saves the Cards-tab order: [keys] top to bottom. */
    suspend fun setCardOrder(keys: List<String>) = db.withTransaction {
        keys.forEachIndexed { i, k -> dao.setCardOrder(k, i) }
    }

    /**
     * Adds statement lines that were missing from the app as typed entries on [cardKey] (kept in backups,
     * untouched by Re-parse). Spends become purchases; credits become card payments / refunds (cards) or money in (accounts).
     */
    suspend fun addStatementLines(cardKey: String, lines: List<com.uaefinancial.tracker.core.StatementLine>): Int = db.withTransaction {
        val card = dao.allCards().firstOrNull { it.cardKey == cardKey } ?: return@withTransaction 0
        val zone = java.time.ZoneId.systemDefault()
        var added = 0
        for (l in lines) {
            val type = when {
                !l.isCredit -> TxnType.PURCHASE
                card.cardType == CardTypes.ACCOUNT -> TxnType.TRANSFER_IN
                Regex("PAYMENT|THANK YOU", RegexOption.IGNORE_CASE).containsMatchIn(l.description) -> TxnType.PAYMENT
                else -> TxnType.REFUND
            }
            val ts = l.date.toJava().atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            if (dao.countManual(ts, l.amountMinor, l.description) > 0) continue
            val key = CategoryRules.merchantKey(l.description)
            dao.insertTxn(
                TransactionEntity(
                    smsId = null, source = "MANUAL", timestamp = ts, bank = card.bank, cardLast4 = card.last4, cardKey = card.cardKey,
                    merchant = l.description, amountMinor = l.amountMinor, currency = BankRules.BASE_CURRENCY, amountAedMinor = l.amountMinor,
                    fxEstimated = false, type = type.name, merchantKey = key,
                    categoryId = if (card.owner == CardOwner.FAMILY && type == TxnType.PURCHASE) CategoryRules.FAMILY
                        else resolveCategory(null, key, l.description, type, l.amountMinor),
                    categoryUserSet = card.owner == CardOwner.FAMILY && type == TxnType.PURCHASE,
                    note = "From statement PDF",
                ),
            )
            added++
        }
        added
    }

    /** Creates a card you add from a statement PDF (e.g. a family member's), or returns the existing one. */
    suspend fun ensureStatementCard(bank: String, last4: String, family: Boolean, nickname: String?): String {
        val key = cardKeyOf(bank.trim(), last4)
        ensureCard(key, bank.trim(), last4, CardType.CREDIT)
        if (family) dao.setCardOwner(key, CardOwner.FAMILY)
        if (!nickname.isNullOrBlank()) {
            val c = dao.allCards().first { it.cardKey == key }
            dao.updateCardProfile(key, nickname.trim(), c.creditLimitMinor, c.statementDay, c.dueDay, c.remindersEnabled)
        }
        return key
    }

    suspend fun setCardOwner(key: String, family: Boolean) = db.withTransaction {
        dao.setCardOwner(key, if (family) CardOwner.FAMILY else null)
        Unit
    }

    /**
     * Saves the key figures read from a statement PDF: credit limit, statement / due day, and (when no SMS statement
     * with that due date exists) the statement itself so it shows in Payments due. Returns what was saved.
     */
    suspend fun applyStatementSummary(cardKey: String, s: com.uaefinancial.tracker.core.StatementSummary, receivedAt: Long): List<String> = db.withTransaction {
        val card = dao.allCards().firstOrNull { it.cardKey == cardKey } ?: return@withTransaction emptyList()
        val done = mutableListOf<String>()
        if (s.isAccount) return@withTransaction done // account statements have no limit / due date to save
        s.creditLimitMinor?.takeIf { it > 0 }?.let { dao.setCreditLimit(cardKey, it); done += "credit limit" }
        val sd = s.statementDate?.dayOfMonth
        val dd = s.dueDate?.dayOfMonth
        if (sd != null || dd != null) {
            dao.updateCardProfile(cardKey, card.nickname, s.creditLimitMinor ?: card.creditLimitMinor, sd ?: card.statementDay, dd ?: card.dueDay, card.remindersEnabled)
            done += "statement / due day"
        }
        val avail = s.availableLimitMinor
        val stDate = s.statementDate
        if (avail != null && stDate != null) {
            dao.setStatementAvailable(cardKey, avail, stDate.toEpochDay())
            done += "available limit"
        }
        val due = s.dueDate
        val total = s.totalDueMinor
        if (due != null && stDate != null && dao.countStatements(cardKey, due.toEpochDay()) > 0) {
            dao.setStatementDate(cardKey, due.toEpochDay(), stDate.toEpochDay())
        }
        if (due != null && total != null && dao.countStatements(cardKey, due.toEpochDay()) == 0) {
            dao.insertStatement(
                StatementEntity(
                    smsId = 0, receivedAt = receivedAt, bank = card.bank, cardLast4 = card.last4, cardKey = cardKey,
                    balanceMinor = total, minimumDueMinor = s.minimumDueMinor, currency = BankRules.BASE_CURRENCY,
                    dueDateEpochDay = due.toEpochDay(), statementDateEpochDay = s.statementDate?.toEpochDay(),
                ),
            )
            done += "statement for due date ${due}"
        }
        done
    }

    /** Pre-fills each card's statement day and due day from its statement SMS of the last 30 days (never overwrites yours). */
    suspend fun autoFillCardDays(today: java.time.LocalDate = java.time.LocalDate.now()) {
        val zone = java.time.ZoneId.systemDefault()
        val byCard = dao.allStatements().groupBy { it.cardKey }
        for (c in dao.allCards()) {
            if (c.statementDay != null && c.dueDay != null) continue
            val list = byCard[c.cardKey].orEmpty().map {
                Triple(
                    it.statementDateEpochDay?.let { d -> java.time.LocalDate.ofEpochDay(d) },
                    java.time.LocalDate.ofEpochDay(it.dueDateEpochDay),
                    java.time.Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate(),
                )
            }
            val days = com.uaefinancial.tracker.core.CardDays.fromStatements(list, today) ?: continue
            dao.fillCardDays(c.cardKey, days.statementDay, days.dueDay)
        }
    }

    suspend fun setCardTheme(key: String, theme: String?) = dao.setCardTheme(key, theme)

    // ------------------------------------------------------------ budgets & fixed payments

    suspend fun setBudgets(limits: Map<Long, Long?>) = db.withTransaction {
        limits.forEach { (cat, v) -> if (v == null || v <= 0) dao.deleteBudget(cat) else dao.upsertBudgets(listOf(BudgetEntity(cat, v))) }
    }

    suspend fun saveFixedPayment(f: FixedPaymentEntity) { dao.upsertFixedPayment(f) }
    suspend fun deleteFixedPayment(id: Long) = dao.deleteFixedPayment(id)

    /** Records this month's payment as a typed transaction (so it counts in spending) and marks it paid. */
    suspend fun markFixedPaid(f: FixedPaymentEntity, ym: String, timestamp: Long = System.currentTimeMillis()) = db.withTransaction {
        val card = f.cardKey?.let { k -> dao.allCards().firstOrNull { it.cardKey == k } }
        dao.insertTxn(
            TransactionEntity(
                smsId = null, source = "MANUAL", timestamp = timestamp,
                bank = card?.bank ?: "Manual", cardLast4 = card?.last4, cardKey = card?.cardKey, merchant = f.name,
                amountMinor = f.amountMinor, currency = BankRules.BASE_CURRENCY, amountAedMinor = f.amountMinor, fxEstimated = false,
                type = TxnType.PURCHASE.name, merchantKey = CategoryRules.merchantKey(f.name),
                categoryId = f.categoryId ?: CategoryRules.guess(f.name, TxnType.PURCHASE), categoryUserSet = f.categoryId != null,
                note = "Fixed payment",
            ),
        )
        dao.upsertFixedPayment(f.copy(lastPaidYm = ym))
        Unit
    }

    suspend fun setCardType(key: String, type: CardType) =
        dao.setCardType(key, type.dbName(), Spending.defaultCountInSpending(type))
}
