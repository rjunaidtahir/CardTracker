package com.junaid.cardtracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.junaid.cardtracker.CardTrackerApp
import com.junaid.cardtracker.core.IngestOutcome
import com.junaid.cardtracker.core.ReviewExport
import com.junaid.cardtracker.core.Spending
import com.junaid.cardtracker.data.CardEntity
import com.junaid.cardtracker.data.SmsEntity
import com.junaid.cardtracker.data.SmsStatus
import com.junaid.cardtracker.data.StatementEntity
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.ManualEntryParser
import com.junaid.cardtracker.parser.TxnType
import com.junaid.cardtracker.sms.LiveListening
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    /** Bank accounts: money in / out this month and the latest balance from the SMS. */
    val monthInMinor: Long = 0,
    val monthOutMinor: Long = 0,
    val latestBalanceMinor: Long? = null,
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
    val message = MutableStateFlow<String?>(null)

    val syncing = MutableStateFlow(false)
    val lastSyncAt = MutableStateFlow(prefs.lastSyncAt)
    val liveListening = MutableStateFlow(prefs.liveListening)

    private fun range(m: YearMonth?): Pair<Long, Long> =
        if (m == null) 0L to Long.MAX_VALUE
        else m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val transactions: StateFlow<List<TransactionEntity>> =
        combine(month, cardFilter) { m, c -> m to c }
            .flatMapLatest { (m, c) -> val (from, to) = range(m); dao.txns(from, to, c) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CardEntity>> =
        dao.cards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Cards with "Count in spending" OFF. */
    val excludedCards: StateFlow<Set<String>> =
        dao.cards().map { l -> l.filterNot { it.countInSpending }.map { it.cardKey }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val cardSummaries: StateFlow<List<CardSummary>> =
        combine(
            dao.cards(),
            month.flatMapLatest { m -> val (from, to) = range(m ?: YearMonth.now(zone)); dao.txnsBetween(from, to) },
            dao.statements(),
        ) { cards, txns, statements ->
            val byCard = txns.groupBy { it.cardKey }
            val latest = statements.groupBy { it.cardKey }.mapValues { (_, v) -> v.maxByOrNull { it.receivedAt } }
            val paidInto = txns.filter { it.counterpartyKey != null }.groupBy { it.counterpartyKey }
            cards.map { c ->
                val t = byCard[c.cardKey].orEmpty()
                val bankPayments = t.filter { it.type == TxnType.PAYMENT.name }
                // A transfer the card's own bank also reported as a PAYMENT (same amount, within 3 days) counts once.
                val transfers = paidInto[c.cardKey].orEmpty().filterNot { tr ->
                    bankPayments.any { p -> p.amountMinor == tr.amountMinor && kotlin.math.abs(p.timestamp - tr.timestamp) <= 3 * 86_400_000L }
                }
                val paid = bankPayments.sumOf { it.amountAedMinor ?: 0L } + transfers.sumOf { it.amountAedMinor ?: 0L }
                CardSummary(
                    card = c,
                    monthSpendAedMinor = spendingTotal(t, emptySet()),
                    monthTxnCount = t.size,
                    latestStatement = latest[c.cardKey],
                    monthPaidInMinor = paid,
                    monthInMinor = t.filter { it.type == TxnType.TRANSFER_IN.name || it.type == TxnType.REFUND.name }.sumOf { it.amountAedMinor ?: 0L },
                    monthOutMinor = t.filter { it.type == TxnType.TRANSFER_OUT.name || it.type == TxnType.PURCHASE.name }.sumOf { it.amountAedMinor ?: 0L },
                    latestBalanceMinor = t.filter { it.availableLimitMinor != null }.maxByOrNull { it.timestamp }?.availableLimitMinor,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val failedSms: StateFlow<List<SmsEntity>> =
        dao.failedSms().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsCounts: StateFlow<Map<String, Int>> =
        dao.smsStatusCounts().map { l -> l.associate { it.status to it.n } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ---------------------------------------------------------------- filters
    fun previousMonth() { month.value = (month.value ?: YearMonth.now(zone)).minusMonths(1) }
    fun nextMonth() { month.value = (month.value ?: YearMonth.now(zone)).plusMonths(1) }
    fun toggleAllMonths() { month.value = if (month.value == null) YearMonth.now(zone) else null }
    fun selectCard(key: String?) { cardFilter.value = key }

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

    // ------------------------------------------------------------------ cards
    fun setCardCounted(key: String, counted: Boolean) = viewModelScope.launch { dao.setCardCounted(key, counted) }
    fun setCardType(key: String, type: CardType) = viewModelScope.launch { repo.setCardType(key, type) }

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
        }
        return true
    }

    fun deleteTransaction(t: TransactionEntity) = viewModelScope.launch { repo.deleteTransaction(t) }

    suspend fun rawSms(smsId: Long): String? = dao.smsBody(smsId)

    fun reparseAll() = viewModelScope.launch {
        val c = repo.reparseAll()
        message.value = "Re-parsed: ${c[IngestOutcome.TRANSACTION] ?: 0} transactions, " +
            "${c[IngestOutcome.MERGED] ?: 0} merged, ${c[IngestOutcome.STATEMENT] ?: 0} statements, ${c[IngestOutcome.FAILED] ?: 0} need review"
    }

    /** Grouped text of all unparsed SMS, for sharing. */
    suspend fun reviewExportText(): String =
        ReviewExport.summarize(dao.failedSmsList().map { ReviewExport.Item(it.bank ?: it.sender, it.body) })

    fun dismiss(smsId: Long) = viewModelScope.launch { dao.setSmsStatus(smsId, SmsStatus.DISMISSED) }
}
