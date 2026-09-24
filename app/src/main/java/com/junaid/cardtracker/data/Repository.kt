package com.junaid.cardtracker.data

import androidx.room.withTransaction
import com.junaid.cardtracker.core.IngestOutcome
import com.junaid.cardtracker.core.SmsKey
import com.junaid.cardtracker.core.Spending
import com.junaid.cardtracker.parser.AccountKind
import com.junaid.cardtracker.parser.BankRules
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.TxnType
import com.junaid.cardtracker.parser.ManualEntry
import com.junaid.cardtracker.parser.Money
import com.junaid.cardtracker.parser.ParseResult
import com.junaid.cardtracker.parser.SmsParser
import com.junaid.cardtracker.parser.CategoryRules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal

/** Shared by Sync and live listening: same parser, same de-duplication. */
class Repository(private val db: AppDatabase) {
    val dao = db.dao()
    private val lock = Mutex()

    /** Current AED rates (from the fx_rates table; defaults from BankRules.fxToAed). */
    @Volatile
    var rates: Map<String, BigDecimal> = BankRules.fxToAed
        private set

    /** New transactions from Sync / live SMS since the last drain, for alerts. Not filled by Re-parse. */
    private val fresh = mutableListOf<TransactionEntity>()
    @Volatile private var reparsing = false

    fun drainFresh(): List<TransactionEntity> = synchronized(fresh) { fresh.toList().also { fresh.clear() } }

    // ------------------------------------------------------------ setup

    /** Seeds categories and exchange rates, and categorises older transactions once. Safe to call every start. */
    suspend fun ensureDefaults() {
        if (dao.categoryCount() == 0) {
            dao.upsertCategories(CategoryRules.defaults.mapIndexed { i, c -> CategoryEntity(c.id, c.name, i) })
        }
        val stored = dao.allRates()
        val missing = BankRules.fxToAed.filterKeys { k -> stored.none { it.currency == k } }
        if (missing.isNotEmpty()) {
            dao.upsertRates(missing.map { (c, r) -> FxRateEntity(c, r.toPlainString(), System.currentTimeMillis()) })
        }
        loadRates()
        // Transactions created before categories existed (v0.4 and earlier).
        for (t in dao.txnsWithoutMerchantKey()) {
            val key = CategoryRules.merchantKey(t.merchant)
            val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
            val cat = if (t.categoryUserSet) t.categoryId else resolveCategory(null, key, t.merchant, type, t.amountMinor)
            dao.setMerchantKeyAndCategory(t.id, key, cat)
        }
    }

    private suspend fun loadRates() {
        rates = dao.allRates().mapNotNull { r -> r.rateToAed.toBigDecimalOrNull()?.let { r.currency to it } }.toMap() +
            (BankRules.BASE_CURRENCY to BigDecimal.ONE)
    }

    /** Your choice for this SMS > learned merchant rule > keyword guess. */
    private suspend fun resolveCategory(dedupKey: String?, merchantKey: String, merchant: String, type: TxnType, amountMinor: Long): Long? {
        if (!CategoryRules.canHaveCategory(type)) return null
        dedupKey?.let { dao.overrideFor(it) }?.let { return it.categoryId }
        // Learned rule: same text and amount for generic debits and transfers, otherwise the merchant.
        dao.ruleFor(CategoryRules.learningKey(merchant, amountMinor, type))?.let { return it }
        if (type == TxnType.TRANSFER_OUT) return null // transfers are only categorised when you choose it
        if (merchantKey.isNotEmpty()) dao.ruleFor(merchantKey)?.let { return it }
        return CategoryRules.guess(merchant, type)
    }

    suspend fun ingestSms(sender: String, body: String, receivedAt: Long, sentAt: Long?, source: String): IngestOutcome =
        lock.withLock {
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
                if (id == -1L) IngestOutcome.DUPLICATE else applyParse(id, dedupKey, parsed, receivedAt)
            }
        }

    /** Re-runs the current BankRules over every stored SMS (except dismissed ones). */
    suspend fun reparseAll(): Map<IngestOutcome, Int> = lock.withLock {
        val all = dao.smsForReparse()
        val counts = mutableMapOf<IngestOutcome, Int>()
        reparsing = true
        try { db.withTransaction {
            // Rebuild every SMS-based row from scratch, in time order, so two-SMS transfers pair up cleanly.
            // (Typed entries are untouched; card settings are kept.)
            dao.deleteAllSmsTxns()
            dao.deleteAllStatements()
            for (s in all) {
                val o = applyParse(s.id, s.dedupKey, SmsParser.parse(s.sender, s.body, s.receivedAt), s.receivedAt)
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

    /** Your own card that a transfer went to, or null (family / unknown destinations). */
    private suspend fun ownCardKeyFor(last4: String): String? {
        val known = BankRules.knownAccount(last4)
        if (known != null) {
            if (known.kind != AccountKind.OWN_CARD || known.bank == null) return null
            val key = cardKeyOf(known.bank, last4)
            ensureCard(key, known.bank, last4, CardType.CREDIT)
            return key
        }
        return dao.cardsByLast4(last4).singleOrNull { it.cardType == CardTypes.CREDIT }?.cardKey
    }

    private suspend fun applyTransaction(smsId: Long, dedupKey: String, r: ParseResult.Transaction): IngestOutcome {
        val t = r.txn
        val key = t.cardLast4?.let { cardKeyOf(t.bank, it) }
        if (key != null) ensureCard(key, t.bank, t.cardLast4, t.cardType)
        val aed = Money.toAedMinor(t.amount, t.currency, rates)
        val merchantKey = CategoryRules.merchantKey(t.merchant)
        val amountMinor = Money.toMinor(t.amount)
        val counterpartyKey = t.toLast4?.let { ownCardKeyFor(it) }
        val availMinor = t.availableLimit?.let { Money.toMinor(it) }

        // Two SMS for one transfer (e.g. FAB "Outward Remittance Debit" + "funds transfer processed"): merge.
        // Only a message that names the destination pairs with one that doesn't (never two transfers with each other).
        val rule = BankRules.ruleById(r.ruleId)
        val group = rule?.pairGroup
        // A generic "amount debited" SMS (PURCHASE, e.g. an EMI) can be the same money as a transfer SMS.
        if (rule != null && group != null && key != null && (t.type == TxnType.TRANSFER_OUT || t.type == TxnType.PURCHASE)) {
            val hasTo = rule.namesDestination
            val groupRules = (BankRules.banks.flatMap { it.rules } + BankRules.genericRules)
                .filter { it.pairGroup == group && it.namesDestination != hasTo }
                .map { it.id }
            // The remittance SMS carries only a date (possibly the next value date), so allow up to a day apart.
            val window = 24 * 60 * 60 * 1000L
            val other = dao.findTransferPair(key, amountMinor, groupRules, r.ruleId, t.timestamp - window, t.timestamp + window, t.timestamp)
            if (other != null) {
                dao.mergePair(
                    id = other.id,
                    smsId = smsId,
                    // Prefer the text that names the destination ("Payment to ENBD credit card ·9940").
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

        val entity =
            TransactionEntity(
                smsId = smsId, source = "SMS", timestamp = t.timestamp, bank = t.bank,
                cardLast4 = t.cardLast4, cardKey = key, merchant = t.merchant,
                amountMinor = amountMinor, currency = t.currency,
                amountAedMinor = aed?.first, fxEstimated = aed?.second ?: false,
                type = t.type.name, availableLimitMinor = availMinor,
                ruleId = r.ruleId, counterpartyKey = counterpartyKey,
                merchantKey = merchantKey,
                categoryId = resolveCategory(dedupKey, merchantKey, t.merchant, t.type, amountMinor),
                categoryUserSet = dao.overrideFor(dedupKey) != null,
            )
        dao.insertTxn(entity)
        if (!reparsing) synchronized(fresh) { fresh += entity }
        val note = if (aed == null) "No AED rate for ${t.currency}" else null
        dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, note)
        return IngestOutcome.TRANSACTION
    }

    private suspend fun applyParse(smsId: Long, dedupKey: String, r: ParseResult, receivedAt: Long): IngestOutcome {
        dao.deleteTxnsForSms(smsId)
        dao.deleteStatementsForSms(smsId)
        dao.unpairSms(smsId)
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
                dao.setSmsResult(smsId, SmsStatus.IGNORED, null, null, "Sender no longer in BankRules")
                IngestOutcome.NOT_BANK
            }
        }
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
        val id = dao.maxCategoryId() + 1
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

    /** Saves a rate and recalculates the AED amount of every transaction in that currency. */
    suspend fun setRate(currency: String, rate: BigDecimal) = lock.withLock {
        db.withTransaction {
            dao.upsertRates(listOf(FxRateEntity(currency.uppercase(), rate.toPlainString(), System.currentTimeMillis())))
            loadRates()
            for (t in dao.foreignTxns().filter { it.currency.equals(currency, true) }) {
                val aed = Money.toAedMinor(Money.fromMinor(t.amountMinor), t.currency, rates)
                dao.setAed(t.id, aed?.first)
            }
            Unit
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
