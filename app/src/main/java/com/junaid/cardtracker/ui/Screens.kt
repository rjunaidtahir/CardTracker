package com.junaid.cardtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junaid.cardtracker.core.DueState
import com.junaid.cardtracker.core.Utilisation
import com.junaid.cardtracker.data.CardDue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junaid.cardtracker.data.CardEntity
import com.junaid.cardtracker.data.CategoryEntity
import com.junaid.cardtracker.data.CardTypes
import com.junaid.cardtracker.data.SmsEntity
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.Money
import com.junaid.cardtracker.parser.TxnType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- formatting

// DecimalFormat isn't thread-safe and fmtMoney is also used by the widget and reminder worker.
private val moneyFmt = ThreadLocal.withInitial { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ENGLISH)) }
fun fmtMoney(minor: Long, currency: String = "AED") = "$currency ${moneyFmt.get()!!.format(Money.fromMinor(minor))}"

private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun fmtEpochDay(day: Long): String = LocalDate.ofEpochDay(day).format(dateFmt)

private fun typeLabel(t: String) = when (t) {
    CardTypes.DEBIT -> "Debit card"
    CardTypes.ACCOUNT -> "Bank account"
    else -> "Credit card"
}

// ---------------------------------------------------------- transactions tab

@Composable
fun TransactionsScreen(
    vm: MainViewModel,
    month: YearMonth?,
    cardFilter: String?,
    cards: List<CardEntity>,
    excluded: Set<String>,
    txns: List<TransactionEntity>,
    syncing: Boolean,
    lastSyncAt: Long?,
    liveOn: Boolean,
    onSync: () -> Unit,
    categories: List<CategoryEntity> = emptyList(),
    categoryFilter: Long? = null,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val catNames = categories.associate { it.id to it.name }
    val submit: () -> Unit = {
        if (input.isNotBlank() && vm.addManual(input)) {
            input = ""
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSync, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (syncing) "Syncing…" else "Sync SMS")
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (lastSyncAt == null) "Never synced" else "Last sync ${fmtDateTime(lastSyncAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (liveOn) "Live listening on" else "Live listening off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Add by typing") },
                    placeholder = { Text("lunch 45 aed") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                IconButton(onClick = { submit() }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                TextButton(onClick = vm::toggleAllMonths, modifier = Modifier.weight(1f)) {
                    Text(month?.format(monthFmt) ?: "All months", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = vm::nextMonth) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = cardFilter == null, onClick = { vm.selectCard(null) }, label = { Text("All cards") }) }
                items(cards.filter { it.countInSpending || it.cardKey == cardFilter }, key = { it.cardKey }) { c ->
                    FilterChip(selected = cardFilter == c.cardKey, onClick = { vm.selectCard(c.cardKey) }, label = { Text(c.nickname ?: c.cardKey) })
                }
            }
        }
        if (categoryFilter != null) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = { vm.selectCategory(null) },
                        label = { Text("Category: ${catNames[categoryFilter] ?: "?"}  ✕") },
                    )
                }
            }
        }
        item {
            val spend = spendingTotal(txns, excluded)
            // Card payments: PAYMENT SMS from the card's bank, plus transfers from your account to your own cards.
            val payments = txns.filter { it.type == TxnType.PAYMENT.name || it.counterpartyKey != null }.sumOf { it.amountAedMinor ?: 0L }
            val notCounted = txns.count { it.cardKey != null && it.cardKey in excluded }
            val selected = cards.firstOrNull { it.cardKey == cardFilter }
            val moneyIn = txns.filter { it.type == TxnType.TRANSFER_IN.name || (selected?.cardType == CardTypes.ACCOUNT && it.type == TxnType.REFUND.name) }
                .sumOf { it.amountAedMinor ?: 0L }
            val moneyOut = txns.filter { it.type == TxnType.TRANSFER_OUT.name || (selected?.cardType == CardTypes.ACCOUNT && it.type == TxnType.PURCHASE.name) }
                .sumOf { it.amountAedMinor ?: 0L }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (selected != null && selected.cardType == CardTypes.ACCOUNT) {
                    // Bank account view: money in / out first, spending second.
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text("Money in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(fmtMoney(moneyIn), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text("Money out", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(fmtMoney(moneyOut), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Column {
                            Text("Net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val net = moneyIn - moneyOut
                            Text((if (net >= 0) "+" else "−") + fmtMoney(kotlin.math.abs(net)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val spendNote = if (selected.countInSpending) "Counted as spending: ${fmtMoney(spend)} (EMIs, bills, ATM)"
                    else "Not counted in spending: switch on in Cards to include EMIs, bills and ATM"
                    Text("${txns.size} transactions · $spendNote", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Spent ${fmtMoney(spend)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val extra = mutableListOf("${txns.size} transactions")
                    if (payments > 0) extra += "card payments ${fmtMoney(payments)}"
                    if (moneyIn > 0 || moneyOut > 0) extra += "account in ${fmtMoney(moneyIn)} / out ${fmtMoney(moneyOut)}"
                    if (notCounted > 0) extra += "switched off on Cards tab (not in total)"
                    Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
        if (txns.isEmpty()) {
            item {
                Text(
                    if (lastSyncAt == null) "Tap Sync SMS to import your bank messages, or add one above." else "No transactions for this filter.",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(txns, key = { it.id }) { t ->
            TransactionRow(vm, t, counted = t.cardKey == null || t.cardKey !in excluded, categories = categories, catNames = catNames)
        }
    }
}

@Composable
private fun TransactionRow(
    vm: MainViewModel,
    t: TransactionEntity,
    counted: Boolean,
    categories: List<CategoryEntity>,
    catNames: Map<Long, String>,
) {
    var expanded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        CategoryPickerDialog(
            merchant = t.merchant,
            current = t.categoryId,
            categories = categories,
            onPick = { id, all -> vm.setCategory(t, id, all); picking = false },
            onCreate = { name, all -> vm.addCategoryAndSet(t, name, all); picking = false },
            onDismiss = { picking = false },
        )
    }
    val spendType = t.type == TxnType.PURCHASE.name || t.type == TxnType.REFUND.name
    var raw by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) { if (expanded && raw == null && t.smsId != null) raw = vm.rawSms(t.smsId) }

    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val card = t.cardKey ?: if (t.cardLast4 != null) "${t.bank} ·${t.cardLast4}" else t.bank
                val tags = mutableListOf(card, fmtDateTime(t.timestamp))
                if (t.type == TxnType.REFUND.name) tags += "refund"
                if (t.type == TxnType.PAYMENT.name) tags += "card payment"
                if (t.type == TxnType.TRANSFER_IN.name) tags += "money in"
                if (t.type == TxnType.TRANSFER_OUT.name) tags += if (t.counterpartyKey != null) "card payment" else "money out"
                if (spendType) t.categoryId?.let { c -> catNames[c]?.let { tags += it } }
                if (t.source == "MANUAL") tags += "typed"
                if (!counted) tags += "not counted"
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val sign = when (t.type) {
                    TxnType.PURCHASE.name, TxnType.TRANSFER_OUT.name -> ""
                    else -> "+"
                }
                val color = when {
                    !counted -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.type == TxnType.PURCHASE.name || t.type == TxnType.TRANSFER_OUT.name -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(sign + fmtMoney(t.amountMinor, t.currency), color = color, fontWeight = FontWeight.Medium)
                if (t.currency != "AED") {
                    Text(
                        t.amountAedMinor?.let { "≈ " + fmtMoney(it) } ?: "no AED rate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            t.availableLimitMinor?.let { Text("Available limit/balance after: ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall) }
            if (spendType) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Category: ${t.categoryId?.let { catNames[it] } ?: "none"}" + if (t.categoryUserSet) " (set by you)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) { Text("Change") }
                }
            }
            t.ruleId?.let { Text("Rule: $it", style = MaterialTheme.typography.bodySmall) }
            raw?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                }
            }
            TextButton(onClick = { vm.deleteTransaction(t) }) { Text("Delete") }
        }
    }
    HorizontalDivider()
}

// ----------------------------------------------------------------- cards tab

@Composable
fun CardsScreen(
    summaries: List<CardSummary>,
    month: YearMonth?,
    onOpenCard: (String) -> Unit,
    onToggleCounted: (String, Boolean) -> Unit,
) {
    val monthLabel = (month ?: YearMonth.now()).format(monthFmt)
    val withLimit = summaries.filter { it.card.cardType == CardTypes.CREDIT && it.card.creditLimitMinor != null && it.latestBalanceMinor != null }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (summaries.isEmpty()) {
            item { Text("Cards appear here after your first Sync.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (withLimit.isNotEmpty()) {
            item { BalancesPanel(withLimit) }
        } else if (summaries.any { it.card.cardType == CardTypes.CREDIT }) {
            item {
                Text(
                    "Tip: open a credit card and enter its limit to see balances side by side with utilisation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(summaries, key = { it.card.cardKey }) { s ->
            Card(Modifier.fillMaxWidth().clickable { onOpenCard(s.card.cardKey) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.card.nickname ?: s.card.cardKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                typeLabel(s.card.cardType) + if (s.card.nickname != null) " · ${s.card.cardKey}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Switch(checked = s.card.countInSpending, onCheckedChange = { onToggleCounted(s.card.cardKey, it) })
                            Text("Show & count", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    CardFigures(s, monthLabel)
                    DueLines(s.due)
                }
            }
        }
    }
}

/** Credit cards side by side: available, limit and utilisation. */
@Composable
private fun BalancesPanel(cards: List<CardSummary>) {
    val totalLimit = cards.sumOf { it.card.creditLimitMinor ?: 0L }
    val totalAvail = cards.sumOf { it.latestBalanceMinor ?: 0L }
    val overall = Utilisation(totalLimit, totalAvail)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Balances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Available ${fmtMoney(totalAvail)} of ${fmtMoney(totalLimit)} · ${overall.percent}% used",
                style = MaterialTheme.typography.bodyMedium,
            )
            cards.forEach { s ->
                val u = Utilisation(s.card.creditLimitMinor ?: 0L, s.latestBalanceMinor ?: 0L)
                Column {
                    Row {
                        Text(s.card.nickname ?: s.card.cardKey, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${u.percent}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    MeterBar(fraction = u.percent / 100f)
                    Text(
                        "Available ${fmtMoney(u.availableMinor)} · used ${fmtMoney(u.usedMinor)} of ${fmtMoney(u.limitMinor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardFigures(s: CardSummary, monthLabel: String) {
    if (s.card.cardType == CardTypes.ACCOUNT) {
        Text("$monthLabel: in ${fmtMoney(s.monthInMinor)} · out ${fmtMoney(s.monthOutMinor)} · ${s.monthTxnCount} txns")
    } else {
        val suffix = if (s.card.countInSpending) "" else " (not in totals)"
        Text("$monthLabel: ${fmtMoney(s.monthSpendAedMinor)} · ${s.monthTxnCount} txns$suffix")
    }
    if (s.monthPaidInMinor > 0) {
        Text("Payments received: ${fmtMoney(s.monthPaidInMinor)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    s.latestBalanceMinor?.let {
        val label = if (s.card.cardType == CardTypes.ACCOUNT) "Balance" else "Available"
        Text("$label (latest SMS): ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun dueStateText(d: CardDue): String = when (d.status.state) {
    DueState.PAID -> "Paid in full"
    DueState.MIN_PAID -> "Minimum paid · ${fmtMoney(d.status.remainingMinor, d.statement.currency)} still to pay"
    DueState.OVERDUE -> "Overdue by ${-d.status.daysLeft} days"
    DueState.NOTHING_DUE -> "Nothing due"
    DueState.UNPAID -> when {
        d.status.daysLeft == 0L -> "Due today"
        d.status.daysLeft == 1L -> "Due tomorrow"
        else -> "Due in ${d.status.daysLeft} days"
    }
}

@Composable
private fun DueLines(d: CardDue?) {
    d ?: return
    val st = d.statement
    Spacer(Modifier.height(8.dp))
    Text("Statement balance ${fmtMoney(st.balanceMinor, st.currency)}", style = MaterialTheme.typography.bodyMedium)
    st.minimumDueMinor?.let { Text("Minimum due ${fmtMoney(it, st.currency)}", style = MaterialTheme.typography.bodyMedium) }
    Text("Due ${fmtEpochDay(st.dueDateEpochDay)}", style = MaterialTheme.typography.bodyMedium)
    if (d.status.paidMinor > 0) {
        Text("Paid so far ${fmtMoney(d.status.paidMinor, st.currency)}", style = MaterialTheme.typography.bodyMedium)
    }
    val attention = d.status.state == DueState.UNPAID || d.status.state == DueState.OVERDUE
    Text(
        dueStateText(d),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/** Thin horizontal meter: filled part in the primary colour on a muted track, 4dp rounded ends. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Card profile (Phase 2): nickname, limit, statement/due day, reminders, utilisation, statement status, payments. */
@Composable
fun CardDetailScreen(
    summary: CardSummary?,
    onSetType: (CardType) -> Unit,
    onToggleCounted: (Boolean) -> Unit,
    onShowTransactions: () -> Unit,
    onSaveProfile: (nickname: String, limit: String, statementDay: String, dueDay: String, reminders: Boolean) -> Unit,
) {
    if (summary == null) {
        Text("Card not found.", Modifier.padding(24.dp))
        return
    }
    val c = summary.card
    var nickname by rememberSaveable(c.cardKey) { mutableStateOf(c.nickname ?: "") }
    var limit by rememberSaveable(c.cardKey) { mutableStateOf(c.creditLimitMinor?.let { Money.fromMinor(it).toPlainString() } ?: "") }
    var statementDay by rememberSaveable(c.cardKey) { mutableStateOf(c.statementDay?.toString() ?: "") }
    var dueDay by rememberSaveable(c.cardKey) { mutableStateOf(c.dueDay?.toString() ?: "") }
    var reminders by rememberSaveable(c.cardKey) { mutableStateOf(c.remindersEnabled) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(c.nickname ?: c.cardKey, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(typeLabel(c.cardType) + " · " + c.cardKey, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // --- status
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    CardFigures(summary, "This month")
                    if (c.cardType == CardTypes.CREDIT && c.creditLimitMinor != null && summary.latestBalanceMinor != null) {
                        val u = Utilisation(c.creditLimitMinor, summary.latestBalanceMinor)
                        Spacer(Modifier.height(8.dp))
                        Text("Utilisation ${u.percent}% · outstanding ${fmtMoney(u.usedMinor)}", style = MaterialTheme.typography.bodyMedium)
                        MeterBar(u.percent / 100f)
                    }
                    DueLines(summary.due)
                }
            }
        }
        summary.due?.let { d ->
            if (d.payments.isNotEmpty()) {
                item { Text("Payments to this card", style = MaterialTheme.typography.titleSmall) }
                items(d.payments.sortedByDescending { it.date }.take(12)) { p ->
                    Row {
                        Text(p.date.format(dateFmt), modifier = Modifier.weight(1f))
                        Text(fmtMoney(p.amountMinor, d.statement.currency), fontWeight = FontWeight.Medium)
                    }
                    Text(
                        if (p.fromBankSms) "confirmed by the card's bank" else "transfer from your account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // --- settings
        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Card type", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = c.cardType == CardTypes.CREDIT, onClick = { onSetType(CardType.CREDIT) }, label = { Text("Credit") })
                FilterChip(selected = c.cardType == CardTypes.DEBIT, onClick = { onSetType(CardType.DEBIT) }, label = { Text("Debit") })
                FilterChip(selected = c.cardType == CardTypes.ACCOUNT, onClick = { onSetType(CardType.ACCOUNT) }, label = { Text("Account") })
            }
            Text(
                "Changing the type resets \"Show & count\" to its default (on for credit, off for debit and accounts).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show & count", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "When off, this card is hidden from the Transactions tab and left out of all totals and charts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = c.countInSpending, onCheckedChange = onToggleCounted)
            }
        }
        item {
            Text("Profile", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (c.cardType == CardTypes.CREDIT) {
                OutlinedTextField(
                    limit, { limit = it }, label = { Text("Credit limit (AED)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        statementDay, { statementDay = it }, label = { Text("Statement day") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        dueDay, { dueDay = it }, label = { Text("Due day") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Due-date reminders for this card", modifier = Modifier.weight(1f))
                    Switch(checked = reminders, onCheckedChange = { reminders = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSaveProfile(nickname, limit, statementDay, dueDay, reminders) }) { Text("Save") }
                OutlinedButton(onClick = onShowTransactions) { Text("Show transactions") }
            }
        }
    }
}

// ---------------------------------------------------------------- review tab

@Composable
fun ReviewScreen(
    failed: List<SmsEntity>,
    counts: Map<String, Int>,
    onDismiss: (Long) -> Unit,
    onReparse: () -> Unit,
    onShare: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column {
                Text(
                    "Bank SMS stored: ${counts.values.sum()} · transactions ${counts["TRANSACTION"] ?: 0} · " +
                        "statements ${counts["STATEMENT"] ?: 0} · ignored ${counts["IGNORED"] ?: 0} · dismissed ${counts["DISMISSED"] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "These bank SMS contain an amount but no rule matched. Add a rule in BankRules.kt, install the new build, then tap Re-parse.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onReparse) { Text("Re-parse all SMS") }
                    OutlinedButton(onClick = onShare, enabled = failed.isNotEmpty()) { Text("Share unparsed SMS") }
                }
            }
        }
        if (failed.isEmpty()) item { Text("Nothing to review.", fontWeight = FontWeight.Medium) }
        items(failed, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${s.bank ?: s.sender} · ${fmtDateTime(s.receivedAt)}", style = MaterialTheme.typography.titleSmall)
                    s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(s.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { onDismiss(s.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

// -------------------------------------------------------------- settings tab

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    liveOn: Boolean,
    lastSyncAt: Long?,
    onLiveToggle: (Boolean) -> Unit,
    onShowSamsungTip: () -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenRates: () -> Unit,
    biometricAvailable: Boolean,
    versionName: String,
) {
    val remindersOn by vm.remindersOn.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val bioOn by vm.biometricOn.collectAsStateWithLifecycle()
    val timeout by vm.lockTimeoutMs.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    if (settingPin) {
        SetPinDialog(onSet = { pin -> if (vm.enableLock(pin)) settingPin = false }, onDismiss = { settingPin = false })
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Restore a backup?") },
            text = { Text("SMS, typed entries and settings from the backup are added to what's already here (nothing is deleted). Then everything is re-parsed.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; onImport() }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SettingSwitch("Live listening", "Capture bank SMS the moment they arrive. When off, nothing runs in the background: use Sync instead.", liveOn, onLiveToggle) }
        if (liveOn) item { TextButton(onClick = onShowSamsungTip) { Text("Samsung: keep the app from sleeping") } }
        item {
            SettingSwitch(
                "Due-date reminders",
                "Notification 3 days before, 1 day before and on the due day while the minimum isn't paid. Checked twice a day in the background while on.",
                remindersOn, onRemindersToggle,
            )
        }
        item { HorizontalDivider() }
        item {
            SettingSwitch("App lock", "PIN (4–8 digits), with fingerprint/face if available. Locks again after the app has been in the background.", lockOn) { on ->
                if (on) settingPin = true else vm.disableLock()
            }
        }
        if (lockOn) {
            item {
                Text("Lock again after", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0L to "Immediately", 60_000L to "1 min", 300_000L to "5 min", 900_000L to "15 min").forEach { (ms, label) ->
                        FilterChip(selected = timeout == ms, onClick = { vm.setLockTimeout(ms) }, label = { Text(label) })
                    }
                }
            }
            if (biometricAvailable) item { SettingSwitch("Use fingerprint / face", "PIN still works as a fallback.", bioOn) { vm.setBiometric(it) } }
            item { TextButton(onClick = { settingPin = true }) { Text("Change PIN") } }
        }
        item { HorizontalDivider() }
        item {
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                if (lastSyncAt == null) "Never synced. The first Sync imports all existing bank SMS."
                else "Last successful sync: ${fmtDateTime(lastSyncAt)}. The next Sync reads from there onward.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { vm.resetSyncPointer() }) { Text("Re-read inbox") }
                OutlinedButton(onClick = { vm.reparseAll() }) { Text("Re-parse all SMS") }
            }
        }
        item {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "A .zip of CSV files: raw SMS, typed entries, cards, categories, learned rules, goals and rates. all_transactions.csv opens in Excel.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onExport) { Text("Export backup") }
                OutlinedButton(onClick = { confirmImport = true }) { Text("Restore backup") }
            }
        }
        item {
            Text("Currencies", style = MaterialTheme.typography.titleMedium)
            Text("All reports are in AED. Foreign amounts use your exchange rates.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenRates, modifier = Modifier.padding(top = 6.dp)) { Text("Exchange rates") }
        }
        item { HorizontalDivider() }
        item {
            Text(
                "Card Tracker $versionName · all data stays on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
