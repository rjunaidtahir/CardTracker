package com.uaefinancial.tracker.ui

import com.uaefinancial.tracker.toShared
import com.uaefinancial.tracker.toJava
import com.uaefinancial.tracker.core.toDecimalOrNull
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.core.CurrencyTotal
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.core.InsightTxn
import com.uaefinancial.tracker.core.Insights
import com.uaefinancial.tracker.core.LockPolicy
import com.uaefinancial.tracker.core.MonthTotal
import com.uaefinancial.tracker.core.PinHasher
import com.uaefinancial.tracker.core.Period
import com.uaefinancial.tracker.core.PeriodKind
import com.uaefinancial.tracker.core.Timeline
import com.uaefinancial.tracker.core.TimePoint
import com.uaefinancial.tracker.core.Bucket
import com.uaefinancial.tracker.core.BudgetStatus
import com.uaefinancial.tracker.core.Budgets
import com.uaefinancial.tracker.data.FixedPaymentEntity
import com.uaefinancial.tracker.data.CardTypes
import com.uaefinancial.tracker.report.ReportBuilder
import com.uaefinancial.tracker.core.RecurringPayment
import com.uaefinancial.tracker.core.ReviewExport
import com.uaefinancial.tracker.core.Slice
import com.uaefinancial.tracker.core.Spending
import com.uaefinancial.tracker.data.CardDue
import com.uaefinancial.tracker.data.CardDues
import com.uaefinancial.tracker.data.CardEntity
import com.uaefinancial.tracker.data.CategoryEntity
import com.uaefinancial.tracker.data.FxRateEntity
import com.uaefinancial.tracker.data.GoalEntity
import com.uaefinancial.tracker.data.SmsEntity
import com.uaefinancial.tracker.data.SmsStatus
import com.uaefinancial.tracker.data.StatementEntity
import com.uaefinancial.tracker.data.TransactionEntity
import com.uaefinancial.tracker.notify.DueReminders
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.ManualEntryParser
import com.uaefinancial.tracker.parser.TxnType
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.data.Repository
import com.uaefinancial.tracker.sms.InboxReader
import com.uaefinancial.tracker.sms.LiveListening
import com.uaefinancial.tracker.sms.SenderScan
import com.uaefinancial.tracker.data.SenderEntity
import com.uaefinancial.tracker.widget.SummaryWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CardSummary(
    val card: CardEntity,
    /** This card's own net spend for the selected period (shown even if the card isn't counted). */
    val monthSpendAedMinor: Long,
    val monthTxnCount: Int,
    val latestStatement: StatementEntity?,
    /** Payments received in the period: PAYMENT SMS on the card + transfers to it from your account. */
    val monthPaidInMinor: Long = 0,
    /** Bank accounts: money in / out in the period. */
    val monthInMinor: Long = 0,
    val monthOutMinor: Long = 0,
    /** Latest available limit (cards) or balance (accounts) from any SMS. */
    val latestBalanceMinor: Long? = null,
    /** Where [latestBalanceMinor] comes from, e.g. "latest SMS, 30 Sep" or "statement 12 Sep + 3 transactions". */
    val balanceBasis: String? = null,
    /** Latest statement with paid/due status (credit cards with a statement SMS). */
    val due: CardDue? = null,
    /** Last available limit / balance of each day (last ~13 months), oldest first: the balance line. */
    val balanceHistory: List<Pair<LocalDate, Long>> = emptyList(),
    /** Credit cards: first day after the latest statement, and net spend since then (goes on the next statement). */
    val sinceStatementStart: LocalDate? = null,
    val sinceStatementMinor: Long = 0,
    val sinceStatementCount: Int = 0,
)

data class OverviewState(
    val period: Period,
    val spentMinor: Long = 0,
    /** Same-length period just before (null for All). */
    val previous: Period? = null,
    val previousMinor: Long = 0,
    val byCategory: List<Pair<Long?, Long>> = emptyList(),
    val byCard: List<Slice> = emptyList(),
    /** Spending over the period, in day/week/month buckets. */
    val timeline: List<TimePoint> = emptyList(),
    val bucket: Bucket = Bucket.DAY,
    /** Last 12 calendar months, always (for the bar chart). */
    val history: List<MonthTotal> = emptyList(),
    val byCurrency: List<CurrencyTotal> = emptyList(),
    val recurring: List<RecurringPayment> = emptyList(),
    val txnCount: Int = 0,
    val avgPerDayMinor: Long = 0,
    val topMerchants: List<Pair<String, Long>> = emptyList(),
    /** Money that came into your bank accounts (salary, transfers in) in the period. */
    val moneyInMinor: Long = 0,
)

fun TransactionEntity.txnType(): TxnType = runCatching { TxnType.valueOf(type) }.getOrDefault(TxnType.PURCHASE)

/** All UI totals go through core.Spending so the rules live in one place. */
fun spendingTotal(txns: List<TransactionEntity>, excluded: Set<String>): Long =
    Spending.totalAedMinor(txns.map { Spending.Item(it.txnType(), it.amountAedMinor, it.cardKey) }, excluded)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctApp = app as TrackerApp
    private val repo = ctApp.repo
    private val dao = repo.dao
    private val prefs = ctApp.prefs
    private val zone: ZoneId = ZoneId.systemDefault()

    val nav = Navigator()

    /** Selected period for Overview, Transactions and Cards. Starts on the current calendar month. */
    val period = MutableStateFlow(Period.of(PeriodKind.MONTH, LocalDate.now(zone)))
    /** Transactions search text (merchant, category, card, amount). */
    val search = MutableStateFlow("")
    /** null = all cards */
    val cardFilter = MutableStateFlow<String?>(null)
    /** null = all categories */
    val categoryFilter = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)

    val syncing = MutableStateFlow(false)
    /** "12 new transactions, 1 statement" from the last Sync in this session. */
    val lastSyncSummary = MutableStateFlow<String?>(null)
    /** Goes up by one each time a Sync ends (success or not), so screens can wait for "the next one finished". */
    val syncsFinished = MutableStateFlow(0)
    val lastSyncAt = MutableStateFlow(prefs.lastSyncAt)
    val liveListening = MutableStateFlow(prefs.liveListening)
    val remindersOn = MutableStateFlow(prefs.remindersEnabled)

    // ------------------------------------------------------------ app lock
    val lockEnabled = MutableStateFlow(prefs.lockEnabled)
    val locked = MutableStateFlow(prefs.lockEnabled)
    val biometricOn = MutableStateFlow(prefs.biometricEnabled)
    val lockTimeoutMs = MutableStateFlow(prefs.lockTimeoutMs)
    private var backgroundedAt: Long? = null

    fun onBackground() { backgroundedAt = System.currentTimeMillis() }

    fun onForeground() {
        if (LockPolicy.shouldLock(prefs.lockEnabled, backgroundedAt, System.currentTimeMillis(), prefs.lockTimeoutMs) && backgroundedAt != null) {
            locked.value = true
        }
    }

    fun unlockWithPin(pin: String): Boolean {
        val salt = prefs.pinSalt ?: return false
        val hash = prefs.pinHash ?: return false
        val ok = PinHasher.verify(pin, salt, hash)
        if (ok) locked.value = false
        return ok
    }

    fun unlockWithBiometric() { locked.value = false }

    fun enableLock(pin: String): Boolean {
        if (!PinHasher.isValidPin(pin)) return false
        val salt = PinHasher.newSalt()
        prefs.pinSalt = salt
        prefs.pinHash = PinHasher.hash(pin, salt)
        prefs.lockEnabled = true
        lockEnabled.value = true
        message.value = "App lock on"
        return true
    }

    fun disableLock() {
        prefs.lockEnabled = false
        prefs.pinHash = null
        prefs.pinSalt = null
        lockEnabled.value = false
        locked.value = false
    }

    fun setBiometric(on: Boolean) { prefs.biometricEnabled = on; biometricOn.value = on }
    fun setLockTimeout(ms: Long) { prefs.lockTimeoutMs = ms; lockTimeoutMs.value = ms }

    // ---------------------------------------------------------------- data

    private fun LocalDate.startMs() = atStartOfDay(zone).toInstant().toEpochMilli()

    /** [from, to) in epoch millis for a period (All = everything). */
    private fun range(p: Period): Pair<Long, Long> =
        (p.start?.startMs() ?: 0L) to (p.end?.plusDays(1)?.startMs() ?: Long.MAX_VALUE)

    /**
     * Transactions tab list. With "All cards", cards switched OFF on the Cards tab are hidden
     * (typed entries with no card always show). Picking a switched-off card explicitly
     * (Card detail → Show transactions) still shows its transactions.
     */
    val transactions: StateFlow<List<TransactionEntity>> =
        combine(period, cardFilter, categoryFilter) { p, c, cat -> Triple(p, c, cat) }
            .flatMapLatest { (p, c, cat) ->
                val (from, to) = range(p)
                combine(dao.txns(from, to, c), dao.cards(), dao.categories(), search) { list, cards, cats, q ->
                    val visible = if (c != null) list else {
                        val hidden = cards.filterNot { it.countInSpending }.map { it.cardKey }.toSet()
                        list.filter { it.cardKey == null || it.cardKey !in hidden }
                    }
                    val byCat = if (cat == null) visible else visible.filter { it.categoryId == cat }
                    val query = q.trim()
                    if (query.isEmpty()) byCat else {
                        val names = cats.associate { it.id to it.name }
                        val nick = cards.associate { it.cardKey to (it.nickname ?: "") }
                        val terms = query.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
                        byCat.filter { t ->
                            val hay = listOf(
                                t.merchant, t.bank, t.cardKey ?: "", t.categoryId?.let { names[it] } ?: "",
                                nick[t.cardKey] ?: "", com.uaefinancial.tracker.parser.Money.fromMinor(t.amountMinor).toPlainString(),
                            ).joinToString(" ")
                            terms.any { hay.contains(it, ignoreCase = true) }
                        }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CardEntity>> =
        dao.cards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Cards with "Show & count" OFF. */
    val excludedCards: StateFlow<Set<String>> =
        dao.cards().map { l -> l.filterNot { it.countInSpending }.map { it.cardKey }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val categories: StateFlow<List<CategoryEntity>> =
        dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Last ~13 months of transactions: feeds dues, balances, charts and recurring detection. */
    private val recentTxns =
        dao.txnsBetween(YearMonth.now(zone).minusMonths(13).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), Long.MAX_VALUE)

    val dues: StateFlow<List<CardDue>> =
        combine(dao.cards(), dao.statements(), recentTxns) { cards, statements, txns ->
            CardDues.compute(cards, statements, txns, LocalDate.now(zone), zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cardSummaries: StateFlow<List<CardSummary>> =
        combine(
            dao.cards(),
            period.flatMapLatest { p -> val (from, to) = range(p); dao.txnsBetween(from, to) },
            dao.statements(),
            recentTxns,
            dues,
        ) { cards, txns, statements, recent, dueList ->
            val byCard = txns.groupBy { it.cardKey }
            val latest = statements.groupBy { it.cardKey }.mapValues { (_, v) -> v.maxByOrNull { it.receivedAt } }
            val payments = CardDues.paymentsByCard(txns, zone)
            val latestSms = recent.filter { it.availableLimitMinor != null && it.cardKey != null }
                .groupBy { it.cardKey!! }
                .mapValues { (_, l) -> l.maxBy { it.timestamp } }
            val dueByCard = dueList.associateBy { it.card.cardKey }
            val recentByCard = recent.filter { it.cardKey != null }.groupBy { it.cardKey!! }.mapValues { (_, l) -> l.map { it.toInsight() } }
            val history = recent.filter { it.availableLimitMinor != null && it.cardKey != null }
                .groupBy { it.cardKey!! }
                .mapValues { (_, l) ->
                    l.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                        .map { (d, dl) -> d to dl.maxBy { it.timestamp }.availableLimitMinor!! }
                        .sortedBy { it.first }
                }
            cards.map { c ->
                val t = byCard[c.cardKey].orEmpty()
                // Credit cards: newest of the SMS figure and the saved statement's, rolled forward (core/AvailableLimit.kt).
                val smsTxn = latestSms[c.cardKey]
                val resolved = if (c.cardType == CardTypes.CREDIT) {
                    com.uaefinancial.tracker.core.AvailableLimit.resolve(
                        sms = smsTxn?.let { com.uaefinancial.tracker.core.AvailableLimit.Figure(it.availableLimitMinor!!, Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toEpochDay()) },
                        statement = if (c.statementAvailMinor != null && c.statementAvailEpochDay != null)
                            com.uaefinancial.tracker.core.AvailableLimit.Figure(c.statementAvailMinor, c.statementAvailEpochDay) else null,
                        transactions = recentByCard[c.cardKey].orEmpty().map { com.uaefinancial.tracker.core.AvailableLimit.Move(it.date.toEpochDay(), it.type, it.amountAedMinor) },
                        limitMinor = c.creditLimitMinor,
                    )
                } else null
                val shownBalance = resolved?.minor ?: smsTxn?.availableLimitMinor
                val basis = when {
                    resolved == null -> if (smsTxn != null) "latest SMS" else null
                    resolved.source == com.uaefinancial.tracker.core.AvailableLimit.Source.SMS ->
                        "latest SMS, ${LocalDate.ofEpochDay(resolved.asOfEpochDay).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}"
                    else -> "statement ${LocalDate.ofEpochDay(resolved.asOfEpochDay).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}" +
                        if (resolved.moves > 0) " + ${resolved.moves} transaction${if (resolved.moves == 1) "" else "s"}" else ""
                }
                CardSummary(
                    card = c,
                    monthSpendAedMinor = spendingTotal(t, emptySet()),
                    monthTxnCount = t.size,
                    latestStatement = latest[c.cardKey],
                    monthPaidInMinor = payments[c.cardKey].orEmpty().sumOf { it.amountMinor },
                    monthInMinor = t.filter { it.type == TxnType.TRANSFER_IN.name || it.type == TxnType.REFUND.name }.sumOf { it.amountAedMinor ?: 0L },
                    monthOutMinor = t.filter { it.type == TxnType.TRANSFER_OUT.name || it.type == TxnType.PURCHASE.name }.sumOf { it.amountAedMinor ?: 0L },
                    latestBalanceMinor = shownBalance,
                    balanceBasis = basis,
                    due = dueByCard[c.cardKey],
                    balanceHistory = history[c.cardKey].orEmpty(),
                ).let { cs ->
                    val st = latest[c.cardKey]
                    if (st == null || c.cardType != CardTypes.CREDIT) cs else {
                        val start = com.uaefinancial.tracker.core.SinceStatement.startDate(
                            st.statementDateEpochDay?.let { LocalDate.ofEpochDay(it) },
                            Instant.ofEpochMilli(st.receivedAt).atZone(zone).toLocalDate(),
                        )
                        val mine = recentByCard[c.cardKey].orEmpty()
                        cs.copy(
                            sinceStatementStart = start,
                            sinceStatementMinor = com.uaefinancial.tracker.core.SinceStatement.spend(mine, start),
                            sinceStatementCount = mine.count { !it.date.isBefore(start) && (it.type == TxnType.PURCHASE || it.type == TxnType.REFUND) },
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun TransactionEntity.toInsight() = InsightTxn(
        date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate(),
        type = txnType(),
        amountAedMinor = amountAedMinor,
        cardKey = cardKey,
        categoryId = categoryId,
        merchant = merchant,
        merchantKey = merchantKey ?: "",
        currency = currency,
        amountMinor = amountMinor,
    )

    /** Transactions for the selected period plus the one before it (for the comparison). */
    private val periodAndPreviousTxns =
        period.flatMapLatest { p ->
            val from = p.previous()?.start ?: p.start
            dao.txnsBetween(from?.startMs() ?: 0L, p.end?.plusDays(1)?.startMs() ?: Long.MAX_VALUE)
        }

    val overview: StateFlow<OverviewState> =
        combine(period, periodAndPreviousTxns, recentTxns, excludedCards, dao.cards()) { p, windowTxns, recent, excluded, cardList ->
            // EMIs and other fixed payments usually leave a bank account, which is often switched off: still look there for recurring.
            val accountKeys = cardList.filter { it.cardType == CardTypes.ACCOUNT }.map { it.cardKey }.toSet()
            val today = LocalDate.now(zone)
            val window = windowTxns.map { it.toInsight() }
            val inPeriod = window.filter { p.contains(it.date) }
            val prev = p.previous()
            val inPrev = if (prev == null) emptyList() else window.filter { prev.contains(it.date) }
            fun total(l: List<InsightTxn>) = Spending.totalAedMinor(l.map { Spending.Item(it.type, it.amountAedMinor, it.cardKey) }, excluded)
            val spent = total(inPeriod)
            // Timeline: from the period start (or the first transaction for All) to its end, capped at today.
            val tlStart = p.start ?: inPeriod.minOfOrNull { it.date } ?: today
            val tlEnd = listOfNotNull(p.end, today).min().let { if (it.isBefore(tlStart)) tlStart else it }
            val bucket = Timeline.bucketFor(tlStart, tlEnd)
            val days = java.time.temporal.ChronoUnit.DAYS.between(tlStart, tlEnd) + 1
            val recentAll = recent.map { it.toInsight() }
            val counted = inPeriod.filter { it.cardKey == null || it.cardKey !in excluded }
            OverviewState(
                period = p,
                spentMinor = spent,
                previous = prev,
                previousMinor = total(inPrev),
                byCategory = Insights.byCategory(inPeriod, excluded),
                byCard = Insights.byCard(inPeriod, excluded),
                timeline = Timeline.of(inPeriod, excluded, tlStart, tlEnd, bucket),
                bucket = bucket,
                history = Insights.byMonth(recentAll, excluded, YearMonth.now(zone), 12),
                byCurrency = Insights.byCurrency(inPeriod, excluded),
                recurring = Insights.recurring(recentAll.filter { it.cardKey == null || it.cardKey !in excluded || it.cardKey in accountKeys }, today),
                txnCount = counted.count { it.type == TxnType.PURCHASE || it.type == TxnType.REFUND },
                avgPerDayMinor = if (days > 0) spent / days else 0L,
                topMerchants = counted
                    .groupBy { it.merchantKey.ifBlank { it.merchant } }
                    .map { (_, l) -> l.last().merchant to l.sumOf { Spending.contributionAedMinor(it.type, it.amountAedMinor, true) } }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(5),
                // Money in is shown for every account, counted or not: salary etc. is not spending.
                moneyInMinor = inPeriod.filter { it.type == TxnType.TRANSFER_IN }.sumOf { it.amountAedMinor ?: 0L },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewState(Period.of(PeriodKind.MONTH, LocalDate.now(zone))))

    // ------------------------------------------------------------ budgets
    val budgetLimits: StateFlow<Map<Long, Long>> =
        dao.budgets().map { l -> l.associate { it.categoryId to it.monthlyLimitMinor } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Budgets always cover the current calendar month, whatever period is selected. */
    val budgetStatus: StateFlow<List<BudgetStatus>> =
        combine(budgetLimits, recentTxns, excludedCards) { limits, txns, excluded ->
            val month = YearMonth.now(zone)
            val inMonth = txns.map { it.toInsight() }.filter { YearMonth.from(it.date) == month }
            Budgets.status(limits, Insights.byCategory(inMonth, excluded).toMap())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveBudgets(texts: Map<Long, String>) = viewModelScope.launch {
        val parsed = texts.mapValues { (_, t) -> SmsParser.parseTyped(t)?.let { com.uaefinancial.tracker.parser.Money.toMinor(it) } }
        repo.setBudgets(parsed)
        message.value = "Budgets saved"
    }

    // ------------------------------------------------------------ fixed payments
    val fixedPayments: StateFlow<List<FixedPaymentEntity>> =
        dao.fixedPayments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveFixedPayment(f: FixedPaymentEntity) = viewModelScope.launch { repo.saveFixedPayment(f); message.value = "Saved ${f.name}" }
    fun deleteFixedPayment(id: Long) = viewModelScope.launch { repo.deleteFixedPayment(id) }
    fun markFixedPaid(f: FixedPaymentEntity) = viewModelScope.launch {
        repo.markFixedPaid(f, YearMonth.now(zone).toString())
        message.value = "${f.name} marked paid and added to transactions"
        refreshWidget()
    }
    /** Fixed payments that an SMS transaction this month already covers (same card, amount within 5%). */
    val fixedAutoPaid: StateFlow<Set<Long>> =
        combine(dao.fixedPayments(), recentTxns) { fixed, txns ->
            val month = YearMonth.now(zone)
            val monthTxns = txns.filter { it.source == "SMS" && YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month }
                .map { com.uaefinancial.tracker.core.FixedSchedule.MonthTxn(it.cardKey, it.amountMinor, it.categoryId, it.txnType()) }
            fixed.filter { com.uaefinancial.tracker.core.FixedSchedule.autoPaid(it.amountMinor, it.cardKey, it.categoryId, monthTxns) }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Turns a detected recurring payment into a fixed payment: it then shows in due payments and gets reminders. */
    fun trackRecurring(r: RecurringPayment) = viewModelScope.launch {
        val name = r.categoryId?.let { id -> categories.value.firstOrNull { it.id == id }?.name }
            ?.takeIf { com.uaefinancial.tracker.parser.CategoryRules.isAmountSpecific(r.merchant, TxnType.PURCHASE) || r.merchantKey.startsWith("TRANSFER") || r.merchantKey.startsWith("PAYMENT") }
            ?: r.merchant
        val existing = fixedPayments.value.any { it.cardKey == r.cardKey && kotlin.math.abs(it.amountMinor - r.averageMinor) * 20 <= r.averageMinor }
        if (existing) { message.value = "Already in fixed payments"; return@launch }
        repo.saveFixedPayment(
            FixedPaymentEntity(name = name, amountMinor = r.averageMinor, dayOfMonth = r.lastDate.dayOfMonth, categoryId = r.categoryId, cardKey = r.cardKey),
        )
        message.value = "$name added to fixed payments (due on day ${r.lastDate.dayOfMonth})"
    }

    /** Copies a picture you picked into the app and uses it for this card. */
    fun setCardImage(key: String, uri: Uri) = viewModelScope.launch {
        try {
            val name = withContext(Dispatchers.IO) { CardImages.save(getApplication(), key, uri) }
            repo.setCardTheme(key, CardImages.PREFIX + name)
            message.value = "Card picture saved"
        } catch (e: Exception) {
            message.value = "Couldn't use that picture: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------ statement PDF check
    data class StatementCheck(
        /** Null until you pick (or the app recognises) which card the statement is for. */
        val cardKey: String?,
        val uri: String? = null,
        val loading: Boolean = false,
        val needsPassword: Boolean = false,
        val error: String? = null,
        val result: com.uaefinancial.tracker.core.Reconciliation? = null,
        val lineCount: Int = 0,
        val text: String? = null,
        val lines: List<com.uaefinancial.tracker.core.StatementLine> = emptyList(),
        val summary: com.uaefinancial.tracker.core.StatementSummary? = null,
        val summarySaved: List<String> = emptyList(),
        /** "Read 23 transactions ... They add up to the statement's balance." */
        val totalsCheck: String? = null,
        val totalsAgree: Boolean? = null,
        /** Supplementary cards found in the statement (last 4 → card key in the app, if known). */
        val otherCards: Map<String, String?> = emptyMap(),
    )
    val statementCheck = MutableStateFlow<StatementCheck?>(null)

    fun startStatementCheck(cardKey: String?) { statementCheck.value = StatementCheck(cardKey) }

    /** A PDF opened from another app (Gmail "Open with", Files, Share), waiting until the app is ready to show it. */
    val incomingPdf = MutableStateFlow<Uri?>(null)

    /**
     * Opens a statement PDF handed over by another app. The file is copied into the app first (the other app's
     * permission to read it can end at any time), then read like a picked file: password prompt if needed, then
     * the card is recognised from its last 4 digits or you choose it.
     */
    fun openIncomingPdf(uri: Uri) {
        startStatementCheck(null)
        statementCheck.value = statementCheck.value?.copy(loading = true)
        viewModelScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val f = java.io.File(getApplication<Application>().cacheDir, "incoming-statement.pdf")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        ?: error("Couldn't open the file")
                    Uri.fromFile(f)
                }
                checkStatement(copy, null)
            } catch (e: Exception) {
                statementCheck.value = statementCheck.value?.copy(loading = false, error = "Couldn't open that PDF: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun checkStatement(uri: Uri, password: String?) {
        val cur = statementCheck.value ?: return
        statementCheck.value = cur.copy(uri = uri.toString(), loading = true, error = null, needsPassword = false, result = null, summarySaved = emptyList())
        viewModelScope.launch {
            try {
                val printed = withContext(Dispatchers.IO) { com.uaefinancial.tracker.report.PdfText.readLines(getApplication(), uri, password) }
                val text = com.uaefinancial.tracker.report.PdfText.asText(printed)
                val analysis = withContext(Dispatchers.Default) { com.uaefinancial.tracker.core.StatementReader.analyze(printed, LocalDate.now(zone).year) }
                val lines = analysis.lines
                val summary = analysis.summary
                // Recognise the card from the last 4 digits printed on the statement.
                val key = cur.cardKey ?: summary.cardLast4?.let { l4 -> dao.allCards().filter { it.last4 == l4 }.singleOrNull()?.cardKey }
                statementCheck.value = statementCheck.value?.copy(
                    cardKey = key, loading = false, lineCount = lines.size, text = text, lines = lines, summary = summary,
                    totalsCheck = analysis.totalsCheck, totalsAgree = analysis.totalsAgree,
                    error = if (lines.isEmpty() && summary.isEmpty) "No transaction lines or statement figures found in this PDF." else null,
                )
                if (key != null) reconcileStatement()
            } catch (e: com.uaefinancial.tracker.report.PdfText.PasswordNeeded) {
                statementCheck.value = statementCheck.value?.copy(loading = false, needsPassword = true,
                    error = if (password.isNullOrEmpty()) null else "Wrong password, try again.")
            } catch (e: Exception) {
                statementCheck.value = statementCheck.value?.copy(loading = false, error = "Couldn't read the PDF: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun reconcileStatement() {
        val cur = statementCheck.value ?: return
        val key = cur.cardKey ?: return
        val lines = cur.lines
        if (lines.isEmpty()) { statementCheck.value = cur.copy(result = null); return }
        val allCards = dao.allCards()
        val main = allCards.firstOrNull { it.cardKey == key }
        // Lines of a supplementary card ("Supplementary Card ... 3944") belong to that card in the app.
        val others = lines.mapNotNull { it.cardLast4 }.filter { it != main?.last4 }.toSet()
            .associateWith { l4 -> (allCards.filter { it.last4 == l4 && it.bank == main?.bank } + allCards.filter { it.last4 == l4 }).firstOrNull()?.cardKey }
        val keys = setOf(key) + others.values.filterNotNull()
        val from = lines.minOf { it.date }.minusDays(5).toJava()
        val to = lines.maxOf { it.date }.plusDays(6).toJava()
        val appTxns = dao.txnsListBetween(from.startMs(), to.startMs())
            .filter { it.cardKey in keys || it.counterpartyKey in keys }
            .map { t ->
                val type = t.txnType()
                // A transfer to this card is a payment (credit) from the card's point of view.
                val credit = t.counterpartyKey in keys || type == TxnType.REFUND || type == TxnType.PAYMENT || type == TxnType.TRANSFER_IN
                com.uaefinancial.tracker.core.AppTxnRef(
                    t.id, Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalDate().toShared(),
                    t.amountAedMinor ?: t.amountMinor, credit, t.fxEstimated, t.merchant,
                )
            }
        statementCheck.value = statementCheck.value?.copy(
            result = com.uaefinancial.tracker.core.StatementImport.reconcile(lines, appTxns),
            otherCards = others,
        )
    }

    /** You picked which existing card the statement belongs to. */
    fun chooseStatementCard(key: String) {
        statementCheck.value = statementCheck.value?.copy(cardKey = key, summarySaved = emptyList())
        viewModelScope.launch { reconcileStatement() }
    }

    /** A card the app doesn't know yet (e.g. a family member's): create it, then compare. */
    fun createStatementCard(bank: String, last4: String, family: Boolean, nickname: String) = viewModelScope.launch {
        if (bank.isBlank() || !Regex("""\d{4}""").matches(last4.trim())) { message.value = "Enter the bank and the card's last 4 digits"; return@launch }
        val key = repo.ensureStatementCard(bank, last4.trim(), family, nickname)
        chooseStatementCard(key)
        message.value = if (family) "Added as a family card: its spends go to the Family category" else "Card added"
    }

    fun setCardFamily(key: String, family: Boolean) = viewModelScope.launch { repo.setCardOwner(key, family) }

    /** Saves the statement's credit limit, statement/due day and (if missing) the statement itself. */
    fun applyStatementSummary() {
        val cur = statementCheck.value ?: return
        val key = cur.cardKey ?: return
        val s = cur.summary ?: return
        viewModelScope.launch {
            val done = repo.applyStatementSummary(key, s, System.currentTimeMillis())
            statementCheck.value = statementCheck.value?.copy(summarySaved = done)
            message.value = if (done.isEmpty()) "Nothing new to save" else "Saved: " + done.joinToString(", ")
            refreshWidget()
        }
    }

    fun addFromStatement(lines: List<com.uaefinancial.tracker.core.StatementLine>) {
        val cur = statementCheck.value ?: return
        val key = cur.cardKey ?: return
        viewModelScope.launch {
            var n = 0
            for ((l4, group) in lines.groupBy { it.cardLast4?.takeIf { l -> cur.otherCards.containsKey(l) } }) {
                val target = if (l4 == null) key else cur.otherCards[l4] ?: repo.ensureStatementCard(
                    dao.allCards().firstOrNull { it.cardKey == key }?.bank ?: "Card", l4, family = false, nickname = null,
                )
                n += repo.addStatementLines(target, group)
            }
            message.value = "Added $n transactions from the statement"
            refreshWidget()
            val added = lines.toSet()
            statementCheck.value = statementCheck.value?.let { st -> st.copy(result = st.result?.let { r -> r.copy(missing = r.missing.filterNot { it in added }) }) }
        }
    }

    /** Transactions tab: this card, from the day after its last statement until today. */
    fun showSinceStatement(cardKey: String, start: LocalDate) {
        cardFilter.value = cardKey
        categoryFilter.value = null
        period.value = Period.custom(start, LocalDate.now(zone))
    }

    fun unmarkFixedPaid(f: FixedPaymentEntity) = viewModelScope.launch { repo.saveFixedPayment(f.copy(lastPaidYm = null)) }

    // ------------------------------------------------------------ look
    val themeId = MutableStateFlow(prefs.themeId)
    fun setTheme(id: String) {
        prefs.themeId = id
        themeId.value = id
        AppThemes.current = AppThemes.byId(id)
    }
    fun setCardOrder(keys: List<String>) = viewModelScope.launch { repo.setCardOrder(keys) }
    fun setCardTheme(key: String, theme: String?) = viewModelScope.launch {
        // Picking a drawn look (or Automatic) removes your own picture file.
        if (theme == null || !theme.startsWith(CardImages.PREFIX)) withContext(Dispatchers.IO) { CardImages.delete(getApplication(), key) }
        repo.setCardTheme(key, theme)
    }

    // ------------------------------------------------------------ alerts
    val alertsOn = MutableStateFlow(prefs.alertsEnabled)
    val bigSpendMinor = MutableStateFlow(prefs.bigSpendMinor)
    val lowAccountMinor = MutableStateFlow(prefs.lowAccountBalanceMinor)
    val lowCardMinor = MutableStateFlow(prefs.lowCardAvailableMinor)
    val budgetAlertsOn = MutableStateFlow(prefs.budgetAlerts)

    /** Caller must make sure notification permission is granted before passing true. */
    fun setAlerts(on: Boolean) {
        prefs.alertsEnabled = on
        alertsOn.value = on
        if (on) com.uaefinancial.tracker.notify.Alerts.ensureChannel(getApplication())
        message.value = if (on) "Spending alerts on" else "Spending alerts off"
    }
    fun setBudgetAlerts(on: Boolean) { prefs.budgetAlerts = on; budgetAlertsOn.value = on }

    /** kind: "big", "account", "card". Empty or 0 turns that alert off. */
    fun setAlertAmount(kind: String, text: String) {
        val minor = SmsParser.parseTyped(text.ifBlank { "0" })?.let { com.uaefinancial.tracker.parser.Money.toMinor(it) }
        if (minor == null || minor < 0) { message.value = "Enter an amount like 1000"; return }
        when (kind) {
            "big" -> { prefs.bigSpendMinor = minor; bigSpendMinor.value = minor }
            "account" -> { prefs.lowAccountBalanceMinor = minor; lowAccountMinor.value = minor }
            "card" -> { prefs.lowCardAvailableMinor = minor; lowCardMinor.value = minor }
        }
        message.value = "Alert amount saved"
    }

    // ------------------------------------------------------------ report
    /** Writes a PDF (or CSV for Excel) report of the selected period to [uri]. */
    fun exportReport(uri: Uri, pdf: Boolean) = viewModelScope.launch {
        try {
            val p = period.value
            val (from, to) = range(p)
            val all = dao.txnsListBetween(from, to)
            val cardList = dao.allCards()
            val hidden = cardList.filterNot { it.countInSpending }.map { it.cardKey }.toSet()
            val visible = all.filter { it.cardKey == null || it.cardKey !in hidden }
            // Overview/budgets are only collected while Overview is showing: wait for figures of this period.
            val ov = overview.first { it.period == p }
            val bs = budgetStatus.first()
            val data = ReportBuilder.build(
                overview = ov,
                txns = visible,
                categories = categories.value.associate { it.id to it.name },
                cardNames = cardList.associate { it.cardKey to CardArts.displayName(it) },
                budgets = bs,
                zone = zone,
                ownerName = prefs.displayName,
            )
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    if (pdf) ReportBuilder.writePdf(data, out) else out.write(ReportBuilder.csv(data).toByteArray(Charsets.UTF_8))
                } ?: error("Couldn't open the file")
            }
            message.value = if (pdf) "PDF report saved" else "CSV report saved (opens in Excel)"
        } catch (e: Exception) {
            message.value = "Report failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    val goals: StateFlow<List<GoalEntity>> =
        dao.goals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rates: StateFlow<List<FxRateEntity>> =
        dao.rates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedSms: StateFlow<List<SmsEntity>> =
        dao.failedSms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsCounts: StateFlow<Map<String, Int>> =
        dao.smsStatusCounts().map { l -> l.associate { it.status to it.n } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun refreshWidget() = SummaryWidget.requestUpdate(getApplication())

    // ---------------------------------------------------------------- filters
    fun selectPeriodKind(k: PeriodKind) { period.value = Period.of(k, LocalDate.now(zone)) }
    fun shiftPeriod(steps: Int) { period.value.shift(steps)?.let { period.value = it } }
    fun setCustomPeriod(from: LocalDate, to: LocalDate) { period.value = Period.custom(from, to) }
    fun selectMonth(m: YearMonth) { period.value = Period.month(m) }
    fun setSearch(q: String) { search.value = q }
    fun selectCard(key: String?) { cardFilter.value = key }
    fun selectCategory(id: Long?) { categoryFilter.value = id }

    // ------------------------------------------------------------------- sync
    /** Caller must make sure READ_SMS is granted. */
    fun sync() {
        if (syncing.value) return
        syncing.value = true
        viewModelScope.launch {
            try {
                val r = ctApp.smsSync.run()
                lastSyncAt.value = prefs.lastSyncAt
                lastSyncSummary.value = r.summary()
                message.value = r.summary()
                refreshWidget()
            } catch (e: SecurityException) {
                message.value = "SMS permission missing. Allow it, then tap Sync again."
            } catch (e: Exception) {
                message.value = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                syncing.value = false
                syncsFinished.value += 1
            }
        }
    }

    /** Next Sync re-reads the whole inbox. Nothing is duplicated thanks to the unique SMS key. */
    fun resetSyncPointer() {
        prefs.lastSyncAt = null
        lastSyncAt.value = null
        message.value = "Next Sync will re-read the whole inbox"
    }

    // ------------------------------------------------------------------- live
    /** Caller must make sure RECEIVE_SMS is granted before passing true. */
    fun setLiveListening(on: Boolean) {
        LiveListening.setEnabled(getApplication(), prefs, on)
        liveListening.value = prefs.liveListening
    }

    // ------------------------------------------------- bank-app notifications
    val notifApps = MutableStateFlow(prefs.notifApps)
    val seenNotifApps = MutableStateFlow(prefs.seenNotifApps)

    fun refreshNotifApps() {
        notifApps.value = prefs.notifApps
        seenNotifApps.value = prefs.seenNotifApps
    }

    /** Tick or untick one bank app ("package<TAB>name"). A ticked app's name becomes a bank sender if it isn't one already. */
    fun setNotifApp(entry: String, on: Boolean) {
        val pkg = entry.substringBefore('\t')
        val label = entry.substringAfter('\t', pkg)
        prefs.notifApps = prefs.notifApps.filterNot { it.substringBefore('\t') == pkg }.toSet() + (if (on) setOf(entry) else emptySet())
        notifApps.value = prefs.notifApps
        if (on) viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bankName = com.uaefinancial.tracker.parser.SmsParser.anyBankFor(label)?.name ?: label
                repo.addSender(label, bankName)
            }
        }
    }

    fun shouldShowBatteryTip(): Boolean = !prefs.batteryTipShown
    fun markBatteryTipShown() { prefs.batteryTipShown = true }

    // -------------------------------------------------------------- reminders
    /** Caller must make sure notification permission is granted (Android 13+) before passing true. */
    fun setReminders(on: Boolean) {
        DueReminders.setEnabled(getApplication(), on)
        remindersOn.value = on
        message.value = if (on) "Due-date reminders on: 3 days before, 1 day before and on the due day" else "Due-date reminders off"
    }

    // ------------------------------------------------------------------ cards
    fun setCardCounted(key: String, counted: Boolean) = viewModelScope.launch { dao.setCardCounted(key, counted); refreshWidget() }
    fun setCardType(key: String, type: CardType) = viewModelScope.launch { repo.setCardType(key, type); refreshWidget() }

    fun saveCardProfile(key: String, nickname: String, limitAed: String, statementDay: String, dueDay: String, reminders: Boolean) =
        viewModelScope.launch {
            val limit = SmsParser.parseTyped(limitAed)?.let { com.uaefinancial.tracker.parser.Money.toMinor(it) }
            val sd = statementDay.trim().toIntOrNull()?.takeIf { it in 1..31 }
            val dd = dueDay.trim().toIntOrNull()?.takeIf { it in 1..31 }
            repo.updateCardProfile(key, nickname, limit, sd, dd, reminders)
            message.value = "Card saved"
        }

    // ------------------------------------------------------------ categories
    fun setCategory(t: TransactionEntity, categoryId: Long, applyToMerchant: Boolean) = viewModelScope.launch {
        repo.setCategory(t, categoryId, applyToMerchant)
        refreshWidget()
    }

    fun addCategoryAndSet(t: TransactionEntity, name: String, applyToMerchant: Boolean) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = repo.addCategory(name)
        repo.setCategory(t, id, applyToMerchant)
    }

    // ------------------------------------------------------------------ goals
    fun saveGoal(g: GoalEntity) = viewModelScope.launch { repo.saveGoal(g) }
    fun deleteGoal(id: Long) = viewModelScope.launch { repo.deleteGoal(id) }

    // ------------------------------------------------------------------ rates
    /** [rateText]: 1 [currency] in your home currency. */
    fun setRate(currency: String, rateText: String) = viewModelScope.launch {
        val t = rateText.trim().let { if (!it.contains('.') && it.count { c -> c == ',' } == 1) it.replace(',', '.') else it.replace(",", "") }
        val r = runCatching { java.math.BigDecimal(t) }.getOrNull()
        if (r == null || r.signum() <= 0) {
            message.value = "Enter a rate like 3.6725"
            return@launch
        }
        val ok = runCatching { withContext(Dispatchers.IO) { repo.setRateInHome(currency, r) } }.isSuccess
        message.value = if (ok) "$currency rate saved; ${SmsParser.homeCurrency} amounts updated" else "Couldn't save that rate"
        refreshWidget()
    }

    fun rateInHome(currency: String): String? = repo.rateInHome(currency)?.toPlainString()

    /** Changes your home currency (More → Home currency): messages are read again and every amount recalculated. */
    fun setHomeCurrency(code: String) = viewModelScope.launch {
        message.value = "Changing the home currency to $code…"
        try {
            withContext(Dispatchers.IO) { repo.setHomeCurrency(code) }
            Home.code = SmsParser.homeCurrency
            message.value = "Home currency: ${SmsParser.homeCurrency}. Budgets and alert amounts kept their numbers."
            refreshWidget()
        } catch (e: Exception) {
            message.value = "Couldn't change the home currency: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ backup
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { ctApp.backup.export(it) }
                    ?: error("Couldn't open the file")
            }
            message.value = "Backup saved"
        } catch (e: Exception) {
            message.value = "Backup failed: ${e.message}"
        }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        try {
            val r = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { ctApp.backup.import(it) }
                    ?: error("Couldn't open the file")
            }
            excludedBanks.value = prefs.excludedBanks
            message.value = "Restored ${r.sms} SMS, ${r.manual} typed entries, ${r.cards} cards"
            refreshWidget()
        } catch (e: Exception) {
            message.value = "Restore failed: ${e.message}"
        }
    }

    // ------------------------------------------------------------ transactions
    /** Returns true if the text was understood and saved. */
    fun addManual(text: String): Boolean {
        val entry = ManualEntryParser.parse(text)
        if (entry == null) {
            message.value = "Couldn't find an amount. Try e.g. \"lunch 45 aed\""
            return false
        }
        viewModelScope.launch {
            repo.addManual(entry)
            message.value = "Added ${entry.description}: ${entry.currency} ${entry.amount.toPlainString()}"
            refreshWidget()
        }
        return true
    }

    fun deleteTransaction(t: TransactionEntity) = viewModelScope.launch { repo.deleteTransaction(t); refreshWidget() }

    suspend fun rawSms(smsId: Long): String? = dao.smsBody(smsId)

    fun reparseAll() = viewModelScope.launch {
        val c = repo.reparseAll()
        runCatching { repo.autoFillCardDays() }
        message.value = "Re-parsed: ${c[IngestOutcome.TRANSACTION] ?: 0} transactions, " +
            "${c[IngestOutcome.MERGED] ?: 0} merged, ${c[IngestOutcome.STATEMENT] ?: 0} statements, ${c[IngestOutcome.FAILED] ?: 0} need review"
        refreshWidget()
    }

    /** Grouped text of all unparsed SMS, for sharing. */
    suspend fun reviewExportText(): String =
        ReviewExport.summarize(dao.failedSmsList().map { ReviewExport.Item(it.bank ?: it.sender, it.body) })

    fun dismiss(smsId: Long) = viewModelScope.launch { dao.setSmsStatus(smsId, SmsStatus.DISMISSED) }

    // ------------------------------------------------------------ first-run setup
    val onboarded = MutableStateFlow(prefs.onboarded)
    fun finishOnboarding() { prefs.onboarded = true; onboarded.value = true }
    fun restartOnboarding() { prefs.onboarded = false; onboarded.value = false }

    val displayName = MutableStateFlow(prefs.displayName)
    fun setDisplayName(name: String) { prefs.displayName = name; displayName.value = prefs.displayName; message.value = "Name saved" }

    // ------------------------------------------------------------ bank senders
    val senders: StateFlow<List<SenderEntity>> =
        dao.senders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class ScanState(val running: Boolean = false, val result: SenderScan? = null, val error: String? = null)
    val scan = MutableStateFlow(ScanState())

    /** Caller must make sure READ_SMS is granted. */
    fun scanSenders() {
        if (scan.value.running) return
        scan.value = ScanState(running = true, result = scan.value.result)
        viewModelScope.launch {
            scan.value = try {
                ScanState(result = withContext(Dispatchers.IO) { InboxReader.scanSenders(getApplication()) })
            } catch (e: SecurityException) {
                ScanState(error = "The app needs permission to read SMS first.")
            } catch (e: Exception) {
                ScanState(error = "Couldn't scan your messages: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------ country and sharing
    fun homeCountry(): String? = prefs.homeCountry

    /** First-run (or later) choice of country: sets the home currency and date style and puts that country's banks first. */
    fun setCountry(code: String) {
        prefs.homeCountry = code
        prefs.dateRegion = code
        SmsParser.setMonthFirstDates(SmsParser.isMonthFirstRegion(code))
        val cur = com.uaefinancial.tracker.data.Region.currencyFor(code)
        if (!cur.equals(SmsParser.homeCurrency, ignoreCase = true)) setHomeCurrency(cur)
    }

    val shareConsent = MutableStateFlow(prefs.shareConsent)
    val recentShared = MutableStateFlow(prefs.recentShared)

    fun setShareConsent(on: Boolean) {
        prefs.shareConsent = if (on) com.uaefinancial.tracker.learn.Learning.ON else com.uaefinancial.tracker.learn.Learning.OFF
        if (!on) prefs.shapeQueue = emptyList()
        shareConsent.value = prefs.shareConsent
        // Messages already waiting in Needs review count too, not only new ones.
        if (on) viewModelScope.launch(Dispatchers.IO) { runCatching { repo.queueUnreadForLearning() } }
    }

    fun refreshShared() { recentShared.value = prefs.recentShared }

    // ------------------------------------------------------------ chats that look like banks
    val candidates = MutableStateFlow<List<com.uaefinancial.tracker.sms.Candidates.Candidate>>(emptyList())

    fun refreshCandidates() {
        candidates.value = com.uaefinancial.tracker.sms.Candidates.list(prefs)
    }

    /** One tap: this chat is a bank. Its messages (also the older ones) are read from now on. */
    fun acceptCandidate(c: com.uaefinancial.tracker.sms.Candidates.Candidate) {
        addSenders(listOf(c.sender to (c.bankName ?: c.sender)), syncAfter = true)
        com.uaefinancial.tracker.sms.Candidates.forget(prefs, c.sender)
        refreshCandidates()
    }

    fun dismissCandidate(c: com.uaefinancial.tracker.sms.Candidates.Candidate) {
        com.uaefinancial.tracker.sms.Candidates.dismiss(prefs, c.key)
        refreshCandidates()
    }

    /** Adds sender IDs; the next Sync re-reads the whole inbox so their older messages come in too. */
    fun addSenders(pairs: List<Pair<String, String>>, syncAfter: Boolean) = viewModelScope.launch {
        val clean = pairs.map { (s, b) -> s.trim() to b.trim() }.filter { it.first.isNotEmpty() }
        if (clean.isEmpty()) { message.value = "Enter the sender name exactly as it shows in Messages"; return@launch }
        clean.forEach { (s, b) -> repo.addSender(s, b) }
        resetSyncPointerQuietly()
        // The scan list should no longer offer what was just added.
        scan.value = scan.value.copy(result = scan.value.result?.let { r -> r.copy(suggestions = r.suggestions.filterNot { sug -> clean.any { it.first.equals(sug.sender, true) } }) })
        message.value = if (clean.size == 1) "Added ${clean[0].first}" else "Added ${clean.size} senders"
        if (syncAfter && InboxReader.hasPermission(getApplication())) sync()
    }

    fun removeSender(sender: String) = viewModelScope.launch {
        repo.removeSender(sender)
        message.value = "Removed $sender. Its messages already imported stay until you tap Re-read stored messages."
    }

    private fun resetSyncPointerQuietly() {
        prefs.lastSyncAt = null
        lastSyncAt.value = null
    }

    // ------------------------------------------------------------ review: fix by hand
    /** What the smart reader makes of an unread SMS, to pre-fill the Fix form. */
    suspend fun guessFor(sms: SmsEntity): com.uaefinancial.tracker.parser.ParsedTransaction? =
        withContext(Dispatchers.Default) { repo.guess(sms) }

    /** Whether "apply to similar messages" can work for this reading (the Fix form enables the option then). */
    fun canLearn(sms: SmsEntity, type: TxnType?, amountText: String, merchant: String, cardLast4: String, cardType: CardType): Boolean {
        val amount = SmsParser.parseTyped(amountText)
        val minor = amount?.takeIf { it.signum() > 0 }?.let { runCatching { com.uaefinancial.tracker.parser.Money.toMinor(it) }.getOrNull() } ?: 0L
        if (type != null && minor <= 0L) return false
        return repo.canLearn(sms, type, minor, merchant, cardLast4.trim().ifEmpty { null }, cardType)
    }

    private fun fixMessage(r: Repository.FixResult, what: String): String =
        if (r.similar > 0) "Learned. $what Also applied to ${r.similar} similar message${if (r.similar == 1) "" else "s"}." else what

    fun saveFix(
        sms: SmsEntity, type: TxnType, amountText: String, currency: String, merchant: String, cardLast4: String, cardType: CardType,
        applyToSimilar: Boolean = false,
    ) = viewModelScope.launch {
        val cur = currency.trim().uppercase().ifEmpty { SmsParser.homeCurrency }
        if (!Regex("[A-Z]{3}").matches(cur)) { message.value = "Currency is a 3-letter code, e.g. ${SmsParser.homeCurrency} or USD"; return@launch }
        val amount = SmsParser.parseTyped(amountText, cur)
        if (amount == null || amount.signum() <= 0) { message.value = "Enter the amount, e.g. 120.50"; return@launch }
        val last4 = cardLast4.trim()
        if (last4.isNotEmpty() && !Regex("""\d{3,4}""").matches(last4)) { message.value = "Card / account: the last 4 digits, or leave it empty"; return@launch }
        val r = repo.saveFix(
            sms.id, type, com.uaefinancial.tracker.parser.Money.toMinor(amount), cur, merchant, last4.ifEmpty { null }, cardType,
            sms.receivedAt, applyToSimilar,
        )
        message.value = fixMessage(r, if (applyToSimilar) "Saved. Similar messages will be read the same way." else "Saved. The app will remember this message.")
        refreshWidget()
    }

    fun markNotTransaction(sms: SmsEntity, applyToSimilar: Boolean = false) = viewModelScope.launch {
        val r = repo.saveFix(sms.id, null, 0, "AED", "", null, CardType.CREDIT, sms.receivedAt, applyToSimilar)
        message.value = fixMessage(r, "Marked as not a transaction.")
        refreshWidget()
    }

    // ------------------------------------------------------------ banks you track
    /** Banks you chose not to track (unticked in setup or in More → Bank senders). */
    val excludedBanks = MutableStateFlow(prefs.excludedBanks)

    private fun applyExcluded(names: Set<String>) {
        prefs.excludedBanks = names
        excludedBanks.value = names
        SmsParser.setExcludedBanks(names)
    }

    /** Setup: which of the banks found on this phone to leave out, before the first import. */
    fun setExcludedBanksBeforeImport(names: Set<String>) = applyExcluded(names)

    /** Starts or stops tracking a bank. Stored messages are read again so the change shows everywhere. */
    fun setBankTracked(bank: String, tracked: Boolean) = viewModelScope.launch {
        val now = excludedBanks.value
        val next = if (tracked) now.filterNot { it.equals(bank, ignoreCase = true) }.toSet() else now + bank
        if (next == now) return@launch
        applyExcluded(next)
        repo.reparseAll()
        runCatching { repo.autoFillCardDays() }
        if (tracked) {
            // Its older messages were never imported: the next Sync reads the whole inbox again.
            resetSyncPointerQuietly()
            message.value = "Tracking $bank again. Tap Sync to bring in its messages."
        } else {
            message.value = "Stopped tracking $bank. Its transactions are hidden; tick it again to bring them back."
        }
        refreshWidget()
    }

    // ------------------------------------------------------------ cards by hand
    fun addCard(bank: String, last4: String, type: CardType, nickname: String, family: Boolean) = viewModelScope.launch {
        val b = bank.trim()
        val l4 = last4.trim()
        if (b.isEmpty()) { message.value = "Enter the bank's name"; return@launch }
        if (l4.isNotEmpty() && !Regex("""\d{4}""").matches(l4)) { message.value = "Last 4 digits: exactly 4 numbers"; return@launch }
        repo.addCard(b, l4.ifEmpty { null }, type, nickname, family)
        message.value = "Added ${nickname.ifBlank { "$b ·${l4.ifEmpty { "····" }}" }}"
        refreshWidget()
    }

    /** Returns via [message]; navigates back only on success (caller checks [cards]). */
    fun deleteCard(key: String, onDone: () -> Unit) = viewModelScope.launch {
        if (repo.deleteEmptyCard(key)) { message.value = "Card removed"; onDone() }
        else message.value = "This card has transactions or statements, so it can't be removed. Switch off \"Show & count\" to hide it instead."
    }
}
