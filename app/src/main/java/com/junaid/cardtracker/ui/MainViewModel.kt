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
    /** This card's own net spend for the month (shown even if the card isn't counted). */
    val monthSpendAedMinor: Long,
    val monthTxnCount: Int,
    val latestStatement: StatementEntity?,
    /** Payments received this month: PAYMENT SMS on the card + transfers to it from your account. */
    val monthPaidInMinor: Long = 0,
    /** Bank accounts: money in / out this month. */
    val monthInMinor: Long = 0,
    val monthOutMinor: Long = 0,
    /** Latest available limit (cards) or balance (accounts) from any SMS. */
    val latestBalanceMinor: Long? = null,
    /** Latest statement with paid/due status (credit cards with a statement SMS). */
    val due: CardDue? = null,
)

data class OverviewState(
    val month: YearMonth,
    val spentMinor: Long = 0,
    val previousMonthMinor: Long = 0,
    val byCategory: List<Pair<Long?, Long>> = emptyList(),
    val byCard: List<Slice> = emptyList(),
    val history: List<MonthTotal> = emptyList(),
    val byCurrency: List<CurrencyTotal> = emptyList(),
    val recurring: List<RecurringPayment> = emptyList(),
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

    /** null = all months */
    val month = MutableStateFlow<YearMonth?>(YearMonth.now(zone))
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

    private fun range(m: YearMonth?): Pair<Long, Long> =
        if (m == null) 0L to Long.MAX_VALUE
        else m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Transactions tab list. With "All cards", cards switched OFF on the Cards tab are hidden
     * (typed entries with no card always show). Picking a switched-off card explicitly
     * (Card detail → Show transactions) still shows its transactions.
     */
    val transactions: StateFlow<List<TransactionEntity>> =
        combine(month, cardFilter, categoryFilter) { m, c, cat -> Triple(m, c, cat) }
            .flatMapLatest { (m, c, cat) ->
                val (from, to) = range(m)
                combine(dao.txns(from, to, c), dao.cards()) { list, cards ->
                    val visible = if (c != null) list else {
                        val hidden = cards.filterNot { it.countInSpending }.map { it.cardKey }.toSet()
                        list.filter { it.cardKey == null || it.cardKey !in hidden }
                    }
                    if (cat == null) visible else visible.filter { it.categoryId == cat }
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
            month.flatMapLatest { m -> val (from, to) = range(m ?: YearMonth.now(zone)); dao.txnsBetween(from, to) },
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

    val overview: StateFlow<OverviewState> =
        combine(month, recentTxns, excludedCards) { m, txns, excluded ->
            val sel = m ?: YearMonth.now(zone)
            val all = txns.map { it.toInsight() }
            val inMonth = all.filter { YearMonth.from(it.date) == sel }
            val history = Insights.byMonth(all, excluded, YearMonth.now(zone), 12)
            OverviewState(
                month = sel,
                spentMinor = Spending.totalAedMinor(inMonth.map { Spending.Item(it.type, it.amountAedMinor, it.cardKey) }, excluded),
                previousMonthMinor = history.firstOrNull { it.month == sel.minusMonths(1) }?.amountMinor ?: 0L,
                byCategory = Insights.byCategory(inMonth, excluded),
                byCard = Insights.byCard(inMonth, excluded),
                history = history,
                byCurrency = Insights.byCurrency(inMonth, excluded),
                recurring = Insights.recurring(all.filter { it.cardKey == null || it.cardKey !in excluded || it.merchantKey.startsWith("ACCOUNT DEBIT") }, LocalDate.now(zone)),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewState(YearMonth.now(zone)))

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
    fun previousMonth() { month.value = (month.value ?: YearMonth.now(zone)).minusMonths(1) }
    fun nextMonth() { month.value = (month.value ?: YearMonth.now(zone)).plusMonths(1) }
    fun toggleAllMonths() { month.value = if (month.value == null) YearMonth.now(zone) else null }
    fun selectMonth(m: YearMonth) { month.value = m }
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
