package com.uaefinancial.tracker.data

import com.uaefinancial.tracker.toJava
import com.uaefinancial.tracker.core.ReadingGuard
import com.uaefinancial.tracker.core.StandingGap
import com.uaefinancial.tracker.core.toDecimalOrNull
import com.uaefinancial.tracker.core.Decimal
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.core.SmsKey
import com.uaefinancial.tracker.core.Spending
import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.ParsedTransaction
import com.uaefinancial.tracker.parser.ParsedStatement
import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType
import com.uaefinancial.tracker.parser.ManualEntry
import com.uaefinancial.tracker.parser.Money
import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.CandidateScreen
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.SmartParser
import com.uaefinancial.tracker.parser.CategoryRules
import com.uaefinancial.tracker.parser.LearnedFormats
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by Sync and live listening: same parser, same de-duplication. */
const val MAX_OPEN_PER_SENDER = 15
const val MAX_OPEN_TOTAL = 200
const val CAND_OPEN = "OPEN"
const val CAND_ACCEPTED = "ACCEPTED"
const val CAND_DISMISSED = "DISMISSED"
const val CAND_MUTED = "MUTED"

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
        runCatching { reapplyAutoCategories() }
    }

    /**
     * When the built-in category keywords change (a new app version), looks once more at the purchases the app itself filed
     * under Other and moves those that now match a keyword. Never touches a category you set (or any row you confirmed),
     * and your learned merchant rules still come first. Runs only when the keyword set differs from the one last applied.
     */
    suspend fun reapplyAutoCategories() {
        val p = prefs ?: return
        val version = "k${CategoryRules.KEYWORDS_VERSION}-p${CategoryRules.packKeywordsVersion}"
        // Names still in Other are reported (names only, once each); this also covers messages that arrived before the report existed.
        for (t in dao.autoOtherTxns(CategoryRules.OTHER)) {
            val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
            onOtherMerchant?.invoke(t.merchant, type)
        }
        if (p.categoryKeywordsApplied == version) return
        for (t in dao.autoOtherTxns(CategoryRules.OTHER)) {
            val type = runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE)
            val key = t.merchantKey ?: CategoryRules.merchantKey(t.merchant)
            val cat = resolveCategory(null, key, t.merchant, type, t.amountMinor) ?: continue
            if (cat != CategoryRules.OTHER) dao.setAutoCategory(t.id, cat)
        }
        p.categoryKeywordsApplied = version
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

    /** Called with a shop name the app filed under Other and the transaction type (the names-only report). */
    var onOtherMerchant: ((merchant: String, type: TxnType) -> Unit)? = null

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
            val fixes = dao.allFixes().filter { it.dedupKey in keys && it.type != FIX_STATEMENT }.associateBy { it.dedupKey }
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
    var onShape: ((String, String?, String, String, String?) -> Unit)? = null

    /** True while the message just read is a standing-instruction message waiting for its account (not an unread format). */
    private var parkedStanding = false

    /** Offers the masked shape of every message waiting in Needs review (nothing is sent unless sharing is on). */
    suspend fun queueUnreadForLearning() {
        for (m in dao.failedSmsList()) if (m.ruleId != SI_RULE) onShape?.invoke(m.sender, m.bank, m.body, "unread", null)
        queueHiddenForLearning()
    }

    /**
     * A message hidden as "not a transaction" (advert, declined, informational...) that the reader would still read as a transaction
     * or a statement: the wording goes to the accuracy check (kind `hidden:<why>|reader=<TYPE>`; sharing ON; masked like any report) so
     * a wrongly hidden purchase is found and fixed with a tested change. It stays hidden meanwhile: it is NOT put in Needs review.
     * Messages that are already stored as ignored are checked once (the scan is remembered); new ones are checked as they arrive.
     */
    private fun hiddenKind(bank: String, body: String, receivedAt: Long, why: String?): String? {
        val r = runCatching { SmartParser.read(bank, SmsParser.normalizeBody(body), receivedAt) }.getOrNull()
        val what = when (r) { is ParseResult.Transaction -> r.txn.type.name; is ParseResult.Statement -> "STATEMENT"; else -> return null }
        return "hidden:${(why ?: "ignored").replace('|', '/').take(40)}|reader=$what"
    }

    suspend fun queueHiddenForLearning() {
        val version = "v1-e${SmsParser.ENGINE_VERSION}"
        val p = prefs ?: return
        if (p.hiddenScanDone == version) return
        for (m in dao.allSms()) {
            if (m.status != SmsStatus.IGNORED || m.ruleId != null) continue
            val bank = m.bank ?: continue
            hiddenKind(bank, m.body, m.receivedAt, m.note)?.let { onShape?.invoke(m.sender, bank, m.body, it, null) }
        }
        p.hiddenScanDone = version
    }

    suspend fun ingestSms(
        sender: String, body: String, receivedAt: Long, sentAt: Long?, source: String, deferResolve: Boolean = false,
    ): IngestOutcome =
        lock.withLock {
            ensureLearned()
            val bank = SmsParser.bankFor(sender) ?: run {
                // Not one of your banks. If it could be a financial message it is kept for your review (never posted).
                noteUnknown(sender, body, receivedAt, sentAt, source)
                return IngestOutcome.NOT_BANK
            }
            val parsed = SmsParser.parse(sender, body, receivedAt)
            if (parsed is ParseResult.Ignored && !parsed.store) return IngestOutcome.OTP_SKIPPED

            val hash = SmsKey.bodyHash(body)
            val sent = sentAt?.takeIf { it > 0 }
            // Safety net when one copy has no sent time (then the keys can't match exactly).
            val window = 10 * 60 * 1000L
            if (dao.countNearDuplicates(bank.name, hash, receivedAt - window, receivedAt + window, sent) > 0) {
                return IngestOutcome.DUPLICATE
            }
            // The same purchase as an SMS and as a bank-app notification is one transaction.
            if (parsed is ParseResult.Transaction) {
                val twins = dao.countCrossSourceTwins(
                    bank.name, Money.toMinor(parsed.txn.amount), parsed.txn.type.name,
                    receivedAt - window, receivedAt + window, if (source == SmsSource.NOTIF) 1 else 0,
                )
                if (twins > 0) return IngestOutcome.DUPLICATE
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
                if (id == -1L) IngestOutcome.DUPLICATE else {
                    parkedStanding = false
                    val o = applyParse(id, dedupKey, parsed, receivedAt, bank.name)
                    // A new balance may settle a waiting standing-instruction message (or this message may be one).
                    if (o != IngestOutcome.DUPLICATE && !deferResolve) resolveStanding()
                    if (o == IngestOutcome.FAILED && parkedStanding && dao.smsById(id)?.status == SmsStatus.TRANSACTION) IngestOutcome.TRANSACTION else o
                }
            }.also { outcome ->
                // Could not be read: offer its masked shape for learning (only if you allowed it). A standing-instruction
                // message waiting for its account is not unread: its format is known.
                if (outcome == IngestOutcome.FAILED && !parkedStanding) runCatching { onShape?.invoke(sender, bank.name, body, "unread", null) }
                if (outcome == IngestOutcome.IGNORED && parsed is ParseResult.Ignored) {
                    hiddenKind(bank.name, body, receivedAt, parsed.reason)?.let { k -> runCatching { onShape?.invoke(sender, bank.name, body, k, null) } }
                }
                // Read only by the generic reader (this bank has no verified rule for the wording): offer the masked shape
                // too, so every wording of a bank like Tabby reaches the review and can get its own verified rule.
                // When part of it fell back to a default (no merchant, no card), the record says which part, so the
                // review can see every wording the reader only half understood.
                if (outcome == IngestOutcome.TRANSACTION && (parsed as? ParseResult.Transaction)?.txn?.auto == true) {
                    val gaps = com.uaefinancial.tracker.core.ReadingGaps.of((parsed as ParseResult.Transaction).txn)
                    val kind = if (gaps.isEmpty()) "smart-read" else "incomplete:" + gaps.joinToString("+")
                    runCatching { onShape?.invoke(sender, bank.name, body, kind, (parsed as ParseResult.Transaction).txn.merchant) }
                }
            }
        }

    /** Runs [block] as one database transaction (a batch of messages saves in one go instead of one by one). */
    suspend fun <T> inBatch(block: suspend () -> T): T = db.withTransaction { block() }

    /** After a batch read with deferResolve: settles waiting standing-instruction messages once. */
    suspend fun finishBatch() = lock.withLock { db.withTransaction { resolveStanding() } }

    /**
     * Re-runs the current BankRules over every stored SMS (except dismissed ones).
     *
     * Nothing you already have is lost by a re-read: if a message was read before and the new reading differs in
     * amount, type or currency (or finds nothing), the old reading is put back and the change is held on the
     * Needs review list ([SmsReadingEntity]) for you to decide. [force] applies every change (used by "Use new").
     */
    suspend fun reparseAll(force: Boolean = false): Map<IngestOutcome, Int> = lock.withLock {
        ensureLearned()
        val all = dao.smsForReparse()
        val counts = mutableMapOf<IngestOutcome, Int>()
        reparsing = true
        pendingOtp.clear()
        try { db.withTransaction {
            val oldTxns = dao.allTxns().filter { it.smsId != null && !it.inferred }.groupBy { it.smsId!! }
            val oldStmts = dao.allStatements().filter { it.smsId > 0 }.groupBy { it.smsId }
            // Rebuild every SMS-based row from scratch, in time order, so two-SMS transfers pair up cleanly.
            // (Typed entries are untouched; card settings are kept.)
            dao.deleteAllSmsTxns()
            dao.deleteAllStatements()
            for (s in all) {
                val o = applyParse(s.id, s.dedupKey, SmsParser.parse(s.sender, s.body, s.receivedAt), s.receivedAt, s.bank)
                counts[o] = (counts[o] ?: 0) + 1
            }
            if (!force) guardChanges(all, oldTxns, oldStmts)
            // OTP messages are only deleted when nothing was ever read from them.
            for (id in pendingOtp) if (dao.txnsForSmsNow(id).isEmpty() && dao.statementsForSmsNow(id).isEmpty()) dao.deleteSms(id)
            pendingOtp.clear()
            resolveStanding()
        } } finally { reparsing = false }
        counts
    }

    private val pendingOtp = mutableSetOf<Long>()

    private fun linesOf(t: List<TransactionEntity>, st: List<StatementEntity>) =
        t.map {
            ReadingGuard.Line("TXN", it.type, it.amountMinor, it.currency, it.timestamp, it.bank, it.cardKey.orEmpty(), it.cardLast4.orEmpty(), it.merchant)
        } + st.map {
            ReadingGuard.Line("STATEMENT", "STATEMENT", it.balanceMinor, it.currency, it.receivedAt, it.bank, it.cardKey, it.cardLast4.orEmpty(), "")
        }

    /**
     * Puts back the old reading of any message whose new reading changes money figures, and holds the change.
     * Two messages that describe one transaction (a standing instruction and its debit, an SMS and a notification)
     * are judged together, because which of them owns the row can change between reads.
     */
    private suspend fun guardChanges(
        all: List<SmsEntity>, oldTxns: Map<Long, List<TransactionEntity>>, oldStmts: Map<Long, List<StatementEntity>>,
    ) {
        val now = System.currentTimeMillis()
        val newTxns = dao.allTxns().filter { it.smsId != null && !it.inferred }.groupBy { it.smsId!! }
        val newStmts = dao.allStatements().filter { it.smsId > 0 }.groupBy { it.smsId }
        // Groups of messages tied together by pairing, in the old or the new reading.
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long { var r = x; while ((parent[r] ?: r) != r) r = parent[r]!!; return r }
        fun union(a: Long, b: Long) { parent[find(a)] = find(b) }
        for (t in oldTxns.values.flatten() + newTxns.values.flatten()) {
            val owner = t.smsId ?: continue
            parent.putIfAbsent(owner, owner)
            t.pairedSmsId?.let { parent.putIfAbsent(it, it); union(owner, it) }
        }
        val byId = all.associateBy { it.id }
        val groups = (oldTxns.keys + oldStmts.keys + newTxns.keys + newStmts.keys).filter { it in byId }.groupBy { find(it) }
        for ((_, ids) in groups) {
            val ot = ids.flatMap { oldTxns[it].orEmpty() }; val os = ids.flatMap { oldStmts[it].orEmpty() }
            val before = linesOf(ot, os)
            if (before.isEmpty()) continue
            val nt = ids.flatMap { newTxns[it].orEmpty() }; val ns = ids.flatMap { newStmts[it].orEmpty() }
            val after = linesOf(nt, ns)
            if (!ReadingGuard.isMaterial(before, after)) continue
            // Put the old reading back exactly as it was, for every message in the group.
            for (id in ids) { dao.deleteTxnsForSms(id); dao.deleteStatementsForSms(id) }
            ot.forEach { dao.restoreTxn(it) }
            os.forEach { dao.restoreStatement(it) }
            for (id in ids) {
                val s = byId[id] ?: continue
                dao.setSmsResult(s.id, s.status, s.bank, s.ruleId, s.note)
                dao.clearReadings(s.id)
                pendingOtp.remove(s.id)
            }
            val first = ids.minOrNull() ?: continue
            dao.insertReading(
                SmsReadingEntity(
                    smsId = first, engineVersion = SmsParser.ENGINE_VERSION, createdAt = now,
                    keptSummary = ReadingGuard.describe(before),
                    newSummary = ReadingGuard.describe(after) + ReadingGuard.changedFields(before, after).let { if (it.isEmpty()) "" else " (changes: " + it.joinToString(", ") + ")" },
                ),
            )
        }
    }

    /**
     * Learning record for your choice on a held message: "choice:keep-mine" tells the weekly review the newer reading was
     * wrong for this bank, "choice:use-new" that it was right. Only the fields that differed (amount, type, currency) and the
     * masked shape are shared, and only if you allowed sharing.
     */
    private suspend fun shareReadingChoice(s: SmsEntity, choice: String) {
        val held = dao.heldReadingsNow().firstOrNull { it.smsId == s.id } ?: return
        val changes = Regex("""\(changes: ([^)]*)\)""").find(held.newSummary)?.groupValues?.get(1)?.replace(", ", ",")?.replace(" ", "-")?.take(60) ?: "unknown"
        runCatching { onShape?.invoke(s.sender, s.bank, s.body, "choice:$choice|changes=$changes", null) }
    }

    /** "Use new": applies the newer reading of one held message. */
    suspend fun useNewReading(smsId: Long): Boolean = lock.withLock {
        val s = dao.smsById(smsId) ?: return@withLock false
        shareReadingChoice(s, "use-new")
        db.withTransaction {
            val o = applyParse(s.id, s.dedupKey, SmsParser.parse(s.sender, s.body, s.receivedAt), s.receivedAt, s.bank)
            if (s.id in pendingOtp && dao.txnsForSmsNow(s.id).isEmpty()) dao.deleteSms(s.id)
            pendingOtp.remove(s.id)
            dao.clearReadings(smsId)
            o
        }
        true
    }

    /** "Keep mine": the reading you have now is saved as your own, so later re-reads never replace it. */
    suspend fun keepCurrentReading(smsId: Long): Boolean = lock.withLock {
        val s = dao.smsById(smsId) ?: return@withLock false
        shareReadingChoice(s, "keep-mine")
        val t = dao.txnsForSmsNow(smsId).firstOrNull()
        val cardType = t?.cardKey?.let { k -> dao.allCards().firstOrNull { it.cardKey == k }?.cardType } ?: CardTypes.CREDIT
        db.withTransaction {
            if (t != null) {
                dao.upsertFixes(
                    listOf(
                        SmsFixEntity(
                            dedupKey = s.dedupKey, type = t.type, amountMinor = t.amountMinor, currency = t.currency,
                            merchant = t.merchant, cardLast4 = t.cardLast4, cardType = cardType, timestamp = t.timestamp,
                        ),
                    ),
                )
            }
            dao.clearReadings(smsId)
        }
        true
    }

    fun heldReadings() = dao.heldReadings()

    // ------------------------------------------------------------ messages from unknown senders (Phase 1d)

    fun openCandidates() = dao.openCandidates()

    fun mutedSenders() = dao.mutedSenders()

    /**
     * Looks at a message from a sender that is not one of your banks. If it could be financial (an amount, not a
     * one-time code) it is kept as a candidate for your review; it is never posted to your ledger from here.
     * Returns true when kept. Open candidates are never removed automatically.
     */
    suspend fun noteUnknown(sender: String, body: String, receivedAt: Long, sentAt: Long?, source: String): Boolean {
        // A bank you added or unticked is not "unknown": unticked ones were your choice.
        if (SmsParser.anyBankFor(sender) != null) return false
        val screened = CandidateScreen.screen(sender, body, receivedAt) ?: return false
        val senderKey = SmsParser.senderKey(sender)
        if (dao.senderMutedCount(senderKey) > 0) return false
        // A safety cap, so a flood can never bury Needs review: a few per sender, a few hundred in all.
        if (dao.openCandidateCountFor(senderKey) >= MAX_OPEN_PER_SENDER || dao.openCandidateCount() >= MAX_OPEN_TOTAL) return false
        val sent = sentAt?.takeIf { it > 0 }
        val key = SmsKey.of(sender, sent, receivedAt, body)
        if (dao.candidateKnown(key) > 0) return false
        return dao.insertCandidate(
            SmsCandidateEntity(
                dedupKey = key, sender = sender.trim(), senderKey = senderKey, body = body,
                receivedAt = receivedAt, sentAt = sent, source = source,
                kind = screened.kind.name, reading = screened.reading, shapeKey = screened.shapeKey,
                createdAt = System.currentTimeMillis(), state = CAND_OPEN,
            ),
        ) != -1L
    }

    /**
     * "This sender is my bank": adds the sender, then reads every waiting message from it like any other message.
     * Anything the reader cannot read lands in Needs review as usual. Returns how many messages were read.
     */
    suspend fun acceptSender(sender: String, bankName: String): Int {
        val key = SmsParser.senderKey(sender)
        val waiting = dao.openCandidatesFor(key)
        addSender(sender, bankName)
        db.withTransaction {
            for (c in waiting) {
                runCatching { ingestSms(c.sender, c.body, c.receivedAt, c.sentAt, c.source, deferResolve = true) }
                dao.closeCandidate(c.id, CAND_ACCEPTED)
            }
        }
        finishBatch()
        return waiting.size
    }

    /**
     * After the signed rules file starts tracking a sender (or at startup): waiting candidates from a sender that is now
     * a known bank are read like any other bank message. Returns how many were read. Nothing is guessed: the sender
     * only counts when the owner-approved, signed rules file names it.
     */
    suspend fun adoptTrackedCandidates(): Int {
        val waiting = dao.openCandidatesNow().filter { SmsParser.bankFor(it.sender) != null }
        if (waiting.isEmpty()) return 0
        db.withTransaction {
            for (c in waiting) {
                runCatching { ingestSms(c.sender, c.body, c.receivedAt, c.sentAt, c.source, deferResolve = true) }
                dao.closeCandidate(c.id, CAND_ACCEPTED)
            }
        }
        finishBatch()
        return waiting.size
    }

    /** A waiting message body from [sender], if one was kept (for the optional share). */
    suspend fun sampleFromCandidates(sender: String): String? =
        dao.openCandidatesFor(SmsParser.senderKey(sender)).firstOrNull()?.body

    /** "Not financial": this one message is dropped (its text is cleared, only its key stays so it isn't offered again). */
    suspend fun dismissCandidate(id: Long) = dao.closeCandidate(id, CAND_DISMISSED)

    /** "Never show this sender": its waiting messages are dropped and later ones are not kept. Can be undone with [unmuteSender]. */
    suspend fun muteSender(sender: String) {
        val key = SmsParser.senderKey(sender)
        db.withTransaction {
            dao.closeCandidatesFor(key, CAND_DISMISSED)
            dao.insertCandidate(
                SmsCandidateEntity(
                    dedupKey = "MUTE|$key", sender = sender.trim(), senderKey = key, body = "",
                    receivedAt = 0, sentAt = null, source = "", kind = "MUTED", reading = null, shapeKey = "",
                    createdAt = System.currentTimeMillis(), state = CAND_MUTED,
                ),
            )
        }
    }

    suspend fun unmuteSender(senderKey: String) = dao.deleteMute(senderKey)

    /** Senders listed at setup that you left unticked: never offered again (same as "Hide this sender"). */
    suspend fun declineSenders(senders: List<String>) { for (s in senders) if (dao.senderMutedCount(SmsParser.senderKey(s)) == 0) muteSender(s) }

    private fun CardType.dbName() = when (this) {
        CardType.CREDIT -> CardTypes.CREDIT
        CardType.DEBIT -> CardTypes.DEBIT
        CardType.ACCOUNT -> CardTypes.ACCOUNT
    }

    /** The card a key stands for after merges ("these two are one card"). */
    suspend fun canonicalKey(key: String): String {
        var k = key
        val aliases = dao.cardAliases().associate { it.fromKey to it.toKey }
        var guard = 0
        while (guard++ < 10) k = aliases[k] ?: break
        return k
    }

    /**
     * Merges card [from] into [to]: its transactions, statements, plans and fixed payments move over, the card is
     * removed, and later messages that would create [from] go to [to]. Returns false if the cards can't be merged.
     * Totals don't change unless the two cards were counted differently (then [to] decides).
     */
    suspend fun mergeCards(from: String, to: String): Boolean = db.withTransaction {
        if (from == to) return@withTransaction false
        val cards = dao.allCards()
        if (cards.none { it.cardKey == from } || cards.none { it.cardKey == to }) return@withTransaction false
        dao.moveTransactions(from, to)
        dao.moveCounterparty(from, to)
        dao.moveStatements(from, to)
        dao.movePlans(from, to)
        dao.moveFixedPayments(from, to)
        dao.repointAliases(from, to)
        dao.insertCardAlias(CardAliasEntity(from, to))
        dao.deleteCard(from)
        true
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

    /** Where a standing-instruction message goes when no debit message explains it: found from the balance gap. */
    private class Placement(val cardKey: String, val last4: String?, val timestamp: Long, val residualMinor: Long, val notify: Boolean)

    private suspend fun applyTransaction(
        smsId: Long, dedupKey: String, r: ParseResult.Transaction,
        placement: Placement? = null, pairOnly: Boolean = false,
    ): IngestOutcome {
        var t = r.txn
        // A reference number read as an amount: never let one message break a Sync or a re-read.
        if (t.amount.abs() >= Decimal("100000000000")) {
            dao.setSmsResult(smsId, SmsStatus.FAILED, t.bank, r.ruleId, "The amount looked wrong")
            return IngestOutcome.FAILED
        }
        // The SMS doesn't name the account (e.g. some bill payments): use your only account at that bank.
        if (placement != null) {
            t = t.copy(cardLast4 = placement.last4, cardType = CardType.ACCOUNT, timestamp = placement.timestamp)
        } else if (t.cardLast4 == null && t.accountNotNamed) {
            dao.cardsOfBank(t.bank, CardTypes.ACCOUNT).singleOrNull()?.let { t = t.copy(cardLast4 = it.last4, cardType = CardType.ACCOUNT) }
        }
        // No pattern found a merchant (it would read "Card purchase"): the small on-device reader may fill it, only with words that
        // are in the message, and the row is marked "Check this" so you confirm it. Never for a rule-read or fixed transaction.
        var guessedMerchant = false
        if (r.ruleId != FIX_RULE && t.auto && t.type == TxnType.PURCHASE &&
            t.merchant in com.uaefinancial.tracker.core.ReadingGaps.placeholderMerchants && prefs?.aiMerchantFill != false
        ) {
            dao.smsBody(smsId)?.let { b ->
                com.uaefinancial.tracker.intel.MessageIntel.merchantFallback(b)?.let { m -> t = t.copy(merchant = m); guessedMerchant = true }
            }
        }
        val amountForPair = Money.toMinor(t.amount)
        // "Standing instructions ... processed" does not name the account. It belongs with a debit message for the same
        // amount if exactly one account has one; otherwise it waits (Needs review) until the balances place it.
        if (r.ruleId == SI_RULE && placement == null) {
            val day = 24 * 60 * 60 * 1000L
            val groupRules = BankRules.banks.flatMap { it.rules }.filter { it.pairGroup == "fab-transfer" && !it.namesDestination }.map { it.id }
            val keys = dao.pairCandidates(t.bank, amountForPair, groupRules, r.ruleId, t.timestamp - day, t.timestamp + day, t.timestamp)
                .mapNotNull { it.cardKey }.distinct()
            if (keys.size == 1) {
                val c = dao.allCards().firstOrNull { it.cardKey == keys[0] }
                t = t.copy(cardLast4 = c?.last4, cardType = CardType.ACCOUNT)
            } else if (pairOnly) {
                dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, "Already counted from the debit message")
                return IngestOutcome.DUPLICATE
            } else {
                dao.setSmsResult(smsId, SmsStatus.FAILED, t.bank, r.ruleId, SI_PENDING_NOTE)
                parkedStanding = true
                return IngestOutcome.FAILED
            }
        }
        // A card payment that doesn't say which card ("You have made a credit repayment of AED 80.74"): your only
        // credit card at that bank, if you have exactly one.
        if (t.cardLast4 == null && t.type == TxnType.PAYMENT && t.cardType == CardType.CREDIT) {
            dao.cardsOfBank(t.bank, CardTypes.CREDIT).singleOrNull()?.last4?.let { t = t.copy(cardLast4 = it) }
        }
        // No card/account number: bank-account money goes on a "Bank ·????" account (not counted as spending by
        // default); anything else has no card, like a typed entry.
        // If the message doesn't name the account but you have exactly one account at that bank, it is that one
        // (no second "Bank ·????" account).
        if (t.cardLast4 == null && (t.accountNotNamed || t.cardType == CardType.ACCOUNT)) {
            dao.cardsOfBank(t.bank, CardTypes.ACCOUNT).singleOrNull { it.last4 != null }?.last4?.let { t = t.copy(cardLast4 = it) }
        }
        val rawKey = when {
            t.cardLast4 != null -> cardKeyOf(t.bank, t.cardLast4)
            t.accountNotNamed || t.cardType == CardType.ACCOUNT -> cardKeyOf(t.bank, null)
            else -> null
        }
        val key = rawKey?.let { canonicalKey(it) }
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
        if (rule != null && group != null && key != null && placement == null && (t.type == TxnType.TRANSFER_OUT || t.type == TxnType.PURCHASE)) {
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
            // The real debit message for a transfer that was placed from its standing-instruction message: it replaces
            // that row (never both).
            if (!hasTo && placement == null) {
                val inferred = dao.findInferred(key, amountMinor, t.timestamp - 3 * window, t.timestamp + 3 * window, t.timestamp)
                if (inferred != null) {
                    // Same time as when the two messages pair up the other way round: the standing-instruction message's own
                    // time (so a re-read of everything ends exactly where the live message did).
                    val siTime = inferred.smsId?.let { dao.smsById(it) }?.let { m ->
                        (SmsParser.parse(m.sender, m.body, m.receivedAt) as? ParseResult.Transaction)?.txn?.timestamp
                    } ?: inferred.timestamp
                    dao.replaceInferred(inferred.id, smsId, siTime, availMinor)
                    dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, "Same transfer as transaction #${inferred.id} (replaces the one placed from the standing-instruction message)")
                    return IngestOutcome.MERGED
                }
            }
        }

        val override = dao.overrideFor(dedupKey)
        // Check: the amount read must be written in the message (user fixes are checked by the user).
        val amountNote = if (r.ruleId == FIX_RULE) null else dao.smsBody(smsId)?.let { b ->
            if (com.uaefinancial.tracker.core.Checks.amountAppearsIn(SmsParser.normalizeBody(b), amountMinor)) null
            else "The amount (${t.currency} ${Money.fromMinor(amountMinor).toPlainString()}) isn't written in the message: check it."
        }
        // Completeness: a part of the message that fell back to a default (no merchant, no card) is marked, never hidden.
        val gaps = if (r.ruleId == FIX_RULE) emptyList()
        else (if (guessedMerchant) listOf("merchant-guess") else emptyList()) + com.uaefinancial.tracker.core.ReadingGaps.of(t)
        val checkNote = amountNote ?: if (gaps.isNotEmpty()) "gap:" + gaps.joinToString(",") else null
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
                checkNote = checkNote,
                inferred = placement != null,
            )
        dao.insertTxn(entity)
        if (entity.categoryId == CategoryRules.OTHER && !entity.categoryUserSet && !reparsing) onOtherMerchant?.invoke(entity.merchant, t.type)
        if (!reparsing && (placement == null || placement.notify)) synchronized(fresh) { fresh += entity }
        val note = if (aed == null) "No exchange rate for ${t.currency}: add one in More → Exchange rates" else null
        dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, note ?: if (placement != null) SI_PLACED_NOTE else null)
        return IngestOutcome.TRANSACTION
    }

    /**
     * Places every waiting "standing instructions processed" message (see core/StandingGap) from the balances of the
     * bank's accounts. Safe to run any number of times: it first takes back what it placed before and decides again from
     * the saved messages, so the result depends only on what is stored. A real debit message that has arrived since
     * has already replaced its row (that row is no longer marked as placed).
     */
    private suspend fun resolveStanding() {
        val pending = dao.pendingStanding(SI_RULE, SI_PENDING_NOTE_PREFIX)
        val placedBefore = dao.inferredTxns().mapNotNull { it.smsId }.toSet()
        val ids = (pending.map { it.id } + placedBefore).distinct()
        if (ids.isEmpty()) return
        dao.deleteInferredFor(ids)
        class Item(val sms: SmsEntity, val parsed: ParseResult.Transaction)
        val items = ids.mapNotNull { id ->
            val sms = dao.smsById(id) ?: return@mapNotNull null
            val p = SmsParser.parse(sms.sender, sms.body, sms.receivedAt) as? ParseResult.Transaction ?: return@mapNotNull null
            if (p.ruleId != SI_RULE) null else Item(sms, p)
        }
        for ((group, list) in items.groupBy { it.parsed.txn.bank to it.parsed.txn.currency }) {
            val accounts = dao.accountTxns(group.first, group.second).filter { it.cardKey != null }.groupBy { it.cardKey!! }
                .map { (k, v) -> StandingGap.Account(k, v.map { StandingGap.Entry(it.id, it.timestamp, it.type, it.amountMinor, it.availableLimitMinor) }) }
            val waiting = list.map { StandingGap.Standing(it.sms.id, it.parsed.txn.timestamp, Money.toMinor(it.parsed.txn.amount)) }
            // Placed and already-counted ones first, so a payee's history is complete before a waiting one is judged.
            val outcomes = StandingGap.resolve(accounts, waiting).sortedBy { it is StandingGap.Outcome.Unplaced }
            for (o in outcomes) {
                val item = list.first { it.sms.id == o.standingId }
                val sms = item.sms
                when (o) {
                    is StandingGap.Outcome.AlreadyCounted -> applyTransaction(sms.id, sms.dedupKey, item.parsed, pairOnly = true)
                    is StandingGap.Outcome.Place -> {
                        val last4 = dao.allCards().firstOrNull { it.cardKey == o.accountKey }?.last4
                        applyTransaction(
                            sms.id, sms.dedupKey, item.parsed,
                            placement = Placement(o.accountKey, last4, o.timestamp, o.residualMinor, notify = sms.id !in placedBefore),
                        )
                    }
                    is StandingGap.Outcome.Unplaced -> {
                        // A standing instruction repeats from the same account: if at least two earlier ones to the same payee were
                        // settled (by a debit message or by the balances) and all came from one account, this one did too.
                        val same = recurringAccount(item.sms, item.parsed.txn, group.first, group.second)
                        if (same != null) {
                            val last4 = dao.allCards().firstOrNull { it.cardKey == same }?.last4
                            applyTransaction(
                                sms.id, sms.dedupKey, item.parsed,
                                placement = Placement(same, last4, item.parsed.txn.timestamp, 0L, notify = sms.id !in placedBefore),
                            )
                            dao.setSmsResult(sms.id, SmsStatus.TRANSACTION, group.first, SI_RULE, SI_RECURRING_NOTE)
                        } else {
                            val t = item.parsed.txn
                            val what = "${t.currency} ${t.amount.toPlainString()} to ${t.merchant}"
                            val why = if (o.why == StandingGap.Why.AMBIGUOUS) "the messages fit more than one account, so the app can't tell which."
                            else "no account's balances show it yet. It is placed automatically if a later message settles it."
                            dao.setSmsResult(sms.id, SmsStatus.FAILED, t.bank, SI_RULE, "$SI_PENDING_NOTE_PREFIX ($what): $why")
                        }
                    }
                }
            }
        }
    }

    /** The one account that paid at least two earlier, settled standing instructions to the same payee; null if none or if they disagree. */
    private suspend fun recurringAccount(self: SmsEntity, txn: com.uaefinancial.tracker.parser.ParsedTransaction, bank: String, currency: String): String? {
        val counts = HashMap<String, Int>()
        for (m in dao.smsByRuleDone(SI_RULE)) {
            if (m.id == self.id || m.note == SI_RECURRING_NOTE) continue
            val p = SmsParser.parse(m.sender, m.body, m.receivedAt) as? ParseResult.Transaction ?: continue
            if (p.txn.bank != bank || p.txn.currency != currency || p.txn.merchant != txn.merchant || p.txn.toLast4 != txn.toLast4) continue
            val key = dao.txnsForSmsNow(m.id).firstOrNull()?.cardKey ?: continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.keys.singleOrNull()?.takeIf { counts.values.sum() >= 2 }
    }

    private suspend fun applyParse(smsId: Long, dedupKey: String, r: ParseResult, receivedAt: Long, bank: String?): IngestOutcome {
        dao.deleteTxnsForSms(smsId)
        dao.deleteStatementsForSms(smsId)
        dao.unpairSms(smsId)
        // Your own reading of this SMS (Needs review → Fix) beats anything the app works out.
        dao.fixFor(dedupKey)?.let { fix -> return applyFix(smsId, dedupKey, fix, bank ?: "Bank", r) }
        return when (r) {
            is ParseResult.Transaction -> applyTransaction(smsId, dedupKey, r)
            is ParseResult.Statement -> storeStatement(smsId, receivedAt, r.statement, r.ruleId)
            is ParseResult.Ignored -> {
                if (!r.store) {
                    // An old SMS that a newer rule now recognises as OTP. During a re-read it is only deleted at the
                    // end, and only if nothing had been read from it (a stored transaction is never deleted by this).
                    if (reparsing) { dao.setSmsResult(smsId, SmsStatus.IGNORED, r.bank, null, r.reason); pendingOtp += smsId }
                    else dao.deleteSms(smsId)
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

    /** Saves a statement (read by the app or fixed by you) and marks the message as a statement. */
    private suspend fun storeStatement(smsId: Long, receivedAt: Long, s: ParsedStatement, ruleId: String?): IngestOutcome {
        val key = canonicalKey(cardKeyOf(s.bank, s.cardLast4))
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
        dao.setSmsResult(smsId, SmsStatus.STATEMENT, s.bank, ruleId, null)
        return IngestOutcome.STATEMENT
    }

    private suspend fun applyFix(smsId: Long, dedupKey: String, fix: SmsFixEntity, bank: String, reading: ParseResult? = null): IngestOutcome {
        if (fix.type == FIX_IGNORE) {
            dao.setSmsResult(smsId, SmsStatus.IGNORED, bank, null, "You marked this as not a transaction")
            return IngestOutcome.IGNORED
        }
        if (fix.type == FIX_STATEMENT) {
            // Your own statement reading: the fields are packed in "merchant" ("stmt-fix;min=<minor>;due=<epoch day>;stmt=<epoch day>").
            val f = fix.merchant.split(';').drop(1).mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
            val due = f["due"]?.toLongOrNull()
            if (due == null) {
                dao.setSmsResult(smsId, SmsStatus.FAILED, bank, null, "The statement fix could not be read")
                return IngestOutcome.FAILED
            }
            val st = ParsedStatement(
                bank = bank, cardLast4 = fix.cardLast4, cardType = CardType.CREDIT, statementBalance = Money.fromMinor(fix.amountMinor),
                minimumDue = f["min"]?.toLongOrNull()?.let { Money.fromMinor(it) }, currency = fix.currency,
                dueDate = CalendarDate.ofEpochDay(due), statementDate = f["stmt"]?.toLongOrNull()?.let { CalendarDate.ofEpochDay(it) }, auto = false,
            )
            return storeStatement(smsId, fix.timestamp, st, FIX_RULE)
        }
        val type = runCatching { TxnType.valueOf(fix.type) }.getOrDefault(TxnType.PURCHASE)
        val cardType = runCatching { CardType.valueOf(fix.cardType) }.getOrDefault(CardType.CREDIT)
        val whole = ParsedTransaction(
            bank = bank, cardLast4 = fix.cardLast4, cardType = cardType, merchant = fix.merchant,
            amount = Money.fromMinor(fix.amountMinor), currency = fix.currency, type = type,
            timestamp = fix.timestamp, dateFromSms = true,
        )
        // Only the fields you confirmed are yours; the rest follow the current reading of the message.
        val locked = fix.lockedFields.split(',').map { it.trim() }.toSet()
        val read = (reading as? ParseResult.Transaction)?.txn
        val txn = if ("ALL" in locked || read == null) whole else read.copy(
            type = if ("TYPE" in locked) whole.type else read.type,
            amount = if ("AMOUNT" in locked) whole.amount else read.amount,
            currency = if ("CURRENCY" in locked) whole.currency else read.currency,
            merchant = if ("MERCHANT" in locked) whole.merchant else read.merchant,
            cardLast4 = if ("CARD" in locked) whole.cardLast4 else read.cardLast4,
            cardType = if ("CARD" in locked) whole.cardType else read.cardType,
            timestamp = if ("TIME" in locked) whole.timestamp else read.timestamp,
            dateFromSms = if ("TIME" in locked) true else read.dateFromSms,
        )
        return applyTransaction(smsId, dedupKey, ParseResult.Transaction(txn, FIX_RULE))
    }

    /**
     * The fields you changed from what the reader says; only these are protected. If the reader has no reading, or
     * you say "not a transaction", or you changed nothing (you confirmed it), the whole reading is yours ("ALL").
     */
    private fun lockedFieldsFor(
        sms: SmsEntity, type: TxnType?, amountMinor: Long, currency: String, merchant: String,
        cardLast4: String?, cardType: CardType, timestamp: Long,
    ): String {
        val read = (SmsParser.parse(sms.sender, sms.body, sms.receivedAt) as? ParseResult.Transaction)?.txn
        if (type == null || read == null) return "ALL"
        val d = mutableListOf<String>()
        if (read.type != type) d += "TYPE"
        if (Money.toMinor(read.amount) != amountMinor) d += "AMOUNT"
        if (!read.currency.equals(currency, ignoreCase = true)) d += "CURRENCY"
        if (!read.merchant.trim().equals(merchant.trim(), ignoreCase = true)) d += "MERCHANT"
        if (read.cardLast4 != cardLast4?.trim()?.takeIf { it.isNotEmpty() } || read.cardType != cardType) d += "CARD"
        if (read.timestamp != timestamp) d += "TIME"
        return if (d.isEmpty()) "ALL" else d.joinToString(",")
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
        val locked = lockedFieldsFor(sms, type, amountMinor, currency, merchant, cardLast4, cardType, timestamp)
        // What the app read before your fix, for the learning record ("unread", or the type it had guessed).
        val wasRead = runCatching { SmsParser.parse(sms.sender, sms.body, sms.receivedAt) }.getOrNull()
            .let { r -> if (r is ParseResult.Transaction) r.txn.type.name else if (r is ParseResult.Statement) "STATEMENT" else "unread" }
        val outcome = db.withTransaction {
            dao.upsertFixes(
                listOf(
                    SmsFixEntity(
                        dedupKey = sms.dedupKey, type = type?.name ?: FIX_IGNORE, amountMinor = amountMinor,
                        currency = currency.uppercase(), merchant = merchant.trim().ifEmpty { "Transaction" },
                        cardLast4 = cardLast4?.trim()?.takeIf { it.isNotEmpty() }, cardType = cardType.name, timestamp = timestamp,
                        lockedFields = locked,
                    ),
                ),
            )
            applyParse(sms.id, sms.dedupKey, SmsParser.parse(sms.sender, sms.body, sms.receivedAt), sms.receivedAt, sms.bank ?: sms.sender)
        }
        // A fix you made teaches the app: offer the masked shape with what you said it is (only if you allowed it).
        runCatching { onShape?.invoke(sms.sender, sms.bank, sms.body, "fixed:" + (type?.name ?: "NOT_A_TRANSACTION") + "|was=" + wasRead + "|card=" + cardType.name, merchant) }
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

    /**
     * Saves your reading of an SMS as a card statement / payment due notice. Kept like any other fix (survives re-reads and
     * backups); it is never learned for "similar messages" (a new bank wording goes to the rules file instead).
     */
    /** The masked message export (see learn/MaskedExport): one row per stored message, with what the app decided it was. */
    suspend fun maskedExportText(): String {
        val cardTypes = dao.allCards().associate { it.cardKey to it.cardType }
        val rows = ArrayList<com.uaefinancial.tracker.learn.MaskedExport.Row>()
        var unsafe = 0; var otp = 0
        for (sms in dao.allSms()) {
            if (sms.status == SmsStatus.IGNORED && sms.ruleId?.contains("otp", ignoreCase = true) == true) { otp++; continue }
            val bank = com.uaefinancial.tracker.learn.Learning.publicBankName(sms.sender, sms.bank)
            val txn = dao.txnsForSmsNow(sms.id).firstOrNull()
            // Local file only (never sent): every ordinary word stays, so a shape shows what decided the reading. Amounts, card and
            // account numbers, other digits, dates, links, emails and the merchant name are replaced.
            val m = com.uaefinancial.tracker.parser.Masker.mask(sms.body, bank, merchant = txn?.merchant, keepWords = true)
            if (!m.safe) { unsafe++; continue }
            val fixed = dao.fixFor(sms.dedupKey) != null
            val stmt = if (txn == null) dao.statementsForSmsNow(sms.id).firstOrNull() else null
            val kind = when {
                txn != null -> txn.type
                stmt != null -> "STATEMENT"
                sms.status == SmsStatus.IGNORED -> "NOT_TXN"
                // Counted as part of another message's transaction (the debit message and the standing-instruction message of one transfer).
                sms.status == SmsStatus.TRANSACTION -> "MERGED"
                else -> "UNREAD"
            }
            val instrument = when {
                txn != null -> cardTypes[txn.cardKey] ?: "unknown"
                stmt != null -> cardTypes[stmt.cardKey] ?: "unknown"
                else -> "unknown"
            }
            val label = bank ?: com.uaefinancial.tracker.learn.Learning.publicSender(sms.sender)?.let { "Other ($it)" } ?: "Other"
            // The reader's answers on the REAL text, computed here so a measurement never has to rebuild a message from its shape.
            fun answer(r: com.uaefinancial.tracker.parser.ParseResult?): String = when (r) {
                null -> "error"
                is com.uaefinancial.tracker.parser.ParseResult.Transaction -> r.txn.type.name
                is com.uaefinancial.tracker.parser.ParseResult.Statement -> "STATEMENT"
                else -> "none"
            }
            val now = answer(runCatching { SmsParser.parse(sms.sender, sms.body, sms.receivedAt) }.getOrNull())
            val generic = answer(runCatching { SmsParser.parseAsBank("Unknown Test Bank", sms.body, sms.receivedAt) }.getOrNull())
            rows += com.uaefinancial.tracker.learn.MaskedExport.Row(
                m.shape, label, kind, com.uaefinancial.tracker.learn.MaskedExport.directionOf(kind), instrument,
                com.uaefinancial.tracker.learn.MaskedExport.sourceOf(sms.ruleId, fixed), now, generic,
                sms.status, (sms.note ?: "").replace(Regex("\\d"), "#").take(90),
            )
        }
        return com.uaefinancial.tracker.learn.MaskedExport.render(rows, unsafe, otp)
    }

    suspend fun saveStatementFix(
        smsId: Long, totalMinor: Long, minimumMinor: Long?, currency: String, dueEpochDay: Long, statementEpochDay: Long?, cardLast4: String?,
    ): FixResult = lock.withLock {
        val sms = dao.smsById(smsId) ?: return@withLock FixResult(IngestOutcome.FAILED, 0)
        val wasRead = runCatching { SmsParser.parse(sms.sender, sms.body, sms.receivedAt) }.getOrNull()
            .let { r -> if (r is ParseResult.Transaction) r.txn.type.name else if (r is ParseResult.Statement) "STATEMENT" else "unread" }
        val packed = "stmt-fix;min=" + (minimumMinor ?: "") + ";due=$dueEpochDay;stmt=" + (statementEpochDay ?: "")
        val outcome = db.withTransaction {
            dao.upsertFixes(
                listOf(
                    SmsFixEntity(
                        dedupKey = sms.dedupKey, type = FIX_STATEMENT, amountMinor = totalMinor, currency = currency.uppercase(),
                        merchant = packed, cardLast4 = cardLast4?.trim()?.takeIf { it.isNotEmpty() }, cardType = CardType.CREDIT.name,
                        timestamp = sms.receivedAt, lockedFields = "ALL",
                    ),
                ),
            )
            applyParse(sms.id, sms.dedupKey, SmsParser.parse(sms.sender, sms.body, sms.receivedAt), sms.receivedAt, sms.bank ?: sms.sender)
        }
        // What was wrong: the message had no due date (or no reading), and you said it is a statement. Masked shape only, if you allowed it.
        runCatching { onShape?.invoke(sms.sender, sms.bank, sms.body, "fixed:STATEMENT|was=$wasRead|card=" + CardType.CREDIT.name, null) }
        prefs?.let { p -> if (sms.dedupKey in p.learnedFixKeys) p.learnedFixKeys = p.learnedFixKeys - sms.dedupKey }
        loadLearned()
        FixResult(outcome, 0)
    }

    /**
     * You decided: every standing-instruction message still waiting in Needs review (no debit message, no balance
     * evidence) is money that left [cardKey]. Each is saved as your own reading (a fix), so it is kept across re-reads.
     * Returns how many were counted.
     */
    suspend fun countWaitingStandingOn(cardKey: String): Int = lock.withLock {
        ensureLearned()
        val card = dao.allCards().firstOrNull { it.cardKey == cardKey && it.cardType == CardTypes.ACCOUNT } ?: return@withLock 0
        var n = 0
        db.withTransaction {
            for (sms in dao.pendingStanding(SI_RULE, SI_PENDING_NOTE_PREFIX)) {
                if ((sms.bank ?: sms.sender) != card.bank) continue
                val p = SmsParser.parse(sms.sender, sms.body, sms.receivedAt) as? ParseResult.Transaction ?: continue
                val t = p.txn
                if (t.amount.abs() >= Decimal("100000000000")) continue
                dao.upsertFixes(listOf(SmsFixEntity(
                    dedupKey = sms.dedupKey, type = TxnType.TRANSFER_OUT.name, amountMinor = Money.toMinor(t.amount),
                    currency = t.currency, merchant = t.merchant, cardLast4 = card.last4, cardType = CardType.ACCOUNT.name,
                    timestamp = t.timestamp,
                )))
                if (applyParse(sms.id, sms.dedupKey, ParseResult.NotBank, sms.receivedAt, sms.bank) != IngestOutcome.FAILED) n++
            }
        }
        n
    }

    /** What saving a fix did: its own outcome, and how many other messages in Needs review it also read. */
    data class FixResult(val outcome: IngestOutcome, val similar: Int)

    /** What the smart reader makes of an SMS: used to pre-fill the Fix form. */
    /**
     * Compares the small on-device model with what the verified rules concluded, for every read message, and stores
     * the result in [AiCheckEntity]. Advisory only: it reads transactions and writes nothing but ai_checks, so the
     * ledger is the same with it on or off. Returns how many messages the model clearly disagrees with the rules on.
     */
    suspend fun updateSecondOpinions(): Int {
        if (prefs?.aiSecondOpinion == false) { dao.clearAiChecks(); return 0 }
        return withContext(Dispatchers.Default) {
            val txnBySms = dao.allTxns().filter { it.smsId != null && !it.inferred }.groupBy { it.smsId!! }
            val stmtSms = dao.allStatements().map { it.smsId }.toSet()
            val now = System.currentTimeMillis()
            val rows = ArrayList<AiCheckEntity>()
            for (s in dao.smsForReparse()) {
                val rule: com.uaefinancial.tracker.intel.Label = when {
                    txnBySms[s.id] != null ->
                        com.uaefinancial.tracker.intel.Label.of(runCatching { TxnType.valueOf(txnBySms[s.id]!!.first().type) }.getOrDefault(TxnType.PURCHASE))
                    s.id in stmtSms -> com.uaefinancial.tracker.intel.Label.STATEMENT
                    else -> continue
                }
                val body = SmsParser.normalizeBody(s.body)
                val top = com.uaefinancial.tracker.intel.MessageIntel.top(body) ?: continue
                val v = com.uaefinancial.tracker.intel.MessageIntel.secondOpinion(body, rule)
                rows += AiCheckEntity(s.id, rule.name, top.first.name, (top.second * 100).toInt(), v.name, now)
            }
            db.withTransaction { dao.pruneAiChecks(); dao.upsertAiChecks(rows) }
            rows.count { it.verdict == "DISAGREE" }
        }
    }

    fun aiDisagreements() = dao.aiDisagreements()
    fun aiVerdictCounts() = dao.aiVerdictCounts()

    fun guess(sms: SmsEntity): ParsedTransaction? {
        val bank = sms.bank ?: sms.sender
        val body = SmsParser.normalizeBody(sms.body)
        val read = (runCatching { com.uaefinancial.tracker.parser.SmartParser.read(bank, body, sms.receivedAt) }.getOrNull() as? ParseResult.Transaction)?.txn
        // Not readable as a whole: still offer what it does say (amount, currency, digits, a likely kind) rather than a blank form.
        return read ?: runCatching { com.uaefinancial.tracker.parser.SmartParser.suggest(bank, body, sms.receivedAt) }.getOrNull()
    }

    /**
     * The model's suggestion for a message the rules could not read (null when it is off, or finds nothing usable).
     * Shown beside the Fix form; it never saves anything by itself.
     */
    suspend fun aiSuggestion(sms: SmsEntity): com.uaefinancial.tracker.intel.Suggestion? {
        return withContext(Dispatchers.Default) {
            runCatching { com.uaefinancial.tracker.intel.MessageIntel.suggest(sms.bank ?: sms.sender, sms.body, sms.receivedAt) }.getOrNull()
        }
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
        const val FIX_STATEMENT = "STATEMENT"
        const val FIX_RULE = "fixed-by-you"
        /** The FAB "standing instructions ... processed" rule (names the payee, not the account). */
        const val SI_RULE = "fab-standing-instruction"
        const val SI_PENDING_NOTE_PREFIX = "Money left one of your accounts"
        const val SI_PENDING_NOTE = "$SI_PENDING_NOTE_PREFIX, but the messages can't tell which one or when yet. It is placed automatically if a later message settles it; otherwise check your statement and use Fix."
        const val SI_RECURRING_NOTE = "Placed on the account that paid the same standing instruction before (at least two earlier ones agree). Use Fix if it is wrong."
        const val SI_PLACED_NOTE = "Placed from the standing-instruction message: no debit message arrived. Account and time come from your balances."
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
    /** Told when you change a category (for the optional learning report); set by the app. */
    var onCategory: ((sender: String?, bank: String, body: String?, merchant: String, type: TxnType, fromId: Long?, toId: Long, all: Boolean) -> Unit)? = null

    suspend fun setCategory(t: TransactionEntity, categoryId: Long, applyToMerchant: Boolean) = db.withTransaction {
        val fromId = t.categoryId
        val sms = t.smsId?.let { dao.smsById(it) }
        runCatching {
            onCategory?.invoke(sms?.sender, t.bank, sms?.body, t.merchant, runCatching { TxnType.valueOf(t.type) }.getOrDefault(TxnType.PURCHASE), fromId, categoryId, applyToMerchant)
        }
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
    /** Replaces a card's instalment plans with the ones the newest statement shows (nothing changes if it shows none). */
    suspend fun savePlans(cardKey: String, plans: List<com.uaefinancial.tracker.core.InstalmentPlan>, readEpochDay: Long): Boolean {
        if (plans.isEmpty()) return false
        db.withTransaction {
            dao.deletePlansFor(cardKey)
            dao.insertPlans(plans.map {
                InstalmentPlanEntity(
                    cardKey = cardKey, kind = it.kind, bookedEpochDay = it.bookedEpochDay, originalMinor = it.originalMinor,
                    outstandingMinor = it.outstandingMinor, instalmentsLeft = it.instalmentsLeft, tenure = it.tenure,
                    monthlyMinor = it.monthlyMinor, endEpochDay = it.endEpochDay, readEpochDay = readEpochDay,
                )
            })
        }
        return true
    }

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
