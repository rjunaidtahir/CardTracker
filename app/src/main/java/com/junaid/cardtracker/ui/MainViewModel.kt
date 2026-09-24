package com.junaid.cardtracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.junaid.cardtracker.CardTrackerApp
import com.junaid.cardtracker.core.CurrencyTotal
import com.junaid.cardtracker.core.IngestOutcome
import com.junaid.cardtracker.core.InsightTxn
import com.junaid.cardtracker.core.Insights
import com.junaid.cardtracker.core.LockPolicy
import com.junaid.cardtracker.core.MonthTotal
import com.junaid.cardtracker.core.PinHasher
import com.junaid.cardtracker.core.Period
import com.junaid.cardtracker.core.PeriodKind
import com.junaid.cardtracker.core.Timeline
import com.junaid.cardtracker.core.TimePoint
import com.junaid.cardtracker.core.Bucket
import com.junaid.cardtracker.core.BudgetStatus
import com.junaid.cardtracker.core.Budgets
import com.junaid.cardtracker.data.FixedPaymentEntity
import com.junaid.cardtracker.data.CardTypes
import com.junaid.cardtracker.report.ReportBuilder
import com.junaid.cardtracker.core.RecurringPayment
import com.junaid.cardtracker.core.ReviewExport
import com.junaid.cardtracker.core.Slice
import com.junaid.cardtracker.core.Spending
import com.junaid.cardtracker.data.CardDue
import com.junaid.cardtracker.data.CardDues
import com.junaid.cardtracker.data.CardEntity
import com.junaid.cardtracker.data.CategoryEntity
import com.junaid.cardtracker.data.FxRateEntity
import com.junaid.cardtracker.data.GoalEntity
import com.junaid.cardtracker.data.SmsEntity
import com.junaid.cardtracker.data.SmsStatus
import com.junaid.cardtracker.data.StatementEntity
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.notify.DueReminders
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.ManualEntryParser
import com.junaid.cardtracker.parser.TxnType
import com.junaid.cardtracker.sms.LiveListening
import com.junaid.cardtracker.widget.SummaryWidget
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
import java.math.BigDecimal
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
    /** Latest statement with paid/due status (credit cards with a statement SMS). */
    val due: CardDue? = null,
    /** Last available limit / balance of each day (last ~13 months), oldest first: the balance line. */
    val balanceHistory: List<Pair<LocalDate, Long>> = emptyList(),
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
    private val ctApp = app as CardTrackerApp
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
                                nick[t.cardKey] ?: "", com.junaid.cardtracker.parser.Money.fromMinor(t.amountMinor).toPlainString(),
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
            val latestBalance = recent.filter { it.availableLimitMinor != null && it.cardKey != null }
                .groupBy { it.cardKey!! }
                .mapValues { (_, l) -> l.maxBy { it.timestamp }.availableLimitMinor }
            val dueByCard = dueList.associateBy { it.card.cardKey }
            val history = recent.filter { it.availableLimitMinor != null && it.cardKey != null }
                .groupBy { it.cardKey!! }
                .mapValues { (_, l) ->
                    l.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                        .map { (d, dl) -> d to dl.maxBy { it.timestamp }.availableLimitMinor!! }
                        .sortedBy { it.first }
                }
            cards.map { c ->
                val t = byCard[c.cardKey].orEmpty()
                CardSummary(
                    card = c,
                    monthSpendAedMinor = spendingTotal(t, emptySet()),
                    monthTxnCount = t.size,
                    latestStatement = latest[c.cardKey],
                    monthPaidInMinor = payments[c.cardKey].orEmpty().sumOf { it.amountMinor },
                    monthInMinor = t.filter { it.type == TxnType.TRANSFER_IN.name || it.type == TxnType.REFUND.name }.sumOf { it.amountAedMinor ?: 0L },
                    monthOutMinor = t.filter { it.type == TxnType.TRANSFER_OUT.name || it.type == TxnType.PURCHASE.name }.sumOf { it.amountAedMinor ?: 0L },
                    latestBalanceMinor = latestBalance[c.cardKey],
                    due = dueByCard[c.cardKey],
                    balanceHistory = history[c.cardKey].orEmpty(),
                )
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
        val parsed = texts.mapValues { (_, t) -> t.replace(",", "").trim().toBigDecimalOrNull()?.let { com.junaid.cardtracker.parser.Money.toMinor(it) } }
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
                .map { com.junaid.cardtracker.core.FixedSchedule.MonthTxn(it.cardKey, it.amountMinor, it.categoryId, it.txnType()) }
            fixed.filter { com.junaid.cardtracker.core.FixedSchedule.autoPaid(it.amountMinor, it.cardKey, it.categoryId, monthTxns) }.map { it.id }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Turns a detected recurring payment into a fixed payment: it then shows in due payments and gets reminders. */
    fun trackRecurring(r: RecurringPayment) = viewModelScope.launch {
        val name = r.categoryId?.let { id -> categories.value.firstOrNull { it.id == id }?.name }
            ?.takeIf { com.junaid.cardtracker.parser.CategoryRules.isAmountSpecific(r.merchant, TxnType.PURCHASE) || r.merchantKey.startsWith("TRANSFER") || r.merchantKey.startsWith("PAYMENT") }
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
        if (on) com.junaid.cardtracker.notify.Alerts.ensureChannel(getApplication())
        message.value = if (on) "Spending alerts on" else "Spending alerts off"
    }
    fun setBudgetAlerts(on: Boolean) { prefs.budgetAlerts = on; budgetAlertsOn.value = on }

    /** kind: "big", "account", "card". Empty or 0 turns that alert off. */
    fun setAlertAmount(kind: String, text: String) {
        val minor = text.replace(",", "").trim().ifEmpty { "0" }.toBigDecimalOrNull()?.let { com.junaid.cardtracker.parser.Money.toMinor(it) }
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
                message.value = r.summary()
                refreshWidget()
            } catch (e: SecurityException) {
                message.value = "SMS permission missing. Allow it, then tap Sync again."
            } catch (e: Exception) {
                message.value = "Sync failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                syncing.value = false
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

    fun shouldShowSamsungTip(): Boolean = !prefs.samsungTipShown
    fun markSamsungTipShown() { prefs.samsungTipShown = true }

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
            val limit = limitAed.replace(",", "").trim().toBigDecimalOrNull()?.let { com.junaid.cardtracker.parser.Money.toMinor(it) }
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
    fun setRate(currency: String, rateText: String) = viewModelScope.launch {
        val r = rateText.trim().toBigDecimalOrNull()
        if (r == null || r <= BigDecimal.ZERO) {
            message.value = "Enter a rate like 3.6725"
            return@launch
        }
        repo.setRate(currency, r)
        message.value = "$currency rate saved; AED amounts updated"
        refreshWidget()
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
        message.value = "Re-parsed: ${c[IngestOutcome.TRANSACTION] ?: 0} transactions, " +
            "${c[IngestOutcome.MERGED] ?: 0} merged, ${c[IngestOutcome.STATEMENT] ?: 0} statements, ${c[IngestOutcome.FAILED] ?: 0} need review"
        refreshWidget()
    }

    /** Grouped text of all unparsed SMS, for sharing. */
    suspend fun reviewExportText(): String =
        ReviewExport.summarize(dao.failedSmsList().map { ReviewExport.Item(it.bank ?: it.sender, it.body) })

    fun dismiss(smsId: Long) = viewModelScope.launch { dao.setSmsStatus(smsId, SmsStatus.DISMISSED) }
}
